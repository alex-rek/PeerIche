package com.arcticfoxapps.peeriche

import android.content.Context
import android.content.res.Configuration
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class AppOptions(
    val interfaceLanguage: String = "", val country: String = "", val homeSort: String = "newest",
    val searchSources: List<String> = listOf("sepia", "peerseek", "federated", "direct", "local"),
    val searchOrder: List<String> = listOf("sepia", "peerseek", "custom", "federated", "direct", "local"),
    val searchMode: String = "parallel", val searchEnough: Int = 30, val searchParallel: Int = 3,
    val searchTimeout: Int = 12, val customIndexes: String = "", val availability: String = "unknown_or_available",
    val includeSensitive: Boolean = false, val sensitiveDisplay: String = "blur", val searchSort: String = "-match",
    val translateMetadata: Boolean = false, val translateSubtitles: Boolean = false, val translateTo: String = "ru",
    val modelsWifiOnly: Boolean = true, val quality: Int = 0, val playbackSpeed: Float = 1f,
    val autoplay: Boolean = true, val backgroundPlayback: Boolean = true, val controlsTimeout: Int = 5,
    val seekSeconds: Int = 10, val resizeMode: String = "fit", val videoAspect: String = "auto", val subtitlesEnabled: Boolean = true,
    val subtitleLanguage: String = "", val subtitleSize: Int = 20, val subtitleColor: String = "white",
    val subtitleHorizontal: Int = 50, val subtitleBottom: Int = 8
)

class AppPreferences private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_options", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val state = MutableStateFlow(runCatching {
        // Gson's constructor defaults are retained when fields are absent.
        gson.fromJson(preferences.getString("options", null), AppOptions::class.java) ?: AppOptions()
    }.getOrDefault(AppOptions()))
    val options = state.asStateFlow()
    fun update(transform: (AppOptions) -> AppOptions) {
        val value = transform(state.value)
        preferences.edit().putString("options", gson.toJson(value)).apply()
        state.value = value
    }
    companion object {
        @Volatile private var instance: AppPreferences? = null
        fun get(context: Context): AppPreferences = instance ?: synchronized(this) {
            instance ?: AppPreferences(context).also { instance = it }
        }
    }
}

internal fun localizedAppContext(context: Context): Context {
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
