package com.arcticfoxapps.peeriche

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface AccountSessionStore {
    suspend fun load(): StoredAccounts
    suspend fun save(accounts: StoredAccounts)
    suspend fun clear()
}

internal class AccountSessionManager(private val store: AccountSessionStore, private val api: AuthTransport,
    private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var loaded = false
    private var saved = StoredAccounts()
    private val mutable = MutableStateFlow(AccountsState())
    val state = mutable.asStateFlow()
    private fun publish(storageError: Boolean = false) {
        mutable.value = AccountsState(ready = true, accounts = saved.sessions.map {
            AccountSummary(it.id, it.server, it.profile.username, it.profile.displayName, it.profile.avatar, it.needsLogin)
        }, activeId = saved.activeId, storageError = storageError)
    }
    private suspend fun loadLocked() {
        if (loaded) return
        try {
            val data = store.load()
            require(data.sessions.size <= 20 && data.sessions.map { it.id }.distinct().size == data.sessions.size)
            data.sessions.forEach { session ->
                require(authServerOrigin(session.server) == session.server && session.profile.userId > 0 && session.profile.username.isNotBlank())
                require(session.needsLogin || (session.access.isNotBlank() && session.refresh.isNotBlank() && session.client.id.isNotBlank() && session.client.secret.isNotBlank()))
            }
            saved = data.copy(activeId = data.activeId?.takeIf { id -> data.sessions.any { it.id == id } } ?: data.sessions.firstOrNull()?.id)
            loaded = true; publish()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(storageError = true); throw AuthFailure("storage_error") }
    }
    suspend fun initialize() = mutex.withLock { loadLocked() }
    private suspend fun commit(next: StoredAccounts) {
        withContext(NonCancellable) {
            try { store.save(next) }
            catch (_: Exception) { throw AuthFailure("storage_error") }
            saved = next; publish()
        }
    }
    private fun expiry(tokens: AuthTokens): Long {
        if (tokens.access.isBlank() || tokens.refresh.isBlank() || tokens.expiresIn !in 1..31_536_000) throw AuthFailure("invalid_response")
        return now() + tokens.expiresIn * 1000
    }
    suspend fun login(serverInput: String, usernameInput: String, password: String, otp: String): AccountSummary = mutex.withLock {
        loadLocked()
        val server = authServerOrigin(serverInput)
        val username = usernameInput.trim()
        if (username.isBlank() || password.isEmpty()) throw AuthFailure("missing_credentials")
        val client = api.client(server)
        val tokens = api.login(server, client, username, password, otp.trim())
        try {
            val expiresAt = expiry(tokens)
            val profile = api.me(server, tokens.access)
            if (profile.userId <= 0 || profile.username.isBlank()) throw AuthFailure("invalid_response")
            val session = AuthSession(server, client, tokens.access, tokens.refresh, expiresAt, profile)
            if (saved.sessions.size >= 20 && saved.sessions.none { it.id == session.id }) throw AuthFailure("account_limit")
            commit(StoredAccounts(saved.sessions.filterNot { it.id == session.id } + session, session.id))
            state.value.active!!
        } catch (error: Exception) {
            // A token was issued but profile verification/storage failed: do not
            // leave a newly-created server session behind when possible.
            if (saved.sessions.none { it.server == server && it.access == tokens.access })
                withContext(NonCancellable) { withTimeoutOrNull(3000) { runCatching { api.revoke(server, tokens.access) } } }
            throw error
        }
    }
    suspend fun activate(id: String) = mutex.withLock {
        loadLocked()
        if (saved.sessions.none { it.id == id }) throw AuthFailure("session_expired")
        commit(saved.copy(activeId = id))
    }
    private suspend fun replace(session: AuthSession) { commit(saved.copy(sessions = saved.sessions.map { if (it.id == session.id) session else it })) }
    private suspend fun expire(session: AuthSession): Nothing {
        replace(session.copy(access = "", refresh = "", needsLogin = true))
        throw AuthFailure("session_expired")
    }
    private suspend fun refresh(session: AuthSession): AuthSession {
        val tokens = try { api.refresh(session.server, session.client, session.refresh) }
        catch (error: AuthFailure) {
            if (error.reason in setOf("invalid_grant", "invalid_client", "invalid_token") || error.status == 401) expire(session)
            throw error
        }
        val updated = session.copy(access = tokens.access, refresh = tokens.refresh, expiresAt = expiry(tokens))
        // Save rotating refresh tokens before a subsequent request can fail.
        replace(updated)
        return updated
    }
    private suspend fun <T> authorizedLocked(id: String, block: suspend (AuthSession) -> T): T {
        loadLocked()
        var session = saved.sessions.firstOrNull { it.id == id } ?: throw AuthFailure("session_expired")
        if (session.needsLogin) throw AuthFailure("session_expired")
        var refreshed = false
        if (session.expiresAt <= now() + 60_000) { session = refresh(session); refreshed = true }
        return try { block(session) }
        catch (error: AuthFailure) {
            if (error.status != 401 && error.reason != "invalid_token") throw error
            if (refreshed) expire(session)
            session = refresh(session)
            try { block(session) }
            catch (retry: AuthFailure) {
                if (retry.status == 401 || retry.reason == "invalid_token") expire(session)
                throw retry
            }
        }
    }
    suspend fun refreshProfile(id: String): AccountSummary = mutex.withLock {
        val profile = authorizedLocked(id) { session -> api.me(session.server, session.access) }
        val current = saved.sessions.first { it.id == id }
        if (profile.userId != current.profile.userId) throw AuthFailure("invalid_response")
        replace(current.copy(profile = profile))
        state.value.accounts.first { it.id == id }
    }
    suspend fun request(id: String, operation: AccountOperation): com.google.gson.JsonObject = mutex.withLock {
        loadLocked()
        if (operation.writes && saved.activeId != id) throw AuthFailure("account_changed")
        authorizedLocked(id) { session -> api.accountRequest(session.server, session.access, operation) }
    }
    suspend fun upload(id: String, operation: UploadOperation): UploadReply = mutex.withLock {
        loadLocked()
        if (saved.activeId != id) throw AuthFailure("account_changed")
        authorizedLocked(id) { session -> api.uploadRequest(session.server, session.access, operation) }
    }
    suspend fun logout(id: String): Boolean {
        val session = mutex.withLock {
            loadLocked()
            val found = saved.sessions.firstOrNull { it.id == id } ?: return true
            val remaining = saved.sessions.filterNot { it.id == id }
            commit(StoredAccounts(remaining, if (saved.activeId == id) remaining.firstOrNull()?.id else saved.activeId))
            found
        }
        if (session.needsLogin || session.access.isBlank()) return true
        return withContext(NonCancellable) { withTimeoutOrNull(5000) {
            try { api.revoke(session.server, session.access); true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        } ?: false }
    }
    suspend fun resetStorage() = mutex.withLock {
        withContext(NonCancellable) {
            try { store.clear() } catch (_: Exception) { throw AuthFailure("storage_error") }
            saved = StoredAccounts(); loaded = true; publish()
        }
    }
}
