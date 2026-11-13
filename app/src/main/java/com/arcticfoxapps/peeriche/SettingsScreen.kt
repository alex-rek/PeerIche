package com.arcticfoxapps.peeriche

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

@Composable internal fun uiText(@androidx.annotation.StringRes id: Int, vararg arguments: Any): String =
    if (arguments.isEmpty()) androidx.compose.ui.res.stringResource(id)
    else androidx.compose.ui.res.stringResource(id, *arguments)

@Composable internal fun interfaceForwardChevron() =
    if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) Icons.Default.ChevronLeft else Icons.Default.ChevronRight

@Composable internal fun SettingsScreen(onClose: () -> Unit, onCheckUpdates: () -> Unit, initialSection: String? = null) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences.get(context) }
    val options by preferences.options.collectAsState()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var speechParent by rememberSaveable { mutableStateOf("player") }
    val categories = listOf(
        Triple("general", uiText(R.string.ui_general_4d7c8e), Icons.Default.Settings),
        Triple("search", uiText(R.string.ui_search_c2faca), Icons.Default.Search),
        Triple("translations", uiText(R.string.ui_language_and_translations_29d99b), Icons.Default.Translate),
        Triple("player", uiText(R.string.ui_player_cd0260), Icons.Default.PlayCircle),
        Triple("hidden_channels", uiText(R.string.hidden_channels), Icons.Default.VisibilityOff),
        Triple("about", uiText(R.string.ui_about_9a8919), Icons.Default.Info)
    )
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (section == "speech") section = speechParent else if (section != null) section = null else onClose() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (section == "speech") section = speechParent else if (section != null) section = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_02b74f))
                    }
                    Text(if (section == "speech") uiText(R.string.ui_subtitle_speech_4f7011) else categories.firstOrNull { it.first == section }?.second ?: uiText(R.string.ui_settings_2bca56),
                        fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                }
                when (section) {
                    null -> LazyColumn(Modifier.fillMaxSize()) {
                        items(categories) { category ->
                            SettingsRow(category.second, icon = category.third, onClick = { section = category.first })
                        }
                    }
                    "hidden_channels" -> HiddenChannelsSettings()
                    "general" -> GeneralSettings(options, preferences)
                    "search" -> SearchSettingsPage(options, preferences)
                    "translations" -> TranslationSettingsPage(options, preferences) { speechParent = "translations"; section = "speech" }
                    "player" -> PlayerSettingsPage(options, preferences) { speechParent = "player"; section = "speech" }
                    "speech" -> SubtitleSpeechSettingsPage(options, preferences)
                    "about" -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("PeerIche", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(uiText(R.string.ui_version_994a31) + " " + BuildConfig.VERSION_NAME + " · " + BuildConfig.BUILD_DATE)
                        Text(uiText(R.string.ui_developer_arctic_fox_apps_fa73f2))
                        Text(uiText(R.string.ui_independent_peertube_client_not_affiliated_8ece4c))
                        FilledTonalButton(onClick = onCheckUpdates) { Text(uiText(R.string.ui_check_for_updates_a3997c)) }
                    }
                }
            }
        }
    }
}

@Composable internal fun SettingsRow(title: String, summary: String? = null, icon: ImageVector? = null,
    onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 18.sp)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable internal fun SettingsToggle(title: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
    SettingsRow(title, summary, onClick = { onChange(!checked) }) { Switch(checked, onCheckedChange = onChange) }
}

@Composable internal fun SettingsChoice(title: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(title, choices.firstOrNull { it.first == value }?.second ?: value, onClick = { open = true })
    if (open) OptionPicker(title, value, choices, onDismiss = { open = false }) { onChange(it); open = false }
}

@Composable internal fun OptionPicker(title: String, selected: String, choices: List<Pair<String, String>>,
    onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            if (choices.size > 12) OutlinedTextField(filter, { filter = it }, singleLine = true,
                label = { Text(uiText(R.string.ui_find_90925b)) }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices.filter { it.second.contains(filter, true) || it.first.contains(filter, true) }, key = { it.first }) { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(key) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == key, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.ui_cancel_28fb55)) } })
}

@Composable internal fun SettingsSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String = "", onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("$title: ${draft.toInt()}$suffix", fontSize = 18.sp)
        Slider(draft, { draft = it }, valueRange = range, onValueChangeFinished = { onChange(draft) })
    }
}

