package com.mg4.streams

import android.content.Context
import android.content.SharedPreferences
import com.mg4.streams.tailnet.mg4tailnet.Mg4tailnet
import com.mg4.streams.tailnet.mg4tailnet.Node
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tailscale relay. The car modem reaches the internet through Germany, so Australian stations
 * (StreamTheWorld, Seven) refuse it. This runs a Tailscale node inside the app (Go, tsnet: no VPN
 * permission) with a loopback HTTP proxy, and sends the app's HTTP traffic through an exit node at
 * home, so stations see the home address in Australia.
 *
 * All HTTP in the app (ExoPlayer, playlists, logos, guide, updates, Network check) goes through
 * HttpURLConnection, which asks the default [ProxySelector]. [Selector] answers with the proxy only
 * while the node is running, the exit node is online and the mode applies to the current
 * connection; otherwise DIRECT, so a hotspot keeps working if the relay is down.
 *
 * Go calls block (network, Tailscale's local API): never call [node] methods on the UI thread.
 */
object Tailnet {

    enum class Mode(val key: String, val label: String) {
        OFF("off", "Off"),
        CAR("car", "On car internet only"),
        ALWAYS("always", "Always");

        companion object {
            fun of(key: String?) = values().firstOrNull { it.key == key } ?: OFF
        }
    }

    data class Status(
        val state: String = "Stopped",
        val loginUrl: String = "",
        val error: String = "",
        val self: String = "",
        val exitNode: String = "",
        val exitOnline: Boolean = false,
        val proxyPort: Int = 0,
    ) {
        val running get() = state == "Running"
        val usable get() = running && exitNode.isNotEmpty() && exitOnline && proxyPort > 0
    }

    data class ExitNode(val name: String, val ip: String, val online: Boolean, val active: Boolean)

    private const val PREF_MODE = "tailnet_mode"
    private const val PREF_EXIT = "tailnet_exit"
    private const val HOSTNAME = "mg4-streams"
    private const val POLL_MS = 3_000L

    /** One thread for every Go call, so they never overlap or block the UI. */
    private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tailnet").apply { isDaemon = true } }
    private var polling = false

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var node: Node? = null
    private var installed = false

    @Volatile var status = Status()
        private set
    /** Connection the head unit is using, refreshed with the status (for [Mode.CAR]). */
    @Volatile private var onCarInternet = false

    val mode: Mode get() = Mode.of(prefs.getString(PREF_MODE, null))
    val exitNodeName: String get() = prefs.getString(PREF_EXIT, "").orEmpty()

    /** Call from every entry point (activity, service). Starts the node if the relay is on. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        app = context.applicationContext
        prefs = app.getSharedPreferences("mg4_streams", Context.MODE_PRIVATE)
        ProxySelector.setDefault(Selector(ProxySelector.getDefault()))
        if (mode != Mode.OFF) thread.execute { startQuietly() }
    }

    fun setMode(newMode: Mode) {
        prefs.edit().putString(PREF_MODE, newMode.key).apply()
        if (newMode != Mode.OFF) thread.execute { startQuietly() }
        // Off keeps the node signed in but idle: the selector stops using it at once.
    }

    /** Requests a sign-in link; it shows up in [status] as loginUrl. */
    fun login(done: (String?) -> Unit) = call(done) { it.login() }

    /** Signs in with an auth key (e.g. read from a file on a USB stick). */
    fun loginWithKey(key: String, done: (String?) -> Unit) = call(done) { it.loginWithKey(key) }

    fun logout(done: (String?) -> Unit) = call(done) { it.logout() }

    fun setExitNode(name: String, done: (String?) -> Unit) = call(done) {
        it.setExitNode(name)
        prefs.edit().putString(PREF_EXIT, name).apply()
    }

    fun exitNodes(done: (List<ExitNode>, String?) -> Unit) {
        thread.execute {
            val result = runCatching {
                val array = JSONArray(ensureStarted().exitNodes())
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ExitNode(o.optString("name"), o.optString("ip"), o.optBoolean("online"), o.optBoolean("active"))
                }
            }
            done(result.getOrDefault(emptyList()), result.exceptionOrNull()?.let(::describe))
        }
    }

    /** One line for Network check. */
    fun describeForNetCheck(): String {
        if (mode == Mode.OFF) return "off"
        val s = status
        val via = if (s.exitNode.isEmpty()) "no exit node chosen"
        else "exit node ${s.exitNode} ${if (s.exitOnline) "online" else "offline"}"
        val used = when {
            !s.usable -> "not in use"
            mode == Mode.CAR && !onCarInternet -> "not in use on this connection"
            else -> "in use"
        }
        return "${mode.label.lowercase()}, ${s.state}, $via, $used"
    }

    private fun call(done: (String?) -> Unit, block: (Node) -> Unit) {
        thread.execute {
            val error = runCatching { block(ensureStarted()) }.exceptionOrNull()?.let(::describe)
            refresh()
            done(error)
        }
    }

    /**
     * For fire-and-forget starts: the executor would swallow the error, and the relay dialog is
     * the only diagnostic on the head unit, so the reason goes into [status].
     */
    private fun startQuietly() {
        runCatching { ensureStarted() }.onFailure { status = Status(state = "Error", error = describe(it)) }
    }

    /** On [thread] only. Throws if the library or the node can't start (shown as the error). */
    private fun ensureStarted(): Node {
        node?.let { return it }
        val dir = File(app.filesDir, "tailnet").absolutePath
        val n = Mg4tailnet.newNode(dir, HOSTNAME)
        try {
            n.start()
            n.startProxy()
        } catch (t: Throwable) {
            // Release the state directory, so a retry doesn't run a second node on it.
            runCatching { n.close() }
            throw t
        }
        node = n
        // The exit node pref lives in Tailscale's own state too; reapply in case it was cleared.
        exitNodeName.takeIf { it.isNotEmpty() }?.let { name -> runCatching { n.setExitNode(name) } }
        if (!polling) {
            polling = true
            thread.scheduleWithFixedDelay({ refresh() }, 0, POLL_MS, TimeUnit.MILLISECONDS)
        }
        return n
    }

    /** Never throws: an exception would cancel the scheduled refresh. */
    private fun refresh() {
        onCarInternet = runCatching { NetKind.current(app) == NetKind.CAR }.getOrDefault(false)
        val n = node ?: return
        status = runCatching {
            val o = JSONObject(n.status())
            Status(
                state = o.optString("state", "Stopped"),
                loginUrl = o.optString("loginURL"),
                error = o.optString("error"),
                self = o.optString("self"),
                exitNode = o.optString("exitNode"),
                exitOnline = o.optBoolean("exitOnline"),
                proxyPort = o.optInt("proxyPort"),
            )
        }.getOrElse { Status(state = "Error", error = describe(it)) }
    }

    private fun describe(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    /** Chooses the relay proxy per request; see the class comment. */
    private class Selector(private val fallback: ProxySelector?) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host?.lowercase()
            val s = status
            val use = (scheme == "http" || scheme == "https") && host != null &&
                host != "localhost" && !host.startsWith("127.") && s.usable &&
                when (mode) {
                    Mode.OFF -> false
                    Mode.CAR -> onCarInternet
                    Mode.ALWAYS -> true
                }
            if (use) return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", s.proxyPort)))
            return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            fallback?.connectFailed(uri, sa, ioe)
        }
    }
}
