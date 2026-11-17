package com.mg4.streams

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Updates the app from the latest GitHub release of [BuildConfig.UPDATE_REPO].
 *
 * The head unit refuses installs started by a non-system app ("not a system app"), so the app
 * doesn't install the update itself. It downloads the APK with DownloadManager into the Downloads
 * folder, checks it, then opens Downloads. Tapping the file there hands it to Android's own
 * installer, which the head unit allows. MG4Control updates the same way.
 *
 * The release must have a `.apk` asset signed with the same key as the installed app, with the
 * same package name and a higher versionCode. Those are checked before Downloads is opened.
 * [latest] and [download] block on network I/O: call them off the UI thread.
 */
class Updater(private val context: Context) {

    data class Release(val tag: String, val apkUrl: String, val apkSize: Long, val notes: String)

    /** A finished download: the DownloadManager id and the file. */
    data class Download(val id: Long, val file: File)

    private val downloads: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** The latest release, or null if there is none or it has no APK. */
    fun latest(): Release? {
        val connection = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "MG4Streams-updater")
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) {
                throw IllegalStateException("GitHub said HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        tag = json.optString("tag_name"),
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size"),
                        notes = json.optString("body").trim(),
                    )
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the release APK with DownloadManager and waits for it. Goes to the public
     * Downloads folder, which needs storage permission: the app's own folder is listed in
     * Downloads on the head unit but the file can't be opened from there. [progress] gets 0-100, or -1 if unknown.
     * If this thread is interrupted, the download carries on in DownloadManager.
     */
    fun download(release: Release, progress: (Int) -> Unit): Download {
        val name = fileName(release.tag)
        val public = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!public) throw IllegalStateException("storage permission is needed to save it in Downloads")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).mkdirs()
        removeOldDownloads()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("$TITLE_PREFIX${release.tag}")
            .setDescription("App update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("User-Agent", "MG4Streams-updater")
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        // Without this the file isn't in the media index until the media scan at the next boot,
        // and the head unit's Downloads lists it but won't open it (fine after a restart).
        @Suppress("DEPRECATION")
        request.allowScanningByMediaScanner()
        val id = downloads.enqueue(request)

        val started = System.currentTimeMillis()
        var lastPercent = -2
        while (true) {
            Thread.sleep(POLL_MS)
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) throw IllegalStateException("the download was cancelled")
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // Read the real path back: DownloadManager may have renamed the file.
                        val path = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            ?.let { Uri.parse(it).path }
                            ?: throw IllegalStateException("the download has no file")
                        val file = File(path)
                        scan(file)
                        return Download(id, file)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        downloads.remove(id)
                        throw IllegalStateException("download failed (${describeReason(reason)})")
                    }
                }
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    .takeIf { it > 0 } ?: release.apkSize
                if (total > MAX_APK_BYTES || done > MAX_APK_BYTES) {
                    downloads.remove(id)
                    throw IllegalStateException("update file is too large")
                }
                val percent = if (total > 0) (done * 100 / total).toInt() else -1
                if (percent != lastPercent) {
                    lastPercent = percent
                    progress(percent)
                }
            }
            if (System.currentTimeMillis() - started > MAX_DOWNLOAD_MS) {
                downloads.remove(id)
                throw IllegalStateException("download took too long")
            }
        }
    }

    /**
     * Adds [file] to the media index as an APK and waits (up to [SCAN_WAIT_MS]) until it is, so
     * it can be opened from Downloads straight away.
     */
    private fun scan(file: File) {
        val done = CountDownLatch(1)
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(APK_MIME)) { _, _ -> done.countDown() }
            done.await(SCAN_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Deletes a download and its file (for example when it fails the checks). */
    fun discard(download: Download) {
        runCatching { downloads.remove(download.id) }
        download.file.delete()
    }

    /** Null if [apk] is a valid update for this app, otherwise the reason it isn't. */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    fun verify(apk: File): String? {
        val pm = context.packageManager
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            ?: return "the downloaded file is not a valid app"
        if (archive.packageName != context.packageName) {
            return "the download is a different app (${archive.packageName})"
        }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        if (archive.longVersionCode <= installed.longVersionCode) {
            return "the download is not newer than this version"
        }
        val newSigs = archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val oldSigs = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        if (newSigs.isEmpty() || newSigs != oldSigs) {
            return "the download is signed with a different key, so the head unit would refuse it"
        }
        return null
    }

    /**
     * Opens the Downloads screen so the user can tap the APK and install it with Android's
     * installer. Returns false if no Downloads screen could be opened.
     */
    fun openDownloads(): Boolean {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Removes this app's earlier update downloads (finished or still running), record and file
     * together, so old versions don't pile up. DownloadManager only lists this app's own
     * downloads, so files the user put in Downloads are never touched.
     */
    private fun removeOldDownloads() {
        val ids = ArrayList<Long>()
        runCatching {
            downloads.query(DownloadManager.Query()).use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(titleCol)?.startsWith(TITLE_PREFIX) == true) ids += cursor.getLong(idCol)
                }
            }
        }
        if (ids.isNotEmpty()) runCatching { downloads.remove(*ids.toLongArray()) }
    }

    private fun describeReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough space"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "connection dropped"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not available"
        DownloadManager.ERROR_FILE_ERROR -> "couldn't save the file"
        DownloadManager.ERROR_CANNOT_RESUME -> "couldn't resume"
        in 100..599 -> "HTTP $reason"
        else -> "error $reason"
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val FILE_PREFIX = "MG4Streams-"
        /** Title of this app's update downloads; how old ones are found again. */
        private const val TITLE_PREFIX = "MG4 Streams "
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val POLL_MS = 500L
        private const val SCAN_WAIT_MS = 10_000L
        private const val MAX_DOWNLOAD_MS = 15 * 60_000L

        /** "MG4Streams-0.3.0.apk" for tag "v0.3.0". Only safe file-name characters are kept. */
        fun fileName(tag: String): String {
            val version = tag.trim().removePrefix("v").removePrefix("V")
                .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
                .ifEmpty { "update" }
            return "$FILE_PREFIX$version.apk"
        }

        /** True if release [tag] (e.g. "v0.3.0") is a higher version than [current] ("0.2.0"). */
        fun isNewer(tag: String, current: String): Boolean {
            val a = numbers(tag)
            val b = numbers(current)
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun numbers(version: String): List<Int> =
            version.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-').split('.')
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
