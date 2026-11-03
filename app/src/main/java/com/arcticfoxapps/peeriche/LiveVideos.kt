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
    deduplicateVideos(candidates, fallback).map { candidate -> async {
        semaphore.withPermit {
            try {
                withTimeoutOrNull(8_000) { resolve(candidate) }
                    ?.takeIf { it.isLive && (it.state == null || it.state.id == 1) && it.playableUrl() != null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    } }.awaitAll().filterNotNull()
}
