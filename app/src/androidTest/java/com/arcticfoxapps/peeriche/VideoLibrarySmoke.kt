package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Synthetic local audio and an in-memory account API. Never publishes or uses real credentials. */
internal fun videoLibrarySmoke(test: Instrumentation) {
    val context = test.targetContext
    val suffix = UUID.randomUUID().toString()
    val dbName = "library-smoke-$suffix.db"
    val audio = File(context.cacheDir, "queue-$suffix.wav")
    var controller: MediaController? = null
    var previousManager: Any? = null
    var managerReplaced = false
    val managerField = PeerTubeAccounts.javaClass.getDeclaredField("instance").apply { isAccessible = true }
    val preferences = AppPreferences.get(context)
    val oldOptions = preferences.options.value
    val repository = VideoPlaylists(context)
    var playlist: String? = null
    val result = Bundle(); var code = Activity.RESULT_OK
    try {
        check(android.os.Build.HARDWARE.contains("ranchu"))
        runBlocking {
            // Opening a genuine version-2 database must preserve its existing history.
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
                db.execSQL("CREATE TABLE saved_videos (identity TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, videoUrl TEXT, server TEXT NOT NULL, thumbnailUrl TEXT, channelName TEXT, views INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(kind, identity))")
                db.execSQL("INSERT INTO saved_videos VALUES ('legacy','history','Preserved',NULL,'https://example.org',NULL,NULL,1,1)")
                db.version = 2
            }
            val migrated = Room.databaseBuilder(context, PeerIcheDatabase::class.java, dbName).addMigrations(PeerIcheDatabase.migration2To3).build()
            try {
                val db = migrated
                val row = db.library().getAll("history").single()
                check(row.title == "Preserved" && row.metadata == null)
            } finally { migrated.close() }
            playlist = repository.create("Smoke $suffix")
            val a = Video(uuid = "a", host = "fixture.invalid", name = "First")
            val b = a.copy(uuid = "b", name = "Second")
            repository.add(playlist!!, a, "https://fixture.invalid")
            repository.add(playlist!!, b, "https://fixture.invalid")
            repository.add(playlist!!, a, "https://fixture.invalid")
            val rows = PeerIcheDatabase.get(context).library().getAll(playlistKind(playlist!!)).sortedBy { it.updatedAt }
            check(rows.map { it.toVideo().apiId() } == listOf("a", "b"))
            repository.delete(playlist!!)
            check(PeerIcheDatabase.get(context).library().getAll(playlistKind(playlist!!)).isEmpty())
            playlist = null
            val session = AuthSession("https://fixture.invalid", AuthClient("fixture", "secret"), "fake", "fake-refresh", Long.MAX_VALUE / 2, AuthProfile(1, "queue", "Queue fixture"))
            val store = object : AccountSessionStore {
                var value = StoredAccounts(listOf(session), session.id)
                override suspend fun load() = value
                override suspend fun save(accounts: StoredAccounts) { value = accounts }
                override suspend fun clear() { value = StoredAccounts() }
            }
            val api = object : AuthTransport {
                override suspend fun client(server: String) = session.client
                override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("fake", "fake", 3600)
                override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("fake", "fake", 3600)
                override suspend fun me(server: String, token: String) = session.profile
                override suspend fun revoke(server: String, token: String) {}
                override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
                    val path = operation.segments.drop(2)
                    check(path.first() == "videos")
                    if (path[1] == "bad") throw AuthFailure("account_resource_missing", 404)
                    return Gson().fromJson(if (path.size > 2) "{\"data\":[]}" else
                        """{"uuid":"${path[1]}","name":"Next ${path[1]}","directStreamUrl":"https://127.0.0.1:1/${path[1]}.mp4","privacy":{"id":3}}""", JsonObject::class.java)
                }
            }
            val manager = AccountSessionManager(store, api).also { it.initialize() }
            previousManager = managerField.get(PeerTubeAccounts)
            managerField.set(PeerTubeAccounts, manager)
            managerReplaced = true
            PlaybackQueue.replace(listOf("bad", "second", "third").map { a.copy(uuid = it, playbackAccountId = session.id) })
        }
        preferences.update { it.copy(translateSubtitles = false, speech = it.speech.copy(enabled = false)) }
        val bytes = 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(bytes + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
        }.array()
        audio.outputStream().use { it.write(header); it.write(ByteArray(bytes)) }
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        test.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        controller = future.get(10, TimeUnit.SECONDS)
        fun localItem(id: String) = MediaItem.Builder().setMediaId(id).setUri(android.net.Uri.fromFile(audio)).build()
        test.runOnMainSync { controller!!.setMediaItem(localItem("first")); controller!!.prepare(); controller!!.play() }
        fun waitFor(id: String) {
            var found = false
            repeat(100) {
                test.runOnMainSync { found = controller!!.currentMediaItem?.mediaId == id && controller!!.playWhenReady }
                if (found) return
                Thread.sleep(100)
            }
            error("Queue did not advance to $id")
        }
        waitFor("https://fixture.invalid|second")
        check(PlaybackQueue.entries.value.single().apiId() == "third")
        test.runOnMainSync { controller!!.setMediaItem(localItem("https://fixture.invalid|second")); controller!!.prepare(); controller!!.play() }
        waitFor("https://fixture.invalid|third")
        check(PlaybackQueue.entries.value.isEmpty())
        check(PlaybackQueue.playing.value?.apiId() == "third")
        result.putString("stream", "PASS: v2 database migration preserved history; playlist order/dedup/delete; service advanced twice without activity, skipping an unavailable video\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED
        result.putString("stream", "FAIL library: ${failure.javaClass.simpleName}: ${failure.message}\n")
    } finally {
        test.runOnMainSync { controller?.release() }
        context.stopService(Intent(context, PlaybackService::class.java))
        Thread.sleep(500)
        if (managerReplaced) managerField.set(PeerTubeAccounts, previousManager)
        preferences.update { oldOptions }
        PlaybackQueue.clear()
        runBlocking { playlist?.let { repository.delete(it) } }
        context.deleteDatabase(dbName)
        audio.delete()
    }
    test.finish(code, result)
}
