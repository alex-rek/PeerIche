package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Isolated device-side Keystore/AtomicFile integration test, without a real account or new dependencies. */
class AuthSmokeInstrumentation : Instrumentation() {
    private var hiddenUi = false
    private var hiddenChannels = false
    private var creatorUi = false
    private var broadcastRtmp = false
    private var broadcastCamera = false
    private var interfaceTranslations = false
    private var interfaceLocales = false
    private var startupUpdate = false
    private var playbackPosition = false
    private var broadcastLogo = false
    private var nativeCamera = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); hiddenUi = arguments?.getString("hidden-ui") == "true"; hiddenChannels = arguments?.getString("hidden-channels") == "true"; nativeCamera = arguments?.getString("native-camera") == "true"; broadcastLogo = arguments?.getString("broadcast-logo") == "true"; playbackPosition = arguments?.getString("playback-position") == "true"; startupUpdate = arguments?.getString("startup-update") == "true"; interfaceLocales = arguments?.getString("interface-locales") == "true"; interfaceTranslations = arguments?.getString("interface-translations") == "true"; creatorUi = arguments?.getString("creator-ui") == "true"; broadcastRtmp = arguments?.getString("broadcast-rtmp") == "true"; broadcastCamera = arguments?.getString("broadcast-camera") == "true"; start() }
    override fun onStart() {
        if (hiddenUi) { hiddenChannelsUiFixture(this); return }
        if (hiddenChannels) {
            val result = Bundle(); var code = Activity.RESULT_OK
            try { runBlocking { hiddenChannelsSmoke(targetContext) }; result.putString("stream", "PASS: hidden channels survive database reopen; restoring does not remove subscription\n") }
            catch (failure: Throwable) { code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL hidden channels: ${failure.message}\n") }
            finish(code, result); return
        }
        if (nativeCamera) {
            val result = Bundle(); var code = Activity.RESULT_OK
            try { runBlocking { nativeCameraSmoke(this@AuthSmokeInstrumentation) }; result.putString("stream", "PASS: native Camera2 encoded frames in both formats without double fitting; no network publication\n") }
            catch (failure: Throwable) { code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL native camera: ${failure.message}\n") }
            finish(code, result); return
        }
        if (broadcastLogo) {
            val result = Bundle(); var code = Activity.RESULT_OK
            try { runBlocking { broadcastLogoSmoke(targetContext) }; result.putString("stream", "PASS: encoded logo, PNG transparency, source switch and disable in both orientations\n") }
            catch (failure: Throwable) { code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL logo: ${failure.message}\n") }
            finish(code, result); return
        }
        if (playbackPosition) { playbackPositionSmoke(this); return }
        if (startupUpdate) { startupUpdateSmoke(this); return }
        if (interfaceTranslations) { exportInterfaceTranslations(this); return }
        if (interfaceLocales) { verifyInterfaceLocales(this); return }
        if (creatorUi) { creatorUiFixture(this); return }
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
                creatorSmoke(targetContext)
                broadcastSmoke(targetContext, broadcastRtmp)
                if (broadcastCamera) broadcastControllerSmoke(this@AuthSmokeInstrumentation)
            }
            result.putString("stream", "PASS: auth/Keystore, account actions, upload resume/cancel, OBS fixtures; synthetic H.264/AAC encoding and source switching" + (if (broadcastRtmp) ", loopback RTMP publishing" else "") + (if (broadcastCamera) ", camera FGS, virtual-camera switching/release, slate, account-switch stop" else "") + " (no external publication).\n")
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: " + failure.javaClass.simpleName + " at " + failure.stackTrace.firstOrNull { it.className.startsWith("com.arcticfoxapps.peeriche") } + " " + if (failure.message?.startsWith("synthetic ") == true) failure.message + "\n" else "\n")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            try { runBlocking { store.clear() } }
            catch (_: Exception) { resultCode = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: test storage cleanup\n") }
        }
        finish(resultCode, result)
    }
}
