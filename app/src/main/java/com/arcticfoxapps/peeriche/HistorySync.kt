package com.arcticfoxapps.peeriche

import android.content.Context
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

internal const val HISTORY = "history"
private const val VIEW_INTERVAL_MS = 15_000L
private const val HISTORY_IMPORT_LIMIT = 200
private const val SETTINGS_TTL_MS = 10 * 60 * 1000L

/** How server history (newest first) enters the local list. Keys are lower-case video UUIDs. */
internal data class HistoryImport(
    /** Watched on another device since the last pass: moved to the top, in this order. */
    val top: List<String> = emptyList(),
    /** Older server entries missing here: added below the local list, in this order. */
    val bottom: List<String> = emptyList(),
    /** The newest server entry, remembered to recognise newer ones next time. */
    val newest: String? = null,
    /** Removed on the server (or on another device) since they were last seen there: removed here too. */
    val removeLocal: List<String> = emptyList(),
    /** Server entries to remember for the next pass. */
    val seen: Set<String> = emptySet()
)

/**
 * PeerTube history has no view dates, only its order. Entries above the one that was newest at the last
 * pass are new and go to the top; on the first pass, or when that entry is gone, nothing is reordered.
 * Only [addLimit] newest entries are added here. A video that was in server history before ([seen]) and is
 * missing now was removed there; this is decided only when [complete] is true, since a partial list would
 * make older entries look removed.
 */
internal fun planHistoryImport(remote: List<String>, local: Set<String>, previousNewest: String?, pendingRemoval: Set<String>,
    seen: Set<String> = emptySet(), complete: Boolean = false, addLimit: Int = Int.MAX_VALUE): HistoryImport {
    val server = remote.filterNot { it in pendingRemoval }.distinct()
    val recent = server.take(addLimit)
    val mark = previousNewest?.let(recent::indexOf) ?: -1
    val top = if (mark > 0) recent.take(mark) else emptyList()
    val bottom = recent.filter { it !in local && it !in top }
    val present = server.toSet()
    val removeLocal = if (complete) local.filter { it in seen && it !in present } else emptyList()
    val nextSeen = if (complete) present else seen + present
    return HistoryImport(top, bottom, server.firstOrNull() ?: previousNewest.takeIf { !complete }, removeLocal, nextSeen)
}

internal fun historyKey(identity: String): String = identity.substringAfterLast('|').lowercase()

/**
 * Keeps History in step with the active PeerTube account: views with the position are sent while watching,
 * server history (other devices) is merged in, and removals here are removed on the server too.
 * Nothing is sent without an account, or when the account has history turned off.
 */
