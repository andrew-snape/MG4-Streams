package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTheWorldTest {

    private val base = "https://playerservices.streamtheworld.com/api/livestream-redirect/"

    @Test fun numberedServerGoesToLoadBalancer() {
        assertEquals(
            base + "GOLD_104_3AAC.aac",
            StreamTheWorld.redirectFor("https://23203.live.streamtheworld.com/GOLD_104_3AAC.aac"),
        )
    }

    @Test fun keepsMountSpellingPortAndQuery() {
        assertEquals(
            base + "KIIS_101_1AAC_SC?dist=web",
            StreamTheWorld.redirectFor("http://14983.live.streamtheworld.com:3690/KIIS_101_1AAC_SC?dist=web"),
        )
        assertEquals(base + "TRIPLEM.mp3", StreamTheWorld.redirectFor("https://28123a.live.streamtheworld.com/TRIPLEM.mp3"))
    }

    @Test fun leavesOtherAddressesAlone() {
        assertNull(StreamTheWorld.redirectFor("https://playerservices.streamtheworld.com/api/livestream-redirect/GOLD_104_3AAC.aac"))
        assertNull(StreamTheWorld.redirectFor("https://example.com/GOLD_104_3AAC.aac"))
        assertNull(StreamTheWorld.redirectFor("https://23203.live.streamtheworld.com/"))
        assertNull(StreamTheWorld.redirectFor("https://23203.live.streamtheworld.com/a/b.aac"))
        assertNull(StreamTheWorld.redirectFor("not a url"))
    }

    @Test fun onlyGoneCodesSwitch() {
        assertTrue(StreamTheWorld.isGone(410))
        assertTrue(StreamTheWorld.isGone(404))
        assertFalse(StreamTheWorld.isGone(403))
        assertFalse(StreamTheWorld.isGone(503))
    }
}

class NetCheckDohTest {
    @Test fun readsOnlyIpv4AnswersFromDohJson() {
        val json = """{"Status":0,"Answer":[{"name":"a.live.streamtheworld.com","type":5,"TTL":60,"data":"b.net."},""" +
            """{"name":"b.net","type":1,"TTL":60,"data":"1.2.3.4"},{"name":"b.net","type":1,"TTL":60,"data":"5.6.7.8"}]}"""
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), NetCheck.dohAddresses(json))
        assertEquals(emptyList<String>(), NetCheck.dohAddresses("""{"Status":3}"""))
    }
}
