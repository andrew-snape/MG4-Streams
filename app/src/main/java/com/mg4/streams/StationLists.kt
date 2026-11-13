package com.mg4.streams

/** The three top-bar tabs. */
enum class Tab(val label: String) {
    RADIO("Radio"), TV("TV"), MY_LIST("My list");

    companion object {
        fun fromName(name: String?): Tab = values().firstOrNull { it.name == name } ?: RADIO
    }
}

/** Pure list logic for the screen: tab filter, search, favourites filter, stepping. */
object StationLists {

    /** Each tab has its own playlist slot; a channel belongs to the tab of its slot. */
    fun tabOf(channel: Channel): Tab = channel.source

    fun filter(
        channels: List<Channel>,
        tab: Tab,
        query: String,
        favourites: Set<String>,
        favouritesOnly: Boolean,
    ): List<Channel> {
        val q = query.trim()
        return channels.filter { ch ->
            tabOf(ch) == tab &&
                (q.isEmpty() || ch.name.contains(q, ignoreCase = true)) &&
                (!favouritesOnly || ch.url in favourites)
        }
    }

    /**
     * Index to move to from the channel with [currentUrl], wrapping at both ends. If the current
     * channel is not in the list, next goes to the first and previous to the last. -1 if empty.
     */
    fun step(list: List<Channel>, currentUrl: String?, delta: Int): Int {
        if (list.isEmpty()) return -1
        val index = list.indexOfFirst { it.url == currentUrl }
        if (index < 0) return if (delta > 0) 0 else list.size - 1
        return ((index + delta) % list.size + list.size) % list.size
    }
}
