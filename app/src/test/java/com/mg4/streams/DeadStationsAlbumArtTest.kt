package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeadStationsAlbumArtTest {

    @Test fun nextAliveSkipsDeadForward() {
        val dead = setOf(2, 3)
        assertEquals(4, DeadStations.nextAlive(6, 1, 1) { it in dead })
    }

    @Test fun nextAliveWrapsBackward() {
        assertEquals(4, DeadStations.nextAlive(5, 1, -1) { it == 0 })
    }

    @Test fun nextAliveAllDead() {
        assertEquals(-1, DeadStations.nextAlive(4, 0, 1) { true })
        assertEquals(-1, DeadStations.nextAlive(1, 0, 1) { false })
        assertEquals(-1, DeadStations.nextAlive(4, 0, 0) { false })
    }

    @Test fun direction() {
        assertEquals(1, DeadStations.direction(2, 3, 6))
        assertEquals(1, DeadStations.direction(5, 0, 6))
        assertEquals(-1, DeadStations.direction(0, 5, 6))
        assertEquals(0, DeadStations.direction(1, 4, 6))
        assertEquals(0, DeadStations.direction(-1, 0, 6))
    }

    @Test fun searchTerm() {
        assertEquals("Tame Impala The Less I Know The Better",
            AlbumArt.searchTerm("Tame Impala - The Less I Know The Better (Radio Edit)"))
        assertEquals("Ruel Painkiller", AlbumArt.searchTerm(" Ruel – Painkiller "))
        assertNull(AlbumArt.searchTerm("Triple J"))
        assertNull(AlbumArt.searchTerm("Hack-a-thon live"))
        assertNull(AlbumArt.searchTerm(" - Song"))
    }

    @Test fun artworkFrom() {
        val json = """{"resultCount":1,"results":[{"artistName":"X","artworkUrl100":"https:\/\/is1-ssl.mzstatic.com\/image\/thumb\/Music\/ab\/100x100bb.jpg"}]}"""
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/ab/600x600bb.jpg", AlbumArt.artworkFrom(json))
        assertNull(AlbumArt.artworkFrom("""{"resultCount":0,"results":[]}"""))
        assertNull(AlbumArt.artworkFrom("""{"artworkUrl100":"http://x/100x100bb.jpg"}"""))
    }
}
