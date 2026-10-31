package com.arcticfoxapps.peeriche

import com.google.gson.JsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class VideoPage(val data: List<Video> = emptyList(), val total: Int = 0)
data class SearchPage(val videos: List<Video>, val hasMore: Boolean)
data class InstancePage(val data: List<PeerTubeInstance> = emptyList())
data class PeerTubeInstance(val host: String = "", val name: String? = null, val totalVideos: Long? = null, val country: String? = null)
data class PeerSeekPage(val videos: List<PeerSeekVideo> = emptyList(), val total: Int = 0)
data class PeerSeekVideo(
    val id: String? = null, val uuid: String? = null, val title: String? = null, val name: String? = null,
    val thumbnailUrl: String? = null, val videoUrl: String? = null, val views: Long? = null,
    val instance: JsonElement? = null, val channel: JsonElement? = null
) {
    fun asVideo(): Video? {
        val identity = uuid?.takeIf(String::isNotBlank) ?: id?.takeIf(String::isNotBlank) ?: return null
        fun field(element: JsonElement?, key: String): String? = runCatching {
            if (element == null || element.isJsonNull) null
            else if (element.isJsonObject) element.asJsonObject.get(key)?.takeUnless { it.isJsonNull }?.asString
            else element.asString
        }.getOrNull()
        val host = (field(instance, "host") ?: field(instance, "domain"))?.removePrefix("https://")?.trimEnd('/')
        val channelName = field(channel, "displayName") ?: field(channel, "name")
        val titleText = title?.takeIf(String::isNotBlank) ?: name?.takeIf(String::isNotBlank) ?: return null
        return Video(uuid = identity, name = titleText, thumbnailUrl = thumbnailUrl, url = videoUrl, views = views ?: 0L,
            host = host, channel = channelName?.let { Channel(displayName = it, host = host) })
    }
}
data class Video(
    val uuid: String = "", val name: String = "", val description: String? = null,
    val thumbnailPath: String? = null, val thumbnailUrl: String? = null,
    val views: Long = 0, val duration: Long = 0, val host: String? = null,
    val url: String? = null, val files: List<VideoFile>? = null,
    val streamingPlaylists: List<StreamingPlaylist>? = null,
    val channel: Channel? = null, val account: Account? = null
) {
    fun sourceServer(fallback: String): String {
        val urlHost = runCatching { java.net.URI(url).takeIf { it.scheme == "https" }?.host }.getOrNull()
        val sourceHost = urlHost ?: host ?: account?.host ?: channel?.host
        return sourceHost?.let { "https://$it" } ?: fallback
    }
    fun playableUrl(): String? = streamingPlaylists.orEmpty().firstNotNullOfOrNull { it.playlistUrl?.takeIf(String::isNotBlank) }
        ?: files.orEmpty().maxByOrNull { it.resolution?.id ?: 0 }?.fileUrl
}
data class VideoFile(val fileUrl: String? = null, val resolution: Resolution? = null)
data class Resolution(val id: Int = 0, val label: String? = null)
data class StreamingPlaylist(val playlistUrl: String? = null, val files: List<VideoFile>? = null)
data class Channel(val displayName: String? = null, val name: String? = null, val host: String? = null)
data class Account(val displayName: String? = null, val name: String? = null, val host: String? = null)

interface PeerTubeApi {
    @GET("api/v1/videos") suspend fun videos(
        @Query("sort") sort: String = "-publishedAt", @Query("count") count: Int = 30,
        @Query("isLocal") isLocal: Boolean? = null, @Query("start") start: Int = 0
    ): VideoPage
    @GET("api/v1/search/videos") suspend fun search(
        @Query("search") query: String, @Query("start") start: Int = 0,
        @Query("count") count: Int = 30, @Query("sort") sort: String = "-match"
    ): VideoPage
    @GET("api/v1/videos/{id}") suspend fun video(@Path("id") id: String): Video
}

interface SepiaApi {
    @GET("api/v1/search/videos") suspend fun search(
        @Query("search") query: String, @Query("start") start: Int = 0,
        @Query("count") count: Int = 30, @Query("sort") sort: String = "-match"
    ): VideoPage
}
interface PeerSeekApi {
    @GET("v1/search") suspend fun search(@Query("q") query: String, @Query("sort") sort: String = "relevance", @Query("page") page: Int = 1, @Query("size") size: Int = 30): PeerSeekPage
}
interface InstanceDirectoryApi {
    @GET("api/v1/instances") suspend fun instances(@Query("start") start: Int = 0, @Query("count") count: Int = 50): InstancePage
}

