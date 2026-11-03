package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsLogicTest {
    @Test fun olderSavedOptionsRetainNewDefaults() {
        val settings = Gson().fromJson("""{"quality":720,"translateTo":"en"}""", AppOptions::class.java)
        assertEquals(720, settings.quality)
        assertEquals("en", settings.translateTo)
        assertTrue(settings.searchSources.contains("sepia"))
        assertEquals(20, settings.subtitleSize)
        assertTrue(settings.modelsWifiOnly)
    }

    @Test fun subtitleTranslationPreservesCueIdentityTimingAndNotes() = runBlocking {
        val source = "WEBVTT\n\nNOTE keep --> this note\nunchanged\n\ncue-1\n00:01.000 --> 00:03.000 line:80%\n<b>Hello</b>\nworld\n\nSTYLE\n::cue { color: yellow; }\n"
        val inputs = mutableListOf<String>()
        val translated = translateVttPayloads(source) { inputs += it; "Привет\nмир" }
        assertEquals(listOf("Hello\nworld"), inputs)
        assertTrue(translated.contains("cue-1\n00:01.000 --> 00:03.000 line:80%\nПривет\nмир"))
        assertTrue(translated.contains("NOTE keep --> this note\nunchanged"))
        assertTrue(translated.contains("::cue { color: yellow; }"))
        assertTrue(translated.startsWith("WEBVTT\n"))
    }

    @Test fun subtitleTranslationHandlesSeveralCuesIndependently() = runBlocking {
        val source = "WEBVTT\n\n00:00.000 --> 00:01.000\nOne\n\n00:02.500 --> 00:04.000\nTwo"
        val result = translateVttPayloads(source) { mapOf("One" to "Один", "Two" to "Два").getValue(it) }
        assertTrue(result.contains("00:00.000 --> 00:01.000\nОдин"))
        assertTrue(result.contains("00:02.500 --> 00:04.000\nДва"))
    }

    @Test fun progressiveQualityHonorsCeilingAndFallsBackToLowest() {
        val video = Video(files = listOf(360, 720, 1080).map { VideoFile("https://video.example/$it.mp4", Resolution(it)) })
        assertEquals("https://video.example/720.mp4", video.playableUrl(720))
        assertEquals("https://video.example/360.mp4", video.playableUrl(240))
        assertEquals("https://video.example/1080.mp4", video.playableUrl())
    }
}
