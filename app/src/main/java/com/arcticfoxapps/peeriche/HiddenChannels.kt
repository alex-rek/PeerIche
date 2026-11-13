package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val HIDDEN_CHANNEL = "hidden_channel"

/** Reuse the subscription identity: a channel handle together with its owning server. */
internal fun Video.hiddenChannelIdentity(server: String): String? =
    runCatching { accountChannelHandle(server); subscriptionIdentity(server) }.getOrNull()

internal fun Video.hiddenChannelRow(server: String, title: String): SavedVideo? {
    val identity = hiddenChannelIdentity(server) ?: return null
    return SavedVideo(identity, HIDDEN_CHANNEL, title.ifBlank { channel?.name.orEmpty() }, null,
        identity.substringBefore('|'), null, identity.substringAfter('|'), 0, System.currentTimeMillis())
}

internal fun List<Video>.withoutHiddenChannels(hidden: Set<String>, server: String): List<Video> =
    if (hidden.isEmpty()) this else filterNot { it.hiddenChannelIdentity(server) in hidden }

@Composable internal fun HideChannelButton(video: Video, server: String, title: String) {
    val context = LocalContext.current
    val library = remember(context) { PeerIcheDatabase.get(context).library() }
    val rows by remember(library) { library.observe(HIDDEN_CHANNEL) }.collectAsState(initial = null)
    val identity = video.hiddenChannelIdentity(server)
    val hidden = rows.orEmpty().any { it.identity == identity }
    var busy by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        AssistChip(enabled = rows != null && identity != null && !busy, onClick = {
            busy = true; error = false
            scope.launch {
                try {
                    if (hidden) library.delete(identity!!, HIDDEN_CHANNEL)
                    else video.hiddenChannelRow(server, title)?.let { library.put(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }, label = { Text(uiText(if (hidden) R.string.channel_show else R.string.channel_hide), maxLines = 1) },
            leadingIcon = { Icon(if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, Modifier.size(20.dp)) })
        if (error) Text(uiText(R.string.hidden_channels_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun HiddenChannelsSettings() {
    val context = LocalContext.current
    val library = remember(context) { PeerIcheDatabase.get(context).library() }
    val rows by remember(library) { library.observe(HIDDEN_CHANNEL) }.collectAsState(initial = emptyList())
    val blocked by remember(library) { library.observe(BLOCKED_CHANNEL) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text(uiText(R.string.hidden_channels_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        item { Text(uiText(R.string.channel_block_local_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        if (rows.isEmpty() && blocked.isEmpty()) item { Text(uiText(R.string.hidden_channels_empty), Modifier.padding(24.dp)) }
        if (error) item { Text(uiText(R.string.hidden_channels_error), Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) }
        items(rows + blocked, key = { it.kind + "|" + it.identity }) { channel ->
            SettingsRow(channel.title, "${channel.channelName}@${channel.server.removePrefix("https://")}") {
                TextButton(onClick = {
                    scope.launch {
                        error = false
                        try { library.delete(channel.identity, channel.kind) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                    }
                }) { Text(uiText(if (channel.kind == BLOCKED_CHANNEL) R.string.menu_unblock_channel else R.string.channel_show)) }
            }
        }
    }
}
