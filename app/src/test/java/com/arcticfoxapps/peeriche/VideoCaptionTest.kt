package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class VideoCaptionTest {
    @Test fun resolvesRelativeCaptionOnSourceServer() {
        val caption = VideoCaption(captionPath = "/static/video-captions/example-ru.vtt")
        assertEquals("https://video.example/static/video-captions/example-ru.vtt", caption.resolvedUrl("https://video.example"))
    }
    @Test fun prefersAbsoluteFileAndRejectsUnsafeSchemes() {
        assertEquals("https://cdn.example/ru.vtt", VideoCaption(fileUrl = "https://cdn.example/ru.vtt", captionPath = "/old.vtt").resolvedUrl("https://video.example"))
        assertNull(VideoCaption(fileUrl = "file:///private.vtt").resolvedUrl("https://video.example"))
        assertNull(VideoCaption().resolvedUrl("https://video.example"))
    }
    @Test fun readsPeerTubeCaptionPayload() {
        val page = Gson().fromJson("""{"total":1,"data":[{"language":{"id":"ru","label":"Русский"},"captionPath":"/captions/ru.vtt"}]}""", CaptionPage::class.java)
        assertEquals("ru", page.data.single().language?.id)
        assertEquals("https://video.example/captions/ru.vtt", page.data.single().resolvedUrl("https://video.example"))
    }
}
