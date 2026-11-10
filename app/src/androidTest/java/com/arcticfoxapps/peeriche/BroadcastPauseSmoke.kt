package com.arcticfoxapps.peeriche

import android.content.Context
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/** Production coordinator with an active local encoder and simulated live state; no network or microphone. */
internal suspend fun broadcastPauseSmoke(context: Context) {
    val session = AuthSession("https://source-smoke.invalid", AuthClient("fake", "fake"), "fake", "fake", Long.MAX_VALUE/2, AuthProfile(31, "source_smoke", "Source smoke"))
    val store = object : AccountSessionStore {
        override suspend fun load() = StoredAccounts(listOf(session), session.id)
        override suspend fun save(accounts: StoredAccounts) {}
        override suspend fun clear() {}
    }
    val transport = object : AuthTransport {
        override suspend fun client(server: String): AuthClient = error("No network")
        override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String): AuthTokens = error("No network")
        override suspend fun refresh(server: String, client: AuthClient, token: String): AuthTokens = error("No network")
        override suspend fun me(server: String, token: String): AuthProfile = error("No network")
        override suspend fun revoke(server: String, token: String) {}
    }
    val manager = AccountSessionManager(store, transport); manager.initialize()
    val preferences = BroadcastPreferences(context)
    val options = BroadcastOptions(resolution = 480, portrait = false, front = false, muted = true)
    preferences.save(session.id, options)
    lateinit var controller: BroadcastCoordinator
    var created = false
    var engine: RtmpStream? = null
    val file = File(context.cacheDir, "source-switch-smoke.mp4")
    try {
        withContext(Dispatchers.Main) {
            controller = BroadcastCoordinator(context, manager)
            created = true
            controller.setFixedPortraitPreview(true)
            controller.preview(manager.state.value.active!!, options)
        }
        withTimeout(15000) { controller.state.first { it.phase in listOf("preview", "error") } }
        check(controller.state.value.phase == "preview")
        engine = controller.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(controller) as RtmpStream
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            engine!!.startRecord(file.path) { if (it == com.pedro.library.base.recording.RecordController.Status.RECORDING) ready.complete(Unit) }
        }
        withTimeout(15000) { ready.await() }; delay(600)
        @Suppress("UNCHECKED_CAST")
        val mutable = controller.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(controller) as MutableStateFlow<BroadcastState>
        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(phase = "live") }
        suspend fun settled() { withTimeout(10000) { controller.state.first { !it.switchingSource } }; check(controller.state.value.error == null) { "Source error: ${controller.state.value.error}" } }
        repeat(2) {
            withContext(Dispatchers.Main) { controller.hideCamera() }; settled()
            check(controller.state.value.options.cameraOff)
            withContext(Dispatchers.Main) { controller.hideCamera() }; settled()
            check(!controller.state.value.options.cameraOff)
            withContext(Dispatchers.Main) { check(engine!!.videoSource is Camera2Source && engine!!.videoSource.isRunning()) }
        }
        withContext(Dispatchers.Main) { controller.pauseBroadcast() }; settled()
        check(controller.state.value.paused && controller.state.value.options.cameraOff && controller.state.value.options.muted)
        check(!preferences.get(session.id).cameraOff)
        withContext(Dispatchers.Main) { check(engine!!.videoSource is BroadcastSlateSource && engine!!.audioSource is BroadcastSilenceSource) }
        withContext(Dispatchers.Main) { controller.pauseBroadcast() }; settled()
        check(!controller.state.value.paused && !controller.state.value.options.cameraOff && controller.state.value.options.muted)
        withContext(Dispatchers.Main) { check(engine!!.videoSource is Camera2Source && engine!!.videoSource.isRunning()); engine!!.stopRecord() }
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { if (created) controller.close() }
        preferences.forget(session.id); file.delete()
    }
}
