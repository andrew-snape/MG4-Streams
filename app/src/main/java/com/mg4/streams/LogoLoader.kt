package com.mg4.streams

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Small logo loader: memory cache, then a disk cache in app files, then download. Downloads and
 * decoding run on [executor]; views and the bookkeeping maps are only touched on the UI thread
 * through [ui]. [load] must be called on the UI thread.
 *
 * Each URL is fetched once at a time (other views wait for it). Download failures are retried
 * after [FAIL_RETRY_MS], so logos appear once the network comes up.
 */
class LogoLoader(
    private val dir: File,
    private val executor: ExecutorService,
    private val ui: Handler,
) {
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** URL -> when it last failed (elapsed realtime). */
    private val failedAt = HashMap<String, Long>()
    /** URL -> views waiting for it. */
    private val inFlight = HashMap<String, MutableList<ImageView>>()

    fun load(url: String?, view: ImageView) {
        // Already showing this logo (the grid refreshes on every station change): leave it, so
        // tiles don't blink.
        if (url != null && view.tag == url && view.drawable != null) return
        view.tag = url
        view.setImageDrawable(null)
        if (url == null) return
        memory.get(url)?.let { view.setImageBitmap(it); return }
        failedAt[url]?.let { at ->
            if (SystemClock.elapsedRealtime() - at < FAIL_RETRY_MS) return
            failedAt.remove(url)
        }
        inFlight[url]?.let { waiting ->
            if (view !in waiting) waiting += view
            return
        }
        inFlight[url] = mutableListOf(view)
        try {
            executor.execute {
                val bitmap = runCatching { fetch(url) }.getOrNull()
                ui.post {
                    val waiting = inFlight.remove(url).orEmpty()
                    if (bitmap == null) {
                        failedAt[url] = SystemClock.elapsedRealtime()
                    } else {
                        memory.put(url, bitmap)
                        waiting.forEach { if (it.tag == url) it.setImageBitmap(bitmap) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.remove(url)
        }
    }

    private fun fetch(address: String): Bitmap? {
        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) return null
        dir.mkdirs()
        val file = File(dir, sha1(address))
        if (!file.exists()) {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            val temp = File.createTempFile(file.name, ".tmp", dir)
            try {
                if (connection.responseCode != 200) return null
                connection.inputStream.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) return null
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (!temp.renameTo(file) && !file.exists()) return null
            } finally {
                connection.disconnect()
                temp.delete()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        // Largest power-of-two sample that keeps the longer side at least TARGET_SIDE.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
            },
        )
        if (bitmap == null) {
            file.delete()
            return null
        }
        // Scale down to TARGET_SIDE so the memory cache holds a full screen of tiles.
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= TARGET_SIDE) return bitmap
        val scale = TARGET_SIDE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES = 3 * 1024 * 1024
        /** The main logo is 300 dp (= px on this screen); tiles are smaller. */
        const val TARGET_SIDE = 300
        const val FAIL_RETRY_MS = 2 * 60_000L
    }
}
