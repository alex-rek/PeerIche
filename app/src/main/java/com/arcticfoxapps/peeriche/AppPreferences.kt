package com.arcticfoxapps.peeriche

import android.content.Context
import android.content.res.Configuration
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class AppOptions(
    val interfaceLanguage: String = "", val country: String = "", val homeSort: String = "newest",
    val speech: SubtitleSpeechSettings = SubtitleSpeechSettings(),
    val searchSources: List<String> = listOf("sepia", "peerseek", "federated", "direct", "local"),
    val searchOrder: List<String> = listOf("sepia", "peerseek", "custom", "federated", "direct", "local"),
    val searchMode: String = "parallel", val searchEnough: Int = 30, val searchParallel: Int = 3,
    val searchTimeout: Int = 12, val customIndexes: String = "", val availability: String = "unknown_or_available",
    val includeSensitive: Boolean = false, val sensitiveDisplay: String = "blur", val searchSort: String = "-match",
    val translateMetadata: Boolean = false, val translateSubtitles: Boolean = false, val translateTo: String = "ru",
    val translationFollowsInterface: Boolean = true,
    val modelsWifiOnly: Boolean = true, val quality: Int = 0, val playbackSpeed: Float = 1f,
    val autoplay: Boolean = true, val backgroundPlayback: Boolean = true, val controlsTimeout: Int = 5,
    val seekSeconds: Int = 10, val resizeMode: String = "fit", val videoAspect: String = "auto", val subtitlesEnabled: Boolean = true,
    val subtitleLanguage: String = "", val subtitleSize: Int = 20, val subtitleColor: String = "white",
    val subtitleHorizontal: Int = 50, val subtitleBottom: Int = 8
)

internal fun translationLanguageForInterface(tags: List<String>): String = tags.firstNotNullOfOrNull { tag ->
    val language = tag.substringBefore('-').lowercase(Locale.ROOT).let { if (it == "iw") "he" else it }
    language.takeIf { code -> interfaceLanguages.any { it.first == code } }
} ?: "en"

internal fun restoredAppOptions(json: String?): AppOptions = runCatching {
    val options = Gson().fromJson(json, AppOptions::class.java) ?: return@runCatching AppOptions()
    val stored = com.google.gson.JsonParser.parseString(json).asJsonObject
    if (stored.has("translationFollowsInterface")) options else options.copy(
        // Old versions always saved Russian, even when the user never chose it.
        // Preserve other previously selected targets; migrate the old default to follow the UI.
        translationFollowsInterface = !stored.has("translateTo") || options.translateTo in listOf("", "ru"))
}.getOrDefault(AppOptions())

internal fun resolvedTranslationOptions(options: AppOptions, interfaceTag: String): AppOptions =
    if (options.translationFollowsInterface) options.copy(translateTo = translationLanguageForInterface(listOf(interfaceTag))) else options

private fun currentInterfaceTranslationLanguage(context: Context, options: AppOptions): String {
    if (android.os.Build.VERSION.SDK_INT < 33 && options.interfaceLanguage.isNotBlank()) return translationLanguageForInterface(listOf(options.interfaceLanguage))
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val locales = manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
        return translationLanguageForInterface((0 until locales.size()).map { locales[it].toLanguageTag() })
    }
    val configured = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)
    return translationLanguageForInterface((0 until configured.size()).mapNotNull { configured[it]?.toLanguageTag() })
}

class AppPreferences private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences("app_options", Context.MODE_PRIVATE)
    private val gson = Gson()
    private var storedOptions = restoredAppOptions(preferences.getString("options", null))
    private var interfaceTag = currentInterfaceTranslationLanguage(context, storedOptions)
    private val state = MutableStateFlow(resolvedTranslationOptions(storedOptions, interfaceTag))
    val options = state.asStateFlow()
    fun update(transform: (AppOptions) -> AppOptions) {
        val value = transform(state.value)
        if (value.interfaceLanguage != state.value.interfaceLanguage) {
            val device = androidx.core.os.ConfigurationCompat.getLocales(android.content.res.Resources.getSystem().configuration)
            interfaceTag = translationLanguageForInterface(if (value.interfaceLanguage.isNotBlank()) listOf(value.interfaceLanguage)
                else (0 until device.size()).mapNotNull { device[it]?.toLanguageTag() })
        }
        preferences.edit().putString("options", gson.toJson(value)).apply()
        storedOptions = value
        state.value = resolvedTranslationOptions(value, interfaceTag)
    }
    fun refreshInterfaceLanguage() {
        interfaceTag = currentInterfaceTranslationLanguage(appContext, storedOptions)
        state.value = resolvedTranslationOptions(storedOptions, interfaceTag)
    }
    companion object {
        @Volatile private var instance: AppPreferences? = null
        fun get(context: Context): AppPreferences = instance ?: synchronized(this) {
            instance ?: AppPreferences(context).also { instance = it }
        }
    }
}

internal fun localizedAppContext(context: Context): Context {
    // Android 13+ owns the per-app locale, including changes from system settings.
    if (android.os.Build.VERSION.SDK_INT >= 33) return context
    val language = AppPreferences.get(context).options.value.interfaceLanguage
    if (language.isBlank()) return context
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(language))
    })
}

internal fun subtitleArgb(name: String): Int = when (name) {
    "yellow" -> android.graphics.Color.YELLOW
    "green" -> android.graphics.Color.GREEN
    "cyan" -> android.graphics.Color.CYAN
    else -> android.graphics.Color.WHITE
}
