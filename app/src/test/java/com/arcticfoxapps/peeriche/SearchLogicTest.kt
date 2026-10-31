package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchLogicTest {
    @Test fun deduplicatesRepeatedResultsOnTheSameInstance() {
        val videos = listOf(
            Video(uuid = "abc", host = "videos.example"),
            Video(uuid = "abc", host = "videos.example"),
            Video(uuid = "abc", host = "other.example")
        )
        assertEquals(2, deduplicateVideos(videos, "https://fallback.example").size)
    }

    @Test fun serverMustUseHttpsAndHaveAHost() {
        assertEquals("https://videos.example", normalizeServer("videos.example"))
        assertThrows(IllegalArgumentException::class.java) { normalizeServer("http://videos.example") }
    }
}
