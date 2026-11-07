package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable internal fun translationLanguages(): List<Pair<String, String>> {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
    return remember(locale) { TranslateLanguage.getAllLanguages().map { code -> code to Locale.forLanguageTag(code).getDisplayLanguage(locale) }.sortedBy { it.second } }
}

@Composable internal fun TranslationSettingsPage(options: AppOptions, preferences: AppPreferences) {
    val scope = rememberCoroutineScope()
    val languages = translationLanguages()
    var downloaded by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val status by LocalTranslation.status.collectAsState()
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val found = LocalTranslation.downloaded()
                if (found != downloaded) { downloaded = found; LocalTranslation.modelsChanged() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            delay(5000)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsToggle(uiText("Переводить названия, описания и комментарии", "Translate titles, descriptions and comments"), options.translateMetadata) { value -> preferences.update { it.copy(translateMetadata = value) } } }
        item { SettingsToggle(uiText("Переводить субтитры", "Translate subtitles"), options.translateSubtitles) { value -> preferences.update { it.copy(translateSubtitles = value) } } }
        item { SettingsChoice(uiText("Язык перевода", "Translation language"), options.translateTo, languages) { value -> preferences.update { it.copy(translateTo = value) } } }
        item { SettingsToggle(uiText("Загружать модели только по Wi-Fi", "Download models only over Wi-Fi"), options.modelsWifiOnly) { value -> preferences.update { it.copy(modelsWifiOnly = value) } } }
        item { Text(uiText("Перевод выполняется на устройстве. Для пары языков нужны соответствующие модели; новые модели могут занимать около 30 МБ каждая. Текст видео не отправляется переводчику в облако.", "Translation runs on your device. Both language models are required; new models can be around 30 MB each. Video text is not sent to a cloud translation service."), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (status == "downloading" || status == "translating") item { Text(uiText("Подготовка моделей или перевод…", "Preparing models or translating…"), Modifier.padding(horizontal = 24.dp)) }
        if (error || status == "error") item { Text(uiText("Не удалось подготовить перевод. Проверьте сеть и загруженные модели; исходный текст остаётся доступен.", "Translation could not be prepared. Check your connection and downloaded models; original text stays available."), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        item { Text(uiText("Языковые модели", "Language models"), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
        items(languages, key = { it.first }) { (language, label) ->
            SettingsRow(label, if (language in downloaded) uiText("Загружена", "Downloaded") else uiText("Не загружена", "Not downloaded")) {
                if (busy == language) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton(enabled = busy == null, onClick = {
                    scope.launch {
                        busy = language; error = false
                        try {
                            if (language in downloaded) LocalTranslation.delete(language)
                            else LocalTranslation.download(language, options.modelsWifiOnly)
                            downloaded = LocalTranslation.downloaded()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { busy = null }
                    }
                }) { Text(if (language in downloaded) uiText("Удалить", "Delete") else uiText("Загрузить", "Download")) }
            }
        }
    }
}
