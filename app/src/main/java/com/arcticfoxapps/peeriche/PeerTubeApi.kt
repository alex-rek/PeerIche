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
data class ChannelPage(val data: List<PeerTubeChannel> = emptyList(), val total: Int = 0)
data class CommentPage(val data: List<VideoComment> = emptyList(), val total: Int = 0)
data class VideoComment(val id: Int = 0, val text: String? = null, val account: Account? = null,
    val createdAt: String? = null, val totalReplies: Int = 0, val isDeleted: Boolean = false, val heldForReview: Boolean = false)
data class CommentTree(val comment: VideoComment? = null, val children: List<CommentTree> = emptyList())
data class FullVideoDescription(val description: String = "")
data class Avatar(val path: String? = null, val fileUrl: String? = null)
data class PeerTubeChannel(val displayName: String = "", val name: String = "", val host: String? = null, val videosCount: Int? = null,
    val followersCount: Long? = null, val avatars: List<Avatar>? = null)
data class SearchPage(val videos: List<Video>, val hasMore: Boolean, val nextPage: Int? = null)
data class InstancePage(val data: List<PeerTubeInstance> = emptyList(), val total: Int = 0)
data class InstanceStats(val totalVideos: Long? = null)
data class PeerTubeInstance(val host: String = "", val name: String? = null, val totalVideos: Long? = null, val country: String? = null, val stats: InstanceStats? = null)
data class PeerSeekPage(val videos: List<PeerSeekVideo> = emptyList(), val total: Int = 0)
data class PeerSeekVideo(
    val id: String? = null, val uuid: String? = null, val title: String? = null, val name: String? = null,
    val thumbnailUrl: String? = null, val videoUrl: String? = null, val views: Long? = null,
    val instance: JsonElement? = null, val channel: JsonElement? = null, val nsfw: Boolean = false
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
            host = host, channel = channelName?.let { Channel(displayName = it, host = host) }, nsfw = nsfw)
    }
}
data class Video(
    val uuid: String = "", val name: String = "", val description: String? = null,
    val publishedAt: String? = null,
    val thumbnailPath: String? = null, val thumbnailUrl: String? = null, val previewPath: String? = null,
    val views: Long = 0, val duration: Long = 0, val host: String? = null,
    val url: String? = null, val files: List<VideoFile>? = null,
    val streamingPlaylists: List<StreamingPlaylist>? = null,
    val directStreamUrl: String? = null,
    val channel: Channel? = null, val account: Account? = null, val nsfw: Boolean = false,
    val isLive: Boolean = false, val state: VideoState? = null, val commentsEnabled: Boolean = true,
    val comments: Int? = null, val likes: Long = 0, val dislikes: Long = 0,
    val playbackAccountId: String? = null, val downloadEnabled: Boolean = true
) {
    fun sourceServer(fallback: String): String {
        val urlHost = runCatching { java.net.URI(url).takeIf { it.scheme == "https" || it.scheme == "http" }?.host }.getOrNull()
        val sourceHost = urlHost ?: host ?: account?.host ?: channel?.host
        val normalizedHost = sourceHost?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
        return normalizedHost?.takeIf(String::isNotBlank)?.let { "https://$it" } ?: fallback
    }
    fun apiId(): String = uuid.takeIf(String::isNotBlank)
        ?: runCatching { java.net.URI(url).path.substringAfterLast('/') }.getOrDefault("")

    fun playableUrl(maxHeight: Int = 0): String? {
        fun secure(value: String?): String? {
            val raw = value?.trim()?.takeIf(String::isNotBlank)?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
            if (uri.userInfo != null || uri.host.isNullOrBlank() || uri.scheme !in setOf("https", "http")) return null
            return if (uri.scheme == "https") raw else raw.replaceFirst("http://", "https://")
        }
        val videoFiles = files.orEmpty() + streamingPlaylists.orEmpty().flatMap { it.files.orEmpty() }
        val preferred = if (maxHeight > 0) videoFiles.filter { (it.resolution?.id ?: 0) <= maxHeight }.ifEmpty { videoFiles.sortedBy { it.resolution?.id ?: 0 }.take(1) } else videoFiles
        return streamingPlaylists.orEmpty().firstNotNullOfOrNull { secure(it.playlistUrl) }
            ?: preferred.maxByOrNull { it.resolution?.id ?: 0 }?.fileUrl?.let(::secure)
            ?: secure(directStreamUrl)
    }

    fun thumbnailCandidates(fallback: String): List<String> {
        val bases = listOf(fallback, sourceServer(fallback)).distinct().mapNotNull {
            runCatching { java.net.URI(it.trimEnd('/') + "/") }.getOrNull()
        }
        return listOfNotNull(thumbnailUrl, thumbnailPath, previewPath).flatMap { value ->
            bases.mapNotNull { base -> runCatching {
                val candidate = java.net.URI(value.trim()).let { if (it.isAbsolute) it else base.resolve(it) }
                if (candidate.host.isNullOrBlank() || candidate.userInfo != null || candidate.scheme !in setOf("http", "https")) null
                else if (candidate.scheme == "http") candidate.toString().replaceFirst("http://", "https://") else candidate.toString()
            }.getOrNull() }
        }.distinct()
    }
}
/** PeerTube and reverse proxies may return scheme-relative or origin-relative media URLs. */
internal fun Video.resolvedMediaUrls(server: String): Video {
    fun resolve(value: String?): String? = value?.let { raw -> runCatching {
        val url = java.net.URI(server.trimEnd('/') + "/").resolve(raw.trim())
        require(url.userInfo == null && !url.host.isNullOrBlank() && url.scheme in listOf("http", "https"))
        url.toString().replaceFirst(Regex("^http:"), "https:")
    }.getOrNull() }
    fun VideoFile.resolved() = copy(fileUrl = resolve(fileUrl), fileDownloadUrl = resolve(fileDownloadUrl))
    return copy(files = files?.map { it.resolved() },
        streamingPlaylists = streamingPlaylists?.map { it.copy(playlistUrl = resolve(it.playlistUrl), files = it.files?.map { file -> file.resolved() }) },
        directStreamUrl = resolve(directStreamUrl))
}

