package com.arcticfoxapps.peeriche

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
@Composable internal fun BackgroundVideoPlayer(video: Video, server: String, modifier: Modifier) {
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
    LaunchedEffect(player, video.apiId(), video.playableUrl(), captionRetry) {
        if (player == null) return@LaunchedEffect
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
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    AndroidView(factory = { PlayerView(it).apply { useController = true; setShowSubtitleButton(true) } },
        update = { it.player = player }, onRelease = { it.player = null }, modifier = modifier)
    if (player == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (captionsFailed) TextButton(onClick = { captionRetry++ }) { Text(stringResource(R.string.retry_captions)) }
    if (failed) TextButton(onClick = {
        failed = false
        if (player == null) connectionAttempt++ else { player.prepare(); player.play() }
    }) {
        Text(stringResource(R.string.retry))
    }
}
