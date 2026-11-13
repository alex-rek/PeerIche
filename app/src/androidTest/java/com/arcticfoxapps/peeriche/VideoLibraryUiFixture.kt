package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/** Uses only public videos and temporary local playlists on the task emulator. */
internal fun videoLibraryUiFixture(test: Instrumentation) {
    val context = test.targetContext
    val marker = File(context.cacheDir, "library-ui-finish").apply { delete() }
    val keys = listOf(stringPreferencesKey("servers"), stringPreferencesKey("activeServer"))
    val previous = runBlocking { context.peerIcheDataStore.data.first() }
    val options = AppPreferences.get(context).options.value
    var activity: Activity? = null
    var playlist: String? = null
    val result = Bundle(); var code = Activity.RESULT_OK
    try {
        check(android.os.Build.HARDWARE.contains("ranchu"))
        runBlocking {
            val origin = "https://0ch.tv"
            context.peerIcheDataStore.edit { it[keys[0]] = origin; it[keys[1]] = origin }
            AppPreferences.get(context).update { it.copy(homeContent = "recommended", homeSort = "newest", translateMetadata = false) }
            val repository = VideoPlaylists(context)
            playlist = repository.create("QA continuous playlist")
            PeerTubeClients.serverPage(origin, 0, false).videos.take(3).forEach { repository.add(playlist!!, it, origin) }
            val restored = PeerIcheDatabase.get(context).library().getAll(playlistKind(playlist!!)).minBy { it.updatedAt }.toVideo()
            val detail = PeerTubeClients.video(restored.sourceServer(origin), restored.apiId())
            check(detail.playableUrl() != null) { "Restored playlist item has no playable detail" }
        }
        activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runBlocking { repeat(360) { if (marker.exists()) return@runBlocking; delay(1000) } }
        result.putString("stream", "Library UI finished; temporary playlist and settings restored\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL library UI: ${failure.message}\n")
    } finally {
        test.runOnMainSync { activity?.finish() }
        context.stopService(Intent(context, PlaybackService::class.java))
        PlaybackQueue.clear()
        runBlocking {
            playlist?.let { VideoPlaylists(context).delete(it) }
            context.peerIcheDataStore.edit { settings -> keys.forEach { key -> previous[key]?.let { settings[key] = it } ?: settings.remove(key) } }
        }
        AppPreferences.get(context).update { options }
        marker.delete()
    }
    test.finish(code, result)
}