data class VideoFile(val fileUrl: String? = null, val resolution: Resolution? = null, val fileDownloadUrl: String? = null, val size: Long = 0, val hasVideo: Boolean? = null)
data class VideoState(val id: Int = 0)
data class Resolution(val id: Int = 0, val label: String? = null)
data class StreamingPlaylist(val playlistUrl: String? = null, val files: List<VideoFile>? = null)
data class Channel(val displayName: String? = null, val name: String? = null, val host: String? = null, val avatars: List<Avatar>? = null)
data class Account(val displayName: String? = null, val name: String? = null, val host: String? = null, val avatars: List<Avatar>? = null)


interface PeerTubeApi {
    @GET("api/v1/videos") suspend fun videoListing(
        @Query("count") count: Int = 30, @Query("start") start: Int = 0,
        @Query("isLocal") isLocal: Boolean? = null, @Query("nsfw") nsfw: String = "false",
        @Query("isLive") isLive: Boolean? = null, @Query("includeScheduledLive") includeScheduledLive: Boolean? = null,
        @Query("sort") sort: String = "-publishedAt", @Query("categoryOneOf") categories: List<Int>? = null
    ): okhttp3.ResponseBody
    @GET("api/v1/videos") suspend fun videos(
        @Query("sort") sort: String = "-publishedAt", @Query("count") count: Int = 30,
        @Query("isLocal") isLocal: Boolean? = null, @Query("start") start: Int = 0,
        @Query("isLive") isLive: Boolean? = null, @Query("includeScheduledLive") includeScheduledLive: Boolean? = null,
        @Query("nsfw") nsfw: String = "false"
    ): VideoPage
    @GET("api/v1/search/videos") suspend fun search(
        @Query("search") query: String, @Query("start") start: Int = 0,
        @Query("count") count: Int = 30, @Query("sort") sort: String = "-match", @Query("nsfw") nsfw: String = "false"
    ): VideoPage
    @GET("api/v1/videos/{id}") suspend fun video(@Path("id") id: String): Video
    @GET("api/v1/videos/{id}/description") suspend fun description(@Path("id") id: String): FullVideoDescription
    @GET("api/v1/videos/{id}/comment-threads") suspend fun comments(@Path("id") id: String, @Query("start") start: Int = 0, @Query("count") count: Int = 20): CommentPage
    @GET("api/v1/videos/{id}/comment-threads/{thread}") suspend fun commentThread(@Path("id") id: String, @Path("thread") thread: Int): CommentTree
    @GET("api/v1/video-channels/{handle}") suspend fun channel(@Path(value = "handle", encoded = true) handle: String): PeerTubeChannel
    @GET("api/v1/videos/{id}/captions") suspend fun captions(@Path("id") id: String): CaptionPage
    @GET("api/v1/video-channels/{handle}/videos") suspend fun channelVideos(@Path(value = "handle", encoded = true) handle: String, @Query("count") count: Int = 30): VideoPage
    @GET("api/v1/video-channels") suspend fun channels(@Query("count") count: Int = 100, @Query("start") start: Int = 0, @Query("sort") sort: String = "-createdAt"): ChannelPage
}

