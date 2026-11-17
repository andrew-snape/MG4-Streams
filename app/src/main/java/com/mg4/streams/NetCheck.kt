package com.mg4.streams

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Settings > Network check. Says which connection the head unit is using, where the internet
 * thinks it is (public address and country), and walks one stream step by step: each redirect,
 * the playlist, a variant and a segment, with the HTTP answer and timing of each.
 *
 * For example, Seven plays on Wi-Fi but not on the car's own internet: this shows whether Seven's
 * servers refuse the car's connection (HTTP 403 = blocked by location or network), it times out,
 * or the address doesn't resolve. Blocking network I/O: run off the UI thread.
 */
object NetCheck {

    fun run(context: Context, channel: Channel?): String {
        val out = StringBuilder()
        out.appendLine("Connection: ${describeNetwork(context)}")
        out.appendLine("Public address: ${publicAddress()}")
        out.appendLine("Download speed: ${speedTest()}")
        val estimate = NetGate.bandwidthEstimate()
        if (estimate > 0) out.appendLine("Player's speed estimate: ${estimate / 1000} kbit/s")
        out.appendLine(NetGate.describe())
        if (channel == null) {
            out.appendLine()
            out.appendLine("Pick a station first to test its stream.")
            return out.toString()
        }
        out.appendLine()
        out.appendLine("Stream: ${channel.name}")
        runCatching { testStream(channel, out) }
            .onFailure { out.appendLine("  stopped: ${describe(it)}") }
        return out.toString()
    }

    private fun describeNetwork(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "car modem (Ethernet)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
        val link = cm.getLinkProperties(network)
        val iface = link?.interfaceName?.let { " $it" }.orEmpty()
        val v6 = link?.linkAddresses?.any { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress } == true
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val dns = link?.dnsServers?.joinToString(", ") { it.hostAddress.orEmpty() }.orEmpty()
        return "$kind$iface, ${if (v6) "IPv4+IPv6" else "IPv4 only"}" +
            (if (validated) "" else ", not validated") +
            (if (dns.isNotEmpty()) ", DNS $dns" else "")
    }

    /** Public IP and country from Cloudflare's trace page (no account, plain text). */
    private fun publicAddress(): String = runCatching {
        val text = get(URL("https://www.cloudflare.com/cdn-cgi/trace"), null, null, 4096).body
        val fields = text.lines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        "${fields["ip"] ?: "?"}, country ${fields["loc"] ?: "?"}"
    }.getOrElse { "unknown (${describe(it)})" }

