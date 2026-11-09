package com.arcticfoxapps.peeriche

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun CreatePage(onClose: () -> Unit, onVideos: () -> Unit) {
    val context = LocalContext.current
    val accounts = remember { PeerTubeAccounts.get(context) }
    val accountState by accounts.state.collectAsState()
    val owner = accountState.active?.takeUnless { it.needsLogin }
    if (owner == null) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.ui_sign_in_on_the_you_page_to_upload_videos_a_38f091))
            TextButton(onClick = onClose) { Text(uiText(R.string.ui_home_9dd0b3)) }
        }; return
    }
    key(owner.id) { CreationHub(owner, accounts, onClose, onVideos) }
}

@Composable private fun CreationHub(owner: AccountSummary, accounts: AccountSessionManager, onClose: () -> Unit, onVideos: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CreatorRepository(accounts) }
    val preferences = remember { BroadcastPreferences(context) }
    val controller = remember { Broadcasts.get(context) }
    val stream by controller.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf("live") }
    var capabilities by remember { mutableStateOf<CreatorCapabilities?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner.id, retry) {
        error = null
        try { capabilities = repository.capabilities(owner.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
    }
    Column(Modifier.fillMaxSize()) {
        if (mode != "live") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (stream.busy) mode = "live" else onClose() }) { Icon(if (stream.busy) Icons.Default.Videocam else Icons.Default.Close, uiText(R.string.ui_close_641636)) }
            Text(uiText(R.string.ui_create_514e39), style = MaterialTheme.typography.titleMedium)
        }
        error?.let { Text(broadcastErrorText(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text(uiText(R.string.ui_retry_e8368b)) } }
        if (capabilities == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                "upload" -> UploadCreationPage(owner, capabilities, repository, onVideos)
                "settings" -> BroadcastChannelSettings(owner, capabilities, repository, preferences, controller) { retry++ }
                else -> PhoneBroadcastPage(owner, capabilities, repository, preferences, controller, onClose, onSettings = { mode = "settings" })
            }
        }
        if (stream.busy && mode != "live") TextButton(onClick = { mode = "live" }, modifier = Modifier.fillMaxWidth()) { Text(uiText(R.string.ui_return_to_broadcast_fef56e)) }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("upload" to uiText(R.string.ui_upload_video_504c7b), "live" to uiText(R.string.ui_broadcast_84c258), "settings" to uiText(R.string.ui_channel_settings_079a60)).forEach { (id, label) ->
                    FilterChip(selected = mode == id, onClick = { mode = id }, label = { Text(label, maxLines = 1) })
                }
            }
        }
    }
    BackHandler(stream.busy && mode != "live") { mode = "live" }
}

