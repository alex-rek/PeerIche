package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/** Exercises actual Android URI reads and AtomicFile recovery. Every network write is an in-memory fixture. */
internal suspend fun creatorSmoke(context: Context) {
    val unique = java.util.UUID.randomUUID().toString()
    val video = File(context.cacheDir, "updates/creator-smoke-$unique.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2_500_001) { (it % 251).toByte() }) }
    val cover = File(context.cacheDir, "updates/creator-smoke-$unique.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val storage = "creator-smoke-$unique.json"
    val gson = Gson()
    fun json(raw: String) = gson.fromJson(raw, JsonObject::class.java)
    val session = AuthSession("https://creator-fixture.example", AuthClient("fixture-client", "fixture-client-secret"), "fixture-access", "fixture-refresh", Long.MAX_VALUE / 2,
        AuthProfile(1, "creator", "Test creator"))
    val store = object : AccountSessionStore {
        var value = StoredAccounts(listOf(session, session.copy(server = "https://other-fixture.example")), session.id)
        override suspend fun load() = value
        override suspend fun save(accounts: StoredAccounts) { value = accounts }
        override suspend fun clear() { value = StoredAccounts() }
    }
    var accepted = 0L
    var initCount = 0
    var coverCount = 0
    var deletes = 0
    var lostReply = true
    var failCover = true
    var blockChunks = false
    var chunkEntered = CompletableDeferred<Unit>()
    val uuid = "11111111-1111-4111-8111-111111111111"
    val api = object : AuthTransport {
        override suspend fun client(server: String) = session.client
        override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("fixture-access", "fixture-refresh", 3600)
        override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("fixture-access", "fixture-refresh", 3600)
        override suspend fun me(server: String, token: String) = session.profile
        override suspend fun revoke(server: String, token: String) { }
        override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
            check(server == session.server)
            return when (operation.segments.drop(2).joinToString("/")) {
                "users/me" -> json("""{"id":1,"videoQuota":999999999,"videoQuotaDaily":999999999,"videoChannels":[{"id":7,"displayName":"Fixture channel"}]}""")
                "config" -> json("""{"video":{"file":{"extensions":[".mp4"]},"image":{"size":{"max":1024}}},"live":{"enabled":true,"allowReplay":true}}""")
                "users/me/video-quota-used" -> json("""{"videoQuotaUsed":0,"videoQuotaUsedDaily":0}""")
                "users/me/videos" -> json("""{"total":1,"data":[{"uuid":"$uuid","name":"Fixture video","privacy":{"id":3},"state":{"id":2}}]}""")
                "videos/live" -> json("""{"video":{"uuid":"$uuid"}}""")
                "videos/live/$uuid" -> json("""{"rtmpUrl":"rtmp://fixture.invalid/live","rtmpsUrl":"rtmps://fixture.invalid/live","streamKey":"fixture-secret-key"}""")
                else -> throw AuthFailure("invalid_response")
            }
        }
        override suspend fun uploadRequest(server: String, token: String, operation: UploadOperation): UploadReply {
            check(server == session.server && token == "fixture-access")
            return when (operation.kind) {
                "init" -> { initCount++; accepted = 0; UploadReply(201, "$server$UPLOAD_PATH?upload_id=fixture") }
                "probe" -> if (accepted == video.length()) UploadReply(200, offset = accepted, videoUuid = uuid) else UploadReply(308, offset = accepted)
                "chunk" -> {
                    if (blockChunks) { chunkEntered.complete(Unit); awaitCancellation() }
                    check(operation.offset == accepted)
                    operation.bytes.forEachIndexed { index, byte -> check(byte == ((operation.offset + index) % 251).toByte()) }
                    accepted += operation.bytes.size
                    if (lostReply) { lostReply = false; throw IOException("fixture: server accepted chunk but connection lost") }
                    if (accepted == video.length()) UploadReply(200, offset = accepted, videoUuid = uuid) else UploadReply(308, offset = accepted)
                }
                "cover" -> { coverCount++; if (failCover) { failCover = false; throw AuthFailure("server_error", 500) }; UploadReply(204) }
                "cancel" -> { deletes++; accepted = 0; UploadReply(204) }
                else -> throw AuthFailure("invalid_response")
            }
        }
    }
    val accounts = AccountSessionManager(store, api)
    val coordinators = mutableListOf<VideoUploadCoordinator>()
    fun coordinator() = VideoUploadCoordinator(context, accounts, storage).also { coordinators += it }
    suspend fun settled(coordinator: VideoUploadCoordinator) = withTimeout(15000) { coordinator.state.first { it != null && !it.active }!! }
    val metadata = CreatorMetadata("Fixture publication", "Unpublished fixture", 7, 3)
    val file = UploadFileInfo(FileProvider.getUriForFile(context, context.packageName + ".updates", video).toString(), video.name, video.length(), "video/mp4")
    val image = UploadFileInfo(FileProvider.getUriForFile(context, context.packageName + ".updates", cover).toString(), cover.name, cover.length(), "image/png")
    try {
        accounts.initialize()
        val first = coordinator()
        first.prepare(session.id, file, metadata, image)
        first.run()
        check(settled(first).status == "failed" && accepted > 0)
        check(first.state.value!!.offset == 0L) // Lost acknowledgement did not advance local offset.
        first.close()
        val restored = coordinator()
        restored.initialize(); check(restored.state.value!!.location != null)
        restored.run()
        val coverFailure = settled(restored)
        check(coverFailure.videoUuid == uuid && coverFailure.status == "failed")
        check(accepted == video.length() && initCount == 1 && coverCount == 1)
        restored.close()
        val coverRetry = coordinator()
        coverRetry.initialize(); coverRetry.run()
        withTimeout(15000) { coverRetry.completionRevision.first { it > 0 } }
        check(coverRetry.state.value == null)
        check(initCount == 1 && coverCount == 2 && coverRetry.completionRevision.value == 1L)
        check(!File(context.noBackupFilesDir, storage).exists())
        // A completed task from an older app version is cleaned without another request.
        val oldDone = coverFailure.copy(status = "done", error = null)
        File(context.noBackupFilesDir, storage).writeText(gson.toJson(oldDone))
        val migrated = coordinator(); migrated.initialize()
        check(migrated.state.value == null && migrated.completionRevision.value == 1L)
        check(!File(context.noBackupFilesDir, storage).exists() && initCount == 1 && coverCount == 2)
        coverRetry.close()

        val pausable = coordinator()
        pausable.prepare(session.id, file, metadata, null)
        blockChunks = true; chunkEntered = CompletableDeferred()
        pausable.run(); withTimeout(15000) { chunkEntered.await() }
        pausable.pause(); check(settled(pausable).status == "paused")
        blockChunks = false
        accounts.activate("https://other-fixture.example|1")
        val beforeSwitch = initCount
        pausable.run(); check(settled(pausable).error == "account_changed" && initCount == beforeSwitch)
        accounts.activate(session.id)
        pausable.run(cancel = true); check(settled(pausable).status == "cancelled" && deletes == 1)
        pausable.discard()
        val repository = CreatorRepository(accounts)
        check(repository.videos(session.id, 0).rows.single().privacy == 3)
        check(repository.createLive(session.id, metadata, replay = true, permanent = false) == uuid)
        val live = repository.liveCredentials(session.id, uuid)
        check(live.url.startsWith("rtmps://") && live.key == "fixture-secret-key" && !live.toString().contains(live.key))
    } finally {
        coordinators.forEach { it.close() }
        video.delete(); cover.delete()
        android.util.AtomicFile(File(context.noBackupFilesDir, storage)).delete()
    }
}
