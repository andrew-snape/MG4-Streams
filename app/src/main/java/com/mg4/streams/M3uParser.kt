package com.mg4.streams

import java.net.URLDecoder

/** One playable entry from an M3U / M3U8 playlist. */
data class Channel(
    val name: String,
    val url: String,
    val group: String?,
    val logo: String?,
    /** tvg-id or channel-id, used to match the EPG. */
    val tvgId: String? = null,
    /** From #EXTVLCOPT:http-user-agent or a Kodi-style "|User-Agent=" suffix. */
    val userAgent: String? = null,
    /** From #EXTVLCOPT:http-referrer or a Kodi-style "|Referer=" suffix. */
    val referrer: String? = null,
    /** Which playlist slot (and so which tab) the entry came from. */
    val source: Tab = Tab.RADIO,
)

/** Parsed playlist: the channels plus any EPG addresses from the #EXTM3U header. */
data class Playlist(val channels: List<Channel>, val epgUrls: List<String>)

/**
 * Minimal M3U / M3U8 parser. Reads #EXTINF metadata (name, group-title, tvg-logo, tvg-id),
 * #EXTVLCOPT user-agent and referrer, the header's x-tvg-url / url-tvg, and keeps only http(s)
 * entries. Other directives are ignored.
 */
object M3uParser {

    fun parse(text: String): List<Channel> = parsePlaylist(text).channels

    fun parsePlaylist(text: String): Playlist {
        val channels = mutableListOf<Channel>()
        val epgUrls = linkedSetOf<String>()
        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingId: String? = null
        var pendingAgent: String? = null
        var pendingReferrer: String? = null

        fun clearPending() {
            pendingName = null
            pendingGroup = null
            pendingLogo = null
            pendingId = null
            pendingAgent = null
            pendingReferrer = null
        }

        text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> epgUrls += headerEpgUrls(line)

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val info = line.substringAfter(':', "")
                    pendingName = info.substringAfterLast(',', "").trim().ifEmpty { null }
                    pendingGroup = attribute(info, "group-title")
                    pendingLogo = attribute(info, "tvg-logo")
                    pendingId = attribute(info, "tvg-id") ?: attribute(info, "channel-id")
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim().ifEmpty { null }
                    when (key) {
                        "http-user-agent" -> pendingAgent = value
                        "http-referrer", "http-referer" -> pendingReferrer = value
                    }
                }

                line.startsWith("#") -> Unit

                line.startsWith("http://", ignoreCase = true) ||
                    line.startsWith("https://", ignoreCase = true) -> {
                    // Kodi style: URL|User-Agent=...&Referer=...
                    val url = line.substringBefore('|')
                    val kodi = kodiHeaders(line.substringAfter('|', ""))
                    channels += Channel(
                        name = pendingName ?: url,
                        url = url,
                        group = pendingGroup,
                        logo = pendingLogo,
                        tvgId = pendingId,
                        userAgent = pendingAgent ?: kodi["user-agent"],
                        referrer = pendingReferrer ?: kodi["referer"] ?: kodi["referrer"],
                    )
                    clearPending()
                }

                // Any other entry (rtmp://, a bare path) consumes the pending metadata
                // so it cannot leak onto the next URL.
                else -> clearPending()
            }
        }
        return Playlist(channels, epgUrls.toList())
    }

    /** EPG addresses from an #EXTM3U header line (comma-separated lists allowed). */
    fun headerEpgUrls(line: String): List<String> =
        listOfNotNull(attribute(line, "x-tvg-url"), attribute(line, "url-tvg"))
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }

    private fun kodiHeaders(suffix: String): Map<String, String> {
        if (suffix.isEmpty()) return emptyMap()
        return suffix.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').trim().lowercase()
            val raw = pair.substringAfter('=', "").trim()
            if (key.isEmpty() || raw.isEmpty()) return@mapNotNull null
            val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()
            value.ifEmpty { null }?.let { key to it }
        }.toMap()
    }

    private fun attribute(info: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(info)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
}
