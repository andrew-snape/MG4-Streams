package com.mg4.streams

import android.net.Uri
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up cover art for a stream title ("Artist - Song") with Apple's public iTunes Search API
 * (no key, no account). Sends only the title text. Blocking: call off the main thread.
 */
object AlbumArt {

    /** term -> artwork URL, or "" for no match, so repeats don't hit the network. */
    private val cache by lazy { LruCache<String, String>(100) }

    fun lookup(title: String): Uri? {
        val term = searchTerm(title) ?: return null
        synchronized(cache) { cache.get(term) }?.let { return it.ifEmpty { null }?.let(Uri::parse) }
        val found = runCatching { fetch(term) }.getOrElse { return null } // network error: try again later
        synchronized(cache) { cache.put(term, found.orEmpty()) }
        return found?.let(Uri::parse)
    }

    private fun fetch(term: String): String? {
        val url = URL(
            "https://itunes.apple.com/search?media=music&entity=song&limit=1&country=AU&term=" +
                URLEncoder.encode(term, "UTF-8"),
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readBytes() }
            val body = String(bytes, 0, minOf(bytes.size, MAX_BYTES), Charsets.UTF_8)
            return artworkFrom(body)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A search term for [title], or null if it doesn't look like a song: songs come as
     * "Artist - Song"; station promos, ads and talk usually don't.
     */
    fun searchTerm(title: String): String? {
        val t = title.trim()
        val dash = Regex("\\s[-–—]\\s").find(t) ?: return null
        val artist = t.substring(0, dash.range.first).trim()
        val song = t.substring(dash.range.last + 1)
            .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "") // (Radio Edit), [Live]...
            .trim()
        if (artist.isEmpty() || song.isEmpty()) return null
        return "$artist $song".take(100)
    }

    /** The first result's 100 px artwork URL from the JSON, at 600 px. */
    fun artworkFrom(json: String): String? {
        val raw = Regex("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val url = raw.replace("\\/", "/")
        if (!url.startsWith("https://")) return null
        return url.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.")
    }

    private const val MAX_BYTES = 256 * 1024
}
