package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AccountVideoPlaybackTest {
    private val server = "https://owner.example"
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val playlist = "$server/static/streaming-playlists/hls/private/$uuid/master.m3u8"
    private val file = "$server/static/web-videos/private/$uuid.mp4"
    private fun json(value: String) = Gson().fromJson(value, JsonObject::class.java)
    private class Store(var data: StoredAccounts) : AccountSessionStore {
        override suspend fun load() = data
        override suspend fun save(accounts: StoredAccounts) { data = accounts }
        override suspend fun clear() { data = StoredAccounts() }
    }
    private class Api : AuthTransport {
        val calls = mutableListOf<AccountOperation>()
        var handler: (AccountOperation) -> JsonObject = { JsonObject() }
        override suspend fun client(server: String) = AuthClient("client", "secret")
        override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("access", "refresh", 3600)
        override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("access", "refresh", 3600)
        override suspend fun me(server: String, token: String) = AuthProfile(1, "owner", "Owner")
        override suspend fun revoke(server: String, token: String) { }
        override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
            calls += operation; return handler(operation)
        }
    }
    private suspend fun setup(): Pair<AccountSessionManager, Api> {
        val session = AuthSession(server, AuthClient("client", "secret"), "access", "refresh", Long.MAX_VALUE / 2, AuthProfile(1, "owner", "Owner"))
        val other = session.copy(server = "https://other.example")
        val api = Api()
        return AccountSessionManager(Store(StoredAccounts(listOf(session, other), session.id)), api).also { it.initialize() } to api
    }
    @Test fun personalDetailsUseAuthenticatedApiAndKeepOnlyAccountIdentity() = runBlocking {
        val (manager, api) = setup()
        api.handler = { json("""{"uuid":"$uuid","name":"Private","privacy":{"id":3},"files":[{"fileUrl":"$file"}]}""") }
        val result = CreatorRepository(manager).playbackVideo("$server|1", uuid)
        assertEquals("$server|1", result.playbackAccountId)
        assertEquals(file, result.playableUrl())
        assertEquals(listOf("api", "v1", "videos", uuid), api.calls.single().segments)
        assertEquals("$server/videos/watch/$uuid", result.publicWatchUrl(server))
        assertFalse(result.shareText(server).contains("access"))
        api.handler = { json("""{"uuid":"$uuid","privacy":{"id":1}}""") }
        assertNull(CreatorRepository(manager).playbackVideo("$server|1", uuid).playbackAccountId)
    }
    @Test fun privateHlsAndMp4UseOneCachedFileTokenNeverOAuthOrForeignOrigin() = runBlocking {
        val (manager, api) = setup()
        api.handler = { json("""{"files":{"token":"FILE-ONLY","expires":"2099-01-01T00:00:00.000Z"}}""") }
        val video = Video(uuid = uuid, name = "Private", files = listOf(VideoFile(file)), streamingPlaylists = listOf(StreamingPlaylist(playlist)), playbackAccountId = "$server|1")
        val grant = AccountVideoGrant("$server|1", video, manager)
        for (url in listOf(playlist, playlist.substringBeforeLast('/') + "/0.m3u8", file)) {
            val resolved = grant.resolve(url).toHttpUrl()
            assertEquals("FILE-ONLY", resolved.queryParameter("videoFileToken"))
            assertFalse(resolved.toString().contains("access"))
        }
        assertEquals(1, api.calls.size)
        assertEquals(listOf("api", "v1", "videos", uuid, "token"), api.calls.single().segments)
        for (url in listOf(playlist.replace("owner.example", "evil.example"), playlist.replace("https:", "http:"), "$server/api/v1/users/me", "$server/static/streaming-playlists/hls/another/master.m3u8")) {
            assertFalse(grant.accepts(url)); assertEquals(url, grant.resolve(url))
        }
        assertFalse(grant.toString().contains("FILE-ONLY"))
        assertFalse(Gson().toJson(video).contains("FILE-ONLY"))
        assertEquals(playlist, video.toSavedVideo(server, "history").videoUrl)
        manager.activate("https://other.example|1")
        try { grant.resolve(file); fail("Account switch must stop private access") }
        catch (failure: AuthFailure) { assertEquals("account_changed", failure.reason) }
        assertEquals(1, api.calls.size)
    }
    @Test fun invalidFileTokenExpiryIsRejectedInsteadOfPersisted() = runBlocking {
        val (manager, api) = setup()
        api.handler = { json("""{"files":{"token":"SECRET","expires":"bad-date"}}""") }
        val grant = AccountVideoGrant("$server|1", Video(uuid = uuid, files = listOf(VideoFile(file))), manager)
        try { grant.resolve(file); fail("Invalid expiry") }
        catch (failure: AuthFailure) { assertEquals("invalid_response", failure.reason) }
        assertEquals(0L, fileTokenExpiry("not-a-date"))
        assertTrue(fileTokenExpiry("2099-01-01T00:00:00Z") > System.currentTimeMillis())
    }
}
