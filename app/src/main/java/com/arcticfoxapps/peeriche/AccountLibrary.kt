package com.arcticfoxapps.peeriche

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AccountLibrarySnapshot(val own: List<PeerTubeChannel> = emptyList(), val subscriptions: List<PeerTubeChannel> = emptyList(),
    val loaded: Boolean = false, val busy: Boolean = false, val error: String? = null)

internal class AccountLibrary(private val accounts: AccountSessionManager) {
    private val gson = Gson()
    private val lock = Any()
    private val generations = mutableMapOf<String, Long>()
    private val mutable = MutableStateFlow<Map<String, AccountLibrarySnapshot>>(emptyMap())
    val snapshots = mutable.asStateFlow()
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal fun close() { scope.cancel() }
    init { scope.launch {
        accounts.state.collect { state -> if (state.ready) synchronized(lock) {
            val ids = state.accounts.map { it.id }.toSet()
            mutable.value = mutable.value.filterKeys { it in ids }
            generations.keys.retainAll(ids)
        } }
    } }
    private fun account(id: String): AccountSummary = accounts.state.value.accounts.firstOrNull { it.id == id && !it.needsLogin }
        ?: throw AuthFailure("session_expired")
    private fun data(json: JsonObject): com.google.gson.JsonArray = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw AuthFailure("invalid_response")
    private fun channels(json: JsonObject, server: String) = data(json).map { element ->
        val channel = gson.fromJson(element, PeerTubeChannel::class.java)
        if (channel.name.isBlank()) throw AuthFailure("invalid_response")
        channel.copy(host = channel.host ?: java.net.URI(server).authority).also { it.subscriptionHandle(server) }
    }
    suspend fun refresh(id: String) {
        val generation = synchronized(lock) {
            val next = (generations[id] ?: 0) + 1; generations[id] = next
            mutable.value = mutable.value + (id to (mutable.value[id] ?: AccountLibrarySnapshot()).copy(busy = true, error = null))
            next
        }
        try {
            val owner = account(id)
            val me = accounts.request(id, AccountOperation.ownChannels())
            if (me.get("id")?.asLong?.toString() != id.substringAfterLast('|')) throw AuthFailure("invalid_response")
            val own = me.getAsJsonArray("videoChannels")?.map { gson.fromJson(it, PeerTubeChannel::class.java)
                .let { channel -> channel.copy(host = channel.host ?: java.net.URI(owner.server).authority).also { it.subscriptionHandle(owner.server) } } }.orEmpty()
            synchronized(lock) { if (generations[id] == generation && accounts.state.value.accounts.any { it.id == id }) {
                mutable.value = mutable.value + (id to (mutable.value[id] ?: AccountLibrarySnapshot()).copy(own = own, busy = true, error = null))
            } }
            val followed = mutableListOf<PeerTubeChannel>()
            var start = 0
            while (start < 10_000) {
                val page = accounts.request(id, AccountOperation.subscriptions(start))
                val rows = channels(page, owner.server)
                followed += rows
                start += rows.size
                val total = page.get("total")?.asInt ?: start
                if (rows.isEmpty() || start >= total) break
            }
            synchronized(lock) { if (generations[id] == generation && accounts.state.value.accounts.any { it.id == id }) {
                mutable.value = mutable.value + (id to AccountLibrarySnapshot(own, followed.distinctBy { it.subscriptionHandle(owner.server) }, loaded = true))
            } }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (generations[id] == generation) mutable.value = mutable.value + (id to (mutable.value[id] ?: AccountLibrarySnapshot()).copy(busy = false)) }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(lock) { if (generations[id] == generation && accounts.state.value.accounts.any { it.id == id })
                mutable.value = mutable.value + (id to (mutable.value[id] ?: AccountLibrarySnapshot()).copy(busy = false, error = accountErrorCode(failure))) }
        }
    }
    suspend fun feed(id: String, start: Int, sensitive: Boolean): VideoPage {
        val owner = account(id)
        val json = accounts.request(id, AccountOperation.feed(start, sensitive))
        data(json)
        val page = gson.fromJson(json, VideoPage::class.java)
        return page.copy(data = page.data.map { video -> video.copy(
            host = video.host ?: video.channel?.host ?: video.account?.host ?: java.net.URI(owner.server).authority,
            thumbnailUrl = video.thumbnailUrl ?: video.thumbnailPath?.let { java.net.URI(owner.server + "/").resolve(it).toString() }) })
    }
    suspend fun isSubscribed(id: String, handle: String): Boolean = try {
        accounts.request(id, AccountOperation.subscription(handle)); true
    } catch (failure: AuthFailure) { if (failure.status == 404) false else throw failure }
    suspend fun setSubscribed(id: String, channel: PeerTubeChannel, wanted: Boolean) {
        val owner = account(id)
        val handle = channel.subscriptionHandle(owner.server)
        val actual = isSubscribed(id, handle)
        if (wanted != actual) try {
            accounts.request(id, if (wanted) AccountOperation.subscribe(handle) else AccountOperation.unsubscribe(handle))
        } catch (failure: AuthFailure) {
            if (!((wanted && failure.status == 409) || (!wanted && failure.status == 404)) || isSubscribed(id, handle) != wanted) throw failure
        }
        synchronized(lock) {
            if (accounts.state.value.accounts.none { it.id == id }) return@synchronized
            generations[id] = (generations[id] ?: 0) + 1
            val previous = mutable.value[id] ?: AccountLibrarySnapshot()
            val updated = previous.subscriptions.filterNot { it.subscriptionHandle(owner.server) == handle } + if (wanted) listOf(channel) else emptyList()
            mutable.value = mutable.value + (id to previous.copy(subscriptions = updated, busy = false, error = null))
            changed.value++
        }
    }
    suspend fun resolveVideo(id: String, video: Video, fallback: String): Video {
        val owner = account(id)
        val expected = video.apiId()
        val uuid = expected.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        if (uuid || video.sourceServer(fallback) == owner.server) {
            try {
                val resolved = gson.fromJson(accounts.request(id, AccountOperation.video(expected)), Video::class.java)
                if (uuid && !resolved.uuid.equals(expected, true)) throw AuthFailure("account_video_unavailable")
                if (resolved.uuid.isBlank()) throw AuthFailure("invalid_response")
                return resolved
            } catch (failure: AuthFailure) { if (failure.status != 404) throw failure }
        }
        val url = video.publicWatchUrl(fallback)
        val json = accounts.request(id, AccountOperation.resolve(url))
        return data(json).map { gson.fromJson(it, Video::class.java) }.firstOrNull {
            if (uuid) it.uuid.equals(expected, true) else it.publicWatchUrl(owner.server) == url
        } ?: throw AuthFailure("account_video_unavailable")
    }
    suspend fun rating(id: String, uuid: String): String {
        val rating = accounts.request(id, AccountOperation.rating(uuid)).get("rating")?.asString
        return rating?.takeIf { it in setOf("like", "dislike", "none") } ?: throw AuthFailure("invalid_response")
    }
    suspend fun rate(id: String, uuid: String, rating: String) { accounts.request(id, AccountOperation.rate(uuid, rating)) }
    suspend fun comments(id: String, uuid: String, start: Int): CommentPage {
        val json = accounts.request(id, AccountOperation.comments(uuid, start)); data(json)
        return gson.fromJson(json, CommentPage::class.java)
    }
    suspend fun thread(id: String, uuid: String, thread: Int): CommentTree = gson.fromJson(accounts.request(id, AccountOperation.thread(uuid, thread)), CommentTree::class.java)
    suspend fun post(id: String, uuid: String, text: String, reply: Int?): VideoComment {
        val json = accounts.request(id, AccountOperation.comment(uuid, text, reply))
        val comment = json.getAsJsonObject("comment") ?: throw AuthFailure("invalid_response")
        return gson.fromJson(comment, VideoComment::class.java).also { if (it.id <= 0) throw AuthFailure("invalid_response") }
    }
}

internal object AccountLibraries {
    @Volatile private var instance: AccountLibrary? = null
    fun get(context: Context): AccountLibrary = instance ?: synchronized(this) {
        instance ?: AccountLibrary(PeerTubeAccounts.get(context)).also { instance = it }
    }
}
