package com.arcticfoxapps.peeriche

import android.net.Uri
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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

private val Ink = Color(0xFF0C1116)
private val Panel = Color(0xFF151D25)
private val CardSurface = Color(0xFF19232D)
private val Orange = Color(0xFFFF744D)
private val Mint = Color(0xFF65D8C4)

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
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(localizedAppContext(newBase)) }
    override fun onStop() {
        if (!AppPreferences.get(this).options.value.backgroundPlayback) PlaybackService.pausePlayback()
        super.onStop()
    }
    private var requestedPlayback by mutableStateOf<Video?>(null)
    private fun restorePlayback(intent: android.content.Intent) {
        val id = intent.getStringExtra("playing_id") ?: return
        val url = intent.getStringExtra("playing_url") ?: return
        requestedPlayback = Video(uuid = id.substringAfter('|'),
            host = id.substringBefore('|').removePrefix("https://"),
            name = intent.getStringExtra("playing_title").orEmpty(), directStreamUrl = url)
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restorePlayback(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(12, 17, 22)
        window.navigationBarColor = android.graphics.Color.rgb(16, 24, 32)
        restorePlayback(intent)
        setContent { PeerIcheApp(requestedPlayback) }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PeerIcheApp(requestedPlayback: Video?) {
    val context = LocalContext.current
    val options by AppPreferences.get(context).options.collectAsState()
    val wideLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600 && LocalConfiguration.current.screenWidthDp >= 600
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val database = remember { PeerIcheDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val serverList by remember(context) { context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[stringPreferencesKey("servers")] ?: "https://peertube.tv" } }.collectAsState(initial = "https://peertube.tv")
    val activeServerSetting by remember(context) { context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[stringPreferencesKey("activeServer")].orEmpty() } }.collectAsState(initial = "")
    val themeMode by remember(context) { context.peerIcheDataStore.data.catch { emit(emptyPreferences()) }.map { it[themeModeKey] ?: "system" } }.collectAsState(initial = "system")
    val darkTheme = themeMode != "light" && (themeMode != "system" || isSystemInDarkTheme())
    val homeGridState = rememberLazyGridState()
    val serverGridState = rememberLazyGridState()
    val liveGridState = rememberLazyGridState()
    val searchGridState = rememberLazyGridState()
    val subscriptionsGridState = rememberLazyGridState()
    val channelGridState = rememberLazyGridState()
    val favoritesGridState = rememberLazyGridState()
    val historyGridState = rememberLazyGridState()
    var homeRefreshing by remember { mutableStateOf(false) }
    var homeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var page by rememberSaveable { mutableStateOf("Home") }
    val availableServers = remember(serverList) { serverList.lines().mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.distinct() }
    val server = remember(serverList, activeServerSetting) { activeServerSetting.takeIf { it in availableServers } ?: availableServers.firstOrNull() ?: "https://peertube.tv" }
    var videos by remember { mutableStateOf(emptyList<Video>()) }
    var homeVideos by remember { mutableStateOf(emptyList<Video>()) }
    var serverVideos by remember { mutableStateOf(emptyList<Video>()) }
    var liveVideos by remember { mutableStateOf(emptyList<Video>()) }
    var browseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var browseGeneration by remember { mutableIntStateOf(0) }
    var browseBusy by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var browsePage by remember { mutableIntStateOf(0) }
    var browseHasMore by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var recommendedVideos by remember { mutableStateOf(emptyList<Video>()) }
    var recommendationsUseHistory by remember { mutableStateOf(false) }
    var subscriptionFeed by remember { mutableStateOf(emptyList<Video>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var resultPage by remember { mutableIntStateOf(0) }
    var hasMoreResults by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedVideo by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<Video?, String>(
        save = { com.google.gson.Gson().toJson(it) },
        restore = { com.google.gson.Gson().fromJson(it, Video::class.java) }
    )) { mutableStateOf<Video?>(null) }
    var tabletBrowsing by rememberSaveable { mutableStateOf(false) }
    var tabletFullscreen by rememberSaveable { mutableStateOf(false) }
    var phoneFullscreen by rememberSaveable { mutableStateOf(false) }
    var inlineLandscape by rememberSaveable { mutableStateOf(false) }
    val phonePlayerFullscreen = !wideLayout && (phoneFullscreen || (landscape && !inlineLandscape))
    LaunchedEffect(landscape) { if (!landscape) inlineLandscape = false }
    val showVideo = selectedVideo != null && (!wideLayout || !tabletBrowsing)
    var loadingVideo by remember { mutableStateOf(false) }
    LaunchedEffect(requestedPlayback) { requestedPlayback?.let { selectedVideo = it; tabletBrowsing = false } }
    var playerError by remember { mutableStateOf<String?>(null) }
    var videoLoadGeneration by remember { mutableIntStateOf(0) }
    var updateRequest by remember { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var chooseServerOpen by remember { mutableStateOf(false) }
    var libraryTab by remember { mutableStateOf("Favorites") }
    var subscriptionsTab by remember { mutableStateOf("Subscriptions") }
    var channelVideosMode by remember { mutableStateOf(false) }
    var channelDirectoryServer by remember { mutableStateOf<String?>(null) }
    var serverChannelList by remember { mutableStateOf(emptyList<PeerTubeChannel>()) }
    val favoriteRows by database.library().observe("favorite").collectAsState(initial = emptyList())
    val historyRows by database.library().observe("history").collectAsState(initial = emptyList())
    val subscriptionRows by database.library().observe("subscription").collectAsState(initial = emptyList())
    val favorites = remember(favoriteRows) { favoriteRows.map { it.identity }.toSet() }

    DisposableEffect(showVideo, phonePlayerFullscreen, tabletFullscreen) {
        val window = (context as? ComponentActivity)?.window
        if (showVideo) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (showVideo && (phonePlayerFullscreen || (wideLayout && tabletFullscreen))) {
            window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        } else {
            window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    fun loadHome() {
        if (homeRefreshing) return
        homeRefreshing = true
        busy = true; error = null
        homeJob = scope.launch {
            try {
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
                latest.onSuccess { homeVideos = it }
                val following = sortHomeVideos(subscriptionRequest.await(), "newest", server).take(30)
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
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = context.getString(R.string.search_failed)
            } finally {
                busy = false
                homeRefreshing = false
            }
        }
    }
    fun loadBrowse(pageToLoad: Int = 0) {
        val destination = page
        if (destination !in listOf("Server", "Live")) return
        val generation = ++browseGeneration
        browseJob?.cancel()
        browseBusy = true
        browseError = null
        browseJob = scope.launch {
            try {
                val response = if (destination == "Server") PeerTubeClients.serverPage(server, pageToLoad, options.includeSensitive)
                    else PeerTubeClients.livePage(server, pageToLoad, options.includeSensitive)
                if (generation == browseGeneration) {
                    val previous = if (destination == "Server") serverVideos else liveVideos
                    val collected = if (pageToLoad == 0) response.videos else deduplicateVideos(previous + response.videos, server)
                    if (destination == "Server") serverVideos = collected else liveVideos = collected
                    browsePage = pageToLoad
                    browseHasMore = response.hasMore
                    if (collected.isEmpty()) browseError = context.getString(if (destination == "Live") R.string.no_live_videos else R.string.no_results)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == browseGeneration) browseError = context.getString(R.string.search_failed) }
            finally { if (generation == browseGeneration) browseBusy = false }
        }
    }
    fun searchNow(pageToLoad: Int = 0) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        val searchTerm = query.trim()
        if (searchTerm.isBlank()) { videos = emptyList(); error = null; hasMoreResults = false; busy = false; return }
        busy = true; error = null
        val sources = availableServers.ifEmpty { listOf(server) }
        searchJob = scope.launch {
            try {
                val response = ConfiguredSearch.search(context, searchTerm, sources, server, pageToLoad, options)
                if (generation == searchGeneration) {
                    videos = if (pageToLoad == 0) response.videos else deduplicateVideos(videos + response.videos, server)
                    resultPage = pageToLoad
                    hasMoreResults = response.hasMore
                    if (videos.isEmpty()) error = context.getString(R.string.no_results)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == searchGeneration) error = context.getString(R.string.search_failed) }
            finally { if (generation == searchGeneration) busy = false }
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
        subscriptionsTab = "Channels"
        page = "Subscriptions"
        channelVideosMode = true
        busy = true
        error = null
        scope.launch {
            val handle = channel.name
            val host = channel.host?.let { "https://$it" } ?: server
            runCatching { PeerTubeClients.server(host).channelVideos(handle).data }
                .onSuccess { videos = it; if (it.isEmpty()) error = context.getString(R.string.no_results) }
                .onFailure { error = it.message ?: context.getString(R.string.search_failed) }
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
    fun setPhoneFullscreen(enabled: Boolean) {
        phoneFullscreen = enabled
        inlineLandscape = !enabled && landscape
    }
    fun closePlayer() {
        (context as? ComponentActivity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        phoneFullscreen = false
        inlineLandscape = false
        tabletFullscreen = false
        tabletBrowsing = false
        context.stopService(android.content.Intent(context, PlaybackService::class.java))
        videoLoadGeneration++
        selectedVideo = null
        loadingVideo = false
        playerError = null
    }
    fun openVideo(video: Video) {
        phoneFullscreen = false
        inlineLandscape = false
        tabletBrowsing = false
        tabletFullscreen = false
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
            }
        }
    }
    fun toggleCurrentVideoSubscription(video: Video) {
        val row = video.toSubscription(server) ?: return
        scope.launch {
            if (subscriptionRows.any { it.identity == row.identity }) database.library().delete(row.identity, "subscription")
            else database.library().put(row)
        }
    }
    fun toggleCurrentVideoFavorite(video: Video) {
        val id = video.apiId()
        scope.launch {
            if (id in favorites) database.library().delete(id, "favorite")
            else database.library().put(video.toSavedVideo(server, "favorite"))
        }
    }
    val endedVideo by PlaybackService.endedVideo.collectAsState()
    LaunchedEffect(endedVideo, selectedVideo?.apiId()) {
        val current = selectedVideo
        if (current != null && endedVideo == current.sourceServer(server) + "|" + current.apiId()) closePlayer()
    }
    LaunchedEffect(server) { homeJob?.cancel(); homeJob?.join(); if (page == "Home") loadHome() else if (page == "Search") searchNow() else if (page == "Subscriptions") loadSubscriptions() }
    LaunchedEffect(page, server, options.includeSensitive) {
        browseGeneration++
        browseJob?.cancel()
        browseBusy = false
        if (page == "Server" || page == "Live") {
            if (page == "Server") serverVideos = emptyList() else liveVideos = emptyList()
            browseHasMore = false
            loadBrowse()
        }
    }
    LaunchedEffect(query, serverList) { searchGridState.scrollToItem(0) }
    val searchSettingsKey = listOf(options.searchSources, options.searchOrder, options.searchMode, options.searchEnough, options.searchParallel, options.searchTimeout, options.customIndexes, options.availability, options.includeSensitive, options.sensitiveDisplay, options.searchSort).hashCode()
    LaunchedEffect(page, query, serverList, searchSettingsKey) {
        if (page == "Search") {
            if (query.isBlank()) searchNow()
            else { delay(450); searchNow(0) }
        }
    }
    var feedPanelsVisible by rememberSaveable { mutableStateOf(true) }
    val activeFeedState = when (page) {
        "Home" -> homeGridState
        "Server" -> serverGridState
        "Live" -> liveGridState
        "Search" -> searchGridState
        "Subscriptions" -> if (channelVideosMode) channelGridState else subscriptionsGridState
        else -> if (libraryTab == "Favorites") favoritesGridState else historyGridState
    }
    val feedScrollConnection = rememberFeedScrollConnection(!wideLayout && !showVideo, activeFeedState) { feedPanelsVisible = it }
    LaunchedEffect(page, query, libraryTab, subscriptionsTab, wideLayout) { feedPanelsVisible = true }
    val canNavigateBack = showVideo || channelDirectoryServer != null || channelVideosMode ||
        (page == "Subscriptions" && subscriptionsTab == "Channels") || page != "Home"
    BackHandler(enabled = canNavigateBack) {
        when {
            !wideLayout && showVideo && phonePlayerFullscreen -> setPhoneFullscreen(false)
            wideLayout && tabletFullscreen -> tabletFullscreen = false
            wideLayout && showVideo -> tabletBrowsing = true
            selectedVideo != null && !wideLayout -> closePlayer()
            channelDirectoryServer != null -> { channelDirectoryServer = null; serverChannelList = emptyList(); error = null }
            channelVideosMode -> { channelVideosMode = false; error = null }
            page == "Subscriptions" && subscriptionsTab == "Channels" -> subscriptionsTab = "Subscriptions"
            page != "Home" -> { page = "Home"; loadHome() }
        }
    }

    val scheme = when {
        themeMode == "aurora" -> darkColorScheme(
            primary = Color(0xFF8DADFF), secondary = Color(0xFF63D8EF),
            background = Color(0xFF071225), surface = Color(0xFF101F36),
            surfaceVariant = Color(0xFF1C2D48), onSurface = Color(0xFFEDF3FF),
            onBackground = Color(0xFFEDF3FF), onPrimary = Color(0xFF10264D),
            secondaryContainer = Color(0xFF233F68), onSecondaryContainer = Color(0xFFD6E7FF))
        darkTheme -> darkColorScheme(
            primary = Orange, secondary = Mint, background = Ink, surface = Panel,
            surfaceVariant = CardSurface, onSurface = Color(0xFFF1F4F6),
            onBackground = Color(0xFFF1F4F6), onPrimary = Color(0xFF33130B),
            secondaryContainer = Color(0xFF49332C), onSecondaryContainer = Orange)
        else -> lightColorScheme(
            primary = Color(0xFF125BD5), secondary = Color(0xFF176B87),
            background = Color(0xFFF7F9FD), surface = Color.White,
            surfaceVariant = Color(0xFFE7EDF7), onSurface = Color(0xFF14213D),
            onBackground = Color(0xFF14213D), onPrimary = Color.White,
            secondaryContainer = Color(0xFFDCE8FF), onSecondaryContainer = Color(0xFF124799))
    }
    SideEffect {
        (context as? ComponentActivity)?.window?.let { window ->
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.surface.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        AppUpdateFeature(updateRequest)
        val colors = MaterialTheme.colorScheme
        CompositionLocalProvider(LocalMetadataTranslationPaused provides settingsOpen) {
        Scaffold(containerColor = colors.background, bottomBar = {
            if (wideLayout && !showVideo && selectedVideo != null && !loadingVideo) BackgroundVideoPlayer(selectedVideo!!, server, Modifier.fillMaxWidth(), compact = true,
                onExpand = { tabletBrowsing = false }, onClose = ::closePlayer)
            if (!showVideo && !wideLayout) FeedPanel(feedPanelsVisible, top = false) { NavigationBar(containerColor = colors.surface, tonalElevation = 8.dp) {
                listOf("Home", "Search", "Subscriptions", "Library").forEach { destination ->
                    val (iconId, labelId) = when (destination) {
                        "Home" -> R.drawable.ic_nav_home to R.string.home
                        "Search" -> R.drawable.ic_nav_search to R.string.search
                        "Subscriptions" -> R.drawable.ic_nav_subscriptions to R.string.subscriptions
                        else -> R.drawable.ic_nav_library to R.string.library
                    }
                    NavigationBarItem(selected = page == destination || (destination == "Home" && page in listOf("Server", "Live")), onClick = {
                        page = destination
                        if (destination == "Home") loadHome()
                        if (destination == "Subscriptions") loadSubscriptions()
                    }, icon = { Icon(painterResource(iconId), stringResource(labelId)) }, label = { Text(stringResource(labelId), fontWeight = if (page == destination) FontWeight.SemiBold else FontWeight.Normal) })
                }
            }
            }
        }) { insets ->
            Row(Modifier.fillMaxSize().padding(insets)) {
                if (wideLayout && !tabletFullscreen) TabletNavigation(page, collapsed = showVideo || LocalConfiguration.current.screenWidthDp < 840) { destination ->
                    tabletBrowsing = selectedVideo != null
                    page = destination
                    if (destination == "Home") loadHome()
                    if (destination == "Subscriptions") loadSubscriptions()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                FeedPanel(wideLayout || feedPanelsVisible, top = true) {
                if ((!showVideo || wideLayout) && !tabletFullscreen) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_peeriche_mark), contentDescription = null, modifier = Modifier.size(28.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = (-0.6).sp, color = colors.onBackground, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Box {
                            Surface(onClick = { chooseServerOpen = true }, shape = RoundedCornerShape(18.dp), color = colors.surface) {
                                Text(server.removePrefix("https://"), Modifier.widthIn(max = 160.dp).padding(horizontal = 12.dp, vertical = 9.dp), color = colors.onSurface, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                            DropdownMenu(expanded = chooseServerOpen, onDismissRequest = { chooseServerOpen = false }) {
                                availableServers.forEach { host -> DropdownMenuItem(text = { Text(host.removePrefix("https://")) }, onClick = {
                                    scope.launch { saveServerList(context, serverList, host) }
                                    chooseServerOpen = false
                                }) }
                                DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { chooseServerOpen = false; settingsOpen = true })
                            }
                        }
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    }
                }
                }
                if (!showVideo) {
                    FeedPanel(wideLayout || feedPanelsVisible, top = true) { Column {
                    if (page in listOf("Home", "Server", "Live", "Subscriptions")) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("Home" to R.string.home, "Server" to R.string.on_server, "Live" to R.string.live_now, "Subscriptions" to R.string.subscriptions).forEach { (destination, label) ->
                                FilterChip(selected = page == destination, onClick = {
                                    page = destination
                                    if (destination == "Home") loadHome()
                                    if (destination == "Subscriptions") {
                                        subscriptionsTab = "Subscriptions"
                                        channelVideosMode = false
                                        channelDirectoryServer = null
                                        loadSubscriptions()
                                    }
                                }, label = { Text(stringResource(label), maxLines = 1) })
                            }
                        }
                    }
                    if (page == "Search") {
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                            shape = RoundedCornerShape(18.dp), leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.secondary) },
                            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.clear_search)) } }) else null,
                            label = { Text(stringResource(R.string.search_hint)) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outlineVariant, focusedLabelColor = colors.primary)
                        )
                    }
                    if (page == "Home") {
                        var sortMenuOpen by remember { mutableStateOf(false) }
                        val sorts = listOf("newest" to R.string.home_sort_newest, "oldest" to R.string.home_sort_oldest,
                            "views" to R.string.home_sort_views, "sections" to R.string.home_sort_sections)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                            Box {
                                TextButton(onClick = { sortMenuOpen = true }) {
                                    Text(stringResource(R.string.home_sort_label, stringResource(sorts.firstOrNull { it.first == options.homeSort }?.second ?: R.string.home_sort_newest)) + " ▾")
                                }
                                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                    sorts.forEach { (value, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                        AppPreferences.get(context).update { it.copy(homeSort = value) }
                                        sortMenuOpen = false
                                        scope.launch { homeGridState.scrollToItem(0) }
                                    }) }
                                }
                            }
                        }
                    }
                    when (page) {
                        "Subscriptions" -> {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("Subscriptions" to R.string.subscriptions, "Channels" to R.string.channels).forEach { (tab, res) ->
                                FilterChip(selected = subscriptionsTab == tab, onClick = { subscriptionsTab = tab; channelVideosMode = false; channelDirectoryServer = null; if (tab == "Subscriptions") loadSubscriptions() }, label = { Text(stringResource(res)) }, modifier = Modifier.padding(end = 8.dp))
                            }
                            }
                            if (channelVideosMode || channelDirectoryServer != null) Text(
                                stringResource(if (channelVideosMode) R.string.channel_videos else R.string.channels_on_server),
                                Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp), color = colors.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        "Library" -> Row(Modifier.padding(horizontal = 12.dp)) {
                            listOf("Favorites" to R.string.favorites, "History" to R.string.history).forEach { (label, res) -> FilterChip(selected = libraryTab == label, onClick = { libraryTab = label }, label = { Text(stringResource(res)) }, modifier = Modifier.padding(end = 8.dp)) }
                        }
                    }
                    } }
                    val browsingFeed = page == "Server" || page == "Live"
                    val watchedIds = remember(historyRows) { historyRows.map { it.identity.substringBefore('|') }.toSet() }
                    val homeFollowing = subscriptionFeed.filterNot { it.apiId() in watchedIds }
                    val homeRecommended = recommendedVideos.filterNot { it.apiId() in watchedIds }
                    val homeRecent = homeVideos.filterNot { it.apiId() in watchedIds }
                    val visibleVideos = when (page) {
                        "Server" -> serverVideos
                        "Live" -> liveVideos
                        "Library" -> (if (libraryTab == "Favorites") favoriteRows else historyRows).map(SavedVideo::toVideo)
                        else -> videos
                    }
                    if (if (browsingFeed) browseBusy else busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = colors.primary, trackColor = colors.surface)
                    (if (browsingFeed) browseError else error)?.let { Text(it, Modifier.padding(18.dp), color = if (darkTheme) Color(0xFFFFB49B) else Color(0xFF9C341F)) }
                    if (browsingFeed && browseError != null && !browseBusy) TextButton(onClick = { loadBrowse(if (browseHasMore) browsePage + 1 else 0) }) { Text(stringResource(R.string.retry)) }
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
                                HorizontalDivider(color = colors.outlineVariant)
                            } else subscriptionRows.forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { openChannelVideos(PeerTubeChannel(row.title, row.channelName.orEmpty(), java.net.URI(row.server).host, 0)) }, modifier = Modifier.weight(1f)) { Text(row.title, maxLines = 2) }
                                    TextButton(onClick = { openServerChannels(row.server) }) { Text(java.net.URI(row.server).host.orEmpty(), color = Color.Gray, maxLines = 1) }
                                }
                                HorizontalDivider(color = colors.outlineVariant)
                            }
                            if (channelDirectoryServer == null && subscriptionRows.isEmpty()) Text(stringResource(R.string.no_subscribed_channels), Modifier.padding(vertical = 16.dp), color = Color.Gray)
                        }
                    } else if (!showVideo) FeedRefreshContainer(
                        enabled = page == "Home" && !wideLayout,
                        refreshing = homeRefreshing,
                        onRefresh = { if (!busy) loadHome() }
                    ) {
                    LazyVerticalGrid(state = activeFeedState, modifier = Modifier.nestedScroll(feedScrollConnection), columns = if (wideLayout) GridCells.Fixed(tabletColumns(LocalConfiguration.current.screenWidthDp)) else GridCells.Adaptive(minSize = 230.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (page == "Home" && options.homeSort != "sections") {
                            videoSection(null, sortHomeVideos(homeFollowing + homeRecommended + homeRecent, options.homeSort, server), "home", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> toggleCurrentVideoFavorite(video) },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope) })
                        } else if (page == "Home") {
                            if (homeFollowing.isNotEmpty()) videoSection(R.string.from_subscriptions, homeFollowing, "following", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope) })
                            if (homeRecommended.isNotEmpty()) videoSection(if (recommendationsUseHistory) R.string.recommended_for_you else R.string.popular_now, homeRecommended, "recommended", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope) })
                            videoSection(R.string.latest_videos, homeRecent, "latest", server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope) })
                        } else {
                            videoSection(null, visibleVideos, page.lowercase(), server, favorites, subscriptionRows.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleSubscription(video, server, database, subscriptionRows, scope) })
                        }
                        if (page == "Search" && hasMoreResults) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { searchNow(resultPage + 1) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (busy) R.string.loading_more else R.string.load_more))
                            }
                        }
                        if (browsingFeed && browseHasMore) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { loadBrowse(browsePage + 1) }, enabled = !browseBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (browseBusy) R.string.loading_more else R.string.load_more))
                            }
                        }
                    }
                    }
                } else if (wideLayout) {
                    val current = selectedVideo!!
                    val next = deduplicateVideos(recommendedVideos + homeVideos + videos + subscriptionFeed, server)
                        .filterNot { it.apiId() == current.apiId() && it.sourceServer(server) == current.sourceServer(server) }.take(20)
                    TabletVideoScreen(current, server, loadingVideo, playerError, next, tabletFullscreen,
                        current.apiId() in favorites, subscriptionRows.any { it.identity == current.subscriptionIdentity(server) },
                        onBrowse = { tabletFullscreen = false; tabletBrowsing = true },
                        onFullscreen = { tabletFullscreen = !tabletFullscreen }, onOpen = ::openVideo,
                        onFavorite = { toggleCurrentVideoFavorite(current) }, onSubscribe = { toggleCurrentVideoSubscription(current) },
                        onRetry = { openVideo(current) })
                } else {
                    val video = selectedVideo!!
                    val playUrl = video.playableUrl()
                    when {
                        loadingVideo -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Orange, modifier = Modifier.size(26.dp))
                            Text(stringResource(R.string.loading_video), Modifier.padding(start = 12.dp), color = colors.onBackground)
                        }
                        playUrl != null -> BackgroundVideoPlayer(video, server,
                            modifier = if (phonePlayerFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            fullscreen = phonePlayerFullscreen, onFullscreenToggle = { setPhoneFullscreen(!phonePlayerFullscreen) })
                        else -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(playerError ?: stringResource(R.string.video_unavailable), color = colors.onBackground)
                            TextButton(onClick = { openVideo(video) }) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    if (!phonePlayerFullscreen) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val channel = video.channel?.name?.let { PeerTubeChannel(video.channel.displayName ?: it, it, video.sourceServer(server).removePrefix("https://"), 0) }
                            TextButton(onClick = ::closePlayer) { Text(stringResource(R.string.back)) }
                            TextButton(onClick = { toggleCurrentVideoSubscription(video) }, enabled = channel != null) {
                                Text(stringResource(if (channel != null && subscriptionRows.any { it.identity == video.subscriptionIdentity(server) }) R.string.unsubscribe_button else R.string.subscribe_button), maxLines = 2)
                            }
                            TextButton(onClick = { toggleCurrentVideoFavorite(video) }) {
                                Text(stringResource(if (video.apiId() in favorites) R.string.remove_favorite else R.string.add_favorite), maxLines = 2)
                            }
                            TextButton(onClick = {
                                video.channel?.name?.let { handle ->
                                    openChannelVideos(PeerTubeChannel(video.channel.displayName ?: handle, handle, video.sourceServer(server).removePrefix("https://")))
                                    closePlayer()
                                }
                            }, enabled = video.channel?.name != null) { Text(stringResource(R.string.channel_videos), maxLines = 2) }
                        }
                        Text(translatedMetadata(video.name), Modifier.padding(16.dp), color = colors.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.video_meta, video.views, video.channel?.displayName ?: video.account?.displayName.orEmpty()), Modifier.padding(horizontal = 16.dp), color = Color.Gray)
                        Text(translatedMetadata(video.description.orEmpty()), Modifier.padding(16.dp), color = colors.onSurfaceVariant)
                    }
                }
                }
            }
        }
        }
        if (settingsOpen) SettingsScreen(onClose = { settingsOpen = false }, onCheckUpdates = { updateRequest++ })

    }
}

