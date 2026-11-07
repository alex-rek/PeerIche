package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class SubtitleSpeechTest {
    @Test fun parsesTimedRussianAndEnglishCuesWithoutSpeakingMarkup() {
        val text = "WEBVTT\n\na\n00:00:01.500 --> 00:00:03.000 align:start\n<v speaker>Привет &amp; hello</v>\n\nNOTE comment\nDo not speak\n\nb\n00:04.000 --> 00:05.000\nWorld"
        val cues = SubtitleCueAdapter.parse(text, "ru")
        assertEquals(2, cues.size)
        assertEquals("Привет & hello", cues[0].text)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertNull(SubtitleCueAdapter.current(cues, 3000))
        assertEquals("World", SubtitleCueAdapter.current(cues, 4500)?.text)
    }
    @Test fun rejectsInvalidIntervalsAndCombinesOverlappingCues() {
        assertTrue(SubtitleCueAdapter.parse("00:02.000 --> 00:01.000\nBad", "en").isEmpty())
        val cues = listOf(SpeechCue(1000, 3000, "One", "en"), SpeechCue(2000, 4000, "Two", "en"))
        assertEquals("Two", SubtitleCueAdapter.current(cues, 2500)?.text)
        assertEquals("Two", SubtitleCueAdapter.current(cues, 3500)?.text)
    }
    @Test fun noRepeatedQueueEntriesUntilPauseOrSeekResets() {
        val queue = SpeechQueueController()
        assertTrue(queue.claim("a")); assertFalse(queue.claim("a"))
        assertTrue(queue.claim("b")); assertFalse(queue.claim("b"))
        queue.reset(); assertTrue(queue.claim("b"))
    }
    @Test fun originalModeAndDisabledDefaults() {
        val original = SpeechCue(0, 1000, "Hello", "en")
        assertEquals(listOf(SpeechPart("Hello", "en")), speechParts("original", original, null, "ru", "skip"))
        assertFalse(SubtitleSpeechSettings().enabled)
        assertFalse(com.google.gson.Gson().fromJson("{}", AppOptions::class.java).speech.enabled)
    }
    @Test fun usesExistingTranslationAndNeverRepeatsNativeTargetInBothMode() {
        val en = SpeechCue(0, 2000, "Hello", "en")
        val ru = SpeechCue(0, 2000, "Привет", "ru")
        assertEquals(listOf(SpeechPart("Привет", "ru")), speechParts("auto", en, ru, "ru", "original"))
        assertEquals(listOf(SpeechPart("Hello", "en"), SpeechPart("Привет", "ru")), speechParts("both", en, ru, "ru", "original"))
        assertEquals(listOf(SpeechPart("Привет", "ru")), speechParts("both", ru, null, "ru", "original"))
        assertTrue(speechParts("translated", en, null, "ru", "skip").isEmpty())
        assertEquals(listOf(SpeechPart("Hello", "en")), speechParts("auto", en, null, "ru", "original"))
    }
    @Test fun translatedVttRetainsOriginalCueWindows() = kotlinx.coroutines.runBlocking {
        val raw = "WEBVTT\n\n00:01.000 --> 00:02.500\nHello"
        val translated = translateVttPayloads(raw) { "Привет" }
        val a = SubtitleCueAdapter.parse(raw, "en").single()
        val b = SubtitleCueAdapter.parse(translated, "ru").single()
        assertEquals(a.startMs, b.startMs); assertEquals(a.endMs, b.endMs)
        assertEquals("Привет", b.text)
    }
    @Test fun speedFollowsVideoAndAccelerationIsBoundedForLongCues() {
        assertEquals(1.5f, speechRate(SubtitleSpeechSettings(), 1.5f, 10, 5000), .001f)
        assertEquals(2.5f, speechRate(SubtitleSpeechSettings(strategy = "speed"), 1f, 1000, 1000), .001f)
        assertEquals(.5f, speechRate(SubtitleSpeechSettings(rate = .5f), .25f, 1, 1000), .001f)
    }
    @Test fun restoresOriginalPlayerVolumeAfterEverySpeechExitAndHonorsUserChanges() {
        var volume = .8f
        val audio = SpeechAudioController({ volume }, { volume = it })
        audio.begin(SubtitleSpeechSettings(audio = "duck", duckPercent = 25))
        assertEquals(.2f, volume, .001f)
        audio.begin(SubtitleSpeechSettings(audio = "mute"))
        assertEquals(0f, volume, .001f)
        audio.restore(); assertEquals(.8f, volume, .001f)
        audio.restore(); assertEquals(.8f, volume, .001f)
        audio.begin(SubtitleSpeechSettings())
        audio.volumeChanged(.6f)
        audio.restore(); assertEquals(.6f, volume, .001f)
        audio.begin(SubtitleSpeechSettings(audio = "unchanged"))
        assertEquals(.6f, volume, .001f)
        audio.restore(); assertEquals(.6f, volume, .001f)
    }
    @Test fun settingsSurviveJsonRoundTripAndMissingLegacyFieldsKeepDefaults() {
        val expected = AppOptions(speech = SubtitleSpeechSettings(enabled = true, offeredAuto = true, mode = "both", language = "ru",
            engine = "offline.engine", voice = "voice-ru", rate = 1.25f, pitch = .9f, audio = "mute", strategy = "wait", fallback = "skip"))
        val gson = com.google.gson.Gson()
        assertEquals(expected, gson.fromJson(gson.toJson(expected), AppOptions::class.java))
        assertEquals(SubtitleSpeechSettings(), gson.fromJson("{}", AppOptions::class.java).speech)
        assertEquals("duck", SubtitleSpeechSettings().audio)
        assertEquals("skip", SubtitleSpeechSettings().strategy)
    }
    @Test fun rapidSeekAndVideoChangeCannotReplayPreviousCueWithoutExplicitReset() {
        val cues = listOf(SpeechCue(0, 1000, "Old video", "en"), SpeechCue(3000, 4000, "Current", "en"))
        val queue = SpeechQueueController()
        val first = SubtitleCueAdapter.current(cues, 10)!!
        assertTrue(queue.claim(first.key))
        assertNull(SubtitleCueAdapter.current(cues, 2000))
        val next = SubtitleCueAdapter.current(cues, 3500)!!
        assertTrue(queue.claim(next.key)); assertFalse(queue.claim(next.key))
        queue.reset()
        assertTrue(queue.claim(first.key))
        assertTrue(speechParts("off", first, next, "ru", "original").isEmpty())
    }
}
