package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class CreatorProtocolTest {
    private val server = "https://creator.example"
    private val location = "$server$UPLOAD_PATH?upload_id=fixture"
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val metadata = CreatorMetadata("Проверка", "Описание", 7, 3)
    private fun json(raw: String) = Gson().fromJson(raw, JsonObject::class.java)
    private fun caps(quota: Long = -1, daily: Long = -1, used: Long = 0, today: Long = 0) = CreatorCapabilities(
        listOf(CreatorChannel(7, "Mine")), quota, daily, used, today, listOf(".mp4"), 0, 0, true, true, 1000, listOf(".png"))
    private fun client(handler: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
    private fun response(request: Request, code: Int, body: String = "", headers: Map<String, String> = emptyMap()): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    private fun failure(reason: String, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: AuthFailure) { assertEquals(reason, error.reason) }
    }
    @Test fun continuationCannotLeakCredentialsToAnotherOriginOrRoute() {
        assertEquals(location, safeUploadUrl(server, "$UPLOAD_PATH?upload_id=fixture"))
        for (bad in listOf("https://evil.example$UPLOAD_PATH?upload_id=x", "http://creator.example$UPLOAD_PATH?upload_id=x", "//$server/x",
            "https://user@creator.example$UPLOAD_PATH?upload_id=x", "$UPLOAD_PATH?upload_id=a&upload_id=b", "$UPLOAD_PATH?token=x&upload_id=x",
            "$UPLOAD_PATH?upload_id=x#secret", "/api/v1/videos?upload_id=x", "$UPLOAD_PATH?upload_id=", "https://creator.example:444$UPLOAD_PATH?upload_id=x")) {
            failure("invalid_upload_location") { safeUploadUrl(server, bad) }
        }
    }
    @Test fun metadataRejectsMissingChannelBadPrivacyAndOversizedText() {
        metadata.validate(); assertEquals("Проверка", metadata.json().get("name").asString)
        for (bad in listOf(metadata.copy(name = "ab"), metadata.copy(name = "a".repeat(121)), metadata.copy(channelId = 0), metadata.copy(privacy = 5), metadata.copy(description = "x".repeat(10001)))) {
            failure("invalid_publication") { bad.validate() }
        }
    }
    @Test fun fileNameCannotInjectHeadersOrPathAndContentTypeIsValidated() {
        for (name in listOf("../video.mp4", "bad\r\nname.mp4", "folder\\video.mp4", "")) failure("invalid_file") { UploadOperation.init(1, "video/mp4", name, metadata) }
        failure("invalid_file") { UploadOperation.init(1, "video/mp4\r\nX: bad", "file.mp4", metadata) }
        failure("invalid_file") { UploadOperation.init(0, "video/mp4", "file.mp4", metadata) }
    }
    @Test fun quotasAreCheckedIndependentlyAndUnlimitedIsSupported() {
        caps().validateFile("VIDEO.MP4", Long.MAX_VALUE)
        caps(100, 100, 40, 40).validateFile("v.mp4", 60)
        failure("upload_quota") { caps(100, -1, 90).validateFile("v.mp4", 11) }
        failure("upload_quota") { caps(-1, 100, 0, 90).validateFile("v.mp4", 11) }
        failure("upload_format") { caps().validateFile("v.exe", 1) }
        failure("upload_quota") { caps(0).validateFile("v.mp4", 1) }
    }
    @Test fun chunkSizesRespectBothServerLimitsAndLargeFiles() {
        assertEquals(1_048_576, caps().chunkSize(10_000_000))
        assertEquals(524288, caps().copy(maxChunk = 524288).chunkSize(10_000_000))
        assertTrue(caps().chunkSize(100_000_000_000) >= (100_000_000_000L - 1) / 9999 + 1)
        failure("upload_chunk_limit") { caps().chunkSize(Long.MAX_VALUE) }
        failure("upload_chunk_limit") { caps().copy(minChunk = 1024, maxChunk = 512).chunkSize(1000) }
    }
    @Test fun capabilitiesUseAccountQuotaAndServerFlags() {
        val parsed = creatorCapabilities(json("""{"videoQuota":100,"videoQuotaDaily":20,"videoChannels":[{"id":7,"displayName":"Mine"},{"id":0}]}"""),
            json("""{"video":{"file":{"extensions":[".MP4"]},"image":{"size":{"max":123}}},"live":{"enabled":true,"allowReplay":false},"client":{"videos":{"resumableUpload":{"minChunkSize":1024,"maxChunkSize":2048}}}}"""), json("""{"videoQuotaUsed":10,"videoQuotaUsedDaily":2}"""))
        assertEquals(7L, parsed.channels.single().id); assertEquals(10L, parsed.used); assertEquals(123L, parsed.coverMax)
        assertTrue(parsed.live); assertFalse(parsed.replay); assertEquals(listOf(".mp4"), parsed.extensions)
        assertEquals(2048, parsed.chunkSize(10000))
    }
    @Test fun rangeValidationRejectsOverflowAndPartialPrefix() {
        assertEquals(0L, uploadOffset(null, 100)); assertEquals(10L, uploadOffset("bytes=0-9", 100))
        for (range in listOf("bytes=1-9", "bytes=0-100", "bytes=0-999999999999999999999", "bytes=0--1", "0-9")) failure("invalid_response") { uploadOffset(range, 100) }
    }
    @Test fun initUsesJsonAndOnlyAccountOriginWithValidatedLocation() = runBlocking {
        val http = client { req ->
            assertEquals("creator.example", req.url.host); assertEquals("Bearer fixture-token", req.header("Authorization"))
            assertEquals("123", req.header("X-Upload-Content-Length")); assertEquals("video/mp4", req.header("X-Upload-Content-Type"))
            val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8(); assertEquals("video.mp4", json(body).get("filename").asString)
            response(req, 201, headers = mapOf("Location" to "$UPLOAD_PATH?upload_id=fixture"))
        }
        val reply = executeUpload(http, server, "fixture-token", UploadOperation.init(123, "video/mp4", "video.mp4", metadata))
        assertEquals(location, reply.location)
    }
    @Test fun foreignLocationFromInitIsRejectedBeforeFurtherRequest() = runBlocking {
        val http = client { response(it, 201, headers = mapOf("Location" to "https://evil.example$UPLOAD_PATH?upload_id=x")) }
        failure("invalid_upload_location") { runBlocking { executeUpload(http, server, "token", UploadOperation.init(10, "video/mp4", "x.mp4", metadata)) } }
    }
    @Test fun probeAndChunkUseDistinctContentRangesAnd308IsNotRedirect() = runBlocking {
        var calls = 0
        val http = client { req ->
            assertEquals("PUT", req.method); assertEquals("fixture", req.url.queryParameter("upload_id"))
            if (calls++ == 0) { assertEquals("bytes */10", req.header("Content-Range")); assertEquals(0L, req.body!!.contentLength()) }
            else { assertEquals("bytes 4-6/10", req.header("Content-Range")); assertArrayEquals(byteArrayOf(4, 5, 6), Buffer().also { req.body!!.writeTo(it) }.readByteArray()) }
            response(req, 308, headers = mapOf("Range" to if (calls == 1) "bytes=0-3" else "bytes=0-6"))
        }
        assertEquals(4L, executeUpload(http, server, "t", UploadOperation.probe(location, 10)).offset)
        assertEquals(7L, executeUpload(http, server, "t", UploadOperation.chunk(location, 10, 4, byteArrayOf(4, 5, 6))).offset)
    }
    @Test fun actualRedirectIsNeverFollowedForAnUpload() = runBlocking {
        var calls = 0
        val http = client { calls++; response(it, 308, headers = mapOf("Location" to "https://evil.example/")) }
        failure("redirect") { runBlocking { executeUpload(http, server, "secret", UploadOperation.probe(location, 10)) } }
        assertEquals(1, calls)
    }
    @Test fun lastChunkRequiresValidVideoIdentityAndCancelUsesDelete() = runBlocking {
        val http = client { req ->
            if (req.method == "DELETE") { assertEquals("0", req.header("Content-Length")); response(req, 204) }
            else response(req, 200, """{"video":{"uuid":"$uuid"}}""")
        }
        assertEquals(uuid, executeUpload(http, server, "t", UploadOperation.chunk(location, 1, 0, byteArrayOf(1))).videoUuid)
        assertEquals(204, executeUpload(http, server, "t", UploadOperation.cancel(location)).code)
        failure("invalid_response") { uploadedUuid(json("""{"video":{"uuid":"------------------------------------"}}""")) }
    }
    @Test fun coverIsMultipartOnFixedVideoEndpoint() = runBlocking {
        val http = client { req ->
            assertEquals("PUT", req.method); assertEquals("/api/v1/videos/$uuid", req.url.encodedPath)
            assertTrue(req.body!!.contentType().toString().startsWith("multipart/form-data"))
            assertTrue(Buffer().also { req.body!!.writeTo(it) }.readUtf8().contains("name=\"thumbnailfile\""))
            response(req, 204)
        }
        executeUpload(http, server, "t", UploadOperation.cover(uuid, byteArrayOf(1, 2), "image/png"))
        Unit
    }
    @Test fun networkFailureDoesNotAutomaticallyReplayUploadInit() = runBlocking {
        var calls = 0
        val http = client { calls++; throw IOException("fixture disconnect") }
        try { executeUpload(http, server, "t", UploadOperation.init(10, "video/mp4", "x.mp4", metadata)); fail() } catch (_: IOException) { }
        assertEquals(1, calls)
    }
    @Test fun skipSupportsStreamsThatCannotSeek() = runBlocking {
        val input = object : ByteArrayInputStream(byteArrayOf(0, 1, 2, 3, 4)) { override fun skip(n: Long): Long = 0 }
        skipUploadBytes(input, 3); assertEquals(3, input.read())
        failure("file_changed") { runBlocking { skipUploadBytes(ByteArrayInputStream(byteArrayOf(1)), 2) } }
    }
    @Test fun liveCredentialsDoNotExposeSecretsThroughLogging() {
        val credentials = LiveCredentials("rtmps://fixture/live", "PRIVATE-KEY", "https://fixture/w/id")
        assertFalse(credentials.toString().contains("PRIVATE-KEY"))
    }
    @Test fun accountUploadRefreshesOnceAndRejectsWritesAfterAccountSwitch() = runBlocking {
        val authorizations = mutableListOf<String?>()
        var refreshes = 0
        val http = client { request ->
            if (request.url.encodedPath == "/api/v1/users/token") {
                refreshes++
                response(request, 200, """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer","expires_in":3600}""")
            } else {
                authorizations += request.header("Authorization")
                if (request.header("Authorization") == "Bearer old-access") response(request, 401, """{"error":"invalid_token"}""")
                else response(request, 201, headers = mapOf("Location" to "$UPLOAD_PATH?upload_id=fixture"))
            }
        }
        val session = AuthSession(server, AuthClient("client", "secret"), "old-access", "old-refresh", Long.MAX_VALUE / 2, AuthProfile(1, "author", "Author"))
        val other = session.copy(server = "https://other.example")
        val store = object : AccountSessionStore {
            var saved = StoredAccounts(listOf(session, other), session.id)
            override suspend fun load() = saved
            override suspend fun save(accounts: StoredAccounts) { saved = accounts }
            override suspend fun clear() { saved = StoredAccounts() }
        }
        val manager = AccountSessionManager(store, PeerTubeAuthTransport(http))
        manager.initialize()
        assertEquals(location, manager.upload(session.id, UploadOperation.init(10, "video/mp4", "v.mp4", metadata)).location)
        assertEquals(listOf("Bearer old-access", "Bearer new-access"), authorizations)
        assertEquals(1, refreshes); assertEquals("new-refresh", store.saved.sessions.first().refresh)
        manager.activate(other.id)
        failure("account_changed") { runBlocking { manager.upload(session.id, UploadOperation.cancel(location)) } }
        assertEquals(2, authorizations.size)
    }
}