    /**
     * Downloads up to [SPEED_TEST_BYTES] from Cloudflare's speed test (no account; nothing about
     * the car is sent) for at most [SPEED_TEST_MS], and says what that is enough for. While a
     * station plays, it measures what's left over beside it.
     */
    private fun speedTest(): String = runCatching {
        val url = URL("https://speed.cloudflare.com/__down?bytes=$SPEED_TEST_BYTES")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "MG4Streams")
        try {
            if (c.responseCode != 200) return@runCatching "test server said HTTP ${c.responseCode}"
            var total = 0L
            var start = 0L
            c.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    // Time from the first bytes, so connection set-up doesn't count.
                    if (start == 0L) start = System.currentTimeMillis()
                    total += n
                    if (System.currentTimeMillis() - start > SPEED_TEST_MS) break
                }
            }
            val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
            val kbps = total * 8 / ms
            "$kbps kbit/s (${total / 1024} KB in $ms ms). ${speedVerdict(kbps)}"
        } finally {
            c.disconnect()
        }
    }.getOrElse { "failed (${describe(it)})" }

    /** What [kbps] is enough for, in the car. */
    internal fun speedVerdict(kbps: Long): String = when {
        kbps < 200 -> "Too slow for most streams."
        kbps < 1000 -> "Enough for radio; TV will stall."
        kbps < 2500 -> "Enough for radio and low-quality TV."
        else -> "Enough for radio and TV."
    }

    private const val SPEED_TEST_BYTES = 1_000_000
    private const val SPEED_TEST_MS = 8_000L

    private fun testStream(channel: Channel, out: StringBuilder) {
        var url = URL(channel.url)
        out.appendLine("  DNS ${url.host}: ${resolve(url.host)}")
        // Follow redirects by hand so each hop shows.
        var response: Response? = null
        for (hop in 0 until 6) {
            // A radio stream never ends: stop after the first bytes unless it's a playlist.
            val r = get(url, channel.userAgent, channel.referrer, 512 * 1024, playlistOnly = true)
            out.appendLine("  ${url.host}: HTTP ${r.code} (${r.ms} ms)")
            if (r.code in 300..399 && r.location != null) {
                url = URL(url, r.location)
                if (hop == 0) out.appendLine("  DNS ${url.host}: ${resolve(url.host)}")
                continue
            }
            response = r
            break
        }
        val r = response ?: run { out.appendLine("  too many redirects"); return }
        if (r.code != 200) {
            out.appendLine("  ${verdict(r.code)}")
            return
        }
        if (!isPlaylist(r.body)) {
            out.appendLine("  Not an HLS playlist (direct stream). Connected fine.")
            return
        }
        // Multivariant playlist: test the lowest-bandwidth variant (what plays while driving).
        val variant = lowestVariant(r.body)
        val media = if (variant != null) {
            val variantUrl = URL(url, variant)
            val v = get(variantUrl, channel.userAgent, channel.referrer, 512 * 1024)
            out.appendLine("  variant ${variantUrl.host}: HTTP ${v.code} (${v.ms} ms)")
            if (v.code != 200) { out.appendLine("  ${verdict(v.code)}"); return }
            url = variantUrl
            v.body
        } else {
            r.body
        }
        val segment = media.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: run { out.appendLine("  playlist has no segments"); return }
        val segUrl = URL(url, segment)
        val s = get(segUrl, channel.userAgent, channel.referrer, 2 * 1024 * 1024)
        val kbps = if (s.ms > 0) s.bytes * 8 / s.ms else 0
        out.appendLine("  segment ${segUrl.host}: HTTP ${s.code} (${s.bytes / 1024} KB in ${s.ms} ms, about $kbps kbit/s)")
        out.appendLine("  ${if (s.code == 200) "Stream reachable on this connection." else verdict(s.code)}")
    }

    /** The URI of the variant with the lowest BANDWIDTH, or null if this is a media playlist. */
    private fun lowestVariant(text: String): String? {
        var best: String? = null
        var bestBw = Long.MAX_VALUE
        var pending: Long? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pending = Regex("[:,]BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE - 1
            } else if (pending != null && line.isNotEmpty() && !line.startsWith("#")) {
                if (pending < bestBw) { bestBw = pending; best = line }
                pending = null
            }
        }
        return best
    }

    private fun verdict(code: Int): String = when (code) {
        401, 403 -> "Refused: the server blocks this connection (often by location or network type)."
        404 -> "Not found: the stream address has changed or expired. Update the playlist."
        410 -> "Gone: the address has expired, or the server blocks this connection (StreamTheWorld " +
            "does this on the car's internet). If it plays on Wi-Fi, use a hotspot for it."
        in 500..599 -> "The server had an error. Try again later."
        else -> "Unexpected answer."
    }

    private fun resolve(host: String): String = runCatching {
        val start = System.currentTimeMillis()
        val addresses = InetAddress.getAllByName(host)
        val ms = System.currentTimeMillis() - start
        addresses.take(3).joinToString(", ") { it.hostAddress.orEmpty() } + " ($ms ms)"
    }.getOrElse { "failed (${describe(it)})" }

    private class Response(val code: Int, val location: String?, val body: String, val bytes: Long, val ms: Long)

    private fun isPlaylist(text: String) = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")

    private fun get(
        url: URL,
        agent: String?,
        referrer: String?,
        maxBytes: Int,
        playlistOnly: Boolean = false,
    ): Response {
        val start = System.currentTimeMillis()
        val c = url.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", agent?.takeIf { it.isNotBlank() } ?: "MG4Streams")
        referrer?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Referer", it) }
        try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val buffer = java.io.ByteArrayOutputStream()
            var total = 0L
            stream?.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (total < maxBytes) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (buffer.size() < 256 * 1024) buffer.write(chunk, 0, n)
                    if (playlistOnly && buffer.size() >= 16 && !isPlaylist(buffer.toString("UTF-8").take(64))) break
                }
            }
            return Response(code, c.getHeaderField("Location"), buffer.toString("UTF-8"), total, System.currentTimeMillis() - start)
        } finally {
            c.disconnect()
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "can't resolve the address"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "secure connection failed (${t.message})"
        is java.net.ConnectException -> "can't connect (${t.message})"
        else -> t.message ?: t.javaClass.simpleName
    }
}
