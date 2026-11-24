package com.arcticfoxapps.peeriche

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal const val WATCH_LATER_SYNCED = "synced"
internal const val WATCH_LATER_REMOVE = "remove"
internal const val WATCH_LATER_LOCAL_ONLY = "local_only"
private const val WATCH_LATER_OWNER = "owner"
private const val MAX_REMOTE_ELEMENTS = 5_000
private const val MAX_REMOTE_PLAYLISTS = 1_000

/** A row with an empty accountId records which account currently owns the visible list. */
@Entity(tableName = "watch_later_sync", primaryKeys = ["accountId", "videoKey"])
data class WatchLaterSyncState(val accountId: String, val videoKey: String, val elementId: Long?, val state: String)

@Dao interface WatchLaterSyncDao {
    @Query("SELECT videoKey FROM watch_later_sync WHERE accountId = '' LIMIT 1") suspend fun owner(): String?
    @Query("DELETE FROM watch_later_sync WHERE accountId = ''") suspend fun clearOwner()
    @Query("SELECT * FROM watch_later_sync WHERE accountId = :accountId") suspend fun states(accountId: String): List<WatchLaterSyncState>
    @Query("SELECT * FROM watch_later_sync WHERE accountId = :accountId AND videoKey = :videoKey") suspend fun state(accountId: String, videoKey: String): WatchLaterSyncState?
    @Query("SELECT DISTINCT accountId FROM watch_later_sync WHERE accountId != ''") suspend fun accounts(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(state: WatchLaterSyncState)
    @Query("DELETE FROM watch_later_sync WHERE accountId = :accountId AND videoKey = :videoKey") suspend fun delete(accountId: String, videoKey: String)
    @Query("DELETE FROM watch_later_sync WHERE accountId = :accountId") suspend fun deleteAccount(accountId: String)
}

internal fun watchLaterKey(identity: String): String = identity.substringAfterLast('|').lowercase()
private fun storedWatchLaterKind(accountId: String) = "$WATCH_LATER@$accountId"

internal data class WatchLaterPlan(
    val upload: List<String> = emptyList(),
    val removeRemote: Map<String, List<Long>> = emptyMap(),
    val link: Map<String, Long> = emptyMap(),
    val download: Map<String, Long> = emptyMap(),
    val removeLocal: List<String> = emptyList(),
    val clearState: List<String> = emptyList()
)

/** Three-way merge. [remote] must be the complete server list, or absent videos would look deleted. */
internal fun planWatchLaterSync(local: Set<String>, states: List<WatchLaterSyncState>, remote: Map<String, List<Long>>): WatchLaterPlan {
    val known = states.associateBy { it.videoKey }
    val upload = mutableListOf<String>(); val removeRemote = linkedMapOf<String, List<Long>>()
    val link = linkedMapOf<String, Long>(); val download = linkedMapOf<String, Long>()
    val removeLocal = mutableListOf<String>(); val clearState = mutableListOf<String>()
    for (key in local + known.keys + remote.keys) {
        val state = known[key]?.state
        val elements = remote[key].orEmpty()
        when {
            state == WATCH_LATER_REMOVE -> if (elements.isNotEmpty()) removeRemote[key] = elements else clearState += key
            key in local && elements.isNotEmpty() -> link[key] = elements.first()
            key in local -> when (state) {
                WATCH_LATER_SYNCED -> removeLocal += key
                WATCH_LATER_LOCAL_ONLY -> Unit
                else -> upload += key
            }
            elements.isNotEmpty() -> download[key] = elements.first()
            else -> clearState += key
        }
    }
    return WatchLaterPlan(upload, removeRemote, link, download, removeLocal, clearState)
}

/** Where the per-video sync states of one synced list live. */
private interface ElementStore {
    suspend fun all(): List<WatchLaterSyncState>
    suspend fun get(key: String): WatchLaterSyncState?
    suspend fun put(key: String, element: Long?, state: String)
    suspend fun delete(key: String)
}

/** Syncs "Watch later" and the ordinary playlists of the active PeerTube account. */
internal class WatchLaterSync private constructor(context: Context) {
    private val database = PeerIcheDatabase.get(context)
    private val library = database.library()
    private val syncStates = database.watchLaterSync()
    private val playlistStates = database.playlistSync()
    private val accounts = PeerTubeAccounts.get(context)
    private val accountLibrary = AccountLibraries.get(context)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val playlists = ConcurrentHashMap<String, Long>()

