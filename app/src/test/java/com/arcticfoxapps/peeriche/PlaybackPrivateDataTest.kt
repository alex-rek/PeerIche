package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class PlaybackPrivateDataTest {
    private fun entry(id: String, account: String? = null) = PlaybackPrivateEntry(
        SavedVideo(id, "history", id, null, "https://video.example", null, null, 0, 0), account)

    @Test fun referencesAreOpaqueAndReturnTheirOwnEntry() {
        val first = entry("same-video", "server|alice")
        val second = entry("same-video")
        val firstReference = PlaybackPrivateData.put(first)
        val secondReference = PlaybackPrivateData.put(second)
        assertNotEquals(firstReference, secondReference)
        assertFalse(firstReference.contains("alice") || firstReference.contains("same-video"))
        assertEquals(first, PlaybackPrivateData.get(firstReference))
        assertEquals(second, PlaybackPrivateData.get(secondReference))
    }

    @Test fun unknownReferencesReturnNothing() {
        assertNull(PlaybackPrivateData.get(null))
        assertNull(PlaybackPrivateData.get("missing"))
    }

    @Test fun oldEntriesAreEvicted() {
        val oldest = PlaybackPrivateData.put(entry("oldest"))
        repeat(32) { PlaybackPrivateData.put(entry("video-$it")) }
        assertNull(PlaybackPrivateData.get(oldest))
    }
}
