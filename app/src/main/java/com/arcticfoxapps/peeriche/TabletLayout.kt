package com.arcticfoxapps.peeriche

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.compose.AsyncImage

internal fun tabletColumns(widthDp: Int): Int = if (widthDp >= 1100) 3 else 2

@Composable internal fun TabletNavigation(page: String, collapsed: Boolean, onNavigate: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.width(if (collapsed) 76.dp else 184.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Home" to R.string.home, "Search" to R.string.search, "Subscriptions" to R.string.subscriptions, "Library" to R.string.library).forEach { (destination, label) ->
                val icon = when (destination) {
                    "Home" -> R.drawable.ic_nav_home
                    "Search" -> R.drawable.ic_nav_search
                    "Subscriptions" -> R.drawable.ic_nav_subscriptions
                    else -> R.drawable.ic_nav_library
                }
                Surface(onClick = { onNavigate(destination) }, shape = MaterialTheme.shapes.medium,
                    color = if (page == destination || (destination == "Home" && page in listOf("Server", "Live"))) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (page == destination || (destination == "Home" && page in listOf("Server", "Live"))) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
                        if (!collapsed) Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TabletVideoScreen(video: Video, server: String, loading: Boolean, error: String?,
    recommendations: List<Video>, fullscreen: Boolean, favorite: Boolean, subscribed: Boolean,
    onBrowse: () -> Unit, onFullscreen: () -> Unit, onOpen: (Video) -> Unit,
    onFavorite: () -> Unit, onSubscribe: () -> Unit, onChannel: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<Player?>(null) }
    var noSubtitles by remember { mutableStateOf(false) }
    var subtitleDialog by remember { mutableStateOf<android.app.Dialog?>(null) }
    DisposableEffect(Unit) { onDispose { subtitleDialog?.dismiss() } }
    if (noSubtitles) AlertDialog(onDismissRequest = { noSubtitles = false },
        title = { Text(stringResource(R.string.subtitles)) }, text = { Text(stringResource(R.string.no_subtitles)) },
        confirmButton = { TextButton(onClick = { noSubtitles = false }) { Text(stringResource(android.R.string.ok)) } })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 760.dp
        val colors = MaterialTheme.colorScheme
        Row(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (video.playableUrl() != null) BackgroundVideoPlayer(video, server,
                    if (fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    fullscreen = fullscreen, onFullscreenToggle = onFullscreen, onController = { controller = it }, onBack = { if (fullscreen) onFullscreen() else onBrowse() })
                else {
                    Text(error ?: stringResource(R.string.video_unavailable), color = colors.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                if (!fullscreen) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WatchDetails(video, server, favorite, subscribed, onFavorite, onSubscribe, onChannel)
                    if (!sideBySide) {
                        Text(stringResource(R.string.next_videos), style = MaterialTheme.typography.titleMedium)
                        recommendations.forEach { NextVideoRow(it, server) { onOpen(it) } }
                    }
                }
            }
            if (sideBySide && !fullscreen) Column(Modifier.widthIn(min = 240.dp, max = 340.dp).weight(.48f).fillMaxHeight()) {
                Text(stringResource(R.string.next_videos), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recommendations, key = { it.sourceServer(server) + "|" + it.apiId() }) { item -> NextVideoRow(item, server) { onOpen(item) } }
                }
            }
        }
    }
}

@Composable private fun RecommendationRow(video: Video, server: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VideoThumbnail(video, server, Modifier.width(112.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f)) {
            Text(translatedMetadata(video.name), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(video.channel?.displayName ?: video.account?.displayName.orEmpty(), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
