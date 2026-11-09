package com.arcticfoxapps.peeriche

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun CreatorPage(onOpenVideo: (Video) -> Unit) {
    val context = LocalContext.current
    val accounts = remember(context) { PeerTubeAccounts.get(context) }
    val state by accounts.state.collectAsState()
    val owner = state.active?.takeUnless { it.needsLogin }
    if (owner == null) {
        Text(uiText(R.string.ui_sign_in_on_the_you_page_15ac8e), Modifier.padding(20.dp)); return
    }
    key(owner.id) { CreatorAccountPage(owner, accounts, onOpenVideo) }
}

@Composable private fun CreatorAccountPage(owner: AccountSummary, accounts: AccountSessionManager, onOpenVideo: (Video) -> Unit) {
    val context = LocalContext.current
    val repository = remember { CreatorRepository(accounts) }
    val uploads = remember { VideoUploads.get(context) }
    val transfer by uploads.state.collectAsState()
    val completionRevision by uploads.completionRevision.collectAsState()
    val draft = transfer?.takeIf { it.accountId == owner.id }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<CreatorVideo>()) }
    var total by remember { mutableIntStateOf(0) }
    var start by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf<CreatorCapabilities?>(null) }
    var editor by remember { mutableStateOf<String?>(null) }
    var credentialsFor by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var uploadStorageError by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    fun load(append: Boolean = false) {
        val token = ++generation
        busy = true; error = null
        scope.launch {
            try {
                if (!append) capabilities = repository.capabilities(owner.id)
                val page = repository.videos(owner.id, if (append) start else 0)
                if (token == generation) {
                    rows = if (append) (rows + page.rows).distinctBy { it.video.apiId() } else page.rows
                    start = if (append) start + page.rows.size else page.rows.size
                    total = if (page.rows.isEmpty()) start else page.total
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == generation) error = accountErrorCode(failure) }
            finally { if (token == generation) busy = false }
        }
    }
    LaunchedEffect(reload) {
        try { uploads.initialize(); uploadStorageError = false } catch (_: Exception) { uploadStorageError = true }
        load()
    }
    LaunchedEffect(completionRevision) { if (completionRevision > 0) load() }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("${owner.username}@${java.net.URI(owner.server).authority}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = capabilities != null && transfer == null, onClick = { editor = "upload" }) { Text(uiText(R.string.ui_upload_video_a6dd28)) }
                OutlinedButton(enabled = capabilities?.live == true, onClick = { editor = "live" }) { Text(uiText(R.string.ui_create_live_2cf7bd)) }
                IconButton(enabled = !busy, onClick = { reload++ }) { Icon(Icons.Default.Refresh, uiText(R.string.ui_refresh_3526ad)) }
            }
            capabilities?.let { caps ->
                Text(uiText(R.string.ui_storage_ecf1de) + formatBytes(caps.used) + " / " + if (caps.quota < 0) uiText(R.string.ui_unlimited_2b3fb9) else formatBytes(caps.quota), style = MaterialTheme.typography.bodySmall)
                Text(uiText(R.string.ui_today_db20e6) + formatBytes(caps.dailyUsed) + " / " + if (caps.dailyQuota < 0) uiText(R.string.ui_unlimited_2b3fb9) else formatBytes(caps.dailyQuota), style = MaterialTheme.typography.bodySmall)
                if (!caps.live) Text(uiText(R.string.ui_live_streaming_is_disabled_on_this_server_d8fe5d), style = MaterialTheme.typography.bodySmall)
            }
            if (transfer != null && draft == null) {
                Text(uiText(R.string.ui_another_account_has_an_upload_task_switch_dadf08))
                TextButton(onClick = { discard = true }) { Text(uiText(R.string.ui_remove_local_task_66ebd2)) }
            }
        }
        if (draft != null) item {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(draft.metadata.name, fontWeight = FontWeight.SemiBold)
                Text(uploadStatus(draft.status) + " · ${formatBytes(draft.offset)} / ${formatBytes(draft.file.size)}")
                LinearProgressIndicator(progress = { (draft.offset.toDouble() / draft.file.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (draft.videoUuid != null && draft.status != "done") Text(uiText(R.string.ui_the_video_has_been_uploaded_its_cover_stil_d841b6))
                draft.error?.let { Text(creatorErrorText(it), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.active) TextButton(onClick = { uploads.pause() }) { Text(uiText(R.string.ui_pause_d19299)) }
                    else if (draft.status !in listOf("done", "cancelled")) {
                        TextButton(onClick = { action { uploads.resume() } }) { Text(uiText(R.string.ui_resume_44eb04)) }
                        if (draft.videoUuid == null) TextButton(onClick = { action { uploads.cancel() } }) { Text(uiText(R.string.ui_cancel_8afaa2)) }
                    }
                    if (!draft.active) TextButton(onClick = { discard = true }) { Text(uiText(R.string.ui_remove_task_e9224e)) }
                }
            } }
        }
        if (uploadStorageError) item {
            Text(creatorErrorText("upload_storage"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { discard = true }) { Text(uiText(R.string.ui_reset_upload_task_322db7)) }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { reason -> item { Text(creatorErrorText(reason), color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text(uiText(R.string.ui_retry_e8368b)) } } }
        if (!busy && rows.isEmpty() && error == null) item { Text(uiText(R.string.ui_you_have_no_videos_on_this_server_yet_74102c)) }
        items(rows, key = { it.video.apiId() }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoThumbnail(item.video, owner.server, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clickable(enabled = item.state == 1) { onOpenVideo(item.video) })
                Text(item.video.name, style = MaterialTheme.typography.titleMedium)
                Text(creatorVideoStatus(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.state == 1) TextButton(onClick = { onOpenVideo(item.video) }) { Text(uiText(R.string.ui_watch_5f1a41)) }
                    TextButton(onClick = {
                        context.playerActivity()?.pipSupport?.suspendForExternalUi()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${owner.server}/videos/watch/${item.video.apiId()}")))
                    }) { Text(uiText(R.string.ui_website_633681)) }
                    if (item.video.isLive) TextButton(onClick = { credentialsFor = item.video.apiId() }) { Text(uiText(R.string.ui_stream_settings_16c86b)) }
                }
            } }
        }
        if (start < total && error == null) item { TextButton(enabled = !busy, onClick = { load(true) }, modifier = Modifier.fillMaxWidth()) { Text(uiText(R.string.ui_load_more_b9d06f)) } }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(uiText(R.string.ui_remove_task_cf3ba9)) },
        text = { Text(uiText(R.string.ui_published_videos_will_remain_on_the_server_f6e568)) },
        confirmButton = { TextButton(onClick = { discard = false; action { uploads.discard(); uploadStorageError = false; reload++ } }) { Text(uiText(R.string.ui_remove_32d0b3)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(uiText(R.string.ui_keep_7058e4)) } })
    if (editor != null && capabilities != null) CreatorEditor(owner, capabilities!!, live = editor == "live", uploads, repository,
        onCreated = { uuid -> editor = null; reload++; if (uuid != null) credentialsFor = uuid }, onClose = { editor = null })
    credentialsFor?.let { uuid -> LiveCredentialsDialog(owner.id, uuid, repository) { credentialsFor = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CreatorEditor(owner: AccountSummary, capabilities: CreatorCapabilities, live: Boolean, uploads: VideoUploadCoordinator,
    repository: CreatorRepository, onCreated: (String?) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<UploadFileInfo?>(null) }
    var cover by remember { mutableStateOf<UploadFileInfo?>(null) }
    var retained by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (!retained) { releaseUploadFile(context, file); releaseUploadFile(context, cover) } } }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf(capabilities.channels.firstOrNull()) }
    var privacy by remember { mutableIntStateOf(3) }
    var nsfw by remember { mutableStateOf(false) }
    var replay by remember { mutableStateOf(capabilities.replay) }
    var permanent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        try {
            val picked = selectedUploadFile(context, uri); capabilities.validateFile(picked.name, picked.size)
            if (file?.uri != picked.uri) releaseUploadFile(context, file)
            file = picked; if (title.isBlank()) title = picked.name.substringBeforeLast('.').take(120)
        } catch (failure: Exception) {
            if (uri.toString() != file?.uri) releaseUploadFile(context, UploadFileInfo(uri.toString(), "", 0, ""))
            if (failure is CancellationException) throw failure
            error = accountErrorCode(failure)
        }
    } }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        try {
            val picked = selectedUploadFile(context, uri)
            if (picked.mime !in listOf("image/jpeg", "image/png", "image/webp") || picked.size > minOf(capabilities.coverMax, 10L*1024*1024)) throw AuthFailure("invalid_cover")
            if (cover?.uri != picked.uri) releaseUploadFile(context, cover)
            cover = picked
        } catch (failure: Exception) {
            if (uri.toString() != cover?.uri) releaseUploadFile(context, UploadFileInfo(uri.toString(), "", 0, ""))
            if (failure is CancellationException) throw failure
            error = accountErrorCode(failure)
        }
    } }
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.94f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(uiText(if (live) R.string.ui_create_live_for_obs_3e8e4f else R.string.ui_upload_video_a6dd28), style = MaterialTheme.typography.titleLarge)
            Text("${owner.username}@${java.net.URI(owner.server).authority}", style = MaterialTheme.typography.bodySmall)
            if (!live) {
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("video/*")) }) { Text(file?.name ?: uiText(R.string.ui_choose_video_71bb7b)) }
                file?.let { Text(formatBytes(it.size), style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(enabled = !busy, onClick = { coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }) { Text(cover?.name ?: uiText(R.string.ui_choose_cover_optional_9a60e9)) }
                if (cover != null) TextButton(enabled = !busy, onClick = { releaseUploadFile(context, cover); cover = null }) { Text(uiText(R.string.ui_no_cover_564f04)) }
            }
            OutlinedTextField(title, { title = it }, enabled = !busy, label = { Text(uiText(R.string.ui_title_3_120_characters_205170)) }, isError = title.length > 120, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, enabled = !busy, label = { Text(uiText(R.string.ui_description_70bc2e)) }, minLines = 2, maxLines = 5, isError = description.length > 10000, modifier = Modifier.fillMaxWidth())
            var chooseChannel by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(enabled = !busy, onClick = { chooseChannel = true }) { Text(channel?.title ?: uiText(R.string.ui_no_channel_available_f2ae51)) }
                DropdownMenu(chooseChannel, onDismissRequest = { chooseChannel = false }) {
                    capabilities.channels.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { channel = item; chooseChannel = false }) }
                }
            }
            Text(uiText(R.string.ui_visibility_613511), style = MaterialTheme.typography.titleSmall)
            listOf(3 to uiText(R.string.ui_private_b83206), 2 to uiText(R.string.ui_unlisted_689d94), 1 to uiText(R.string.ui_public_5fe491)).forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(privacy == id, enabled = !busy, onClick = { privacy = id }); Text(label) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(nsfw, { nsfw = it }, enabled = !busy); Text(uiText(R.string.ui_sensitive_content_3d5344)) }
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replay, { replay = it }, enabled = !busy && capabilities.replay); Text(uiText(R.string.ui_save_replay_6cbd4e)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(permanent, { permanent = it }, enabled = !busy); Text(uiText(R.string.ui_recurring_live_526a88)) }
                Text(uiText(R.string.ui_after_creation_use_the_server_url_and_stre_1eddbc), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(creatorErrorText(it), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && title.trim().length in 3..120 && description.length <= 10000 && channel != null && (live || file != null), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val metadata = CreatorMetadata(title, description, channel!!.id, privacy, nsfw)
                            if (live) {
                                val uuid = repository.createLive(owner.id, metadata, replay, permanent)
                                onCreated(uuid)
                            } else {
                                retained = true
                                try { uploads.prepare(owner.id, file!!, metadata, cover) }
                                catch (failure: Exception) {
                                    retained = uploads.state.value?.file?.uri == file?.uri
                                    if (failure is CancellationException && !retained) { releaseUploadFile(context, file); releaseUploadFile(context, cover) }
                                    throw failure
                                }
                                try {
                                    uploads.resume()
                                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } finally { onCreated(null) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = if (live && (failure !is AuthFailure || failure.status >= 500 || failure.reason == "invalid_response")) "live_create_unknown" else accountErrorCode(failure) }
                        finally { busy = false }
                    }
                }) { Text(uiText(if (live) R.string.ui_create_live_2cf7bd else R.string.ui_upload_b4fcd7)) }
                TextButton(enabled = !busy, onClick = onClose) { Text(uiText(R.string.ui_close_641636)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun LiveCredentialsDialog(account: String, uuid: String, repository: CreatorRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    var data by remember(account, uuid) { mutableStateOf<LiveCredentials?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(account, uuid, retry) {
        data = null; error = null
        try { data = repository.liveCredentials(account, uuid) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
    }
    fun copy(value: String, secret: Boolean = false) {
        val clip = ClipData.newPlainText("PeerIche", value)
        if (secret && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uiText(R.string.ui_obs_stream_settings_a35ef7), style = MaterialTheme.typography.titleLarge)
                data?.let { value ->
                    Text(uiText(R.string.ui_obs_service_custom_257d44))
                    Text(uiText(R.string.ui_server_dedf58), fontWeight = FontWeight.SemiBold); Text(value.url)
                    TextButton(onClick = { copy(value.url) }) { Text(uiText(R.string.ui_copy_url_4f5c50)) }
                    var visible by remember { mutableStateOf(false) }
                    Text(uiText(R.string.ui_stream_key_8d248e), fontWeight = FontWeight.SemiBold)
                    Text(if (visible) value.key else "••••••••••••")
                    Row {
                        TextButton(onClick = { visible = !visible }) { Text(uiText(if (visible) R.string.ui_hide_c7431d else R.string.ui_show_bd4eeb)) }
                        TextButton(onClick = { copy(value.key, true) }) { Text(uiText(R.string.ui_copy_key_8b55c7)) }
                    }
                    Text(uiText(R.string.ui_the_key_allows_broadcasting_as_you_keep_it_45f0a6), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copy(value.watchUrl) }) { Text(uiText(R.string.ui_copy_watch_link_52e6ac)) }
                }
                if (data == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(creatorErrorText(it)); TextButton(onClick = { retry++ }) { Text(uiText(R.string.ui_retry_e8368b)) } }
                TextButton(onClick = onClose) { Text(uiText(R.string.ui_close_641636)) }
            }
        }
    }
}

internal fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024L * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KiB", value / 1024.0)
    value < 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MiB", value / (1024.0 * 1024))
    else -> String.format(java.util.Locale.getDefault(), "%.1f GiB", value / (1024.0 * 1024 * 1024))
}

