package com.arcticfoxapps.peeriche

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConfiguredSearchTest {
    @Test fun hybridWaitsForEnoughDistinctVideosAndKeepsPriority() = runBlocking {
        val called = mutableListOf<String>()
        val requests: List<suspend () -> SearchPage> = listOf(
            { called += "first"; SearchPage(List(5) { Video(uuid = "duplicate") }, false) },
            { called += "second"; SearchPage(List(4) { Video(uuid = "new-$it") }, true) },
            { called += "third"; SearchPage(listOf(Video(uuid = "unused")), false) }
        )
        val result = collectSearchBatches(requests, AppOptions(searchMode = "hybrid", searchEnough = 5), "https://video.example")
        assertEquals(listOf("first", "second"), called)
        assertEquals(5, deduplicateVideos(result.flatMap { it.videos }, "https://video.example").size)
        assertTrue(result.any { it.hasMore })
    }

    @Test fun parallelHonorsLimitAndSurvivesOneFailedSource() = runBlocking {
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val requests = (0..4).map { index -> suspend {
            val count = running.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try {
                delay(30)
                if (index == 2) error("source unavailable")
                SearchPage(listOf(Video(uuid = "$index")), false)
            } finally { running.decrementAndGet() }
        } }
        val result = collectSearchBatches(requests, AppOptions(searchMode = "parallel", searchParallel = 2), "https://video.example")
        assertEquals(4, result.size)
        assertEquals(2, maximum.get())
        assertEquals(0, running.get())
    }

    @Test fun cancellingSearchCancelsItsRequests() = runBlocking {
        var finished = false
        val job = launch {
            collectSearchBatches(listOf({ delay(10000); finished = true; SearchPage(emptyList(), false) }), AppOptions(), "https://video.example")
        }
        yield()
        job.cancelAndJoin()
        assertFalse(finished)
        assertTrue(job.isCancelled)
    }
}
