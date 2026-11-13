package com.mg4.streams

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Counts the app's own data use (streams, playlists, logos, guide, updates) for today and this
 * month, split by connection (car internet, Wi-Fi...). [sample] adds the bytes since the last
 * sample to the connection in use now, so call it often (PlaybackService does, every 30 s while
 * it runs). Counts reset at the start of each day and month. Prefs file "data_usage".
 */
object DataUsage {

    private const val PREFS = "data_usage"
    private const val LAST_TOTAL = "lastTotal"
    private const val DAY = "day"
    private const val MONTH = "month"

    @Synchronized
    fun sample(context: Context) {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return
        val total = rx + tx
        val prefs = prefs(context)
        val last = prefs.getLong(LAST_TOTAL, -1L)
        // First sample, or the counters restarted (head unit rebooted): count from zero.
        val delta = when {
            last < 0 -> 0L
            total < last -> total
            else -> total - last
        }
        val now = Date()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val month = day.substring(0, 7)
        val editor = prefs.edit().putLong(LAST_TOTAL, total)
        // Roll over only when the date moves forward: a head unit that boots with a wrong
        // (earlier) clock must not wipe the counts.
        val storedDay = prefs.getString(DAY, null)
        val storedMonth = prefs.getString(MONTH, null)
        if (storedDay != null && day < storedDay) {
            editor.apply()
            return
        }
        if (storedDay != day) {
            prefs.all.keys.filter { it.startsWith("d.") }.forEach { editor.remove(it) }
            editor.putString(DAY, day)
        }
        if (storedMonth == null || month > storedMonth) {
            prefs.all.keys.filter { it.startsWith("m.") }.forEach { editor.remove(it) }
            editor.putString(MONTH, month)
        }
        if (delta > 0) {
            val kind = NetKind.current(context).key
            // Re-read after the resets above were queued: a reset key counts from zero.
            val dayKey = "d.$kind"
            val monthKey = "m.$kind"
            val dayBase = if (prefs.getString(DAY, null) == day) prefs.getLong(dayKey, 0L) else 0L
            val monthBase = if (prefs.getString(MONTH, null) == month) prefs.getLong(monthKey, 0L) else 0L
            editor.putLong(dayKey, dayBase + delta).putLong(monthKey, monthBase + delta)
        }
        editor.apply()
    }

    /** "Today: Car internet 120 MB, Wi-Fi 30 MB\nThis month: ..." */
    fun summary(context: Context): String {
        sample(context)
        val prefs = prefs(context)
        fun line(prefix: String): String {
            val parts = NetKind.values().mapNotNull { kind ->
                prefs.getLong("$prefix.${kind.key}", 0L).takeIf { it > 0 }?.let { "${kind.label} ${format(it)}" }
            }
            return if (parts.isEmpty()) "nothing yet" else parts.joinToString(", ")
        }
        return "Data used today: ${line("d")}\nThis month: ${line("m")}"
    }

    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "${bytes / 1000L} KB"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
