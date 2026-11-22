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

internal class WatchLaterSync private constructor(context: Context) {
    private val database = PeerIcheDatabase.get(context)
    private val library = database.library()
    private val syncStates = database.watchLaterSync()
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

    private suspend fun adoptAccount(state: AccountsState) {
        val target = state.activeId.orEmpty()
        val known = state.accounts.map { it.id }.toSet()
        database.withTransaction {
            val owner = syncStates.owner().orEmpty()
            if (owner != target) {
                // A guest list stays visible so it merges into the first account that signs in.
                if (owner.isNotEmpty()) library.moveKind(WATCH_LATER, storedWatchLaterKind(owner))
                if (target.isNotEmpty()) library.moveKind(storedWatchLaterKind(target), WATCH_LATER)
                syncStates.clearOwner()
                if (target.isNotEmpty()) syncStates.put(WatchLaterSyncState("", target, null, WATCH_LATER_OWNER))
            }
            syncStates.accounts().filterNot { it in known }.forEach { syncStates.deleteAccount(it) }
            library.kindsLike("$WATCH_LATER@%").filterNot { it.removePrefix("$WATCH_LATER@") in known }.forEach { library.deleteKind(it) }
        }
        playlists.keys.retainAll(known)
    }

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

    private suspend fun syncActiveAccount() {
        val account = accounts.state.value.active?.takeUnless { it.needsLogin } ?: return
        if (syncStates.owner() != account.id) return
        val playlist = playlistId(account)
        val remote = try { remoteVideos(account, playlist) }
        catch (failure: AuthFailure) { if (failure.status == 404) playlists.remove(account.id); throw failure }
        val plan = database.withTransaction {
            val rows = library.getAll(WATCH_LATER).associateBy { watchLaterKey(it.identity) }
            val next = planWatchLaterSync(rows.keys, syncStates.states(account.id), remote.mapValues { it.value.second })
            next.link.forEach { (key, element) -> syncStates.put(WatchLaterSyncState(account.id, key, element, WATCH_LATER_SYNCED)) }
            next.removeLocal.forEach { key ->
                rows[key]?.let { library.delete(it.identity, WATCH_LATER) }
                syncStates.delete(account.id, key)
            }
            next.clearState.forEach { syncStates.delete(account.id, it) }
            var order = maxOf(System.currentTimeMillis(), (rows.values.maxOfOrNull { it.updatedAt } ?: 0) + 1)
            next.download.forEach { (key, element) ->
                val video = remote.getValue(key).first ?: return@forEach
                library.put(video.libraryRow(account.server, WATCH_LATER).copy(updatedAt = order++))
                syncStates.put(WatchLaterSyncState(account.id, key, element, WATCH_LATER_SYNCED))
            }
            next
        }
        plan.removeRemote.forEach { (key, elements) ->
            elements.forEach { element ->
                try { accounts.request(account.id, AccountOperation.removePlaylistElement(playlist, element)) }
                catch (failure: AuthFailure) { if (failure.status != 404) throw failure }
            }
            database.withTransaction {
                if (syncStates.state(account.id, key)?.state == WATCH_LATER_REMOVE) syncStates.delete(account.id, key)
            }
        }
        plan.upload.forEach { key -> upload(account, playlist, key) }
    }

    private suspend fun markLocalOnly(accountId: String, key: String) =
        syncStates.put(WatchLaterSyncState(accountId, key, null, WATCH_LATER_LOCAL_ONLY))

    private suspend fun upload(account: AccountSummary, playlist: Long, key: String) {
        val row = library.getAll(WATCH_LATER).firstOrNull { watchLaterKey(it.identity) == key } ?: return
        val resolved = try { accountLibrary.resolveVideo(account.id, row.toVideo(), row.server) }
        catch (failure: AuthFailure) {
            if (failure.reason == "account_video_unavailable" || failure.status in listOf(403, 404)) { markLocalOnly(account.id, key); return }
            throw failure
        }
        val reply = try { accounts.request(account.id, AccountOperation.addPlaylistVideo(playlist, resolved.uuid)) }
        catch (failure: AuthFailure) {
            when (failure.status) {
                409 -> { requestSync(); return } // Already on the server; the next pass links it.
                400, 403 -> { markLocalOnly(account.id, key); return }
                404 -> { playlists.remove(account.id); throw failure }
                else -> throw failure
            }
        }
        val element = reply.getAsJsonObject("videoPlaylistElement")?.get("id")?.asLong ?: throw AuthFailure("invalid_response")
        val remoteKey = resolved.uuid.lowercase()
        var removedMeanwhile = false
        database.withTransaction {
            val current = library.getAll(WATCH_LATER).firstOrNull { watchLaterKey(it.identity) == key }
            if (current == null) {
                syncStates.put(WatchLaterSyncState(account.id, remoteKey, element, WATCH_LATER_REMOVE))
                removedMeanwhile = true
            } else {
                if (remoteKey != key) {
                    library.delete(current.identity, WATCH_LATER)
                    library.put(current.copy(identity = "${current.server}|${resolved.uuid}"))
                    syncStates.delete(account.id, key)
                }
                syncStates.put(WatchLaterSyncState(account.id, remoteKey, element, WATCH_LATER_SYNCED))
            }
        }
        if (removedMeanwhile) requestSync()
    }

    companion object {
        @Volatile private var instance: WatchLaterSync? = null
        fun get(context: Context): WatchLaterSync = instance ?: synchronized(this) {
            instance ?: WatchLaterSync(context.applicationContext).also { instance = it }
        }
    }
}
