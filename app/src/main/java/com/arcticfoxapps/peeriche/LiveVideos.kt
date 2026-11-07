package com.arcticfoxapps.peeriche

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

// Search indexes may retain waiting/ended entries. Check their current origin state.
internal suspend fun resolveLiveVideos(
    candidates: List<Video>, fallback: String, resolve: suspend (Video) -> Video
): List<Video> = coroutineScope {
    val semaphore = Semaphore(6)
    val failedHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    deduplicateVideos(candidates, fallback).map { candidate -> async {
        semaphore.withPermit {
            val host = candidate.sourceServer(fallback)
            if (host in failedHosts) return@withPermit null
            try {
                val details = withTimeoutOrNull(8_000) { resolve(candidate) }
                if (details == null) failedHosts.add(host)
                details
                    ?.takeIf { it.isLive && (it.state == null || it.state.id == 1) && it.playableUrl() != null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is java.io.IOException || (error is retrofit2.HttpException && error.code() in listOf(401, 403, 500, 502, 503, 504))) failedHosts.add(host)
                null
            }
        }
    } }.awaitAll().filterNotNull()
}

internal suspend fun scanLivePages(startPage: Int, server: String, sensitive: Boolean,
    fetch: suspend (Int) -> VideoPage, resolve: suspend (Video) -> Video, maxPages: Int = 4): SearchPage {
    var next = startPage.coerceAtLeast(0)
    var more = false
    repeat(maxPages) {
        val index = next++
        val response = fetch(index)
        more = response.data.isNotEmpty() && index * 30 + response.data.size < response.total
        val active = resolveLiveVideos(response.data.filter { sensitive || !it.nsfw }, server, resolve)
        if (active.isNotEmpty() || !more) return SearchPage(active, more, next)
    }
    return SearchPage(emptyList(), more, next)
}
