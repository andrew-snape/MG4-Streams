package com.mg4.streams

/**
 * "Stream first". The car's internet can be as slow as 2 Mbit/s. At car start the app used to
 * download the TV guide, playlists, logos, album art and the update check at the same moment it
 * resumed the last station, and the station timed out. Now those background downloads wait here
 * until the station has played smoothly for [SETTLE_MS], then go through one at a time.
 *
 * - PlaybackService says when a stream is starting, rebuffering or retrying ([setStreamBusy]).
 * - The screen calls [expectStream] just before it starts a station, so nothing slips through
 *   before the service has caught up.
 * - A job never waits more than [MAX_WAIT_MS] (a station that never settles mustn't keep the
 *   guide and logos away for ever), but still runs one at a time.
 *
 * Downloads the user asks for (Add URL, Update all, Check for updates, Network check) don't wait.
 * Everything runs in one process, so this is a plain object. [background] blocks: call it on a
 * background thread.
 */
object NetGate {

    /** How long a station must play without stalling before background downloads start. */
    const val SETTLE_MS = 30_000L
    /** Longest a background job waits for a station to settle. */
    const val MAX_WAIT_MS = 3 * 60_000L
    /** How long [expectStream] holds jobs if the service doesn't report in. */
    const val EXPECT_MS = 15_000L

    /** Monotonic milliseconds; replaced in tests. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private val lock = Object()
    private var busy = false
    private var expectUntil = 0L
    private val running = ArrayList<String>()
    private val waiting = ArrayList<String>()

    /** The player's current download-speed estimate in bit/s (0 until the service has started). */
    @Volatile
    var bandwidthEstimate: () -> Long = { 0L }

    /** A station is starting, rebuffering or retrying (true), or playing settled / stopped (false). */
    fun setStreamBusy(value: Boolean) {
        synchronized(lock) {
            if (busy == value && !value) return
            busy = value
            // The service has taken over from the screen's hint.
            if (value) expectUntil = 0L
            lock.notifyAll()
        }
    }

    /** The screen is about to start a station: hold background jobs until the service reports. */
    fun expectStream() {
        synchronized(lock) {
            expectUntil = maxOf(expectUntil, clock() + EXPECT_MS)
        }
    }

    /** True while background downloads are being held for the station. */
    fun isHolding(): Boolean = synchronized(lock) { busy || clock() < expectUntil }

    /**
     * Runs [block] once no station is starting and no other background download is running.
     * [label] names the job in Network check (for example "TV guide").
     */
    fun <T> background(label: String, block: () -> T): T {
        val start = clock()
        synchronized(lock) {
            waiting += label
            try {
                while (true) {
                    val now = clock()
                    val holding = busy || now < expectUntil
                    val waitedTooLong = now - start >= MAX_WAIT_MS
                    if (running.isEmpty() && (!holding || waitedTooLong)) break
                    // Timed, so the [expectStream] hold and [MAX_WAIT_MS] expire without a notify.
                    lock.wait(POLL_MS)
                }
            } finally {
                waiting.remove(label)
            }
            running += label
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                running.remove(label)
                lock.notifyAll()
            }
        }
    }

    /** A few lines for Network check: whether jobs are held, and which are running and waiting. */
    fun describe(): String = synchronized(lock) {
        val state = if (busy || clock() < expectUntil) {
            "holding background downloads while the station starts"
        } else {
            "clear"
        }
        val lines = mutableListOf("Stream first: $state")
        lines += "Background download running: ${running.firstOrNull() ?: "none"}"
        lines += "Background downloads waiting: ${summarise(waiting)}"
        lines.joinToString("\n")
    }

    /** "none", or "Logo ×3, TV guide" in the order they started waiting. */
    internal fun summarise(labels: List<String>): String {
        if (labels.isEmpty()) return "none"
        return labels.groupingBy { it }.eachCount().entries.joinToString(", ") { (label, count) ->
            if (count > 1) "$label ×$count" else label
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            busy = false
            expectUntil = 0L
            running.clear()
            waiting.clear()
            clock = { System.nanoTime() / 1_000_000 }
            lock.notifyAll()
        }
    }

    private const val POLL_MS = 500L
}
