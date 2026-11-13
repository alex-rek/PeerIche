package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class VideoLibraryTest {
    @Test fun channelSourcesAcceptHandlesAndPublicChannelUrls() {
        assertEquals("news", homeChannel("news@example.org").name)
        assertEquals("example.org", homeChannel("https://example.org/c/news").host)
        assertEquals("news", homeChannel("https://example.org/video-channels/news").name)
        listOf("http://example.org/c/news", "https://user:pass@example.org/c/news", "https://example.org/c/news?token=secret", "https://example.org/videos/watch/id").forEach {
            assertTrue(it, runCatching { homeChannel(it) }.isFailure)
        }
    }

    @Test fun relativeMediaAddressesResolveBeforePlaybackAndPrivateDownloads() {
        val video = Video(uuid = "one", files = listOf(VideoFile("//cdn.example/v.mp4", Resolution(720), "/download/v.mp4")),
            streamingPlaylists = listOf(StreamingPlaylist(playlistUrl = "/static/one/master.m3u8")))
        val resolved = video.resolvedMediaUrls("https://example.org")
        assertEquals("https://cdn.example/v.mp4", resolved.files!!.single().fileUrl)
        assertEquals("https://example.org/download/v.mp4", videoDownloadOptions(resolved).single().url)
        assertEquals("https://example.org/static/one/master.m3u8", resolved.playableUrl())
        assertEquals("https://cdn.example/v.mp4", video.copy(streamingPlaylists = null).playableUrl())
    }

    @Test fun explicitPlaylistStartIgnoresSingleVideoAutoplayPreference() {
        try {
            val a = Video(uuid = "a", host = "example.org")
            val b = a.copy(uuid = "b")
            PlaybackQueue.start(listOf(a, b))
            assertTrue(PlaybackQueue.starts("https://example.org|a"))
            assertTrue(PlaybackQueue.consumeStart("https://example.org|a"))
            assertFalse(PlaybackQueue.consumeStart("https://example.org|a"))
            assertEquals(listOf(b), PlaybackQueue.entries.value)
        } finally { PlaybackQueue.clear() }
    }

    @Test fun oldPreferencesKeepRecommendedHome() {
        val options = restoredAppOptions("{\"quality\":720,\"translateTo\":\"de\"}")
        assertEquals("recommended", options.homeContent)
        assertTrue(options.homeInterests.isEmpty())
        assertEquals(720, options.quality)
    }

    @Test fun playlistsRetainVideoAndAccountIdentityWithoutPersistingMediaTokens() {
        val video = Video(uuid = "one", name = "First", host = "example.org", playbackAccountId = "account",
            channel = Channel("News", "news", "example.org"), files = listOf(VideoFile("https://example.org/a.mp4?videoFileToken=secret")))
        val row = video.libraryRow("https://other.example", WATCH_LATER)
        assertEquals("https://example.org|one", row.identity)
        assertFalse(row.metadata!!.contains("secret"))
        assertFalse(row.videoUrl.orEmpty().contains("secret"))
        val restored = row.toVideo()
        assertEquals("one", restored.apiId())
        assertEquals("account", restored.playbackAccountId)
        assertEquals("news", restored.channel?.name)
        assertNull(restored.files)
    }

    @Test fun nextQueueDeduplicatesByServerAndKeepsPlaylistOrder() {
        try {
            val a = Video(uuid = "a", host = "one.example")
            val b = Video(uuid = "b", host = "one.example")
            val remote = a.copy(host = "two.example")
            PlaybackQueue.replace(listOf(a, b, remote))
            PlaybackQueue.next(b)
            assertEquals(listOf(b, a, remote), PlaybackQueue.entries.value)
            assertEquals(b, PlaybackQueue.take())
            assertEquals(a, PlaybackQueue.take())
            assertEquals(remote, PlaybackQueue.take())
            assertNull(PlaybackQueue.take())
        } finally { PlaybackQueue.clear() }
    }

    @Test fun downloadOptionsRespectPermissionAndOnlyOfferVideoFiles() {
        val video = Video(files = listOf(VideoFile("https://media.example/v.mp4", Resolution(720), size = 100),
            VideoFile("https://media.example/audio.mp4", Resolution(0), hasVideo = false),
            VideoFile("https://media.example/list.m3u8", Resolution(1080)),
            VideoFile("http://media.example/insecure.mp4", Resolution(360))))
        assertEquals(listOf(720), videoDownloadOptions(video).map { it.quality })
        assertTrue(videoDownloadOptions(video.copy(downloadEnabled = false)).isEmpty())
        assertTrue(videoDownloadOptions(video.copy(isLive = true)).isEmpty())
    }
}
