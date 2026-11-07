package com.arcticfoxapps.peeriche

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    "off" -> uiText("Выключено", "Off")
    "ready" -> uiText("Готово", "Ready")
    "speaking" -> uiText("Озвучивание", "Speaking")
    "translating" -> uiText("Подготовка существующего перевода…", "Preparing existing translation…")
    "no_subtitles" -> uiText("Нет доступных текстовых субтитров", "No text subtitles available")
    "missing_voice" -> uiText("Нет установленного голоса для этого языка. Установите языковые данные TTS.", "No installed voice for this language. Install TTS language data.")
    "translation_error" -> uiText("Перевод недоступен — используется резервный режим", "Translation unavailable — using fallback")
    "captions_error" -> uiText("Не удалось прочитать субтитры", "Could not read subtitles")
    "engine_error" -> uiText("TTS-движок недоступен. Проверьте системные настройки синтеза речи.", "TTS engine unavailable. Check system speech settings.")
    else -> uiText("Ошибка синтеза речи. Видео продолжает воспроизводиться.", "Speech synthesis failed. Video playback is unaffected.")
}

@Composable private fun SpeechFirstEnablePrompt(onDismiss: () -> Unit, onEnable: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Включить озвучивание?", "Enable subtitle speech?")) },
        text = { Text(uiText("Рекомендуется автоматический режим: читать субтитры на выбранном языке перевода, а при необходимости использовать существующий перевод. Режим можно изменить в настройках.",
            "Automatic mode is recommended: speak subtitles in the selected translation language, using the existing translation when needed. You can change the mode in settings.")) },
        confirmButton = { TextButton(onClick = onEnable) { Text(uiText("Включить автоматически", "Enable automatic mode")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Отмена", "Cancel")) } })
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
            uiText("Озвучивание субтитров", "Subtitle speech"), tint = if (options.speech.enabled) MaterialTheme.colorScheme.primary else Color.White) }
        if (showSettings) IconButton(onClick = { settings = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Tune, uiText("Настройки озвучивания", "Speech settings"), tint = Color.White, modifier = Modifier.size(20.dp))
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
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, uiText("Назад", "Back")) }
                    Text(uiText("Озвучивание субтитров", "Subtitle speech"), style = MaterialTheme.typography.titleLarge)
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
        item { SettingsToggle(uiText("Озвучивать субтитры", "Speak subtitles"), settings.enabled) { enabled ->
            if (enabled && !settings.offeredAuto) offer = true
            else change { it.copy(enabled = enabled, mode = it.mode.takeUnless { mode -> mode == "off" } ?: "auto") }
        } }
        item { SettingsChoice(uiText("Режим озвучивания", "Speech mode"), if (settings.enabled) settings.mode else "off", listOf(
            "off" to uiText("Выключено", "Off"), "auto" to uiText("Автоматически", "Automatic"),
            "original" to uiText("Оригинальные субтитры", "Original subtitles"), "translated" to uiText("Переведённые субтитры", "Translated subtitles"),
            "both" to uiText("Оригинал + перевод", "Original + translation"))) { mode ->
            if (mode == "off") change { it.copy(enabled = false) }
            else if (!settings.offeredAuto) offer = true
            else change { it.copy(enabled = true, mode = mode) }
        } }
        item { SettingsChoice(uiText("Язык озвучивания перевода", "Translated speech language"), settings.language,
            listOf("" to uiText("Как язык перевода", "Follow translation language")) + translationLanguages()) { value -> change { it.copy(language = value, voice = "") } } }
        item { Text(uiText("Для оригинала язык голоса подбирается по субтитрам. По умолчанию используются установленные голоса без сети. Перевод и языковые модели — существующие в PeerIche.",
            "Original speech follows the subtitle language. Installed offline voices are preferred. Translation uses PeerIche's existing models and cache."), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsChoice(uiText("TTS-движок", "TTS engine"), settings.engine,
            listOf("" to uiText("Системный", "System default")) + engines) { value -> change { it.copy(engine = value, voice = "") } } }
        item { SettingsChoice(uiText("Голос", "Voice"), settings.voice,
            listOf("" to uiText("Автоматический выбор", "Automatic selection")) + voices.filter { it.language.substringBefore('-') == language.substringBefore('-') }.map {
                it.name to (it.name + if (it.network) uiText(" · сеть", " · network") else uiText(" · на устройстве", " · on device"))
            }) { value -> change { it.copy(voice = value) } } }
        item { SettingsSlider(uiText("Скорость речи", "Speech rate"), settings.rate * 100, 50f..200f, "%") { value -> change { it.copy(rate = value / 100) } } }
        item { SettingsSlider(uiText("Высота голоса", "Voice pitch"), settings.pitch * 100, 50f..200f, "%") { value -> change { it.copy(pitch = value / 100) } } }
        item { SettingsChoice(uiText("Исходный звук", "Original audio"), settings.audio, listOf("unchanged" to uiText("Без изменений", "Unchanged"),
            "duck" to uiText("Приглушать во время речи", "Duck during speech"), "mute" to uiText("Отключать во время речи", "Mute during speech"))) { value -> change { it.copy(audio = value) } } }
        if (settings.audio == "duck") item { SettingsSlider(uiText("Громкость оригинала во время речи", "Original volume during speech"), settings.duckPercent.toFloat(), 0f..100f, "%") { value -> change { it.copy(duckPercent = value.toInt()) } } }
        item { SettingsChoice(uiText("Длинные реплики", "Long utterances"), settings.strategy, listOf("skip" to uiText("Пропускать устаревшее", "Skip outdated speech"),
            "speed" to uiText("Ускорять речь (до 2,5×)", "Speed up speech (up to 2.5×)"), "wait" to uiText("Приостановить видео до конца речи", "Pause video until speech ends"))) { value -> change { it.copy(strategy = value) } } }
        item { SettingsChoice(uiText("Если перевод недоступен", "If translation is unavailable"), settings.fallback, listOf(
            "original" to uiText("Читать оригинал", "Speak original"), "skip" to uiText("Пропустить реплику", "Skip utterance"))) { value -> change { it.copy(fallback = value) } } }
        item { SettingsRow(uiText("Состояние", "Status"), speechStatus(if (engineStatus != "ready") engineStatus else runtime)) }
        item { SettingsRow(uiText("Проверить голос", "Test voice"), uiText("Язык: $languageLabel. Видео будет приостановлено.", "Language: $languageLabel. Video will pause."), onClick = {
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
        item { SettingsRow(uiText("Остановить проверку", "Stop test"), onClick = { testJob?.cancel(); engine.stop(); engineStatus = "ready" }) }
        item { SettingsRow(uiText("Установить языковые данные TTS", "Install TTS language data"), onClick = {
            try { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (settings.engine.isNotBlank()) setPackage(settings.engine) }) }
            catch (_: Exception) { engineStatus = "engine_error" }
        }) }
    }
    if (offer) SpeechFirstEnablePrompt({ offer = false }) {
        change { it.copy(enabled = true, offeredAuto = true, mode = "auto") }; offer = false
    }
}
