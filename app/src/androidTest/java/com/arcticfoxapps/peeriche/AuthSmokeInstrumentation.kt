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
                var followed = false
                var rating = "none"
                val videoId = "11111111-1111-4111-8111-111111111111"
                val gson = com.google.gson.Gson()
                fun json(raw: String) = gson.fromJson(raw, com.google.gson.JsonObject::class.java)
                val api = object : AuthTransport {
                    override suspend fun client(server: String) = AuthClient("test-client", "test-client-secret")
                    override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("fake-access", "fake-refresh", 120)
                    override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("rotated-access", "rotated-refresh", 3600)
                    override suspend fun me(server: String, token: String) = AuthProfile(7, "smoke", "Smoke account")
                    override suspend fun revoke(server: String, token: String) { revoked = true }
                    override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): com.google.gson.JsonObject {
                        check(server == "https://smoke.example")
                        val path = operation.segments.drop(2).joinToString("/")
                        return when {
                            path == "users/me" -> json("""{"id":7,"videoChannels":[{"name":"smoke_channel","displayName":"Smoke channel"}]}""")
                            path == "users/me/subscriptions" && operation.method == "GET" -> json(if (followed) """{"total":1,"data":[{"name":"news","host":"remote.example"}]}""" else """{"total":0,"data":[]}""")
                            path == "users/me/subscriptions" && operation.method == "POST" -> { followed = true; json("{}") }
                            path == "users/me/subscriptions/news@remote.example" -> {
                                if (!followed) throw AuthFailure("account_resource_missing", 404)
                                if (operation.method == "DELETE") followed = false
                                json("{}")
                            }
                            path == "users/me/subscriptions/videos" -> json("""{"total":1,"data":[{"uuid":"$videoId","name":"Device fixture","host":"remote.example"}]}""")
                            path == "videos/$videoId" -> json("""{"uuid":"$videoId","name":"Device fixture","host":"remote.example"}""")
                            path == "users/me/videos/$videoId/rating" -> json("""{"rating":"$rating"}""")
                            path == "videos/$videoId/rate" -> { rating = operation.body!!.get("rating").asString; json("{}") }
                            operation.method == "POST" && path.startsWith("videos/$videoId/") -> {
                                check(operation.body!!.get("text").asString == "Проверка")
                                json("""{"comment":{"id":100,"text":"Проверка","heldForReview":true}}""")
                            }
                            else -> throw AuthFailure("invalid_response")
                        }
                    }
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
                val library = AccountLibrary(restored)
                try {
                    library.refresh(account.id)
                    check(library.snapshots.value[account.id]!!.own.single().name == "smoke_channel")
                    val channel = PeerTubeChannel("News", "news", "remote.example")
                    library.setSubscribed(account.id, channel, true)
                    check(followed && library.isSubscribed(account.id, "news@remote.example"))
                    check(library.feed(account.id, 0, false).data.single().uuid == videoId)
                    val resolved = library.resolveVideo(account.id, Video(uuid = videoId, host = "remote.example"), "https://smoke.example")
                    library.rate(account.id, resolved.uuid, "like")
                    check(library.rating(account.id, resolved.uuid) == "like")
                    library.rate(account.id, resolved.uuid, "none")
                    check(library.rating(account.id, resolved.uuid) == "none")
                    check(library.post(account.id, resolved.uuid, "Проверка", null).heldForReview)
                    check(library.post(account.id, resolved.uuid, "Проверка", 100).id == 100)
                    library.setSubscribed(account.id, channel, false)
                    check(!followed)
                } finally { library.close() }
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
            result.putString("stream", "PASS: Android Keystore, restore, refresh, own channels, subscriptions/feed, remote video, rating/removal, comment/reply, tamper rejection, AtomicFile recovery, logout (isolated fixtures).\n")
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