interface SepiaApi {
    @GET("api/v1/search/videos") suspend fun live(
        @Query("start") start: Int = 0, @Query("count") count: Int = 30,
        @Query("sort") sort: String = "-views", @Query("isLive") isLive: Boolean = true,
        @Query("includeScheduledLive") includeScheduledLive: Boolean = false, @Query("nsfw") nsfw: String = "false"
    ): VideoPage
    @GET("api/v1/search/videos") suspend fun search(
        @Query("search") query: String, @Query("start") start: Int = 0,
        @Query("count") count: Int = 30, @Query("sort") sort: String = "-match", @Query("nsfw") nsfw: String = "false"
    ): VideoPage
}
interface PeerSeekApi {
    @GET("v1/search") suspend fun search(@Query("q") query: String, @Query("sort") sort: String = "relevance", @Query("page") page: Int = 1, @Query("size") size: Int = 30): PeerSeekPage
}
interface InstanceDirectoryApi {
    @GET("api/v1/instances/hosts") suspend fun hosts(@Query("start") start: Int, @Query("count") count: Int = 100,
        @Query("sort") sort: String = "host"): InstancePage
    @GET("api/v1/instances") suspend fun instances(@Query("start") start: Int = 0, @Query("count") count: Int = 50, @Query("search") search: String? = null): InstancePage
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
    internal suspend fun discoverAllHosts(): List<String> {
        val first = directory.hosts(0)
        require(first.total in 1..25_000 && first.data.isNotEmpty())
        val hosts = first.data.map { it.host }.toMutableList()
        while (hosts.size < first.total) {
            val page = directory.hosts(hosts.size)
            require(page.total == first.total && page.data.isNotEmpty())
            hosts.addAll(page.data.map { it.host })
        }
        require(hosts.size == first.total && hosts.distinct().size == hosts.size)
        return hosts
    }
    suspend fun discoverInstances() = directory.instances().data.filter { it.host.isNotBlank() }
    suspend fun discoverInstancePage(start: Int, search: String = "") = directory.instances(start = start, count = 100, search = search.trim().takeIf(String::isNotBlank))
    internal suspend fun interestVideos(server: String, categories: List<Int>, sensitive: Boolean): List<Video> =
        server(server).videoListing(categories = categories, nsfw = sensitive.toString()).use { parsePeerTubeVideoPage(it.string()).data }
    suspend fun home(server: String) = server(server).videos(sort = "-publishedAt", count = 30).data
    suspend fun popular(server: String) = server(server).videos(sort = "-views", count = 30).data
    suspend fun serverPage(host: String, page: Int, sensitive: Boolean): SearchPage {
        val start = page.coerceAtLeast(0) * 30
        val response = checkedVideoListing(host, start = start, local = true, sensitive = sensitive)
        return SearchPage(response.data, start + response.data.size < response.total)
    }
    suspend fun checkServer(host: String): VideoPage = checkedVideoListing(host, count = 1)
    private suspend fun checkedVideoListing(host: String, start: Int = 0, count: Int = 30, local: Boolean? = null,
        sensitive: Boolean = false, live: Boolean? = null): VideoPage {
        val body = try { server(host).videoListing(count, start, local, sensitive.toString(), live, if (live == true) false else null) }
        catch (error: retrofit2.HttpException) { if (error.code() == 404) throw NotPeerTubeServerException() else throw error }
        return body.use { parsePeerTubeVideoPage(it.string()) }
    }
    suspend fun livePage(host: String, page: Int, sensitive: Boolean): SearchPage {
        val indexed = try { scanLivePages(page, host, sensitive,
            fetch = { index -> kotlinx.coroutines.withTimeout(15_000) { sepia.live(start = index * 30, nsfw = sensitive.toString()) } },
            resolve = { candidate -> server(candidate.sourceServer(host)).video(candidate.apiId()) }) }
        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (indexed != null && indexed.videos.isNotEmpty()) return indexed
        // The global live feed must not depend on a mistakenly selected non-PeerTube website.
        for (fallback in listOf(host, "https://peertube.tv").distinct()) {
            try {
                val response = kotlinx.coroutines.withTimeout(10_000) { checkedVideoListing(fallback, live = true, sensitive = sensitive) }
                val active = resolveLiveVideos(response.data, fallback) { candidate -> server(candidate.sourceServer(fallback)).video(candidate.apiId()) }
                if (active.isNotEmpty()) return SearchPage(active, indexed?.hasMore ?: false, indexed?.nextPage)
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { /* try the next source */ }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* try the next source */ }
        }
        return indexed ?: throw java.io.IOException("Live sources unavailable")
    }
    suspend fun video(server: String, id: String): Video = server(server).video(id).resolvedMediaUrls(server)
    suspend fun allChannels(server: String): List<PeerTubeChannel> {
        val api = server(server)
        val channels = mutableListOf<PeerTubeChannel>()
        var start = 0
        var total = Int.MAX_VALUE
        while (start < total && start < 10000) {
            val page = api.channels(count = 100, start = start)
            channels += page.data
            total = page.total.takeIf { it > 0 } ?: (start + page.data.size + if (page.data.size == 100) 1 else 0)
            if (page.data.isEmpty()) break
            start += page.data.size
        }
        return channels.sortedByDescending(PeerTubeChannel::videosCount)
    }
    suspend fun allChannelsWithVideoCounts(server: String): List<PeerTubeChannel> = coroutineScope {
        val api = server(server)
        val channels = allChannels(server)
        val semaphore = Semaphore(5)
        channels.map { channel -> async {
            val actualCount = semaphore.withPermit {
                runCatching { api.channelVideos(channel.name, count = 1).total }
                    .getOrDefault(channel.videosCount ?: 0)
            }
            channel.copy(videosCount = actualCount)
        } }.awaitAll().sortedByDescending { it.videosCount ?: 0 }
    }
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
