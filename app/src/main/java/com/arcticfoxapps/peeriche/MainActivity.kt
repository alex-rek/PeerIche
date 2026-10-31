package com.arcticfoxapps.peeriche

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView

private val Ink = Color(0xFF101113)
private val Panel = Color(0xFF1B1C20)
private val Orange = Color(0xFFFF7139)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 19)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 19)
        setContent { PeerIcheApp() }
    }
}

@Composable private fun PeerIcheApp() {
    val context = LocalContext.current
    val database = remember { PeerIcheDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val serverList by context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[stringPreferencesKey("servers")] ?: "https://peertube.tv" }.collectAsState(initial = "https://peertube.tv")
    val activeServerSetting by context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[stringPreferencesKey("activeServer")].orEmpty() }.collectAsState(initial = "")
    var page by remember { mutableStateOf("Home") }
    val availableServers = remember(serverList) { serverList.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.distinct() }
    val server = remember(serverList, activeServerSetting) { activeServerSetting.takeIf { it in availableServers } ?: availableServers.firstOrNull() ?: "https://peertube.tv" }
    var videos by remember { mutableStateOf(emptyList<Video>()) }
    var query by remember { mutableStateOf("") }
    var resultPage by remember { mutableIntStateOf(0) }
    var hasMoreResults by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedVideo by remember { mutableStateOf<Video?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var discoveringServers by remember { mutableStateOf(false) }
    var chooseServerOpen by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf(serverList) }
    var libraryTab by remember { mutableStateOf("Favorites") }
    val favoriteRows by database.library().observe("favorite").collectAsState(initial = emptyList())
    val historyRows by database.library().observe("history").collectAsState(initial = emptyList())
    val favorites = remember(favoriteRows) { favoriteRows.map { it.identity }.toSet() }

    fun loadHome() {
        busy = true; error = null
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            runCatching { PeerTubeClients.home(server) }
                .onSuccess { videos = it }
                .onFailure { error = it.message ?: context.getString(R.string.search_failed) }
            busy = false
        }
    }
    fun searchNow(pageToLoad: Int = 0) {
        if (query.isBlank()) return
        busy = true; error = null
        val sources = serverList.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.distinct().ifEmpty { listOf(server) }
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            runCatching { PeerTubeClients.searchPage(query.trim(), sources, pageToLoad) }
                .onSuccess { response ->
                    videos = if (pageToLoad == 0) response.videos else deduplicateVideos(videos + response.videos, server)
                    resultPage = pageToLoad
                    hasMoreResults = response.hasMore
                    if (videos.isEmpty()) error = context.getString(R.string.no_results)
                }
                .onFailure { error = it.message ?: context.getString(R.string.search_failed) }
            busy = false
        }
    }
    LaunchedEffect(Unit) { loadHome() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Orange, secondary = Orange, background = Ink, surface = Panel, onSurface = Color(0xFFF1F1F3))) {
        Scaffold(containerColor = Ink, bottomBar = {
            if (selectedVideo == null) NavigationBar(containerColor = Panel) {
                listOf("Home", "Search", "Subscriptions", "Library").forEach { destination ->
                    val (icon, labelId) = when (destination) {
                        "Home" -> Icons.Default.Home to R.string.home
                        "Search" -> Icons.Default.Search to R.string.search
                        "Subscriptions" -> Icons.Default.Subscriptions to R.string.subscriptions
                        else -> Icons.Default.LibraryBooks to R.string.library
                    }
                    NavigationBarItem(selected = page == destination, onClick = {
                        page = destination
                        if (destination == "Home") loadHome()
                    }, icon = { Icon(icon, null) }, label = { Text(stringResource(labelId)) })
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if (selectedVideo == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 25.sp, color = Orange, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { chooseServerOpen = true }) { Text(server.removePrefix("https://"), color = Color.White, maxLines = 1) }
                            DropdownMenu(expanded = chooseServerOpen, onDismissRequest = { chooseServerOpen = false }) {
                                availableServers.forEach { host -> DropdownMenuItem(text = { Text(host.removePrefix("https://")) }, onClick = {
                                    scope.launch { saveServerList(context, serverList, host) }
                                    chooseServerOpen = false
                                }) }
                                DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { chooseServerOpen = false; serverDraft = serverList; settingsOpen = true })
                            }
                        }
                        IconButton(onClick = { serverDraft = serverList; settingsOpen = true }) { Icon(Icons.Default.Tune, stringResource(R.string.settings)) }
                    }
                    if (page == "Search") {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                            label = { Text(stringResource(R.string.search_hint)) }, trailingIcon = { TextButton(onClick = { searchNow(0) }) { Text(stringResource(R.string.go)) } })
                    }
                    when (page) {
                        "Home" -> Text(stringResource(R.string.discover_videos), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        "Subscriptions" -> Text(stringResource(R.string.followed_channels), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        "Library" -> Row(Modifier.padding(horizontal = 12.dp)) {
                            listOf("Favorites" to R.string.favorites, "History" to R.string.history).forEach { (label, res) -> FilterChip(selected = libraryTab == label, onClick = { libraryTab = label }, label = { Text(stringResource(res)) }, modifier = Modifier.padding(end = 8.dp)) }
                        }
                    }
                    val visibleVideos = if (page == "Library") (if (libraryTab == "Favorites") favoriteRows else historyRows).map(SavedVideo::toVideo) else videos
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Orange)
                    error?.let { Text(it, Modifier.padding(18.dp), color = Color(0xFFFFB49B)) }
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 230.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(visibleVideos, key = { it.uuid.ifBlank { it.url ?: it.name } }) { video ->
                            VideoCard(video, server, video.uuid in favorites, onOpen = {
                                selectedVideo = video
                                scope.launch { database.library().put(video.toSavedVideo(server, "history")) }
                            }, onFavorite = {
                                scope.launch {
                                    if (video.uuid in favorites) database.library().delete(video.uuid, "favorite")
                                    else database.library().put(video.toSavedVideo(server, "favorite"))
                                }
                            })
                        }
                        if (page == "Search" && hasMoreResults) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { searchNow(resultPage + 1) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (busy) R.string.loading_more else R.string.load_more))
                            }
                        }
                    }
                } else {
                    val video = selectedVideo!!
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedVideo = null }) { Text("← ${stringResource(R.string.back)}") }
                        Text(video.name, color = Color.White, maxLines = 2, modifier = Modifier.padding(start = 12.dp))
                    }
                    val playUrl = video.playableUrl()
                    if (playUrl != null) VideoPlayer(playUrl) else Text(stringResource(R.string.no_playable_stream), Modifier.padding(20.dp), color = Color.White)
                    Text(video.name, Modifier.padding(16.dp), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(video.description.orEmpty(), Modifier.padding(horizontal = 16.dp), color = Color.LightGray)
                    Text(stringResource(R.string.video_meta, video.views, video.channel?.displayName ?: video.account?.displayName.orEmpty()), Modifier.padding(16.dp), color = Color.Gray)
                }
            }
        }
        if (settingsOpen) AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text(stringResource(R.string.servers)) },
            text = { Column {
                Text(stringResource(R.string.servers_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(serverDraft, { serverDraft = it }, label = { Text(stringResource(R.string.server_list)) }, minLines = 3, maxLines = 8)
                TextButton(enabled = !discoveringServers, onClick = {
                    discoveringServers = true
                    scope.launch {
                        val discovered = runCatching { PeerTubeClients.discoverInstances() }.getOrDefault(emptyList())
                        val current = serverDraft.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.toMutableList()
                        discovered.map { "https://${it.host}" }.filter { runCatching { normalizeServer(it) }.isSuccess }.forEach { if (it !in current) current += it }
                        serverDraft = current.joinToString("\n")
                        discoveringServers = false
                    }
                }) { Text(stringResource(if (discoveringServers) R.string.discovering_servers else R.string.discover_servers)) }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.about_independent), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } },
            confirmButton = { TextButton(onClick = {
                val normalized = serverDraft.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.distinct()
                if (normalized.isNotEmpty()) {
                    val active = server.takeIf { it in normalized } ?: normalized.first()
                    scope.launch { saveServerList(context, normalized.joinToString("\n"), active) }
                }
                settingsOpen = false
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun Video.toSavedVideo(fallbackServer: String, kind: String) = SavedVideo(
    identity = uuid.ifBlank { url ?: name }, kind = kind, title = name,
    videoUrl = playableUrl() ?: url, server = sourceServer(fallbackServer),
    thumbnailUrl = thumbnailUrl ?: thumbnailPath?.let { runCatching { java.net.URI(sourceServer(fallbackServer).trimEnd('/') + "/").resolve(it).toString() }.getOrNull() },
    channelName = channel?.displayName ?: account?.displayName, views = views, updatedAt = System.currentTimeMillis()
)

@Composable private fun VideoCard(video: Video, fallbackServer: String, favorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onOpen)) {
        val imageServer = video.sourceServer(fallbackServer)
        val imageUrl = video.thumbnailUrl?.takeIf { it.startsWith("https://") }
            ?: video.thumbnailPath?.let { path -> runCatching { java.net.URI(imageServer.trimEnd('/') + "/").resolve(path).toString() }.getOrNull() }
        AsyncImage(model = imageUrl, contentDescription = video.name, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray))
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(video.name, Modifier.weight(1f), color = Color.White, maxLines = 2, fontWeight = FontWeight.Medium)
            IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Favorite", tint = if (favorite) Orange else Color.Gray) }
        }
        Text(video.channel?.displayName ?: video.account?.displayName.orEmpty(), Modifier.padding(start = 12.dp, bottom = 12.dp), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(Uri.parse(url))); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
}
