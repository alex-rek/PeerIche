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

@Composable internal fun searchSortChoices() = listOf("-match" to uiText(R.string.ui_relevance_5278a3),
    "-publishedAt" to uiText(R.string.ui_newest_first_a4f3b2), "-views" to uiText(R.string.ui_views_385ac8))

@Composable internal fun SearchSortMenu(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = searchSortChoices()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(uiText(R.string.home_sort_label, choices.firstOrNull { it.first == value }?.second ?: choices.first().second) + " ▾")
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(id) }) }
            }
        }
    }
}

@Composable internal fun searchSourceName(key: String): String = when (key) {
    "sepia" -> "Sepia Search"
    "peerseek" -> "PeerSeek"
    "custom" -> uiText(R.string.ui_custom_search_indexes_33a8cb)
    "federated" -> uiText(R.string.ui_selected_server_search_ff8be3)
    "direct" -> uiText(R.string.ui_connected_servers_search_f61604)
    else -> uiText(R.string.ui_local_search_cache_213967)
}

@Composable internal fun SearchSettingsPage(options: AppOptions, preferences: AppPreferences) {
    var indexes by remember(options.customIndexes) { mutableStateOf(options.customIndexes) }
    var invalid by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingsChoice(uiText(R.string.ui_search_mode_083d38), options.searchMode,
            listOf("parallel" to uiText(R.string.ui_parallel_563423), "sequential" to uiText(R.string.ui_sequential_8e675b), "hybrid" to uiText(R.string.ui_until_enough_results_dfbe89))) { value -> preferences.update { it.copy(searchMode = value) } } }
        item { Text(uiText(R.string.ui_search_sources_and_priority_295e1b), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
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
                    } }) { Icon(Icons.Default.ArrowUpward, uiText(R.string.ui_move_up_cdf16d)) }
                    IconButton(enabled = index < options.searchOrder.lastIndex, onClick = { preferences.update { current ->
                        val order = current.searchOrder.toMutableList(); java.util.Collections.swap(order, index, index + 1); current.copy(searchOrder = order)
                    } }) { Icon(Icons.Default.ArrowDownward, uiText(R.string.ui_move_down_a0544c)) }
                }
            }
        } }
        item { Column(Modifier.padding(24.dp)) {
            OutlinedTextField(indexes, { indexes = it; invalid = false }, label = { Text(uiText(R.string.ui_custom_indexes_one_https_address_per_line_84c995)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2, isError = invalid)
            TextButton(onClick = {
                val urls = indexes.lines().map(String::trim).filter(String::isNotBlank)
                invalid = urls.any { runCatching { normalizeServer(it) }.isFailure }
                if (!invalid) preferences.update { it.copy(customIndexes = urls.map(::normalizeServer).distinct().joinToString("\n")) }
            }) { Text(uiText(R.string.ui_save_indexes_a883b9)) }
            if (invalid) Text(uiText(R.string.ui_check_the_https_addresses_5a8051), color = MaterialTheme.colorScheme.error)
        } }
        item { SettingsSlider(uiText(R.string.ui_sufficient_results_64a8a1), options.searchEnough.toFloat(), 5f..100f) { value -> preferences.update { it.copy(searchEnough = value.toInt()) } } }
        item { SettingsSlider(uiText(R.string.ui_parallel_requests_34a8a3), options.searchParallel.toFloat(), 1f..6f) { value -> preferences.update { it.copy(searchParallel = value.toInt()) } } }
        item { SettingsSlider(uiText(R.string.ui_source_timeout_b4e594), options.searchTimeout.toFloat(), 3f..30f, uiText(R.string.ui_sec_56737f)) { value -> preferences.update { it.copy(searchTimeout = value.toInt()) } } }
        item { SettingsChoice(uiText(R.string.ui_result_order_0b1c23), options.searchSort, searchSortChoices()) { value -> preferences.update { it.copy(searchSort = value) } } }
        item { SettingsChoice(uiText(R.string.ui_video_availability_69e650), options.availability, listOf("all" to uiText(R.string.ui_all_results_069caf), "unknown_or_available" to uiText(R.string.ui_hide_known_unavailable_videos_615b8f), "available_only" to uiText(R.string.ui_verified_playable_only_slower_a59030))) { value -> preferences.update { it.copy(availability = value) } } }
        item { SettingsToggle(uiText(R.string.ui_include_sensitive_content_9b8651), options.includeSensitive) { value -> preferences.update { it.copy(includeSensitive = value) } } }
        item { SettingsChoice(uiText(R.string.ui_sensitive_content_display_dc3750), options.sensitiveDisplay, listOf("hide" to uiText(R.string.ui_hide_929379), "blur" to uiText(R.string.ui_blur_thumbnails_1c7734), "warning" to uiText(R.string.ui_replace_thumbnail_with_warning_9eae35), "show" to uiText(R.string.ui_show_ab515c))) { value -> preferences.update { it.copy(sensitiveDisplay = value) } } }
    }
}