@Composable private fun GeneralSettings(options: AppOptions, preferences: AppPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by remember(context) { context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[themeModeKey] ?: "system" } }.collectAsState("system")
    var homeContentDialog by remember { mutableStateOf(false) }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsChoice(uiText(R.string.ui_app_theme_610976), theme, listOf("system" to uiText(R.string.ui_follow_android_9652ce),
            "graphite" to uiText(R.string.ui_graphite_7c3294), "light" to uiText(R.string.ui_light_bc799a), "aurora" to uiText(R.string.ui_aurora_blue_a5d4ab))) {
            scope.launch { saveThemeMode(context, it) }
        } }
        item { SettingsChoice(uiText(R.string.ui_interface_language_88df3c),
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else options.interfaceLanguage,
            listOf("" to uiText(R.string.ui_follow_android_9652ce)) + interfaceLanguages) { language ->
            preferences.update { it.copy(interfaceLanguage = language) }
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
            else (context as? ComponentActivity)?.recreate()
        } }
        item { SettingsChoice(uiText(R.string.ui_country_0db877), options.country,
            listOf("" to uiText(R.string.ui_all_countries_719a89)) + Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(locale) }.sortedBy { it.second }) {
            preferences.update { current -> current.copy(country = it) }
        } }
        item { SettingsRow(uiText(R.string.home_content), uiText(homeContentLabel(options.homeContent)), onClick = { homeContentDialog = true }) }
        item { Text(uiText(R.string.ui_the_directory_prefers_servers_from_this_co_c7856f),
            Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (homeContentDialog) HomeContentDialog(options, preferences) { homeContentDialog = false }
}

@Composable internal fun PlayerSettingsPage(options: AppOptions, preferences: AppPreferences, onSpeech: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsToggle(uiText(R.string.ui_start_playback_automatically_61d054), options.autoplay) { value -> preferences.update { it.copy(autoplay = value) } } }
        item { SettingsToggle(uiText(R.string.ui_background_playback_364de7), options.backgroundPlayback) { value -> preferences.update { it.copy(backgroundPlayback = value) } } }
        item { SettingsChoice(uiText(R.string.ui_default_quality_424975), options.quality.toString(), listOf("0" to uiText(R.string.ui_automatic_00a8ae)) + listOf(360, 480, 720, 1080, 2160).map { "$it" to "${it}p" }) { value -> preferences.update { it.copy(quality = value.toInt()) } } }
        item { SettingsChoice(uiText(R.string.ui_playback_speed_cd9e90), options.playbackSpeed.toString(), listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { "$it" to "${it}×" }) { value -> preferences.update { it.copy(playbackSpeed = value.toFloat()) } } }
        item { SettingsChoice(uiText(R.string.ui_hide_controls_after_5aa971), options.controlsTimeout.toString(), listOf("0" to uiText(R.string.ui_keep_visible_63e256)) + listOf(3, 5, 10, 15).map { "$it" to "$it ${uiText(R.string.ui_sec_563e48)}" }) { value -> preferences.update { it.copy(controlsTimeout = value.toInt()) } } }
        item { SettingsChoice(uiText(R.string.ui_seek_increment_8f8898), options.seekSeconds.toString(), listOf(5, 10, 15, 30).map { "$it" to "$it ${uiText(R.string.ui_sec_563e48)}" }) { value -> preferences.update { it.copy(seekSeconds = value.toInt()) } } }
        item { SettingsChoice(uiText(R.string.ui_video_scaling_cfb650), options.resizeMode, listOf("fit" to uiText(R.string.ui_fit_dc1e4e), "zoom" to uiText(R.string.ui_zoom_crop_e0b550), "fill" to uiText(R.string.ui_stretch_6f9bc7))) { value -> preferences.update { it.copy(resizeMode = value) } } }
        item { SettingsChoice(uiText(R.string.ui_aspect_ratio_47bc1a), options.videoAspect,
            listOf("auto" to uiText(R.string.ui_original_77bed0)) + listOf("16:9", "4:3", "1:1", "21:9").map { it to it }) { value -> preferences.update { it.copy(videoAspect = value) } } }
        item { SettingsRow(uiText(R.string.ui_subtitles_speech_ab081c), uiText(R.string.ui_voice_language_timing_and_original_audio_afa432), onClick = onSpeech) }
        item { SettingsToggle(uiText(R.string.ui_subtitles_by_default_007b19), options.subtitlesEnabled) { value -> preferences.update { it.copy(subtitlesEnabled = value) } } }
        item { SettingsChoice(uiText(R.string.ui_preferred_subtitle_language_5e7c1c), options.subtitleLanguage,
            listOf("" to uiText(R.string.ui_follow_android_9652ce)) + translationLanguages()) { value -> preferences.update { it.copy(subtitleLanguage = value) } } }
        item { SettingsSlider(uiText(R.string.ui_subtitle_size_f492b3), options.subtitleSize.toFloat(), 12f..42f, " sp") { value -> preferences.update { it.copy(subtitleSize = value.toInt()) } } }
        item { SettingsChoice(uiText(R.string.ui_subtitle_color_ee2bd3), options.subtitleColor, listOf("white" to uiText(R.string.ui_white_eecc94), "yellow" to uiText(R.string.ui_yellow_20cd3f), "green" to uiText(R.string.ui_green_3fb852), "cyan" to uiText(R.string.ui_cyan_335685))) { value -> preferences.update { it.copy(subtitleColor = value) } } }
        item { SettingsSlider(uiText(R.string.ui_horizontal_position_1439c5), options.subtitleHorizontal.toFloat(), 10f..90f, "%") { value -> preferences.update { it.copy(subtitleHorizontal = value.toInt()) } } }
        item { SettingsSlider(uiText(R.string.ui_distance_from_bottom_ae3e60), options.subtitleBottom.toFloat(), 0f..45f, "%") { value -> preferences.update { it.copy(subtitleBottom = value.toInt()) } } }
        item { BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp).height(150.dp)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {}
            Text(uiText(R.string.ui_subtitle_preview_73219c), color = Color(subtitleArgb(options.subtitleColor)), fontSize = options.subtitleSize.sp,
                modifier = Modifier.align(Alignment.BottomCenter).offset(x = maxWidth * ((options.subtitleHorizontal - 50) / 100f), y = -maxHeight * (options.subtitleBottom / 100f)))
        } }
    }
}
