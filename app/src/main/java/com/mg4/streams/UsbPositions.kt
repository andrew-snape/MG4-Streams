package com.mg4.streams

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Where each USB video was stopped, and which are watched. Kept in the app's prefs (never on the
 * stick), keyed by the path inside the stick plus the file size, so it survives the stick
 * mounting under a different /storage name. Prefs file "usb_positions":
 * key -> "positionMs|durationMs|updatedMillis".
 */
object UsbPositions {

    private const val PREFS = "usb_positions"
    /** Past this share of the length, an episode counts as watched. */
    private const val WATCHED_SHARE = 0.95
    /** Resume only from this far in; earlier means start again. */
    const val MIN_RESUME_MS = 30_000L
    private const val MAX_ENTRIES = 500

    data class Entry(val positionMs: Long, val durationMs: Long, val updated: Long) {
        val watched: Boolean get() = durationMs > 0 && positionMs >= durationMs * WATCHED_SHARE
        /** 0..1000 for a progress bar. */
        val progress: Int get() = if (durationMs > 0) (positionMs * 1000 / durationMs).toInt().coerceIn(0, 1000) else 0
    }

    /** "/storage/1234-ABCD/TV/Show/S01E02.mkv" -> "TV/Show/S01E02.mkv|<size>". */
    fun key(file: File): String = "${relative(file.path)}|${file.length()}"

    fun relative(path: String): String {
        val parts = path.trimStart('/').split('/')
        // /storage/<volume>/... or /mnt/media_rw/<volume>/...
        val skip = when {
            parts.size > 2 && parts[0] == "storage" -> 2
            parts.size > 3 && parts[0] == "mnt" -> 3
            else -> 0
        }
        return parts.drop(skip).joinToString("/")
    }

    fun get(context: Context, file: File): Entry? = parse(prefs(context).getString(key(file), null))

    fun save(context: Context, file: File, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs < 0) return
        val prefs = prefs(context)
        val editor = prefs.edit().putString(key(file), "$positionMs|$durationMs|${System.currentTimeMillis()}")
        if (prefs.all.size > MAX_ENTRIES) {
            prefs.all.entries.sortedBy { parse(it.value as? String)?.updated ?: 0L }
                .take(prefs.all.size - MAX_ENTRIES).forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    fun markWatched(context: Context, file: File, durationMs: Long) {
        if (durationMs > 0) save(context, file, durationMs, durationMs)
    }

    /** Where to start [file]: the saved point if it's partly watched, else 0. */
    fun startPosition(context: Context, file: File): Long {
        val entry = get(context, file) ?: return 0L
        return if (!entry.watched && entry.positionMs >= MIN_RESUME_MS) entry.positionMs - 5_000 else 0L
    }

    /** Recently played, unfinished entries (relative keys), newest first. */
    fun inProgress(context: Context, limit: Int = 6): List<Pair<String, Entry>> =
        prefs(context).all.mapNotNull { (k, v) -> parse(v as? String)?.let { k to it } }
            .filter { !it.second.watched && it.second.positionMs >= MIN_RESUME_MS }
            .sortedByDescending { it.second.updated }
            .take(limit)

    fun parse(value: String?): Entry? {
        val parts = value?.split('|') ?: return null
        if (parts.size < 3) return null
        return Entry(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: 0L)
    }

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
