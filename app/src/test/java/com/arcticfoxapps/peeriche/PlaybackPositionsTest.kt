package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionsTest {
    @Test fun interruptedVideoKeepsItsPosition() {
        assertEquals(42_000L, resumablePosition(42_000, 120_000, false))
        assertEquals(42_000L, resumablePosition(42_000, -1, false))
    }
    @Test fun completedVideosAndLiveStreamsStartFresh() {
        assertEquals(0L, resumablePosition(42_000, 120_000, true))
        assertEquals(0L, resumablePosition(120_000, 120_000, false, true))
        assertEquals(0L, resumablePosition(119_800, 120_000, false))
        assertEquals(0L, resumablePosition(-1, 120_000, false))
    }
    @Test fun channelIdentityUsesChannelHostRatherThanVideoMirror() {
        val video = Video(uuid = "id", host = "mirror.example", channel = Channel(name = "news", host = "origin.example"))
        assertEquals("https://origin.example|news", video.subscriptionIdentity("https://fallback.example"))
        assertEquals(PeerTubeChannel("News", "news", "origin.example").subscriptionRow("https://account.example").identity,
            video.subscriptionIdentity("https://fallback.example"))
    }
}
