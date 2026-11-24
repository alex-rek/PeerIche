package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlobalInterestsTest {
    private fun video(id: String, host: String) = Video(uuid = id, name = id, host = host)

    @Test fun onlyAFewVideosPerSourceServer() {
        val videos = listOf(video("a1", "a.org"), video("a2", "a.org"), video("b1", "b.org"), video("a3", "a.org"), video("a4", "A.org"))
        assertEquals(listOf("a1", "a2", "b1", "a3"), capPerServer(videos, SEPIA_ORIGIN).map { it.uuid })
        assertEquals(listOf("a1", "b1"), capPerServer(videos, SEPIA_ORIGIN, max = 1).map { it.uuid })
    }

    @Test fun activeServerFirstThenGlobalWithoutDuplicates() {
        val local = listOf(video("x", "home.org"), video("y", "a.org"))
        val global = listOf(video("y", "a.org"), video("z", "b.org"))
        assertEquals(listOf("x", "y", "z"), mergeInterestSources(Result.success(local), Result.success(global), "https://home.org").map { it.uuid })
    }

    @Test fun oneFailedSourceDoesNotEmptyHome() {
        val global = listOf(video("z", "b.org"))
        assertEquals(listOf("z"), mergeInterestSources(Result.failure(Exception("down")), Result.success(global), "https://home.org").map { it.uuid })
        assertEquals(listOf("z"), mergeInterestSources(Result.success(global), Result.failure(Exception("sepia down")), "https://home.org").map { it.uuid })
        assertThrows(Exception::class.java) {
            mergeInterestSources(Result.failure(Exception("down")), Result.failure(Exception("sepia down")), "https://home.org")
        }
    }
}
