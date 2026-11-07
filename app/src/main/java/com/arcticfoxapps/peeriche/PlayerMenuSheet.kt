package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.media3.ui.TrackSelectionDialogBuilder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun showSubtitleTracks(context: android.content.Context, player: Player?): android.app.Dialog {
    return if (player == null || player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) {
        android.app.AlertDialog.Builder(context).setMessage(context.getString(R.string.no_subtitles)).setPositiveButton(android.R.string.ok, null).create()
    } else TrackSelectionDialogBuilder(context, context.getString(R.string.subtitles), player, C.TRACK_TYPE_TEXT).setShowDisableOption(true).build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerMenuSheet(player: Player?, onPip: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val preferences = AppPreferences.get(context)
    val options by preferences.options.collectAsState()
    var tracks by remember { mutableStateOf<android.app.Dialog?>(null) }
    var speech by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { tracks?.dismiss() } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            SettingsChoice(uiText("Качество", "Quality"), options.quality.toString(), listOf("0" to uiText("Авто", "Auto")) +
                listOf(144, 240, 360, 480, 720, 1080, 2160).map { "$it" to "${it}p" }) { value -> preferences.update { it.copy(quality = value.toInt()) } }
            SettingsRow(uiText("Субтитры", "Subtitles"), onClick = { tracks?.dismiss(); tracks = showSubtitleTracks(context, player).also { it.show() } })
            SettingsChoice(uiText("Скорость воспроизведения", "Playback speed"), options.playbackSpeed.toString(),
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { "$it" to "${it}×" }) { value -> preferences.update { it.copy(playbackSpeed = value.toFloat()) } }
            SettingsChoice(uiText("Формат изображения", "Aspect ratio"), options.videoAspect,
                listOf("auto" to uiText("Исходный", "Original")) + listOf("16:9", "4:3", "1:1", "21:9").map { it to it }) { value -> preferences.update { it.copy(videoAspect = value) } }
            SettingsRow(uiText("Озвучивание субтитров", "Subtitle speech"), onClick = { speech = true })
            if (context.playerActivity()?.pipSupport?.supported == true) SettingsRow(uiText("Картинка в картинке", "Picture in picture"), onClick = { onClose(); onPip() })
            SettingsRow(uiText("Ещё", "More"), onClick = { more = true })
        }
    }
    if (speech) SpeechSettingsDialog { speech = false }
    if (more) Dialog(onDismissRequest = { more = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { more = false }) { Icon(Icons.Default.ArrowBack, uiText("Назад", "Back")) }
                    Text(uiText("Параметры плеера", "Player settings"), style = MaterialTheme.typography.titleLarge)
                }
                PlayerSettingsPage(options, preferences) { speech = true }
            }
        }
    }
}
