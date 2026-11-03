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
}
