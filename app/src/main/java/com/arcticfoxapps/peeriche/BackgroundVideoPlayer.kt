package com.arcticfoxapps.peeriche

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class CaptionPage(val data: List<VideoCaption> = emptyList())
data class CaptionLanguage(val id: String = "", val label: String = "")
data class VideoCaption(val language: CaptionLanguage? = null, val captionPath: String? = null, val fileUrl: String? = null) {
    fun resolvedUrl(server: String): String? = runCatching {
        java.net.URI(server.trimEnd('/') + "/").resolve(fileUrl?.takeIf(String::isNotBlank) ?: captionPath ?: return null)
            .takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }?.toString()
    }.getOrNull()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun BackgroundVideoPlayer(video: Video, server: String, modifier: Modifier,
    compact: Boolean = false, onExpand: () -> Unit = {}, onClose: () -> Unit = {},
    fullscreen: Boolean = false, onFullscreenToggle: () -> Unit = {}, onController: (Player?) -> Unit = {}) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var failed by remember(video.apiId()) { mutableStateOf(false) }
    var captionsFailed by remember(video.apiId()) { mutableStateOf(false) }
    var captionRetry by remember { mutableIntStateOf(0) }
    var connectionAttempt by remember { mutableIntStateOf(0) }
    DisposableEffect(context, connectionAttempt) {
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({ if (!disposed) try { controller = future.get() } catch (_: Exception) { failed = true } },
            ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; controller = null; MediaController.releaseFuture(future) }
    }
    val player = controller
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player, compact) {
        while (compact && player != null) {
            progress = if (player.duration > 0) (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f) else 0f
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(player, video.apiId(), video.playableUrl(), captionRetry) {
        if (player == null) return@LaunchedEffect
        if (video.playableUrl() == null) return@LaunchedEffect
        val identity = video.sourceServer(server) + "|" + video.apiId()
        if (player.currentMediaItem?.mediaId == identity && captionRetry == 0) return@LaunchedEffect
        val previousPosition = if (player.currentMediaItem?.mediaId == identity) player.currentPosition else 0L
        val wasPlaying = player.currentMediaItem?.mediaId != identity || player.playWhenReady
        val captions = try {
            captionsFailed = false
            withTimeoutOrNull(8000) { PeerTubeClients.server(video.sourceServer(server)).captions(video.apiId()).data }
                ?: run { captionsFailed = true; emptyList() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { captionsFailed = true; emptyList() }
        val tracks = captions.mapNotNull { caption -> caption.resolvedUrl(video.sourceServer(server))?.let { url ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(caption.language?.id).setLabel(caption.language?.label).build()
        } }
        val item = MediaItem.Builder().setMediaId(identity).setUri(video.playableUrl())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(video.name)
                .setArtist(video.channel?.displayName ?: video.account?.displayName).build())
            .setSubtitleConfigurations(tracks).build()
        player.setMediaItem(item, previousPosition)
        player.prepare()
        player.playWhenReady = wasPlaying
    }
    DisposableEffect(player) {
        onController(player)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        playing = player?.isPlaying == true
        player?.addListener(listener)
        onDispose { player?.removeListener(listener); onController(null) }
    }
    if (compact) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
        Column {
        Row(modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            coil.compose.AsyncImage(video.thumbnailCandidates(server).firstOrNull(), null,
                Modifier.width(80.dp).height(48.dp).clickable(onClick = onExpand), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(horizontal = 16.dp)) {
                Text(video.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(video.channel?.displayName ?: video.account?.displayName.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = player != null, onClick = { if (player?.playWhenReady == true) player.pause() else player?.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.pause_playback else R.string.resume_playback))
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.stop_playback)) }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    } else Box(modifier) {
        AndroidView(factory = { PlayerView(it).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            useController = true
            setShowSubtitleButton(true)
            // Leave space for the always-visible Compose fullscreen control.
            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_bottom_bar)?.let { bar ->
                bar.setPadding(bar.paddingLeft, bar.paddingTop, (48 * resources.displayMetrics.density).toInt(), bar.paddingBottom)
            }
        } }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        IconButton(onClick = onFullscreenToggle,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = .55f), CircleShape)) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                stringResource(if (fullscreen) R.string.exit_fullscreen else R.string.enter_fullscreen), tint = Color.White)
        }
    }
    if (player == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (captionsFailed) TextButton(onClick = { captionRetry++ }) { Text(stringResource(R.string.retry_captions)) }
    if (failed) TextButton(onClick = {
        failed = false
        if (player == null) connectionAttempt++ else { player.prepare(); player.play() }
    }) {
        Text(stringResource(R.string.retry))
    }
}
