package com.arcticfoxapps.peeriche

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

internal fun Video.publicWatchUrl(server: String): String {
    val supplied = runCatching { java.net.URI(url.orEmpty()) }.getOrNull()
    if (supplied?.scheme in listOf("https", "http") && supplied?.host != null && supplied.userInfo == null &&
        (supplied.path.orEmpty().startsWith("/videos/watch/") || supplied.path.orEmpty().startsWith("/w/")))
        return java.net.URI(supplied.scheme, supplied.authority, supplied.path, null, null).toString()
    return sourceServer(server).trimEnd('/') + "/videos/watch/" + java.net.URLEncoder.encode(apiId(), "UTF-8")
}
internal fun Video.shareText(server: String): String = name + "\n" + publicWatchUrl(server)

@Composable internal fun WatchDetails(video: Video, server: String, favorite: Boolean, subscribed: Boolean,
    onFavorite: () -> Unit, onSubscribe: () -> Unit, onChannel: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val manager = remember(context) { PeerTubeAccounts.get(context) }
    val accountState by manager.state.collectAsState()
    val serverSubscription = LocalServerSubscriptions.current && accountState.active != null
    val canSubscribe = !serverSubscription || accountState.active?.needsLogin == false
    var expanded by rememberSaveable(video.apiId()) { mutableStateOf(false) }
    var comments by rememberSaveable(video.apiId()) { mutableStateOf(false) }
    var speechSettings by remember { mutableStateOf(false) }
    var fullDescription by remember(video.apiId(), video.description) { mutableStateOf(video.description.orEmpty()) }
    var descriptionLoaded by remember(video.apiId()) { mutableStateOf(false) }
    var channel by remember(video.apiId()) { mutableStateOf<PeerTubeChannel?>(null) }
    LaunchedEffect(video.apiId(), video.channel?.name) {
        video.channel?.name?.let { handle ->
            try { channel = PeerTubeClients.server(video.sourceServer(server)).channel(handle) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    LaunchedEffect(video.apiId(), expanded) {
        if (expanded && !descriptionLoaded) try {
            val loaded = PeerTubeClients.server(video.sourceServer(server)).description(video.apiId()).description
            if (loaded.isNotBlank()) fullDescription = loaded
            descriptionLoaded = true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(translatedMetadata(video.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val date = video.publishedAt?.substringBefore('T').orEmpty()
            Text(java.text.NumberFormat.getIntegerInstance().format(video.views) + " " + uiText("просмотров", "views") + if (date.isBlank()) "" else " · $date",
                Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                uiText("Описание видео", "Video description")) }
        }
        if (expanded) LinkedWatchDescription(translatedMetadata(fullDescription).ifBlank { uiText("Описание отсутствует", "No description") })
        VideoAccountActions(video, server) {
            AssistChip(onClick = onFavorite, label = { Text(uiText(if (favorite) "В избранном" else "В избранное", if (favorite) "Saved" else "Save")) },
                leadingIcon = { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(20.dp)) })
            val shareLabel = uiText("Поделиться", "Share")
            AssistChip(onClick = {
                context.playerActivity()?.pipSupport?.suspendForExternalUi()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, video.shareText(server)); putExtra(Intent.EXTRA_SUBJECT, video.name)
                }, shareLabel))
            }, label = { Text(shareLabel) }, leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(20.dp)) })
            AssistChip(onClick = { speechSettings = true }, label = { Text(uiText("Озвучивание", "Speech")) }, leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(20.dp)) })
            AssistChip(onClick = onChannel, enabled = video.channel?.name != null, label = { Text(uiText("Видео канала", "Channel videos")) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = channel?.displayName ?: video.channel?.displayName ?: video.account?.displayName.orEmpty()
            val avatar = channel?.avatars?.firstOrNull() ?: video.channel?.avatars?.firstOrNull() ?: video.account?.avatars?.firstOrNull()
            val avatarUrl = avatar?.fileUrl ?: avatar?.path?.let { video.sourceServer(server).trimEnd('/') + it }
            Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceVariant).clickable(enabled = video.channel?.name != null, onClick = onChannel), contentAlignment = Alignment.Center) {
                Text(title.take(1).uppercase(), fontWeight = FontWeight.Bold)
                if (avatarUrl != null) AsyncImage(avatarUrl, null, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).clickable(enabled = video.channel?.name != null, onClick = onChannel)) {
                Text(title, maxLines = 2, fontWeight = FontWeight.SemiBold)
                channel?.followersCount?.let { Text(java.text.NumberFormat.getIntegerInstance().format(it) + " " + uiText("подписчиков", "followers"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
            if (canSubscribe) FilledTonalButton(onClick = onSubscribe, enabled = video.channel?.name != null, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text(uiText(if (subscribed) "Вы подписаны" else "Подписаться", if (subscribed) "Subscribed" else "Subscribe"), maxLines = 1)
            }
        }
        if (canSubscribe) Text(if (serverSubscription)
            uiText("Подписка аккаунта: ${accountState.active!!.username}@${java.net.URI(accountState.active!!.server).authority}", "Account subscription: ${accountState.active!!.username}@${java.net.URI(accountState.active!!.server).authority}")
            else uiText("Подписка на этом устройстве", "Subscription on this device"), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Surface(onClick = { comments = true }, shape = RoundedCornerShape(16.dp), color = colors.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Comment, null)
                Text(uiText("Комментарии", "Comments") + (video.comments?.let { " · $it" } ?: ""), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, null)
            }
        }
    }
    if (comments) VideoCommentsSheet(video, server) { comments = false }
    if (speechSettings) SpeechSettingsDialog { speechSettings = false }
}

@Composable internal fun NextVideoRow(video: Video, server: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VideoThumbnail(video, server, Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(translatedMetadata(video.name), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(video.channel?.displayName ?: video.account?.displayName.orEmpty(), Modifier.padding(top = 6.dp), maxLines = 1,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun flattenComments(tree: CommentTree): List<VideoComment> {
    val queue = java.util.ArrayDeque<CommentTree>(); queue.add(tree)
    val result = mutableListOf<VideoComment>()
    var visited = 0
    while (queue.isNotEmpty() && visited++ < 1000) {
        val item = queue.removeFirst()
        item.comment?.let { result += it }
        item.children.take((1000 - visited - queue.size).coerceAtLeast(0)).asReversed().forEach { queue.addFirst(it) }
    }
    return result.distinctBy { it.id }
}

