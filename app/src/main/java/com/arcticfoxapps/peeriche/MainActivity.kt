package com.arcticfoxapps.peeriche

import android.net.Uri
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView

private val Ink = Color(0xFF101113)
private val Panel = Color(0xFF1B1C20)
private val Orange = Color(0xFFFF7139)

private suspend fun fetchSubscribedVideos(rows: List<SavedVideo>): List<Video> = coroutineScope {
    val requests: List<Deferred<List<Video>>> = rows.take(20).mapNotNull { subscription ->
        val handle = subscription.channelName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        async<List<Video>> {
            try { PeerTubeClients.server(subscription.server).channelVideos(handle).data }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
    }
    requests.awaitAll().flatten()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 19)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 19)
        setContent { PeerIcheApp() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}

@Composable private fun PeerIcheApp() {
    val context = LocalContext.current
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 600
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val database = remember { PeerIcheDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val serverList by context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[stringPreferencesKey("servers")] ?: "https://peertube.tv" }.collectAsState(initial = "https://peertube.tv")
    val activeServerSetting by context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[stringPreferencesKey("activeServer")].orEmpty() }.collectAsState(initial = "")
    val darkTheme by context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[darkThemeKey] ?: true }.collectAsState(initial = true)
    var page by remember { mutableStateOf("Home") }
    val availableServers = remember(serverList) { serverList.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.distinct() }
    val server = remember(serverList, activeServerSetting) { activeServerSetting.takeIf { it in availableServers } ?: availableServers.firstOrNull() ?: "https://peertube.tv" }
    var videos by remember { mutableStateOf(emptyList<Video>()) }
    var recommendedVideos by remember { mutableStateOf(emptyList<Video>()) }
    var recommendationsUseHistory by remember { mutableStateOf(false) }
    var subscriptionFeed by remember { mutableStateOf(emptyList<Video>()) }
    var query by remember { mutableStateOf("") }
    var resultPage by remember { mutableIntStateOf(0) }
    var hasMoreResults by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedVideo by remember { mutableStateOf<Video?>(null) }
    var loadingVideo by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var videoLoadGeneration by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var discoveringServers by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf(emptyList<PeerTubeInstance>()) }
    var manualServerInput by remember { mutableStateOf("") }
    var serverInputError by remember { mutableStateOf(false) }
    var chooseServerOpen by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf(serverList) }
    var libraryTab by remember { mutableStateOf("Favorites") }
    var subscriptionsTab by remember { mutableStateOf("Subscriptions") }
    var channelVideosMode by remember { mutableStateOf(false) }
    var channelDirectoryServer by remember { mutableStateOf<String?>(null) }
    var serverChannelList by remember { mutableStateOf(emptyList<PeerTubeChannel>()) }
    val favoriteRows by database.library().observe("favorite").collectAsState(initial = emptyList())
    val historyRows by database.library().observe("history").collectAsState(initial = emptyList())
    val subscriptionRows by database.library().observe("subscription").collectAsState(initial = emptyList())
    val favorites = remember(favoriteRows) { favoriteRows.map { it.identity }.toSet() }

    DisposableEffect(selectedVideo != null, landscape) {
        val window = (context as? ComponentActivity)?.window
        if (selectedVideo != null) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (selectedVideo != null && landscape) {
            window?.decorView?.windowInsetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            window?.decorView?.windowInsetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.decorView?.windowInsetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        }
    }

    fun appendServer(raw: String) {
        val normalized = runCatching { normalizeServer(raw) }.getOrNull()
        if (normalized == null) { serverInputError = true; return }
        val current = serverDraft.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.toMutableList()
        if (normalized !in current) current += normalized
        serverDraft = current.joinToString("\n")
        manualServerInput = ""
        serverInputError = false
    }

    fun loadHome() {
        busy = true; error = null
        scope.launch {
            val history = database.library().getAll("history")
            val savedSubscriptions = database.library().getAll("subscription")
            val searchServers = availableServers.ifEmpty { listOf(server) }
            val latestRequest = async { runCatching { PeerTubeClients.home(server) } }
            val popularRequest = async { runCatching { PeerTubeClients.popular(server) } }
            val subscriptionRequest = async { fetchSubscribedVideos(savedSubscriptions) }
            val terms = history.take(3).map { it.title.trim() }.filter(String::isNotBlank).distinct()
            val recommendationRequest = async {
                coroutineScope {
                    terms.map { term -> async {
                        try { PeerTubeClients.searchPage(term, searchServers, 0).videos }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                    } }.awaitAll().flatten()
                }
            }
            val latest = latestRequest.await()
            videos = latest.getOrDefault(emptyList())
            busy = false
            val following = deduplicateVideos(subscriptionRequest.await(), server).take(30)
            subscriptionFeed = following
            val historyIds = history.map { it.identity.substringBefore("|") }.toSet()
            val followingIds = following.map { it.apiId() }.toSet()
            val fromHistory = deduplicateVideos(recommendationRequest.await(), server)
                .filterNot { it.apiId() in historyIds || it.apiId() in followingIds }
            recommendationsUseHistory = fromHistory.isNotEmpty()
            recommendedVideos = fromHistory.ifEmpty {
                popularRequest.await().getOrDefault(emptyList()).filterNot { it.apiId() in historyIds || it.apiId() in followingIds }
            }.take(30)
            if (latest.isFailure && recommendedVideos.isEmpty() && subscriptionFeed.isEmpty()) {
                error = latest.exceptionOrNull()?.message ?: context.getString(R.string.search_failed)
            }
        }
    }
    fun searchNow(pageToLoad: Int = 0) {
        val searchTerm = query.trim()
        if (searchTerm.isBlank()) { videos = emptyList(); error = null; hasMoreResults = false; return }
        busy = true; error = null
        val sources = serverList.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.distinct().ifEmpty { listOf(server) }
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            runCatching { PeerTubeClients.searchPage(searchTerm, sources, pageToLoad) }
                .onSuccess { response ->
                    if (searchTerm == query.trim()) {
                        videos = if (pageToLoad == 0) response.videos else deduplicateVideos(videos + response.videos, server)
                        resultPage = pageToLoad
                        hasMoreResults = response.hasMore
                        if (videos.isEmpty()) error = context.getString(R.string.no_results)
                    }
                }
                .onFailure { if (searchTerm == query.trim()) error = it.message ?: context.getString(R.string.search_failed) }
            if (searchTerm == query.trim()) busy = false
        }
    }
    fun loadSubscriptions(rows: List<SavedVideo> = subscriptionRows) {
        busy = true; error = null
        scope.launch {
            val collected = fetchSubscribedVideos(rows)
            videos = deduplicateVideos(collected, server)
            busy = false
        }
    }
    fun openChannelVideos(channel: PeerTubeChannel) {
        channelDirectoryServer = null
        channelVideosMode = true
        busy = true
        scope.launch {
            val handle = channel.name
            val host = channel.host?.let { "https://$it" } ?: server
            videos = runCatching { PeerTubeClients.server(host).channelVideos(handle).data }.getOrDefault(emptyList())
            busy = false
        }
    }
    fun openServerChannels(host: String) {
        channelDirectoryServer = host
        channelVideosMode = false
        busy = true
        error = null
        serverChannelList = emptyList()
        scope.launch {
            runCatching { PeerTubeClients.allChannelsWithVideoCounts(host) }
                .onSuccess { serverChannelList = it; if (it.isEmpty()) error = context.getString(R.string.no_channels) }
                .onFailure { error = it.message ?: context.getString(R.string.channels_load_failed) }
            busy = false
        }
    }
    fun closePlayer() {
        videoLoadGeneration++
        selectedVideo = null
        loadingVideo = false
        playerError = null
    }
    fun openVideo(video: Video) {
        val generation = ++videoLoadGeneration
        selectedVideo = video
        loadingVideo = true
        playerError = null
        scope.launch {
            val id = video.apiId()
            val details = if (id.isNotBlank()) runCatching { PeerTubeClients.video(video.sourceServer(server), id) }.getOrNull() else null
            val ready = details?.takeIf { it.playableUrl() != null } ?: video
            if (generation == videoLoadGeneration) {
                selectedVideo = ready
                loadingVideo = false
                if (ready.playableUrl() == null) playerError = context.getString(R.string.video_unavailable)
                database.library().put(ready.toSavedVideo(server, "history"))
            }
        }
    }
    LaunchedEffect(Unit) { loadHome() }
    LaunchedEffect(page, query, serverList) {
        if (page == "Search") {
            if (query.isBlank()) { videos = emptyList(); error = null; hasMoreResults = false; busy = false }
            else { delay(450); searchNow(0) }
        }
    }
    val canNavigateBack = selectedVideo != null || channelDirectoryServer != null || channelVideosMode ||
        (page == "Subscriptions" && subscriptionsTab == "Channels") || page != "Home"
    BackHandler(enabled = canNavigateBack) {
        when {
            selectedVideo != null -> closePlayer()
            channelDirectoryServer != null -> { channelDirectoryServer = null; serverChannelList = emptyList(); error = null }
            channelVideosMode -> { channelVideosMode = false; error = null }
            page == "Subscriptions" && subscriptionsTab == "Channels" -> subscriptionsTab = "Subscriptions"
            page != "Home" -> { page = "Home"; loadHome() }
        }
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(primary = Orange, secondary = Orange, background = Ink, surface = Panel, onSurface = Color(0xFFF1F1F3)) else lightColorScheme(primary = Orange, secondary = Color(0xFFB64720), background = Color(0xFFF7F6F4), surface = Color.White)) {
        Scaffold(containerColor = Ink, bottomBar = {
            if (selectedVideo == null && !wideLayout) NavigationBar(containerColor = Panel) {
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
                        if (destination == "Subscriptions") loadSubscriptions()
                    }, icon = { Icon(icon, null) }, label = { Text(stringResource(labelId)) })
                }
            }
        }) { insets ->
            Row(Modifier.fillMaxSize().padding(insets)) {
                if (wideLayout && selectedVideo == null) NavigationRail(containerColor = Panel) {
                    listOf("Home" to R.string.home, "Search" to R.string.search, "Subscriptions" to R.string.subscriptions, "Library" to R.string.library).forEach { (destination, labelId) ->
                        val icon = when (destination) { "Home" -> Icons.Default.Home; "Search" -> Icons.Default.Search; "Subscriptions" -> Icons.Default.Subscriptions; else -> Icons.Default.LibraryBooks }
                        NavigationRailItem(selected = page == destination, onClick = {
                            page = destination
                            if (destination == "Home") loadHome()
                            if (destination == "Subscriptions") loadSubscriptions()
                        }, icon = { Icon(icon, null) }, label = { Text(stringResource(labelId)) }, alwaysShowLabel = true)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
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
                            label = { Text(stringResource(R.string.search_hint)) })
                    }
                    when (page) {
                        "Home" -> Text(stringResource(R.string.discover_videos), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        "Subscriptions" -> if (channelVideosMode || channelDirectoryServer != null) {
                            TextButton(onClick = { channelVideosMode = false; channelDirectoryServer = null }) { Text("← ${stringResource(R.string.subscriptions)}") }
                            Text(if (channelVideosMode) stringResource(R.string.channel_videos) else stringResource(R.string.channels_on_server), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("Subscriptions" to R.string.subscriptions, "Channels" to R.string.channels).forEach { (tab, res) ->
                                FilterChip(selected = subscriptionsTab == tab, onClick = { subscriptionsTab = tab; channelVideosMode = false; channelDirectoryServer = null; if (tab == "Subscriptions") loadSubscriptions() }, label = { Text(stringResource(res)) }, modifier = Modifier.padding(end = 8.dp))
                            }
                        }
                        "Library" -> Row(Modifier.padding(horizontal = 12.dp)) {
                            listOf("Favorites" to R.string.favorites, "History" to R.string.history).forEach { (label, res) -> FilterChip(selected = libraryTab == label, onClick = { libraryTab = label }, label = { Text(stringResource(res)) }, modifier = Modifier.padding(end = 8.dp)) }
                        }
                    }
                    val visibleVideos = if (page == "Library") (if (libraryTab == "Favorites") favoriteRows else historyRows).map(SavedVideo::toVideo) else videos
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Orange)
                    error?.let { Text(it, Modifier.padding(18.dp), color = Color(0xFFFFB49B)) }
                    if (page == "Subscriptions" && subscriptionsTab == "Channels" && !channelVideosMode) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            if (channelDirectoryServer == null) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(stringResource(R.string.channel_column), Modifier.weight(1f), color = Color.Gray)
                                Text(stringResource(R.string.server_column), Modifier.padding(end = 12.dp), color = Color.Gray)
                            }
                            if (channelDirectoryServer != null) serverChannelList.forEach { channel ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { openChannelVideos(channel) }, modifier = Modifier.weight(1f)) { Text(channel.displayName.ifBlank { channel.name }, maxLines = 2) }
                                    Text("${channel.videosCount ?: 0}  ·  ${channel.host ?: channelDirectoryServer?.removePrefix("https://")}", color = Color.Gray, modifier = Modifier.clickable { openServerChannels(channel.host?.let { "https://$it" } ?: channelDirectoryServer.orEmpty()) }.padding(8.dp))
                                }
                                HorizontalDivider(color = Panel)
                            } else subscriptionRows.forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { openChannelVideos(PeerTubeChannel(row.title, row.channelName.orEmpty(), java.net.URI(row.server).host, 0)) }, modifier = Modifier.weight(1f)) { Text(row.title, maxLines = 2) }
                                    TextButton(onClick = { openServerChannels(row.server) }) { Text(java.net.URI(row.server).host.orEmpty(), color = Color.Gray, maxLines = 1) }
                                }
                                HorizontalDivider(color = Panel)
                            }
                            if (channelDirectoryServer == null && subscriptionRows.isEmpty()) Text(stringResource(R.string.no_subscribed_channels), Modifier.padding(vertical = 16.dp), color = Color.Gray)
                        }
                    } else if (selectedVideo == null) LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 230.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (page == "Home") {
                            if (subscriptionFeed.isNotEmpty()) videoSection(R.string.from_subscriptions, subscriptionFeed, "following", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope, ::loadSubscriptions) })
                            if (recommendedVideos.isNotEmpty()) videoSection(if (recommendationsUseHistory) R.string.recommended_for_you else R.string.popular_now, recommendedVideos, "recommended", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope, ::loadSubscriptions) })
                            videoSection(R.string.latest_videos, videos, "latest", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope, ::loadSubscriptions) })
                        } else {
                            videoSection(null, visibleVideos, page.lowercase(), server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope, ::loadSubscriptions) })
                        }
                        if (page == "Search" && hasMoreResults) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { searchNow(resultPage + 1) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (busy) R.string.loading_more else R.string.load_more))
                            }
                        }
                    }
                } else {
                    val video = selectedVideo!!
                    if (!landscape) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::closePlayer) { Text("← ${stringResource(R.string.back)}") }
                        Text(video.name, color = Color.White, maxLines = 2, modifier = Modifier.padding(start = 12.dp))
                    }
                    val playUrl = video.playableUrl()
                    when {
                        loadingVideo -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Orange, modifier = Modifier.size(26.dp))
                            Text(stringResource(R.string.loading_video), Modifier.padding(start = 12.dp), color = Color.White)
                        }
                        playUrl != null -> VideoPlayer(playUrl, modifier = if (landscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        else -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(playerError ?: stringResource(R.string.video_unavailable), color = Color.White)
                            TextButton(onClick = { openVideo(video) }) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    if (!landscape) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(video.name, Modifier.padding(16.dp), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.video_meta, video.views, video.channel?.displayName ?: video.account?.displayName.orEmpty()), Modifier.padding(horizontal = 16.dp), color = Color.Gray)
                        Text(video.description.orEmpty(), Modifier.padding(16.dp), color = Color.LightGray)
                        TextButton(onClick = {
                            val handle = video.channel?.name ?: return@TextButton
                            channelVideosMode = true
                            page = "Subscriptions"
                            scope.launch { videos = runCatching { PeerTubeClients.server(video.sourceServer(server)).channelVideos(handle).data }.getOrDefault(emptyList()) }
                            closePlayer()
                        }) { Text(stringResource(R.string.channel_videos)) }
                    }
                }
                }
            }
        }
        if (settingsOpen) AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text(stringResource(R.string.servers)) },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dark_theme), Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = { enabled -> scope.launch { saveDarkTheme(context, enabled) } })
                }
                Text(stringResource(R.string.servers_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualServerInput,
                    onValueChange = { manualServerInput = it; serverInputError = false },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = serverInputError,
                    label = { Text(stringResource(R.string.manual_server_address)) },
                    supportingText = if (serverInputError) ({ Text(stringResource(R.string.invalid_server_address)) }) else null,
                    trailingIcon = { TextButton(onClick = { appendServer(manualServerInput) }, enabled = manualServerInput.isNotBlank()) { Text(stringResource(R.string.add_server)) } }
                )
                OutlinedTextField(serverDraft, { serverDraft = it }, label = { Text(stringResource(R.string.server_list)) }, minLines = 3, maxLines = 8)
                TextButton(enabled = !discoveringServers, onClick = {
                    discoveringServers = true
                    scope.launch {
                        discoveredServers = runCatching { PeerTubeClients.discoverInstances() }.getOrDefault(emptyList())
                        discoveringServers = false
                    }
                }) { Text(stringResource(if (discoveringServers) R.string.discovering_servers else R.string.discover_servers)) }
                discoveredServers.take(20).forEach { instance ->
                    TextButton(onClick = { appendServer("https://${instance.host}") }, modifier = Modifier.fillMaxWidth()) {
                        Text("＋ ${instance.name?.takeIf(String::isNotBlank) ?: instance.host} · ${instance.host}", maxLines = 1)
                    }
                }
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
    identity = apiId().ifBlank { url ?: name }, kind = kind, title = name,
    videoUrl = playableUrl(), server = sourceServer(fallbackServer),
    thumbnailUrl = thumbnailCandidates(fallbackServer).firstOrNull(),
    channelName = channel?.displayName ?: account?.displayName, views = views, updatedAt = System.currentTimeMillis()
)

