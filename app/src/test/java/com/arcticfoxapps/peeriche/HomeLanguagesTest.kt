package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLanguagesTest {
    private fun video(id: String, language: String?) = Video(uuid = id, name = id, host = "example.org",
        language = language?.let { VideoLanguage(it) })

    @Test fun emptySelectionMeansAnyLanguage() {
        assertEquals(emptySet<String>(), resolveHomeVideoLanguages(emptyList(), "ru"))
        val videos = listOf(video("a", "en"), video("b", "ru"))
        assertEquals(videos, videos.preferVideoLanguages(emptySet()))
    }

    @Test fun interfaceChoiceFollowsInterfaceAndRegionalCodesCount() {
        assertEquals(setOf("ru", "de"), resolveHomeVideoLanguages(listOf(INTERFACE_VIDEO_LANGUAGE, "de"), "ru"))
        assertEquals(setOf("pt", "pt-BR", "pt-PT"), resolveHomeVideoLanguages(listOf(INTERFACE_VIDEO_LANGUAGE), "pt"))
        assertEquals(setOf("zh", "zh-Hans", "zh-Hant"), resolveHomeVideoLanguages(listOf("zh"), "en"))
    }

    @Test fun preferredLanguagesFirstThenUnknownThenOthersKeepingOrder() {
        val videos = listOf(video("en1", "en"), video("ru1", "ru"), video("none", null), video("en2", "en"), video("ru2", "ru"))
        assertEquals(listOf("ru1", "ru2", "none", "en1", "en2"), videos.preferVideoLanguages(setOf("ru")).map { it.uuid })
    }

    @Test fun subscriptionsStayInFirstGroup() {
        val videos = listOf(video("sub", "en"), video("ru", "ru"), video("other", "en"))
        assertEquals(listOf("sub", "ru", "other"), videos.preferVideoLanguages(setOf("ru"), setOf("sub")).map { it.uuid })
    }

    @Test fun languageResultsComeFirstAndAreNotRepeated() {
        val preferred = listOf(video("ru1", "ru"), video("ru2", "ru"))
        val all = listOf(video("en1", "en"), video("ru1", "ru"), video("en2", "en"))
        assertEquals(listOf("ru1", "ru2", "en1", "en2"), mergeLanguageFirst(preferred, all, "https://example.org").map { it.uuid })
        assertEquals(listOf("ru1", "ru2", "en1"), mergeLanguageFirst(preferred, all, "https://example.org", limit = 3).map { it.uuid })
    }
}
