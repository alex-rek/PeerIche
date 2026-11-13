package com.arcticfoxapps.peeriche

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val BLOCKED_CHANNEL = "blocked_channel"
internal val LocalVideoChannelNavigation = staticCompositionLocalOf<(Video) -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VideoContextMenu(video: Video, server: String, favorite: Boolean, subscribed: Boolean,
    onFavorite: () -> Unit, onSubscribe: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = remember { PeerIcheDatabase.get(context).library() }
    val scope = rememberCoroutineScope()
    val navigateChannel = LocalVideoChannelNavigation.current
    var selected by remember { mutableStateOf(video) }
    var picker by remember { mutableStateOf<Boolean?>(null) }
    var download by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val hidden by remember { library.observe(HIDDEN_CHANNEL) }.collectAsState(emptyList())
    val blocked by remember { library.observe(BLOCKED_CHANNEL) }.collectAsState(emptyList())
    val pins by remember { library.observe("pinned_channel") }.collectAsState(emptyList())
    val later by remember { library.observe(WATCH_LATER) }.collectAsState(emptyList())
    val hiddenVideos by remember { library.observe("hidden_video") }.collectAsState(emptyList())
    val identity = selected.hiddenChannelIdentity(server)
    val videoIdentity = selected.sourceServer(server) + "|" + selected.apiId()
    val accounts by PeerTubeAccounts.get(context).state.collectAsState()
    val subscribeEnabled = !LocalServerSubscriptions.current || accounts.active?.needsLogin == false
    LaunchedEffect(video) {
        if (video.channel?.name.isNullOrBlank()) {
            try { selected = downloadVideoDetails(context, video, server) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    fun toggle(kind: String, rows: List<SavedVideo>, channel: Boolean) {
        scope.launch {
            try {
                val row = if (channel) selected.hiddenChannelRow(server, selected.channel?.displayName.orEmpty())?.copy(kind = kind)
                    else selected.libraryRow(server, kind)
                if (row != null) {
                    if (rows.any { it.identity == row.identity }) library.delete(row.identity, kind) else library.put(row)
                    onClose()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(translatedMetadata(video.name), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 2, fontWeight = FontWeight.SemiBold)
            VideoMenuRow(uiText(if (later.any { it.identity == videoIdentity }) R.string.watch_later_remove else R.string.watch_later_add), onClick = { toggle(WATCH_LATER, later, false) })
            VideoMenuRow(uiText(R.string.playlist_add_remove), onClick = { picker = false })
            VideoMenuRow(uiText(R.string.playlist_new), onClick = { picker = true })
            VideoMenuRow(uiText(if (favorite) R.string.favorite_remove else R.string.add_favorite), onClick = { onFavorite(); onClose() })
            VideoMenuRow(uiText(R.string.update_download), onClick = { download = true })
            VideoMenuRow(uiText(R.string.ui_share_0e2e41), onClick = {
                context.playerActivity()?.pipSupport?.suspendForExternalUi()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, video.shareText(server)), null))
                onClose()
            })
            VideoMenuRow(uiText(if (hiddenVideos.any { it.identity == videoIdentity }) R.string.menu_recommend_video else R.string.menu_hide_video), onClick = { toggle("hidden_video", hiddenVideos, false) })
            if (identity != null) VideoMenuRow(uiText(if (hidden.any { it.identity == identity }) R.string.channel_show else R.string.menu_hide_channel), onClick = { toggle(HIDDEN_CHANNEL, hidden, true) })
            VideoMenuRow(uiText(R.string.menu_play_next), onClick = {
                PlaybackQueue.next(selected.copy(host = selected.sourceServer(server)))
                onClose()
            })
            if (identity != null) {
                VideoMenuRow(uiText(if (blocked.any { it.identity == identity }) R.string.menu_unblock_channel else R.string.menu_block_channel), onClick = { toggle(BLOCKED_CHANNEL, blocked, true) })
                VideoMenuRow(uiText(R.string.menu_go_channel), onClick = { navigateChannel(selected); onClose() })
                if (subscribeEnabled) VideoMenuRow(uiText(if (subscribed) R.string.ui_subscribed_b31c7d else R.string.ui_subscribe_7a95fd), onClick = { onSubscribe(); onClose() })
                VideoMenuRow(uiText(if (pins.any { it.identity == identity }) R.string.menu_unpin_channel else R.string.menu_pin_channel), onClick = { toggle("pinned_channel", pins, true) })
            }
            if (failed) Text(uiText(R.string.library_action_failed), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        }
    }
    picker?.let { create -> PlaylistPicker(selected, server, create) { picker = null; onClose() } }
    if (download) VideoDownloadDialog(selected, server) { download = false; onClose() }
}

@Composable private fun VideoMenuRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
