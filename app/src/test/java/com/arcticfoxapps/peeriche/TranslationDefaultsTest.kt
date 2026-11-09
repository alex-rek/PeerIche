package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class TranslationDefaultsTest {
    @Test fun defaultTranslationFollowsEverySupportedInterfaceLanguage() {
        for ((language, _) in interfaceLanguages) {
            val options = resolvedTranslationOptions(AppOptions(), language)
            assertTrue(options.translationFollowsInterface)
            assertEquals(language, options.translateTo)
        }
    }
    @Test fun regionalAndLegacyLocaleTagsResolveToModelLanguageCodes() {
        assertEquals("pt", translationLanguageForInterface(listOf("pt-BR")))
        assertEquals("zh", translationLanguageForInterface(listOf("zh-Hans-CN")))
        assertEquals("he", translationLanguageForInterface(listOf("iw-IL")))
        assertEquals("uk", translationLanguageForInterface(listOf("xx", "uk-UA")))
        assertEquals("en", translationLanguageForInterface(listOf("xx")))
        assertEquals("en", translationLanguageForInterface(emptyList()))
    }
    @Test fun oldRussianDefaultMigratesButOtherPreviouslyChosenTargetsRemainManual() {
        val oldDefault = restoredAppOptions("""{"interfaceLanguage":"fr","translateTo":"ru","quality":720}""")
        assertEquals("fr", resolvedTranslationOptions(oldDefault, "fr").translateTo)
        assertEquals(720, oldDefault.quality)
        val manual = restoredAppOptions("""{"interfaceLanguage":"fr","translateTo":"ja"}""")
        assertFalse(manual.translationFollowsInterface)
        assertEquals("ja", resolvedTranslationOptions(manual, "ar").translateTo)
    }
    @Test fun manualChoiceAndFollowModeSurviveSettingsRoundTrip() {
        val manual = AppOptions(translateTo = "ru", translationFollowsInterface = false)
        val restored = restoredAppOptions(Gson().toJson(manual))
        assertEquals("ru", resolvedTranslationOptions(restored, "he").translateTo)
        val following = restored.copy(translationFollowsInterface = true)
        val french = resolvedTranslationOptions(restoredAppOptions(Gson().toJson(following)), "fr")
        assertEquals("fr", french.translateTo)
        assertEquals("ar", resolvedTranslationOptions(french, "ar").translateTo)
    }
    @Test fun absentOrInvalidStoredOptionsUseInterfaceDefault() {
        for (json in listOf(null, "{}", "not-json", "[]")) {
            assertEquals("de", resolvedTranslationOptions(restoredAppOptions(json), "de").translateTo)
        }
    }
}
