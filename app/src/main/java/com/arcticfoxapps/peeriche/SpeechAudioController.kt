package com.arcticfoxapps.peeriche

/** Changes only player volume; retains user changes made during speech. */
internal class SpeechAudioController(private val read: () -> Float, private val write: (Float) -> Unit) {
    private var original: Float? = null
    private var applied = 1f
    private var settings = SubtitleSpeechSettings()
    fun begin(value: SubtitleSpeechSettings) {
        settings = value
        if (original == null) original = read()
        apply()
    }
    private fun apply() {
        applied = speechVolume(original ?: return, settings, true)
        write(applied)
    }
    fun volumeChanged(value: Float) {
        if (original != null && kotlin.math.abs(value - applied) > .001f) { original = value; apply() }
    }
    fun restore() {
        val value = original ?: return
        original = null
        write(value)
    }
}
