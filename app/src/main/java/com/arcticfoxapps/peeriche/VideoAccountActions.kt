package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun VideoAccountActions(video: Video, server: String, actions: @Composable RowScope.() -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { PeerTubeAccounts.get(context) }
    val accounts by manager.state.collectAsState()
    val owner = accounts.active?.takeUnless { it.needsLogin }
    if (owner == null) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        return
    }
    val library = remember(context) { AccountLibraries.get(context) }
    val scope = rememberCoroutineScope()
    val key = video.sourceServer(server) + "|" + video.apiId()
    var resolved by remember(key, owner?.id) { mutableStateOf<Video?>(null) }
    var rating by remember(key, owner?.id) { mutableStateOf("none") }
    var counts by remember(key, owner?.id) { mutableStateOf(RatingCounts(video.likes, video.dislikes)) }
    var busy by remember(key, owner?.id) { mutableStateOf(false) }
    var error by remember(key, owner?.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, owner?.id, retry) {
        val account = owner ?: return@LaunchedEffect
        busy = true; error = null
        try {
            val remote = library.resolveVideo(account.id, video, server)
            val mine = library.rating(account.id, remote.apiId())
            resolved = remote; rating = mine; counts = RatingCounts(remote.likes, remote.dislikes)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
        finally { busy = false }
    }
    fun rate(value: String) {
        val account = owner
        val target = resolved ?: return
        if (busy) return
        val next = if (rating == value) "none" else value
        val previous = rating
        busy = true; error = null
        scope.launch {
            try {
                library.rate(account.id, target.apiId(), next)
                if (manager.state.value.activeId == account.id) { counts = updatedRatingCounts(counts.likes, counts.dislikes, previous, next); rating = next }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (manager.state.value.activeId == account.id) error = accountErrorCode(failure) }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = rating == "like", onClick = { rate("like") }, enabled = !busy && resolved != null,
                label = { Text(counts.likes.toString()) }, leadingIcon = { Icon(Icons.Default.ThumbUp, uiText("Нравится", "Like"), Modifier.size(20.dp)) })
            FilterChip(selected = rating == "dislike", onClick = { rate("dislike") }, enabled = !busy && resolved != null,
                label = { Text(counts.dislikes.toString()) }, leadingIcon = { Icon(Icons.Default.ThumbDown, uiText("Не нравится", "Dislike"), Modifier.size(20.dp)) })
            actions()
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { reason ->
            Text(accountErrorText(reason), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text(uiText("Повторить", "Retry")) }
        }
    }
}
