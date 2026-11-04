package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class WatchTimeTrackerTest {
    @Test fun openingAndBufferingDoNotCountButFiveSecondsOfPlayingDo() {
        val tracker = WatchTimeTracker()
        assertFalse(tracker.sample("a", 0, false))
        assertFalse(tracker.sample("a", 20_000, true))
        assertFalse(tracker.sample("a", 24_999, true))
        assertTrue(tracker.sample("a", 25_000, true))
        assertFalse(tracker.sample("a", 30_000, true))
    }
    @Test fun pausesDoNotCountAndSameItemRepreparationKeepsAccumulatedTime() {
        val tracker = WatchTimeTracker()
        tracker.sample("a", 0, true)
        assertFalse(tracker.sample("a", 3_000, false))
        assertFalse(tracker.sample("a", 60_000, true))
        assertFalse(tracker.sample("a", 61_999, true))
        assertTrue(tracker.sample("a", 62_000, false))
    }
    @Test fun anotherVideoStartsItsOwnFiveSeconds() {
        val tracker = WatchTimeTracker()
        tracker.sample("a", 0, true)
        assertFalse(tracker.sample("b", 4_000, true))
        assertFalse(tracker.sample("b", 8_999, true))
        assertTrue(tracker.sample("b", 9_000, true))
    }
    @Test fun aspectRatiosHaveExpectedValuesAndAutoUsesSource() {
        assertNull(videoAspectRatio("auto"))
        assertEquals(16f / 9f, videoAspectRatio("16:9")!!, .001f)
        assertEquals(4f / 3f, videoAspectRatio("4:3")!!, .001f)
        assertEquals(1f, videoAspectRatio("1:1")!!, .001f)
        assertEquals(21f / 9f, videoAspectRatio("21:9")!!, .001f)
    }
}
