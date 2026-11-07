package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class WatchPageTest {
    @Test fun sharesCanonicalPublicWatchLinkWithoutStreamOrQuerySecrets() {
        val video = Video(uuid = "abc", name = "Title", url = "https://video.example:8443/w/abc?token=private#fragment", directStreamUrl = "https://video.example/stream.m3u8")
        assertEquals("Title\nhttps://video.example:8443/w/abc", video.shareText("https://fallback.example"))
    }
    @Test fun rejectsUnsafeAndNonWatchUrlsWhenSharing() {
        for (url in listOf("javascript:alert(1)", "https://user:secret@video.example/videos/watch/abc", "https://video.example/file.mp4")) {
            val video = Video(uuid = "abc", host = "origin.example", url = url)
            assertTrue(video.publicWatchUrl("https://fallback.example").endsWith("/videos/watch/abc"))
            assertFalse(video.publicWatchUrl("https://fallback.example").contains("secret"))
        }
    }
    @Test fun flattensThreadInReadingOrderAndDeduplicatesComments() {
        val child = CommentTree(VideoComment(id = 2, text = "Reply"))
        val tree = CommentTree(VideoComment(id = 1, text = "Root"), listOf(child, CommentTree(VideoComment(id = 3)), child))
        assertEquals(listOf(1, 2, 3), flattenComments(tree).map { it.id })
    }
    @Test fun boundsDeepReplyTreesAndEmptyNodes() {
        var tree = CommentTree(VideoComment(id = 2001))
        for (i in 2000 downTo 1) tree = CommentTree(VideoComment(id = i), listOf(tree))
        assertEquals(1000, flattenComments(tree).size)
        assertTrue(flattenComments(CommentTree()).isEmpty())
    }
    @Test fun keepsPipAspectWithinPlatformLimits() {
        assertEquals(2.39f, safePipRatio(10f), .001f)
        assertEquals(1f / 2.39f, safePipRatio(.1f), .001f)
        assertEquals(16f / 9f, safePipRatio(Float.NaN), .001f)
        assertEquals(4f / 3f, safePipRatio(4f / 3f), .001f)
    }
}
