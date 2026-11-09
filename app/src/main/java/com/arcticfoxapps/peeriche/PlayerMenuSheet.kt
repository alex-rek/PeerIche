package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Player


@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerMenuSheet(player: Player?, hasSubtitles: Boolean, onPip: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val preferences = AppPreferences.get(context)
    val options by preferences.options.collectAsState()
    var tracks by remember { mutableStateOf(false) }
    var speech by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .85f)
            .verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            CompactPlayerChoice(Icons.Default.Tune, uiText(R.string.ui_quality_ca3d7b), options.quality.toString(), listOf("0" to uiText(R.string.ui_auto_cbb079)) +
                listOf(144, 240, 360, 480, 720, 1080, 2160).map { "$it" to "${it}p" }) { value -> preferences.update { it.copy(quality = value.toInt()) } }
            if (hasSubtitles) CompactPlayerRow(Icons.Default.Subtitles, uiText(R.string.ui_subtitles_4b389d),
                if (options.subtitlesEnabled) uiText(R.string.ui_on_c10791) else uiText(R.string.ui_off_b8a9e9)) { tracks = true }
            CompactPlayerChoice(Icons.Default.Speed, uiText(R.string.ui_playback_speed_cd9e90), options.playbackSpeed.toString(),
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { "$it" to "${it}×" }) { value -> preferences.update { it.copy(playbackSpeed = value.toFloat()) } }
            CompactPlayerChoice(Icons.Default.AspectRatio, uiText(R.string.ui_aspect_ratio_47bc1a), options.videoAspect,
                listOf("auto" to uiText(R.string.ui_original_77bed0)) + listOf("16:9", "4:3", "1:1", "21:9").map { it to it }) { value -> preferences.update { it.copy(videoAspect = value) } }
            if (hasSubtitles) CompactPlayerRow(Icons.Default.RecordVoiceOver, uiText(R.string.ui_subtitle_speech_ffd472),
                if (options.speech.enabled) uiText(R.string.ui_on_e3afd0) else uiText(R.string.ui_off_b8a9e9)) { speech = true }
            CompactPlayerRow(Icons.Default.PictureInPictureAlt, uiText(R.string.ui_picture_in_picture_481ea8)) { onClose(); onPip() }
            CompactPlayerRow(Icons.Default.Settings, uiText(R.string.ui_more_81555a)) { more = true }
        }
    }
    LaunchedEffect(hasSubtitles) { if (!hasSubtitles) { tracks = false; speech = false } }
    if (tracks && hasSubtitles) SubtitleTracksDialog(player) { tracks = false }
    if (speech) SpeechSettingsDialog { speech = false }
    if (more) Dialog(onDismissRequest = { more = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { more = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_02b74f)) }
                    Text(uiText(R.string.ui_player_settings_2989d1), style = MaterialTheme.typography.titleLarge)
                }
                PlayerSettingsPage(options, preferences) { speech = true }
            }
        }
    }
}

@Composable private fun CompactPlayerRow(icon: ImageVector, label: String, value: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (value.isNotBlank()) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Icon(interfaceForwardChevron(), null, Modifier.size(20.dp))
    }
}

@Composable private fun CompactPlayerChoice(icon: ImageVector, label: String, selected: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    CompactPlayerRow(icon, label, choices.firstOrNull { it.first == selected }?.second ?: selected) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { (value, title) -> Row(Modifier.fillMaxWidth().clickable { onSelect(value); open = false }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == value, onClick = null); Text(title, Modifier.padding(start = 12.dp))
            } }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(uiText(R.string.ui_close_641636)) } })
}
