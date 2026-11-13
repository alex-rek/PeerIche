package com.arcticfoxapps.peeriche

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class YouSection { Menu, Channels, Favorites, History, Videos, Servers, Playlists }

@Composable internal fun YouSectionHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_02b74f)) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun YouPage(server: String, onOpenSection: (YouSection) -> Unit, onSettings: () -> Unit, onAccountServer: (String) -> Unit, onPinnedChannel: (SavedVideo) -> Unit) {
    var accountInfo by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val manager = remember(context) { PeerTubeAccounts.get(context) }
    val accounts by manager.state.collectAsState()
    val active = accounts.active
    LaunchedEffect(Unit) {
        try {
            manager.initialize()
            manager.state.value.active?.takeUnless { it.needsLogin }?.let { manager.refreshProfile(it.id) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Account screen provides retry and session details. */ }
    }
    val pinned by remember(context) { PeerIcheDatabase.get(context).library().observe("pinned_channel") }.collectAsState(emptyList())
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { accountInfo = true }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, Modifier.size(36.dp), tint = colors.onSurfaceVariant)
                    active?.avatar?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(active?.displayName ?: uiText(R.string.ui_sign_in_fe4740), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (active != null) "${active.username}@${java.net.URI(active.server).authority}" else server.removePrefix("https://").removePrefix("http://"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (active?.needsLogin == true) Icon(Icons.Default.Login, uiText(R.string.ui_sign_in_again_770ace))
                else Icon(interfaceForwardChevron(), null)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            YouMenuRow(Icons.Default.Dns, uiText(R.string.ui_servers_d9002c)) { onOpenSection(YouSection.Servers) }
            YouMenuRow(Icons.Default.PlaylistPlay, uiText(R.string.library_playlists)) { onOpenSection(YouSection.Playlists) }
            YouMenuRow(Icons.Default.AccountBox, uiText(R.string.ui_channels_a38998)) { onOpenSection(YouSection.Channels) }
            if (active != null && !active.needsLogin) YouMenuRow(Icons.Default.VideoLibrary, uiText(R.string.ui_your_videos_90d416)) { onOpenSection(YouSection.Videos) }
            YouMenuRow(Icons.Default.FavoriteBorder, uiText(R.string.ui_favorites_d4c059)) { onOpenSection(YouSection.Favorites) }
            if (pinned.isNotEmpty()) {
                Text(uiText(R.string.channels_pinned), Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.SemiBold)
                pinned.forEach { row -> YouMenuRow(Icons.Default.PushPin, row.title) { onPinnedChannel(row) } }
            }
            YouMenuRow(Icons.Default.History, uiText(R.string.ui_history_4d3d0c)) { onOpenSection(YouSection.History) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            YouMenuRow(Icons.Default.Settings, uiText(R.string.ui_settings_2bca56), onSettings)
        }
    }
    if (accountInfo) AccountScreen(server, manager, onAccountServer) { accountInfo = false }

}

@Composable private fun YouMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
