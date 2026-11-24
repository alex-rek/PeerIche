package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedVideosTest {
    private val server = "https://tube.example"
    private fun video(id: String, live: Boolean = false, host: String = "tube.example") = Video(uuid = id, name = id, host = host, isLive = live)
    private fun merge(sources: RelatedSources, fallback: List<Video> = emptyList(), excluded: Set<String> = emptySet(),
        watched: Set<String> = emptySet(), limit: Int = 20) = mergeRelatedVideos(video("current"), server, sources, fallback,
        excluded = { it.uuid in excluded }, watched = { it.uuid in watched }, limit = limit).map { it.uuid }

    @Test fun channelLeadsThenTagsCategoryRestOfChannelAndFallback() {
        val channel = (1..8).map { video("c$it") }
        val result = merge(RelatedSources(channel, listOf(video("t1")), listOf(video("k1"))), listOf(video("f1")))
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6", "t1", "k1", "c7", "c8", "f1"), result)
    }

    @Test fun dropsStreamsCurrentDuplicatesAndExcluded() {
        val sources = RelatedSources(listOf(video("current"), video("a"), video("live", live = true)), listOf(video("a"), video("hidden")))
        assertEquals(listOf("a"), merge(sources, excluded = setOf("hidden")))
    }

    @Test fun sameIdOnAnotherServerIsNotTheCurrentVideo() {
        assertEquals(listOf("current"), merge(RelatedSources(tags = listOf(video("current", host = "other.example")))))
    }

    @Test fun watchedVideosMoveToTheEndBeforeTheLimit() {
        val sources = RelatedSources(channel = listOf(video("seen"), video("a"), video("b")))
        assertEquals(listOf("a", "b", "seen"), merge(sources, watched = setOf("seen")))
        assertEquals(listOf("a", "b"), merge(sources, watched = setOf("seen"), limit = 2))
    }

    @Test fun withoutRelatedResultsFallbackIsUsed() {
        assertEquals(listOf("f1", "f2"), merge(RelatedSources(), listOf(video("f1"), video("f2"), video("live", live = true))))
    }
}
