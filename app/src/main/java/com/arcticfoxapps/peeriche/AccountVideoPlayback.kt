package com.arcticfoxapps.peeriche

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** File tokens stay in memory, outside Video, saved history, share text and intents. */
internal class AccountVideoGrant(val accountId: String, val video: Video, private val accounts: AccountSessionManager) {
    private val origin = authServerOrigin(accountId.substringBeforeLast('|')).toHttpUrl()
    private val files = (video.files.orEmpty() + video.streamingPlaylists.orEmpty().flatMap { it.files.orEmpty() })
        .mapNotNull { it.fileUrl?.toHttpUrlOrNull()?.encodedPath }.toSet()
    private val playlists = video.streamingPlaylists.orEmpty().mapNotNull { it.playlistUrl?.toHttpUrlOrNull() }
        .filter { it.scheme == origin.scheme && it.host == origin.host && it.port == origin.port &&
            it.encodedPath.startsWith("/static/streaming-playlists/") && video.apiId() in it.pathSegments }
        .map { it.encodedPath.substringBeforeLast('/') + "/" }
    private val lock = Mutex()
    private var token = ""
    private var expires = 0L
    fun active(): Boolean = accounts.state.value.active?.let { it.id == accountId && !it.needsLogin } == true
    fun accepts(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == origin.host && url.port == origin.port &&
            url.username.isEmpty() && url.password.isEmpty() &&
            (url.encodedPath in files || playlists.any { url.encodedPath.startsWith(it) })
    }
    suspend fun resolve(raw: String): String = lock.withLock {
        if (!active()) throw AuthFailure("account_changed")
        if (!accepts(raw)) return@withLock raw
        if (token.isBlank() || expires <= System.currentTimeMillis() + 60_000) {
            val result = accounts.request(accountId, AccountOperation.videoToken(video.apiId())).objectAt("files")
                ?: throw AuthFailure("invalid_response")
            val next = result.stringAt("token")
            val deadline = fileTokenExpiry(result.stringAt("expires"))
            if (next.isBlank() || next.length > 4096 || next.any { it.isISOControl() } || deadline <= System.currentTimeMillis()) throw AuthFailure("invalid_response")
            if (!active()) throw AuthFailure("account_changed")
            token = next; expires = deadline
        }
        raw.toHttpUrl().newBuilder().setQueryParameter("videoFileToken", token)
            .setQueryParameter("reinjectVideoFileToken", "true").build().toString()
    }
    override fun toString() = "AccountVideoGrant(redacted)"
}

internal fun fileTokenExpiry(value: String): Long = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { pattern ->
    val position = java.text.ParsePosition(0)
    java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT).apply { isLenient = false; timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .parse(value, position)?.takeIf { position.index == value.length }?.time
} ?: 0L

internal object AccountVideoPlayback {
    @Volatile var grant: AccountVideoGrant? = null
    fun clear() { grant = null }
}
