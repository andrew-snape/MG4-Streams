package com.mg4.streams

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stations that failed today on the current kind of connection ("dead"). Previous/next skip them;
 * tapping one still plays it. A station counts as dead only for the day and the connection it
 * failed on: Seven can be blocked on the car's internet and fine on a hotspot. Playing a station
 * successfully clears it. Prefs file "dead_stations": url -> "yyyy-MM-dd|<NetKind.key>".
 */
object DeadStations {

    const val PREFS = "dead_stations"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mark(context: Context, url: String) {
        prefs(context).edit().putString(url, stamp(today(), NetKind.current(context).key)).apply()
    }

    fun clear(context: Context, url: String) {
        val prefs = prefs(context)
        if (prefs.contains(url)) prefs.edit().remove(url).apply()
    }

    /** URLs dead today on the current connection. Also drops entries from earlier days. */
    fun current(context: Context): Set<String> {
        val prefs = prefs(context)
        val day = today()
        val now = stamp(day, NetKind.current(context).key)
        val all = prefs.all
        val old = all.filter { (_, v) -> (v as? String)?.substringBefore('|') != day }.keys
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
        return all.filter { (_, v) -> v == now }.keys
    }

    fun stamp(day: String, kind: String) = "$day|$kind"

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * The first index from [from] (exclusive) in direction [dir] (+1/-1), wrapping, that isn't
     * dead. -1 if every other station is dead, or [dir] is 0.
     */
    fun nextAlive(count: Int, from: Int, dir: Int, isDead: (Int) -> Boolean): Int {
        if (count <= 1 || dir == 0) return -1
        var i = from
        repeat(count - 1) {
            i = ((i + dir) % count + count) % count
            if (!isDead(i)) return i
        }
        return -1
    }

    /** +1 or -1 if [new] is next to [old] in a wrapping list of [count], else 0. */
    fun direction(old: Int, new: Int, count: Int): Int = when {
        count < 2 || old < 0 || new < 0 || old == new -> 0
        (old + 1) % count == new -> 1
        (old - 1 + count) % count == new -> -1
        else -> 0
    }
}
