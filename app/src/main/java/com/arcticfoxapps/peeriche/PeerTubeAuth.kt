package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AuthFailure(val reason: String, val status: Int = 0) : IOException(reason)

internal fun authServerOrigin(input: String): String {
    val raw = input.trim().let { if (it.contains("://")) it else "https://$it" }
    val uri = runCatching { URI(raw) }.getOrNull() ?: throw AuthFailure("invalid_server")
    if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.path.orEmpty() !in listOf("", "/") ||
        uri.port !in -1..65535 || uri.port == 0) throw AuthFailure("invalid_server")
    return "https://${uri.host.lowercase(Locale.ROOT)}${if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""}"
}

internal data class AuthClient(val id: String, val secret: String) {
    override fun toString() = "AuthClient(redacted)"
}
internal data class AuthTokens(val access: String, val refresh: String, val expiresIn: Long) {
    override fun toString() = "AuthTokens(redacted)"
}
internal data class AuthProfile(val userId: Long, val username: String, val displayName: String, val avatar: String? = null)
internal data class AuthSession(val server: String, val client: AuthClient, val access: String, val refresh: String,
    val expiresAt: Long, val profile: AuthProfile, val needsLogin: Boolean = false) {
    val id: String get() = "$server|${profile.userId}"
    override fun toString() = "AuthSession(server=$server, credentials=redacted)"
}
internal data class StoredAccounts(val sessions: List<AuthSession> = emptyList(), val activeId: String? = null)
internal data class AccountSummary(val id: String, val server: String, val username: String, val displayName: String,
    val avatar: String?, val needsLogin: Boolean)
internal data class AccountsState(val ready: Boolean = false, val accounts: List<AccountSummary> = emptyList(),
    val activeId: String? = null, val storageError: Boolean = false) {
    val active: AccountSummary? get() = accounts.firstOrNull { it.id == activeId }
}

internal interface AuthTransport {
    suspend fun client(server: String): AuthClient
    suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String): AuthTokens
    suspend fun refresh(server: String, client: AuthClient, token: String): AuthTokens
    suspend fun me(server: String, token: String): AuthProfile
    suspend fun revoke(server: String, token: String)
    suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject = throw AuthFailure("unsupported_account_operation")
}

internal fun authErrorReason(status: Int, json: JsonObject?): String {
    fun field(name: String) = runCatching { json?.get(name)?.asString }.getOrNull().orEmpty()
    val known = setOf("missing_two_factor", "invalid_two_factor", "account_blocked", "email_not_verified",
        "account_waiting_for_approval", "account_approval_rejected", "invalid_grant", "invalid_client", "invalid_token", "too_long_password")
    for (value in listOf(field("code"), field("error"), field("type").substringAfterLast('/').substringAfterLast('#'))) {
        if (value in known) return value
    }
    // Older PeerTube versions predate the machine-readable problem codes.
    val legacy = (field("error") + " " + field("error_description") + " " + field("detail")).lowercase(Locale.ROOT)
    if (legacy.contains("missing two factor")) return "missing_two_factor"
    if (legacy.contains("invalid two factor")) return "invalid_two_factor"
    return when (status) { 301, 302, 303, 307, 308 -> "redirect"; 401 -> "invalid_grant"; 403 -> "forbidden";
        404 -> "not_peertube"; 429 -> "rate_limited"; in 500..599 -> "server_error"; else -> "request_failed" }
}

