package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavouritesTest {
    @Test fun formatParseRoundTripKeepsOrderAndDropsJunk() {
        val text = FavouritesStore.format(listOf("https://a/1", "http://b/2"))
        assertEquals("https://a/1\nhttp://b/2\n", text)
        val parsed = FavouritesStore.parse("\n junk\nhttps://a/1\r\nhttps://a/1\nhttp://b/2\n")
        assertEquals(listOf("https://a/1", "http://b/2"), parsed.toList())
    }

    @Test fun toggleAddsThenRemoves() {
        val set = LinkedHashSet<String>()
        assertTrue(FavouritesStore.toggle(set, "https://a/1"))
        assertFalse(FavouritesStore.toggle(set, "https://a/1"))
        assertTrue(set.isEmpty())
    }

    @Test fun saveAndLoad() {
        val dir = createTempDir()
        val store = FavouritesStore(File(dir, "favourites.txt"))
        assertTrue(store.load().isEmpty())
        store.save(listOf("https://a/1", "https://a/2"))
        assertEquals(listOf("https://a/1", "https://a/2"), store.load().toList())
        dir.deleteRecursively()
    }
}
