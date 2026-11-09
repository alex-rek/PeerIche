package com.arcticfoxapps.peeriche

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val LocalServerSubscriptions = staticCompositionLocalOf { true }

@Composable internal fun SubscriptionSourceMenu(account: AccountSummary?, device: Boolean, onDevice: (Boolean) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(enabled = account != null, onClick = { open = true }) {
                Text(if (account != null && !device) "${account.username}@${java.net.URI(account.server).authority}" else uiText(R.string.ui_on_this_device_8d14b3),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (account != null) Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                if (account != null) DropdownMenuItem(text = { Text("${account.username}@${java.net.URI(account.server).authority}") }, onClick = { open = false; onDevice(false) })
                DropdownMenuItem(text = { Text(uiText(R.string.ui_on_this_device_8d14b3)) }, onClick = { open = false; onDevice(true) })
            }
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, uiText(R.string.ui_refresh_subscriptions_343f9b)) }
    }
}

@Composable internal fun AccountChannelsPage(account: AccountSummary?, snapshot: AccountLibrarySnapshot?, local: List<SavedVideo>, server: String,
    onOpen: (PeerTubeChannel) -> Unit, onBrowse: (String) -> Unit, onRefresh: () -> Unit, onUnsubscribe: (PeerTubeChannel) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item { TextButton(onClick = { onBrowse(server) }) { Text(uiText(R.string.ui_server_channels_639d6c) + " · " + java.net.URI(server).authority) } }
        if (account != null) {
            item { Text("${account.username}@${java.net.URI(account.server).authority}", Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (snapshot?.busy == true) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (snapshot?.error != null || account.needsLogin) item {
                Text(accountErrorText(if (account.needsLogin) "session_expired" else snapshot?.error.orEmpty()), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh) { Text(uiText(R.string.ui_retry_e8368b)) }
            }
            item { Text(uiText(R.string.ui_my_channels_350cbf), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium) }
            items(snapshot?.own.orEmpty(), key = { "own:" + it.subscriptionHandle(account.server) }) { channel -> ChannelAccountRow(channel, account.server, onOpen) }
            if (snapshot?.loaded == true && snapshot.own.isEmpty()) item { Text(uiText(R.string.ui_this_account_has_no_channels_90d173), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text(uiText(R.string.ui_account_subscriptions_5aa21c), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium) }
            items(snapshot?.subscriptions.orEmpty(), key = { "sub:" + it.subscriptionHandle(account.server) }) { channel ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ChannelAccountRow(channel, account.server, onOpen) }
                    IconButton(enabled = !account.needsLogin, onClick = { onUnsubscribe(channel) }) { Icon(Icons.Default.NotificationsOff, uiText(R.string.ui_unsubscribe_c612d7)) }
                }
            }
            if (snapshot?.loaded == true && snapshot.subscriptions.isEmpty()) item { Text(uiText(R.string.ui_no_subscriptions_on_the_server_yet_d1abd5), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        }
        item { Text(uiText(R.string.ui_on_this_device_8d14b3), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium) }
        items(local, key = { "local:" + it.identity }) { row ->
            val channel = PeerTubeChannel(row.title, row.channelName.orEmpty(), java.net.URI(row.server).authority)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ChannelAccountRow(channel, row.server, onOpen) }
                TextButton(onClick = { onBrowse(row.server) }) { Text(java.net.URI(row.server).authority.orEmpty()) }
            }
        }
        if (local.isEmpty()) item { Text(uiText(R.string.ui_no_local_subscriptions_yet_aa8224), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun ChannelAccountRow(channel: PeerTubeChannel, fallback: String, onOpen: (PeerTubeChannel) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(channel) }.padding(vertical = 10.dp)) {
        Text(channel.displayName.ifBlank { channel.name })
        Text(channel.subscriptionHandle(fallback), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun PeerTubeChannel.subscriptionRow(fallback: String): SavedVideo {
    val handle = subscriptionHandle(fallback)
    val name = handle.substringBeforeLast('@'); val host = handle.substringAfterLast('@')
    val origin = authServerOrigin("https://$host")
    return SavedVideo("${origin.lowercase()}|${name.lowercase()}", "subscription", displayName.ifBlank { name }, null, origin, null, name, 0, 0)
}