internal class PeerTubeAuthTransport(client: OkHttpClient = OkHttpClient()) : AuthTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private fun request(server: String, path: String, token: String? = null): Request.Builder {
        val origin = authServerOrigin(server)
        require(path.startsWith("/api/v1/") && !path.contains('?'))
        return Request.Builder().url(origin + path).header("Accept", "application/json").apply {
            if (token != null) header("Authorization", "Bearer $token")
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request, empty: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response, onCancellation = { response.close() })
                }
            })
        }
        response.use {
            val bytes = it.body?.byteStream()?.let { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    if (out.size() + count > 1_048_576) throw AuthFailure("invalid_response")
                    out.write(buffer, 0, count)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }.orEmpty()
            val json = runCatching { gson.fromJson(bytes, JsonObject::class.java) }.getOrNull()
            if (!it.isSuccessful) throw AuthFailure(authErrorReason(it.code, json), it.code)
            if (empty) return@withContext JsonObject()
            json ?: throw AuthFailure("invalid_response")
        }
    }
    private fun JsonObject.text(key: String): String = runCatching { get(key)?.asString }.getOrNull().orEmpty()
    private fun tokens(json: JsonObject): AuthTokens {
        val access = json.text("access_token"); val refresh = json.text("refresh_token")
        val expires = runCatching { json.get("expires_in").asLong }.getOrDefault(0)
        if (access.isBlank() || refresh.isBlank() || expires !in 1..31_536_000 ||
            json.text("token_type").lowercase(Locale.ROOT) != "bearer") throw AuthFailure("invalid_response")
        return AuthTokens(access, refresh, expires)
    }
    override suspend fun client(server: String): AuthClient {
        val json = execute(request(server, "/api/v1/oauth-clients/local").build())
        val id = json.text("client_id"); val secret = json.text("client_secret")
        if (id.isBlank() || secret.isBlank()) throw AuthFailure("not_peertube")
        return AuthClient(id, secret)
    }
    override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String): AuthTokens {
        val body = FormBody.Builder().add("client_id", client.id).add("client_secret", client.secret)
            .add("grant_type", "password").add("response_type", "code").add("username", username).add("password", password).build()
        return tokens(execute(request(server, "/api/v1/users/token").apply {
            if (otp.isNotBlank()) header("x-peertube-otp", otp)
        }.post(body).build()))
    }
    override suspend fun refresh(server: String, client: AuthClient, token: String): AuthTokens {
        val body = FormBody.Builder().add("client_id", client.id).add("client_secret", client.secret)
            .add("grant_type", "refresh_token").add("refresh_token", token).build()
        return tokens(execute(request(server, "/api/v1/users/token").post(body).build()))
    }
    override suspend fun me(server: String, token: String): AuthProfile {
        val json = execute(request(server, "/api/v1/users/me", token).build())
        val id = runCatching { json.get("id").asLong }.getOrDefault(0)
        val username = json.text("username")
        if (id <= 0 || username.isBlank()) throw AuthFailure("invalid_response")
        val account = runCatching { json.getAsJsonObject("account") }.getOrNull()
        val display = account?.text("displayName")?.takeIf { it.isNotBlank() } ?: username
        val avatar = runCatching { account?.getAsJsonArray("avatars")?.firstOrNull()?.asJsonObject?.let { it.text("fileUrl").ifBlank { it.text("path") } } }.getOrNull()
        val avatarUrl = avatar?.takeIf { it.isNotBlank() }?.let { value -> runCatching {
            URI(authServerOrigin(server) + "/").resolve(value).takeIf { it.scheme == "https" && it.userInfo == null && it.host != null }?.toString()
        }.getOrNull() }
        return AuthProfile(id, username, display, avatarUrl)
    }
    override suspend fun revoke(server: String, token: String) {
        execute(request(server, "/api/v1/users/revoke-token", token).post(FormBody.Builder().build()).build(), empty = true)
    }
    override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
        val url = okhttp3.HttpUrl.Builder().scheme("https")
        val origin = authServerOrigin(server).toHttpUrl()
        url.host(origin.host).port(origin.port)
        operation.segments.forEach { url.addPathSegment(it) }
        operation.query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Accept", "application/json").header("Authorization", "Bearer $token")
        val body = operation.body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        request.method(operation.method, if (operation.method in listOf("POST", "PUT")) body ?: FormBody.Builder().build() else null)
        return try { execute(request.build(), operation.empty) }
        catch (failure: AuthFailure) { if (failure.status == 404) throw AuthFailure("account_resource_missing", 404) else throw failure }
    }
}
