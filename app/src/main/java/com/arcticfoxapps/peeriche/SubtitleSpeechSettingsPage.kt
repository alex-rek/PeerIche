package com.arcticfoxapps.peeriche

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import java.util.Locale

@Composable private fun speechStatus(code: String): String = when (code) {
    "off" -> uiText(R.string.ui_off_7f197c)
    "ready" -> uiText(R.string.ui_ready_a518f4)
    "speaking" -> uiText(R.string.ui_speaking_737906)
    "translating" -> uiText(R.string.ui_preparing_existing_translation_4a6aba)
    "no_subtitles" -> uiText(R.string.ui_no_text_subtitles_available_f53567)
    "missing_voice" -> uiText(R.string.ui_no_installed_voice_for_this_language_insta_087db8)
    "translation_error" -> uiText(R.string.ui_translation_unavailable_using_fallback_604641)
    "captions_error" -> uiText(R.string.ui_could_not_read_subtitles_8fc073)
    "engine_error" -> uiText(R.string.ui_tts_engine_unavailable_check_system_speech_1ff563)
    else -> uiText(R.string.ui_speech_synthesis_failed_video_playback_is_03ff7c)
}

@Composable private fun SpeechFirstEnablePrompt(onDismiss: () -> Unit, onEnable: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText(R.string.ui_enable_subtitle_speech_a576df)) },
        text = { Text(uiText(R.string.ui_automatic_mode_is_recommended_speak_subtit_b9ef26)) },
        confirmButton = { TextButton(onClick = onEnable) { Text(uiText(R.string.ui_enable_automatic_mode_6c85a3)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.ui_cancel_28fb55)) } })
}

@Composable internal fun SpeechQuickControl(modifier: Modifier = Modifier, transparent: Boolean = false, showSettings: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences.get(context) }
    val options by preferences.options.collectAsState()
    val status by SubtitleSpeechRuntime.status.collectAsState()
    var offer by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Row(if (transparent) modifier else modifier.background(Color.Black.copy(alpha = .55f), MaterialTheme.shapes.large), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (!options.speech.enabled && !options.speech.offeredAuto) offer = true
            else preferences.update { it.copy(speech = it.speech.copy(enabled = !it.speech.enabled, mode = it.speech.mode.takeUnless { mode -> mode == "off" } ?: "auto")) }
        }) { Icon(if (options.speech.enabled) Icons.Default.RecordVoiceOver else Icons.Default.VoiceOverOff,
            uiText(R.string.ui_subtitle_speech_4f7011), tint = if (options.speech.enabled) MaterialTheme.colorScheme.primary else Color.White) }
        if (showSettings) IconButton(onClick = { settings = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Tune, uiText(R.string.ui_speech_settings_8ae8a1), tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (options.speech.enabled && status in listOf("missing_voice", "engine_error", "speech_error", "no_subtitles", "captions_error")) {
            IconButton(onClick = { settings = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ErrorOutline, speechStatus(status), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (offer) SpeechFirstEnablePrompt({ offer = false }) {
        preferences.update { it.copy(speech = it.speech.copy(enabled = true, offeredAuto = true, mode = "auto")) }; offer = false
    }
    if (settings) SpeechSettingsDialog { settings = false }
}

@Composable internal fun SpeechSettingsDialog(onClose: () -> Unit) {
    val preferences = AppPreferences.get(LocalContext.current)
    val options by preferences.options.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_02b74f)) }
                    Text(uiText(R.string.ui_subtitle_speech_4f7011), style = MaterialTheme.typography.titleLarge)
                }
                SubtitleSpeechSettingsPage(options, preferences)
            }
        }
    }
}

