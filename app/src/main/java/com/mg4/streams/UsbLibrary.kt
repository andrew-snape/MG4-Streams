package com.mg4.streams

import android.content.Context
import android.os.Environment
import java.io.File

/** A folder or a media file on the USB stick, with what the grid shows for it. */
data class UsbEntry(
    val file: File,
    val isFolder: Boolean,
    /** Tile text: folder name, or "S01E02 · Title". */
    val label: String,
    /** Poster (folders) or thumbnail (files) image as "file://" or "frame://" address. */
    val image: String?,
    val episode: EpisodeInfo? = null,
)

/** What a file name (and its folders) say about an episode. Any part may be missing. */
data class EpisodeInfo(val show: String?, val season: Int?, val episode: Int?, val title: String?) {
    /** "S01E02" or "E02", or null. */
    val code: String?
        get() = when {
            season != null && episode != null -> "S%02dE%02d".format(season, episode)
            episode != null -> "E%02d".format(episode)
            else -> null
        }
}

/**
 * Reads USB sticks: finds them, lists folders and media files in natural order (E2 before E10),
 * and reads local metadata (episode numbers from names, Kodi/Plex .nfo files, poster images).
 * Read-only: nothing is ever written to the stick. File access blocks, so call it off the UI
 * thread. The parsing helpers are pure.
 */
object UsbLibrary {

