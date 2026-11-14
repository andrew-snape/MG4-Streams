package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLibraryTest {

    @Test fun naturalOrder() {
        val names = listOf("Episode 10.mkv", "episode 2.mkv", "Episode 1.mkv", "S01E11.mp4", "S01E02.mp4", "S01E001.mp4")
        assertEquals(
            listOf("Episode 1.mkv", "episode 2.mkv", "Episode 10.mkv", "S01E001.mp4", "S01E02.mp4", "S01E11.mp4"),
            names.sortedWith(UsbLibrary.NATURAL),
        )
        assertTrue(UsbLibrary.naturalCompare("Season 2", "Season 10") < 0)
        assertEquals(0, UsbLibrary.naturalCompare("abc", "ABC"))
    }

    @Test fun sceneName() {
        val info = UsbLibrary.parseName("The.Office.US.S02E05.Halloween.1080p.WEB-DL.x264")
        assertEquals("The Office US", info.show)
        assertEquals(2, info.season)
        assertEquals(5, info.episode)
        assertEquals("Halloween", info.title)
        assertEquals("S02E05", info.code)
    }

    @Test fun plexName() {
        val info = UsbLibrary.parseName("Bluey - S01E03 - Keepy Uppy", 1, "Bluey")
        assertEquals("Bluey", info.show)
        assertEquals("Keepy Uppy", info.title)
    }

    @Test fun crossFormatAndFolders() {
        val x = UsbLibrary.parseName("3x07 The Return", null, "Doctor Who")
        assertEquals(3, x.season); assertEquals(7, x.episode); assertEquals("Doctor Who", x.show); assertEquals("The Return", x.title)
        val lead = UsbLibrary.parseName("04 - The Lift", 2, "Utopia")
        assertEquals(2, lead.season); assertEquals(4, lead.episode); assertEquals("The Lift", lead.title)
        val ep = UsbLibrary.parseName("Episode 12", 1, "Show")
        assertEquals(12, ep.episode); assertNull(ep.title)
        val none = UsbLibrary.parseName("Home movie", null, "Videos")
        assertNull(none.episode); assertNull(none.code)
    }

    @Test fun seasons() {
        assertEquals(2, UsbLibrary.seasonNumber("Season 2"))
        assertEquals(10, UsbLibrary.seasonNumber("series 10"))
        assertEquals(3, UsbLibrary.seasonNumber("S03"))
        assertNull(UsbLibrary.seasonNumber("Specials"))
        assertNull(UsbLibrary.seasonNumber("Season of the Witch"))
    }

    @Test fun nfo() {
        assertEquals("Keepy Uppy & Friends", UsbLibrary.nfoTitle("<episodedetails>\n  <title> Keepy Uppy &amp; Friends </title><plot>x</plot>"))
        assertNull(UsbLibrary.nfoTitle("<episodedetails><plot>x</plot>"))
    }

    @Test fun positions() {
        assertEquals("TV/Show/S01E02.mkv", UsbPositions.relative("/storage/1234-ABCD/TV/Show/S01E02.mkv"))
        assertEquals("TV/a.mp4", UsbPositions.relative("/mnt/media_rw/1234-ABCD/TV/a.mp4"))
        val e = UsbPositions.parse("1200000|1260000|5")!!
        assertTrue(e.watched)
        assertEquals(952, e.progress)
        assertFalse(UsbPositions.parse("60000|1260000|5")!!.watched)
        assertNull(UsbPositions.parse("x"))
    }
}
