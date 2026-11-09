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
        item { SettingsToggle(uiText(R.string.ui_translate_titles_descriptions_and_comments_234bae), options.translateMetadata) { value -> preferences.update { it.copy(translateMetadata = value) } } }
        item { SettingsToggle(uiText(R.string.ui_translate_subtitles_92ac18), options.translateSubtitles) { value -> preferences.update { it.copy(translateSubtitles = value) } } }
        item { SettingsChoice(uiText(R.string.ui_translation_language_401967), if (options.translationFollowsInterface) "" else options.translateTo,
            listOf("" to uiText(R.string.translation_follow_interface)) + languages) { value ->
            preferences.update { it.copy(translateTo = value.ifBlank { it.translateTo }, translationFollowsInterface = value.isBlank()) }
        } }
        item { SettingsToggle(uiText(R.string.ui_download_models_only_over_wi_fi_b739a9), options.modelsWifiOnly) { value -> preferences.update { it.copy(modelsWifiOnly = value) } } }
        item { Text(uiText(R.string.ui_translation_runs_on_your_device_both_langu_bebcf9), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (status == "downloading" || status == "translating") item { Text(uiText(R.string.ui_preparing_models_or_translating_a98b86), Modifier.padding(horizontal = 24.dp)) }
        if (error || status == "error") item { Text(uiText(R.string.ui_translation_could_not_be_prepared_check_yo_180ecc), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        item { Text(uiText(R.string.ui_language_models_6ecbd3), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
        items(languages, key = { it.first }) { (language, label) ->
            SettingsRow(label, if (language in downloaded) uiText(R.string.ui_downloaded_8b6faf) else uiText(R.string.ui_not_downloaded_1af9b2)) {
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
                }) { Text(if (language in downloaded) uiText(R.string.ui_delete_423009) else uiText(R.string.ui_download_eac11a)) }
            }
        }
    }
}
