package com.mg4.streams

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** One programme in the guide. Times are epoch milliseconds. */
data class Programme(val start: Long, val stop: Long, val title: String)

/** Programmes by channel id (tvg-id), sorted by start time. */
class EpgGuide(private val byChannel: Map<String, List<Programme>>) {

    val isEmpty: Boolean get() = byChannel.isEmpty()

    fun now(channelId: String?, at: Long = System.currentTimeMillis()): Programme? =
        channelId?.let { id -> byChannel[id]?.firstOrNull { it.start <= at && at < it.stop } }

    fun next(channelId: String?, at: Long = System.currentTimeMillis()): Programme? =
        channelId?.let { id -> byChannel[id]?.firstOrNull { it.start > at } }

    /** The latest stop time in the guide, to tell when it is running out. */
    fun lastStop(): Long = byChannel.values.maxOfOrNull { it.lastOrNull()?.stop ?: 0L } ?: 0L

    companion object {
        val EMPTY = EpgGuide(emptyMap())
    }
}

/**
 * Downloads XMLTV guides, keeps only the channels in the playlists and the next day, and caches
 * the result as a small tab-separated file. All methods do I/O: call them on the worker thread.
 */
class EpgStore(private val cacheFile: File) {

    /** Records which guide addresses and channel ids the cache was built for. */
    private val keyFile = File(cacheFile.parentFile, cacheFile.name + ".key")

    private fun keyFor(urls: List<String>, channelIds: Set<String>): String =
        (urls.sorted() + "|" + channelIds.sorted()).joinToString("\n").hashCode().toString()

    fun loadCache(): EpgGuide {
        if (!cacheFile.exists()) return EpgGuide.EMPTY
        val map = HashMap<String, MutableList<Programme>>()
        runCatching {
            cacheFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@forEachLine
                val start = parts[1].toLongOrNull() ?: return@forEachLine
                val stop = parts[2].toLongOrNull() ?: return@forEachLine
                map.getOrPut(parts[0]) { mutableListOf() } += Programme(start, stop, parts[3])
            }
        }
        return EpgGuide(map.mapValues { (_, list) -> list.sortedBy { it.start } })
    }

    /**
     * True if the cache is missing, older than [CACHE_MAX_AGE_MS], ending within 3 hours, or was
     * built for different guide addresses or channels (a new or changed playlist).
     */
    fun needsRefresh(
        guide: EpgGuide,
        urls: List<String>,
        channelIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        !cacheFile.exists() ||
            now - cacheFile.lastModified() > CACHE_MAX_AGE_MS ||
            guide.lastStop() < now + 3 * HOUR_MS ||
            runCatching { keyFile.readText() }.getOrNull() != keyFor(urls, channelIds)

    /**
     * Downloads each guide in [urls], keeps programmes for [channelIds] from one hour ago to a
     * day ahead, and saves the cache. Throws if every download fails.
     */
    fun refresh(urls: List<String>, channelIds: Set<String>): EpgGuide {
        val now = System.currentTimeMillis()
        val from = now - HOUR_MS
        val to = now + 24 * HOUR_MS
        val merged = HashMap<String, MutableList<Programme>>()
        var lastError: Exception? = null
        var anyOk = false
        for (url in urls) {
            try {
                open(url).use { input ->
                    parseXmltv(input, channelIds, from, to).forEach { (id, list) ->
                        merged.getOrPut(id) { mutableListOf() } += list
                    }
                }
                anyOk = true
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anyOk) throw lastError ?: IllegalStateException("No guide address in the playlists")

        val sorted = merged.mapValues { (_, list) -> list.distinctBy { it.start }.sortedBy { it.start } }
        val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        temp.bufferedWriter().use { out ->
            sorted.forEach { (id, list) ->
                list.forEach { p -> out.write("$id\t${p.start}\t${p.stop}\t${p.title}\n") }
            }
        }
        if (!temp.renameTo(cacheFile)) {
            temp.copyTo(cacheFile, overwrite = true)
            temp.delete()
        }
        runCatching { keyFile.writeText(keyFor(urls, channelIds)) }
        return EpgGuide(sorted)
    }

    /** Opens the guide, unzipping it if it is gzip (by content, not by name). */
    private fun open(address: String): InputStream {
        // Closing the returned stream hands the connection back to the pool.
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        val raw = BufferedInputStream(connection.inputStream)
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    private fun parseXmltv(
        input: InputStream,
        channelIds: Set<String>,
        from: Long,
        to: Long,
    ): Map<String, List<Programme>> {
        val result = HashMap<String, MutableList<Programme>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        val id = parser.getAttributeValue(null, "channel")
                        val s = parseTime(parser.getAttributeValue(null, "start"))
                        val e = parseTime(parser.getAttributeValue(null, "stop"))
                        channel = if (id != null && id in channelIds && s != null && e != null &&
                            e > from && s < to
                        ) id else null
                        start = s ?: 0L
                        stop = e ?: 0L
                        title = null
                    }
                    "title" -> if (channel != null && title == null) {
                        title = parser.nextText().replace('\t', ' ').replace('\n', ' ').trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    val id = channel
                    val t = title
                    if (id != null && !t.isNullOrEmpty()) {
                        result.getOrPut(id) { mutableListOf() } += Programme(start, stop, t)
                    }
                    channel = null
                }
            }
            event = parser.next()
        }
        return result
    }

    /** XMLTV time: "20261113010000 +0000". A missing offset is read as UTC. */
    private fun parseTime(value: String?): Long? {
        if (value == null || value.length < 14) return null
        val base = TIME_FORMAT.get()!!.runCatching { parse(value.substring(0, 14))?.time }.getOrNull()
            ?: return null
        val offset = value.substring(14).trim()
        if (offset.length != 5 || (offset[0] != '+' && offset[0] != '-')) return base
        val hours = offset.substring(1, 3).toIntOrNull() ?: return base
        val minutes = offset.substring(3, 5).toIntOrNull() ?: return base
        val millis = (hours * 60 + minutes) * 60_000L
        return if (offset[0] == '+') base - millis else base + millis
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val CACHE_MAX_AGE_MS = 6 * HOUR_MS
        val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        }
    }
}
