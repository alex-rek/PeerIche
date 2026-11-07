package com.arcticfoxapps.peeriche

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LiveVideosTest {
    private fun candidate(id: String) = Video(uuid = id, host = "video.example", isLive = true)

    @Test fun verifiesOriginAndExcludesWaitingEndedAndUnavailableStreams() = runBlocking {
        val found = resolveLiveVideos(listOf("active", "waiting", "ended", "replay", "offline", "missing").map(::candidate), "https://video.example") {
            if (it.uuid == "offline") error("offline")
            it.copy(isLive = it.uuid != "replay", state = VideoState(when (it.uuid) { "waiting" -> 4; "ended" -> 5; else -> 1 }),
                directStreamUrl = if (it.uuid == "missing") null else "https://video.example/live.m3u8")
        }
        assertEquals(listOf("active"), found.map { it.uuid })
    }

    @Test fun deduplicatesBeforeResolvingAndAcceptsOlderServersWithoutState() = runBlocking {
        var requests = 0
        val found = resolveLiveVideos(listOf(candidate("one"), candidate("one")), "https://video.example") {
            requests++
            it.copy(directStreamUrl = "https://video.example/live.m3u8")
        }
        assertEquals(1, requests)
        assertEquals(1, found.size)
    }

    @Test fun leavingFeedCancelsOriginChecks() = runBlocking {
        var completed = false
        val job = launch {
            resolveLiveVideos(listOf(candidate("one")), "https://video.example") {
                delay(10_000); completed = true; it
            }
        }
        yield()
        job.cancelAndJoin()
        assertFalse(completed)
        assertTrue(job.isCancelled)
    }
    @Test fun skipsWaitingIndexPagesAndReturnsTheFirstActivePageWithCorrectCursor() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = scanLivePages(0, "https://video.example", false, fetch = { page ->
            requested += page
            VideoPage(listOf(candidate("$page")), total = 61)
        }, resolve = { it.copy(state = VideoState(if (it.uuid == "2") 1 else 4), directStreamUrl = "https://video.example/live.m3u8") })
        assertEquals(listOf(0, 1, 2), requested)
        assertEquals("2", result.videos.single().uuid)
        assertEquals(3, result.nextPage)
        assertFalse(result.hasMore)
    }
    @Test fun boundsEmptyPageScanningAndKeepsContinuationInsteadOfClaimingNoMoreLiveStreams() = runBlocking {
        var count = 0
        val result = scanLivePages(0, "https://video.example", false, fetch = {
            count++; VideoPage(listOf(candidate("$it")), total = 1000)
        }, resolve = { it.copy(state = VideoState(5)) }, maxPages = 2)
        assertEquals(2, count)
        assertTrue(result.hasMore); assertEquals(2, result.nextPage); assertTrue(result.videos.isEmpty())
    }
    @Test fun stopsEmptyCatalogAndExcludesSensitiveStreams() = runBlocking {
        val result = scanLivePages(0, "https://video.example", false,
            fetch = { VideoPage(listOf(candidate("hidden").copy(nsfw = true)), total = 1) },
            resolve = { error("Sensitive stream must not be resolved") })
        assertFalse(result.hasMore); assertTrue(result.videos.isEmpty())
    }
    @Test fun unreachableOriginDoesNotCauseOneTimeoutForEveryVideoOnTheSameHost() = runBlocking {
        var requests = 0
        val result = resolveLiveVideos((1..30).map { candidate("$it") }, "https://video.example") {
            requests++; throw java.io.IOException("origin down")
        }
        assertTrue(result.isEmpty()); assertTrue(requests <= 6)
    }
}
