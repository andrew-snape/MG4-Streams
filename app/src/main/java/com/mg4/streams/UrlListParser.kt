package com.mg4.streams

/**
 * Reads a plain-text list of playlist addresses, for example `streams.txt` on a USB stick:
 *
 * ```
 * # Lines starting with # are comments.
 * radio = https://example.com/radio.m3u8
 * tv: https://example.com/tv.m3u8
 *
 * [my list]
 * https://example.com/mine-1.m3u
 * https://example.com/mine-2.m3u
 * ```
 *
 * A line can name its tab (`radio`, `tv`, `my list` / `mylist` / `mine` / `custom`, then `=` or
 * `:`), or a `[tab]` header applies to the bare addresses below it. Bare addresses before any
 * header go to [defaultSlot]. Only http(s) addresses are kept; duplicates are dropped.
 */
object UrlListParser {

    private val KEYED = Regex("""^([A-Za-z ]+?)\s*[=:]\s*(https?://\S+)(\s.*)?$""", RegexOption.IGNORE_CASE)
    private val HEADER = Regex("""^\[([^\]]*)]$""")

    fun parse(text: String, defaultSlot: Tab): Map<Tab, List<String>> {
        val result = LinkedHashMap<Tab, MutableList<String>>()
        // Null under an unknown [header]: its addresses are skipped, not put in another tab.
        var section: Tab? = defaultSlot
        text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            HEADER.matchEntire(line)?.let { match ->
                section = slotFor(match.groupValues[1])
                return@forEach
            }
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                // Anything after the address (e.g. "# comment") is ignored.
                section?.let { add(result, it, line.split(WHITESPACE, 2)[0]) }
                return@forEach
            }
            KEYED.matchEntire(line)?.let { match ->
                val slot = slotFor(match.groupValues[1]) ?: return@forEach
                add(result, slot, match.groupValues[2])
            }
        }
        return result
    }

    private val WHITESPACE = Regex("\\s+")

    fun slotFor(name: String): Tab? = when (name.trim().lowercase().replace(" ", "")) {
        "radio" -> Tab.RADIO
        "tv" -> Tab.TV
        "mylist", "mine", "custom", "my" -> Tab.MY_LIST
        else -> null
    }

    private fun add(result: MutableMap<Tab, MutableList<String>>, slot: Tab, url: String) {
        val list = result.getOrPut(slot) { mutableListOf() }
        if (url !in list) list += url
    }
}