@Composable internal fun SubtitleSpeechSettingsPage(options: AppOptions, preferences: AppPreferences) {
    val context = LocalContext.current
    val settings = options.speech
    val scope = rememberCoroutineScope()
    val engine = remember(settings.engine) { TtsEngineController(context, settings.engine) }
    val voices by engine.voices.collectAsState()
    val engines by engine.engines.collectAsState()
    val runtime by SubtitleSpeechRuntime.status.collectAsState()
    var engineStatus by remember(engine) { mutableStateOf("ready") }
    var offer by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(engine) { onDispose { testJob?.cancel(); engine.close() } }
    LaunchedEffect(engine) {
        try { engine.initialize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { engineStatus = "engine_error" }
    }
    fun change(transform: (SubtitleSpeechSettings) -> SubtitleSpeechSettings) { preferences.update { it.copy(speech = transform(it.speech)) } }
    val language = settings.language.ifBlank { options.translateTo }
    val languageLabel = Locale.forLanguageTag(language).displayLanguage
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsToggle(uiText(R.string.ui_speak_subtitles_8f45b6), settings.enabled) { enabled ->
            if (enabled && !settings.offeredAuto) offer = true
            else change { it.copy(enabled = enabled, mode = it.mode.takeUnless { mode -> mode == "off" } ?: "auto") }
        } }
        item { SettingsChoice(uiText(R.string.ui_speech_mode_21b9ce), if (settings.enabled) settings.mode else "off", listOf(
            "off" to uiText(R.string.ui_off_7f197c), "auto" to uiText(R.string.ui_automatic_00a8ae),
            "original" to uiText(R.string.ui_original_subtitles_8c6f16), "translated" to uiText(R.string.ui_translated_subtitles_01ecb4),
            "both" to uiText(R.string.ui_original_translation_18e287))) { mode ->
            if (mode == "off") change { it.copy(enabled = false) }
            else if (!settings.offeredAuto) offer = true
            else change { it.copy(enabled = true, mode = mode) }
        } }
        item { SettingsChoice(uiText(R.string.ui_translated_speech_language_fb3e9d), settings.language,
            listOf("" to uiText(R.string.ui_follow_translation_language_5b9919)) + translationLanguages()) { value -> change { it.copy(language = value, voice = "") } } }
        item { Text(uiText(R.string.ui_original_speech_follows_the_subtitle_langu_90034f), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsChoice(uiText(R.string.ui_tts_engine_6d87fc), settings.engine,
            listOf("" to uiText(R.string.ui_system_default_00d48c)) + engines) { value -> change { it.copy(engine = value, voice = "") } } }
        item { SettingsChoice(uiText(R.string.ui_voice_7692ca), settings.voice,
            listOf("" to uiText(R.string.ui_automatic_selection_f4cc61)) + voices.filter { it.language.substringBefore('-') == language.substringBefore('-') }.map {
                it.name to (it.name + if (it.network) uiText(R.string.ui_network_a37c44) else uiText(R.string.ui_on_device_e0756a))
            }) { value -> change { it.copy(voice = value) } } }
        item { SettingsSlider(uiText(R.string.ui_speech_rate_7cb6d9), settings.rate * 100, 50f..200f, "%") { value -> change { it.copy(rate = value / 100) } } }
        item { SettingsSlider(uiText(R.string.ui_voice_pitch_8d9081), settings.pitch * 100, 50f..200f, "%") { value -> change { it.copy(pitch = value / 100) } } }
        item { SettingsChoice(uiText(R.string.ui_original_audio_2d2547), settings.audio, listOf("unchanged" to uiText(R.string.ui_unchanged_dc2c08),
            "duck" to uiText(R.string.ui_duck_during_speech_62dee3), "mute" to uiText(R.string.ui_mute_during_speech_669ae8))) { value -> change { it.copy(audio = value) } } }
        if (settings.audio == "duck") item { SettingsSlider(uiText(R.string.ui_original_volume_during_speech_e135a9), settings.duckPercent.toFloat(), 0f..100f, "%") { value -> change { it.copy(duckPercent = value.toInt()) } } }
        item { SettingsChoice(uiText(R.string.ui_long_utterances_e0af43), settings.strategy, listOf("skip" to uiText(R.string.ui_skip_outdated_speech_f1fffa),
            "speed" to uiText(R.string.ui_speed_up_speech_up_to_2_5_b97e49), "wait" to uiText(R.string.ui_pause_video_until_speech_ends_898fb7))) { value -> change { it.copy(strategy = value) } } }
        item { SettingsChoice(uiText(R.string.ui_if_translation_is_unavailable_f4e52f), settings.fallback, listOf(
            "original" to uiText(R.string.ui_speak_original_381a76), "skip" to uiText(R.string.ui_skip_utterance_4e9d44))) { value -> change { it.copy(fallback = value) } } }
        item { SettingsRow(uiText(R.string.ui_status_b60505), speechStatus(if (engineStatus != "ready") engineStatus else runtime)) }
        item { SettingsRow(uiText(R.string.ui_test_voice_9c6b87), uiText(R.string.ui_voice_test_language, languageLabel), onClick = {
            PlaybackService.pausePlayback()
            testJob?.cancel(); engine.stop()
            testJob = scope.launch {
                engineStatus = "speaking"
                try {
                    val text = when (language.substringBefore('-')) { "ru" -> "Проверка озвучивания субтитров PeerIche."; "en" -> "PeerIche subtitle speech test."; else -> "PeerIche. 1, 2, 3." }
                    engine.speak(SpeechPart(text, language), settings.rate, settings.pitch, settings.voice)
                    engineStatus = "ready"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: SpeechUnavailable) { engineStatus = error.code }
                catch (_: Exception) { engineStatus = "engine_error" }
            }
        }) }
        item { SettingsRow(uiText(R.string.ui_stop_test_d2ea2c), onClick = { testJob?.cancel(); engine.stop(); engineStatus = "ready" }) }
        item { SettingsRow(uiText(R.string.ui_install_tts_language_data_03de98), onClick = {
            try { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (settings.engine.isNotBlank()) setPackage(settings.engine) }) }
            catch (_: Exception) { engineStatus = "engine_error" }
        }) }
    }
    if (offer) SpeechFirstEnablePrompt({ offer = false }) {
        change { it.copy(enabled = true, offeredAuto = true, mode = "auto") }; offer = false
    }
}
