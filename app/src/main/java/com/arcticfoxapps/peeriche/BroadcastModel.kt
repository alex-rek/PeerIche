package com.arcticfoxapps.peeriche

import java.net.URI

internal data class BroadcastOptions(val channelId: Long = 0, val portrait: Boolean = true, val resolution: Int = 720, val bitrate: Int = 2_000_000,
    val fps: Int = 30, val front: Boolean = true, val muted: Boolean = false, val cameraOff: Boolean = false) {
    fun checked(): BroadcastOptions {
        require(resolution in listOf(480, 720, 1080) && fps in listOf(24, 30) && bitrate in 500_000..6_000_000 && channelId >= 0)
        return this
    }
}
internal data class BroadcastState(val phase: String = "idle", val accountId: String? = null, val options: BroadcastOptions = BroadcastOptions(),
    val error: String? = null, val watchUrl: String? = null, val uuid: String? = null, val bitrate: Long = 0, val startedAt: Long = 0,
    val title: String = "", val privacy: Int = 3, val actualWidth: Int = 0, val actualHeight: Int = 0, val torch: Boolean = false) {
    val busy: Boolean get() = phase in listOf("creating", "connecting", "live")
}
internal class BroadcastTarget private constructor(val endpoint: String, val watchUrl: String) {
    override fun toString() = "BroadcastTarget(redacted)"
    companion object {
        fun from(credentials: LiveCredentials): BroadcastTarget {
            val uri = runCatching { URI(credentials.url) }.getOrNull() ?: throw AuthFailure("broadcast_endpoint")
            if (uri.scheme !in listOf("rtmp", "rtmps") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.path.orEmpty().trim('/').isBlank() || !credentials.key.matches(Regex("[A-Za-z0-9_-]{8,256}"))) throw AuthFailure("broadcast_endpoint")
            return BroadcastTarget(credentials.url.trimEnd('/') + "/" + credentials.key, credentials.watchUrl)
        }
    }
}
internal fun broadcastMenuDestination(authorized: Boolean) = if (authorized) "Create" else "Subscriptions"
internal fun broadcastRotation(portrait: Boolean) = if (portrait) 90 else 0
internal fun resumableBroadcastUuid(state: BroadcastState, account: String): String? = state.uuid?.takeIf {
    state.accountId == account && state.phase in listOf("creating", "connecting", "live", "error")
}
