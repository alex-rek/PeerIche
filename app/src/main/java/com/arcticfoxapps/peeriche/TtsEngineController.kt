package com.arcticfoxapps.peeriche

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class SpeechVoice(val name: String, val language: String, val network: Boolean)
internal class SpeechUnavailable(val code: String) : Exception(code)

internal class TtsEngineController(context: Context, private val enginePackage: String) {
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var closed = false
    @Volatile private var installedVoices: Set<android.speech.tts.Voice>? = null
    private fun allVoices(): Set<android.speech.tts.Voice> = installedVoices ?: engine.voices.orEmpty().also { installedVoices = it }
    private val engine = TextToSpeech(context.applicationContext, { result ->
        if (result == TextToSpeech.SUCCESS) ready.complete(Unit) else ready.completeExceptionally(SpeechUnavailable("engine_error"))
    }, enginePackage.takeIf { it.isNotBlank() })
    val voices = MutableStateFlow<List<SpeechVoice>>(emptyList())
    val engines = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (BuildConfig.DEBUG) android.util.Log.d("PeerIcheSpeech", "tts_utterance_started") }
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Android compatibility callback")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.completeExceptionally(SpeechUnavailable("speech_error")) } }
            override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.cancel() } }
        })
    }
    suspend fun initialize() = withContext(Dispatchers.IO) {
        // Engine discovery must also work when the currently selected engine cannot initialize.
        engines.value = engine.engines.map { it.name to it.label }
        if (enginePackage.isNotBlank() && engines.value.none { it.first == enginePackage }) throw SpeechUnavailable("engine_error")
        withTimeout(12_000) { ready.await() }
        check(!closed)
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        voices.value = allVoices().filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .map { SpeechVoice(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired) }.sortedBy { it.name }
    }
    suspend fun prepare(language: String, selectedVoice: String) = withContext(Dispatchers.IO) {
        withTimeout(12_000) { ready.await() }
        if (closed) throw CancellationException()
        val locale = Locale.forLanguageTag(language)
        if (language.isBlank() || engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) throw SpeechUnavailable("missing_voice")
        val available = allVoices().filter { it.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        val voice = available.firstOrNull { it.name == selectedVoice }
            ?: available.filterNot { it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
            ?: throw SpeechUnavailable("missing_voice")
        withContext(Dispatchers.Main.immediate) {
            ensureActive()
            if (engine.setVoice(voice) == TextToSpeech.ERROR) throw SpeechUnavailable("missing_voice")
        }
    }
    suspend fun speak(part: SpeechPart, rate: Float, pitch: Float, voice: String) {
        prepare(part.language, voice)
        for (chunk in part.text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1))) {
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            pending[id] = done
            try {
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    engine.setSpeechRate(rate)
                    engine.setPitch(pitch.coerceIn(.5f, 2f))
                    if (engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) throw SpeechUnavailable("speech_error")
                }
                withTimeout(30_000) { done.await() }
            } finally { pending.remove(id) }
        }
    }
    fun stop() { if (!closed) engine.stop(); pending.values.forEach { it.cancel() }; pending.clear() }
    fun close() { if (!closed) { stop(); closed = true; engine.shutdown(); if (!ready.isCompleted) ready.cancel() } }
}
