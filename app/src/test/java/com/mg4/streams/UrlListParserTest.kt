package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlListParserTest {

    @Test
    fun keyedLinesHeadersAndBareUrls() {
        val text = """
            # my streams
            https://a.example/default.m3u
            radio = https://a.example/radio.m3u8
            TV: https://a.example/tv.m3u8

            [my list]
            https://a.example/mine1.m3u
            https://a.example/mine2.m3u
            https://a.example/mine1.m3u
            [nonsense]
            https://a.example/skipped.m3u
            [tv]
            https://a.example/tv2.m3u8	# trailing comment
            radio = https://a.example/r2.m3u?x=1&y=2  # comment
            junk line
            ftp://ignored
        """.trimIndent()
        val lists = UrlListParser.parse(text, Tab.TV)
        assertEquals(
            listOf("https://a.example/default.m3u", "https://a.example/tv.m3u8", "https://a.example/tv2.m3u8"),
            lists[Tab.TV],
        )
        assertEquals(listOf("https://a.example/radio.m3u8", "https://a.example/r2.m3u?x=1&y=2"), lists[Tab.RADIO])
        assertEquals(listOf("https://a.example/mine1.m3u", "https://a.example/mine2.m3u"), lists[Tab.MY_LIST])
    }

    @Test
    fun emptyOrCommentsOnly() {
        assertTrue(UrlListParser.parse("# nothing\n\n", Tab.RADIO).isEmpty())
    }

    @Test
    fun slotNames() {
        assertEquals(Tab.MY_LIST, UrlListParser.slotFor("My List"))
        assertEquals(Tab.MY_LIST, UrlListParser.slotFor("custom"))
        assertEquals(Tab.TV, UrlListParser.slotFor(" tv "))
    }
}
