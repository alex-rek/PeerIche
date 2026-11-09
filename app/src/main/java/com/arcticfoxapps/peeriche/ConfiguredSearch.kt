package com.arcticfoxapps.peeriche

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap

object ConfiguredSearch {
    private val indexes = ConcurrentHashMap<String, SepiaApi>()
    private val peerSeek = Retrofit.Builder().baseUrl("https://peerseek.video/")
        .addConverterFactory(GsonConverterFactory.create()).build().create(PeerSeekApi::class.java)
    private val availability = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private fun index(url: String): SepiaApi = indexes.getOrPut(url) {
        Retrofit.Builder().baseUrl(normalizeServer(url) + "/").addConverterFactory(GsonConverterFactory.create()).build().create(SepiaApi::class.java)
    }
    fun record(video: Video, server: String, available: Boolean) {
        availability[video.sourceServer(server) + "|" + video.apiId()] = available to System.currentTimeMillis()
    }
    private fun known(video: Video, server: String): Boolean? = availability[video.sourceServer(server) + "|" + video.apiId()]
        ?.takeIf { System.currentTimeMillis() - it.second < 15 * 60 * 1000 }?.first

    suspend fun search(context: Context, query: String, servers: List<String>, active: String, page: Int, options: AppOptions): SearchPage {
        val start = page.coerceAtLeast(0) * 30
        val sensitive = options.includeSensitive && options.sensitiveDisplay != "hide"
        val nsfw = if (sensitive) "both" else "false"
        val cache = context.getSharedPreferences("search_cache", 0)
        val sources = linkedMapOf<String, suspend () -> SearchPage>()
        sources["sepia"] = { index("https://sepiasearch.org").search(query, start = start, sort = options.searchSort, nsfw = nsfw).let { SearchPage(it.data, start + it.data.size < it.total) } }
        sources["peerseek"] = { peerSeek.search(query, page = page + 1,
            sort = when (options.searchSort) { "-views" -> "views"; "-publishedAt" -> "date"; else -> "relevance" })
            .let { SearchPage(it.videos.mapNotNull(PeerSeekVideo::asVideo), (page + 1) * 30 < it.total) } }
        sources["federated"] = { PeerTubeClients.server(active).search(query, start = start, sort = options.searchSort, nsfw = nsfw)
            .let { SearchPage(it.data, start + it.data.size < it.total) } }
        sources["direct"] = {
            val requests = servers.take(5).map { host -> suspend {
                PeerTubeClients.server(host).search(query, start = start, sort = options.searchSort, nsfw = nsfw)
                    .let { SearchPage(it.data, start + it.data.size < it.total) }
            } }
            combine(requests, options.searchParallel)
        }
        sources["custom"] = {
            val requests = options.customIndexes.lines().filter(String::isNotBlank).take(10).map { url -> suspend {
                index(url).search(query, start = start, sort = options.searchSort, nsfw = nsfw).let { SearchPage(it.data, start + it.data.size < it.total) }
            } }
            combine(requests, options.searchParallel)
        }
        sources["local"] = {
            val videos = if (cache.getString("query", "").equals(query, true)) runCatching {
                Gson().fromJson<List<Video>>(cache.getString("videos", "[]"), object : TypeToken<List<Video>>() {}.type) ?: emptyList()
            }.getOrDefault(emptyList()) else emptyList()
            SearchPage(videos.drop(start).take(30), videos.size > start + 30)
        }
        val ordered = options.searchOrder.filter { it in options.searchSources && (it != "custom" || options.customIndexes.isNotBlank()) }
        val requests = ordered.mapNotNull { sources[it] }
        val batches = collectSearchBatches(requests, options, active)
        if (batches.isEmpty()) error("No search source responded")
        var videos = deduplicateVideos(batches.flatMap { it.videos }, active).filter { sensitive || !it.nsfw }
        videos = sortSearchVideos(videos, options.searchSort, active)
        if (options.availability != "all") {
            if (options.availability == "available_only") coroutineScope {
                val semaphore = Semaphore(options.searchParallel.coerceIn(1, 6))
                videos.take(100).map { video -> async { semaphore.withPermit {
                    if (known(video, active) == null) {
                        try {
                            val details = withTimeout(5000) { PeerTubeClients.video(video.sourceServer(active), video.apiId()) }
                            record(video, active, details.playableUrl() != null)
                        } catch (timeout: TimeoutCancellationException) { }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { if (error is HttpException && error.code() in listOf(404, 410)) record(video, active, false) }
                    }
                } } }.awaitAll()
            }
            videos = videos.filter { if (options.availability == "available_only") known(it, active) == true else known(it, active) != false }
        }
        if (videos.isNotEmpty()) {
            val previous = if (page > 0 && cache.getString("query", "").equals(query, true)) runCatching {
                Gson().fromJson<List<Video>>(cache.getString("videos", "[]"), object : TypeToken<List<Video>>() {}.type).orEmpty()
            }.getOrDefault(emptyList()) else emptyList()
            cache.edit().putString("query", query).putString("videos", Gson().toJson(deduplicateVideos(previous + videos, active).take(300))).apply()
        }
        return SearchPage(videos, batches.any { it.hasMore })
    }

    private suspend fun combine(requests: List<suspend () -> SearchPage>, parallel: Int): SearchPage = coroutineScope {
        val semaphore = Semaphore(parallel.coerceIn(1, 6))
        val results = requests.map { request -> async { semaphore.withPermit {
            try { request() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        } } }.awaitAll().filterNotNull()
        check(results.isNotEmpty()) { "No server responded" }
        SearchPage(results.flatMap { it.videos }, results.any { it.hasMore })
    }
}

internal fun sortSearchVideos(videos: List<Video>, sort: String, server: String): List<Video> = when (sort) {
    "-views" -> sortHomeVideos(videos, "views", server)
    "-publishedAt" -> sortHomeVideos(videos, "newest", server)
    else -> deduplicateVideos(videos, server)
}

internal suspend fun collectSearchBatches(requests: List<suspend () -> SearchPage>, options: AppOptions, active: String): List<SearchPage> {
    val batches = mutableListOf<SearchPage>()
    suspend fun call(request: suspend () -> SearchPage): SearchPage? = try {
        withTimeoutOrNull(options.searchTimeout.coerceIn(3, 30) * 1000L) { request() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    if (options.searchMode == "parallel") coroutineScope {
        val semaphore = Semaphore(options.searchParallel.coerceIn(1, 6))
        requests.map { request -> async { semaphore.withPermit { call(request) } } }.awaitAll().filterNotNull().forEach(batches::add)
    } else for (request in requests) {
        call(request)?.let(batches::add)
        val limit = if (options.searchMode == "hybrid") options.searchEnough else 30
        if (deduplicateVideos(batches.flatMap { it.videos }, active).size >= limit) break
    }
    return batches
}
