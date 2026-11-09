package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Bundle
import java.util.Locale

internal fun verifyInterfaceLocales(instrumentation: Instrumentation) {
    val result = Bundle()
    try {
        val expectedHome = mapOf("en" to "Home", "ru" to "Главная", "es" to "Inicio", "fr" to "Accueil", "de" to "Startseite", "pt" to "Início", "tr" to "Ana sayfa", "uk" to "Головна", "zh" to "首页", "ar" to "الرئيسية", "he" to "דף הבית")
        check(interfaceLanguages.map { it.first }.toSet() == expectedHome.keys)
        for ((language, home) in expectedHome) {
            val resources = instrumentation.targetContext.createConfigurationContext(Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }).resources
            check(resources.getString(R.string.home) == home)
            check(resources.getString(R.string.ui_home_9dd0b3) == if (language == "ru") "На главную" else home)
            check((resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) == (language in listOf("ar", "he")))
            val description = resources.getString(R.string.ui_account_server_mismatch, "one.example", "two.example")
            check(description.contains("one.example") && description.contains("two.example"))
            check(resources.getString(R.string.video_meta, 42, "channel").contains(String.format(Locale.forLanguageTag(language), "%d", 42)))
            for (field in R.string::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType }) {
                val text = resources.getString(field.getInt(null))
                check(text.isNotBlank())
                check(!(text.startsWith('"') && text.endsWith('"')))
            }
        }
        // Regional system locales must resolve their base-language resources.
        for ((tag, expected) in listOf("es-MX" to "Inicio", "pt-BR" to "Início", "fr-CA" to "Accueil")) {
            val resources = instrumentation.targetContext.createConfigurationContext(Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources
            check(resources.getString(R.string.home) == expected)
        }
        result.putString("stream", "PASS: eleven interface locales, RTL, regional fallbacks, formatted arguments and all string resources\n")
        instrumentation.finish(Activity.RESULT_OK, result)
    } catch (failure: Throwable) {
        result.putString("stream", "FAIL: locale resources ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull { it.className.startsWith("com.arcticfoxapps.peeriche") }}\n")
        instrumentation.finish(Activity.RESULT_CANCELED, result)
    }
}
