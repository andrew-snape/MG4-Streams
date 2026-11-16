package com.mg4.streams

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NetGateTest {

    @Before fun setUp() = NetGate.resetForTest()
    @After fun tearDown() = NetGate.resetForTest()

    @Test fun runsStraightAwayWhenNoStationIsStarting() {
        assertEquals(42, NetGate.background("Logo") { 42 })
    }

    @Test fun waitsWhileTheStationStartsThenRuns() {
        NetGate.setStreamBusy(true)
        val ran = CountDownLatch(1)
        Thread { NetGate.background("TV guide") { ran.countDown() } }.start()
        assertFalse(ran.await(700, TimeUnit.MILLISECONDS))
        assertTrue(NetGate.describe().contains("TV guide"))
        NetGate.setStreamBusy(false)
        assertTrue(ran.await(2, TimeUnit.SECONDS))
    }

    @Test fun expectStreamHoldsUntilItExpires() {
        val now = AtomicLong(1_000_000)
        NetGate.clock = { now.get() }
        NetGate.expectStream()
        assertTrue(NetGate.isHolding())
        val ran = CountDownLatch(1)
        Thread { NetGate.background("Playlist") { ran.countDown() } }.start()
        assertFalse(ran.await(700, TimeUnit.MILLISECONDS))
        now.addAndGet(NetGate.EXPECT_MS)
        assertTrue(ran.await(2, TimeUnit.SECONDS))
        assertFalse(NetGate.isHolding())
    }

    @Test fun serviceTakesOverFromTheScreensHint() {
        NetGate.clock = { 1_000_000 }
        NetGate.expectStream()
        NetGate.setStreamBusy(true)
        NetGate.setStreamBusy(false)
        assertFalse(NetGate.isHolding())
    }

    @Test fun givesUpWaitingAfterMaxWait() {
        val now = AtomicLong(1_000_000)
        NetGate.clock = { now.get() }
        NetGate.setStreamBusy(true)
        val ran = CountDownLatch(1)
        Thread { NetGate.background("Logo") { ran.countDown() } }.start()
        assertFalse(ran.await(700, TimeUnit.MILLISECONDS))
        now.addAndGet(NetGate.MAX_WAIT_MS)
        assertTrue(ran.await(2, TimeUnit.SECONDS))
    }

    @Test fun runsOneAtATime() {
        val inside = AtomicInteger(0)
        val most = AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                NetGate.background("Logo") {
                    most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    Thread.sleep(50)
                    inside.decrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        assertEquals(1, most.get())
    }

    @Test fun summariseGroupsRepeats() {
        assertEquals("none", NetGate.summarise(emptyList()))
        assertEquals("Logo ×3, TV guide", NetGate.summarise(listOf("Logo", "TV guide", "Logo", "Logo")))
    }

    @Test fun speedVerdicts() {
        assertEquals("Too slow for most streams.", NetCheck.speedVerdict(100))
        assertEquals("Enough for radio; TV will stall.", NetCheck.speedVerdict(600))
        assertEquals("Enough for radio and low-quality TV.", NetCheck.speedVerdict(2000))
        assertEquals("Enough for radio and TV.", NetCheck.speedVerdict(5000))
    }
}
