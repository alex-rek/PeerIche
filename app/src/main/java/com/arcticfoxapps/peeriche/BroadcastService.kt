package com.arcticfoxapps.peeriche

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.*
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BroadcastCoordinator(private val context: Context, private val accounts: AccountSessionManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(BroadcastState())
    val state = mutable.asStateFlow()
    private val preferences = BroadcastPreferences(context)
    private var engine: RtmpStream? = null
    private var slate: Bitmap? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0; private var surfaceHeight = 0
    private var generation = 0
    private var preparation: Job? = null
    private var publication: Job? = null
    private var timeout: Job? = null
    private var logoUpdate: Job? = null
    private var owner: AccountSummary? = null
    init { scope.launch { accounts.state.collect { current ->
        val activeOwner = mutable.value.accountId
        if (current.ready && activeOwner != null && (current.activeId != activeOwner || current.active?.needsLogin != false)) stop("account_changed")
    } } }
    fun permissionGranted() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun preview(account: AccountSummary, options: BroadcastOptions = preferences.get(account.id)) {
        if (state.value.busy) return
        if (accounts.state.value.activeId != account.id || accounts.state.value.active?.needsLogin != false) { stop("account_changed"); return }
        if (!permissionGranted()) { mutable.value = BroadcastState(accountId = account.id, options = options, error = "broadcast_permissions"); return }
        preparation?.cancel(); releaseEngine(); val token = ++generation
        owner = account; mutable.value = BroadcastState(phase = "preparing", accountId = account.id, options = options.checked())
        preparation = scope.launch {
            try {
                val image = preferences.slate(account)
                if (token != generation) return@launch
                slate = image
                val camera = Camera2Source(context).apply { if (options.front) switchCamera() }
                val desiredWidth = if (options.resolution == 480) 640 else if (options.resolution == 1080) 1920 else 1280
                val sizes = camera.getCameraResolutions(camera.getCameraFacing())
                val size = sizes.filter { it.width >= it.height && it.width % 2 == 0 && it.height % 2 == 0 }
                    .minByOrNull { kotlin.math.abs(it.width - desiredWidth) + kotlin.math.abs(it.height - options.resolution) * 2 }
                    ?: throw AuthFailure("broadcast_camera")
                val stream = RtmpStream(context, checker(token), if (options.cameraOff) BroadcastSlateSource(image) else camera, BroadcastSilenceSource())
                engine = stream
                stream.getStreamClient().setLogs(false)
                stream.getStreamClient().setReTries(0)
                stream.setEncoderErrorCallback(object : com.pedro.encoder.EncoderErrorCallback {
                    override fun onCodecError(type: com.pedro.encoder.utils.CodecUtil.CodecTypeError, e: android.media.MediaCodec.CodecException) {
                        scope.launch { if (token == generation) stop("broadcast_encoder") }
                    }
                    override fun onEncodeError(type: com.pedro.encoder.utils.CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
                        scope.launch { if (token == generation) stop("broadcast_encoder") }; return false
                    }
                })
                if (!stream.prepareVideo(size.width, size.height, options.bitrate, options.fps, 2, broadcastRotation(options.portrait)) ||
                    !stream.prepareAudio(44100, false, 96000)) throw AuthFailure("broadcast_encoder")
                if (options.cameraOff) stream.setOrientation(0)
                mutable.value = state.value.copy(phase = "preview", actualWidth = if (options.portrait) size.height else size.width, actualHeight = if (options.portrait) size.width else size.height)
                attachAvailableSurface()
                refreshLogo(account)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == generation) stop(if (failure is AuthFailure) failure.reason else "broadcast_camera") }
        }
    }
    private fun checker(token: Int) = object : ConnectChecker {
        private fun update(block: () -> Unit) { scope.launch { if (token == generation) block() } }
        override fun onConnectionStarted(url: String) { /* Do not retain or log the endpoint/key. */ }
        override fun onConnectionSuccess() = update { timeout?.cancel(); mutable.value = state.value.copy(phase = "live", startedAt = SystemClock.elapsedRealtime(), error = null) }
        override fun onConnectionFailed(reason: String) = update { stop("broadcast_connection") }
        override fun onDisconnect() = update { if (state.value.busy) stop("broadcast_disconnected") }
        override fun onAuthError() = update { stop("broadcast_auth") }
        override fun onAuthSuccess() { }
        override fun onNewBitrate(bitrate: Long) = update { mutable.value = state.value.copy(bitrate = bitrate) }
    }
    fun attach(output: Surface, width: Int, height: Int) {
        if ((surface !== output || surfaceWidth != width || surfaceHeight != height) && engine?.isOnPreview == true) runCatching { engine?.stopPreview() }
        surface = output; surfaceWidth = width; surfaceHeight = height; attachAvailableSurface()
    }
    private fun attachAvailableSurface() {
        val output = surface?.takeIf { it.isValid } ?: return
        val stream = engine ?: return
        if (stream.videoSource is Camera2Source) {
            val rotation = com.pedro.encoder.input.video.CameraHelper.getCameraOrientation(context)
            stream.setOrientation(if (rotation == 0) 270 else rotation - 90)
        }
        if (!stream.isOnPreview && state.value.phase in listOf("preview", "creating", "connecting", "live") && surfaceWidth > 0 && surfaceHeight > 0) {
            try { stream.startPreview(output, surfaceWidth, surfaceHeight) }
            catch (_: Exception) { stop("broadcast_camera") }
            owner?.let { refreshLogo(it) }
        }
    }
    fun detach() {
        surface = null
        runCatching { if (engine?.isOnPreview == true) engine?.stopPreview() }
        if (!state.value.busy) { preparation?.cancel(); generation++; releaseEngine(); mutable.value = state.value.copy(phase = "idle") }
    }
    fun camera() {
        val stream = engine
        if (stream == null) {
            owner?.let { account -> val options = state.value.options.copy(front = !state.value.options.front); saveOptions(options); preview(account, options) }
            return
        }
        if (state.value.options.cameraOff) return
        try {
            (stream.videoSource as? Camera2Source)?.switchCamera()
            val options = state.value.options.copy(front = !state.value.options.front)
            mutable.value = state.value.copy(options = options, torch = false); saveOptions(options)
        } catch (_: Exception) { mutable.value = state.value.copy(error = "broadcast_camera_switch") }
    }
    fun mute() {
        val stream = engine ?: return
        try {
            val muted = !state.value.options.muted
            // Before stream starts no microphone is opened, regardless of the selection.
            if (state.value.busy) stream.changeAudioSource(if (muted) BroadcastSilenceSource() else MicrophoneSource())
            val options = state.value.options.copy(muted = muted)
            mutable.value = state.value.copy(options = options); saveOptions(options)
        } catch (_: Exception) { stop("broadcast_microphone") }
    }
    fun hideCamera() {
        val stream = engine ?: return
        try {
            val hidden = !state.value.options.cameraOff
            stream.changeVideoSource(if (hidden) BroadcastSlateSource(slate ?: return) else Camera2Source(context).apply { if (state.value.options.front) switchCamera() })
            if (hidden) stream.setOrientation(0)
            // Camera2Source's default orientation follows the display, independently
            // of the chosen encoded aspect ratio. changeVideoSource applies it.
            val options = state.value.options.copy(cameraOff = hidden)
            mutable.value = state.value.copy(options = options, torch = false); saveOptions(options); stream.requestKeyframe()
            owner?.let { refreshLogo(it) }
        } catch (_: Exception) { stop("broadcast_camera") }
    }
    fun orientation() {
        val account = owner ?: return
        val options = state.value.options.copy(portrait = !state.value.options.portrait)
        if (state.value.busy) {
            mutable.value = state.value.copy(error = "broadcast_fixed_frame")
            return
        }
        saveOptions(options); preview(account, options)
    }
    fun torch() {
        try {
            val camera = engine?.videoSource as? Camera2Source ?: return
            if (camera.isLanternEnabled()) camera.disableLantern() else camera.enableLantern()
            mutable.value = state.value.copy(torch = camera.isLanternEnabled())
        } catch (_: Exception) { mutable.value = state.value.copy(error = "broadcast_torch") }
    }
    fun refreshSlate(account: AccountSummary) {
        val token = generation
        scope.launch {
            try {
                val image = preferences.slate(account)
                if (token != generation) return@launch
                slate = image
                if (state.value.options.cameraOff) engine?.changeVideoSource(BroadcastSlateSource(image))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) mutable.value = state.value.copy(error = "invalid_cover") }
        }
    }
    fun refreshLogo(account: AccountSummary) {
        if (owner?.id != account.id || accounts.state.value.activeId != account.id) return
        logoUpdate?.cancel()
        val token = generation
        val stream = engine ?: return
        val current = preferences.get(account.id)
        val options = state.value.options.copy(logoEnabled = current.logoEnabled, logoCorner = current.logoCorner,
            logoSize = current.logoSize, logoOpacity = current.logoOpacity, logoMargin = current.logoMargin)
        val width = state.value.actualWidth; val height = state.value.actualHeight
        mutable.value = state.value.copy(options = options)
        logoUpdate = scope.launch {
            try {
                val filter = withContext(Dispatchers.IO) {
                    val image = if (options.logoEnabled) preferences.logo(account.id) else null
                    try { image?.let { broadcastLogoFilter(it, width, height, options) } }
                    finally { image?.recycle() }
                }
                if (token != generation || engine !== stream) return@launch
                val gl = stream.getGlInterface()
                gl.clearFilters()
                if (stream.videoSource is Camera2Source) {
                    val portraitDisplay = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val short = minOf(width, height).toFloat(); val long = maxOf(width, height).toFloat()
                    val sourceAspect = if (portraitDisplay) short / long else long / short
                    gl.addFilter(BroadcastCameraFit(sourceAspect, width.toFloat() / height))
                }
                if (filter != null) gl.addFilter(filter)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) mutable.value = state.value.copy(error = "invalid_cover") }
        }
    }
    private fun saveOptions(options: BroadcastOptions) { owner?.let { preferences.save(it.id, options) } }
    fun begin(metadata: CreatorMetadata, replay: Boolean, existingUuid: String?) {
        if (state.value.phase != "preview" || engine == null || !permissionGranted()) return
        val account = owner ?: return
        try { metadata.validate() }
        catch (failure: AuthFailure) { mutable.value = state.value.copy(error = failure.reason); return }
        mutable.value = state.value.copy(phase = "creating", error = null, title = metadata.name, privacy = metadata.privacy, uuid = existingUuid)
        pending = PendingBroadcast(account.id, metadata, replay, existingUuid)
        try { ContextCompat.startForegroundService(context, Intent(context, BroadcastService::class.java).setAction("start")) }
        catch (_: Exception) { stop("broadcast_service") }
    }
    private class PendingBroadcast(val account: String, val metadata: CreatorMetadata, val replay: Boolean, val uuid: String?)
    private var pending: PendingBroadcast? = null
    fun serviceReady() {
        val request = pending ?: return
        pending = null; val token = generation
        publication = scope.launch {
            try {
                val repository = CreatorRepository(accounts)
                val id = request.uuid ?: repository.createLive(request.account, request.metadata, request.replay, false)
                if (generation != token) return@launch
                // Preserve the created UUID before querying connection details: never recreate on a connection retry.
                mutable.value = state.value.copy(uuid = id, watchUrl = "${owner!!.server}/videos/watch/$id")
                val target = BroadcastTarget.from(repository.liveCredentials(request.account, id))
                if (generation != token || accounts.state.value.activeId != request.account) return@launch
                val stream = engine ?: throw AuthFailure("broadcast_encoder")
                if (java.net.URI(target.endpoint).scheme == "rtmps") stream.getStreamClient().addCertificates(broadcastTlsTrust(java.net.URI(target.endpoint).host))
                stream.changeAudioSource(if (state.value.options.muted) BroadcastSilenceSource() else MicrophoneSource())
                mutable.value = state.value.copy(phase = "connecting", watchUrl = target.watchUrl)
                stream.startStream(target.endpoint)
                timeout = scope.launch { delay(25000); if (generation == token && state.value.phase == "connecting") stop("broadcast_connection") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == token) stop(if (state.value.uuid == null && (failure !is AuthFailure || failure.status >= 500)) "live_create_unknown" else accountErrorCode(failure))
            }
        }
    }
    fun stop(reason: String? = null) {
        generation++; preparation?.cancel(); publication?.cancel(); timeout?.cancel(); pending = null
        releaseEngine()
        mutable.value = state.value.copy(phase = if (reason == null) "stopped" else "error", error = reason, bitrate = 0, torch = false)
    }
    private fun releaseEngine() { logoUpdate?.cancel(); val previous = engine; engine = null; runCatching { previous?.release() }; slate = null }
    internal fun close() { stop(); scope.cancel() }
}
internal object Broadcasts {
    @Volatile private var instance: BroadcastCoordinator? = null
    fun get(context: Context) = instance ?: synchronized(this) {
        instance ?: BroadcastCoordinator(context.applicationContext, PeerTubeAccounts.get(context)).also { instance = it }
    }
}

class BroadcastService : Service() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(localizedAppContext(newBase)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: BroadcastCoordinator
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate(); coordinator = Broadcasts.get(this)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("broadcast", getString(R.string.notification_broadcast), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { coordinator.stop(); finishService(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 901, Intent(this, BroadcastService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 902, Intent(this, MainActivity::class.java).setAction("peeriche.broadcast").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "broadcast").setSmallIcon(android.R.drawable.presence_video_busy).setContentTitle(getString(R.string.notification_broadcast))
            .setContentText(coordinator.state.value.title).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.notification_end), stop).build()
        try {
            val types = if (Build.VERSION.SDK_INT >= 30) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, 903, notification, types)
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PeerIche:Broadcast").apply { acquire(4L * 60 * 60 * 1000) }
            coordinator.serviceReady()
            scope.launch { coordinator.state.collect { if (!it.busy) finishService() } }
        } catch (_: Exception) { coordinator.stop("broadcast_service"); finishService() }
        return START_NOT_STICKY
    }
    private fun finishService() { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { coordinator.stop(); finishService() }
    override fun onDestroy() {
        if (coordinator.state.value.busy) coordinator.stop("broadcast_interrupted")
        scope.cancel(); wakeLock?.takeIf { it.isHeld }?.release(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
