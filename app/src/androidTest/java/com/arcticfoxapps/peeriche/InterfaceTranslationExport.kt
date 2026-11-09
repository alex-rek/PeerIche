package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

/** Build-time aid on the isolated emulator. Only bundled, generic English UI text
 * is translated. Shipped interface translations are static Android resources. */
internal fun exportInterfaceTranslations(instrumentation: Instrumentation) {
    val result = Bundle()
    try {
        check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"))
        val context = instrumentation.targetContext
        val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }).resources
        val catalog = R.string::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType && it.name != "app_name" }
            .associate { it.name to resources.getString(it.getInt(null)) }.toSortedMap()
        val gson = Gson()
        val output = File(context.cacheDir, "interface-translations").apply { mkdirs() }
        val placeholders = Regex("%[0-9]+\\\$[sd]")
        runBlocking {
            for (language in listOf("es", "fr", "de", "pt", "tr", "uk", "zh", "ar", "he")) {
                val file = File(output, "$language.json")
                val translated: MutableMap<String, String> = if (file.exists()) gson.fromJson(file.readText(), object : TypeToken<MutableMap<String, String>>() {}.type) else linkedMapOf()
                val engine = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage("en").setTargetLanguage(language).build())
                fun progress(message: String) { instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$language: $message\n") }) }
                try {
                    progress("preparing local model")
                    awaitModelTask(engine.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                    val cache = mutableMapOf<String, String>()
                    suspend fun translate(text: String): String {
                        if (text.isBlank()) return text
                        val prefix = text.takeWhile(Char::isWhitespace)
                        val suffix = text.takeLastWhile(Char::isWhitespace)
                        return prefix + awaitModelTask(engine.translate(text.trim())) + suffix
                    }
                    for ((key, source) in catalog) {
                        if (translated.containsKey(key)) continue
                        var target = cache[source] ?: translate(source)
                        val expected = placeholders.findAll(source).map { it.value }.sorted().toList()
                        if (placeholders.findAll(target).map { it.value }.sorted().toList() != expected) {
                            val rebuilt = StringBuilder(); var offset = 0
                            for (match in placeholders.findAll(source)) {
                                rebuilt.append(translate(source.substring(offset, match.range.first))).append(match.value)
                                offset = match.range.last + 1
                            }
                            rebuilt.append(translate(source.substring(offset))); target = rebuilt.toString()
                        }
                        check(target.isNotBlank() || source.isBlank())
                        translated[key] = target; cache[source] = target
                        if (translated.size % 25 == 0) { file.writeText(gson.toJson(translated)); progress("${translated.size}/${catalog.size}") }
                    }
                    file.writeText(gson.toJson(translated)); progress("complete: ${translated.size}")
                } finally { engine.close() }
            }
        }
        result.putString("stream", "PASS: static interface translation catalogs exported\n")
        instrumentation.finish(Activity.RESULT_OK, result)
    } catch (failure: Throwable) {
        result.putString("stream", "FAIL: interface catalog export ${failure.javaClass.simpleName}\n")
        instrumentation.finish(Activity.RESULT_CANCELED, result)
    }
}
