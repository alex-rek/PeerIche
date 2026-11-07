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

internal enum class YouSection { Menu, Channels, Favorites, History, Videos }

@Composable internal fun YouSectionHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад", "Back")) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun YouPage(server: String, onOpenSection: (YouSection) -> Unit, onSettings: () -> Unit, onAccountServer: (String) -> Unit) {
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
                    Text(active?.displayName ?: uiText("Войти в аккаунт", "Sign in"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (active != null) "${active.username}@${java.net.URI(active.server).authority}" else server.removePrefix("https://").removePrefix("http://"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (active?.needsLogin == true) Icon(Icons.Default.Login, uiText("Войдите снова", "Sign in again"))
                else Icon(Icons.Default.ChevronRight, null)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            YouMenuRow(Icons.Default.AccountBox, uiText("Каналы", "Channels")) { onOpenSection(YouSection.Channels) }
            if (active != null && !active.needsLogin) YouMenuRow(Icons.Default.VideoLibrary, uiText("Ваши видео", "Your videos")) { onOpenSection(YouSection.Videos) }
            YouMenuRow(Icons.Default.FavoriteBorder, uiText("Избранное", "Favorites")) { onOpenSection(YouSection.Favorites) }
            YouMenuRow(Icons.Default.History, uiText("История", "History")) { onOpenSection(YouSection.History) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            YouMenuRow(Icons.Default.Settings, uiText("Настройки", "Settings"), onSettings)
        }
    }
    if (accountInfo) AccountScreen(server, manager, onAccountServer) { accountInfo = false }

}

@Composable private fun YouMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