@Composable private fun UploadCreationPage(owner: AccountSummary, capabilities: CreatorCapabilities?, repository: CreatorRepository, onVideos: () -> Unit) {
    val context = LocalContext.current
    val uploads = remember { VideoUploads.get(context) }
    val draft by uploads.state.collectAsState()
    var editor by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { uploads.initialize() } catch (failure: Exception) { error = accountErrorCode(failure) } }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.VideoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(uiText(R.string.ui_upload_video_504c7b), style = MaterialTheme.typography.headlineSmall)
        Text(uiText(R.string.ui_choose_a_video_on_your_device_its_channel_7a7d31))
        Button(enabled = capabilities != null && draft == null, onClick = { editor = true }) { Text(uiText(R.string.ui_choose_video_71bb7b)) }
        draft?.let { task ->
            Text(if (task.accountId == owner.id) task.metadata.name else uiText(R.string.ui_another_account_has_an_upload_task_ce4ce5))
            if (task.accountId == owner.id) LinearProgressIndicator(progress = { (task.offset.toDouble() / task.file.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(creatorErrorText(it), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onVideos) { Text(uiText(R.string.ui_your_videos_and_upload_tasks_65de76)) }
    }
    if (editor && capabilities != null) CreatorEditor(owner, capabilities, false, uploads, repository, onCreated = { editor = false; onVideos() }, onClose = { editor = false })
}

@Composable private fun PhoneBroadcastPage(owner: AccountSummary, capabilities: CreatorCapabilities?, repository: CreatorRepository, preferences: BroadcastPreferences,
    controller: BroadcastCoordinator, onClose: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val compact = LocalConfiguration.current.screenHeightDp < 500
    val stream by controller.state.collectAsState()
    var allowed by remember { mutableStateOf(controller.permissionGranted()) }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = controller.permissionGranted(); permissionRequested = true
        if (allowed && !stream.busy) controller.preview(owner)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val titleSuffix = uiText(R.string.ui_is_live_20cabe)
    var title by rememberSaveable(owner.id) { mutableStateOf((owner.displayName + titleSuffix).take(120)) }
    var description by rememberSaveable(owner.id) { mutableStateOf("") }
    var privacy by rememberSaveable(owner.id) { mutableIntStateOf(3) }
    var replay by rememberSaveable(owner.id) { mutableStateOf(true) }
    var channelId by rememberSaveable(owner.id) { mutableLongStateOf(preferences.get(owner.id).channelId) }
    var selectedUuid by rememberSaveable(owner.id) { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var lives by remember { mutableStateOf(emptyList<CreatorVideo>()) }
    var livesError by remember { mutableStateOf<String?>(null) }
    var refreshLives by remember { mutableIntStateOf(0) }
    LaunchedEffect(capabilities) { if (capabilities?.channels?.none { it.id == channelId } == true) channelId = capabilities.channels.firstOrNull()?.id ?: 0 }
    LaunchedEffect(stream.uuid, stream.phase, owner.id) {
        resumableBroadcastUuid(stream, owner.id)?.let { selectedUuid = it }
        if (stream.accountId == owner.id && stream.phase == "stopped") selectedUuid = null
    }
    LaunchedEffect(refreshLives) {
        try { lives = repository.videos(owner.id, 0).rows.filter { it.video.isLive && it.state == 4 }; livesError = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { livesError = accountErrorCode(failure) }
    }
    DisposableEffect(lifecycle, owner.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                allowed = controller.permissionGranted()
                if (allowed && !controller.state.value.busy) controller.preview(owner)
            }
            if (event == Lifecycle.Event.ON_STOP && !controller.state.value.busy) controller.detach()
        }
        lifecycle.lifecycle.addObserver(observer)
        if (allowed && !controller.state.value.busy) controller.preview(owner)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.detach() }
    }
    BackHandler(stream.busy) { confirmStop = true }
    var elapsed by remember { mutableLongStateOf(0) }
    LaunchedEffect(stream.startedAt, stream.phase) { while (stream.phase == "live") { elapsed = (SystemClock.elapsedRealtime() - stream.startedAt) / 1000; delay(1000) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) AndroidView(factory = { ctx -> SurfaceView(ctx).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { controller.attach(holder.surface, width, height) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { controller.attach(holder.surface, width, height) }
                override fun surfaceDestroyed(holder: SurfaceHolder) { controller.detach() }
            })
        } }, modifier = Modifier.fillMaxSize())
        if (!allowed) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Videocam, null, Modifier.size(56.dp), tint = Color.White)
            Text(uiText(R.string.ui_camera_and_microphone_access_384ace), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text(uiText(R.string.ui_allow_ea9723)) }
            if (permissionRequested) TextButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
            }) { Text(uiText(R.string.ui_android_permission_settings_d74962)) }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BroadcastOverlayButton(Icons.Default.Close, uiText(R.string.ui_close_641636)) { if (stream.busy) confirmStop = true else onClose() }
            if (stream.watchUrl != null) BroadcastOverlayButton(Icons.Default.Share, uiText(R.string.ui_share_live_23cbbf)) {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, stream.watchUrl), null))
            }
        }
        val controls: @Composable () -> Unit = {
            BroadcastControl(Icons.Default.Cameraswitch, uiText(R.string.ui_switch_camera_515854), expanded, enabled = allowed && !stream.options.cameraOff) { controller.camera() }
            BroadcastControl(if (stream.options.muted) Icons.Default.MicOff else Icons.Default.Mic, uiText(if (stream.options.muted) R.string.ui_enable_microphone_79e3a9 else R.string.ui_mute_microphone_877493), expanded, allowed) { controller.mute() }
            BroadcastControl(if (stream.options.cameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam, uiText(if (stream.options.cameraOff) R.string.ui_enable_camera_3b9a3f else R.string.ui_hide_camera_0122c1), expanded, allowed) { controller.hideCamera() }
            BroadcastControl(Icons.Default.ScreenRotation, uiText(R.string.ui_orientation_ae6af6) + if (stream.options.portrait) uiText(R.string.ui_portrait_c5160e) else uiText(R.string.ui_landscape_69a703), expanded, allowed) { controller.orientation() }
            if (!stream.options.front && !stream.options.cameraOff) BroadcastControl(if (stream.torch) Icons.Default.FlashOn else Icons.Default.FlashOff, uiText(R.string.ui_light_ce5d5c), expanded, allowed) { controller.torch() }
            BroadcastControl(Icons.Default.Settings, uiText(R.string.ui_settings_23de1b), expanded) { onSettings() }
            BroadcastOverlayButton(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, uiText(R.string.ui_control_labels_e1ef00)) { expanded = !expanded }
        }
        if (compact) Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 64.dp, end = 64.dp).horizontalScroll(rememberScrollState()).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(28.dp)), verticalAlignment = Alignment.CenterVertically) { controls() }
        else Column(Modifier.align(Alignment.CenterEnd).padding(10.dp).heightIn(max = 380.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(28.dp)).verticalScroll(rememberScrollState()).padding(4.dp), horizontalAlignment = Alignment.End) { controls() }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .62f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (stream.busy) stream.title else title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    if (!stream.busy) Text(if (selectedUuid == null) visibilityName(privacy) else uiText(R.string.ui_existing_live_selected_b99798), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    if (stream.actualWidth > 0 && stream.actualHeight > 0) Text(
                        "${stream.actualWidth} × ${stream.actualHeight} · " + if (stream.options.portrait) uiText(R.string.ui_portrait_frame_6adb4d) else uiText(R.string.ui_landscape_frame_36005f),
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Text(if (stream.phase == "live") "● LIVE · ${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')} · ${stream.bitrate / 1000} kb/s"
                        else if (stream.phase == "creating") uiText(R.string.ui_preparing_live_adb70d) else if (stream.phase == "connecting") uiText(R.string.ui_connecting_6da59b)
                        else uiText(R.string.ui_preview_not_live_b602f3), color = if (stream.phase == "live") Color(0xFFFF8A70) else Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(enabled = !stream.busy, onClick = { details = true }) { Icon(Icons.Default.Edit, uiText(R.string.ui_title_and_visibility_b072fd), tint = Color.White) }
            }
            if (capabilities?.live == false) Text(uiText(R.string.ui_this_server_disables_live_streaming_local_fcce38), color = Color.White, style = MaterialTheme.typography.bodySmall)
            stream.error?.let { Text(broadcastErrorText(it), color = Color(0xFFFFC0A8), style = MaterialTheme.typography.bodySmall, maxLines = 3) }
            if (stream.busy) Button(onClick = { confirmStop = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB62B29)), modifier = Modifier.fillMaxWidth()) { Text(uiText(R.string.ui_end_broadcast_4e87f9)) }
            else {
                Button(enabled = allowed && capabilities?.live == true && channelId > 0 && title.trim().length in 3..120, onClick = {
                    if (controller.state.value.phase != "preview") controller.preview(owner)
                    else {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        controller.begin(CreatorMetadata(title, description, channelId, privacy), replay && capabilities?.replay == true, selectedUuid)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(uiText(if (stream.phase == "preview") R.string.ui_start_broadcast_ca85c4 else R.string.ui_enable_preview_5b929b)) }
            }
        }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(uiText(R.string.ui_end_broadcast_6bfccc)) },
        text = { Text(uiText(R.string.ui_camera_and_microphone_transmission_will_st_82b4a0)) },
        confirmButton = { TextButton(onClick = { controller.stop(); confirmStop = false; onClose() }) { Text(uiText(R.string.ui_end_18118d)) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(uiText(R.string.ui_keep_streaming_f81bca)) } })
    if (details && capabilities != null) BroadcastDetails(capabilities, lives, livesError, title, description, channelId, privacy, replay, selectedUuid,
        onRefresh = { refreshLives++ }, onSave = { name, desc, channel, access, saveReplay, uuid ->
            title = name; description = desc; channelId = channel; privacy = access; replay = saveReplay; selectedUuid = uuid
            preferences.save(owner.id, preferences.get(owner.id).copy(channelId = channel)); details = false
        }, onClose = { details = false })
}

