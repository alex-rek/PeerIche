package com.arcticfoxapps.peeriche

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class VideoDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transfer: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    private val notificationId = 730
    private fun notification(title: String, percent: Int = -1) = NotificationCompat.Builder(this, "video_downloads")
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title)
        .setContentText(getString(R.string.video_download_running)).setOngoing(true)
        .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        .addAction(0, getString(R.string.ui_cancel_28fb55),
            PendingIntent.getService(this, 1, Intent(this, VideoDownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            activeCall?.cancel(); transfer?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        val target = intent?.data ?: return START_NOT_STICKY
        if (transfer?.isActive == true) {
            scope.launch(Dispatchers.IO) {
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, target) }
                runCatching { contentResolver.releasePersistableUriPermission(target, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            android.widget.Toast.makeText(this, R.string.video_download_busy, android.widget.Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("video_downloads", getString(R.string.update_download), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, notificationId, notification(getString(R.string.update_download)),
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        transfer = scope.launch {
            var complete = false
            try {
                val server = intent.getStringExtra("server") ?: error("Missing server")
                val requested = Video(uuid = intent.getStringExtra("video").orEmpty(), host = java.net.URI(server).authority,
                    playbackAccountId = intent.getStringExtra("account"))
                val video = downloadVideoDetails(this@VideoDownloadService, requested, server)
                val option = videoDownloadOptions(video).first { it.quality == intent.getIntExtra("quality", -1) && it.extension == intent.getStringExtra("extension") }
                val accounts = PeerTubeAccounts.get(this@VideoDownloadService)
                val grant = video.playbackAccountId?.let { AccountVideoGrant(it, video, accounts) }
                val url = grant?.resolve(option.url) ?: option.url
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
                    val call = client.newCall(Request.Builder().url(url).build())
                    activeCall = call
                    try {
                        call.execute().use { response ->
                            check(response.isSuccessful)
                            val body = response.body ?: error("Empty response")
                            check(body.contentType()?.subtype?.contains("html") != true && body.contentType()?.subtype?.contains("json") != true)
                            val length = body.contentLength()
                            var count = 0L
                            var notifiedAt = 0L
                            contentResolver.openOutputStream(target, "w")!!.use { output ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        ensureActive()
                                        check(grant?.active() != false)
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        output.write(buffer, 0, read)
                                        count += read
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - notifiedAt >= 1000) {
                                            getSystemService(NotificationManager::class.java).notify(notificationId,
                                                notification(video.name, if (length > 0) (count * 100 / length).toInt().coerceIn(0, 100) else -1))
                                            notifiedAt = now
                                        }
                                    }
                                }
                            }
                            check(count > 0 && (length < 0 || count == length))
                        }
                    } finally { activeCall = null; call.cancel() }
                }
                complete = true
                getSystemService(NotificationManager::class.java).notify(notificationId + 1,
                    NotificationCompat.Builder(this@VideoDownloadService, "video_downloads")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(video.name)
                        .setContentText(getString(R.string.video_download_complete)).setAutoCancel(true).build())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isActive) android.widget.Toast.makeText(this@VideoDownloadService, R.string.video_download_failed, android.widget.Toast.LENGTH_LONG).show() }
            finally {
                if (!complete) withContext(NonCancellable + Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, target) } }
                androidx.core.app.ServiceCompat.stopForeground(this@VideoDownloadService, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
                runCatching { contentResolver.releasePersistableUriPermission(target, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { activeCall?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { activeCall?.cancel(); transfer?.cancel(); stopSelf() }
}
