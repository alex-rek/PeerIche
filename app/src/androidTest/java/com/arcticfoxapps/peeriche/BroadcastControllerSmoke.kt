package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Opt-in emulator test: only virtual cameras, generated slate, muted audio and a loopback receiver.
 * Uses an existing fake live UUID. All PeerTube API reads are in memory; there is no create-live request. */
internal suspend fun broadcastControllerSmoke(instrumentation: Instrumentation) {
    check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"))
    val context = instrumentation.targetContext
    val replacements = mutableListOf<Triple<Any, java.lang.reflect.Field, Any?>>()
    fun replace(target: Any, value: Any) {
        val field = target.javaClass.getDeclaredField("instance").apply { isAccessible = true }
        replacements += Triple(target, field, field.get(target)); field.set(target, value)
    }
    val session = AuthSession("https://broadcast-fixture.invalid", AuthClient("fixture", "fixture-secret"), "fixture-access", "fixture-refresh", Long.MAX_VALUE / 2, AuthProfile(1, "fixture", "Broadcast fixture"))
    val other = session.copy(server = "https://other-broadcast-fixture.invalid")
    val store = object : AccountSessionStore {
        var value = StoredAccounts(listOf(session, other), session.id)
        override suspend fun load() = value
        override suspend fun save(accounts: StoredAccounts) { value = accounts }
        override suspend fun clear() { value = StoredAccounts() }
    }
    val gson = Gson()
    fun json(value: String) = gson.fromJson(value, JsonObject::class.java)
    val api = object : AuthTransport {
        override suspend fun client(server: String) = session.client
        override suspend fun login(server: String, client: AuthClient, username: String, password: String, otp: String) = AuthTokens("fixture-access", "fixture-refresh", 3600)
        override suspend fun refresh(server: String, client: AuthClient, token: String) = AuthTokens("fixture-access", "fixture-refresh", 3600)
        override suspend fun me(server: String, token: String) = session.profile
        override suspend fun revoke(server: String, token: String) { }
        override suspend fun accountRequest(server: String, token: String, operation: AccountOperation): JsonObject {
            check(operation.method == "GET") // A test can never publish a PeerTube resource.
            return when (operation.segments.drop(2).joinToString("/")) {
                "users/me" -> json("""{"id":1,"videoQuota":-1,"videoQuotaDaily":-1,"videoChannels":[{"id":7,"name":"fixture","displayName":"Fixture channel"}]}""")
                "config" -> json("""{"live":{"enabled":true,"allowReplay":true}}""")
                "users/me/video-quota-used" -> json("""{"videoQuotaUsed":0,"videoQuotaUsedDaily":0}""")
                "users/me/subscriptions", "users/me/subscriptions/videos" -> json("""{"total":0,"data":[]}""")
                "videos/live/11111111-1111-4111-8111-111111111111" -> json("""{"rtmpUrl":"rtmp://127.0.0.1:19350/live","streamKey":"fixture-stream-key"}""")
                else -> throw AuthFailure("account_resource_missing", 404)
            }
        }
    }
    val accounts = AccountSessionManager(store, api); accounts.initialize()
    var activity: Activity? = null
    var coordinator: BroadcastCoordinator? = null
    val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val usedCamera = java.util.concurrent.atomic.AtomicBoolean(false)
    val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) { unavailable += cameraId; usedCamera.set(true) }
        override fun onCameraAvailable(cameraId: String) { unavailable -= cameraId }
    }
    try {
        withContext(Dispatchers.Main) {
            coordinator = BroadcastCoordinator(context, accounts)
            replace(PeerTubeAccounts, accounts); replace(AccountLibraries, AccountLibrary(accounts)); replace(Broadcasts, coordinator!!)
            cameras.registerAvailabilityCallback(availability, Handler(Looper.getMainLooper()))
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(700)
        val controller = coordinator!!
        withContext(Dispatchers.Main) {
            check(activity!!.hasWindowFocus() && controller.permissionGranted())
            controller.preview(accounts.state.value.active!!, BroadcastOptions(channelId = 7, portrait = false, resolution = 480, bitrate = 500_000, fps = 24, front = false, muted = true, cameraOff = true))
        }
        withTimeout(15000) { controller.state.first { it.phase in listOf("preview", "error") } }
        check(controller.state.value.phase == "preview")
        withContext(Dispatchers.Main) { controller.begin(CreatorMetadata("Loopback fixture", "Synthetic test", 7, 3), false, "11111111-1111-4111-8111-111111111111") }
        val connected = withTimeout(25000) { controller.state.first { it.phase in listOf("live", "error") } }
        check(connected.phase == "live")
        delay(800)
        withContext(Dispatchers.Main) { controller.hideCamera() }
        delay(700)
        withContext(Dispatchers.Main) { controller.camera() }
        delay(700)
        withContext(Dispatchers.Main) { controller.orientation(); controller.hideCamera() }
        delay(700)
        check(controller.state.value.options.cameraOff && controller.state.value.options.muted && usedCamera.get())
        withTimeout(5000) { while (unavailable.isNotEmpty()) delay(50) }
        accounts.activate(other.id)
        withTimeout(10000) { controller.state.first { !it.busy } }
        check(controller.state.value.error == "account_changed")
        withTimeout(5000) { while (unavailable.isNotEmpty()) delay(50) }
    } finally {
        withContext(NonCancellable + Dispatchers.Main) {
            coordinator?.close(); context.stopService(Intent(context, BroadcastService::class.java)); cameras.unregisterAvailabilityCallback(availability)
            BroadcastPreferences(context).forget(session.id); BroadcastPreferences(context).forget(other.id)
            activity?.finish()
            replacements.asReversed().forEach { (target, field, original) ->
                if (target === AccountLibraries) (field.get(target) as? AccountLibrary)?.close()
                field.set(target, original)
            }
        }
    }
}
