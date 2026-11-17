package com.mg4.streams

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * StreamTheWorld (Triton) radio, used by ARN, SCA and Nova stations (GOLD 104.3, KIIS, Triple M...).
 *
 * Playlists often hold the address of one numbered server, for example
 * `https://23203.live.streamtheworld.com/GOLD_104_3AAC.aac`. Triton retires those servers and they
 * then answer HTTP 410 (Gone) or 404, so the station stops working until the playlist is fixed.
 * Triton's load balancer, `playerservices.streamtheworld.com/api/livestream-redirect/<mount>`,
 * redirects to a server that is up now. [FallbackDataSource] switches to it when a numbered server
 * refuses, and remembers the switch for the rest of the session.
 */
object StreamTheWorld {

    private const val REDIRECT_BASE = "https://playerservices.streamtheworld.com/api/livestream-redirect/"
    private val NODE_HOST = Regex("""^[a-z0-9-]+\.live\.streamtheworld\.com$""", RegexOption.IGNORE_CASE)

    /** Numbered-server addresses that refused, mapped to the load balancer address. */
    private val switched = ConcurrentHashMap<String, String>()

    /**
     * The load balancer address for a numbered-server stream address, or null if [url] isn't one.
     * Keeps the mount (`GOLD_104_3AAC.aac`, `GOLD_104_3AAC_SC`, `GOLD_104_3.mp3`) and any query.
     */
    fun redirectFor(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host ?: return null
        if (!NODE_HOST.matches(host)) return null
        val mount = uri.rawPath?.trim('/')?.takeIf { it.isNotEmpty() && '/' !in it } ?: return null
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return REDIRECT_BASE + mount + query
    }

    /** HTTP codes that mean the numbered server no longer carries the station. */
    fun isGone(code: Int): Boolean = code == 404 || code == 410

    internal fun remember(original: String, redirect: String) {
        switched[original] = redirect
    }

    internal fun switchedFor(url: String): String? = switched[url]

    internal fun resetForTest() = switched.clear()
}

/**
 * Opens StreamTheWorld numbered-server addresses through Triton's load balancer when the server
 * answers 404/410. Everything else passes straight through to [inner].
 */
@UnstableApi
class FallbackDataSource(private val inner: HttpDataSource) : DataSource by inner {

    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri.toString()
        StreamTheWorld.switchedFor(original)?.let { return inner.open(dataSpec.withUri(Uri.parse(it))) }
        return try {
            inner.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            val redirect = StreamTheWorld.redirectFor(original)
            if (redirect == null || !StreamTheWorld.isGone(e.responseCode)) throw e
            runCatching { inner.close() }
            val length = inner.open(dataSpec.withUri(Uri.parse(redirect)))
            StreamTheWorld.remember(original, redirect)
            length
        }
    }

    class Factory(private val inner: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = FallbackDataSource(inner.createDataSource())
    }
}
