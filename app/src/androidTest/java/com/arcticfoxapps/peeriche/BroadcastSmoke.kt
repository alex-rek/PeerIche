package com.arcticfoxapps.peeriche

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.*
import java.io.File

/** No camera or microphone capture: production sources encode only generated test images and zeros. */
internal suspend fun broadcastSmoke(context: Context, loopback: Boolean) {
    val file = File(context.cacheDir, "broadcast-smoke-${java.util.UUID.randomUUID()}.mp4")
    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    val second = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    val connected = CompletableDeferred<Unit>()
    val recording = CompletableDeferred<Unit>()
    val checker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) { }
        override fun onConnectionSuccess() { connected.complete(Unit) }
        override fun onConnectionFailed(reason: String) { connected.completeExceptionally(IllegalStateException("fixture connection")) }
        override fun onDisconnect() { }
        override fun onAuthError() { connected.completeExceptionally(IllegalStateException("fixture auth")) }
        override fun onAuthSuccess() { }
    }
    var stream: RtmpStream? = null
    try {
        withContext(Dispatchers.Main) {
            stream = RtmpStream(context, checker, BroadcastSlateSource(bitmap), BroadcastSilenceSource()).apply {
                getStreamClient().setLogs(false)
                check(prepareVideo(640, 480, 500_000, 24, 2, 0))
                check(prepareAudio(44100, false, 96000))
                setOrientation(0)
                startRecord(file.path) { status -> if (status == com.pedro.library.base.recording.RecordController.Status.RECORDING) recording.complete(Unit) }
            }
        }
        withTimeout(15000) { recording.await() }; delay(1200)
        withContext(Dispatchers.Main) { stream!!.changeVideoSource(BroadcastSlateSource(second)); stream!!.changeAudioSource(BroadcastSilenceSource()) }
        delay(1200)
        withContext(Dispatchers.Main) { stream!!.stopRecord(); stream!!.release(); stream = null }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            check(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" && it.getInteger(MediaFormat.KEY_WIDTH) == 640 && it.getInteger(MediaFormat.KEY_HEIGHT) == 480 })
            check(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
            for (index in 0 until extractor.trackCount) {
                extractor.selectTrack(index); extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                check(extractor.sampleTrackIndex == index && extractor.readSampleData(java.nio.ByteBuffer.allocate(1_048_576), 0) > 0) { "synthetic track=$index mime=${formats[index].getString(MediaFormat.KEY_MIME)} sampleTime=${extractor.sampleTime} bytes=${file.length()}" }
                extractor.unselectTrack(index)
            }
        } finally { extractor.release() }
        if (loopback) {
            check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"))
            withContext(Dispatchers.Main) {
                stream = RtmpStream(context, checker, BroadcastSlateSource(bitmap), BroadcastSilenceSource()).apply {
                    getStreamClient().setLogs(false); getStreamClient().setReTries(0)
                    check(prepareVideo(640, 480, 500_000, 24, 2, 0)); check(prepareAudio(44100, false, 96000))
                    setOrientation(0)
                    startStream("rtmp://127.0.0.1:19350/live/fixture-stream-key")
                }
            }
            withTimeout(20000) { connected.await() }; delay(1500)
            withContext(Dispatchers.Main) { stream!!.changeVideoSource(BroadcastSlateSource(second)); stream!!.changeAudioSource(BroadcastSilenceSource()); stream!!.setOrientation(180) }
            delay(1500)
            withContext(Dispatchers.Main) {
                check(stream!!.getStreamClient().getSentVideoFrames() > 0 && stream!!.getStreamClient().getSentAudioFrames() > 0)
                stream!!.stopStream(); stream!!.release(); stream = null
            }
        }
        File(context.cacheDir, "broadcast-synthetic-failure.mp4").delete()
    } catch (failure: Exception) {
        if (file.exists()) file.copyTo(File(context.cacheDir, "broadcast-synthetic-failure.mp4"), overwrite = true)
        throw failure
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { runCatching { stream?.release() } }
        file.delete(); bitmap.recycle(); second.recycle()
    }
}
