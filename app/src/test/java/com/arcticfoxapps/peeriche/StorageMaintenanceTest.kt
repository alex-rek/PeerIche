package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class StorageMaintenanceTest {
    @Test fun cacheWithinLimitIsUntouched() {
        val files = listOf(CachedFile("a", 40, 1), CachedFile("b", 60, 2))
        assertEquals(emptyList<String>(), cacheFilesToDelete(files, 100))
    }
    @Test fun removesLeastRecentlyUsedFilesUntilCacheFits() {
        val files = listOf(CachedFile("new", 50, 30), CachedFile("old", 50, 10), CachedFile("middle", 50, 20))
        assertEquals(listOf("old", "middle"), cacheFilesToDelete(files, 60))
    }
    @Test fun neverRemovesTheFileInUse() {
        val files = listOf(CachedFile("current", 80, 1), CachedFile("other", 80, 2))
        assertEquals(listOf("other"), cacheFilesToDelete(files, 100, keep = "current"))
    }
    @Test fun keepsPendingUpdateOnlyWhileItIsNewerAndRecent() {
        assertFalse(shouldDeleteDownloadedUpdate(44, 43, 1_000))
        assertTrue(shouldDeleteDownloadedUpdate(43, 43, 1_000))
        assertTrue(shouldDeleteDownloadedUpdate(42, 43, 1_000))
        assertTrue(shouldDeleteDownloadedUpdate(null, 43, 1_000))
        assertTrue(shouldDeleteDownloadedUpdate(44, 43, 25L * 60 * 60 * 1000))
    }
    @Test fun evictsOldestPositionsAndTreatsLegacyEntriesAsOldest() {
        val ids = setOf("legacy", "old", "new")
        assertEquals(listOf("legacy", "old"), positionsToEvict(ids, mapOf("old" to 10L, "new" to 20L), 1))
        assertEquals(emptyList<String>(), positionsToEvict(ids, emptyMap(), 3))
    }
    @Test fun historyRecommendationsAreOffByDefault() {
        assertFalse(AppOptions().historyRecommendations)
        assertFalse(restoredAppOptions("{\"homeSort\":\"views\"}").historyRecommendations)
    }
}