object PeerTubeClients {
    private val retrofitCache = linkedMapOf<String, PeerTubeApi>()
    private val sepia by lazy { Retrofit.Builder().baseUrl("https://sepiasearch.org/").addConverterFactory(GsonConverterFactory.create()).build().create(SepiaApi::class.java) }
    private val peerSeek by lazy { Retrofit.Builder().baseUrl("https://peerseek.video/").addConverterFactory(GsonConverterFactory.create()).build().create(PeerSeekApi::class.java) }
    private val directory by lazy { Retrofit.Builder().baseUrl("https://instances.joinpeertube.org/").addConverterFactory(GsonConverterFactory.create()).build().create(InstanceDirectoryApi::class.java) }
    @Synchronized fun server(value: String): PeerTubeApi {
        val host = normalizeServer(value)
        return retrofitCache.getOrPut(host) {
            Retrofit.Builder().baseUrl("$host/").addConverterFactory(GsonConverterFactory.create()).build().create(PeerTubeApi::class.java)
        }
    }
    suspend fun search(query: String, servers: List<String>): List<Video> {
        val sources = listOf<suspend () -> List<Video>>(
            { sepia.search(query).data },
            { peerSeek.search(query).videos.mapNotNull(PeerSeekVideo::asVideo) }
        ) + servers.take(5).map { host -> suspend { server(host).search(query).data } }
        val semaphore = Semaphore(3)
        val results = coroutineScope {
            sources.map { source -> async {
                semaphore.withPermit {
                    try { Result.success(source()) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                }
            } }.awaitAll()
        }
        val found = results.filter { it.isSuccess }.flatMap { it.getOrDefault(emptyList()) }
        if (found.isEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull() ?: IllegalStateException("No search sources available")
        return deduplicateVideos(found, servers.firstOrNull().orEmpty())
    }
    suspend fun searchPage(query: String, servers: List<String>, page: Int): SearchPage {
        val start = page.coerceAtLeast(0) * 30
        val sources = listOf<suspend () -> Pair<List<Video>, Boolean>>(
            { sepia.search(query, start = start).let { it.data to (start + it.data.size < it.total) } },
            { peerSeek.search(query, page = page + 1).let { it.videos.mapNotNull(PeerSeekVideo::asVideo) to (it.total > (page + 1) * 30) } }
        ) + servers.take(5).map { host -> suspend {
            server(host).search(query, start = start).let { it.data to (start + it.data.size < it.total) }
        } }
        val semaphore = Semaphore(3)
        val results = coroutineScope {
            sources.map { source -> async { semaphore.withPermit {
                try { Result.success(source()) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            } } }.awaitAll()
        }
        val successful = results.filter { it.isSuccess }.mapNotNull { it.getOrNull() }
        val allVideos = successful.flatMap { it.first }
        if (allVideos.isEmpty() && successful.isEmpty()) throw results.firstOrNull()?.exceptionOrNull() ?: IllegalStateException("No search sources available")
        return SearchPage(deduplicateVideos(allVideos, servers.firstOrNull().orEmpty()), successful.any { it.second })
    }
    suspend fun discoverInstances() = directory.instances().data.filter { it.host.isNotBlank() }
    suspend fun home(server: String) = server(server).videos(sort = "-publishedAt", count = 30).data
}

internal fun deduplicateVideos(videos: List<Video>, fallbackServer: String): List<Video> {
    val identities = hashSetOf<String>()
    return videos.filter { video ->
        val server = video.sourceServer(fallbackServer).trimEnd('/').lowercase()
        val item = video.uuid.ifBlank { video.url.orEmpty() }.lowercase()
        item.isNotBlank() && identities.add("$server|$item")
    }
}

fun normalizeServer(input: String): String {
    val raw = input.trim().let { if (it.contains("://")) it else "https://$it" }.trimEnd('/')
    val uri = java.net.URI(raw)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Enter a public HTTPS PeerTube address" }
    return "https://${uri.host}${if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""}"
}