private fun Video.subscriptionIdentity(fallbackServer: String): String = "${sourceServer(fallbackServer).lowercase()}|${channel?.name.orEmpty().lowercase()}"

private fun Video.toSubscription(fallbackServer: String): SavedVideo? {
    val handle = channel?.name?.takeIf(String::isNotBlank) ?: return null
    val origin = sourceServer(fallbackServer)
    return SavedVideo(identity = subscriptionIdentity(fallbackServer), kind = "subscription", title = channel.displayName ?: handle,
        videoUrl = null, server = origin, thumbnailUrl = null, channelName = handle, views = 0, updatedAt = System.currentTimeMillis())
}

private fun LazyGridScope.videoSection(
    title: Int?, videos: List<Video>, keyPrefix: String, server: String,
    favorites: Set<String>, subscriptionIds: Set<String>,
    onOpen: (Video) -> Unit, onFavorite: (Video) -> Unit, onSubscribe: (Video) -> Unit
) {
    if (title != null && videos.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
        Text(stringResource(title), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
    items(videos, key = { "$keyPrefix:${it.sourceServer(server)}:${it.apiId().ifBlank { it.name }}" }) { video ->
        VideoCard(
            video = video,
            fallbackServer = server,
            favorite = video.uuid in favorites,
            subscribed = video.subscriptionIdentity(server) in subscriptionIds,
            onOpen = { onOpen(video) },
            onFavorite = { onFavorite(video) },
            onSubscribe = { onSubscribe(video) }
        )
    }
}

private fun toggleSubscription(
    video: Video, server: String, database: PeerIcheDatabase, rows: List<SavedVideo>,
    scope: kotlinx.coroutines.CoroutineScope, reload: (List<SavedVideo>) -> Unit
) {
    val row = video.toSubscription(server) ?: return
    scope.launch {
        if (rows.any { it.identity == row.identity }) database.library().delete(row.identity, "subscription")
        else database.library().put(row)
        reload(database.library().getAll("subscription"))
    }
}

@Composable private fun VideoCard(video: Video, fallbackServer: String, favorite: Boolean, subscribed: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit, onSubscribe: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onOpen)) {
        val candidates = remember(video.apiId(), video.thumbnailUrl, video.thumbnailPath, video.previewPath, fallbackServer) { video.thumbnailCandidates(fallbackServer) }
        var imageIndex by remember(video.apiId(), candidates) { mutableIntStateOf(0) }
        AsyncImage(
            model = candidates.getOrNull(imageIndex),
            contentDescription = video.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray),
            onError = { if (imageIndex < candidates.lastIndex) imageIndex++ }
        )
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(video.name, Modifier.weight(1f), color = Color.White, maxLines = 2, fontWeight = FontWeight.Medium)
            if (video.channel?.name != null) IconButton(onClick = onSubscribe) { Icon(Icons.Default.Notifications, if (subscribed) stringResource(R.string.unsubscribe_channel) else stringResource(R.string.subscribe_channel), tint = if (subscribed) Orange else Color.Gray) }
            IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Favorite", tint = if (favorite) Orange else Color.Gray) }
        }
        Text(video.channel?.displayName ?: video.account?.displayName.orEmpty(), Modifier.padding(start = 12.dp, bottom = 12.dp), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        video.publishedAt?.let { Text(formatPublishedDate(it), Modifier.padding(start = 12.dp, bottom = 12.dp), color = Color.Gray, style = MaterialTheme.typography.labelSmall) }
    }
}

private fun formatPublishedDate(value: String): String = runCatching {
    val date = java.time.OffsetDateTime.parse(value).toLocalDate()
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))
}.getOrElse { value.substringBefore('T') }

@Composable private fun VideoPlayer(url: String, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
    val context = LocalContext.current
    var playbackFailed by remember(url) { mutableStateOf(false) }
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(Uri.parse(url))); prepare(); playWhenReady = true } }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackFailed = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = modifier)
    if (playbackFailed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.video_unavailable), Modifier.weight(1f), color = Color(0xFFFFB49B))
        TextButton(onClick = { playbackFailed = false; player.seekTo(0); player.prepare(); player.playWhenReady = true }) { Text(stringResource(R.string.retry)) }
    }
}