    val VIDEO = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "m2ts", "mts", "avi", "flv", "mpg", "mpeg", "3gp")
    val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav")
    private val SUBTITLES = listOf("srt", "ass", "ssa", "vtt")
    private val POSTERS = listOf("poster", "folder", "cover", "show", "season")
    private val IMAGE_EXT = listOf("jpg", "jpeg", "png")
    /** System folders, never shown. */
    private val HIDDEN = setOf("android", "lost.dir", "system volume information", "\$recycle.bin", "found.000")

    fun isMedia(file: File) = file.extension.lowercase() in VIDEO || file.extension.lowercase() in AUDIO
    fun isVideo(file: File) = file.extension.lowercase() in VIDEO

    /**
     * Mounted removable volumes (USB sticks, SD cards), not the internal storage. Uses the app's
     * external dirs (one per mounted volume) plus /storage, for sticks the app dirs miss.
     */
    fun roots(context: Context): List<File> {
        val internal = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
        val fromApp = context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val path = dir.absolutePath
            val cut = path.indexOf("/Android/data/")
            if (cut > 0) File(path.substring(0, cut)) else null
        }
        val fromStorage = File("/storage").listFiles().orEmpty()
            .filter { it.name != "emulated" && it.name != "self" && it.isDirectory }
        return (fromApp + fromStorage)
            .filter { runCatching { it.canonicalPath != internal && it.canRead() && it.list() != null }.getOrDefault(false) }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
    }

    /** Folders, then media files, in natural order. Hidden and system folders are skipped. */
    fun list(dir: File): List<UsbEntry> {
        val all = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        val folders = all.filter { it.isDirectory && it.name.lowercase() !in HIDDEN }
            .sortedWith(compareBy(NATURAL) { it.name })
            .map { UsbEntry(it, true, it.name, posterFor(it)?.let { p -> "file://${p.path}" } ?: framePlaceholder(it)) }
        val files = all.filter { it.isFile && isMedia(it) }
            .sortedWith(compareBy(NATURAL) { it.name })
            .map { fileEntry(it) }
        return folders + files
    }

    fun fileEntry(file: File): UsbEntry {
        val info = episodeInfo(file)
        val nfo = readNfoTitle(File(file.parentFile, file.nameWithoutExtension + ".nfo"))
        val title = nfo ?: info.title
        val label = listOfNotNull(info.code, title).joinToString(" · ").ifEmpty { cleanName(file.nameWithoutExtension) }
        val thumb = IMAGE_EXT.asSequence().flatMap { ext ->
            sequenceOf(File(file.parentFile, "${file.nameWithoutExtension}-thumb.$ext"), File(file.parentFile, "${file.nameWithoutExtension}.$ext"))
        }.firstOrNull { it.isFile }
        val image = thumb?.let { "file://${it.path}" } ?: if (isVideo(file)) "frame://${file.path}" else null
        return UsbEntry(file, false, label, image, info.copy(title = title))
    }

    /** A folder without a poster gets a frame from its first video (made on demand). */
    private fun framePlaceholder(dir: File): String = "frame://${dir.path}"

    fun posterFor(dir: File): File? {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        return POSTERS.asSequence().mapNotNull { base ->
            files.firstOrNull { it.nameWithoutExtension.equals(base, true) && it.extension.lowercase() in IMAGE_EXT }
        }.firstOrNull()
    }

    /** The first video in [dir] or its subfolders (two levels), for a folder thumbnail. */
    fun firstVideo(dir: File, depth: Int = 2): File? {
        val items = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.sortedWith(compareBy(NATURAL) { it.name })
        items.firstOrNull { it.isFile && isVideo(it) }?.let { return it }
        if (depth == 0) return null
        return items.asSequence().filter { it.isDirectory }.mapNotNull { firstVideo(it, depth - 1) }.firstOrNull()
    }

    /**
     * What plays after [file]: the rest of its folder, and if the folder is a season ("Season 2",
     * "S02"), the following seasons of the same show too.
     */
    fun playlistFor(file: File): List<File> {
        val folder = file.parentFile ?: return listOf(file)
        fun media(dir: File) = dir.listFiles().orEmpty()
            .filter { it.isFile && isMedia(it) && !it.name.startsWith(".") }
            .sortedWith(compareBy(NATURAL) { it.name })
        if (seasonNumber(folder.name) == null) return media(folder).ifEmpty { listOf(file) }
        val show = folder.parentFile ?: return media(folder)
        val seasons = show.listFiles().orEmpty()
            .filter { it.isDirectory && seasonNumber(it.name) != null }
            .sortedWith(compareBy<File> { seasonNumber(it.name) }.thenBy(NATURAL) { it.name })
        return seasons.flatMap { media(it) }.ifEmpty { listOf(file) }
    }

    /** Subtitle files next to [video]: "Name.srt", "Name.en.srt"... */
    fun subtitlesFor(video: File): List<File> {
        val base = video.nameWithoutExtension
        return video.parentFile?.listFiles().orEmpty().filter { f ->
            f.isFile && f.extension.lowercase() in SUBTITLES &&
                (f.nameWithoutExtension == base || f.nameWithoutExtension.startsWith("$base."))
        }.sortedBy { it.name.length }
    }

    /** "<title>" from a Kodi/Plex .nfo file, if there is one. */
    fun readNfoTitle(nfo: File): String? {
        if (!nfo.isFile || nfo.length() > 256 * 1024) return null
        return runCatching { nfoTitle(nfo.readText()) }.getOrNull()
    }

    fun nfoTitle(xml: String): String? =
        Regex("<title>\\s*(.*?)\\s*</title>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&apos;", "'")?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.takeIf { it.isNotBlank() }

    // ---- Pure parsing ----

    private val SXE = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})(?:[ ._-]?E\\d{1,3})*")
    private val NXN = Regex("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b")
    private val EP_ONLY = Regex("(?i)\\b(?:E|Ep|Episode)[ ._-]?(\\d{1,3})\\b")
    private val SEASON = Regex("(?i)^(?:season|series|staffel|saison)[ ._-]*(\\d{1,2})$|^S(\\d{1,2})$")
    private val JUNK = Regex(
        "(?i)[ ._-]*[\\[(]?\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr|web[ ._-]?dl|webrip|web|bluray|blu[ ._-]?ray|brrip|bdrip|hdtv|dvdrip|x264|x265|h[ ._]?264|h[ ._]?265|hevc|aac|ac3|eac3|ddp?5[ ._]?1|dts|10bit|repack|amzn|dsnp)\\b.*$",
    )

    /** "Season 2", "Series 2", "S02" -> 2. */
    fun seasonNumber(folderName: String): Int? =
        SEASON.find(folderName.trim())?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }

    /** Show, season, episode and title from the file name, filling gaps from its folders. */
    fun episodeInfo(file: File): EpisodeInfo {
        val folder = file.parentFile
        val folderSeason = folder?.name?.let(::seasonNumber)
        val showFolder = if (folderSeason != null) folder?.parentFile?.name else folder?.name
        return parseName(file.nameWithoutExtension, folderSeason, showFolder)
    }

    fun parseName(name: String, folderSeason: Int? = null, folderShow: String? = null): EpisodeInfo {
        val match = SXE.find(name) ?: NXN.find(name)
        if (match != null) {
            val show = cleanName(name.substring(0, match.range.first)).ifEmpty { null }
            val title = cleanName(name.substring(match.range.last + 1)).ifEmpty { null }
            return EpisodeInfo(show ?: folderShow, match.groupValues[1].toInt(), match.groupValues[2].toInt(), title)
        }
        val ep = EP_ONLY.find(name)
        if (ep != null) {
            val title = cleanName(name.substring(ep.range.last + 1)).ifEmpty { null }
            return EpisodeInfo(folderShow, folderSeason, ep.groupValues[1].toInt(), title)
        }
        // "01 - Title" inside a season folder.
        val lead = Regex("^(\\d{1,3})[ ._-]+(.*)$").find(name.trim())
        if (lead != null && folderSeason != null) {
            return EpisodeInfo(folderShow, folderSeason, lead.groupValues[1].toInt(), cleanName(lead.groupValues[2]).ifEmpty { null })
        }
        return EpisodeInfo(folderShow, folderSeason, null, null)
    }

    /** Dots and underscores to spaces, quality tags removed, edge dashes trimmed. */
    fun cleanName(text: String): String {
        var t = text
        if (!t.contains(' ')) t = t.replace('.', ' ').replace('_', ' ')
        t = t.replace(JUNK, "")
        return t.trim().trim('-', '–', '.', '_', ' ', '[', '(').trim()
    }

    /** Natural order: numbers compare by value, letters ignore case ("E2" < "E10"). */
    val NATURAL: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
