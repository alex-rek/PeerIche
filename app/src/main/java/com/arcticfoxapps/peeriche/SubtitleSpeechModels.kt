package com.arcticfoxapps.peeriche

data class SubtitleSpeechSettings(
    val enabled: Boolean = false, val offeredAuto: Boolean = false, val mode: String = "auto",
    val language: String = "", val engine: String = "", val voice: String = "",
    val rate: Float = 1f, val pitch: Float = 1f, val audio: String = "duck", val duckPercent: Int = 25,
    val strategy: String = "skip", val fallback: String = "original"
)

internal data class SpeechCue(val startMs: Long, val endMs: Long, val text: String, val language: String) {
    val key: String get() = "$startMs:$endMs:$language:$text"
}
internal data class SpeechPart(val text: String, val language: String)
internal class SpeechQueueController {
    private var lastKey: String? = null
    fun claim(key: String): Boolean = if (key == lastKey) false else { lastKey = key; true }
    fun reset() { lastKey = null }
}

internal fun speechParts(mode: String, original: SpeechCue?, translated: SpeechCue?, target: String, fallback: String): List<SpeechPart> {
    val direct = original?.takeIf { it.language.substringBefore('-') == target.substringBefore('-') }
    val targetCue = translated ?: direct
    val chosen = when (mode) {
        "original" -> listOfNotNull(original)
        "both" -> listOfNotNull(original, targetCue)
        "translated", "auto" -> listOfNotNull(targetCue ?: original?.takeIf { fallback == "original" })
        else -> emptyList()
    }
    return chosen.map { SpeechPart(it.text, it.language) }.filter { it.text.isNotBlank() }.distinct()
}

internal fun speechRate(settings: SubtitleSpeechSettings, videoSpeed: Float, textLength: Int, remainingMs: Long): Float {
    val base = settings.rate.coerceIn(.5f, 2f) * videoSpeed.coerceIn(.25f, 3f)
    val fitted = if (settings.strategy == "speed" && remainingMs > 0) textLength / 14f / (remainingMs / 1000f) else base
    return maxOf(base, fitted).coerceIn(.5f, 2.5f)
}

internal fun speechVolume(base: Float, settings: SubtitleSpeechSettings, speaking: Boolean): Float =
    if (!speaking) base else when (settings.audio) {
        "mute" -> 0f
        "duck" -> base * settings.duckPercent.coerceIn(0, 100) / 100f
        else -> base
    }
