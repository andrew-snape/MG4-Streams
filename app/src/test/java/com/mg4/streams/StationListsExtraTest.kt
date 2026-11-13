package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Test

class StationListsExtraTest {
    private fun ch(name: String, tab: Tab, group: String? = null) = Channel(name, "https://x/$name", group, null, source = tab)

    private val all = listOf(
        ch("A", Tab.RADIO, "Au"), ch("B", Tab.RADIO, "World"), ch("C", Tab.TV, "Au"),
        ch("D", Tab.TV, "Melbourne"), ch("E", Tab.MY_LIST),
    )

    @Test fun recentSpansTabsNewestFirstAndSkipsMissing() {
        val recents = listOf("https://x/D", "https://x/gone", "https://x/A")
        assertEquals(listOf("D", "A"), StationLists.filter(all, Tab.RADIO, "", emptySet(), Filter.RECENT, recents).map { it.name })
        assertEquals(listOf("A"), StationLists.filter(all, Tab.RADIO, "a", emptySet(), Filter.RECENT, recents).map { it.name })
    }

    @Test fun groupFilterAndGroups() {
        assertEquals(listOf("Au", "Melbourne"), StationLists.groups(all, Tab.TV))
        assertEquals(listOf("D"), StationLists.filter(all, Tab.TV, "", emptySet(), Filter.ALL, group = "Melbourne").map { it.name })
        assertEquals(emptyList<String>(), StationLists.groups(all, Tab.MY_LIST))
    }

    @Test fun presetsFavouritesThenRecents() {
        val favs = linkedSetOf("https://x/C", "https://x/gone")
        val recents = listOf("https://x/C", "https://x/A", "https://x/B")
        assertEquals(listOf("C", "A"), StationLists.presets(all, favs, recents, count = 2).map { it.name })
        assertEquals(listOf("C", "A", "B"), StationLists.presets(all, favs, recents).map { it.name })
    }

    @Test fun pushRecentMovesToFrontAndCaps() {
        assertEquals(listOf("b", "a", "c"), StationLists.pushRecent(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("z", "a"), StationLists.pushRecent(listOf("a", "b"), "z", max = 2))
    }
}
