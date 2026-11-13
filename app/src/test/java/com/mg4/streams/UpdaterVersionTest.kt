package com.mg4.streams

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test
    fun compares() {
        assertTrue(Updater.isNewer("v0.3.0", "0.2.0"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("v1.0", "0.9.9"))
        assertFalse(Updater.isNewer("v0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("v0.1.9", "0.2.0"))
        assertFalse(Updater.isNewer("nightly", "0.2.0"))
        assertTrue(Updater.isNewer("v0.2.1-beta", "0.2.0"))
    }
}
