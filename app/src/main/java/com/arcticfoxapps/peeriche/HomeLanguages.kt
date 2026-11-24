package com.arcticfoxapps.peeriche

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Stored in [AppOptions.videoLanguages] to follow the interface language. */
internal const val INTERFACE_VIDEO_LANGUAGE = "interface"

/** PeerTube language codes offered for Home, labelled with each language's own name. */
internal val homeVideoLanguageCodes = listOf("en", "ru", "uk", "be", "kk", "de", "fr", "es", "it", "pt", "nl", "pl", "cs",
    "sv", "fi", "da", "no", "hu", "ro", "el", "tr", "ar", "he", "fa", "hi", "zh", "ja", "ko", "id", "vi", "ca", "eu", "gl", "eo")

/** Regional PeerTube codes that should count as the base language. */
private val regionalVideoLanguages = mapOf("pt" to listOf("pt-BR", "pt-PT"), "zh" to listOf("zh-Hans", "zh-Hant"))

internal fun resolveHomeVideoLanguages(selected: List<String>, interfaceLanguage: String): Set<String> =
    selected.map { if (it == INTERFACE_VIDEO_LANGUAGE) interfaceLanguage else it }
        .filter(String::isNotBlank)
        .flatMap { listOf(it) + regionalVideoLanguages[it].orEmpty() }
        .toSet()

/**
 * Moves videos in the preferred languages to the front, then videos with no language set, then the rest.
 * The order inside each group is kept, so the chosen Home sort still applies. [alwaysFirst] holds
 * identities (see [Video.apiId]) that keep the first group regardless of language, such as subscriptions.
 */
internal fun List<Video>.preferVideoLanguages(languages: Set<String>, alwaysFirst: Set<String> = emptySet()): List<Video> {
    if (languages.isEmpty()) return this
    return sortedBy { video ->
        val code = video.language?.id
        when {
            code in languages || video.apiId() in alwaysFirst -> 0
            code.isNullOrBlank() -> 1
            else -> 2
        }
    }
}

/** Puts the language-filtered request first and fills up from the unfiltered one, without duplicates. */
internal fun mergeLanguageFirst(preferred: List<Video>, others: List<Video>, server: String, limit: Int = 30): List<Video> =
    deduplicateVideos(preferred + others, server).take(limit)

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).let { locale -> locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) } }

@Composable internal fun homeVideoLanguagesSummary(selected: List<String>): String {
    if (selected.isEmpty()) return uiText(R.string.home_languages_any)
    val interfaceLabel = uiText(R.string.home_languages_interface)
    return selected.joinToString(", ") { if (it == INTERFACE_VIDEO_LANGUAGE) interfaceLabel else languageName(it) }
}

@Composable internal fun HomeVideoLanguagesDialog(selected: List<String>, onChange: (List<String>) -> Unit, onClose: () -> Unit) {
    val choices = listOf(INTERFACE_VIDEO_LANGUAGE to uiText(R.string.home_languages_interface)) +
        homeVideoLanguageCodes.map { it to languageName(it) }
    fun toggle(code: String) = onChange(if (code in selected) selected - code else selected + code)
    AlertDialog(onDismissRequest = onClose, title = { Text(uiText(R.string.home_languages)) }, text = {
        Column {
            Text(uiText(R.string.home_languages_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                item(key = "any") {
                    Row(Modifier.fillMaxWidth().clickable { onChange(emptyList()) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected.isEmpty(), onCheckedChange = null)
                        Text(uiText(R.string.home_languages_any), Modifier.padding(start = 12.dp))
                    }
                }
                items(choices, key = { it.first }) { (code, label) ->
                    Row(Modifier.fillMaxWidth().clickable { toggle(code) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(code in selected, onCheckedChange = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(uiText(R.string.ui_done_76ab9d)) } })
}
