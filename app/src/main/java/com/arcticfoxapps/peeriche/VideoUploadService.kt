package com.arcticfoxapps.peeriche

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream

internal data class UploadFileInfo(val uri: String, val name: String, val size: Long, val mime: String)
internal suspend fun selectedUploadFile(context: Context, uri: Uri): UploadFileInfo = withContext(Dispatchers.IO) {
    if (uri.scheme != "content") throw AuthFailure("invalid_file")
    var name = ""; var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) { name = it.getString(0).orEmpty(); if (!it.isNull(1)) size = it.getLong(1) }
    }
    if (size < 0) context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
    if (size <= 0 || name.isBlank() || name.length > 255 || name.any { it == '/' || it == '\\' || it.isISOControl() }) throw AuthFailure("invalid_file")
    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    catch (_: SecurityException) { throw AuthFailure("file_unavailable") }
    UploadFileInfo(uri.toString(), name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
}
internal fun releaseUploadFile(context: Context, file: UploadFileInfo?) {
    file?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
}
internal data class UploadDraft(val id: String, val accountId: String, val file: UploadFileInfo, val metadata: CreatorMetadata,
    val cover: UploadFileInfo? = null, val location: String? = null, val offset: Long = 0, val chunkSize: Int = 1_048_576,
    val status: String = "paused", val error: String? = null, val videoUuid: String? = null) {
    val active: Boolean get() = status in listOf("starting", "uploading", "cover", "cancelling")
    override fun toString() = "UploadDraft(id=$id, status=$status, details=redacted)"
}
internal class VideoUploadCoordinator(private val context: Context, private val accounts: AccountSessionManager, storageName: String = "video-upload.json") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val disk = AtomicFile(File(context.noBackupFilesDir, storageName.also { require(it.matches(Regex("[a-zA-Z0-9_.-]+"))) }))
    private val gson = Gson()
    private val mutable = MutableStateFlow<UploadDraft?>(null)
    val state = mutable.asStateFlow()
    private val completed = MutableStateFlow(0L)
    val completionRevision = completed.asStateFlow()
    private var loaded = false
    private var job: Job? = null
    internal suspend fun close() { job?.cancelAndJoin(); scope.cancel() }
    suspend fun initialize() = lock.withLock {
        if (loaded) return@withLock
        val draft = if (disk.baseFile.exists() || File(disk.baseFile.path + ".bak").exists()) {
            try { disk.openRead().use { stream ->
                if (stream.channel.size() > 65536) throw AuthFailure("upload_storage")
                val bytes = stream.readBytes()
                gson.fromJson(bytes.toString(Charsets.UTF_8), UploadDraft::class.java).also {
                    it.metadata.validate(); require(it.file.size > 0 && it.offset in 0..it.file.size && it.chunkSize in 1..32*1024*1024)
                    require(Uri.parse(it.file.uri).scheme == "content")
                    it.location?.let { location -> safeUploadUrl(it.accountId.substringBefore('|'), location) }
                }
            } } catch (_: Exception) { throw AuthFailure("upload_storage") }
        } else null
        if (draft?.status == "done") {
            save(null); releaseFiles(draft); completed.value++
        } else mutable.value = draft?.let { if (it.active) it.copy(status = "paused", error = "upload_interrupted") else it }
        loaded = true
    }
    private suspend fun save(draft: UploadDraft?) = withContext(NonCancellable + Dispatchers.IO) {
        if (draft == null) disk.delete() else {
            val stream = try { disk.startWrite() } catch (_: Exception) { throw AuthFailure("upload_storage") }
            try { stream.write(gson.toJson(draft).toByteArray()); disk.finishWrite(stream) }
            catch (error: Exception) { disk.failWrite(stream); throw AuthFailure("upload_storage") }
        }
        mutable.value = draft
    }
    private suspend fun update(id: String, change: (UploadDraft) -> UploadDraft) = lock.withLock {
        mutable.value?.takeIf { it.id == id }?.let { save(change(it)) }
    }
    private fun releaseFiles(draft: UploadDraft) {
        listOfNotNull(draft.file, draft.cover).forEach { releaseUploadFile(context, it) }
    }
    private suspend fun complete(id: String) = withContext(NonCancellable) { lock.withLock {
        mutable.value?.takeIf { it.id == id }?.let { draft ->
            // Persist completion first so a process interruption can safely clean it on startup.
            save(draft.copy(status = "done", error = null))
            save(null); releaseFiles(draft); completed.value++
        }
    } }
    suspend fun prepare(account: String, file: UploadFileInfo, metadata: CreatorMetadata, cover: UploadFileInfo?) {
        initialize()
        val capabilities = CreatorRepository(accounts).capabilities(account)
        metadata.validate(); capabilities.validateFile(file.name, file.size)
        if (capabilities.channels.none { it.id == metadata.channelId }) throw AuthFailure("invalid_channel")
        if (cover != null && (cover.size > minOf(capabilities.coverMax, 10L * 1024 * 1024) || cover.mime !in listOf("image/jpeg", "image/png", "image/webp") ||
            (capabilities.coverExtensions.isNotEmpty() && capabilities.coverExtensions.none { cover.name.endsWith(it, true) }))) throw AuthFailure("invalid_cover")
        lock.withLock {
            if (mutable.value != null) throw AuthFailure("upload_exists")
            save(UploadDraft(java.util.UUID.randomUUID().toString(), account, file, metadata, cover, chunkSize = capabilities.chunkSize(file.size)))
        }
    }
    private fun startService(action: String) {
        try { ContextCompat.startForegroundService(context, Intent(context, VideoUploadService::class.java).setAction(action)) }
        catch (_: Exception) { throw AuthFailure("upload_service") }
    }
    suspend fun resume() {
        try { startService("resume") }
        catch (failure: Exception) {
            state.value?.let { draft -> update(draft.id) { it.copy(status = "failed", error = "upload_service") } }
            throw failure
        }
    }
    fun cancel() = startService("cancel")
    fun pause() { job?.cancel() }
    suspend fun discard() {
        job?.cancelAndJoin()
        lock.withLock {
            val previous = mutable.value
            save(null)
            listOfNotNull(previous?.file, previous?.cover).forEach { file -> runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(file.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } }
        }
    }
    /** Starts once from the foreground service; background process restart never publishes automatically. */
    suspend fun run(cancel: Boolean = false) {
        initialize()
        lock.withLock {
            if (job?.isActive == true) return
            val draft = mutable.value ?: return
            if (draft.status == "done" && !cancel) return
            save(draft.copy(status = if (cancel) "cancelling" else "starting", error = null))
            job = scope.launch { transfer(draft.id, cancel) }
        }
    }
    private suspend fun transfer(id: String, cancel: Boolean) {
        try {
            accounts.initialize()
            var draft = state.value?.takeIf { it.id == id } ?: return
            val owner = accounts.state.value.active?.takeIf { it.id == draft.accountId && !it.needsLogin } ?: throw AuthFailure("account_changed")
            if (cancel) {
                if (draft.videoUuid != null) throw AuthFailure("upload_already_complete")
                draft.location?.let { location ->
                    try { accounts.upload(owner.id, UploadOperation.cancel(location)) }
                    catch (error: AuthFailure) { if (error.status != 404) throw error }
                }
                update(id) { it.copy(status = "cancelled", error = null) }; return
            }
            if (draft.videoUuid == null) {
                if (draft.location == null) {
                    val caps = CreatorRepository(accounts).capabilities(owner.id)
                    caps.validateFile(draft.file.name, draft.file.size)
                    if (caps.channels.none { it.id == draft.metadata.channelId }) throw AuthFailure("invalid_channel")
                    val reply = accounts.upload(owner.id, UploadOperation.init(draft.file.size, draft.file.mime, draft.file.name, draft.metadata))
                    update(id) { it.copy(location = reply.location, status = "uploading") }
                }
                draft = state.value ?: return
                val location = safeUploadUrl(owner.server, draft.location ?: throw AuthFailure("invalid_upload_location"))
                val probe = accounts.upload(owner.id, UploadOperation.probe(location, draft.file.size))
                update(id) { it.copy(offset = probe.offset, videoUuid = probe.videoUuid, status = "uploading") }
                if (probe.videoUuid == null) {
                    context.contentResolver.openInputStream(Uri.parse(draft.file.uri))?.use { input ->
                        skipUploadBytes(input, probe.offset)
                        var offset = probe.offset
                        while (offset < draft.file.size) {
                            currentCoroutineContext().ensureActive()
                            val bytes = ByteArray(minOf(draft.chunkSize.toLong(), draft.file.size - offset).toInt())
                            var read = 0
                            while (read < bytes.size) { currentCoroutineContext().ensureActive(); val count = input.read(bytes, read, bytes.size - read); if (count <= 0) throw AuthFailure("file_changed"); read += count }
                            val reply = accounts.upload(owner.id, UploadOperation.chunk(location, draft.file.size, offset, bytes))
                            if (reply.code == 308 && reply.offset != offset + bytes.size) throw AuthFailure("upload_range")
                            offset = reply.offset
                            update(id) { it.copy(offset = offset, videoUuid = reply.videoUuid ?: it.videoUuid) }
                        }
                    } ?: throw AuthFailure("file_unavailable")
                }
            }
            draft = state.value?.takeIf { it.id == id } ?: return
            val uuid = draft.videoUuid ?: throw AuthFailure("upload_processing")
            draft.cover?.let { cover ->
                update(id) { it.copy(status = "cover") }
                val bytes = context.contentResolver.openInputStream(Uri.parse(cover.uri))?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; if (out.size() + count > cover.size || out.size() + count > 10*1024*1024) throw AuthFailure("invalid_cover"); out.write(buffer, 0, count) }
                    out.toByteArray()
                } ?: throw AuthFailure("file_unavailable")
                accounts.upload(owner.id, UploadOperation.cover(uuid, bytes, cover.mime))
            }
            complete(id)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { update(id) { if (it.active) it.copy(status = "paused", error = null) else it } } }
        } catch (error: Exception) {
            val reason = if (error is SecurityException || error is java.io.FileNotFoundException) "file_unavailable" else accountErrorCode(error)
            runCatching { update(id) { it.copy(status = "failed", error = reason) } }
        } finally {
            // Even an unavailable disk must not leave the service waiting forever.
            withContext(NonCancellable) { lock.withLock {
                mutable.value?.takeIf { it.id == id && it.active }?.let { mutable.value = it.copy(status = "failed", error = "upload_storage") }
            } }
        }
    }
}
internal suspend fun skipUploadBytes(input: InputStream, bytes: Long) {
    var left = bytes
    while (left > 0) { currentCoroutineContext().ensureActive(); val skipped = input.skip(left); if (skipped > 0) left -= skipped else { if (input.read() < 0) throw AuthFailure("file_changed"); left-- } }
}
internal object VideoUploads {
    @Volatile private var instance: VideoUploadCoordinator? = null
    fun get(context: Context) = instance ?: synchronized(this) {
        instance ?: VideoUploadCoordinator(context.applicationContext, PeerTubeAccounts.get(context)).also { instance = it }
    }
}

