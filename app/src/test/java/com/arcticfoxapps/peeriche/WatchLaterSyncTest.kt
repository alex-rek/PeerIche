package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class WatchLaterSyncTest {
    private fun state(key: String, value: String, element: Long? = 7) = WatchLaterSyncState("account", key, element, value)

    @Test fun firstSyncMergesBothListsWithoutDuplicates() {
        val plan = planWatchLaterSync(setOf("local", "both"), emptyList(), mapOf("both" to listOf(1L), "remote" to listOf(2L)))
        assertEquals(listOf("local"), plan.upload)
        assertEquals(mapOf("both" to 1L), plan.link)
        assertEquals(mapOf("remote" to 2L), plan.download)
        assertTrue(plan.removeLocal.isEmpty() && plan.removeRemote.isEmpty())
    }
    @Test fun removalOnServerRemovesSyncedVideoLocally() {
        val plan = planWatchLaterSync(setOf("gone"), listOf(state("gone", WATCH_LATER_SYNCED)), emptyMap())
        assertEquals(listOf("gone"), plan.removeLocal)
        assertTrue(plan.upload.isEmpty())
    }
    @Test fun offlineRemovalIsSentAndNotDownloadedBack() {
        val plan = planWatchLaterSync(emptySet(), listOf(state("removed", WATCH_LATER_REMOVE)), mapOf("removed" to listOf(7L, 8L)))
        assertEquals(mapOf("removed" to listOf(7L, 8L)), plan.removeRemote)
        assertTrue(plan.download.isEmpty())
    }
    @Test fun finishedRemovalClearsItsQueueEntry() {
        val plan = planWatchLaterSync(emptySet(), listOf(state("removed", WATCH_LATER_REMOVE)), emptyMap())
        assertEquals(listOf("removed"), plan.clearState)
    }
    @Test fun offlineAdditionIsUploaded() {
        assertEquals(listOf("new"), planWatchLaterSync(setOf("new"), emptyList(), emptyMap()).upload)
    }
    @Test fun videoUnavailableOnServerStaysLocalWithoutRetrying() {
        val plan = planWatchLaterSync(setOf("private"), listOf(state("private", WATCH_LATER_LOCAL_ONLY, null)), emptyMap())
        assertTrue(plan.upload.isEmpty() && plan.removeLocal.isEmpty())
    }
    @Test fun keysIgnoreHostAndCase() {
        assertEquals(watchLaterKey("https://a.example|ABC-1"), watchLaterKey("https://b.example|abc-1"))
    }
    @Test fun playlistOperationsUseExpectedRoutes() {
        val list = AccountOperation.watchLaterPlaylists("alice")
        assertEquals(listOf("api", "v1", "accounts", "alice", "video-playlists"), list.segments)
        assertEquals("2", list.query["playlistType"])
        val add = AccountOperation.addPlaylistVideo(5, "123e4567-e89b-42d3-a456-426614174000")
        assertEquals("POST", add.method)
        assertEquals("123e4567-e89b-42d3-a456-426614174000", add.body?.get("videoId")?.asString)
        val remove = AccountOperation.removePlaylistElement(5, 9)
        assertEquals("DELETE", remove.method)
        assertEquals(listOf("api", "v1", "video-playlists", "5", "videos", "9"), remove.segments)
    }
}
