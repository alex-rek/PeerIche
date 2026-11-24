package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSyncTest {
    private val a = "123e4567-e89b-42d3-a456-426614174000"
    private val b = "223e4567-e89b-42d3-a456-426614174000"
    private fun link(local: String, remote: Long?, state: String = PLAYLIST_SYNCED) = PlaylistLink("acc", local, remote, state)

    @Test fun firstSyncUploadsDeviceOnlyAndDownloadsServerOnly() {
        val plan = planPlaylistSync(mapOf(a to "Music"), emptyList(), listOf(RemotePlaylist(5, "News")))
        assertEquals(listOf(a), plan.create)
        assertEquals(listOf(RemotePlaylist(5, "News")), plan.download)
    }

    @Test fun samePlaylistNameIsLinkedInsteadOfDuplicated() {
        val plan = planPlaylistSync(mapOf(a to " music "), emptyList(), listOf(RemotePlaylist(5, "Music")))
        assertEquals(mapOf(a to 5L), plan.link)
        assertTrue(plan.create.isEmpty() && plan.download.isEmpty())
    }

    @Test fun eachServerPlaylistIsLinkedOnlyOnce() {
        val plan = planPlaylistSync(mapOf(a to "Music", b to "Music"), emptyList(), listOf(RemotePlaylist(5, "Music")))
        assertEquals(1, plan.link.size)
        assertEquals(1, plan.create.size)
    }

    @Test fun deletionOnServerRemovesLinkedPlaylistHere() {
        val plan = planPlaylistSync(mapOf(a to "Music"), listOf(link(a, 5)), emptyList())
        assertEquals(listOf(a), plan.deleteLocal)
        assertTrue(plan.create.isEmpty())
    }

    @Test fun deletionHereIsSentToServerAndNotDownloadedBack() {
        val plan = planPlaylistSync(emptyMap(), listOf(link(a, 5, PLAYLIST_REMOVE)), listOf(RemotePlaylist(5, "Music")))
        assertEquals(mapOf(a to 5L), plan.deleteRemote)
        assertTrue(plan.download.isEmpty())
    }

    @Test fun finishedDeletionDropsTheLink() {
        val plan = planPlaylistSync(emptyMap(), listOf(link(a, 5, PLAYLIST_REMOVE)), emptyList())
        assertEquals(listOf(a), plan.dropLink)
    }

    @Test fun renameOnServerIsApplied() {
        val plan = planPlaylistSync(mapOf(a to "Music"), listOf(link(a, 5)), listOf(RemotePlaylist(5, "Songs")))
        assertEquals(mapOf(a to "Songs"), plan.rename)
    }

    @Test fun lostLocalCopyNeverDeletesServerPlaylist() {
        val plan = planPlaylistSync(emptyMap(), listOf(link(a, 5)), listOf(RemotePlaylist(5, "Music")))
        assertTrue(plan.deleteRemote.isEmpty())
        assertEquals(listOf(a), plan.dropLink)
        assertEquals(listOf(RemotePlaylist(5, "Music")), plan.download)
    }

    @Test fun refusedPlaylistStaysOnDeviceWithoutRetrying() {
        val plan = planPlaylistSync(mapOf(a to "Music"), listOf(link(a, null, PLAYLIST_LOCAL_ONLY)), emptyList())
        assertTrue(plan.create.isEmpty() && plan.deleteLocal.isEmpty() && plan.dropLink.isEmpty())
    }

    @Test fun storedKindsSplitEvenWhenAccountIdContainsAt() {
        assertEquals(PLAYLIST_DEFINITIONS to "user@host", storedPlaylistOwner(storedPlaylistKind(PLAYLIST_DEFINITIONS, "user@host")))
        assertEquals(playlistKind(a) to "x@y", storedPlaylistOwner(storedPlaylistKind(playlistKind(a), "x@y")))
        assertNull(storedPlaylistOwner(playlistKind(a)))
        assertTrue(isVisiblePlaylistKind(playlistKind(a)) && isVisiblePlaylistKind(PLAYLIST_DEFINITIONS))
        assertTrue(!isVisiblePlaylistKind(storedPlaylistKind(playlistKind(a), "acc")))
    }

    @Test fun playlistRoutesAreExpected() {
        val list = AccountOperation.regularPlaylists("alice", 100)
        assertEquals(listOf("api", "v1", "accounts", "alice", "video-playlists"), list.segments)
        assertEquals("1", list.query["playlistType"]); assertEquals("100", list.query["start"])
        val create = AccountOperation.createPrivatePlaylist(" Music ")
        assertEquals("POST", create.method); assertTrue(create.multipart)
        assertEquals("Music", create.body?.get("displayName")?.asString)
        assertEquals(3, create.body?.get("privacy")?.asInt)
        assertEquals(listOf("api", "v1", "video-playlists", "7"), AccountOperation.deletePlaylist(7).segments)
    }
}
