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

@Composable internal fun uiText(ru: String, en: String): String =
    if (ConfigurationCompat.getLocales(LocalConfiguration.current)[0]?.language == "ru") ru else en

@Composable internal fun SettingsScreen(onClose: () -> Unit, onCheckUpdates: () -> Unit, initialSection: String? = null) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences.get(context) }
    val options by preferences.options.collectAsState()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    val categories = listOf(
        Triple("general", uiText("Общие", "General"), Icons.Default.Settings),
        Triple("search", uiText("Поиск", "Search"), Icons.Default.Search),
        Triple("servers", uiText("Серверы", "Servers"), Icons.Default.Dns),
        Triple("translations", uiText("Язык и переводы", "Language and translations"), Icons.Default.Translate),
        Triple("player", uiText("Плеер", "Player"), Icons.Default.PlayCircle),
        Triple("about", uiText("О приложении", "About"), Icons.Default.Info)
    )
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (section == "speech") section = "player" else if (section != null) section = null else onClose() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (section == "speech") section = "player" else if (section != null) section = null else onClose() }) {
                        Icon(Icons.Default.ArrowBack, uiText("Назад", "Back"))
                    }
                    Text(if (section == "speech") uiText("Озвучивание субтитров", "Subtitle speech") else categories.firstOrNull { it.first == section }?.second ?: uiText("Настройки", "Settings"),
                        fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                }
                when (section) {
                    null -> LazyColumn(Modifier.fillMaxSize()) {
                        items(categories) { category ->
                            SettingsRow(category.second, icon = category.third, onClick = { section = category.first })
                        }
                    }
                    "general" -> GeneralSettings(options, preferences)
                    "search" -> SearchSettingsPage(options, preferences)
                    "servers" -> ServerSettingsPage(options)
                    "translations" -> TranslationSettingsPage(options, preferences)
                    "player" -> PlayerSettingsPage(options, preferences) { section = "speech" }
                    "speech" -> SubtitleSpeechSettingsPage(options, preferences)
                    "about" -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("PeerIche", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(uiText("Версия", "Version") + " " + BuildConfig.VERSION_NAME + " · " + BuildConfig.BUILD_DATE)
                        Text(uiText("Разработчик: Arctic Fox Apps", "Developer: Arctic Fox Apps"))
                        Text(uiText("Независимый клиент PeerTube. Не связан с Framasoft.", "Independent PeerTube client. Not affiliated with Framasoft."))
                        FilledTonalButton(onClick = onCheckUpdates) { Text(uiText("Проверить обновление", "Check for updates")) }
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
                label = { Text(uiText("Найти", "Find")) }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices.filter { it.second.contains(filter, true) || it.first.contains(filter, true) }, key = { it.first }) { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(key) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == key, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Отмена", "Cancel")) } })
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
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsChoice(uiText("Тема приложения", "App theme"), theme, listOf("system" to uiText("Как на устройстве", "Follow Android"),
            "graphite" to uiText("Графит", "Graphite"), "light" to uiText("Светлая", "Light"), "aurora" to uiText("Аврора · синяя", "Aurora · blue"))) {
            scope.launch { saveThemeMode(context, it) }
        } }
        item { SettingsChoice(uiText("Язык интерфейса", "Interface language"), options.interfaceLanguage,
            listOf("" to uiText("Как на устройстве", "Follow Android"), "ru" to "Русский", "en" to "English")) { language ->
            preferences.update { it.copy(interfaceLanguage = language) }
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
            else (context as? ComponentActivity)?.recreate()
        } }
        item { SettingsChoice(uiText("Страна", "Country"), options.country,
            listOf("" to uiText("Все страны", "All countries")) + Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(locale) }.sortedBy { it.second }) {
            preferences.update { current -> current.copy(country = it) }
        } }
        item { Text(uiText("В каталоге предпочтение отдаётся серверам этой страны. Другие страны остаются доступны.", "The directory prefers servers from this country. Other countries remain available."),
            Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun PlayerSettingsPage(options: AppOptions, preferences: AppPreferences, onSpeech: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsToggle(uiText("Автовоспроизведение", "Start playback automatically"), options.autoplay) { value -> preferences.update { it.copy(autoplay = value) } } }
        item { SettingsToggle(uiText("Фоновое воспроизведение", "Background playback"), options.backgroundPlayback) { value -> preferences.update { it.copy(backgroundPlayback = value) } } }
        item { SettingsChoice(uiText("Качество по умолчанию", "Default quality"), options.quality.toString(), listOf("0" to uiText("Автоматически", "Automatic")) + listOf(360, 480, 720, 1080, 2160).map { "$it" to "${it}p" }) { value -> preferences.update { it.copy(quality = value.toInt()) } } }
        item { SettingsChoice(uiText("Скорость воспроизведения", "Playback speed"), options.playbackSpeed.toString(), listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { "$it" to "${it}×" }) { value -> preferences.update { it.copy(playbackSpeed = value.toFloat()) } } }
        item { SettingsChoice(uiText("Скрывать управление через", "Hide controls after"), options.controlsTimeout.toString(), listOf("0" to uiText("Не скрывать", "Keep visible")) + listOf(3, 5, 10, 15).map { "$it" to "$it ${uiText("сек.", "sec")}" }) { value -> preferences.update { it.copy(controlsTimeout = value.toInt()) } } }
        item { SettingsChoice(uiText("Шаг перемотки", "Seek increment"), options.seekSeconds.toString(), listOf(5, 10, 15, 30).map { "$it" to "$it ${uiText("сек.", "sec")}" }) { value -> preferences.update { it.copy(seekSeconds = value.toInt()) } } }
        item { SettingsChoice(uiText("Масштаб видео", "Video scaling"), options.resizeMode, listOf("fit" to uiText("Вписать", "Fit"), "zoom" to uiText("Заполнить с обрезкой", "Zoom / crop"), "fill" to uiText("Растянуть", "Stretch"))) { value -> preferences.update { it.copy(resizeMode = value) } } }
        item { SettingsChoice(uiText("Формат изображения", "Aspect ratio"), options.videoAspect,
            listOf("auto" to uiText("Исходный", "Original")) + listOf("16:9", "4:3", "1:1", "21:9").map { it to it }) { value -> preferences.update { it.copy(videoAspect = value) } } }
        item { SettingsRow(uiText("Субтитры → Озвучивание", "Subtitles → Speech"), uiText("Голос, язык, синхронизация и исходный звук", "Voice, language, timing and original audio"), onClick = onSpeech) }
        item { SettingsToggle(uiText("Субтитры по умолчанию", "Subtitles by default"), options.subtitlesEnabled) { value -> preferences.update { it.copy(subtitlesEnabled = value) } } }
        item { SettingsChoice(uiText("Предпочтительный язык субтитров", "Preferred subtitle language"), options.subtitleLanguage,
            listOf("" to uiText("Как на устройстве", "Follow Android")) + translationLanguages()) { value -> preferences.update { it.copy(subtitleLanguage = value) } } }
        item { SettingsSlider(uiText("Размер субтитров", "Subtitle size"), options.subtitleSize.toFloat(), 12f..42f, " sp") { value -> preferences.update { it.copy(subtitleSize = value.toInt()) } } }
        item { SettingsChoice(uiText("Цвет субтитров", "Subtitle color"), options.subtitleColor, listOf("white" to uiText("Белый", "White"), "yellow" to uiText("Жёлтый", "Yellow"), "green" to uiText("Зелёный", "Green"), "cyan" to uiText("Голубой", "Cyan"))) { value -> preferences.update { it.copy(subtitleColor = value) } } }
        item { SettingsSlider(uiText("Положение по горизонтали", "Horizontal position"), options.subtitleHorizontal.toFloat(), 10f..90f, "%") { value -> preferences.update { it.copy(subtitleHorizontal = value.toInt()) } } }
        item { SettingsSlider(uiText("Отступ от нижнего края", "Distance from bottom"), options.subtitleBottom.toFloat(), 0f..45f, "%") { value -> preferences.update { it.copy(subtitleBottom = value.toInt()) } } }
        item { BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp).height(150.dp)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {}
            Text(uiText("Пример субтитров", "Subtitle preview"), color = Color(subtitleArgb(options.subtitleColor)), fontSize = options.subtitleSize.sp,
                modifier = Modifier.align(Alignment.BottomCenter).offset(x = maxWidth * ((options.subtitleHorizontal - 50) / 100f), y = -maxHeight * (options.subtitleBottom / 100f)))
        } }
    }
}
