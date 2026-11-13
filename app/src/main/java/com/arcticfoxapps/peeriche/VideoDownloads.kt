package com.arcticfoxapps.peeriche

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class VideoDownloadOption(val quality: Int, val url: String, val extension: String, val size: Long)

internal fun videoDownloadOptions(video: Video): List<VideoDownloadOption> {
    if (!video.downloadEnabled || video.isLive) return emptyList()
    return (video.files.orEmpty() + video.streamingPlaylists.orEmpty().flatMap { it.files.orEmpty() }).mapNotNull { file ->
        if (file.hasVideo == false) return@mapNotNull null
        val raw = if (video.playbackAccountId == null) file.fileDownloadUrl ?: file.fileUrl else file.fileUrl
        val url = raw?.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() } ?: return@mapNotNull null
        val extension = url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase()
        if (extension !in listOf("mp4", "webm", "m4v", "ogv", "mkv")) return@mapNotNull null
        VideoDownloadOption(file.resolution?.id ?: 0, url.toString(), extension!!, file.size)
    }.distinctBy { it.quality to it.extension }.sortedByDescending { it.quality }
}

internal suspend fun downloadVideoDetails(context: Context, video: Video, server: String): Video {
    val accounts = PeerTubeAccounts.get(context)
    accounts.initialize()
    val account = video.playbackAccountId
    if (account != null) return CreatorRepository(accounts).playbackVideo(account, video.apiId())
    return try { PeerTubeClients.video(video.sourceServer(server), video.apiId()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        val owner = accounts.state.value.active?.takeIf { !it.needsLogin && it.server == video.sourceServer(server) } ?: throw failure
        CreatorRepository(accounts).playbackVideo(owner.id, video.apiId())
    }
}

@Composable internal fun VideoDownloadButton(video: Video, server: String) {
    var open by remember(video.apiId()) { mutableStateOf(false) }
    AssistChip(onClick = { open = true }, label = { Text(uiText(R.string.update_download)) }, leadingIcon = { Icon(Icons.Default.Download, null, Modifier.size(20.dp)) })
    if (open) VideoDownloadDialog(video, server) { open = false }
}

@Composable internal fun VideoDownloadDialog(video: Video, server: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var details by remember { mutableStateOf<Video?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VideoDownloadOption?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(video, server, retry) {
        failed = false
        try { details = downloadVideoDetails(context, video, server) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        if (uri != null) {
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val option = selected ?: error("No selected format")
                val ready = details ?: error("No video details")
                ContextCompat.startForegroundService(context, Intent(context, VideoDownloadService::class.java)
                    .putExtra("server", ready.sourceServer(server)).putExtra("video", ready.apiId())
                    .putExtra("account", ready.playbackAccountId).putExtra("quality", option.quality)
                    .putExtra("extension", option.extension).setData(uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                onClose()
            } catch (_: Exception) { failed = true }
        }
    }
    fun chooseDestination() {
        val option = selected ?: return
        context.playerActivity()?.pipSupport?.suspendForExternalUi()
        val name = video.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(100).ifBlank { video.apiId() }
        picker.launch("$name-${option.quality}p.${option.extension}")
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { chooseDestination() }
    AlertDialog(onDismissRequest = onClose, title = { Text(uiText(R.string.update_download)) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(video.name, maxLines = 2)
            val ready = details
            if (ready == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) {
                Text(uiText(R.string.video_download_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(uiText(R.string.ui_retry_e8368b)) }
            }
            if (ready != null) {
                val options = videoDownloadOptions(ready)
                if (options.isEmpty()) Text(uiText(R.string.video_download_unavailable))
                options.forEach { option ->
                    TextButton(onClick = {
                        selected = option
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            notification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else chooseDestination()
                    }) { Text("${option.quality}p · ${option.extension.uppercase()}" + if (option.size > 0) " · ${formatBytes(option.size)}" else "") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(uiText(R.string.ui_close_641636)) } })
}
