package com.arcticfoxapps.peeriche

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AccountSessionManagerTest {
    private class MemoryStore(var data: StoredAccounts = StoredAccounts()) : AccountSessionStore {
        var failLoad = false; var failSave = false
        override suspend fun load(): StoredAccounts { if (failLoad) throw IOException(); return data }
        override suspend fun save(accounts: StoredAccounts) { if (failSave) throw IOException(); data = accounts }
        override suspend fun clear() { data = StoredAccounts(); failLoad = false }
    }
    private class Api : AuthTransport {
        val profile = AuthProfile(1, "alice", "Alice")
        val credentials = AuthClient("client", "client-secret")
        var refreshes = 0; var revoked = 0; var loginOtp = ""; var loginUsername = ""
        var loginError: Exception? = null; var refreshError: Exception? = null; var meError: Exception? = null
        var firstMeUnauthorized = false; var revokeError = false; var refreshDelay = false
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun client(server: String) = credentials
        override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String): AuthTokens {
            loginError?.let { throw it }; loginOtp = otp; loginUsername = username
            return AuthTokens("access", "refresh", 3600)
        }
        override suspend fun refresh(server: String, client: AuthClient, token: String): AuthTokens {
            refreshes++; if (refreshDelay) delay(10)
            refreshError?.let { throw it }; return AuthTokens("rotated-access", "rotated-refresh", 3600)
        }
        override suspend fun me(server: String, token: String): AuthProfile {
            calls += server to token
            if (firstMeUnauthorized && calls.size == 1) throw AuthFailure("invalid_token", 401)
            meError?.let { throw it }; return profile
        }
        override suspend fun revoke(server: String, token: String) { revoked++; if (revokeError) throw IOException() }
    }
    private fun session(server: String = "https://one.example", expires: Long = 9_000_000) =
        AuthSession(server, AuthClient("id", "secret"), "access-$server", "refresh-$server", expires, AuthProfile(1, "alice", "Alice"))
    private suspend fun failure(block: suspend () -> Unit): AuthFailure {
        try { block() } catch (error: AuthFailure) { return error }
        throw AssertionError("Expected AuthFailure")
    }
    @Test fun loginVerifiesProfileAndPersistsSessionWithoutPassword() = runBlocking {
        val store = MemoryStore(); val api = Api(); val manager = AccountSessionManager(store, api) { 1000 }
        val account = manager.login("ONE.example/", " alice ", "unique-password-never-persist", " 123456 ")
        assertEquals("https://one.example|1", account.id); assertEquals("Alice", manager.state.value.active?.displayName)
        assertEquals("alice", api.loginUsername); assertEquals("123456", api.loginOtp)
        assertFalse(com.google.gson.Gson().toJson(store.data).contains("unique-password-never-persist"))
        val restored = AccountSessionManager(store, api); restored.initialize()
        assertEquals(account.id, restored.state.value.activeId)
    }
    @Test fun failedTwoFactorLoginDoesNotSaveSession() = runBlocking {
        val store = MemoryStore(); val api = Api().apply { loginError = AuthFailure("missing_two_factor", 401) }
        assertEquals("missing_two_factor", failure { AccountSessionManager(store, api).login("one.example", "alice", "pw", "") }.reason)
        assertTrue(store.data.sessions.isEmpty()); assertEquals(0, api.revoked)
    }
    @Test fun profileVerificationFailureRevokesIssuedToken() = runBlocking {
        val store = MemoryStore(); val api = Api().apply { meError = AuthFailure("invalid_response") }
        failure { AccountSessionManager(store, api).login("one.example", "alice", "pw", "") }
        assertTrue(store.data.sessions.isEmpty()); assertEquals(1, api.revoked)
    }
    @Test fun storageFailureDoesNotExposeSignedInAccount() = runBlocking {
        val store = MemoryStore().apply { failSave = true }; val api = Api(); val manager = AccountSessionManager(store, api)
        assertEquals("storage_error", failure { manager.login("one.example", "alice", "pw", "") }.reason)
        assertTrue(manager.state.value.accounts.isEmpty()); assertEquals(1, api.revoked)
    }
    @Test fun expiredAccessRotatesAndStoresRefreshToken() = runBlocking {
        val old = session(expires = 999); val store = MemoryStore(StoredAccounts(listOf(old), old.id)); val api = Api()
        AccountSessionManager(store, api) { 1000 }.refreshProfile(old.id)
        assertEquals(1, api.refreshes); assertEquals("rotated-refresh", store.data.sessions.single().refresh)
        assertEquals(old.server to "rotated-access", api.calls.single())
    }
    @Test fun unauthorizedProfileRetriesOnlyOnceWithRefreshedToken() = runBlocking {
        val old = session(); val store = MemoryStore(StoredAccounts(listOf(old), old.id)); val api = Api().apply { firstMeUnauthorized = true }
        AccountSessionManager(store, api) { 1000 }.refreshProfile(old.id)
        assertEquals(1, api.refreshes); assertEquals(2, api.calls.size)
    }
    @Test fun rejectedRefreshRequiresReentryAndErasesOldTokens() = runBlocking {
        val old = session(expires = 0); val store = MemoryStore(StoredAccounts(listOf(old), old.id)); val api = Api().apply { refreshError = AuthFailure("invalid_grant", 400) }
        val manager = AccountSessionManager(store, api) { 1000 }
        assertEquals("session_expired", failure { manager.refreshProfile(old.id) }.reason)
        assertTrue(manager.state.value.active!!.needsLogin); assertEquals("", store.data.sessions.single().access)
        assertEquals("", store.data.sessions.single().refresh)
    }
    @Test fun networkRefreshFailureKeepsSavedSession() = runBlocking {
        val old = session(expires = 0); val store = MemoryStore(StoredAccounts(listOf(old), old.id)); val api = Api().apply { refreshError = IOException("offline") }
        try { AccountSessionManager(store, api) { 1000 }.refreshProfile(old.id); fail() } catch (_: IOException) { }
        assertFalse(store.data.sessions.single().needsLogin); assertEquals(old.refresh, store.data.sessions.single().refresh)
    }
    @Test fun repeatedUnauthorizedDoesNotLoop() = runBlocking {
        val old = session(); val store = MemoryStore(StoredAccounts(listOf(old), old.id)); val api = Api().apply { meError = AuthFailure("invalid_token", 401) }
        assertEquals("session_expired", failure { AccountSessionManager(store, api) { 1000 }.refreshProfile(old.id) }.reason)
        assertEquals(1, api.refreshes); assertEquals(2, api.calls.size)
    }
    @Test fun concurrentChecksShareSingleRefresh() = runBlocking {
        val old = session(expires = 0); val store = MemoryStore(StoredAccounts(listOf(old), old.id)); val api = Api().apply { refreshDelay = true }
        val manager = AccountSessionManager(store, api) { 1000 }
        awaitAll(async { manager.refreshProfile(old.id) }, async { manager.refreshProfile(old.id) })
        assertEquals(1, api.refreshes); assertEquals(2, api.calls.size)
    }
    @Test fun multipleServersNeverReuseEachOthersAccessToken() = runBlocking {
        val one = session(); val two = session("https://two.example")
        val store = MemoryStore(StoredAccounts(listOf(one, two), one.id)); val api = Api(); val manager = AccountSessionManager(store, api) { 1000 }
        manager.activate(two.id); manager.refreshProfile(two.id); manager.refreshProfile(one.id)
        assertEquals(listOf(two.server to two.access, one.server to one.access), api.calls)
        assertEquals(two.id, manager.state.value.activeId)
    }
    @Test fun offlineLogoutForgetsLocalSessionAndReportsServerFailure() = runBlocking {
        val one = session(); val two = session("https://two.example"); val store = MemoryStore(StoredAccounts(listOf(one, two), one.id))
        val api = Api().apply { revokeError = true }; val manager = AccountSessionManager(store, api)
        assertFalse(manager.logout(one.id)); assertEquals(listOf(two.id), manager.state.value.accounts.map { it.id })
        assertEquals(two.id, manager.state.value.activeId); assertEquals(1, api.revoked)
    }
    @Test fun corruptStorageIsNotSilentlyOverwrittenAndCanBeReset() = runBlocking {
        val store = MemoryStore().apply { failLoad = true }; val api = Api(); val manager = AccountSessionManager(store, api)
        assertEquals("storage_error", failure { manager.initialize() }.reason); assertTrue(manager.state.value.storageError)
        failure { manager.login("one.example", "alice", "pw", "") }; assertTrue(store.data.sessions.isEmpty())
        manager.resetStorage(); manager.login("one.example", "alice", "pw", ""); assertNotNull(manager.state.value.active)
    }
    @Test fun cancellationBeforeTokenResponseNeverSavesAnAccount() = runBlocking {
        val store = MemoryStore(); val api = Api(); val started = CompletableDeferred<Unit>()
        val waiting = object : AuthTransport by api {
            override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String): AuthTokens {
                started.complete(Unit); awaitCancellation()
            }
        }
        val manager = AccountSessionManager(store, waiting)
        val request = launch { manager.login("one.example", "alice", "pw", "") }
        started.await(); request.cancelAndJoin()
        assertTrue(store.data.sessions.isEmpty()); assertTrue(manager.state.value.accounts.isEmpty())
    }
    @Test fun rotatedRefreshSurvivesSubsequentProfileNetworkFailure() = runBlocking {
        val old = session(expires = 0); val store = MemoryStore(StoredAccounts(listOf(old), old.id))
        val api = Api().apply { meError = IOException("offline") }
        try { AccountSessionManager(store, api) { 1000 }.refreshProfile(old.id); fail() } catch (_: IOException) { }
        assertEquals("rotated-refresh", store.data.sessions.single().refresh)
        assertFalse(store.data.sessions.single().needsLogin)
    }
}
