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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class YouSection { Menu, Channels, Favorites, History }

@Composable internal fun YouSectionHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад", "Back")) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun YouPage(server: String, onOpenSection: (YouSection) -> Unit, onSettings: () -> Unit) {
    var accountInfo by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { accountInfo = true }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, Modifier.size(36.dp), tint = colors.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiText("Войти в аккаунт", "Sign in"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(server.removePrefix("https://").removePrefix("http://"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Default.ChevronRight, null)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            YouMenuRow(Icons.Default.AccountBox, uiText("Каналы", "Channels")) { onOpenSection(YouSection.Channels) }
            YouMenuRow(Icons.Default.FavoriteBorder, uiText("Избранное", "Favorites")) { onOpenSection(YouSection.Favorites) }
            YouMenuRow(Icons.Default.History, uiText("История", "History")) { onOpenSection(YouSection.History) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            YouMenuRow(Icons.Default.Settings, uiText("Настройки", "Settings"), onSettings)
        }
    }
    if (accountInfo) ModalBottomSheet(onDismissRequest = { accountInfo = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("Вход в PeerTube", "Sign in to PeerTube"), style = MaterialTheme.typography.titleLarge)
            Text(server, color = colors.onSurfaceVariant)
            Text(uiText("Вход в аккаунт будет подключён на следующем этапе. Сейчас каналы, избранное и история хранятся на этом устройстве.",
                "Account sign-in will be connected in the next stage. Channels, favorites and history are currently stored on this device."))
            TextButton(onClick = { accountInfo = false }, modifier = Modifier.align(Alignment.End)) { Text(uiText("Понятно", "Got it")) }
        }
    }
}

@Composable private fun YouMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
