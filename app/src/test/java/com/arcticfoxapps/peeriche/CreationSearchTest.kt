package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class CreationSearchTest {
    @Test fun importUrlPreservesQueriesAndRejectsNonWebUrlsAndCredentials() {
        assertEquals("https://media.example/watch?v=123&list=456", validatedImportUrl(" https://media.example/watch?v=123&list=456 "))
        assertEquals("http://media.example/video.mp4", validatedImportUrl("http://media.example/video.mp4"))
        for (url in listOf("", "file:///video.mp4", "javascript:alert(1)", "https://user:secret@media.example/video", "https://media.example/#fragment")) {
            try { validatedImportUrl(url); fail(url) } catch (failure: AuthFailure) { assertEquals("invalid_import_url", failure.reason) }
        }
    }
    @Test fun sortingCombinesLoadedPagesAndAccountsForDateOffsets() {
        val old = Video(uuid = "old", publishedAt = "2026-11-09T13:00:00+03:00", views = 90)
        val recent = Video(uuid = "new", publishedAt = "2026-11-09T11:00:00Z", views = 1)
        val unknown = Video(uuid = "unknown", views = 5)
        val loaded = listOf(old, unknown) + listOf(recent, old)
        assertEquals(listOf("new", "old", "unknown"), sortSearchVideos(loaded, "-publishedAt", "https://server.example").map { it.uuid })
        assertEquals(listOf("old", "unknown", "new"), sortSearchVideos(loaded, "-views", "https://server.example").map { it.uuid })
        assertEquals(listOf("old", "unknown", "new"), sortSearchVideos(loaded, "-match", "https://server.example").map { it.uuid })
    }
}
