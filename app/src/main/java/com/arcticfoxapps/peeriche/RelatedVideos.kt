package com.arcticfoxapps.peeriche

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Videos fetched for the "Up next" list, grouped by how they relate to the open video. */
internal data class RelatedSources(val channel: List<Video> = emptyList(), val tags: List<Video> = emptyList(),
    val category: List<Video> = emptyList())

private const val CHANNEL_FIRST = 6

/** Queries the server that published [video]; any failed query just contributes nothing. */
internal suspend fun loadRelatedSources(video: Video, fallbackServer: String, includeSensitive: Boolean): RelatedSources = coroutineScope {
    val origin = video.sourceServer(fallbackServer)
    val api = PeerTubeClients.server(origin)
    val nsfw = sensitiveContentFilter(includeSensitive)
    val tags = video.tags.orEmpty().map(String::trim).filter(String::isNotBlank).distinct().take(5)
    val category = video.category?.id?.takeIf { it > 0 }
    suspend fun attempt(block: suspend () -> List<Video>): List<Video> =
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    val channel = async { attempt { api.channelVideos(video.accountChannelHandle(origin), count = 15, sort = "-publishedAt", isLive = false, nsfw = nsfw).data } }
    val byTags = async { attempt { if (tags.isEmpty()) emptyList() else api.relatedVideos(20, "-views", nsfw, tags = tags).data } }
    val byCategory = async { attempt { if (category == null) emptyList() else api.relatedVideos(20, "-publishedAt", nsfw, categories = listOf(category)).data } }
    RelatedSources(channel.await(), byTags.await(), byCategory.await())
}

/**
 * Builds "Up next": a few newest videos of the same channel, then videos sharing tags, then the same
 * category, then the rest of the channel and finally [fallback]. Streams, the open video and [excluded]
 * ones are dropped; already watched videos go to the end instead of disappearing.
 */
internal fun mergeRelatedVideos(current: Video, server: String, sources: RelatedSources, fallback: List<Video>,
    excluded: (Video) -> Boolean, watched: (Video) -> Boolean, limit: Int = 20): List<Video> {
    val currentKey = current.sourceServer(server) + "|" + current.apiId()
    val candidates = sources.channel.take(CHANNEL_FIRST) + sources.tags + sources.category + sources.channel.drop(CHANNEL_FIRST) + fallback
    val usable = deduplicateVideos(candidates, server).filterNot { it.isLive || it.sourceServer(server) + "|" + it.apiId() == currentKey || excluded(it) }
    val (fresh, seen) = usable.partition { !watched(it) }
    return (fresh + seen).take(limit)
}
