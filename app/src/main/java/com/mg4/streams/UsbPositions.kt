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

    /**
     * "/storage/1234-ABCD/TV/Show/S01E02.mkv" -> "TV/Show/S01E02.mkv|<size>". Reads the file size
     * from the stick: call off the main thread, and keep the key rather than asking again.
     */
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

    fun get(context: Context, key: String): Entry? = parse(prefs(context).getString(key, null))

    /** In-memory prefs write (saved to disk in the background): fine on the main thread. */
    fun save(context: Context, key: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs < 0) return
        prefs(context).edit().putString(key, "$positionMs|$durationMs|${System.currentTimeMillis()}").apply()
    }

    fun markWatched(context: Context, key: String, durationMs: Long) {
        if (durationMs > 0) save(context, key, durationMs, durationMs)
    }

    /** Keeps the newest [MAX_ENTRIES]. Call now and then (each new USB file), not on every save. */
    fun prune(context: Context) {
        val prefs = prefs(context)
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return
        val editor = prefs.edit()
        all.entries.sortedBy { parse(it.value as? String)?.updated ?: 0L }
            .take(all.size - MAX_ENTRIES).forEach { editor.remove(it.key) }
        editor.apply()
    }

    /** Where to start: the saved point if it's partly watched, else 0. */
    fun startPosition(context: Context, key: String): Long {
        val entry = get(context, key) ?: return 0L
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
