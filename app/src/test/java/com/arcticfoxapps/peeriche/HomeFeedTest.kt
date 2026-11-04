package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class HomeFeedTest {
    private val server = "https://video.example"
    @Test fun mixesChannelBatchesByActualPublicationTimeAndRemovesDuplicates() {
        val a = Video(uuid = "a", publishedAt = "2026-11-04T10:00:00Z")
        val b = Video(uuid = "b", publishedAt = "2026-11-04T09:00:00.000Z")
        val c = Video(uuid = "c", publishedAt = "2026-11-04T12:30:00+02:00")
        assertEquals(listOf("c", "a", "b"), sortHomeVideos(listOf(a, b, c, a), "newest", server).map { it.uuid })
    }
    @Test fun missingAndInvalidDatesStayLastInBothDirections() {
        val videos = listOf(Video(uuid = "missing"), Video(uuid = "invalid", publishedAt = "bad"),
            Video(uuid = "new", publishedAt = "2026-11-04"), Video(uuid = "old", publishedAt = "2026-10-04"))
        assertEquals(listOf("new", "old", "missing", "invalid"), sortHomeVideos(videos, "newest", server).map { it.uuid })
        assertEquals(listOf("old", "new", "missing", "invalid"), sortHomeVideos(videos, "oldest", server).map { it.uuid })
    }
    @Test fun sortsByViewsAndOlderPreferencesDefaultToNewest() {
        val videos = listOf(Video(uuid = "a", views = 1), Video(uuid = "b", views = 20))
        assertEquals(listOf("b", "a"), sortHomeVideos(videos, "views", server).map { it.uuid })
        assertEquals("newest", com.google.gson.Gson().fromJson("{}", AppOptions::class.java).homeSort)
    }
}
