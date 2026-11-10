package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject

internal fun JsonObject.objectAt(vararg path: String): JsonObject? = runCatching { path.fold(this) { item, key -> item.getAsJsonObject(key) } }.getOrNull()
internal fun JsonObject.longAt(key: String, fallback: Long = -1): Long = runCatching { get(key).asLong }.getOrDefault(fallback)
internal fun JsonObject.boolAt(key: String): Boolean = runCatching { get(key).asBoolean }.getOrDefault(false)
internal fun JsonObject.stringAt(key: String): String = runCatching { get(key).asString }.getOrDefault("")
internal data class CreatorChannel(val id: Long, val title: String, val name: String = "")
internal data class CreatorCapabilities(val channels: List<CreatorChannel>, val quota: Long, val dailyQuota: Long, val used: Long, val dailyUsed: Long,
    val extensions: List<String>, val minChunk: Int, val maxChunk: Int, val live: Boolean, val replay: Boolean, val coverMax: Long, val coverExtensions: List<String>,
    val httpImport: Boolean = false, val scheduledLives: Boolean = false) {
    fun validateFile(filename: String, size: Long) {
        if (size <= 0) throw AuthFailure("invalid_file")
        if (extensions.isNotEmpty() && extensions.none { filename.endsWith(it, ignoreCase = true) }) throw AuthFailure("upload_format")
        if ((quota >= 0 && size > (quota - used).coerceAtLeast(0)) || (dailyQuota >= 0 && size > (dailyQuota - dailyUsed).coerceAtLeast(0))) throw AuthFailure("upload_quota")
    }
    fun chunkSize(size: Long): Int {
        require(size > 0)
        val required = maxOf(1L, minChunk.toLong(), (size - 1) / 9999 + 1)
        val limit = if (maxChunk > 0) minOf(maxChunk.toLong(), 32L * 1024 * 1024) else 32L * 1024 * 1024
        val candidate = maxOf(required, minOf(1_048_576L, limit))
        if (candidate > limit) throw AuthFailure("upload_chunk_limit")
        return candidate.toInt()
    }
}
internal fun creatorCapabilities(me: JsonObject, config: JsonObject, used: JsonObject): CreatorCapabilities {
    fun extensions(section: JsonObject?) = runCatching { section?.getAsJsonArray("extensions")?.map { it.asString.lowercase() } }.getOrNull().orEmpty()
    val file = config.objectAt("video", "file")
    val image = config.objectAt("video", "image")
    val limits = config.objectAt("client", "videos", "resumableUpload")
    val channels = me.getAsJsonArray("videoChannels")?.mapNotNull { item -> item.asJsonObject.let {
        val id = it.longAt("id", 0); if (id <= 0) null else CreatorChannel(id, it.stringAt("displayName").ifBlank { it.stringAt("name") }, it.stringAt("name"))
    } }.orEmpty()
    return CreatorCapabilities(channels, me.longAt("videoQuota"), me.longAt("videoQuotaDaily"), used.longAt("videoQuotaUsed", 0), used.longAt("videoQuotaUsedDaily", 0),
        extensions(file), limits?.longAt("minChunkSize", 0)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        limits?.longAt("maxChunkSize", 0)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        config.objectAt("live")?.boolAt("enabled") == true, config.objectAt("live")?.boolAt("allowReplay") == true,
        image?.objectAt("size")?.longAt("max", 8_388_608) ?: 8_388_608, extensions(image),
        config.objectAt("import", "videos", "http")?.boolAt("enabled") == true, supportsLiveSchedules(config.stringAt("serverVersion")))
}
internal data class CreatorVideo(val video: Video, val privacy: Int, val state: Int, val stateLabel: String, val scheduledAt: Long? = null)
internal data class CreatorPage(val rows: List<CreatorVideo>, val total: Int)
internal class LiveCredentials(val url: String, val key: String, val watchUrl: String) {
    override fun toString() = "LiveCredentials(redacted)"
}
internal class CreatorRepository(private val accounts: AccountSessionManager) {
    private val gson = Gson()
    private fun owner(id: String) = accounts.state.value.active?.takeIf { it.id == id && !it.needsLogin } ?: throw AuthFailure("account_changed")
    suspend fun capabilities(id: String): CreatorCapabilities {
        owner(id)
        val me = accounts.request(id, AccountOperation.ownChannels())
        val config = accounts.request(id, AccountOperation.creatorConfig())
        val quota = accounts.request(id, AccountOperation.quotaUsed())
        owner(id)
        return creatorCapabilities(me, config, quota)
    }
    suspend fun videos(id: String, start: Int): CreatorPage {
        val account = owner(id)
        val json = accounts.request(id, AccountOperation.ownVideos(start)); owner(id)
        val rows = json.getAsJsonArray("data") ?: throw AuthFailure("invalid_response")
        return CreatorPage(rows.map { item ->
            val raw = item.asJsonObject
            val video = gson.fromJson(raw, Video::class.java).copy(host = java.net.URI(account.server).authority, playbackAccountId = id)
            CreatorVideo(video, raw.objectAt("privacy")?.longAt("id", 0)?.toInt() ?: 0,
                raw.objectAt("state")?.longAt("id", 0)?.toInt() ?: 0, raw.objectAt("state")?.stringAt("label").orEmpty(), scheduledLiveTime(raw))
        }, json.longAt("total", rows.size().toLong()).toInt())
    }
    suspend fun playbackVideo(id: String, uuid: String): Video {
        val account = owner(id)
        val raw = accounts.request(id, AccountOperation.video(uuid))
        owner(id)
        val video = gson.fromJson(raw, Video::class.java)
        if (video.apiId() != uuid) throw AuthFailure("invalid_response")
        val restricted = raw.objectAt("privacy")?.longAt("id", 3) !in listOf(1L, 2L)
        return video.copy(host = java.net.URI(account.server).authority,
            url = "${account.server}/videos/watch/$uuid", playbackAccountId = id.takeIf { restricted })
    }
    suspend fun createLive(id: String, metadata: CreatorMetadata, replay: Boolean, permanent: Boolean, startAt: Long? = null): String {
        val capabilities = capabilities(id)
        if (!capabilities.live || (replay && !capabilities.replay)) throw AuthFailure("live_disabled")
        if (capabilities.channels.none { it.id == metadata.channelId }) throw AuthFailure("invalid_channel")
        if (startAt != null && !capabilities.scheduledLives) throw AuthFailure("schedule_unsupported")
        validateLiveSchedule(startAt)
        // Only a user-initiated request: never automatically replay after network failure.
        return uploadedUuid(accounts.request(id, AccountOperation.createLive(metadata, replay, permanent, startAt)))
    }
    suspend fun importVideo(id: String, metadata: CreatorMetadata, url: String): String? {
        val caps = capabilities(id)
        if (!caps.httpImport) throw AuthFailure("import_disabled")
        if (caps.channels.none { it.id == metadata.channelId }) throw AuthFailure("invalid_channel")
        val target = validatedImportUrl(url)
        val operation = AccountOperation.importVideo(metadata, target)
        // Remember existing jobs: a failed response must not confuse an older import with a new one.
        val before = runCatching { importJobs(id, target).map { it.longAt("id") }.toSet() }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        }
        try {
            return importedUuid(accounts.request(id, operation))
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure is AuthFailure && failure.status == 0 && failure.reason != "invalid_response") throw failure
            if (failure is AuthFailure && failure.status in 400..499) {
                if (failure.status == 409) throw AuthFailure("import_disabled", 409)
                if (failure.status == 400 && failure.reason == "request_failed") throw AuthFailure("import_source_failed", 400)
                throw failure
            }
            // Read-only reconciliation; never issue a second publication automatically.
            val created = runCatching { importJobs(id, target).firstOrNull { before != null && it.longAt("id") !in before &&
                it.stringAt("targetUrl") == target && recentImport(it) } }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                null
            }
            if (created != null) return importedUuid(created)
            throw AuthFailure("import_unknown")
        }
    }
    private suspend fun importJobs(id: String, url: String): List<JsonObject> =
        accounts.request(id, AccountOperation.imports(url)).getAsJsonArray("data")?.map { it.asJsonObject }.orEmpty()
    suspend fun liveSchedule(id: String, uuid: String): Long? {
        owner(id)
        val data = accounts.request(id, AccountOperation.live(uuid)); owner(id)
        return scheduledLiveTime(data)
    }
    suspend fun updateLiveSchedule(id: String, uuid: String, startAt: Long?) {
        if (!capabilities(id).scheduledLives) throw AuthFailure("schedule_unsupported")
        validateLiveSchedule(startAt)
        // The server checks ownership and rejects updates after the broadcast starts.
        accounts.request(id, AccountOperation.scheduleLive(uuid, startAt))
    }
    suspend fun liveCredentials(id: String, uuid: String): LiveCredentials {
        val account = owner(id)
        val json = accounts.request(id, AccountOperation.live(uuid)); owner(id)
        val url = json.stringAt("rtmpsUrl").ifBlank { json.stringAt("rtmpUrl") }
        val key = json.stringAt("streamKey")
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        if (uri?.scheme !in listOf("rtmp", "rtmps") || uri?.host.isNullOrBlank() || uri?.userInfo != null || key.isBlank()) throw AuthFailure("invalid_response")
        return LiveCredentials(url, key, "${account.server}/videos/watch/$uuid")
    }
    suspend fun channel(id: String, channel: Long): JsonObject {
        val own = capabilities(id).channels.firstOrNull { it.id == channel } ?: throw AuthFailure("invalid_channel")
        return accounts.request(id, AccountOperation.creatorChannel(own.name))
    }
    suspend fun updateChannel(id: String, channel: Long, title: String, description: String) {
        val own = capabilities(id).channels.firstOrNull { it.id == channel } ?: throw AuthFailure("invalid_channel")
        accounts.request(id, AccountOperation.updateCreatorChannel(own.name, title, description))
    }
}
