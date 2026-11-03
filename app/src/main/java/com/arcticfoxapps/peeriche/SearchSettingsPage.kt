package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun searchSourceName(key: String): String = when (key) {
    "sepia" -> "Sepia Search"
    "peerseek" -> "PeerSeek"
    "custom" -> uiText("Свои поисковые индексы", "Custom search indexes")
    "federated" -> uiText("Поиск выбранного сервера", "Selected server search")
    "direct" -> uiText("Поиск по подключённым серверам", "Connected servers search")
    else -> uiText("Локальный кэш поиска", "Local search cache")
}

@Composable internal fun SearchSettingsPage(options: AppOptions, preferences: AppPreferences) {
    var indexes by remember(options.customIndexes) { mutableStateOf(options.customIndexes) }
    var invalid by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsChoice(uiText("Режим поиска", "Search mode"), options.searchMode,
            listOf("parallel" to uiText("Параллельно", "Parallel"), "sequential" to uiText("Последовательно", "Sequential"), "hybrid" to uiText("До достаточного количества результатов", "Until enough results"))) { value -> preferences.update { it.copy(searchMode = value) } } }
        item { Text(uiText("Источники и порядок поиска", "Search sources and priority"), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
        options.searchOrder.forEachIndexed { index, key -> item(key = key) {
            SettingsRow(searchSourceName(key), onClick = {
                preferences.update { current ->
                    val enabled = current.searchSources.toMutableList()
                    if (key in enabled && enabled.size > 1) enabled.remove(key) else if (key !in enabled) enabled.add(key)
                    current.copy(searchSources = enabled)
                }
            }) {
                Checkbox(key in options.searchSources, onCheckedChange = { selected -> preferences.update {
                    it.copy(searchSources = if (selected) (it.searchSources + key).distinct() else if (it.searchSources.size > 1) it.searchSources - key else it.searchSources)
                } })
                Column {
                    IconButton(enabled = index > 0, onClick = { preferences.update { current ->
                        val order = current.searchOrder.toMutableList(); java.util.Collections.swap(order, index, index - 1); current.copy(searchOrder = order)
                    } }) { Icon(Icons.Default.ArrowUpward, uiText("Выше", "Move up")) }
                    IconButton(enabled = index < options.searchOrder.lastIndex, onClick = { preferences.update { current ->
                        val order = current.searchOrder.toMutableList(); java.util.Collections.swap(order, index, index + 1); current.copy(searchOrder = order)
                    } }) { Icon(Icons.Default.ArrowDownward, uiText("Ниже", "Move down")) }
                }
            }
        } }
        item { Column(Modifier.padding(24.dp)) {
            OutlinedTextField(indexes, { indexes = it; invalid = false }, label = { Text(uiText("Свои индексы — по одному HTTPS-адресу в строке", "Custom indexes — one HTTPS address per line")) },
                modifier = Modifier.fillMaxWidth(), minLines = 2, isError = invalid)
            TextButton(onClick = {
                val urls = indexes.lines().map(String::trim).filter(String::isNotBlank)
                invalid = urls.any { runCatching { normalizeServer(it) }.isFailure }
                if (!invalid) preferences.update { it.copy(customIndexes = urls.map(::normalizeServer).distinct().joinToString("\n")) }
            }) { Text(uiText("Сохранить индексы", "Save indexes")) }
            if (invalid) Text(uiText("Проверьте HTTPS-адреса.", "Check the HTTPS addresses."), color = MaterialTheme.colorScheme.error)
        } }
        item { SettingsSlider(uiText("Достаточно результатов", "Sufficient results"), options.searchEnough.toFloat(), 5f..100f) { value -> preferences.update { it.copy(searchEnough = value.toInt()) } } }
        item { SettingsSlider(uiText("Параллельных запросов", "Parallel requests"), options.searchParallel.toFloat(), 1f..6f) { value -> preferences.update { it.copy(searchParallel = value.toInt()) } } }
        item { SettingsSlider(uiText("Ожидание источника", "Source timeout"), options.searchTimeout.toFloat(), 3f..30f, uiText(" сек.", " sec")) { value -> preferences.update { it.copy(searchTimeout = value.toInt()) } } }
        item { SettingsChoice(uiText("Сортировка результатов", "Result order"), options.searchSort, listOf("-match" to uiText("По соответствию", "Relevance"), "-publishedAt" to uiText("Сначала новые", "Newest first"), "-views" to uiText("По просмотрам", "Views"))) { value -> preferences.update { it.copy(searchSort = value) } } }
        item { SettingsChoice(uiText("Доступность видео", "Video availability"), options.availability, listOf("all" to uiText("Все результаты", "All results"), "unknown_or_available" to uiText("Скрывать заведомо недоступные", "Hide known unavailable videos"), "available_only" to uiText("Только проверенные — медленнее", "Verified playable only — slower"))) { value -> preferences.update { it.copy(availability = value) } } }
        item { SettingsToggle(uiText("Включать чувствительный контент", "Include sensitive content"), options.includeSensitive) { value -> preferences.update { it.copy(includeSensitive = value) } } }
        item { SettingsChoice(uiText("Отображение чувствительного контента", "Sensitive content display"), options.sensitiveDisplay, listOf("hide" to uiText("Скрывать", "Hide"), "blur" to uiText("Размывать обложки", "Blur thumbnails"), "warning" to uiText("Предупреждение вместо обложки", "Replace thumbnail with warning"), "show" to uiText("Показывать", "Show"))) { value -> preferences.update { it.copy(sensitiveDisplay = value) } } }
    }
}
