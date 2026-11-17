package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Test

class NetCheckDohTest {
    @Test fun readsOnlyIpv4AnswersFromDohJson() {
        val json = """{"Status":0,"Answer":[{"name":"a.live.streamtheworld.com","type":5,"TTL":60,"data":"b.net."},""" +
            """{"name":"b.net","type":1,"TTL":60,"data":"1.2.3.4"},{"name":"b.net","type":1,"TTL":60,"data":"5.6.7.8"}]}"""
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), NetCheck.dohAddresses(json))
        assertEquals(emptyList<String>(), NetCheck.dohAddresses("""{"Status":3}"""))
    }
}