@Composable private fun creatorVideoStatus(item: CreatorVideo): String {
    val privacy = when (item.privacy) { 1 -> uiText(R.string.ui_public_5fe491); 2 -> uiText(R.string.ui_unlisted_689d94); 3 -> uiText(R.string.ui_private_b83206); else -> uiText(R.string.ui_restricted_5746bb) }
    val status = when (item.state) {
        1 -> if (item.video.isLive) uiText(R.string.ui_live_4a340c) else uiText(R.string.ui_published_b4e8f8)
        2 -> uiText(R.string.ui_processing_video_967d3f); 3 -> uiText(R.string.ui_importing_video_978518)
        4 -> uiText(R.string.ui_waiting_for_live_fc9024); 5 -> uiText(R.string.ui_live_ended_4704f1)
        6, 10 -> uiText(R.string.ui_moving_video_98925c); 7 -> uiText(R.string.ui_processing_failed_423bf1)
        8, 11 -> uiText(R.string.ui_moving_failed_0aec63); 9 -> uiText(R.string.ui_editing_video_99ff81); 12 -> uiText(R.string.ui_import_failed_6ca1a5)
        else -> item.stateLabel.ifBlank { uiText(R.string.ui_server_processing_726797) }
    }
    return "$privacy · $status"
}
@Composable private fun uploadStatus(status: String): String = when (status) {
    "starting" -> uiText(R.string.ui_preparing_6e055d); "uploading" -> uiText(R.string.ui_uploading_9da15d); "paused" -> uiText(R.string.ui_paused_5d65a6)
    "cover" -> uiText(R.string.ui_cover_686522); "done" -> uiText(R.string.ui_uploaded_to_server_825d5e); "cancelled" -> uiText(R.string.ui_cancelled_36a6b0)
    "cancelling" -> uiText(R.string.ui_cancelling_6a8032); else -> uiText(R.string.ui_upload_stopped_86c348)
}
@Composable internal fun creatorErrorText(reason: String): String = when (reason) {
    "upload_response", "invalid_response" -> uiText(R.string.ui_could_not_read_the_server_publication_resp_afcf21)
    "invalid_description", "publication_description" -> uiText(R.string.ui_leave_the_description_empty_or_enter_3_to_b7e8f0)
    "publication_parameters" -> uiText(R.string.ui_the_server_rejected_the_publication_parame_022749)
    "max_instance_lives_limit_reached" -> uiText(R.string.ui_the_server_has_reached_its_simultaneous_li_7e7ab4)
    "max_user_lives_limit_reached" -> uiText(R.string.ui_your_account_has_reached_its_live_stream_l_fdf60d)
    "live_not_enabled", "live_not_allowing_replay" -> uiText(R.string.ui_the_server_does_not_allow_these_live_or_re_b8bb40)
    "upload_storage" -> uiText(R.string.ui_the_upload_task_could_not_be_read_or_saved_803c41)
    "invalid_publication" -> uiText(R.string.ui_check_the_title_3_120_characters_descripti_42321d)
    "invalid_file", "file_unavailable", "file_changed" -> uiText(R.string.ui_the_original_file_must_remain_accessible_a_f44e10)
    "invalid_cover" -> uiText(R.string.ui_invalid_cover_check_its_size_and_jpg_png_o_f819b9)
    "upload_format" -> uiText(R.string.ui_the_server_does_not_support_this_file_form_0ebd3a)
    "upload_quota" -> uiText(R.string.ui_insufficient_quota_or_the_server_file_size_b79de3)
    "upload_expired" -> uiText(R.string.ui_the_upload_session_is_unavailable_refresh_c1a973)
    "upload_interrupted" -> uiText(R.string.ui_transfer_was_interrupted_resume_to_check_p_77ca35)
    "upload_range", "upload_processing" -> uiText(R.string.ui_resume_to_check_the_transfer_then_refresh_2ca5ab)
    "upload_chunk_limit" -> uiText(R.string.ui_the_server_chunk_limits_are_incompatible_w_055ddc)
    "upload_unreadable" -> uiText(R.string.ui_the_server_could_not_read_the_video_file_c95cf8)
    "upload_exists" -> uiText(R.string.ui_finish_or_remove_the_existing_task_first_2d4084)
    "upload_service" -> uiText(R.string.ui_android_could_not_start_the_upload_service_6cfdee)
    "upload_already_complete" -> uiText(R.string.ui_the_video_has_already_been_uploaded_cancel_006471)
    "live_disabled" -> uiText(R.string.ui_the_server_does_not_allow_these_live_or_re_b8bb40)
    "live_create_unknown" -> uiText(R.string.ui_live_creation_could_not_be_confirmed_refre_523650)
    "invalid_upload_location" -> uiText(R.string.ui_the_server_returned_an_invalid_upload_url_d03771)
    else -> accountErrorText(reason)
}