    init {
        scope.launch { try { accounts.initialize() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
        scope.launch {
            accounts.state.filter { it.ready && !it.storageError }.distinctUntilChanged { old, new ->
                old.activeId == new.activeId && old.accounts.map { it.id to it.needsLogin } == new.accounts.map { it.id to it.needsLogin }
            }.collect { state ->
                try { lock.withLock { adoptAccount(state) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                requestSync()
            }
        }
        scope.launch {
            for (request in requests) {
                delay(800)
                try { lock.withLock { syncActiveAccount() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Pending changes stay queued for the next trigger. */ }
            }
        }
        runCatching {
            val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() { override fun onAvailable(network: Network) { requestSync() } })
        }
    }

    fun requestSync() { requests.trySend(Unit) }

    suspend fun setSaved(video: Video, server: String, saved: Boolean) {
        val row = video.libraryRow(server, WATCH_LATER)
        if (saved) database.withTransaction {
            val key = watchLaterKey(row.identity)
            if (library.getAll(WATCH_LATER).none { watchLaterKey(it.identity) == key }) library.put(row)
            syncStates.owner()?.let { owner ->
                if (syncStates.state(owner, key)?.state == WATCH_LATER_REMOVE) syncStates.delete(owner, key)
            }
        } else remove(row.identity)
        requestSync()
    }

    suspend fun remove(identity: String) {
        val key = watchLaterKey(identity)
        database.withTransaction {
            library.getAll(WATCH_LATER).filter { watchLaterKey(it.identity) == key }.forEach { library.delete(it.identity, WATCH_LATER) }
            syncStates.owner()?.let { owner ->
                val state = syncStates.state(owner, key)
                if (state?.elementId != null) syncStates.put(state.copy(state = WATCH_LATER_REMOVE))
                else if (state != null) syncStates.delete(owner, key)
            }
        }
        requestSync()
    }

    // ---- Ordinary playlists: local edits ----

    /** Call after a local playlist is created, so the next pass uploads it. */
    fun playlistCreated() = requestSync()

    suspend fun addToPlaylist(localId: String, video: Video, server: String) {
        database.withTransaction {
            VideoPlaylists.addRow(database, localId, video, server)
            val key = watchLaterKey(video.libraryRow(server, playlistKind(localId)).identity)
            syncStates.owner()?.let { owner ->
                if (playlistStates.element(owner, localId, key)?.state == WATCH_LATER_REMOVE) playlistStates.deleteElement(owner, localId, key)
            }
        }
        requestSync()
    }

    suspend fun removeFromPlaylist(localId: String, identity: String) {
        if (localId == WATCH_LATER) return remove(identity)
        val kind = playlistKind(localId)
        val key = watchLaterKey(identity)
        database.withTransaction {
            library.getAll(kind).filter { watchLaterKey(it.identity) == key }.forEach { library.delete(it.identity, kind) }
            syncStates.owner()?.let { owner ->
                val state = playlistStates.element(owner, localId, key)
                if (state?.elementId != null) playlistStates.putElement(state.copy(state = WATCH_LATER_REMOVE))
                else if (state != null) playlistStates.deleteElement(owner, localId, key)
            }
        }
        requestSync()
    }

    /** Deletes a playlist here and, when it is linked to the account, on the server at the next pass. */
    suspend fun deletePlaylist(localId: String) {
        require(localId != WATCH_LATER)
        database.withTransaction {
            library.deleteKind(playlistKind(localId))
            library.delete(localId, PLAYLIST_DEFINITIONS)
            syncStates.owner()?.let { owner ->
                val link = playlistStates.link(owner, localId)
                playlistStates.deleteElements(owner, localId)
                if (link?.remoteId != null && link.state == PLAYLIST_SYNCED) playlistStates.putLink(link.copy(state = PLAYLIST_REMOVE))
                else if (link != null) playlistStates.deleteLink(owner, localId)
            }
        }
        requestSync()
    }

    private suspend fun adoptAccount(state: AccountsState) {
        val target = state.activeId.orEmpty()
        val known = state.accounts.map { it.id }.toSet()
        database.withTransaction {
            val owner = syncStates.owner().orEmpty()
            if (owner != target) {
                // A guest list stays visible so it merges into the first account that signs in.
                if (owner.isNotEmpty()) {
                    library.moveKind(WATCH_LATER, storedWatchLaterKind(owner))
                    visiblePlaylistKinds().forEach { library.moveKind(it, storedPlaylistKind(it, owner)) }
                }
                if (target.isNotEmpty()) {
                    library.moveKind(storedWatchLaterKind(target), WATCH_LATER)
                    library.kindsLike("$PLAYLIST_DEFINITIONS%@%").forEach { kind ->
                        storedPlaylistOwner(kind)?.takeIf { it.second == target }?.let { library.moveKind(kind, it.first) }
                    }
                }
                syncStates.clearOwner()
                if (target.isNotEmpty()) syncStates.put(WatchLaterSyncState("", target, null, WATCH_LATER_OWNER))
            }
            syncStates.accounts().filterNot { it in known }.forEach { syncStates.deleteAccount(it) }
            playlistStates.accounts().filterNot { it in known }.forEach { playlistStates.deleteAccountLinks(it); playlistStates.deleteAccountElements(it) }
            library.kindsLike("$WATCH_LATER@%").filterNot { it.removePrefix("$WATCH_LATER@") in known }.forEach { library.deleteKind(it) }
            library.kindsLike("$PLAYLIST_DEFINITIONS%@%").filter { kind -> storedPlaylistOwner(kind)?.second?.let { it !in known } == true }
                .forEach { library.deleteKind(it) }
        }
        playlists.keys.retainAll(known)
    }

    /** The playlist list and each playlist's videos, as shown for the current account. */
    private suspend fun visiblePlaylistKinds(): List<String> =
        listOf(PLAYLIST_DEFINITIONS) + library.kindsLike("$PLAYLIST_DEFINITIONS:%").filter(::isVisiblePlaylistKind)

    private suspend fun playlistId(account: AccountSummary): Long = playlists[account.id] ?: run {
        val json = accounts.request(account.id, AccountOperation.watchLaterPlaylists(account.username))
        val id = json.getAsJsonArray("data")?.map { it.asJsonObject }?.firstOrNull { playlist ->
            runCatching { playlist.getAsJsonObject("type").get("id").asInt }.getOrNull() == 2
        }?.get("id")?.asLong ?: throw AuthFailure("account_resource_missing", 404)
        playlists[account.id] = id
        id
    }

    private suspend fun remoteVideos(account: AccountSummary, playlist: Long): Map<String, Pair<Video?, List<Long>>> {
        val result = linkedMapOf<String, Pair<Video?, List<Long>>>()
        var start = 0
        while (true) {
            val page = accounts.request(account.id, AccountOperation.playlistElements(playlist, start))
            val rows = page.getAsJsonArray("data")?.map { it.asJsonObject } ?: throw AuthFailure("invalid_response")
            val total = page.get("total")?.asInt ?: throw AuthFailure("invalid_response")
            if (total > MAX_REMOTE_ELEMENTS) throw AuthFailure("invalid_response")
            rows.forEach { element ->
                val video = element.get("video")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val parsed = accountVideo(video, account.server)
                if (parsed.uuid.isBlank()) return@forEach
                val key = parsed.uuid.lowercase()
                result[key] = parsed to (result[key]?.second.orEmpty() + element.get("id").asLong)
            }
            start += rows.size
            if (start >= total) return result
            // A short list here must abort: treating it as complete would delete local videos.
            if (rows.isEmpty()) throw AuthFailure("invalid_response")
        }
    }

    private fun accountVideo(json: JsonObject, server: String): Video {
        val video = gson.fromJson(json, Video::class.java)
        return video.copy(host = video.host ?: video.channel?.host ?: video.account?.host ?: java.net.URI(server).authority,
            thumbnailUrl = video.thumbnailUrl ?: video.thumbnailPath?.let { java.net.URI("$server/").resolve(it).toString() })
    }

    private fun watchLaterStore(accountId: String) = object : ElementStore {
        override suspend fun all() = syncStates.states(accountId)
        override suspend fun get(key: String) = syncStates.state(accountId, key)
        override suspend fun put(key: String, element: Long?, state: String) = syncStates.put(WatchLaterSyncState(accountId, key, element, state))
        override suspend fun delete(key: String) = syncStates.delete(accountId, key)
    }

    private fun playlistStore(accountId: String, localId: String) = object : ElementStore {
        override suspend fun all() = playlistStates.elements(accountId, localId).map { WatchLaterSyncState(accountId, it.videoKey, it.elementId, it.state) }
        override suspend fun get(key: String) = playlistStates.element(accountId, localId, key)?.let { WatchLaterSyncState(accountId, key, it.elementId, it.state) }
        override suspend fun put(key: String, element: Long?, state: String) = playlistStates.putElement(PlaylistElementState(accountId, localId, key, element, state))
        override suspend fun delete(key: String) = playlistStates.deleteElement(accountId, localId, key)
    }

    private suspend fun syncActiveAccount() {
        val account = accounts.state.value.active?.takeUnless { it.needsLogin } ?: return
        if (syncStates.owner() != account.id) return
        // Each list is synced on its own, so a failure in one does not hold back the others.
        var failure: Exception? = null
        try {
            val playlist = playlistId(account)
            try { syncElements(account, playlist, WATCH_LATER, watchLaterStore(account.id)) }
            catch (error: AuthFailure) { if (error.status == 404) playlists.remove(account.id); throw error }
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error }
        try { syncPlaylists(account) }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = failure ?: error }
        failure?.let { throw it }
    }

    /** Three-way merge of one local list with one server playlist. */
    private suspend fun syncElements(account: AccountSummary, playlist: Long, kind: String, store: ElementStore) {
        val remote = remoteVideos(account, playlist)
        val plan = database.withTransaction {
            val rows = library.getAll(kind).associateBy { watchLaterKey(it.identity) }
            val next = planWatchLaterSync(rows.keys, store.all(), remote.mapValues { it.value.second })
            next.link.forEach { (key, element) -> store.put(key, element, WATCH_LATER_SYNCED) }
            next.removeLocal.forEach { key ->
                rows[key]?.let { library.delete(it.identity, kind) }
                store.delete(key)
            }
            next.clearState.forEach { store.delete(it) }
            var order = maxOf(System.currentTimeMillis(), (rows.values.maxOfOrNull { it.updatedAt } ?: 0) + 1)
            next.download.forEach { (key, element) ->
                val video = remote.getValue(key).first ?: return@forEach
                library.put(video.libraryRow(account.server, kind).copy(updatedAt = order++))
                store.put(key, element, WATCH_LATER_SYNCED)
            }
            next
        }
        plan.removeRemote.forEach { (key, elements) ->
            elements.forEach { element ->
                try { accounts.request(account.id, AccountOperation.removePlaylistElement(playlist, element)) }
                catch (failure: AuthFailure) { if (failure.status != 404) throw failure }
            }
            database.withTransaction {
                if (store.get(key)?.state == WATCH_LATER_REMOVE) store.delete(key)
            }
        }
        plan.upload.forEach { key -> upload(account, playlist, kind, store, key) }
    }

    private suspend fun upload(account: AccountSummary, playlist: Long, kind: String, store: ElementStore, key: String) {
        val row = library.getAll(kind).firstOrNull { watchLaterKey(it.identity) == key } ?: return
        val resolved = try { accountLibrary.resolveVideo(account.id, row.toVideo(), row.server) }
        catch (failure: AuthFailure) {
            if (failure.reason == "account_video_unavailable" || failure.status in listOf(403, 404)) { store.put(key, null, WATCH_LATER_LOCAL_ONLY); return }
            throw failure
        }
        val reply = try { accounts.request(account.id, AccountOperation.addPlaylistVideo(playlist, resolved.uuid)) }
        catch (failure: AuthFailure) {
            when (failure.status) {
                409 -> { requestSync(); return } // Already on the server; the next pass links it.
                400, 403 -> { store.put(key, null, WATCH_LATER_LOCAL_ONLY); return }
                404 -> { if (kind == WATCH_LATER) playlists.remove(account.id); throw failure }
                else -> throw failure
            }
        }
        val element = reply.getAsJsonObject("videoPlaylistElement")?.get("id")?.asLong ?: throw AuthFailure("invalid_response")
        val remoteKey = resolved.uuid.lowercase()
        var removedMeanwhile = false
        database.withTransaction {
            val current = library.getAll(kind).firstOrNull { watchLaterKey(it.identity) == key }
            if (current == null) {
                store.put(remoteKey, element, WATCH_LATER_REMOVE)
                removedMeanwhile = true
            } else {
                if (remoteKey != key) {
                    library.delete(current.identity, kind)
                    library.put(current.copy(identity = "${current.server}|${resolved.uuid}"))
                    store.delete(key)
                }
                store.put(remoteKey, element, WATCH_LATER_SYNCED)
            }
        }
        if (removedMeanwhile) requestSync()
    }

    // ---- Ordinary playlists: server side ----

    /** Every regular playlist of the account; any doubt about completeness aborts the pass. */
    private suspend fun remotePlaylists(account: AccountSummary): List<RemotePlaylist> {
        val result = mutableListOf<RemotePlaylist>()
        var start = 0
        while (true) {
            val page = accounts.request(account.id, AccountOperation.regularPlaylists(account.username, start))
            val rows = page.getAsJsonArray("data")?.map { it.asJsonObject } ?: throw AuthFailure("invalid_response")
            val total = page.get("total")?.asInt ?: throw AuthFailure("invalid_response")
            if (total > MAX_REMOTE_PLAYLISTS) throw AuthFailure("invalid_response")
            rows.forEach { row ->
                val id = runCatching { row.get("id").asLong }.getOrNull() ?: throw AuthFailure("invalid_response")
                val type = runCatching { row.getAsJsonObject("type").get("id").asInt }.getOrNull()
                val name = runCatching { row.get("displayName").asString }.getOrNull()?.trim().orEmpty()
                if (type == PEERTUBE_PLAYLIST_REGULAR && name.isNotEmpty()) result += RemotePlaylist(id, name.take(120))
            }
            start += rows.size
            if (start >= total) return result
            // A short list must abort: treating it as complete would delete local playlists.
            if (rows.isEmpty()) throw AuthFailure("invalid_response")
        }
    }

    private suspend fun syncPlaylists(account: AccountSummary) {
        val remote = remotePlaylists(account)
        val plan = database.withTransaction {
            val definitions = library.getAll(PLAYLIST_DEFINITIONS)
            val next = planPlaylistSync(definitions.associate { it.identity to it.title }, playlistStates.links(account.id), remote)
            next.dropLink.forEach { playlistStates.deleteLink(account.id, it); playlistStates.deleteElements(account.id, it) }
            next.link.forEach { (localId, remoteId) -> playlistStates.putLink(PlaylistLink(account.id, localId, remoteId, PLAYLIST_SYNCED)) }
            next.rename.forEach { (localId, name) ->
                definitions.firstOrNull { it.identity == localId }?.let { library.put(it.copy(title = name)) }
            }
            next.deleteLocal.forEach { localId ->
                library.deleteKind(playlistKind(localId))
                library.delete(localId, PLAYLIST_DEFINITIONS)
                playlistStates.deleteLink(account.id, localId); playlistStates.deleteElements(account.id, localId)
            }
            var order = maxOf(System.currentTimeMillis(), (definitions.maxOfOrNull { it.updatedAt } ?: 0) + 1)
            next.download.forEach { playlist ->
                val localId = java.util.UUID.randomUUID().toString()
                library.put(SavedVideo(localId, PLAYLIST_DEFINITIONS, playlist.name, null, "", null, null, 0, order++))
                playlistStates.putLink(PlaylistLink(account.id, localId, playlist.id, PLAYLIST_SYNCED))
            }
            next
        }
        plan.deleteRemote.forEach { (localId, remoteId) ->
            try { accounts.request(account.id, AccountOperation.deletePlaylist(remoteId)) }
            catch (failure: AuthFailure) { if (failure.status != 404) throw failure }
            database.withTransaction {
                if (playlistStates.link(account.id, localId)?.state == PLAYLIST_REMOVE) playlistStates.deleteLink(account.id, localId)
            }
        }
        plan.create.forEach { localId -> createRemote(account, localId) }
        // Videos of every linked playlist; one failing playlist does not stop the rest.
        var failure: Exception? = null
        val visible = library.getAll(PLAYLIST_DEFINITIONS).map { it.identity }.toSet()
        playlistStates.links(account.id).filter { it.state == PLAYLIST_SYNCED && it.remoteId != null && it.localId in visible }.forEach { link ->
            try { syncElements(account, link.remoteId!!, playlistKind(link.localId), playlistStore(account.id, link.localId)) }
            catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = failure ?: error }
        }
        failure?.let { throw it }
    }

    private suspend fun createRemote(account: AccountSummary, localId: String) {
        val definition = library.getAll(PLAYLIST_DEFINITIONS).firstOrNull { it.identity == localId } ?: return
        val reply = try { accounts.request(account.id, AccountOperation.createPrivatePlaylist(definition.title)) }
        catch (failure: AuthFailure) {
            // Refused by this server (a limit or a name it rejects): keep the playlist on the device only.
            if (failure.status == 400 || failure.status == 403 || failure.reason == "invalid_playlist") {
                playlistStates.putLink(PlaylistLink(account.id, localId, null, PLAYLIST_LOCAL_ONLY)); return
            }
            throw failure
        }
        val remoteId = reply.getAsJsonObject("videoPlaylist")?.get("id")?.asLong ?: throw AuthFailure("invalid_response")
        var deletedMeanwhile = false
        database.withTransaction {
            if (library.getAll(PLAYLIST_DEFINITIONS).any { it.identity == localId }) playlistStates.putLink(PlaylistLink(account.id, localId, remoteId, PLAYLIST_SYNCED))
            else { playlistStates.putLink(PlaylistLink(account.id, localId, remoteId, PLAYLIST_REMOVE)); deletedMeanwhile = true }
        }
        if (deletedMeanwhile) requestSync()
    }

    companion object {
        @Volatile private var instance: WatchLaterSync? = null
        fun get(context: Context): WatchLaterSync = instance ?: synchronized(this) {
            instance ?: WatchLaterSync(context.applicationContext).also { instance = it }
        }
    }
}