class VideoUploadService : Service() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(localizedAppContext(newBase)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var uploads: VideoUploadCoordinator
    private var monitor: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private fun finishService() { androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onCreate() {
        super.onCreate(); uploads = VideoUploads.get(this)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("uploads", getString(R.string.notification_upload), NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(draft: UploadDraft?): Notification {
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, VideoUploadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = draft?.let { (100.0 * it.offset / it.file.size).toInt().coerceIn(0, 100) } ?: 0
        return NotificationCompat.Builder(this, "uploads").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(getString(R.string.notification_upload))
            .setContentText(draft?.metadata?.name.orEmpty()).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent, draft?.status in listOf("starting", "cover", "cancelling")).addAction(0, getString(R.string.pause_playback), pause).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(702, notification(uploads.state.value))
        if (wakeLock?.isHeld != true) wakeLock = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PeerIche:VideoUpload").apply { acquire(6L * 60 * 60 * 1000) }
        if (intent?.action == "pause") { uploads.pause(); finishService(); return START_NOT_STICKY }
        scope.launch {
            try {
                uploads.run(cancel = intent?.action == "cancel")
                monitor?.cancel()
                monitor = scope.launch { uploads.state.collect { draft ->
                    if (draft?.active == true) getSystemService(NotificationManager::class.java).notify(702, notification(draft))
                    else finishService()
                } }
            } catch (_: Exception) { finishService() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { uploads.pause(); finishService() }
    override fun onDestroy() { uploads.pause(); scope.cancel(); wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
