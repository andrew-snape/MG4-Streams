package com.mg4.streams

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    private lateinit var connectivity: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var attempts = 0
    private var waitingForNetwork = false
    private var waitStartedAt = 0L
    /** A retry is already queued because the network came up. */
    private var networkRetryQueued = false

    private val retryRunnable = Runnable {
        networkRetryQueued = false
        retryNow()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkMaybeUp()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetworkMaybeUp()
    }

    private val retryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> resetRetries(clearStatus = true)
                // Stopped by the user (no error): stop retrying.
                Player.STATE_IDLE -> if (player.playerError == null) resetRetries(clearStatus = true)
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) resetRetries(clearStatus = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resetRetries(clearStatus = true)
        }
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
        // Start playing after 1 s of buffered audio instead of ExoPlayer's default 2.5 s, so a
        // station starts sooner. After a stall, wait 2 s so it doesn't stutter on weak signal.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(StreamMediaSourceFactory(this))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(retryListener)
        session = MediaSession.Builder(this, player).build()

        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback, handler) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        val reason = describe(error)
        handler.removeCallbacks(retryRunnable)
        networkRetryQueued = false
        if (!player.playWhenReady || !isRetryable(error)) {
            setStatus("Can't play: $reason")
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
            return
        }
        val delay = RETRY_DELAYS_MS[attempts]
        attempts++
        setStatus("Reconnecting ($attempts of ${RETRY_DELAYS_MS.size}): $reason")
        handler.postDelayed(retryRunnable, delay)
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

    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error.cause
        while (cause != null) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException ->
                    return "server refused (HTTP ${cause.responseCode})"
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
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000)
        private const val NETWORK_POLL_MS = 15_000L
        private const val MAX_NETWORK_WAIT_MS = 5 * 60_000L
    }
}
