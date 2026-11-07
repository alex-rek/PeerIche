package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject

internal fun JsonObject.objectAt(vararg path: String): JsonObject? = runCatching { path.fold(this) { item, key -> item.getAsJsonObject(key) } }.getOrNull()
internal fun JsonObject.longAt(key: String, fallback: Long = -1): Long = runCatching { get(key).asLong }.getOrDefault(fallback)
internal fun JsonObject.boolAt(key: String): Boolean = runCatching { get(key).asBoolean }.getOrDefault(false)
internal fun JsonObject.stringAt(key: String): String = runCatching { get(key).asString }.getOrDefault("")
internal data class CreatorChannel(val id: Long, val title: String)
internal data class CreatorCapabilities(val channels: List<CreatorChannel>, val quota: Long, val dailyQuota: Long, val used: Long, val dailyUsed: Long,
    val extensions: List<String>, val minChunk: Int, val maxChunk: Int, val live: Boolean, val replay: Boolean, val coverMax: Long, val coverExtensions: List<String>) {
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
        val id = it.longAt("id", 0); if (id <= 0) null else CreatorChannel(id, it.stringAt("displayName").ifBlank { it.stringAt("name") })
    } }.orEmpty()
    return CreatorCapabilities(channels, me.longAt("videoQuota"), me.longAt("videoQuotaDaily"), used.longAt("videoQuotaUsed", 0), used.longAt("videoQuotaUsedDaily", 0),
        extensions(file), limits?.longAt("minChunkSize", 0)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        limits?.longAt("maxChunkSize", 0)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        config.objectAt("live")?.boolAt("enabled") == true, config.objectAt("live")?.boolAt("allowReplay") == true,
        image?.objectAt("size")?.longAt("max", 8_388_608) ?: 8_388_608, extensions(image))
}
internal data class CreatorVideo(val video: Video, val privacy: Int, val state: Int, val stateLabel: String)
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
            val video = gson.fromJson(raw, Video::class.java).copy(host = java.net.URI(account.server).authority)
            CreatorVideo(video, raw.objectAt("privacy")?.longAt("id", 0)?.toInt() ?: 0,
                raw.objectAt("state")?.longAt("id", 0)?.toInt() ?: 0, raw.objectAt("state")?.stringAt("label").orEmpty())
        }, json.longAt("total", rows.size().toLong()).toInt())
    }
    suspend fun createLive(id: String, metadata: CreatorMetadata, replay: Boolean, permanent: Boolean): String {
        val capabilities = capabilities(id)
        if (!capabilities.live || (replay && !capabilities.replay)) throw AuthFailure("live_disabled")
        if (capabilities.channels.none { it.id == metadata.channelId }) throw AuthFailure("invalid_channel")
        // Only a user-initiated request: never automatically replay after network failure.
        return uploadedUuid(accounts.request(id, AccountOperation.createLive(metadata, replay, permanent)))
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
}
