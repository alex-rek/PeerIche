package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal const val WATCH_LATER = "watch_later"
internal fun playlistKind(id: String): String {
    if (id == WATCH_LATER) return WATCH_LATER
    require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
    return "playlist:$id"
}

internal fun Video.libraryRow(server: String, kind: String): SavedVideo {
    val saved = copy(uuid = apiId(), host = sourceServer(server), url = publicWatchUrl(server),
        files = null, streamingPlaylists = null, directStreamUrl = null)
    return toSavedVideo(server, kind).copy(identity = "${sourceServer(server)}|${apiId()}", videoUrl = saved.url, metadata = Gson().toJson(saved))
}

internal class VideoPlaylists(context: Context) {
    private val database = PeerIcheDatabase.get(context)
    val definitions get() = database.library().observe("playlist")
    suspend fun create(name: String): String {
        require(name.trim().length in 1..120)
        val id = UUID.randomUUID().toString()
        database.library().put(SavedVideo(id, "playlist", name.trim(), null, "", null, null, 0, System.currentTimeMillis()))
        return id
    }
    suspend fun add(id: String, video: Video, server: String) = database.withTransaction {
        val kind = playlistKind(id)
        if (id != WATCH_LATER) require(database.library().getAll("playlist").any { it.identity == id })
        val existing = database.library().getAll(kind)
        val row = video.libraryRow(server, kind)
        val order = existing.firstOrNull { it.identity == row.identity }?.updatedAt
            ?: maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.updatedAt } ?: 0) + 1)
        database.library().put(row.copy(updatedAt = order))
    }
    suspend fun delete(id: String) = database.withTransaction {
        require(id != WATCH_LATER)
        database.library().deleteKind(playlistKind(id))
        database.library().delete(id, "playlist")
    }
}

internal object PlaybackQueue {
    private val pending = MutableStateFlow<List<Video>>(emptyList())
    val entries = pending.asStateFlow()
    val playing = MutableStateFlow<Video?>(null)
    private var requestedStart: String? = null
    @Synchronized fun start(videos: List<Video>) {
        requestedStart = videos.firstOrNull()?.let { it.sourceServer("") + "|" + it.apiId() }
        pending.value = videos.drop(1)
    }
    @Synchronized fun starts(identity: String): Boolean = requestedStart == identity
    @Synchronized fun consumeStart(identity: String): Boolean {
        if (requestedStart != identity) return false
        requestedStart = null
        return true
    }
    @Synchronized fun replace(videos: List<Video>) { pending.value = videos }
    @Synchronized fun next(video: Video) {
        val identity = video.sourceServer("") + "|" + video.apiId()
        pending.value = (listOf(video) + pending.value.filterNot { it.sourceServer("") + "|" + it.apiId() == identity })
    }
    @Synchronized fun take(): Video? {
        val first = pending.value.firstOrNull() ?: return null
        pending.value = pending.value.drop(1)
        return first
    }
    @Synchronized fun clear() { pending.value = emptyList(); playing.value = null; requestedStart = null }
}

@Composable internal fun PlaylistPicker(video: Video, server: String, createNew: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { VideoPlaylists(context) }
    val definitions by remember(repository) { repository.definitions }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(createNew) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val library = remember { PeerIcheDatabase.get(context).library() }
    val memberships by remember(video, server) { library.playlistMemberships(video.libraryRow(server, WATCH_LATER).identity) }.collectAsState(emptyList())
    fun add(id: String?) {
        busy = true; failed = false
        scope.launch {
            try {
                val chosen = id ?: repository.create(name)
                val row = video.libraryRow(server, playlistKind(chosen))
                if (library.getAll(row.kind).any { it.identity == row.identity }) library.delete(row.identity, row.kind)
                else repository.add(chosen, video, server)
                onClose()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(uiText(R.string.library_playlists)) }, text = {
        Column {
            if (creating) OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text(uiText(R.string.ui_title_1b6233)) }, singleLine = true)
            else {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(definitions, key = { it.identity }) { playlist ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { add(playlist.identity) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(memberships.any { it.kind == playlistKind(playlist.identity) }, null)
                            Text(playlist.title)
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { creating = true }) { Text(uiText(R.string.playlist_new)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(uiText(R.string.library_action_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        if (creating) TextButton(enabled = !busy && name.trim().length in 1..120, onClick = { add(null) }) { Text(uiText(R.string.ui_done_76ab9d)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(uiText(R.string.ui_close_641636)) } })
}

@Composable internal fun PlaylistsPage(selected: String?, onSelect: (String?) -> Unit, onPlay: (List<Video>) -> Unit) {
    val context = LocalContext.current
    val repository = remember { VideoPlaylists(context) }
    val library = remember { PeerIcheDatabase.get(context).library() }
    val definitions by remember(repository) { repository.definitions }.collectAsState(emptyList())
    var delete by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val id = selected
    if (id == null) LazyColumn(Modifier.fillMaxSize()) {
        item { TextButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Text(uiText(R.string.playlist_new)) } }
        item { SettingsRow(uiText(R.string.watch_later), icon = Icons.Default.WatchLater, onClick = { onSelect(WATCH_LATER) }) }
        items(definitions, key = { it.identity }) { playlist ->
            SettingsRow(playlist.title, icon = Icons.Default.PlaylistPlay, onClick = { onSelect(playlist.identity) }) {
                IconButton(onClick = { delete = playlist.identity }) { Icon(Icons.Default.Delete, uiText(R.string.ui_delete_423009)) }
            }
        }
        if (failed) item { Text(uiText(R.string.library_action_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
    } else {
        val rows by remember(id) { library.observe(playlistKind(id)) }.collectAsState(emptyList())
        val ordered = rows.sortedBy { it.updatedAt }
        Column {
            Text(if (id == WATCH_LATER) uiText(R.string.watch_later) else definitions.firstOrNull { it.identity == id }?.title.orEmpty(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(enabled = ordered.isNotEmpty(), modifier = Modifier.padding(horizontal = 16.dp), onClick = { onPlay(ordered.map(SavedVideo::toVideo)) }) {
                Icon(Icons.Default.PlayArrow, null); Text(uiText(R.string.playlist_play_all))
            }
            if (failed) Text(uiText(R.string.library_action_failed), color = MaterialTheme.colorScheme.error)
            LazyColumn {
                items(ordered, key = { it.identity }) { row ->
                    SettingsRow(row.title, row.channelName, onClick = { onPlay(ordered.dropWhile { it.identity != row.identity }.map(SavedVideo::toVideo)) }) {
                        IconButton(onClick = {
                            scope.launch {
                                try { library.delete(row.identity, row.kind) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true }
                            }
                        }) { Icon(Icons.Default.RemoveCircleOutline, uiText(R.string.ui_remove_32d0b3)) }
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text(uiText(R.string.playlist_new)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(uiText(R.string.ui_title_1b6233)) }) },
        confirmButton = { TextButton(enabled = name.trim().length in 1..120, onClick = {
            scope.launch {
                try { repository.create(name); name = ""; creating = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true; creating = false }
            }
        }) { Text(uiText(R.string.ui_done_76ab9d)) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(uiText(R.string.ui_cancel_28fb55)) } })
    if (delete != null) AlertDialog(onDismissRequest = { delete = null }, title = { Text(uiText(R.string.playlist_delete)) },
        confirmButton = { TextButton(onClick = {
            val chosen = delete!!; delete = null
            scope.launch {
                try { repository.delete(chosen) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
        }) { Text(uiText(R.string.ui_delete_423009)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(uiText(R.string.ui_cancel_28fb55)) } })
}
