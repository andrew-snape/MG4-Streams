package com.mg4.streams

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Updates the app from the latest GitHub release of [BuildConfig.UPDATE_REPO].
 *
 * The release must have a `.apk` asset signed with the same key as the installed app, with the
 * same package name and a higher versionCode. Those are checked before installing.
 * [latest] and [download] do network I/O: call them off the UI thread.
 */
class Updater(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    /** The receiver waiting for the current install's result, if any. Main thread only. */
    private var activeReceiver: BroadcastReceiver? = null

    data class Release(val tag: String, val apkUrl: String, val apkSize: Long, val notes: String)

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

    /** Downloads the release APK to the cache folder. [progress] gets 0-100 (or -1 if unknown). */
    fun download(release: Release, progress: (Int) -> Unit): File {
        val target = File(context.cacheDir, "update.apk")
        val temp = File(context.cacheDir, "update.apk.part")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MG4Streams-updater")
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("download failed (HTTP ${connection.responseCode})")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var read = 0L
            var lastPercent = -2
            connection.inputStream.use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw IllegalStateException("update file is too large")
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress(percent)
                        }
                    }
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        } finally {
            connection.disconnect()
            temp.delete()
        }
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
     * Installs [apk] with PackageInstaller. Call on a background thread: it copies the APK into
     * the install session. A platform-signed app installs straight away; otherwise Android shows
     * its install screen. [onResult] runs on the main thread with null on success or an error.
     * On success the system replaces (and stops) the app.
     */
    fun install(apk: File, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        // Only the result for this session.
                        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION")
                                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { app.startActivity(confirm) }
                                    .onFailure { finish("can't open the installer: ${it.message}") }
                            }
                            PackageInstaller.STATUS_SUCCESS -> finish(null)
                            else -> finish(
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                                    ?: "install failed ($status)",
                            )
                        }
                    }

                    private fun finish(error: String?) {
                        cancelPending()
                        onResult(error)
                    }
                }
                val registered = CountDownLatch(1)
                main.post {
                    cancelPending()
                    activeReceiver = receiver
                    // Only senders holding INSTALL_PACKAGES (the system installer) can deliver,
                    // so another app can't fake a result or slip in an Intent to open.
                    app.registerReceiver(
                        receiver,
                        IntentFilter(ACTION_INSTALL_STATUS),
                        Manifest.permission.INSTALL_PACKAGES,
                        main,
                    )
                    registered.countDown()
                }
                // Register before committing, so the result can't arrive first.
                if (!registered.await(10, TimeUnit.SECONDS)) {
                    throw IllegalStateException("installer did not start")
                }
                val pending = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    Intent(ACTION_INSTALL_STATUS).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            main.post { cancelPending() }
            throw e
        }
    }

    /** Stops waiting for an install result (for example after a timeout). Main thread only. */
    fun cancelPending() {
        activeReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        activeReceiver = null
    }

    companion object {
        private const val ACTION_INSTALL_STATUS = "com.mg4.streams.INSTALL_STATUS"
        private const val MAX_APK_BYTES = 100L * 1024 * 1024

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
