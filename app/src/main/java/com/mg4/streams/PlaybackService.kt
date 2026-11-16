package com.mg4.streams

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import java.io.File
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Owns the ExoPlayer and its media session, so audio keeps playing when the app is in the
 * background. The foreground media notification is shown automatically while playing.
 *
 * Network and stream errors are retried here: if there is no network (for example at car start,
 * before the phone hotspot connects) it waits for one, then reconnects. Retry status is sent to
 * the screen as the session extra [EXTRA_STATUS].
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    /** What the session (notification, controllers) sees: the player plus cover art. */
    private lateinit var sessionPlayer: ArtworkPlayer
    private lateinit var connectivity: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var attempts = 0
    private var waitingForNetwork = false
    private var waitStartedAt = 0L
    /** A retry is already queued because the network came up. */
    private var networkRetryQueued = false

    /** Data-use sampling every 30 s while the service runs (see [DataUsage]). */
    private val dataRunnable = object : Runnable {
        override fun run() {
            runCatching { DataUsage.sample(this@PlaybackService) }
            handler.postDelayed(this, DATA_SAMPLE_MS)
        }
    }

    private val retryRunnable = Runnable {
        networkRetryQueued = false
        retryNow()
    }

    // ---- Stream first (see [NetGate]) ----
    /** When the current stream last became READY without stalling since (elapsed realtime), or 0. */
    private var readySince = 0L
    private val settleRunnable = Runnable { updateGate() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkMaybeUp()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetworkMaybeUp()
    }

    // ---- Skipping dead stations ----
    /** Index of the item before the last transition, to tell next from previous. */
    private var lastIndex = -1
    /** +1/-1 while the user is stepping (next/previous, on screen or the wheel); 0 after a tap. */
    private var stepDir = 0
    /** Index this service seeked to when skipping, so that transition keeps [stepDir]. */
    private var skipTarget = -1
    /** Failed stations skipped in a row; capped so a dead network doesn't spin the playlist. */
    private var autoSkips = 0

    // ---- USB files: resume points ----
    /** The USB file playing, its resume key (null until read off the main thread), and length. */
    private var usbFile: File? = null
    private var usbKey: String? = null
    private var usbDurationMs = 0L
    private val usbExecutor = Executors.newSingleThreadExecutor()
    private val positionRunnable = object : Runnable {
        override fun run() {
            saveUsbPosition()
            handler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    // ---- Album art ----
    private val artExecutor = Executors.newSingleThreadExecutor()
    /** The stream title the art was last looked up for. Read on [artExecutor] too. */
    @Volatile private var artTitle: String? = null

    private val retryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_ENDED -> usbKey?.let { UsbPositions.markWatched(this@PlaybackService, it, usbDurationMs) }
                Player.STATE_READY -> {
                    saveUsbPosition()
                    resetRetries(clearStatus = true)
                    autoSkips = 0
                    // The step has landed: a later failure on this station is not a skip.
                    stepDir = 0
                    currentUrl()?.let { DeadStations.clear(this@PlaybackService, it) }
                }
                // Stopped by the user (no error): stop retrying.
                Player.STATE_IDLE -> if (player.playerError == null) resetRetries(clearStatus = true)
                else -> Unit
            }
            updateGate()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                resetRetries(clearStatus = true)
                saveUsbPosition()
            }
            updateGate()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Leaving a USB file: finished (played to the end) or stopped part-way (next/previous).
            val key = usbKey ?: return
            if (old.mediaItem?.mediaId == new.mediaItem?.mediaId) return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                UsbPositions.markWatched(this@PlaybackService, key, usbDurationMs)
            } else if (usbDurationMs > 0) {
                UsbPositions.save(this@PlaybackService, key, old.positionMs, usbDurationMs)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resetRetries(clearStatus = true)
            handler.removeCallbacks(dropRunnable)
            droppedThisItem = false
            artTitle = null
            sessionPlayer.setArtwork(null)
            onUsbItem(mediaItem)
            if (fileOf(mediaItem) == null) onStationChanged(reason)
            // A new station starts from scratch: hold background downloads until it settles.
            readySince = 0L
            updateGate()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = maybeLookUpArt(mediaMetadata)

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            val videoOff = C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes
            val turnedOff = videoOff && !videoWasOff
            videoWasOff = videoOff
            if (!videoOff) {
                handler.removeCallbacks(dropRunnable)
            } else if (turnedOff && !droppedThisItem && isAdaptiveVideoPlaying()) {
                // Wait: a slow gear read or a quick trip to another screen turns video off
                // briefly. Only reload if it is still off after DROP_DELAY_MS.
                handler.removeCallbacks(dropRunnable)
                handler.postDelayed(dropRunnable, DROP_DELAY_MS)
            }
        }
    }

    /** Video was disabled at the last parameters change. True from the start (see onCreate). */
    private var videoWasOff = true
    /** At most one reload per station, so gate flicker can't cause repeated audio gaps. */
    private var droppedThisItem = false

    private val dropRunnable = Runnable { dropToLowestVariant() }

    /** True if a video track from a group of several variants (adaptive HLS) is playing. */
    private fun isAdaptiveVideoPlaying(): Boolean =
        player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected && it.length > 1 }

    /**
     * Video has been off for a while (shifted out of P, or the screen closed). ExoPlayer would
     * keep loading whichever variant was playing, often 720p. Reloading makes it start again on
     * the first-listed variant, the lowest bitrate (see [LowestFirstParserFactory]), which saves
     * data and stalls on the car's connection. Live streams only (a film would restart from the
     * beginning). Costs a second or two of audio, once per station.
     */
    private fun dropToLowestVariant() {
        val stillOff = C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes
        if (!stillOff || droppedThisItem || !player.playWhenReady || !player.isCurrentMediaItemLive) return
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
        droppedThisItem = true
        player.stop()
        player.seekToDefaultPosition()
        player.prepare()
    }

    override fun onCreate() {
        super.onCreate()
        // Media/music audio attributes with audio focus handling: starting a station asks the
        // system for audio focus, so other media (FM, Bluetooth, other apps) pauses or stops.
        // If another app takes focus, this player pauses; it resumes after a short interruption.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Start playing after 3 s of buffered audio. 1 s started sooner but stalled straight
        // away on the car's slow connection (a couple of Mbit/s). After a stall, wait 4 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .build()
        // ExoPlayer's first guess of the speed comes from the country, and Australia's is far
        // above the car's connection, so TV in P opened on a 720p variant and stalled. Start the
        // guess at 1 Mbit/s: TV opens on a small variant and steps up if the link allows.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE)
            .build()
        NetGate.bandwidthEstimate = { bandwidthMeter.bitrateEstimate }
        player = ExoPlayer.Builder(this, FfmpegRenderersFactory(this))
            .setMediaSourceFactory(StreamMediaSourceFactory(this))
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Video starts disabled here too, so the service fails closed until the screen's gear
        // gate turns it on.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(retryListener)
        sessionPlayer = ArtworkPlayer(player)
        session = MediaSession.Builder(this, sessionPlayer).build()

        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback, handler) }
        handler.post(dataRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        saveUsbPosition()
        handler.removeCallbacksAndMessages(null)
        artExecutor.shutdownNow()
        usbExecutor.shutdown()
        NetGate.setStreamBusy(false)
        NetGate.bandwidthEstimate = { 0L }
        runCatching { DataUsage.sample(this) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        session?.let { mediaSession ->
            mediaSession.player.removeListener(retryListener)
            mediaSession.player.release()
            mediaSession.release()
        }
        session = null
        super.onDestroy()
    }

    private fun handleError(error: PlaybackException) {
        handleErrorInner(error)
        updateGate()
    }

    private fun handleErrorInner(error: PlaybackException) {
        val reason = describe(error)
        handler.removeCallbacks(retryRunnable)
        networkRetryQueued = false
        if (fileOf(player.currentMediaItem) != null) {
            // A file on the USB stick: retrying or waiting for internet won't help. Move on to the
            // next episode if there is one (the playlist doesn't loop, so this ends).
            setStatus("Can't play this file: $reason")
            if (player.playWhenReady && player.hasNextMediaItem()) {
                handler.post {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
            return
        }
        if (!player.playWhenReady || !isRetryable(error)) {
            setStatus("Can't play: $reason")
            gaveUp(error)
            return
        }
        if (!isNetworkUp()) {
            if (!waitingForNetwork) {
                waitingForNetwork = true
                waitStartedAt = System.currentTimeMillis()
            }
            if (System.currentTimeMillis() - waitStartedAt > MAX_NETWORK_WAIT_MS) {
                setStatus("No internet connection. Press Play to try again.")
                resetRetries(clearStatus = false)
                return
            }
            setStatus("Waiting for internet…")
            // The network callback retries as soon as a network appears; this is a fallback.
            handler.postDelayed(retryRunnable, NETWORK_POLL_MS)
            return
        }
        waitingForNetwork = false
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && attempts == 0) {
            // Fell behind the live stream: jump to the live point straight away.
            attempts++
            handler.post(retryRunnable)
            return
        }
        if (attempts >= RETRY_DELAYS_MS.size) {
            setStatus("Can't play: $reason. Press Play to try again.")
            resetRetries(clearStatus = false)
            gaveUp(error)
            return
        }
        val delay = RETRY_DELAYS_MS[attempts]
        attempts++
        setStatus("Reconnecting ($attempts of ${RETRY_DELAYS_MS.size}): $reason")
        handler.postDelayed(retryRunnable, delay)
    }

    /** The local file behind [item], or null for a stream. */
    private fun fileOf(item: MediaItem?): File? {
        val uri = item?.localConfiguration?.uri ?: return null
        return if (uri.scheme == "file") uri.path?.let(::File) else null
    }

    /** A USB file started: resume where it was stopped, and save the position every few seconds. */
    private fun onUsbItem(item: MediaItem?) {
        handler.removeCallbacks(positionRunnable)
        val file = fileOf(item)
        usbFile = file
        usbKey = null
        usbDurationMs = 0L
        if (file == null) return
        val mediaId = item?.mediaId
        // The key needs the file size: read it off the main thread (a slow stick mustn't stall it).
        runCatching {
            usbExecutor.execute {
                val key = runCatching { UsbPositions.key(file) }.getOrNull() ?: return@execute
                val start = UsbPositions.startPosition(this, key)
                UsbPositions.prune(this)
                handler.post {
                    if (player.currentMediaItem?.mediaId != mediaId) return@post
                    usbKey = key
                    // Resume, unless the user has already moved on in this episode.
                    if (start > 0 && player.currentPosition < 15_000) player.seekTo(player.currentMediaItemIndex, start)
                }
            }
        }
        handler.postDelayed(positionRunnable, POSITION_SAVE_MS)
    }

    private fun saveUsbPosition() {
        val file = usbFile ?: return
        val key = usbKey ?: return
        if (fileOf(player.currentMediaItem)?.path != file.path) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        usbDurationMs = duration
        UsbPositions.save(this, key, player.currentPosition, duration)
    }

    private fun currentUrl(): String? = player.currentMediaItem?.let { item ->
        item.mediaId.takeIf { it.startsWith("http", true) } ?: item.localConfiguration?.uri?.toString()
    }

    private fun urlAt(index: Int): String? = player.getMediaItemAt(index).let { item ->
        item.mediaId.takeIf { it.startsWith("http", true) } ?: item.localConfiguration?.uri?.toString()
    }

    /**
     * After next/previous (screen or steering wheel), skip stations that already failed today on
     * this connection. A station picked directly (a tap) always plays.
     */
    private fun onStationChanged(reason: Int) {
        val index = player.currentMediaItemIndex
        val count = player.mediaItemCount
        when {
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    stepDir = 0
                    autoSkips = 0
                }
            }
            index == skipTarget -> Unit // our own skip: keep the direction
            else -> {
                stepDir = DeadStations.direction(lastIndex, index, count)
                autoSkips = 0
            }
        }
        skipTarget = -1
        lastIndex = index
        if (stepDir == 0) return
        val dead = DeadStations.current(this)
        if (currentUrl() !in dead) return
        skipFrom(index, dead)
    }

    /** Seeks to the next station in [stepDir] that isn't dead. */
    private fun skipFrom(index: Int, dead: Set<String>) {
        val target = DeadStations.nextAlive(player.mediaItemCount, index, stepDir) { urlAt(it) in dead }
        if (target < 0) return
        skipTarget = target
        // Not from inside the listener callback.
        handler.post {
            if (player.currentMediaItemIndex == index && target < player.mediaItemCount) {
                player.seekTo(target, C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
            } else if (skipTarget == target) {
                skipTarget = -1
            }
        }
    }

    /**
     * Playback gave up on the current station. If the internet works and the error is the
     * station's (refused, broken, unsupported), remember it as dead for today. Timeouts and DNS
     * failures are the connection's fault, so they don't count. If the user was stepping, move on
     * to the next one (at most [MAX_AUTO_SKIPS] in a row).
     */
    private fun gaveUp(error: PlaybackException) {
        if (usbFile != null) return // files aren't stations
        if (!player.playWhenReady || !isInternetValidated() || isConnectionError(error)) return
        val url = currentUrl() ?: return
        DeadStations.mark(this, url)
        if (stepDir == 0 || !player.playWhenReady || autoSkips >= MAX_AUTO_SKIPS) return
        autoSkips++
        skipFrom(player.currentMediaItemIndex, DeadStations.current(this) + url)
    }

    /**
     * New stream title: look up its cover art (iTunes) and show it through the session, so the
     * screen and the notification show it. Station logo otherwise. Off in Settings means logos only.
     */
    private fun maybeLookUpArt(metadata: MediaMetadata) {
        val item = player.currentMediaItem ?: return
        if (fileOf(item) != null) return // USB: the file's own metadata only
        val title = metadata.title?.toString()?.trim().orEmpty()
        if (title == artTitle) return
        artTitle = title
        val station = item.mediaMetadata.title?.toString()
        val enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ALBUM_ART, true)
        if (!enabled || title.isEmpty() || title == station || AlbumArt.searchTerm(title) == null) {
            sessionPlayer.setArtwork(null)
            return
        }
        val mediaId = item.mediaId
        runCatching {
            artExecutor.execute {
                // Waits behind the station (see NetGate); the song may have changed meanwhile.
                if (artTitle != title) return@execute
                val art = AlbumArt.lookup(title)
                handler.post {
                    if (artTitle == title && player.currentMediaItem?.mediaId == mediaId) sessionPlayer.setArtwork(art)
                }
            }
        }
    }

    /**
     * Tells [NetGate] whether background downloads should wait: yes while a stream is starting,
     * rebuffering or being retried, and for [NetGate.SETTLE_MS] after it starts playing. No when
     * stopped, playing a USB file, or after giving up.
     */
    private fun updateGate() {
        handler.removeCallbacks(settleRunnable)
        val item = player.currentMediaItem
        if (!player.playWhenReady || item == null || fileOf(item) != null) {
            readySince = 0L
            NetGate.setStreamBusy(false)
            return
        }
        when (player.playbackState) {
            Player.STATE_READY -> {
                val now = SystemClock.elapsedRealtime()
                if (readySince == 0L) readySince = now
                val left = NetGate.SETTLE_MS - (now - readySince)
                if (left > 0) {
                    NetGate.setStreamBusy(true)
                    handler.postDelayed(settleRunnable, left)
                } else {
                    NetGate.setStreamBusy(false)
                }
            }
            Player.STATE_BUFFERING -> {
                readySince = 0L
                NetGate.setStreamBusy(true)
            }
            else -> {
                // Idle after an error: busy while a retry is coming, free once it has given up.
                readySince = 0L
                NetGate.setStreamBusy(player.playerError != null && (attempts > 0 || waitingForNetwork))
            }
        }
    }

    /** Re-opens the stream from its original address, at the live point. */
    private fun retryNow() {
        handler.removeCallbacks(retryRunnable)
        if (!player.playWhenReady || player.playerError == null) return
        player.seekToDefaultPosition()
        player.prepare()
    }

    private fun onNetworkMaybeUp() {
        // onCapabilitiesChanged fires on every signal change: queue one retry, don't keep
        // pushing it back.
        if (waitingForNetwork && !networkRetryQueued && isNetworkUp()) {
            networkRetryQueued = true
            handler.removeCallbacks(retryRunnable)
            // Give DNS a moment after the network appears.
            handler.postDelayed(retryRunnable, 1_500)
        }
    }

    private fun resetRetries(clearStatus: Boolean) {
        handler.removeCallbacks(retryRunnable)
        networkRetryQueued = false
        attempts = 0
        waitingForNetwork = false
        waitStartedAt = 0L
        if (clearStatus) setStatus(null)
    }

    /** Android has checked that this network actually reaches the internet. */
    private fun isInternetValidated(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isConnectionError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) return true
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is UnknownHostException || cause is SocketTimeoutException) return true
            cause = cause.cause
        }
        return false
    }

    private fun isNetworkUp(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setStatus(text: String?) {
        val current = session?.sessionExtras?.getString(EXTRA_STATUS)
        if (current == text) return
        session?.setSessionExtras(Bundle().apply { text?.let { putString(EXTRA_STATUS, it) } })
    }

    private fun isRetryable(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> true
        else -> false
    }

    private fun describeUri(uri: android.net.Uri): String {
        val host = uri.host ?: "?"
        val file = uri.lastPathSegment?.substringAfterLast('.', "")?.take(5)?.ifEmpty { null }
        return if (file != null) "$host, .$file" else host
    }

    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error.cause
        while (cause != null) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException ->
                    // Name the server and file type, so it's clear which step was refused
                    // (playlist host, ad server, video CDN, subtitles).
                    return "server refused (HTTP ${cause.responseCode} from ${describeUri(cause.dataSpec.uri)})"
                is UnknownHostException -> return "no internet (can't find server)"
                is SocketTimeoutException -> return "connection timed out"
            }
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "can't connect"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "connection timed out"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "fell behind the live stream"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "format not supported by the head unit"
            else -> error.errorCodeName
        }
    }

    companion object {
        const val EXTRA_STATUS = "mg4.status"
        /** The screen's prefs file and the album-art setting (default on). */
        const val PREFS = "mg4_streams"
        const val PREF_ALBUM_ART = "albumArt"
        private const val MAX_AUTO_SKIPS = 3
        private const val POSITION_SAVE_MS = 10_000L
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000)
        private const val NETWORK_POLL_MS = 15_000L
        private const val MAX_NETWORK_WAIT_MS = 5 * 60_000L
        /** First guess of the connection speed, bit/s (see onCreate). */
        private const val INITIAL_BITRATE_ESTIMATE = 1_000_000L
        private const val DROP_DELAY_MS = 10_000L
        private const val DATA_SAMPLE_MS = 30_000L
    }
}

/**
 * The usual renderers, plus FFmpeg audio decoding (AC3, E-AC3, DTS, TrueHD...) after the head
 * unit's own decoders, for USB videos with Dolby or DTS sound. Added directly, not found by
 * reflection, so the release build's shrinking can't drop it.
 */
@UnstableApi
private class FfmpegRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        if (runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }
    }
}
