package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class PeerTubeAuthTest {
    @Test fun canonicalizesSecureOriginsAndPorts() {
        assertEquals("https://video.example", authServerOrigin(" VIDEO.example/ "))
        assertEquals("https://video.example", authServerOrigin("https://video.example:443"))
        assertEquals("https://video.example:8443", authServerOrigin("https://video.example:8443/"))
    }
    @Test fun rejectsNonHttpsCredentialsPathsAndQueries() {
        for (value in listOf("http://video.example", "https://alice:pw@video.example", "https://video.example/login",
            "https://video.example?token=secret", "https://video.example/#fragment", "https://video.example:0", "javascript:alert(1)")) {
            try { authServerOrigin(value); fail("Accepted unsafe origin") } catch (error: AuthFailure) { assertEquals("invalid_server", error.reason) }
        }
    }
    @Test fun understandsModernAndLegacyTwoFactorErrors() {
        fun json(value: String) = Gson().fromJson(value, JsonObject::class.java)
        assertEquals("missing_two_factor", authErrorReason(401, json("""{"code":"missing_two_factor"}""")))
        assertEquals("invalid_two_factor", authErrorReason(401, json("""{"type":"https://docs.example/#invalid_two_factor"}""")))
        assertEquals("missing_two_factor", authErrorReason(401, json("""{"error":"Missing two factor header"}""")))
        assertEquals("rate_limited", authErrorReason(429, null))
        assertEquals("redirect", authErrorReason(302, null))
    }
    private fun transport(requests: MutableList<Request>, code: Int = 200, json: (Request) -> String): PeerTubeAuthTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); synchronized(requests) { requests += request }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(json(request).toResponseBody("application/json".toMediaType()))
                .apply { if (code == 302) header("Location", "https://other.example/steal") }.build()
        }.build()
        return PeerTubeAuthTransport(client)
    }
    @Test fun postsEncodedPasswordAndOtpOnlyToChosenServer() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = transport(requests) { """{"token_type":"Bearer","access_token":"access","refresh_token":"refresh","expires_in":3600}""" }
        api.login("https://one.example", AuthClient("id", "secret"), "alice", "p+&= pass", "123456")
        val request = requests.single(); val form = request.body as FormBody
        assertEquals("https://one.example/api/v1/users/token", request.url.toString()); assertNull(request.url.query)
        assertEquals("123456", request.header("x-peertube-otp")); assertNull(request.header("Authorization"))
        assertEquals("p+&= pass", (0 until form.size).associate { form.name(it) to form.value(it) }["password"])
    }
    @Test fun authenticatedProfileRequestHasNoTokenInUrl() = runBlocking {
        val requests = mutableListOf<Request>(); val api = transport(requests) { """{"id":42,"username":"alice","account":{"displayName":"Alice","avatars":[{"path":"/avatar.png"}]}}""" }
        val profile = api.me("https://one.example", "private-access")
        assertEquals("Bearer private-access", requests.single().header("Authorization"))
        assertEquals("https://one.example/api/v1/users/me", requests.single().url.toString())
        assertEquals("https://one.example/avatar.png", profile.avatar); assertEquals(42L, profile.userId)
    }
    @Test fun redirectsNeverForwardLoginOrToken() = runBlocking {
        val requests = mutableListOf<Request>(); val api = transport(requests, 302) { "{}" }
        try { api.me("https://one.example", "private-access"); fail() } catch (error: AuthFailure) { assertEquals("redirect", error.reason) }
        assertEquals(1, requests.size); assertEquals("one.example", requests.single().url.host)
    }
    @Test fun refreshUsesRefreshGrantWithoutPasswordOrOtp() = runBlocking {
        val requests = mutableListOf<Request>(); val api = transport(requests) { """{"token_type":"Bearer","access_token":"next","refresh_token":"rotated","expires_in":3600}""" }
        api.refresh("https://one.example", AuthClient("id", "secret"), "refresh-secret")
        val request = requests.single(); val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("refresh_token", fields["grant_type"]); assertEquals("refresh-secret", fields["refresh_token"])
        assertFalse(fields.containsKey("password")); assertNull(request.header("x-peertube-otp"))
    }
    @Test fun rejectsIncompleteTokenAndProfileResponses() = runBlocking {
        val api = transport(mutableListOf()) { "{}" }
        try { api.login("one.example", AuthClient("id", "secret"), "alice", "pw", ""); fail() } catch (error: AuthFailure) { assertEquals("invalid_response", error.reason) }
        try { api.me("one.example", "access"); fail() } catch (error: AuthFailure) { assertEquals("invalid_response", error.reason) }
    }
    @Test fun secretsAreRedactedFromDebugRepresentations() {
        assertFalse(AuthClient("id", "secret-password").toString().contains("secret-password"))
        assertFalse(AuthTokens("access-private", "refresh-private", 30).toString().contains("private"))
    }
    @Test fun encryptionUsesFreshNonceAndAuthenticatesPayload() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plain = "unique-private-access-and-refresh".toByteArray()
        val one = AccountCipher.encrypt(plain, key); val two = AccountCipher.encrypt(plain, key)
        assertFalse(one.contentEquals(two)); assertArrayEquals(plain, AccountCipher.decrypt(one, key))
        assertFalse(one.toString(Charsets.UTF_8).contains("unique-private-access"))
        one[one.lastIndex] = (one.last().toInt() xor 1).toByte()
        try { AccountCipher.decrypt(one, key); fail() } catch (_: java.security.GeneralSecurityException) { }
    }
    @Test fun wrongKeyAndUnsupportedStorageFormatAreRejected() {
        val generator = KeyGenerator.getInstance("AES").apply { init(256) }
        val encrypted = AccountCipher.encrypt("secret".toByteArray(), generator.generateKey())
        try { AccountCipher.decrypt(encrypted, generator.generateKey()); fail() } catch (_: java.security.GeneralSecurityException) { }
        try { AccountCipher.decrypt(byteArrayOf(9), generator.generateKey()); fail() } catch (_: IllegalArgumentException) { }
    }
}
