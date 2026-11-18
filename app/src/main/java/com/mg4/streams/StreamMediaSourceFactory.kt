package com.mg4.streams

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable

/**
 * Builds each stream with that channel's own HTTP headers (user agent and referrer from the
 * playlist), so they go on every request: master playlist, variant playlists and segments.
 * Headers travel in the media item's metadata extras ([EXTRA_USER_AGENT], [EXTRA_REFERRER]).
 *
 * HLS streams list their variants lowest bitrate first (see [LowestFirstParserFactory]), so a TV
 * channel heard with video off (driving) downloads the smallest variant, not a full-HD one.
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
        Tailnet.ensureSelector() // Android may have reset it; the player asks it for every request
        val extras = mediaItem.mediaMetadata.extras
        val agent = extras?.getString(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }
        val referrer = extras?.getString(EXTRA_REFERRER)?.takeIf { it.isNotBlank() }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            // Generous: on the car's slow connection a busy moment shouldn't end the stream.
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        agent?.let { http.setUserAgent(it) }
        referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val dataSource = DefaultDataSource.Factory(context, http)

        val local = mediaItem.localConfiguration
        val isHls = local != null &&
            Util.inferContentTypeForUriAndMimeType(local.uri, local.mimeType) == C.CONTENT_TYPE_HLS
        if (isHls) {
            val hls = HlsMediaSource.Factory(dataSource)
                .setPlaylistParserFactory(LowestFirstParserFactory())
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
            drmProvider?.let { hls.setDrmSessionManagerProvider(it) }
            return hls.createMediaSource(mediaItem)
        }

        val factory = DefaultMediaSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        drmProvider?.let { factory.setDrmSessionManagerProvider(it) }
        return factory.createMediaSource(mediaItem)
    }

    companion object {
        const val EXTRA_USER_AGENT = "mg4.userAgent"
        const val EXTRA_REFERRER = "mg4.referrer"
        const val EXTRA_TVG_ID = "mg4.tvgId"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

/**
 * Re-orders a multivariant (master) playlist's variants by bitrate, lowest first.
 *
 * With video turned off (car not in P), ExoPlayer doesn't pick a variant: it keeps the first one
 * listed. Many TV playlists list a 720p variant first (Seven lists a 1.7 Mbps one), which on the
 * car's connection wastes data and stalls. With this order it keeps the smallest variant instead.
 * When video is on, ExoPlayer's adaptive selection picks the variant as before, so order doesn't
 * matter there.
 */
@UnstableApi
class LowestFirstParserFactory(
    private val inner: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        reorder(inner.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        reorder(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun reorder(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            when (val playlist = parser.parse(uri, input)) {
                is HlsMultivariantPlaylist -> lowestFirst(playlist)
                else -> playlist
            }
        }

    companion object {
        private fun hasVideoHint(format: Format): Boolean =
            format.width > 0 || format.height > 0 ||
                MimeTypes.getVideoMediaMimeType(format.codecs) != null

        fun lowestFirst(p: HlsMultivariantPlaylist): HlsMultivariantPlaylist {
            if (p.variants.size < 2) return p
            // Only when every variant says it has video (a RESOLUTION or a video codec). Without
            // those hints the lowest variant may be audio-only, and preparing from it would leave
            // the stream with no video track even in P. Stable sort; no bitrate goes last.
            if (!p.variants.all { hasVideoHint(it.format) }) return p
            val sorted = p.variants.sortedBy { v ->
                v.format.bitrate.takeIf { it != Format.NO_VALUE }?.toLong() ?: Long.MAX_VALUE
            }
            if (sorted == p.variants) return p
            return HlsMultivariantPlaylist(
                p.baseUri,
                p.tags,
                sorted,
                p.videos,
                p.audios,
                p.subtitles,
                p.closedCaptions,
                p.muxedAudioFormat,
                p.muxedCaptionFormats,
                p.hasIndependentSegments,
                p.variableDefinitions,
                p.sessionKeyDrmInitData,
            )
        }
    }
}
