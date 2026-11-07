package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Isolated device-side Keystore/AtomicFile integration test, without a real account or new dependencies. */
class AuthSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val suffix = UUID.randomUUID().toString()
        val name = "auth-smoke-$suffix.bin"
        val store = EncryptedAccountStore(targetContext, name, "peeriche.test.$suffix")
        var resultCode = Activity.RESULT_OK
        try {
            runBlocking {
                var clock = 1000L
                var revoked = false
                val api = object : AuthTransport {
                    override suspend fun client(server: String) = AuthClient("test-client", "test-client-secret")
                    override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("fake-access", "fake-refresh", 120)
                    override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("rotated-access", "rotated-refresh", 3600)
                    override suspend fun me(server: String, token: String) = AuthProfile(7, "smoke", "Smoke account")
                    override suspend fun revoke(server: String, token: String) { revoked = true }
                }
                val first = AccountSessionManager(store, api) { clock }
                val account = first.login("https://smoke.example", "smoke", "fake-password-not-persisted", "")
                val file = File(targetContext.noBackupFilesDir, name)
                val raw = file.readBytes().toString(Charsets.UTF_8)
                check(!raw.contains("fake-access") && !raw.contains("fake-refresh") && !raw.contains("fake-password"))
                val restored = AccountSessionManager(store, api) { clock }
                restored.initialize()
                check(restored.state.value.active?.id == account.id)
                clock = 121_000L
                restored.refreshProfile(account.id)
                check(store.load().sessions.single().refresh == "rotated-refresh")
                val encrypted = file.readBytes()
                file.writeBytes(encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
                var rejected = false
                try { store.load() } catch (_: Exception) { rejected = true }
                check(rejected)
                file.writeBytes(encrypted)
                check(file.renameTo(File(file.path + ".bak")))
                check(store.load().sessions.single().profile.userId == 7L)
                check(restored.logout(account.id) && revoked)
                check(store.load().sessions.isEmpty())
            }
            result.putString("stream", "PASS: Android Keystore AES-GCM, encrypted persistence, restore, refresh rotation, tamper rejection, AtomicFile backup recovery, logout.\n")
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: " + failure.javaClass.simpleName + "\n")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            try { runBlocking { store.clear() } }
            catch (_: Exception) { resultCode = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: test storage cleanup\n") }
        }
        finish(resultCode, result)
    }
}
