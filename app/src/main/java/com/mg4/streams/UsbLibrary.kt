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
     * Mounted removable volumes (USB sticks, SD cards), not the internal storage. Read from the
     * StorageManager, plus a scan of /storage. Doesn't use getExternalFilesDirs: on Android 9 that
     * creates Android/data/<app> folders on every stick, and nothing may be written to the stick.
     */
    fun roots(context: Context): List<File> {
        val internal = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val fromManager = runCatching {
            manager?.storageVolumes.orEmpty()
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { volume ->
                    // StorageVolume.getPath() is hidden on Android 9; platform-signed apps may call it.
                    runCatching { volume.javaClass.getMethod("getPath").invoke(volume) as? String }.getOrNull()?.let(::File)
                }
        }.getOrDefault(emptyList())
        val fromStorage = File("/storage").listFiles().orEmpty()
            .filter { it.name != "emulated" && it.name != "self" && it.isDirectory }
        return (fromManager + fromStorage)
            .filter { runCatching { it.canonicalPath != internal && it.canRead() && it.list() != null }.getOrDefault(false) }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
    }

    /** Folders, then media files, in natural order. Hidden and system folders are skipped. */
    fun list(dir: File): List<UsbEntry> {
        val all = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        val folders = all.filter { it.isDirectory && it.name.lowercase() !in HIDDEN }
            .sortedWith(compareBy(NATURAL) { it.name })
            .map { UsbEntry(it, true, it.name, posterFor(it)?.let { p -> "file://${p.path}" } ?: framePlaceholder(it)) }
        val names = all.mapTo(HashSet()) { it.name }
        val files = all.filter { it.isFile && isMedia(it) }
            .sortedWith(compareBy(NATURAL) { it.name })
            .map { fileEntry(it, names) }
        return folders + files
    }

    /**
     * [names]: the file names in [file]'s folder, if already listed, so checking for .nfo and
     * thumbnail files costs no extra disk access (slow on a USB stick).
     */
    fun fileEntry(file: File, names: Set<String>? = null): UsbEntry {
        val folder = file.parentFile
        fun exists(name: String) = if (names != null) name in names else File(folder, name).isFile
        val info = episodeInfo(file)
        val base = file.nameWithoutExtension
        val nfo = if (exists("$base.nfo")) readNfoTitle(File(folder, "$base.nfo")) else null
        val title = nfo ?: info.title
        val label = listOfNotNull(info.code, title).joinToString(" · ").ifEmpty { cleanName(base) }
        val thumb = IMAGE_EXT.asSequence().flatMap { ext -> sequenceOf("$base-thumb.$ext", "$base.$ext") }
            .firstOrNull { exists(it) }?.let { File(folder, it) }
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

    /** One playlist item: the entry and its subtitle file, if any. */
    data class Item(val entry: UsbEntry, val subtitle: File?)

    /** [playlistFor] with entries and subtitles, listing each folder once. */
    fun playlistItems(file: File): List<Item> {
        val files = playlistFor(file)
        val listings = files.mapNotNull { it.parentFile }.distinct()
            .associateWith { dir -> dir.list()?.toHashSet() ?: hashSetOf() }
        return files.map { f ->
            val names = listings[f.parentFile].orEmpty()
            Item(fileEntry(f, names), subtitleIn(f, names))
        }
    }

    /** The subtitle file for [video] among [names] (its folder): "Name.srt", then "Name.en.srt"... */
    fun subtitleIn(video: File, names: Set<String>): File? {
        val base = video.nameWithoutExtension
        val match = names.filter { n ->
            val ext = n.substringAfterLast('.', "").lowercase()
            ext in SUBTITLES && (n.substringBeforeLast('.') == base || n.startsWith("$base."))
        }.minByOrNull { it.length } ?: return null
        return File(video.parentFile, match)
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
            ?.replace(Regex("^<!\\[CDATA\\[(.*)]]>$", RegexOption.DOT_MATCHES_ALL), "$1")?.trim()
            ?.replace("&amp;", "&")?.replace("&apos;", "'")?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.takeIf { it.isNotBlank() }

    // ---- Pure parsing ----

    private val SXE = Regex("(?i)(?<![A-Za-z0-9])S(\\d{1,2})[ ._-]?E(\\d{1,3})(?:[ ._-]?E\\d{1,3})*")
    private val NXN = Regex("(?i)(?<![A-Za-z0-9])(\\d{1,2})x(\\d{2,3})(?![A-Za-z0-9])")
    private val EP_ONLY = Regex("(?i)(?<![A-Za-z0-9])(?:E|Ep|Episode)[ ._-]?(\\d{1,3})(?![0-9])")
    private val SEASON = Regex("(?i)^(?:season|series|staffel|saison)[ ._-]*(\\d{1,2})(?:\\b.*)?$|^S(\\d{1,2})$")
    private val JUNK = Regex(
        "(?i)[ ._-]*[\\[(]?\\b(2160p|1080p|1080i|720p|576p|480p|web[ ._-]?dl|webrip|bluray|blu[ ._-]?ray|brrip|bdrip|hdtv|dvdrip|x264|x265|h[ ._]?264|h[ ._]?265|hevc|aac|ac3|eac3|ddp?5[ ._]?1|10bit|repack|amzn|dsnp)\\b.*$",
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
