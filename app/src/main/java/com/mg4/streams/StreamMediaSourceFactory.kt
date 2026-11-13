package com.mg4.streams

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Builds each stream with that channel's own HTTP headers (user agent and referrer from the
 * playlist), so they go on every request: master playlist, variant playlists and segments.
 * Headers travel in the media item's metadata extras ([EXTRA_USER_AGENT], [EXTRA_REFERRER]).
 */
@UnstableApi
class StreamMediaSourceFactory(private val context: Context) : MediaSource.Factory {

    private var drmProvider: DrmSessionManagerProvider? = null
    private var loadErrorPolicy: LoadErrorHandlingPolicy =
        DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 5)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drmProvider = provider
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        loadErrorPolicy = policy
        return this
    }

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras = mediaItem.mediaMetadata.extras
        val agent = extras?.getString(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }
        val referrer = extras?.getString(EXTRA_REFERRER)?.takeIf { it.isNotBlank() }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        agent?.let { http.setUserAgent(it) }
        referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        drmProvider?.let { factory.setDrmSessionManagerProvider(it) }
        return factory.createMediaSource(mediaItem)
    }

    companion object {
        const val EXTRA_USER_AGENT = "mg4.userAgent"
        const val EXTRA_REFERRER = "mg4.referrer"
        const val EXTRA_TVG_ID = "mg4.tvgId"
    }
}
