package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun comparesNumericVersionsRatherThanText() {
        assertTrue(newerVersion("v0.1.10", "0.1.9"))
        assertTrue(newerVersion("1.0.0", "0.99.99"))
        assertFalse(newerVersion("0.1.1", "0.1.1"))
        assertFalse(newerVersion("0.1.0", "0.1.1"))
    }
    @Test fun ignoresUnrecognizedAndPrereleaseTags() {
        assertNull(releaseVersion("v0.1.2-beta"))
        assertNull(releaseVersion("latest"))
        assertNull(releaseVersion("999999999999.0.0"))
        assertFalse(newerVersion("garbage", "0.1.1"))
    }
}
