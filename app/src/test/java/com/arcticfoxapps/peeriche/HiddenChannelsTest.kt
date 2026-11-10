package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class HiddenChannelsTest {
    private val video = Video(uuid = "one", name = "Video", host = "replica.example", channel = Channel("Channel", "music", "origin.example"))
    @Test fun identityUsesOwningChannelServerAcrossFederatedCopies() {
        assertEquals("https://origin.example|music", video.hiddenChannelIdentity("https://browsing.example"))
        assertEquals(video.hiddenChannelIdentity("https://browsing.example"), video.copy(host = "other-replica.example").hiddenChannelIdentity("https://another.example"))
        assertEquals(video.hiddenChannelIdentity("https://browsing.example"), video.copy(channel = Channel("Renamed", "music@origin.example")).hiddenChannelIdentity("https://another.example"))
    }
    @Test fun filteringDoesNotHideNamesakesOtherChannelsOrUnidentifiedRows() {
        val namesake = video.copy(uuid = "two", channel = Channel("Channel", "music", "another.example"))
        val other = video.copy(uuid = "three", channel = Channel("Channel", "news", "origin.example"))
        val unknown = video.copy(uuid = "four", channel = Channel(displayName = "Channel"))
        val list = listOf(video, namesake, other, unknown)
        assertEquals(listOf(namesake, other, unknown), list.withoutHiddenChannels(setOf(video.hiddenChannelIdentity("https://browse.example")!!), "https://browse.example"))
        assertEquals(list, list.withoutHiddenChannels(emptySet(), "https://browse.example"))
        assertNull(unknown.hiddenChannelIdentity("https://browse.example"))
    }
    @Test fun hiddenRecordIsSeparateFromSubscriptionAndPreservesDisplayName() {
        val row = video.hiddenChannelRow("https://browse.example", "My music")!!
        assertEquals(HIDDEN_CHANNEL, row.kind)
        assertEquals("My music", row.title)
        assertEquals("https://origin.example", row.server)
        assertEquals("music", row.channelName)
        assertEquals(video.subscriptionIdentity("https://browse.example"), row.identity)
        assertNull(video.copy(channel = Channel(name = "../bad")).hiddenChannelRow("https://browse.example", "Invalid"))
    }
}