@Composable private fun BroadcastOverlayButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.background(Color.Black.copy(alpha = .4f), RoundedCornerShape(24.dp))) { Icon(icon, label, tint = if (enabled) Color.White else Color.Gray) }
}
@Composable private fun BroadcastControl(icon: ImageVector, label: String, expanded: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { if (expanded) Text(label, color = Color.White, modifier = Modifier.padding(start = 10.dp, end = 6.dp), style = MaterialTheme.typography.bodySmall); BroadcastOverlayButton(icon, label, enabled, onClick) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BroadcastDetails(caps: CreatorCapabilities, lives: List<CreatorVideo>, loadError: String?, title: String, description: String, channel: Long, privacy: Int, replay: Boolean, uuid: String?,
    onRefresh: () -> Unit, onSave: (String, String, Long, Int, Boolean, String?) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(title) }; var desc by remember { mutableStateOf(description) }; var selectedChannel by remember { mutableLongStateOf(channel) }
    var access by remember { mutableIntStateOf(privacy) }; var saveReplay by remember { mutableStateOf(replay && caps.replay) }; var selectedLive by remember { mutableStateOf(uuid) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(uiText(R.string.ui_broadcast_details_fb7ead), style = MaterialTheme.typography.titleLarge)
            OptionPicker(uiText(R.string.ui_live_13cd53), selectedLive?.let { id -> lives.firstOrNull { it.video.apiId() == id }?.video?.name ?: uiText(R.string.ui_created_live_7a0340) } ?: uiText(R.string.ui_new_live_57d293),
                listOf<String?>(null) + lives.map { it.video.apiId() }, { id -> id?.let { lives.firstOrNull { item -> item.video.apiId() == it }?.video?.name }.orEmpty().ifBlank { uiText(R.string.ui_new_live_57d293) } }) { id -> selectedLive = id; lives.firstOrNull { it.video.apiId() == id }?.let { name = it.video.name } }
            TextButton(onClick = onRefresh) { Text(uiText(R.string.ui_refresh_waiting_lives_e1b4e8)) }
            loadError?.let { Text(creatorErrorText(it), style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(name, { name = it }, enabled = selectedLive == null, label = { Text(uiText(R.string.ui_title_1b6233)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, enabled = selectedLive == null, label = { Text(uiText(R.string.ui_description_70bc2e)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            OptionPicker(uiText(R.string.ui_channel_c07a24), caps.channels.firstOrNull { it.id == selectedChannel }?.title.orEmpty(), caps.channels.map { it.id }, { id -> caps.channels.first { it.id == id }.title }, enabled = selectedLive == null) { selectedChannel = it }
            OptionPicker(uiText(R.string.ui_visibility_cb41fd), visibilityName(access), listOf(3, 2, 1), { visibilityName(it) }, enabled = selectedLive == null) { access = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(saveReplay, { saveReplay = it }, enabled = caps.replay && selectedLive == null); Text(uiText(R.string.ui_save_replay_on_server_c7d724)) }
            if (selectedLive != null) Text(uiText(R.string.ui_existing_live_streams_keep_their_server_se_ece468), style = MaterialTheme.typography.bodySmall)
            Button(enabled = name.trim().length in 3..120 && desc.length <= 10000 && selectedChannel > 0, onClick = { onSave(name, desc, selectedChannel, access, saveReplay, selectedLive) }, modifier = Modifier.fillMaxWidth()) { Text(uiText(R.string.ui_done_76ab9d)) }
        }
    }
}

@Composable private fun BroadcastChannelSettings(owner: AccountSummary, capabilities: CreatorCapabilities?, repository: CreatorRepository, preferences: BroadcastPreferences, controller: BroadcastCoordinator, onUpdated: () -> Unit) {
    val scope = rememberCoroutineScope(); val stream by controller.state.collectAsState()
    var options by remember { mutableStateOf(preferences.get(owner.id)) }; var channel by remember { mutableLongStateOf(options.channelId) }
    var title by remember { mutableStateOf("") }; var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var saved by remember { mutableStateOf(false) }
    var channelGeneration by remember { mutableIntStateOf(0) }
    var customImage by remember { mutableStateOf(preferences.hasImage(owner.id)) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch {
        try { preferences.saveImage(owner.id, uri); customImage = true; controller.refreshSlate(owner) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
    } }
    LaunchedEffect(capabilities) { if (capabilities != null && capabilities.channels.none { it.id == channel }) channel = capabilities.channels.firstOrNull()?.id ?: 0 }
    LaunchedEffect(channel) {
        val token = ++channelGeneration
        if (channel > 0) {
            busy = true; error = null; saved = false; title = ""; description = ""
            try { val details = repository.channel(owner.id, channel); if (token == channelGeneration) { title = details.stringAt("displayName"); description = details.stringAt("description") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == channelGeneration) error = accountErrorCode(failure) }
            finally { if (token == channelGeneration) busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText(R.string.ui_channel_settings_079a60), style = MaterialTheme.typography.titleLarge)
        OptionPicker(uiText(R.string.ui_your_channel_1dcac2), capabilities?.channels?.firstOrNull { it.id == channel }?.title.orEmpty(), capabilities?.channels.orEmpty().map { it.id }, { id -> capabilities!!.channels.first { it.id == id }.title }) {
            if (channel != it) { channelGeneration++; busy = true; title = ""; description = ""; channel = it }
            options = options.copy(channelId = it); preferences.save(owner.id, options)
        }
        OutlinedTextField(title, { title = it; saved = false }, label = { Text(uiText(R.string.ui_channel_name_e9c0fd)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it; saved = false }, label = { Text(uiText(R.string.ui_channel_description_9a5a40)) }, enabled = !busy, maxLines = 5, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && channel > 0 && title.trim().length in 3..120 && description.length <= 1000, onClick = {
            busy = true; error = null
            scope.launch {
                try { repository.updateChannel(owner.id, channel, title, description); saved = true; onUpdated() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = accountErrorCode(failure) }
                finally { busy = false }
            }
        }) { Text(uiText(R.string.ui_save_channel_on_server_87bc4e)) }
        if (saved) Text(uiText(R.string.ui_channel_saved_7a1980), color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        Text(uiText(R.string.ui_broadcast_84c258), style = MaterialTheme.typography.titleMedium)
        OptionPicker(uiText(R.string.ui_quality_ca3d7b), "${options.resolution}p", listOf(480, 720, 1080), { "${it}p" }, enabled = !stream.busy) { options = options.copy(resolution = it); preferences.save(owner.id, options) }
        OptionPicker(uiText(R.string.ui_frame_rate_3345f0), "${options.fps} fps", listOf(24, 30), { "$it fps" }, enabled = !stream.busy) { options = options.copy(fps = it); preferences.save(owner.id, options) }
        OptionPicker(uiText(R.string.ui_bitrate_654ba4), "${options.bitrate / 1000} kb/s", listOf(500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000), { "${it / 1000} kb/s" }, enabled = !stream.busy) { options = options.copy(bitrate = it); preferences.save(owner.id, options) }
        Text(uiText(R.string.ui_image_when_camera_is_off_e4eb6f), style = MaterialTheme.typography.titleMedium)
        Text(if (customImage) uiText(R.string.ui_custom_image_selected_5d7182) else uiText(R.string.ui_account_avatar_or_user_initials_a1dfdc))
        OutlinedButton(onClick = { imagePicker.launch("image/*") }) { Text(uiText(R.string.ui_choose_image_4cd057)) }
        if (customImage) TextButton(onClick = { scope.launch { preferences.removeImage(owner.id); customImage = false; controller.refreshSlate(owner) } }) { Text(uiText(R.string.ui_use_avatar_a723a9)) }
        if (stream.busy) Text(uiText(R.string.ui_frame_size_and_quality_can_be_changed_befo_eeda5d), style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(broadcastErrorText(it), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun visibilityName(id: Int) = when (id) { 1 -> uiText(R.string.ui_public_5fe491); 2 -> uiText(R.string.ui_unlisted_689d94); else -> uiText(R.string.ui_private_b83206) }
@Composable private fun <T> OptionPicker(label: String, value: String, items: List<T>, title: @Composable (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }, enabled = enabled && items.isNotEmpty()) { Text("$label: $value"); Icon(Icons.Default.ExpandMore, null) }
        DropdownMenu(open, onDismissRequest = { open = false }) { items.forEach { item -> DropdownMenuItem(text = { Text(title(item)) }, onClick = { open = false; onSelect(item) }) } }
    }
}
@Composable internal fun broadcastErrorText(reason: String): String = when (reason) {
    "broadcast_permissions" -> uiText(R.string.ui_allow_camera_and_microphone_access_98d95e)
    "broadcast_camera", "broadcast_encoder" -> uiText(R.string.ui_could_not_prepare_the_camera_or_encoder_tr_322eab)
    "broadcast_microphone" -> uiText(R.string.ui_the_microphone_is_unavailable_check_permis_69f097)
    "broadcast_connection", "broadcast_disconnected", "broadcast_auth" -> uiText(R.string.ui_broadcast_stopped_the_server_rejected_the_42e89f)
    "broadcast_service", "broadcast_interrupted" -> uiText(R.string.ui_android_stopped_broadcasting_return_to_the_c7f063)
    "broadcast_camera_switch" -> uiText(R.string.ui_could_not_switch_the_camera_d211fd)
    "broadcast_torch" -> uiText(R.string.ui_light_is_unavailable_for_this_camera_673dc4)
    "broadcast_fixed_frame" -> uiText(R.string.ui_choose_the_frame_orientation_before_starti_6979da)
    "broadcast_endpoint" -> uiText(R.string.ui_the_server_returned_an_unsupported_stream_0cfcba)
    "invalid_channel_settings" -> uiText(R.string.ui_name_3_120_characters_description_up_to_1_f6f55f)
    else -> creatorErrorText(reason)
}
