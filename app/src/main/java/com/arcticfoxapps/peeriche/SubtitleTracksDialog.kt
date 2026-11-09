package com.arcticfoxapps.peeriche

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

internal data class SubtitleTrackChoice(val group: TrackGroup, val index: Int, val label: String, val selected: Boolean)

// Observe available tracks, not selected cues: disabled subtitles and gaps between
// cues must keep the controls visible. Ignore tracks left over from another video.
@Composable internal fun rememberSubtitleAvailability(player: Player?, mediaId: String): Boolean {
    fun available() = player?.currentMediaItem?.mediaId == mediaId &&
        subtitleTrackChoices(player.currentTracks).isNotEmpty()
    var available by remember(player, mediaId) { mutableStateOf(available()) }
    DisposableEffect(player, mediaId) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { available = available() }
        }
        available = available()
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return available
}

internal fun subtitleTrackChoices(tracks: Tracks): List<SubtitleTrackChoice> = tracks.groups.flatMap { group ->
    if (group.type != C.TRACK_TYPE_TEXT) emptyList() else (0 until group.length).mapNotNull { index ->
        if (!group.isTrackSupported(index)) null else {
            val format = group.getTrackFormat(index)
            val language = format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }.orEmpty()
            SubtitleTrackChoice(group.mediaTrackGroup, index, format.label?.takeIf { it.isNotBlank() }
                ?: language.ifBlank { "${index + 1}" }, group.isTrackSelected(index))
        }
    }
}

// Compose dialogs use the application's Material theme. Media3's native track
// dialog can choose AppCompat's builder, which requires a different Activity theme.
@Composable internal fun SubtitleTracksDialog(player: Player?, onClose: () -> Unit) {
    val preferences = AppPreferences.get(LocalContext.current)
    val options by preferences.options.collectAsState()
    var tracks by remember(player) { mutableStateOf(player?.currentTracks ?: Tracks.EMPTY) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onTracksChanged(value: Tracks) { tracks = value } }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val choices = subtitleTrackChoices(tracks)
    fun select(choice: SubtitleTrackChoice?) {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        // A track can disappear during a quality change or subtitle translation.
        if (choice != null && subtitleTrackChoices(player.currentTracks).none { it.group == choice.group && it.index == choice.index }) return
        val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (choice != null) parameters.setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
        parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice == null && !options.speech.enabled)
        player.trackSelectionParameters = parameters.build()
        preferences.update { it.copy(subtitlesEnabled = choice != null) }
        onClose()
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(uiText(R.string.ui_subtitles_4b389d)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clickable { select(null) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!options.subtitlesEnabled, onClick = null); Spacer(Modifier.width(12.dp)); Text(uiText(R.string.ui_off_135883))
            }
            if (choices.isEmpty()) Text(uiText(R.string.ui_no_subtitles_available_yet_71da63), Modifier.padding(vertical = 12.dp))
            choices.forEach { choice ->
                Row(Modifier.fillMaxWidth().clickable { select(choice) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(options.subtitlesEnabled && choice.selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(choice.label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(uiText(R.string.ui_close_641636)) } })
}