internal fun Video.toSavedVideo(fallbackServer: String, kind: String) = SavedVideo(
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
        Text(stringResource(title), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
    scope: kotlinx.coroutines.CoroutineScope
) {
    val row = video.toSubscription(server) ?: return
    scope.launch {
        if (rows.any { it.identity == row.identity }) database.library().delete(row.identity, "subscription")
        else database.library().put(row)
    }
}

@Composable private fun VideoCard(video: Video, fallbackServer: String, favorite: Boolean, subscribed: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit, onSubscribe: () -> Unit) {
    val cardContext = LocalContext.current
    val cardOptions by AppPreferences.get(cardContext).options.collectAsState()
    val cardShape = RoundedCornerShape(18.dp)
    val cardSurface = MaterialTheme.colorScheme.surface
    Column(Modifier.clip(cardShape).background(cardSurface).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f), cardShape).clickable(onClick = onOpen)) {
        Box {
            VideoThumbnail(video, fallbackServer, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .then(if (video.nsfw && cardOptions.sensitiveDisplay == "blur") Modifier.blur(24.dp) else Modifier))
            if (video.nsfw && cardOptions.sensitiveDisplay != "show") Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (cardOptions.sensitiveDisplay == "warning" || android.os.Build.VERSION.SDK_INT < 31) 1f else .55f)), contentAlignment = Alignment.Center) { Text(uiText("Чувствительный контент", "Sensitive content"), Modifier.padding(16.dp)) }
            if (video.isLive || video.duration > 0) Text(if (video.isLive) stringResource(R.string.live_now) else formatDuration(video.duration), Modifier.align(Alignment.BottomEnd).padding(9.dp).clip(RoundedCornerShape(7.dp)).background(if (video.isLive) Color(0xFFC83232) else Color.Black.copy(alpha = 0.78f)).padding(horizontal = 7.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 11.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(translatedMetadata(video.name), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            if (video.channel?.name != null) IconButton(onClick = onSubscribe) { Icon(Icons.Default.Notifications, if (subscribed) stringResource(R.string.unsubscribe_channel) else stringResource(R.string.subscribe_channel), tint = if (subscribed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, stringResource(R.string.favorites), tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(video.channel?.displayName ?: video.account?.displayName.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (video.publishedAt != null) Text(formatPublishedDate(video.publishedAt), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatPublishedDate(value: String): String = runCatching {
    val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { isLenient = false }
    val date = requireNotNull(parser.parse(value.substringBefore('T')))
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(date)
}.getOrElse { value.substringBefore('T') }

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FeedRefreshContainer(
    enabled: Boolean, refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit
) {
    if (enabled) PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        content()
    } else Box(Modifier.fillMaxSize()) { content() }
}
