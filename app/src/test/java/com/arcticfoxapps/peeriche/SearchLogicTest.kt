package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchLogicTest {
    @Test fun deduplicatesRepeatedResultsOnTheSameInstance() {
        val videos = listOf(
            Video(uuid = "abc", host = "videos.example"),
            Video(uuid = "abc", host = "videos.example"),
            Video(uuid = "abc", host = "other.example")
        )
        assertEquals(2, deduplicateVideos(videos, "https://fallback.example").size)
    }

    @Test fun serverMustUseHttpsAndHaveAHost() {
        assertEquals("https://videos.example", normalizeServer("videos.example"))
        assertThrows(IllegalArgumentException::class.java) { normalizeServer("http://videos.example") }
    }

    @Test fun mediaDetailsSelectsHlsAndFindsVideoId() {
        val video = Video(
            uuid = "",
            url = "https://videos.example/videos/watch/abc-123",
            files = listOf(VideoFile("https://videos.example/video.mp4", Resolution(1080))),
            streamingPlaylists = listOf(StreamingPlaylist("https://videos.example/master.m3u8"))
        )
        assertEquals("abc-123", video.apiId())
        assertEquals("https://videos.example/master.m3u8", video.playableUrl())
    }

    @Test fun thumbnailCandidatesUseOriginAndUpgradeHttp() {
        val video = Video(host = "origin.example", thumbnailPath = "/static/preview.jpg")
        assertEquals(listOf("https://origin.example/static/preview.jpg"), video.thumbnailCandidates("https://fallback.example"))
    }
}
