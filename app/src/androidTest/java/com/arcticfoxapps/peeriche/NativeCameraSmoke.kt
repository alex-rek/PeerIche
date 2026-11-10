package com.arcticfoxapps.peeriche

import android.app.Instrumentation
import android.content.Intent
import android.media.MediaMetadataRetriever
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtmp.RtmpStream
import com.pedro.common.ConnectChecker
import kotlinx.coroutines.*
import java.io.File

/** Emulator virtual camera only; records locally without a stream endpoint or microphone. */
internal suspend fun nativeCameraSmoke(test: Instrumentation) {
    check(android.os.Build.HARDWARE.contains("ranchu"))
    val context = test.targetContext
    val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    try {
        withContext(Dispatchers.Main) { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        delay(1500)
        for (portrait in listOf(true, false)) {
            var stream: RtmpStream? = null
            val file = File(context.cacheDir, "native-camera-smoke.mp4")
            val ready = CompletableDeferred<Unit>()
            try {
                withContext(Dispatchers.Main) {
                    stream = RtmpStream(context, object : ConnectChecker {
                        override fun onConnectionStarted(url: String) {}
                        override fun onConnectionSuccess() {}
                        override fun onConnectionFailed(reason: String) {}
                        override fun onDisconnect() {}
                        override fun onAuthError() {}
                        override fun onAuthSuccess() {}
                    }, Camera2Source(context), BroadcastSilenceSource()).apply {
                        check(prepareVideo(640, 480, 800_000, 24, 2, broadcastRotation(portrait)))
                        check(prepareAudio(44100, false, 96000))
                        val transform = broadcastPreviewTransform(portrait, true, 90)
                        setOrientation(transform.first); getGlInterface().setPreviewRotation(transform.second)
                        getGlInterface().setAspectRatioMode(com.pedro.encoder.utils.gl.AspectRatioMode.NONE)
                        startRecord(file.path) { if (it == com.pedro.library.base.recording.RecordController.Status.RECORDING) ready.complete(Unit) }
                    }
                }
                withTimeout(15000) { ready.await() }; delay(2000)
                withContext(Dispatchers.Main) { stream!!.stopRecord(); stream!!.release(); stream = null }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    val frame = reader.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("No camera frame")
                    try {
                        check(frame.width == if (portrait) 480 else 640)
                        check(frame.height == if (portrait) 640 else 480)
                        var left = frame.width; var right = 0; var top = frame.height; var bottom = 0
                        for (y in 0 until frame.height step 4) for (x in 0 until frame.width step 4) {
                            val color = frame.getPixel(x,y)
                            if (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color) > 60) {
                                left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
                            }
                        }
                        check(right-left > frame.width*.8 && bottom-top > frame.height*.8) { "Camera was fitted twice" }
                        File(context.cacheDir, "native-camera-${if (portrait) "portrait" else "landscape"}.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    } finally { frame.recycle() }
                } finally { reader.release() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { stream?.release() }
                file.delete()
            }
        }
        broadcastPauseSmoke(context)
    } finally { withContext(NonCancellable + Dispatchers.Main) { activity.finish() } }
}
