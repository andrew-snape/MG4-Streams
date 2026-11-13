package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationListsTest {
    private fun ch(name: String, group: String?, custom: Boolean = false) =
        Channel(
            name, "https://x/$name", group, null,
            source = when {
                custom -> Tab.MY_LIST
                group.equals("TV", ignoreCase = true) -> Tab.TV
                else -> Tab.RADIO
            },
        )

    private val all = listOf(
        ch("Triple J", "Radio"), ch("ABC Classic", "Radio"), ch("Seven", "TV"),
        ch("Nine", "TV"), ch("My Jazz", "Radio", custom = true), ch("Mine TV", "TV", custom = true),
    )

    @Test fun tabsSplitByGroupAndSource() {
        assertEquals(listOf("Triple J", "ABC Classic"), StationLists.filter(all, Tab.RADIO, "", emptySet(), false).map { it.name })
        assertEquals(listOf("Seven", "Nine"), StationLists.filter(all, Tab.TV, "", emptySet(), false).map { it.name })
        assertEquals(listOf("My Jazz", "Mine TV"), StationLists.filter(all, Tab.MY_LIST, "", emptySet(), false).map { it.name })
    }

    @Test fun searchIsCaseInsensitiveAndFavouritesFilter() {
        assertEquals(listOf("Triple J"), StationLists.filter(all, Tab.RADIO, " triple", emptySet(), false).map { it.name })
        val favs = setOf("https://x/Nine")
        assertEquals(listOf("Nine"), StationLists.filter(all, Tab.TV, "", favs, true).map { it.name })
        assertTrue(StationLists.filter(all, Tab.RADIO, "", favs, true).isEmpty())
    }

    @Test fun stepWraps() {
        val list = StationLists.filter(all, Tab.TV, "", emptySet(), false)
        assertEquals(1, StationLists.step(list, "https://x/Seven", 1))
        assertEquals(0, StationLists.step(list, "https://x/Nine", 1))
        assertEquals(1, StationLists.step(list, "https://x/Seven", -1))
        assertEquals(0, StationLists.step(list, "https://x/other", 1))
        assertEquals(1, StationLists.step(list, null, -1))
        assertEquals(-1, StationLists.step(emptyList(), null, 1))
    }

    @Test fun tabNameRoundTrip() {
        assertEquals(Tab.TV, Tab.fromName("TV"))
        assertEquals(Tab.RADIO, Tab.fromName("junk"))
        assertFalse(Tab.fromName(null) == Tab.MY_LIST)
    }
}
