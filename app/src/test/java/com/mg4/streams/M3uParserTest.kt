package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun resource(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun tvListHasHeadersIdsAndGuide() {
        val playlist = M3uParser.parsePlaylist(resource("sample-tv.m3u8"))
        assertEquals(listOf("https://example.com/epg.xml.gz"), playlist.epgUrls)
        val one = playlist.channels.first { it.name == "Channel One" }
        assertEquals("https://example.com/live/one.m3u8", one.url)
        assertEquals("ch-one", one.tvgId)
        assertEquals("ExampleAgent/1.0 (TV)", one.userAgent)
        assertNull(one.referrer) // "#EXTVLCOPT:http-referrer=" is empty
        val two = playlist.channels.first { it.name == "Channel Two" }
        assertEquals("https://example.com/", two.referrer)
        assertTrue(playlist.channels.all { it.userAgent != null })
    }

    @Test
    fun kodiPipeHeaders() {
        val text = "#EXTM3U url-tvg=\"https://a/epg.xml,https://b/epg.xml.gz\"\n" +
            "#EXTINF:-1 tvg-id=\"x\",X\nhttps://h/x.m3u8|User-Agent=Foo%20Bar&Referer=https%3A%2F%2Fr%2F\n"
        val playlist = M3uParser.parsePlaylist(text)
        assertEquals(listOf("https://a/epg.xml", "https://b/epg.xml.gz"), playlist.epgUrls)
        val ch = playlist.channels.single()
        assertEquals("https://h/x.m3u8", ch.url)
        assertEquals("Foo Bar", ch.userAgent)
        assertEquals("https://r/", ch.referrer)
    }

    @Test
    fun headersDoNotLeakToNextEntry() {
        val text = "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=UA\nhttps://a\n#EXTINF:-1,B\nhttps://b\n"
        val channels = M3uParser.parse(text)
        assertEquals("UA", channels[0].userAgent)
        assertNull(channels[1].userAgent)
    }
}
