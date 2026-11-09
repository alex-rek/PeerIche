package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/** Opt-in emulator-only UI fixture. Does not save credentials or perform external account writes. */
internal fun creatorUiFixture(instrumentation: Instrumentation) {
    val result = Bundle()
    var code = Activity.RESULT_OK
    val replacements = mutableListOf<Triple<Any, java.lang.reflect.Field, Any?>>()
    var activity: Activity? = null
    var fixtureUploads: VideoUploadCoordinator? = null
    val context = instrumentation.targetContext
    val marker = File(context.cacheDir, "creator-ui-finish")
    fun replace(target: Any, value: Any) {
        val field = target.javaClass.getDeclaredField("instance").apply { isAccessible = true }
        replacements += Triple(target, field, field.get(target)); field.set(target, value)
    }
    try {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.HARDWARE.contains("ranchu"))
        marker.delete()
        runBlocking {
            val gson = Gson()
            fun json(raw: String) = gson.fromJson(raw, JsonObject::class.java)
            val session = AuthSession("https://fixture.invalid", AuthClient("fixture", "secret"), "fixture-access", "fixture-refresh", Long.MAX_VALUE / 2, AuthProfile(1, "creator", "Тестовый автор"))
            val store = object : AccountSessionStore {
                var value = StoredAccounts(listOf(session), session.id)
                override suspend fun load() = value
                override suspend fun save(accounts: StoredAccounts) { value = accounts }
                override suspend fun clear() { value = StoredAccounts() }
            }
            var created = false
            var accepted = 0L
            var uploadTotal = 0L
            val liveUuid = "33333333-3333-4333-8333-333333333333"
            val api = object : AuthTransport {
                override suspend fun client(server: String) = session.client
                override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("fixture-access", "fixture-refresh", 3600)
                override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("fixture-access", "fixture-refresh", 3600)
                override suspend fun me(server: String, token: String) = session.profile
                override suspend fun revoke(server: String, token: String) { }
                override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
                    check(server == session.server)
                    return when (operation.segments.drop(2).joinToString("/")) {
                        "users/me" -> json("""{"id":1,"videoQuota":10737418240,"videoQuotaDaily":2147483648,"videoChannels":[{"id":7,"name":"creator","displayName":"Мой канал","host":"fixture.invalid"}]}""")
                        "config" -> json("""{"video":{"file":{"extensions":[".mp4"]},"image":{"size":{"max":8388608},"extensions":[".jpg",".png"]}},"live":{"enabled":true,"allowReplay":true},"import":{"videos":{"http":{"enabled":true}}}}""")
                        "users/me/video-quota-used" -> json("""{"videoQuotaUsed":123456789,"videoQuotaUsedDaily":1234567}""")
                        "users/me/subscriptions", "users/me/subscriptions/videos" -> json("""{"total":0,"data":[]}""")
                        "video-channels/creator" -> if (operation.method == "PUT") json("{}") else json("""{"id":7,"name":"creator","displayName":"Мой канал","description":"Описание тестового канала"}""")
                        "users/me/videos" -> json("""{"total":${if (created) 3 else 2},"data":[{"uuid":"11111111-1111-4111-8111-111111111111","name":"Поход в горы","privacy":{"id":1},"state":{"id":1}},{"uuid":"22222222-2222-4222-8222-222222222222","name":"Новый ролик — обработка на сервере","privacy":{"id":3},"state":{"id":2}}${if (created) ",{\"uuid\":\"$liveUuid\",\"name\":\"Тестовый эфир\",\"privacy\":{\"id\":2},\"state\":{\"id\":4},\"isLive\":true}" else ""}]}""")
                        "videos/live" -> { created = true; json("""{"video":{"uuid":"$liveUuid"}}""") }
                        "videos/imports" -> json("""{"video":{"uuid":"11111111-1111-4111-8111-111111111111"}}""")
                        "videos/live/$liveUuid" -> json("""{"rtmpsUrl":"rtmps://fixture.invalid:1936/live","streamKey":"test-fixture-key-not-real"}""")
                        else -> throw AuthFailure("account_resource_missing", 404)
                    }
                }
                override suspend fun uploadRequest(server: String, token: String, operation: UploadOperation): UploadReply {
                    check(server == session.server)
                    return when (operation.kind) {
                        "init" -> { accepted = 0; uploadTotal = operation.size; UploadReply(201, "$server$UPLOAD_PATH?upload_id=fixture-ui") }
                        "probe" -> if (accepted == uploadTotal) UploadReply(200, offset = accepted, videoUuid = "11111111-1111-4111-8111-111111111111") else UploadReply(308, offset = accepted)
                        "chunk" -> { accepted = operation.offset + operation.bytes.size; delay(200)
                            if (accepted == uploadTotal) UploadReply(200, offset = accepted, videoUuid = "11111111-1111-4111-8111-111111111111") else UploadReply(308, offset = accepted) }
                        "cover", "cancel" -> UploadReply(204)
                        else -> throw AuthFailure("invalid_response")
                    }
                }
            }
            val manager = AccountSessionManager(store, api); manager.initialize()
            replace(PeerTubeAccounts, manager)
            replace(AccountLibraries, AccountLibrary(manager))
            replace(Broadcasts, BroadcastCoordinator(context, manager))
            fixtureUploads = VideoUploadCoordinator(context, manager, "creator-ui-fixture.json")
            fixtureUploads!!.initialize(); replace(VideoUploads, fixtureUploads!!)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repeat(360) { if (marker.exists()) return@runBlocking; delay(1000) }
        }
        result.putString("stream", "Creator UI fixture finished (emulator, fake account, no external writes).\n")
    } catch (error: Throwable) {
        code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL creator UI fixture: ${error.javaClass.simpleName}\n")
    } finally {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        context.stopService(Intent(context, VideoUploadService::class.java))
        runBlocking { fixtureUploads?.discard(); fixtureUploads?.close() }
        replacements.asReversed().forEach { (target, field, original) ->
            if (target === AccountLibraries) (field.get(target) as? AccountLibrary)?.close()
            if (target === Broadcasts) instrumentation.runOnMainSync { (field.get(target) as? BroadcastCoordinator)?.close() }
            field.set(target, original)
        }
        marker.delete()
    }
    instrumentation.finish(code, result)
}
