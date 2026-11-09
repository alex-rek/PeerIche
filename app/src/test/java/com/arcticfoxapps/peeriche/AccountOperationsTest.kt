package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AccountOperationsTest {
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private fun json(value: String) = Gson().fromJson(value, JsonObject::class.java)
    private class Store(var saved: StoredAccounts) : AccountSessionStore {
        override suspend fun load() = saved
        override suspend fun save(accounts: StoredAccounts) { saved = accounts }
        override suspend fun clear() { saved = StoredAccounts() }
    }
    private class Api : AuthTransport {
        val calls = mutableListOf<Triple<String, String, AccountOperation>>()
        var handler: (AccountOperation) -> JsonObject = { JsonObject() }
        var refreshes = 0
        override suspend fun client(server: String) = AuthClient("id", "secret")
        override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("access", "refresh", 3600)
        override suspend fun refresh(server: String, client: AuthClient, token: String): AuthTokens { refreshes++; return AuthTokens("new-access", "new-refresh", 3600) }
        override suspend fun me(server: String, token: String) = AuthProfile(1, "alice", "Alice")
        override suspend fun revoke(server: String, token: String) { }
        override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
            calls += Triple(server, token, operation); return handler(operation)
        }
    }
    private suspend fun setup(api: Api = Api(), two: Boolean = false): Pair<AccountSessionManager, Api> {
        val one = AuthSession("https://account.example", AuthClient("id", "secret"), "one-access", "one-refresh", Long.MAX_VALUE / 2, AuthProfile(1, "alice", "Alice"))
        val other = one.copy(server = "https://other.example", access = "two-access")
        val manager = AccountSessionManager(Store(StoredAccounts(if (two) listOf(one, other) else listOf(one), one.id)), api)
        manager.initialize(); return manager to api
    }
    private fun route(op: AccountOperation) = op.segments.drop(2).joinToString("/")
    @Test fun queuedSubscriptionSurvivesStaleRefreshUntilServerConfirms() = runBlocking {
        val (manager, api) = setup()
        var confirmed = false
        api.handler = { op -> when {
            route(op) == "users/me" -> json("""{"id":1,"videoChannels":[]}""")
            route(op) == "users/me/subscriptions" && op.method == "GET" ->
                json(if (confirmed) """{"total":1,"data":[{"name":"news","host":"remote.example"}]}""" else """{"total":0,"data":[]}""")
            op.method == "GET" && !confirmed -> throw AuthFailure("account_resource_missing", 404)
            else -> JsonObject()
        } }
        val library = AccountLibrary(manager)
        try {
            val channel = PeerTubeChannel("News", "news", "remote.example")
            library.setSubscribed("https://account.example|1", channel, true)
            library.refresh("https://account.example|1")
            assertEquals("news", library.snapshots.value.values.single().subscriptions.single().name)
            library.setSubscribed("https://account.example|1", channel, true)
            assertEquals(1, api.calls.count { it.third.method == "POST" })
            confirmed = true
            library.refresh("https://account.example|1")
            assertEquals(1, library.snapshots.value.values.single().subscriptions.size)
        } finally { library.close() }
    }
    @Test fun writesAreRejectedAfterAccountSwitchBeforeSending() = runBlocking {
        val (manager, api) = setup(two = true); manager.activate("https://other.example|1")
        try { manager.request("https://account.example|1", AccountOperation.comment(uuid, "hello")); fail() }
        catch (error: AuthFailure) { assertEquals("account_changed", error.reason) }
        assertTrue(api.calls.isEmpty())
    }
    @Test fun authenticatedMutationRefreshesOnlyOnExplicitUnauthorized() = runBlocking {
        val (manager, api) = setup(); var calls = 0
        api.handler = { if (calls++ == 0) throw AuthFailure("invalid_token", 401) else JsonObject() }
        manager.request("https://account.example|1", AccountOperation.rate(uuid, "like"))
        assertEquals(1, api.refreshes); assertEquals(2, api.calls.size)
        assertEquals("new-access", api.calls.last().second)
        assertTrue(api.calls.all { it.first == "https://account.example" })
    }
    @Test fun serverErrorDoesNotAutomaticallyRepeatAComment() = runBlocking {
        val (manager, api) = setup(); api.handler = { throw AuthFailure("server_error", 500) }
        try { manager.request("https://account.example|1", AccountOperation.comment(uuid, "hello")); fail() } catch (_: AuthFailure) { }
        assertEquals(1, api.calls.size); assertEquals(0, api.refreshes)
    }
    @Test fun libraryLoadsOwnChannelsAndAllSubscriptionPages() = runBlocking {
        val (manager, api) = setup()
        api.handler = { op -> when (route(op)) {
            "users/me" -> json("""{"id":1,"videoChannels":[{"name":"mine","displayName":"My channel"}]}""")
            else -> if (op.query["start"] == "0") json("""{"total":3,"data":[{"name":"one","host":"remote.example"},{"name":"two","host":"remote.example"}]}""")
                else json("""{"total":3,"data":[{"name":"three","host":"else.example"}]}""")
        } }
        val library = AccountLibrary(manager)
        try {
            library.refresh("https://account.example|1")
            val snapshot = library.snapshots.value.values.single()
            assertTrue(snapshot.loaded); assertEquals("mine@account.example", snapshot.own.single().subscriptionHandle("https://account.example"))
            assertEquals(3, snapshot.subscriptions.size)
            assertEquals(listOf("0", "2"), api.calls.filter { route(it.third) == "users/me/subscriptions" }.map { it.third.query["start"] })
        } finally { library.close() }
    }
    @Test fun failedRefreshKeepsExistingChannelsAndReportsError() = runBlocking {
        val (manager, api) = setup()
        api.handler = { op -> if (route(op) == "users/me") json("""{"id":1,"videoChannels":[]}""") else json("""{"total":1,"data":[{"name":"news","host":"remote.example"}]}""") }
        val library = AccountLibrary(manager)
        try {
            library.refresh("https://account.example|1")
            api.handler = { throw AuthFailure("server_error", 503) }
            library.refresh("https://account.example|1")
            val snapshot = library.snapshots.value.values.single()
            assertEquals(1, snapshot.subscriptions.size); assertEquals("server_error", snapshot.error); assertFalse(snapshot.busy)
        } finally { library.close() }
    }
    @Test fun subscribeAndUnsubscribeAffectAccountServerOnlyAndAreIdempotent() = runBlocking {
        val (manager, api) = setup(); var follows = false
        api.handler = { op ->
            when (op.method) {
                "GET" -> if (!follows) throw AuthFailure("account_resource_missing", 404)
                "POST" -> { assertEquals("news@remote.example", op.body!!.get("uri").asString); follows = true }
                "DELETE" -> follows = false
            }
            JsonObject()
        }
        val library = AccountLibrary(manager)
        try {
            val channel = PeerTubeChannel("News", "news", "remote.example")
            library.setSubscribed("https://account.example|1", channel, true)
            library.setSubscribed("https://account.example|1", channel, true)
            assertEquals(1, library.snapshots.value.values.single().subscriptions.size)
            library.setSubscribed("https://account.example|1", channel, false)
            assertTrue(library.snapshots.value.values.single().subscriptions.isEmpty())
            assertEquals(1, api.calls.count { it.third.method == "POST" }); assertEquals(1, api.calls.count { it.third.method == "DELETE" })
            assertTrue(api.calls.all { it.first == "https://account.example" })
        } finally { library.close() }
    }
    @Test fun remoteVideoIsResolvedOnAccountServerByStableUuid() = runBlocking {
        val (manager, api) = setup()
        api.handler = { op -> if (route(op).startsWith("videos/")) throw AuthFailure("account_resource_missing", 404)
            else json("""{"data":[{"uuid":"$uuid","name":"Remote","url":"https://remote.example/videos/watch/$uuid"}]}""") }
        val library = AccountLibrary(manager)
        try {
            val video = Video(uuid = uuid, host = "remote.example", url = "https://remote.example/videos/watch/$uuid")
            assertEquals(uuid, library.resolveVideo("https://account.example|1", video, "https://account.example").uuid)
            assertEquals(video.publicWatchUrl("https://account.example"), api.calls.last().third.query["search"])
            assertTrue(api.calls.all { it.first == "https://account.example" })
        } finally { library.close() }
    }
    @Test fun unrelatedRemoteNumericIdNeverTargetsLocalVideoWithSameNumber() = runBlocking {
        val (manager, api) = setup()
        api.handler = { json("""{"data":[{"uuid":"$uuid","name":"Different","url":"https://account.example/videos/watch/$uuid"}]}""") }
        val library = AccountLibrary(manager)
        try {
            try { library.resolveVideo("https://account.example|1", Video(uuid = "12", host = "remote.example"), "https://account.example"); fail() }
            catch (error: AuthFailure) { assertEquals("account_video_unavailable", error.reason) }
            assertEquals("search/videos", route(api.calls.single().third))
        } finally { library.close() }
    }
    @Test fun mismatchedUuidFailsClosed() = runBlocking {
        val (manager, api) = setup(); api.handler = { json("""{"uuid":"different","name":"Wrong"}""") }
        val library = AccountLibrary(manager)
        try {
            try { library.resolveVideo("https://account.example|1", Video(uuid = uuid, host = "remote.example"), "https://account.example"); fail() }
            catch (error: AuthFailure) { assertEquals("account_video_unavailable", error.reason) }
            assertEquals(1, api.calls.size)
        } finally { library.close() }
    }
    @Test fun commentAndReplySendOriginalTextAndReturnModerationState() = runBlocking {
        val (manager, api) = setup(); val original = "Текст без перевода: \"hello\" & <tag>"
        api.handler = { op -> assertEquals(original, op.body!!.get("text").asString); json("""{"comment":{"id":42,"text":"stored","heldForReview":true}}""") }
        val library = AccountLibrary(manager)
        try {
            assertTrue(library.post("https://account.example|1", uuid, original, null).heldForReview)
            library.post("https://account.example|1", uuid, original, 7)
            assertEquals("videos/$uuid/comment-threads", route(api.calls[0].third))
            assertEquals("videos/$uuid/comments/7", route(api.calls[1].third))
        } finally { library.close() }
    }
    @Test fun rejectsInvalidCommentBeforeNetwork() {
        for (value in listOf("   ", "a".repeat(10_001))) try { AccountOperation.comment(uuid, value); fail() }
        catch (error: AuthFailure) { assertEquals("invalid_comment", error.reason) }
    }
    @Test fun feedPreservesRemoteOriginAndResolvesThumbnailAgainstAccountServer() = runBlocking {
        val (manager, api) = setup(); api.handler = { json("""{"total":31,"data":[{"uuid":"$uuid","name":"Video","channel":{"host":"remote.example"},"thumbnailPath":"/thumb.jpg"}]}""") }
        val library = AccountLibrary(manager)
        try {
            val page = library.feed("https://account.example|1", 30, false)
            assertEquals("https://remote.example", page.data.single().sourceServer("https://fallback.example"))
            assertEquals("https://account.example/thumb.jpg", page.data.single().thumbnailUrl)
            assertEquals("30", api.calls.single().third.query["start"])
            assertEquals("false", api.calls.single().third.query["nsfw"])
        } finally { library.close() }
    }
    @Test fun ratingCountsHandleToggleSwitchAndZeroCounts() {
        assertEquals(RatingCounts(4, 2), updatedRatingCounts(5, 2, "like", "none"))
        assertEquals(RatingCounts(6, 1), updatedRatingCounts(5, 2, "dislike", "like"))
        assertEquals(RatingCounts(0, 0), updatedRatingCounts(0, 0, "like", "none"))
    }
    @Test fun channelHandlesCannotInjectPathsQueriesOrCredentials() {
        assertEquals("news@remote.example:8443", validSubscriptionHandle("news@REMOTE.example:8443"))
        for (value in listOf("news@remote.example/path", "news@remote.example?q=x", "news@user:pw@remote.example", "../x@remote.example")) {
            try { validSubscriptionHandle(value); fail() } catch (_: AuthFailure) { }
        }
    }
    @Test fun accountPostRedirectDoesNotForwardTokenOrBody() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(307).message("redirect")
                .header("Location", "https://remote.example/steal").body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { PeerTubeAuthTransport(client).accountRequest("https://account.example", "private-token", AccountOperation.comment(uuid, "private-text")); fail() }
        catch (error: AuthFailure) { assertEquals("redirect", error.reason) }
        assertEquals(1, requests.size); assertEquals("account.example", requests.single().url.host)
        assertEquals("Bearer private-token", requests.single().header("Authorization"))
        assertFalse(requests.single().url.toString().contains("private"))
    }
    @Test fun ratingRemovalUsesPutNoneAndCommentJsonPreservesUnicode() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = PeerTubeAuthTransport(client)
        transport.accountRequest("https://account.example", "token", AccountOperation.rate(uuid, "none"))
        transport.accountRequest("https://account.example", "token", AccountOperation.comment(uuid, "Привет & hello"))
        assertEquals("PUT", requests[0].method)
        val rateBody = Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8()
        assertEquals("none", json(rateBody).get("rating").asString)
        val commentBody = Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8()
        assertEquals("Привет & hello", json(commentBody).get("text").asString)
    }
    @Test fun concurrentSubscriptionChangeIsRecheckedBeforeReportingConflict() = runBlocking {
        val (manager, api) = setup(); var followed = false
        api.handler = { op ->
            when (op.method) {
                "GET" -> if (!followed) throw AuthFailure("account_resource_missing", 404)
                "POST" -> { followed = true; throw AuthFailure("request_failed", 409) }
                "DELETE" -> { followed = false; throw AuthFailure("account_resource_missing", 404) }
            }
            JsonObject()
        }
        val library = AccountLibrary(manager)
        try {
            val channel = PeerTubeChannel("News", "news", "remote.example")
            library.setSubscribed("https://account.example|1", channel, true)
            assertTrue(followed)
            library.setSubscribed("https://account.example|1", channel, false)
            assertFalse(followed)
            assertTrue(library.snapshots.value.values.single().subscriptions.isEmpty())
        } finally { library.close() }
    }
    @Test fun ownChannelsRemainAvailableWhenSubscriptionsEndpointFails() = runBlocking {
        val (manager, api) = setup()
        api.handler = { op -> if (route(op) == "users/me") json("""{"id":1,"videoChannels":[{"name":"mine"}]}""") else throw AuthFailure("server_error", 503) }
        val library = AccountLibrary(manager)
        try {
            library.refresh("https://account.example|1")
            assertEquals("mine", library.snapshots.value.values.single().own.single().name)
            assertEquals("server_error", library.snapshots.value.values.single().error)
        } finally { library.close() }
    }
}
