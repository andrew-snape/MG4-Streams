package com.mg4.streams

/** The top-bar tabs. Radio, TV and My list each have a playlist file; USB browses the stick. */
enum class Tab(val label: String) {
    RADIO("Radio"), TV("TV"), MY_LIST("My list"),
    /** Files on a USB stick (no playlist file). */
    USB("USB");

    companion object {
        fun fromName(name: String?): Tab = values().firstOrNull { it.name == name } ?: RADIO
        /** The tabs that come from playlist files. */
        val PLAYLISTS = listOf(RADIO, TV, MY_LIST)
    }
}

/** What the grid shows within a tab: favourites, recently played (all tabs) or everything. */
enum class Filter(val label: String) {
    FAVOURITES("FAVOURITES"), RECENT("RECENT"), ALL("ALL");

    companion object {
        fun fromName(name: String?): Filter? = values().firstOrNull { it.name == name }
    }
}

/** Pure list logic for the screen: tab filter, search, favourites, recents, presets, stepping. */
object StationLists {

    /** Each tab has its own playlist slot; a channel belongs to the tab of its slot. */
    fun tabOf(channel: Channel): Tab = channel.source

    fun filter(
        channels: List<Channel>,
        tab: Tab,
        query: String,
        favourites: Set<String>,
        favouritesOnly: Boolean,
    ): List<Channel> =
        filter(channels, tab, query, favourites, if (favouritesOnly) Filter.FAVOURITES else Filter.ALL)

    /**
     * The grid's stations. RECENT ignores the tab and group (it spans all tabs, newest first).
     * [group] limits ALL and FAVOURITES to one group-title; null means every group.
     */
    fun filter(
        channels: List<Channel>,
        tab: Tab,
        query: String,
        favourites: Set<String>,
        filter: Filter,
        recents: List<String> = emptyList(),
        group: String? = null,
    ): List<Channel> {
        val q = query.trim()
        fun matches(ch: Channel) = q.isEmpty() || ch.name.contains(q, ignoreCase = true)
        if (filter == Filter.RECENT) {
            val byUrl = channels.associateBy { it.url }
            return recents.mapNotNull { byUrl[it] }.filter(::matches)
        }
        return channels.filter { ch ->
            tabOf(ch) == tab && matches(ch) &&
                (group == null || ch.group == group) &&
                (filter != Filter.FAVOURITES || ch.url in favourites)
        }
    }

    /** The distinct group-titles in [tab], in playlist order. */
    fun groups(channels: List<Channel>, tab: Tab): List<String> =
        channels.asSequence().filter { tabOf(it) == tab }
            .mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
            .distinct().toList()

    /**
     * The driving-mode preset row: favourites first (in the order they were added, any tab), then
     * recently played stations to fill up to [count]. Stations no longer in a playlist are skipped.
     */
    fun presets(channels: List<Channel>, favourites: Collection<String>, recents: List<String>, count: Int = 6): List<Channel> {
        val byUrl = channels.associateBy { it.url }
        return (favourites.asSequence() + recents.asSequence())
            .distinct().mapNotNull { byUrl[it] }.take(count).toList()
    }

    /** [recents] with [url] moved to the front, at most [max] long. */
    fun pushRecent(recents: List<String>, url: String, max: Int = MAX_RECENTS): List<String> =
        (listOf(url) + recents.filter { it != url }).take(max)

    const val MAX_RECENTS = 20

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
