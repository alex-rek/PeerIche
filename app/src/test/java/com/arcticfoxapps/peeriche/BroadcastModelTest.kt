package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class BroadcastModelTest {
    private fun credentials(url: String = "rtmps://stream.example:1936/live", key: String = "fixture-stream-key") = LiveCredentials(url, key, "https://account.example/w/fixture")
    @Test fun bottomMenuUsesSubscriptionsWithoutActiveAuthorization() {
        assertEquals("Subscriptions", broadcastMenuDestination(false)); assertEquals("Create", broadcastMenuDestination(true))
    }
    @Test fun rtmpsEndpointPreservesTransportAndAppendsOpaqueKey() {
        val target = BroadcastTarget.from(credentials())
        assertEquals("rtmps://stream.example:1936/live/fixture-stream-key", target.endpoint)
        assertFalse(target.toString().contains("fixture-stream-key"))
    }
    @Test fun plainRtmpIsSupportedWhenServerDoesNotProvideTls() {
        assertEquals("rtmp://stream.example/live/fixture-stream-key", BroadcastTarget.from(credentials("rtmp://stream.example/live/")).endpoint)
    }
    @Test fun endpointRejectsEmbeddedCredentialsQueriesFragmentsAndOtherProtocols() {
        for (url in listOf("https://stream.example/live", "rtmp://user:password@stream.example/live", "rtmps://stream.example/live?key=other", "rtmp://stream.example/live#fragment", "rtmp://stream.example/")) {
            try { BroadcastTarget.from(credentials(url)); fail(url) } catch (error: AuthFailure) { assertEquals("broadcast_endpoint", error.reason) }
        }
    }
    @Test fun keyCannotInjectAnotherPathOrProtocolParameter() {
        for (key in listOf("", "short", "test/key-value", "test-key?token=secret", "test-key\r\nvalue", "x".repeat(257))) {
            try { BroadcastTarget.from(credentials(key = key)); fail() } catch (error: AuthFailure) { assertEquals("broadcast_endpoint", error.reason) }
        }
    }
    @Test fun qualityBoundsPreventOversizedOrUnsupportedEncoderParameters() {
        BroadcastOptions().checked()
        for (option in listOf(BroadcastOptions(resolution = 2160), BroadcastOptions(fps = 60), BroadcastOptions(bitrate = 0), BroadcastOptions(channelId = -1))) {
            try { option.checked(); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun captureRotationUsesFixedDimensionsForPortraitAndLandscape() { assertEquals(90, broadcastRotation(true)); assertEquals(0, broadcastRotation(false)) }
    @Test fun onlyConnectingCreatingAndLiveStatesHoldTheForegroundSession() {
        for (phase in listOf("creating", "connecting", "live")) assertTrue(BroadcastState(phase = phase).busy)
        for (phase in listOf("idle", "preview", "error", "stopped", "preparing")) assertFalse(BroadcastState(phase = phase).busy)
    }
    @Test fun channelUpdateIsFixedRouteAndValidatesTextBeforeRequest() {
        val request = AccountOperation.updateCreatorChannel("my_channel", "  My channel  ", "Description")
        assertEquals(listOf("api", "v1", "video-channels", "my_channel"), request.segments)
        assertTrue(request.writes); assertEquals("My channel", request.body!!.get("displayName").asString)
        for (name in listOf("a", "x".repeat(121))) { try { AccountOperation.updateCreatorChannel("my_channel", name, ""); fail() } catch (_: AuthFailure) { } }
        try { AccountOperation.updateCreatorChannel("my_channel", "Valid", "x".repeat(1001)); fail() } catch (_: AuthFailure) { }
        try { AccountOperation.updateCreatorChannel("other@remote.example", "Valid", ""); fail() } catch (_: AuthFailure) { }
        try { AccountOperation.updateCreatorChannel("..", "Valid", ""); fail() } catch (_: AuthFailure) { }
    }
    @Test fun tlsDnsIdentityRejectsDifferentHostsAndBroadWildcards() {
        assertTrue(broadcastDnsMatches("STREAM.example.org", "stream.example.org"))
        assertTrue(broadcastDnsMatches("stream.example.org", "*.example.org"))
        assertFalse(broadcastDnsMatches("example.org", "*.example.org"))
        assertFalse(broadcastDnsMatches("a.b.example.org", "*.example.org"))
        assertFalse(broadcastDnsMatches("stream.evil.org", "stream.example.org"))
        assertFalse(broadcastDnsMatches("stream.org", "*.org"))
        assertFalse(broadcastDnsMatches("stream.example.org", "str*.example.org"))
    }
    @Test fun finishedOrForeignBroadcastIsNotReusedAsTheNextStream() {
        val state = BroadcastState(phase = "live", accountId = "one", uuid = "fixture")
        assertEquals("fixture", resumableBroadcastUuid(state, "one"))
        assertEquals("fixture", resumableBroadcastUuid(state.copy(phase = "error"), "one"))
        assertNull(resumableBroadcastUuid(state.copy(phase = "stopped"), "one"))
        assertNull(resumableBroadcastUuid(state.copy(phase = "preview"), "one"))
        assertNull(resumableBroadcastUuid(state, "other"))
    }
}
