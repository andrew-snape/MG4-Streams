package com.mg4.streams

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdaterFileNameTest {
    @Test
    fun namesTheDownload() {
        assertEquals("MG4Streams-0.3.0.apk", Updater.fileName("v0.3.0"))
        assertEquals("MG4Streams-0.3.0-beta.apk", Updater.fileName("0.3.0-beta"))
        assertEquals("MG4Streams-0.3.0..x.apk", Updater.fileName("v0.3.0/../x")) // no slash survives
        assertEquals("MG4Streams-update.apk", Updater.fileName("  "))
    }
}
