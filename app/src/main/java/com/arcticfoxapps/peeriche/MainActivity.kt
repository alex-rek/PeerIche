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
    internal val pipSupport by lazy { PlayerPipController(this) }
    private var playerFullscreen = false
    internal fun setPlayerFullscreen(enabled: Boolean) {
        playerFullscreen = enabled
        applyPlayerSystemBars()
    }
    private fun applyPlayerSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (playerFullscreen) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the share sheet and returning from another app can reveal
        // system bars without changing Compose's fullscreen state.
        if (hasFocus) applyPlayerSystemBars()
    }
    override fun onResume() { super.onResume(); pipSupport.onResume(); applyPlayerSystemBars() }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); pipSupport.onUserLeaveHint() }
    @androidx.annotation.RequiresApi(26)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipSupport.onModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) applyPlayerSystemBars()
    }
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(localizedAppContext(newBase)) }
    override fun onStop() {
        if (!pipSupport.windowActive && !AppPreferences.get(this).options.value.backgroundPlayback) PlaybackService.pausePlayback()
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
    val inPip = context.playerActivity()?.pipSupport?.active == true
    val options by AppPreferences.get(context).options.collectAsState()
    val wideLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600 && LocalConfiguration.current.screenWidthDp >= 600
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val database = remember { PeerIcheDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val accountManager = remember(context) { PeerTubeAccounts.get(context) }
    val accountState by accountManager.state.collectAsState()
    val selectedAccount = accountState.active
    val accountLibrary = remember(context) { AccountLibraries.get(context) }
    val accountSnapshots by accountLibrary.snapshots.collectAsState()
    val accountRevision by accountLibrary.revision.collectAsState()
    val accountSnapshot = accountSnapshots[selectedAccount?.id]
    var deviceSubscriptions by rememberSaveable(selectedAccount?.id) { mutableStateOf(false) }
    val serverSubscriptions = selectedAccount != null && !deviceSubscriptions
    var accountActionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { accountManager.initialize() } catch (_: AuthFailure) { } }
    LaunchedEffect(selectedAccount?.id, selectedAccount?.needsLogin, accountRevision) {
        selectedAccount?.let { accountLibrary.refresh(it.id) }
    }
    var accountFeed by remember(selectedAccount?.id) { mutableStateOf(emptyList<Video>()) }
    var accountFeedBusy by remember { mutableStateOf(false) }
    var accountFeedError by remember(selectedAccount?.id) { mutableStateOf<String?>(null) }
    var accountFeedStart by remember(selectedAccount?.id) { mutableIntStateOf(0) }
    var accountFeedMore by remember(selectedAccount?.id) { mutableStateOf(false) }
    var accountFeedJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var accountFeedGeneration by remember { mutableIntStateOf(0) }
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
    var browseUnsupported by remember { mutableStateOf(false) }
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
    var collectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var collectionGeneration by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedVideo by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<Video?, String>(
        save = { com.google.gson.Gson().toJson(it) },
        restore = { com.google.gson.Gson().fromJson(it, Video::class.java) }
    )) { mutableStateOf<Video?>(null) }
    var floatingPlayer by rememberSaveable { mutableStateOf(false) }
    var tabletBrowsing by rememberSaveable { mutableStateOf(false) }
    var tabletFullscreen by rememberSaveable { mutableStateOf(false) }
    var phoneFullscreen by rememberSaveable { mutableStateOf(false) }
    var inlineLandscape by rememberSaveable { mutableStateOf(false) }
    val phonePlayerFullscreen = !floatingPlayer && !wideLayout && (phoneFullscreen || (landscape && !inlineLandscape))
    LaunchedEffect(landscape) { if (!landscape) inlineLandscape = false }
    val showVideo = selectedVideo != null && !floatingPlayer && (!wideLayout || !tabletBrowsing)
    var loadingVideo by remember { mutableStateOf(false) }
    LaunchedEffect(requestedPlayback) {
        requestedPlayback?.let { requested ->
            selectedVideo = requested; tabletBrowsing = false; floatingPlayer = false
            try {
                val detail = PeerTubeClients.video(requested.sourceServer(server), requested.apiId())
                if (selectedVideo?.apiId() == requested.apiId()) selectedVideo = detail
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    var playerError by remember { mutableStateOf<String?>(null) }
    var videoLoadGeneration by remember { mutableIntStateOf(0) }
    var updateRequest by remember { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseServerOpen by remember { mutableStateOf(false) }
    var youSection by rememberSaveable { mutableStateOf(YouSection.Menu) }
    var channelVideosMode by remember { mutableStateOf(false) }
    var channelDirectoryServer by remember { mutableStateOf<String?>(null) }
    var channelVideosParentServer by remember { mutableStateOf<String?>(null) }
    var serverChannelList by remember { mutableStateOf(emptyList<PeerTubeChannel>()) }
    val favoriteRows by database.library().observe("favorite").collectAsState(initial = emptyList())
    val historyRows by database.library().observe("history").collectAsState(initial = emptyList())
    val subscriptionRows by database.library().observe("subscription").collectAsState(initial = emptyList())
    val effectiveSubscriptions = if (serverSubscriptions) accountSnapshot?.subscriptions.orEmpty().map { it.subscriptionRow(selectedAccount!!.server) } else subscriptionRows
    val favorites = remember(favoriteRows) { favoriteRows.map { it.identity }.toSet() }

    DisposableEffect(showVideo, phonePlayerFullscreen, tabletFullscreen, floatingPlayer) {
        val window = (context as? ComponentActivity)?.window
        if (showVideo || floatingPlayer) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val immersivePlayer = showVideo && !inPip && (phonePlayerFullscreen || (wideLayout && tabletFullscreen))
    DisposableEffect(immersivePlayer) {
        context.playerActivity()?.setPlayerFullscreen(immersivePlayer)
        onDispose { context.playerActivity()?.setPlayerFullscreen(false) }
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
        browseUnsupported = false
        browseJob = scope.launch {
            try {
                val response = if (destination == "Server") PeerTubeClients.serverPage(server, pageToLoad, options.includeSensitive)
                    else PeerTubeClients.livePage(server, pageToLoad, options.includeSensitive)
                if (generation == browseGeneration) {
                    val previous = if (destination == "Server") serverVideos else liveVideos
                    val collected = if (pageToLoad == 0) response.videos else deduplicateVideos(previous + response.videos, server)
                    if (destination == "Server") serverVideos = collected else liveVideos = collected
                    browsePage = (response.nextPage ?: (pageToLoad + 1)) - 1
                    browseHasMore = response.hasMore
                    if (collected.isEmpty()) browseError = context.getString(if (destination == "Live") {
                        if (response.hasMore) R.string.live_more_results else R.string.no_live_videos
                    } else R.string.no_results)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: NotPeerTubeServerException) { if (generation == browseGeneration) {
                browseUnsupported = true
                browseError = context.getString(R.string.server_not_peertube)
            } }
            catch (_: Exception) { if (generation == browseGeneration) browseError = context.getString(if (destination == "Server") R.string.server_feed_failed else R.string.live_feed_failed) }
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
    fun loadAccountFeed(append: Boolean = false) {
        val owner = selectedAccount ?: return
        val generation = ++accountFeedGeneration
        accountFeedJob?.cancel(); accountFeedBusy = true; accountFeedError = null
        val start = if (append) accountFeedStart else 0
        accountFeedJob = scope.launch {
            try {
                val response = accountLibrary.feed(owner.id, start, options.includeSensitive)
                if (generation == accountFeedGeneration && accountManager.state.value.activeId == owner.id) {
                    accountFeed = if (append) deduplicateVideos(accountFeed + response.data, owner.server) else response.data
                    accountFeedStart = start + response.data.size
                    accountFeedMore = response.data.isNotEmpty() && accountFeedStart < response.total
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == accountFeedGeneration) accountFeedError = accountErrorCode(failure) }
            finally { if (generation == accountFeedGeneration) accountFeedBusy = false }
        }
    }
    fun cancelCollection() { collectionGeneration++; collectionJob?.cancel(); busy = false }
    fun loadSubscriptions(rows: List<SavedVideo> = subscriptionRows) {
        cancelCollection()
        val generation = collectionGeneration
        busy = true; error = null
        collectionJob = scope.launch {
            try {
                val collected = fetchSubscribedVideos(rows)
                if (generation == collectionGeneration) videos = deduplicateVideos(collected, server)
            } finally { if (generation == collectionGeneration) busy = false }
        }
    }
    fun openChannelVideos(channel: PeerTubeChannel) {
        cancelCollection()
        val generation = collectionGeneration
        channelVideosParentServer = channelDirectoryServer
        channelDirectoryServer = null; youSection = YouSection.Channels; page = "You"
        channelVideosMode = true; busy = true; error = null
        collectionJob = scope.launch {
            try {
                val host = channel.host?.let { "https://$it" } ?: server
                val found = PeerTubeClients.server(host).channelVideos(channel.name).data
                if (generation == collectionGeneration) { videos = found; if (found.isEmpty()) error = context.getString(R.string.no_results) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == collectionGeneration) error = failure.message ?: context.getString(R.string.search_failed) }
            finally { if (generation == collectionGeneration) busy = false }
        }
    }
    fun openServerChannels(host: String) {
        cancelCollection()
        val generation = collectionGeneration
        channelDirectoryServer = host; channelVideosMode = false; busy = true; error = null; serverChannelList = emptyList()
        collectionJob = scope.launch {
            try {
                val found = PeerTubeClients.allChannelsWithVideoCounts(host)
                if (generation == collectionGeneration) { serverChannelList = found; if (found.isEmpty()) error = context.getString(R.string.no_channels) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == collectionGeneration) error = failure.message ?: context.getString(R.string.channels_load_failed) }
            finally { if (generation == collectionGeneration) busy = false }
        }
    }
    fun navigateTo(destination: String) {
        val same = page == destination
        cancelCollection()
        page = destination
        channelVideosMode = false; channelDirectoryServer = null; channelVideosParentServer = null; error = null
        if (destination == "You") youSection = YouSection.Menu
        if (destination == "Home") loadHome()
        if (same && destination == "Subscriptions") { if (serverSubscriptions) loadAccountFeed() else loadSubscriptions() }
    }
    fun openYouSection(section: YouSection) {
        cancelCollection()
        youSection = section
        channelVideosMode = false; channelDirectoryServer = null; channelVideosParentServer = null; error = null
    }
    fun backFromYou() {
        cancelCollection()
        when {
            channelDirectoryServer != null -> { channelDirectoryServer = null; serverChannelList = emptyList() }
            channelVideosMode -> {
                channelVideosMode = false
                channelVideosParentServer?.let { openServerChannels(it) }
                channelVideosParentServer = null
            }
            else -> youSection = YouSection.Menu
        }
        error = null
    }
    fun setPhoneFullscreen(enabled: Boolean) {
        phoneFullscreen = enabled
        inlineLandscape = !enabled && landscape
    }
    fun minimizePlayer() {
        phoneFullscreen = false; inlineLandscape = true; tabletFullscreen = false
        floatingPlayer = true
    }
    fun expandPlayer() {
        floatingPlayer = false; tabletBrowsing = false; inlineLandscape = false
    }
    fun closePlayer() {
        floatingPlayer = false
        context.playerActivity()?.pipSupport?.disable()
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
        floatingPlayer = false
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
        val owner = selectedAccount
        val wanted = effectiveSubscriptions.none { it.identity == row.identity }
        scope.launch {
            if (serverSubscriptions) {
                try {
                    val account = owner ?: throw AuthFailure("session_expired")
                    val handle = video.accountChannelHandle(server)
                    val channel = PeerTubeChannel(video.channel?.displayName.orEmpty(), handle.substringBeforeLast('@'), handle.substringAfterLast('@'))
                    accountLibrary.setSubscribed(account.id, channel, wanted)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { accountActionError = accountErrorCode(failure) }
            } else {
                if (subscriptionRows.any { it.identity == row.identity }) database.library().delete(row.identity, "subscription")
                else database.library().put(row)
            }
        }
    }
    fun unsubscribeAccountChannel(channel: PeerTubeChannel) {
        val owner = selectedAccount ?: return
        scope.launch {
            try { accountLibrary.setSubscribed(owner.id, channel, false) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { accountActionError = accountErrorCode(failure) }
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
    LaunchedEffect(server) { homeJob?.cancel(); homeJob?.join(); if (page == "Home") loadHome() else if (page == "Search") searchNow()  }
    LaunchedEffect(page, serverSubscriptions, selectedAccount?.id, selectedAccount?.needsLogin, accountRevision, options.includeSensitive, server) {
        if (serverSubscriptions && page in listOf("Home", "Subscriptions")) loadAccountFeed()
        else {
            accountFeedGeneration++; accountFeedJob?.cancel(); accountFeedBusy = false
            if (page == "Subscriptions") loadSubscriptions()
        }
    }
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
        "Subscriptions" -> subscriptionsGridState
        else -> when (youSection) {
            YouSection.Channels -> channelGridState
            YouSection.Favorites -> favoritesGridState
            else -> historyGridState
        }
    }
    val feedScrollConnection = rememberFeedScrollConnection(!wideLayout && !showVideo && page != "You", activeFeedState) { feedPanelsVisible = it }
    LaunchedEffect(page, query, youSection, wideLayout) { feedPanelsVisible = true }
    val canNavigateBack = showVideo || channelDirectoryServer != null || channelVideosMode ||
        page != "Home"
    BackHandler(enabled = canNavigateBack) {
        when {
            !wideLayout && showVideo && phonePlayerFullscreen -> setPhoneFullscreen(false)
            wideLayout && tabletFullscreen -> tabletFullscreen = false
            wideLayout && showVideo -> tabletBrowsing = true
            selectedVideo != null && !wideLayout && !floatingPlayer -> closePlayer()
            page == "You" && youSection != YouSection.Menu -> backFromYou()
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
        if (inPip && selectedVideo != null) {
            BackgroundVideoPlayer(selectedVideo!!, server, Modifier.fillMaxSize(), pip = true)
            return@MaterialTheme
        }
        AppUpdateFeature(updateRequest)
        val colors = MaterialTheme.colorScheme
        CompositionLocalProvider(LocalMetadataTranslationPaused provides settingsOpen, LocalServerSubscriptions provides serverSubscriptions) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = colors.background, bottomBar = {
            if (wideLayout && !showVideo && !floatingPlayer && selectedVideo != null && !loadingVideo) BackgroundVideoPlayer(selectedVideo!!, server, Modifier.fillMaxWidth(), compact = true,
                onExpand = { tabletBrowsing = false }, onClose = ::closePlayer)
            if (!showVideo && !wideLayout) FeedPanel(feedPanelsVisible, top = false) { NavigationBar(containerColor = colors.surface, tonalElevation = 8.dp) {
                listOf("Home", "Search", "Subscriptions", "You").forEach { destination ->
                    val (iconId, labelId) = when (destination) {
                        "Home" -> R.drawable.ic_nav_home to R.string.home
                        "Search" -> R.drawable.ic_nav_search to R.string.search
                        "Subscriptions" -> R.drawable.ic_nav_subscriptions to R.string.subscriptions
                        else -> R.drawable.ic_nav_you to R.string.you
                    }
                    NavigationBarItem(selected = page == destination || (destination == "Home" && page in listOf("Server", "Live")), onClick = {
                        navigateTo(destination)
                    }, icon = { Icon(painterResource(iconId), stringResource(labelId)) }, label = { Text(stringResource(labelId), fontWeight = if (page == destination) FontWeight.SemiBold else FontWeight.Normal) })
                }
            }
            }
        }) { insets ->
            Row(Modifier.fillMaxSize().padding(insets)) {
                if (wideLayout && !tabletFullscreen) TabletNavigation(page, collapsed = showVideo || LocalConfiguration.current.screenWidthDp < 840) { destination ->
                    tabletBrowsing = selectedVideo != null
                    navigateTo(destination)
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
                                DropdownMenuItem(text = { Text(stringResource(R.string.choose_peer_server)) }, onClick = { chooseServerOpen = false; settingsSection = "servers"; settingsOpen = true })
                            }
                        }
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
                                    navigateTo(destination)
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
                    if (page == "Subscriptions") SubscriptionSourceMenu(selectedAccount, deviceSubscriptions,
                        onDevice = { deviceSubscriptions = it }, onRefresh = {
                            if (serverSubscriptions) { loadAccountFeed(); selectedAccount?.let { scope.launch { accountLibrary.refresh(it.id) } } }
                            else loadSubscriptions()
                        })
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
                    if (page == "You" && youSection != YouSection.Menu) YouSectionHeader(
                        title = when {
                            channelVideosMode -> stringResource(R.string.channel_videos)
                            channelDirectoryServer != null -> stringResource(R.string.channels_on_server)
                            youSection == YouSection.Channels -> stringResource(R.string.channels)
                            youSection == YouSection.Favorites -> stringResource(R.string.favorites)
                            youSection == YouSection.Videos -> uiText("Ваши видео", "Your videos")
                            else -> stringResource(R.string.history)
                        }, onBack = ::backFromYou)
                    } }
                    val browsingFeed = page == "Server" || page == "Live"
                    val watchedIds = remember(historyRows) { historyRows.map { it.identity.substringBefore('|') }.toSet() }
                    val homeFollowing = (if (serverSubscriptions) accountFeed else subscriptionFeed).filterNot { it.apiId() in watchedIds }
                    val homeRecommended = recommendedVideos.filterNot { it.apiId() in watchedIds }
                    val homeRecent = homeVideos.filterNot { it.apiId() in watchedIds }
                    val visibleVideos = when (page) {
                        "Server" -> serverVideos
                        "Live" -> liveVideos
                        "Subscriptions" -> if (serverSubscriptions) accountFeed else videos
                        "You" -> when (youSection) {
                            YouSection.Favorites -> favoriteRows.map(SavedVideo::toVideo)
                            YouSection.History -> historyRows.map(SavedVideo::toVideo)
                            YouSection.Channels -> if (channelVideosMode) videos else emptyList()
                            YouSection.Menu -> emptyList()
                            YouSection.Videos -> emptyList()
                        }
                        else -> videos
                    }
                    val showFeedStatus = page != "You" || (youSection == YouSection.Channels && (channelVideosMode || channelDirectoryServer != null))
                    val serverSubscriptionFeed = page == "Subscriptions" && serverSubscriptions
                    if (showFeedStatus && (if (serverSubscriptionFeed) accountFeedBusy else if (browsingFeed) browseBusy else busy)) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = colors.primary, trackColor = colors.surface)
                    if (serverSubscriptionFeed && accountFeedError != null) {
                        Text(accountErrorText(accountFeedError!!), Modifier.padding(16.dp), color = colors.error)
                        TextButton(onClick = { loadAccountFeed() }) { Text(stringResource(R.string.retry)) }
                    }
                    if (serverSubscriptionFeed && !accountFeedBusy && accountFeedError == null && accountFeed.isEmpty()) Text(uiText("В подписках аккаунта пока нет видео", "No videos in this account's subscriptions yet"), Modifier.padding(16.dp))
                    (if (!showFeedStatus || serverSubscriptionFeed) null else if (browsingFeed) browseError else error)?.let { Text(it, Modifier.padding(18.dp), color = if (darkTheme) Color(0xFFFFB49B) else Color(0xFF9C341F)) }
                    if (browsingFeed && browseError != null && !browseBusy) Row {
                        if (!browseUnsupported) TextButton(onClick = { loadBrowse(if (browseHasMore) browsePage + 1 else 0) }) {
                            Text(stringResource(if (page == "Live" && browseHasMore) R.string.find_more_live else R.string.retry))
                        }
                        if (page == "Server") TextButton(onClick = { settingsSection = "servers"; settingsOpen = true }) { Text(stringResource(R.string.choose_peer_server)) }
                    }
                    if (page == "You" && youSection == YouSection.Menu) {
                        YouPage(server, onOpenSection = ::openYouSection, onSettings = { settingsSection = null; settingsOpen = true },
                            onAccountServer = { host -> scope.launch { useAccountServer(context, host) } })
                    } else if (page == "You" && youSection == YouSection.Videos) {
                        CreatorPage(onOpenVideo = ::openVideo)
                    } else if (page == "You" && youSection == YouSection.Channels && !channelVideosMode) {
                        if (channelDirectoryServer == null) AccountChannelsPage(selectedAccount, accountSnapshot, subscriptionRows, server,
                            onOpen = ::openChannelVideos, onBrowse = ::openServerChannels,
                            onRefresh = { selectedAccount?.let { scope.launch { accountLibrary.refresh(it.id) } } }, onUnsubscribe = ::unsubscribeAccountChannel)
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            serverChannelList.forEach { channel ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { openChannelVideos(channel) }, modifier = Modifier.weight(1f)) { Text(channel.displayName.ifBlank { channel.name }, maxLines = 2) }
                                    Text("${channel.videosCount ?: 0} · ${channel.host.orEmpty()}", color = colors.onSurfaceVariant)
                                }
                                HorizontalDivider(color = colors.outlineVariant)
                            }
                        }
                    } else if (!showVideo) FeedRefreshContainer(
                        enabled = page == "Home" && !wideLayout,
                        refreshing = homeRefreshing,
                        onRefresh = { if (!busy) { loadHome(); if (serverSubscriptions) { loadAccountFeed(); selectedAccount?.let { scope.launch { accountLibrary.refresh(it.id) } } } } }
                    ) {
                    if (page == "You" && visibleVideos.isEmpty() && youSection in listOf(YouSection.Favorites, YouSection.History)) {
                        Text(if (youSection == YouSection.Favorites) uiText("В избранном пока нет видео", "No saved videos yet") else uiText("История пока пуста", "No watch history yet"), Modifier.padding(20.dp), color = colors.onSurfaceVariant)
                    }
                    LazyVerticalGrid(state = activeFeedState, modifier = Modifier.nestedScroll(feedScrollConnection), columns = if (wideLayout) GridCells.Fixed(tabletColumns(LocalConfiguration.current.screenWidthDp)) else GridCells.Adaptive(minSize = 230.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (page == "Home" && options.homeSort != "sections") {
                            videoSection(null, sortHomeVideos(homeFollowing + homeRecommended + homeRecent, options.homeSort, server), "home", server, favorites, effectiveSubscriptions.map { it.identity }.toSet(), ::openVideo,
                                { video -> toggleCurrentVideoFavorite(video) },
                                { video -> toggleCurrentVideoSubscription(video) })
                        } else if (page == "Home") {
                            if (homeFollowing.isNotEmpty()) videoSection(R.string.from_subscriptions, homeFollowing, "following", server, favorites, effectiveSubscriptions.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleCurrentVideoSubscription(video) })
                            if (homeRecommended.isNotEmpty()) videoSection(if (recommendationsUseHistory) R.string.recommended_for_you else R.string.popular_now, homeRecommended, "recommended", server, favorites, effectiveSubscriptions.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleCurrentVideoSubscription(video) })
                            videoSection(R.string.latest_videos, homeRecent, "latest", server, favorites, effectiveSubscriptions.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleCurrentVideoSubscription(video) })
                        } else {
                            videoSection(null, visibleVideos, page.lowercase(), server, favorites, effectiveSubscriptions.map { it.identity }.toSet(), ::openVideo,
                                { video -> scope.launch { val id = video.uuid; if (id in favorites) database.library().delete(id, "favorite") else database.library().put(video.toSavedVideo(server, "favorite")) } },
                                { video -> toggleCurrentVideoSubscription(video) })
                        }
                        if (page == "Subscriptions" && serverSubscriptions && accountFeedMore) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { loadAccountFeed(append = true) }, enabled = !accountFeedBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                        }
                        if (page == "Search" && hasMoreResults) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { searchNow(resultPage + 1) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (busy) R.string.loading_more else R.string.load_more))
                            }
                        }
                        if (browsingFeed && browseHasMore && (visibleVideos.isNotEmpty() || browseError == null)) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { loadBrowse(browsePage + 1) }, enabled = !browseBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (browseBusy) R.string.loading_more else R.string.load_more))
                            }
                        }
                    }
                    }
                } else if (wideLayout) {
                    val current = selectedVideo!!
                    val next = deduplicateVideos(recommendedVideos + homeVideos + serverVideos + liveVideos + videos + subscriptionFeed, server)
                        .filterNot { it.apiId() == current.apiId() && it.sourceServer(server) == current.sourceServer(server) }.take(20)
                    TabletVideoScreen(current, server, loadingVideo, playerError, next, tabletFullscreen,
                        current.apiId() in favorites, effectiveSubscriptions.any { it.identity == current.subscriptionIdentity(server) },
                        onBrowse = { tabletFullscreen = false; tabletBrowsing = true },
                        onFullscreen = { tabletFullscreen = !tabletFullscreen }, onOpen = ::openVideo,
                        onFavorite = { toggleCurrentVideoFavorite(current) }, onSubscribe = { toggleCurrentVideoSubscription(current) },
                        onChannel = { current.channel?.name?.let { handle -> openChannelVideos(PeerTubeChannel(current.channel.displayName ?: handle, handle, current.sourceServer(server).removePrefix("https://"))); closePlayer() } },
                        onRetry = { openVideo(current) }, onMiniPlayer = ::minimizePlayer)
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
                            fullscreen = phonePlayerFullscreen, onFullscreenToggle = { setPhoneFullscreen(!phonePlayerFullscreen) },
                            onBack = { if (phonePlayerFullscreen) setPhoneFullscreen(false) else closePlayer() }, onMiniPlayer = ::minimizePlayer)
                        else -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(playerError ?: stringResource(R.string.video_unavailable), color = colors.onBackground)
                            TextButton(onClick = { openVideo(video) }) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    if (!phonePlayerFullscreen) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        WatchDetails(video, server, video.apiId() in favorites, effectiveSubscriptions.any { it.identity == video.subscriptionIdentity(server) },
                            onFavorite = { toggleCurrentVideoFavorite(video) }, onSubscribe = { toggleCurrentVideoSubscription(video) },
                            onChannel = { video.channel?.name?.let { handle -> openChannelVideos(PeerTubeChannel(video.channel.displayName ?: handle, handle, video.sourceServer(server).removePrefix("https://"))); closePlayer() } })
                        val next = deduplicateVideos(recommendedVideos + homeVideos + serverVideos + liveVideos + videos + subscriptionFeed, server)
                            .filterNot { it.apiId() == video.apiId() && it.sourceServer(server) == video.sourceServer(server) }.take(20)
                        if (next.isNotEmpty()) Text(uiText("Далее", "Up next"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        next.forEach { item -> NextVideoRow(item, server) { openVideo(item) } }
                    }
                }
                }
            }
        }
        }
        if (floatingPlayer && selectedVideo != null && !loadingVideo) FloatingVideoPlayer(selectedVideo!!, server, ::expandPlayer, ::closePlayer)
        }
        accountActionError?.let { reason -> AlertDialog(onDismissRequest = { accountActionError = null },
            text = { Text(accountErrorText(reason)) }, confirmButton = { TextButton(onClick = { accountActionError = null }) { Text("OK") } }) }
        if (settingsOpen) SettingsScreen(onClose = { settingsOpen = false; settingsSection = null }, onCheckUpdates = { updateRequest++ }, initialSection = settingsSection)

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
