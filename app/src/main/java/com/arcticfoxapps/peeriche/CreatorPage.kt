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
        Text(uiText("Войдите в аккаунт на странице «Вы».", "Sign in on the You page."), Modifier.padding(20.dp)); return
    }
    key(owner.id) { CreatorAccountPage(owner, accounts, onOpenVideo) }
}

@Composable private fun CreatorAccountPage(owner: AccountSummary, accounts: AccountSessionManager, onOpenVideo: (Video) -> Unit) {
    val context = LocalContext.current
    val repository = remember { CreatorRepository(accounts) }
    val uploads = remember { VideoUploads.get(context) }
    val transfer by uploads.state.collectAsState()
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
    LaunchedEffect(draft?.status) { if (draft?.status == "done") load() }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("${owner.username}@${java.net.URI(owner.server).authority}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = capabilities != null && transfer == null, onClick = { editor = "upload" }) { Text(uiText("Загрузить видео", "Upload video")) }
                OutlinedButton(enabled = capabilities?.live == true, onClick = { editor = "live" }) { Text(uiText("Создать эфир", "Create live")) }
                IconButton(enabled = !busy, onClick = { reload++ }) { Icon(Icons.Default.Refresh, uiText("Обновить", "Refresh")) }
            }
            capabilities?.let { caps ->
                Text(uiText("Хранилище: ", "Storage: ") + formatBytes(caps.used) + " / " + if (caps.quota < 0) uiText("без ограничений", "unlimited") else formatBytes(caps.quota), style = MaterialTheme.typography.bodySmall)
                Text(uiText("За сутки: ", "Today: ") + formatBytes(caps.dailyUsed) + " / " + if (caps.dailyQuota < 0) uiText("без ограничений", "unlimited") else formatBytes(caps.dailyQuota), style = MaterialTheme.typography.bodySmall)
                if (!caps.live) Text(uiText("Прямые эфиры отключены на этом сервере.", "Live streaming is disabled on this server."), style = MaterialTheme.typography.bodySmall)
            }
            if (transfer != null && draft == null) {
                Text(uiText("Есть задание загрузки другого аккаунта. Для продолжения переключитесь на него; ненужное локальное задание можно убрать.", "Another account has an upload task. Switch to it to resume, or remove the local task if no longer needed."))
                TextButton(onClick = { discard = true }) { Text(uiText("Убрать локальное задание", "Remove local task")) }
            }
        }
        if (draft != null) item {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(draft.metadata.name, fontWeight = FontWeight.SemiBold)
                Text(uploadStatus(draft.status) + " · ${formatBytes(draft.offset)} / ${formatBytes(draft.file.size)}")
                LinearProgressIndicator(progress = { (draft.offset.toDouble() / draft.file.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (draft.videoUuid != null && draft.status != "done") Text(uiText("Видео уже загружено. Осталось применить обложку.", "The video has been uploaded. Its cover still needs to be applied."))
                draft.error?.let { Text(creatorErrorText(it), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.active) TextButton(onClick = { uploads.pause() }) { Text(uiText("Пауза", "Pause")) }
                    else if (draft.status !in listOf("done", "cancelled")) {
                        TextButton(onClick = { action { uploads.resume() } }) { Text(uiText("Продолжить", "Resume")) }
                        if (draft.videoUuid == null) TextButton(onClick = { action { uploads.cancel() } }) { Text(uiText("Отменить", "Cancel")) }
                    }
                    if (!draft.active) TextButton(onClick = { discard = true }) { Text(uiText("Убрать задание", "Remove task")) }
                }
            } }
        }
        if (uploadStorageError) item {
            Text(creatorErrorText("upload_storage"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { discard = true }) { Text(uiText("Сбросить задание загрузки", "Reset upload task")) }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { reason -> item { Text(creatorErrorText(reason), color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text(uiText("Повторить", "Retry")) } } }
        if (!busy && rows.isEmpty() && error == null) item { Text(uiText("На сервере пока нет ваших видео.", "You have no videos on this server yet.")) }
        items(rows, key = { it.video.apiId() }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.video.name, style = MaterialTheme.typography.titleMedium)
                Text(creatorVideoStatus(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.privacy in 1..2 && item.state == 1) TextButton(onClick = { onOpenVideo(item.video) }) { Text(uiText("Смотреть", "Watch")) }
                    TextButton(onClick = {
                        context.playerActivity()?.pipSupport?.suspendForExternalUi()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${owner.server}/videos/watch/${item.video.apiId()}")))
                    }) { Text(uiText("На сайте", "Website")) }
                    if (item.video.isLive) TextButton(onClick = { credentialsFor = item.video.apiId() }) { Text(uiText("Параметры эфира", "Stream settings")) }
                }
            } }
        }
        if (start < total && error == null) item { TextButton(enabled = !busy, onClick = { load(true) }, modifier = Modifier.fillMaxWidth()) { Text(uiText("Загрузить ещё", "Load more")) } }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(uiText("Убрать задание?", "Remove task?")) },
        text = { Text(uiText("Опубликованное видео останется на сервере. Незавершённую передачу сначала можно отменить кнопкой «Отменить»; после удаления задания продолжить её не получится.", "Published videos will remain on the server. You can cancel an incomplete transfer first; removing the task discards its resume information.")) },
        confirmButton = { TextButton(onClick = { discard = false; action { uploads.discard(); uploadStorageError = false; reload++ } }) { Text(uiText("Убрать", "Remove")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(uiText("Оставить", "Keep")) } })
    if (editor != null && capabilities != null) CreatorEditor(owner, capabilities!!, live = editor == "live", uploads, repository,
        onCreated = { uuid -> editor = null; reload++; if (uuid != null) credentialsFor = uuid }, onClose = { editor = null })
    credentialsFor?.let { uuid -> LiveCredentialsDialog(owner.id, uuid, repository) { credentialsFor = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CreatorEditor(owner: AccountSummary, capabilities: CreatorCapabilities, live: Boolean, uploads: VideoUploadCoordinator,
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
    var replay by remember { mutableStateOf(false) }
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
            Text(uiText(if (live) "Создать эфир для OBS" else "Загрузить видео", if (live) "Create live for OBS" else "Upload video"), style = MaterialTheme.typography.titleLarge)
            Text("${owner.username}@${java.net.URI(owner.server).authority}", style = MaterialTheme.typography.bodySmall)
            if (!live) {
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("video/*")) }) { Text(file?.name ?: uiText("Выбрать видео", "Choose video")) }
                file?.let { Text(formatBytes(it.size), style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(enabled = !busy, onClick = { coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }) { Text(cover?.name ?: uiText("Выбрать обложку (необязательно)", "Choose cover (optional)")) }
                if (cover != null) TextButton(enabled = !busy, onClick = { releaseUploadFile(context, cover); cover = null }) { Text(uiText("Без обложки", "No cover")) }
            }
            OutlinedTextField(title, { title = it }, enabled = !busy, label = { Text(uiText("Название · 3–120 символов", "Title · 3–120 characters")) }, isError = title.length > 120, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, enabled = !busy, label = { Text(uiText("Описание", "Description")) }, minLines = 2, maxLines = 5, isError = description.length > 10000, modifier = Modifier.fillMaxWidth())
            var chooseChannel by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(enabled = !busy, onClick = { chooseChannel = true }) { Text(channel?.title ?: uiText("Нет собственного канала", "No channel available")) }
                DropdownMenu(chooseChannel, onDismissRequest = { chooseChannel = false }) {
                    capabilities.channels.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { channel = item; chooseChannel = false }) }
                }
            }
            Text(uiText("Доступность", "Visibility"), style = MaterialTheme.typography.titleSmall)
            listOf(3 to uiText("Личное", "Private"), 2 to uiText("По ссылке", "Unlisted"), 1 to uiText("Публичное", "Public")).forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(privacy == id, enabled = !busy, onClick = { privacy = id }); Text(label) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(nsfw, { nsfw = it }, enabled = !busy); Text(uiText("Контент для взрослых", "Sensitive content")) }
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replay, { replay = it }, enabled = !busy && capabilities.replay); Text(uiText("Сохранять запись эфира", "Save replay")) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(permanent, { permanent = it }, enabled = !busy); Text(uiText("Повторно используемый эфир", "Recurring live")) }
                Text(uiText("После создания появятся адрес сервера и ключ для OBS. Камера телефона будет добавлена следующим этапом.", "After creation, use the server URL and stream key in OBS. Phone broadcasting will be added separately."), style = MaterialTheme.typography.bodySmall)
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
                }) { Text(uiText(if (live) "Создать эфир" else "Загрузить", if (live) "Create live" else "Upload")) }
                TextButton(enabled = !busy, onClick = onClose) { Text(uiText("Закрыть", "Close")) }
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
                Text(uiText("Параметры эфира для OBS", "OBS stream settings"), style = MaterialTheme.typography.titleLarge)
                data?.let { value ->
                    Text(uiText("Сервис OBS: «Пользовательский»", "OBS service: Custom"))
                    Text(uiText("Сервер", "Server"), fontWeight = FontWeight.SemiBold); Text(value.url)
                    TextButton(onClick = { copy(value.url) }) { Text(uiText("Копировать адрес", "Copy URL")) }
                    var visible by remember { mutableStateOf(false) }
                    Text(uiText("Ключ трансляции", "Stream key"), fontWeight = FontWeight.SemiBold)
                    Text(if (visible) value.key else "••••••••••••")
                    Row {
                        TextButton(onClick = { visible = !visible }) { Text(uiText(if (visible) "Скрыть" else "Показать", if (visible) "Hide" else "Show")) }
                        TextButton(onClick = { copy(value.key, true) }) { Text(uiText("Копировать ключ", "Copy key")) }
                    }
                    Text(uiText("Ключ позволяет вести эфир от вашего имени. Не публикуйте его.", "The key allows broadcasting as you. Keep it private."), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copy(value.watchUrl) }) { Text(uiText("Копировать ссылку на эфир", "Copy watch link")) }
                }
                if (data == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(creatorErrorText(it)); TextButton(onClick = { retry++ }) { Text(uiText("Повторить", "Retry")) } }
                TextButton(onClick = onClose) { Text(uiText("Закрыть", "Close")) }
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
    val privacy = when (item.privacy) { 1 -> uiText("Публичное", "Public"); 2 -> uiText("По ссылке", "Unlisted"); 3 -> uiText("Личное", "Private"); else -> uiText("Ограниченный доступ", "Restricted") }
    val status = when (item.state) {
        1 -> if (item.video.isLive) uiText("В эфире", "Live") else uiText("Опубликовано", "Published")
        2 -> uiText("Обработка видео", "Processing video"); 3 -> uiText("Импорт видео", "Importing video")
        4 -> uiText("Ожидает начала эфира", "Waiting for live"); 5 -> uiText("Эфир завершён", "Live ended")
        6, 10 -> uiText("Перемещение видео", "Moving video"); 7 -> uiText("Ошибка обработки", "Processing failed")
        8, 11 -> uiText("Ошибка перемещения", "Moving failed"); 9 -> uiText("Редактирование видео", "Editing video"); 12 -> uiText("Ошибка импорта", "Import failed")
        else -> item.stateLabel.ifBlank { uiText("Обработка на сервере", "Server processing") }
    }
    return "$privacy · $status"
}
@Composable private fun uploadStatus(status: String): String = when (status) {
    "starting" -> uiText("Подготовка", "Preparing"); "uploading" -> uiText("Загрузка", "Uploading"); "paused" -> uiText("Приостановлено", "Paused")
    "cover" -> uiText("Обложка", "Cover"); "done" -> uiText("Передано на сервер", "Uploaded to server"); "cancelled" -> uiText("Отменено", "Cancelled")
    "cancelling" -> uiText("Отмена", "Cancelling"); else -> uiText("Загрузка остановлена", "Upload stopped")
}
@Composable internal fun creatorErrorText(reason: String): String = when (reason) {
    "upload_storage" -> uiText("Не удалось прочитать или сохранить задание загрузки. Можно сбросить только это задание; аккаунты и опубликованные видео сохранятся.", "The upload task could not be read or saved. You can reset this task without removing accounts or published videos.")
    "invalid_publication" -> uiText("Проверьте название (3–120 символов), описание (до 10 000) и канал.", "Check the title (3–120 characters), description (up to 10,000) and channel.")
    "invalid_file", "file_unavailable", "file_changed" -> uiText("Не удалось прочитать исходный файл. Он должен оставаться доступным и неизменным до завершения загрузки.", "The original file must remain accessible and unchanged until upload finishes.")
    "invalid_cover" -> uiText("Недопустимая обложка: проверьте размер и формат JPG, PNG или WebP.", "Invalid cover: check its size and JPG, PNG or WebP format.")
    "upload_format" -> uiText("Сервер не поддерживает этот формат файла.", "The server does not support this file format.")
    "upload_quota" -> uiText("Недостаточно квоты или превышен максимальный размер файла на сервере.", "Insufficient quota or the server file size limit was exceeded.")
    "upload_expired" -> uiText("Сессия загрузки недоступна. Сначала обновите «Ваши видео»: файл мог уже загрузиться. Если его нет, уберите задание и выберите файл заново.", "The upload session is unavailable. Refresh Your videos first: it may already be uploaded. If absent, remove the task and select the file again.")
    "upload_interrupted" -> uiText("Передача прервалась. Нажмите «Продолжить» для сверки с сервером.", "Transfer was interrupted. Resume to check progress with the server.")
    "upload_range", "upload_processing" -> uiText("Сверьте состояние кнопкой «Продолжить», затем обновите список видео. Не создавайте повторную загрузку, пока результат не известен.", "Resume to check the transfer, then refresh your videos before starting another upload.")
    "upload_chunk_limit" -> uiText("Параметры блоков сервера не поддерживаются для этого размера файла.", "The server chunk limits are incompatible with this file size.")
    "upload_unreadable" -> uiText("Сервер не смог обработать видеофайл.", "The server could not read the video file.")
    "upload_exists" -> uiText("Сначала завершите или уберите существующее задание.", "Finish or remove the existing task first.")
    "upload_service" -> uiText("Android не разрешил запустить фоновую загрузку. Вернитесь в приложение и нажмите «Продолжить».", "Android could not start the upload service. Return to the app and resume.")
    "upload_already_complete" -> uiText("Видео уже передано на сервер. Отмена загрузки не удаляет опубликованное видео.", "The video has already been uploaded. Cancelling does not delete published videos.")
    "live_disabled" -> uiText("Сервер не разрешает эфир или сохранение записи с выбранными параметрами.", "The server does not allow these live or replay settings.")
    "live_create_unknown" -> uiText("Создание эфира не подтверждено. Закройте окно и обновите «Ваши видео» перед повторной попыткой.", "Live creation could not be confirmed. Refresh Your videos before trying again.")
    "invalid_upload_location" -> uiText("Сервер вернул недопустимый адрес продолжения загрузки.", "The server returned an invalid upload URL.")
    else -> accountErrorText(reason)
}
