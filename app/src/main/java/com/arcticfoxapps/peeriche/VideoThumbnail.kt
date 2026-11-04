package com.arcticfoxapps.peeriche

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Composable internal fun VideoThumbnail(video: Video, server: String, modifier: Modifier) {
    val initial = remember(video.apiId(), video.thumbnailUrl, video.thumbnailPath, video.previewPath, server) { video.thumbnailCandidates(server) }
    var candidates by remember(initial) { mutableStateOf(initial) }
    var index by remember(initial) { mutableIntStateOf(0) }
    var failed by remember(initial) { mutableStateOf(initial.isEmpty()) }
    var resolved by remember(initial) { mutableStateOf(false) }
    LaunchedEffect(failed, index, initial) {
        if (failed && !resolved && video.apiId().isNotBlank()) {
            resolved = true
            val origin = video.sourceServer(server)
            val details = try { withTimeoutOrNull(10_000) { PeerTubeClients.video(origin, video.apiId()) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val extra = details?.thumbnailCandidates(origin).orEmpty().filterNot { it in candidates }
            if (extra.isNotEmpty()) {
                index = candidates.size
                candidates = candidates + extra
                failed = false
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (failed) Icon(Icons.Outlined.ImageNotSupported, uiText("Миниатюра недоступна", "Thumbnail unavailable"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(candidates.getOrNull(index), video.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            onSuccess = { failed = false },
            onError = { if (index < candidates.lastIndex) index++ else failed = true })
    }
}
