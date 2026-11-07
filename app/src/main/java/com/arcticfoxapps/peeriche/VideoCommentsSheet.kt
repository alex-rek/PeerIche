package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VideoCommentsSheet(video: Video, server: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { PeerTubeAccounts.get(context) }
    val accounts by manager.state.collectAsState()
    val owner = accounts.active?.takeUnless { it.needsLogin }
    val library = remember(context) { AccountLibraries.get(context) }
    val scope = rememberCoroutineScope()
    val key = video.sourceServer(server) + "|" + video.apiId()
    var rows by remember(key, owner?.id) { mutableStateOf(emptyList<VideoComment>()) }
    var roots by remember(key, owner?.id) { mutableStateOf(emptyList<VideoComment>()) }
    var thread by remember(key, owner?.id) { mutableStateOf<Int?>(null) }
    var reply by remember(key, owner?.id) { mutableStateOf<Int?>(null) }
    var replyName by remember(key, owner?.id) { mutableStateOf("") }
    var total by remember(key, owner?.id) { mutableIntStateOf(0) }
    var start by remember(key, owner?.id) { mutableIntStateOf(0) }
    var busy by remember(key, owner?.id) { mutableStateOf(false) }
    var failed by remember(key, owner?.id) { mutableStateOf(false) }
    var resolved by remember(key, owner?.id) { mutableStateOf<Video?>(null) }
    var accountError by remember(key, owner?.id) { mutableStateOf<String?>(null) }
    var sending by remember(key, owner?.id) { mutableStateOf(false) }
    var sendError by remember(key, owner?.id) { mutableStateOf<String?>(null) }
    var sent by remember(key, owner?.id) { mutableStateOf(false) }
    var held by remember(key, owner?.id) { mutableStateOf(false) }
    var draft by rememberSaveable(key, owner?.id, reply) { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var postJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    fun load(replies: Int? = thread, append: Boolean = false) {
        val token = ++generation
        val account = owner
        val target = resolved
        job?.cancel(); busy = true; failed = false
        job = scope.launch {
            try {
                val api = PeerTubeClients.server(video.sourceServer(server))
                if (replies != null) {
                    val tree = if (account != null && target != null) library.thread(account.id, target.apiId(), replies)
                        else api.commentThread(video.apiId(), replies)
                    if (generation == token) rows = flattenComments(tree)
                } else {
                    val page = if (account != null && target != null) library.comments(account.id, target.apiId(), if (append) start else 0)
                        else api.comments(video.apiId(), if (append) start else 0)
                    if (generation != token) return@launch
                    rows = if (append) (rows + page.data).distinctBy { it.id } else page.data
                    start = if (page.data.isEmpty()) page.total else if (append) start + page.data.size else page.data.size
                    total = page.total; roots = rows
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == token) failed = true }
            finally { if (generation == token) busy = false }
        }
    }
    LaunchedEffect(key, owner?.id, retry) {
        generation++; job?.cancel(); postJob?.cancel()
        rows = emptyList(); roots = emptyList(); thread = null; reply = null; resolved = null; accountError = null
        if (!video.commentsEnabled) return@LaunchedEffect
        busy = true
        if (owner != null) try { resolved = library.resolveVideo(owner.id, video, server) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { accountError = accountErrorCode(failure) }
        load()
    }
    fun send() {
        val account = owner ?: return
        val target = resolved ?: return
        if (sending || draft.isBlank() || draft.length > 10_000) return
        val original = draft
        val parent = reply
        val viewThread = thread
        generation++; job?.cancel(); busy = false
        sending = true; sendError = null; sent = false
        postJob = scope.launch {
            try {
                val posted = library.post(account.id, target.apiId(), original, parent)
                if (manager.state.value.activeId == account.id) {
                    draft = ""; held = posted.heldForReview; sent = true
                    if (viewThread == null) { rows = (listOf(posted) + rows).distinctBy { it.id }; roots = rows; total++ }
                    else {
                        rows = (rows + posted).distinctBy { it.id }
                        roots = roots.map { if (it.id == viewThread) it.copy(totalReplies = it.totalReplies + 1) else it }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (manager.state.value.activeId == account.id)
                sendError = if (failure is AuthFailure && failure.status < 500 && failure.reason != "invalid_response") failure.reason else "comment_delivery_unknown" }
            finally { sending = false }
        }
    }
    val commentsEnabled = video.commentsEnabled && resolved?.commentsEnabled != false
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thread != null) IconButton(enabled = !sending, onClick = {
                    generation++; job?.cancel(); thread = null; reply = null; rows = roots; busy = false; failed = false
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад", "Back")) }
                Text(uiText("Комментарии", "Comments") + if (thread == null && total > 0) " · $total" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(enabled = !sending, onClick = { load() }) { Icon(Icons.Default.Refresh, uiText("Обновить комментарии", "Refresh comments")) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, uiText("Закрыть", "Close")) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!commentsEnabled) Text(uiText("Автор отключил комментарии", "Comments are disabled by the author"), Modifier.padding(16.dp))
            if (failed) TextButton(onClick = { load() }) { Text(uiText("Не удалось загрузить. Повторить", "Could not load. Retry")) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (!busy && !failed && rows.isEmpty() && commentsEnabled) item { Text(uiText("Комментариев пока нет", "No comments yet"), Modifier.padding(20.dp)) }
                items(rows, key = { it.id }) { comment ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(comment.account?.displayName ?: comment.account?.name.orEmpty(), fontWeight = FontWeight.SemiBold)
                        val body = if (comment.isDeleted) uiText("Комментарий удалён", "Comment deleted") else HtmlCompat.fromHtml(comment.text.orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                        SelectionContainer { Text(translatedMetadata(body), Modifier.padding(top = 4.dp)) }
                        if (comment.heldForReview) Text(uiText("На модерации", "Pending moderation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            if (thread == null && comment.totalReplies > 0) TextButton(enabled = !sending, onClick = {
                                thread = comment.id; reply = comment.id; replyName = comment.account?.displayName.orEmpty(); load(comment.id)
                            }) { Text(uiText("Ответы", "Replies") + " · ${comment.totalReplies}") }
                            if (!comment.isDeleted && commentsEnabled && owner != null && resolved != null) TextButton(enabled = !sending, onClick = {
                                if (thread == null) { thread = comment.id; load(comment.id) }
                                reply = comment.id; replyName = comment.account?.displayName ?: comment.account?.name.orEmpty()
                            }) { Text(uiText("Ответить", "Reply")) }
                        }
                    }
                }
                if (thread == null && start < total && !failed) item { TextButton(onClick = { load(append = true) }, enabled = !busy && !sending, modifier = Modifier.fillMaxWidth()) { Text(uiText("Загрузить ещё", "Load more")) } }
            }
            if (commentsEnabled && owner != null) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (resolved == null) {
                    accountError?.let { Text(accountErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (accountError != null) TextButton(onClick = { retry++ }) { Text(uiText("Повторить", "Retry")) }
                } else {
                    Text(uiText("От имени", "Posting as") + " ${owner.username}@${java.net.URI(owner.server).authority}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reply != null) Text(uiText("Ответ: $replyName", "Reply to: $replyName"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft, { draft = it; sendError = null; sent = false }, enabled = !sending, minLines = 1, maxLines = 3,
                        label = { Text(uiText("Комментарий", "Comment")) }, modifier = Modifier.fillMaxWidth(), isError = draft.length > 10_000)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${draft.length}/10000", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !sending && draft.isNotBlank() && draft.length <= 10_000, onClick = ::send) { Text(uiText(if (sending) "Отправка…" else "Отправить", if (sending) "Sending…" else "Send")) }
                    }
                    sendError?.let { Text(accountErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (sent) Text(if (held) uiText("Комментарий отправлен на модерацию", "Comment sent for moderation") else uiText("Комментарий отправлен", "Comment sent"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