internal class HistorySync private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = PeerIcheDatabase.get(context)
    private val library = database.library()
    private val accounts = PeerTubeAccounts.get(context)
    private val accountLibrary = AccountLibraries.get(context)
    private val state = appContext.getSharedPreferences("history_sync", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val lastSent = ConcurrentHashMap<String, Long>()
    /** Account id to (history enabled, checked at). */
    private val enabled = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    /** "account|uuid" to whether the account server knows the video. */
    private val known = ConcurrentHashMap<String, Boolean>()

    init {
        scope.launch { try { accounts.initialize() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
        // Signing in or switching accounts brings that account's history in.
        scope.launch {
            accounts.state.filter { it.ready && !it.storageError }
                .distinctUntilChanged { old, new -> old.active?.id == new.active?.id && old.active?.needsLogin == new.active?.needsLogin }
                .collect { requestSync() }
        }
        scope.launch {
            for (request in requests) {
                delay(800)
                try { lock.withLock { syncActiveAccount() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Removals stay queued for the next pass. */ }
            }
        }
    }

    fun requestSync() { requests.trySend(Unit) }

    /** Called from the player with the history row of the playing video. [force] skips the interval (pause, end, seek). */
    fun report(row: SavedVideo, positionMs: Long, force: Boolean) {
        val key = historyKey(row.identity)
        val now = System.currentTimeMillis()
        if (!force && now - (lastSent[key] ?: 0) < VIEW_INTERVAL_MS) return
        lastSent[key] = now
        scope.launch {
            try {
                val account = activeAccount() ?: return@launch
                // Only videos that reached History (5 s of watching) are sent, like local history.
                if (library.count(row.identity, HISTORY) == 0) return@launch
                if (!historyEnabled(account)) return@launch
                val uuid = accountUuid(account, row) ?: return@launch
                accounts.request(account.id, AccountOperation.view(uuid, positionMs / 1000))
                synchronized(state) {
                    state.edit().putString(newestKey(account.id), uuid.lowercase())
                        .putStringSet(seenKey(account.id), seen(account.id) + uuid.lowercase()).apply()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missed view only loses that position; the next one replaces it. */ }
        }
    }

    suspend fun remove(identity: String) {
        val key = historyKey(identity)
        database.withTransaction {
            library.getAll(HISTORY).filter { historyKey(it.identity) == key }.forEach { library.delete(it.identity, HISTORY) }
        }
        activeAccount()?.let { account ->
            synchronized(state) { state.edit().putStringSet(removalsKey(account.id), removals(account.id) + key).apply() }
            requestSync()
        }
    }

    suspend fun clear() {
        library.deleteKind(HISTORY)
        activeAccount()?.let { account ->
            synchronized(state) {
                state.edit().putLong(clearKey(account.id), System.currentTimeMillis()).remove(removalsKey(account.id)).remove(newestKey(account.id)).apply()
            }
            requestSync()
        }
    }

    private fun activeAccount() = accounts.state.value.active?.takeUnless { it.needsLogin }
    private fun newestKey(account: String) = "newest:$account"
    private fun removalsKey(account: String) = "removals:$account"
    private fun clearKey(account: String) = "clear:$account"
    private fun removals(account: String): Set<String> = state.getStringSet(removalsKey(account), null).orEmpty().toSet()
    private fun seenKey(account: String) = "seen:$account"
    /** Videos known to be in the account's server history after the last pass or view. */
    private fun seen(account: String): Set<String> = state.getStringSet(seenKey(account), null).orEmpty().toSet()

    private suspend fun historyEnabled(account: AccountSummary): Boolean {
        enabled[account.id]?.takeIf { System.currentTimeMillis() - it.second < SETTINGS_TTL_MS }?.let { return it.first }
        val value = accounts.request(account.id, AccountOperation.ownChannels()).get("videosHistoryEnabled")?.takeUnless { it.isJsonNull }?.asBoolean ?: true
        enabled[account.id] = value to System.currentTimeMillis()
        return value
    }

    /** The UUID the account server uses for this video, or null when it cannot find it. */
    private suspend fun accountUuid(account: AccountSummary, row: SavedVideo): String? {
        val uuid = historyKey(row.identity)
        val cacheKey = "${account.id}|$uuid"
        known[cacheKey]?.let { return if (it) uuid else null }
        val found = try { accountLibrary.resolveVideo(account.id, row.toVideo().copy(host = row.server), row.server).uuid.lowercase() }
        catch (failure: AuthFailure) {
            if (failure.reason == "account_video_unavailable" || failure.status in listOf(403, 404)) null else throw failure
        }
        known[cacheKey] = found == uuid
        return found?.takeIf { it == uuid }
    }

    private suspend fun syncActiveAccount() {
        val account = activeAccount() ?: return
        sendClear(account)
        sendRemovals(account)
        if (!historyEnabled(account)) return
        importHistory(account)
    }

    private suspend fun sendClear(account: AccountSummary) {
        val since = state.getLong(clearKey(account.id), 0)
        if (since <= 0) return
        val date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(since))
        accounts.request(account.id, AccountOperation.clearHistory(date))
        synchronized(state) { if (state.getLong(clearKey(account.id), 0) == since) state.edit().remove(clearKey(account.id)).apply() }
    }

    private suspend fun sendRemovals(account: AccountSummary) {
        for (uuid in removals(account.id)) {
            val videoId = try { accounts.request(account.id, AccountOperation.video(uuid)).get("id")?.asLong }
            catch (failure: AuthFailure) { if (failure.status == 404) null else throw failure }
            if (videoId != null) {
                try { accounts.request(account.id, AccountOperation.removeHistoryVideo(videoId)) }
                catch (failure: AuthFailure) { if (failure.status != 404) throw failure }
            }
            synchronized(state) { state.edit().putStringSet(removalsKey(account.id), removals(account.id) - uuid).apply() }
        }
    }

    private suspend fun importHistory(account: AccountSummary) {
        // Never merge server history over a clear or removal that has not reached the server yet.
        if (state.getLong(clearKey(account.id), 0) > 0) return
        // The whole server history is read (up to the local limit), so removals there can be recognised.
        val videos = linkedMapOf<String, Pair<Video, Long>>()
        var start = 0
        var complete = false
        while (start < MAX_HISTORY_ROWS) {
            val page = accounts.request(account.id, AccountOperation.history(start))
            val rows = page.getAsJsonArray("data")?.map { it.asJsonObject } ?: throw AuthFailure("invalid_response")
            val total = page.get("total")?.asInt ?: throw AuthFailure("invalid_response")
            rows.forEach { json ->
                val video = accountVideo(json, account.server)
                val key = video.uuid.lowercase()
                if (key.isBlank() || key in videos) return@forEach
                val seconds = runCatching { json.getAsJsonObject("userHistory").get("currentTime").asLong }.getOrDefault(0)
                videos[key] = video to seconds
            }
            start += rows.size
            if (start >= total) { complete = true; break }
            // A short page must abort: a partial list would make the rest look removed.
            if (rows.isEmpty()) throw AuthFailure("invalid_response")
        }
        val positions = PlaybackPositions(appContext)
        database.withTransaction {
            val local = library.getAll(HISTORY)
            val plan = planHistoryImport(videos.filterValues { !it.first.isLive }.keys.toList(), local.map { historyKey(it.identity) }.toSet(),
                state.getString(newestKey(account.id), null), removals(account.id), seen(account.id), complete, HISTORY_IMPORT_LIMIT)
            plan.removeLocal.toSet().let { gone -> local.filter { historyKey(it.identity) in gone }.forEach { library.delete(it.identity, HISTORY) } }
            synchronized(state) { state.edit().putStringSet(seenKey(account.id), plan.seen).apply() }
            val now = System.currentTimeMillis()
            plan.top.forEachIndexed { index, key ->
                val (video, seconds) = videos.getValue(key)
                val existing = local.firstOrNull { historyKey(it.identity) == key }
                library.put((existing ?: video.toSavedVideo(account.server, HISTORY)).copy(updatedAt = now - index))
                // Continue where the other device stopped.
                if (seconds > 0) positions.save(video.sourceServer(account.server) + "|" + video.apiId(), seconds * 1000, video.duration * 1000, false)
            }
            var order = minOf(local.minOfOrNull { it.updatedAt } ?: now, now - plan.top.size) - 1
            plan.bottom.forEach { key -> library.put(videos.getValue(key).first.toSavedVideo(account.server, HISTORY).copy(updatedAt = order--)) }
            library.trimKind(HISTORY, MAX_HISTORY_ROWS)
            state.edit().apply { if (plan.newest == null) remove(newestKey(account.id)) else putString(newestKey(account.id), plan.newest) }.apply()
        }
    }

    private fun accountVideo(json: JsonObject, server: String): Video {
        val video = gson.fromJson(json, Video::class.java)
        return video.copy(host = video.host ?: video.channel?.host ?: video.account?.host ?: java.net.URI(server).authority,
            thumbnailUrl = video.thumbnailUrl ?: video.thumbnailPath?.let { java.net.URI("$server/").resolve(it).toString() })
    }

    companion object {
        @Volatile private var instance: HistorySync? = null
        fun get(context: Context): HistorySync = instance ?: synchronized(this) {
            instance ?: HistorySync(context.applicationContext).also { instance = it }
        }
    }
}
