package com.arcticfoxapps.peeriche

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.*
import java.io.File

/** Decodes the encoded output, rather than checking a preview-only overlay. No camera or network. */
internal suspend fun broadcastLogoSmoke(context: Context) {
    for (portrait in listOf(false, true)) {
        val file = File(context.cacheDir, "logo-smoke-${System.nanoTime()}.mp4")
        val background = Bitmap.createBitmap(if (portrait) 480 else 640, if (portrait) 640 else 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            val halfWidth = if (portrait) 45f else 80f
            android.graphics.Canvas(this).drawOval(width / 2f - halfWidth, height / 2f - 45f, width / 2f + halfWidth, height / 2f + 45f,
                android.graphics.Paint().apply { color = Color.CYAN })
        }
        val next = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val logo = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).drawRect(0f, 0f, 50f, 100f, android.graphics.Paint().apply { color = Color.RED })
        }
        val ready = CompletableDeferred<Unit>()
        var stream: RtmpStream? = null
        val options = BroadcastOptions(portrait = portrait, logoEnabled = true)
        val width = if (portrait) 480 else 640; val height = if (portrait) 640 else 480
        try {
            withContext(Dispatchers.Main) {
                stream = RtmpStream(context, object : ConnectChecker {
                    override fun onConnectionStarted(url: String) {}
                    override fun onConnectionSuccess() {}
                    override fun onConnectionFailed(reason: String) {}
                    override fun onDisconnect() {}
                    override fun onAuthError() {}
                    override fun onAuthSuccess() {}
                }, BroadcastSlateSource(background), BroadcastSilenceSource()).apply {
                    check(prepareVideo(640, 480, 800_000, 24, 2, broadcastRotation(portrait)))
                    check(prepareAudio(44100, false, 96000)); setOrientation(0)
                    getGlInterface().setFilter(BroadcastCameraFit(3f / 4, width.toFloat() / height))
                    getGlInterface().addFilter(broadcastLogoFilter(logo, width, height, options))
                    startRecord(file.path) { if (it == com.pedro.library.base.recording.RecordController.Status.RECORDING) ready.complete(Unit) }
                }
            }
            withTimeout(15_000) { ready.await() }; delay(1500)
            withContext(Dispatchers.Main) { stream!!.changeVideoSource(BroadcastSlateSource(next)); stream!!.setOrientation(0) }
            delay(1500)
            withContext(Dispatchers.Main) {
                stream!!.getGlInterface().clearFilters()
                stream!!.getGlInterface().addFilter(BroadcastCameraFit(3f / 4, width.toFloat() / height))
            }
            delay(300)
            withContext(Dispatchers.Main) { stream!!.requestKeyframe() }
            delay(1200)
            withContext(Dispatchers.Main) { stream!!.stopRecord(); stream!!.release(); stream = null }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.path)
                val last = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000 - 200_000
                for ((time, enabled) in listOf(800_000L to true, 2_300_000L to true, last to false)) {
                    val frame = reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("No encoded frame")
                    try {
                        check(frame.width == width && frame.height == height)
                        val b = broadcastLogoBounds(width, height, 100, 100, options)
                        val pixel = frame.getPixel((b.left + b.width * .25f).toInt(), (b.top + b.height * .5f).toInt())
                        check((Color.red(pixel) > 170 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) == enabled) { "Logo pixels missing: portrait=$portrait time=$time pixel=$pixel" }
                        val transparent = frame.getPixel((b.left + b.width * .75f).toInt(), (b.top + b.height * .5f).toInt())
                        check(Color.red(transparent) < 80) { "PNG transparency lost" }
                        val center = frame.getPixel(width / 2, height / 2)
                        check(if (time < 1_500_000) Color.blue(center) > 170 else Color.green(center) > 170) { "Source hidden by overlay" }
                        if (time == 800_000L) {
                            var left = width; var right = 0; var top = height; var bottom = 0
                            for (y in 0 until height) for (x in 0 until width) {
                                val p = frame.getPixel(x, y)
                                if (Color.red(p) < 70 && Color.green(p) > 150 && Color.blue(p) > 150) {
                                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                                }
                            }
                            check(right > left && kotlin.math.abs((right-left) - (bottom-top)) <= 8) { "Camera aspect distorted: ${right-left} x ${bottom-top}" }
                        }
                    } finally { frame.recycle() }
                }
            } finally { reader.release() }
        } catch (failure: Throwable) {
            file.copyTo(File(context.cacheDir, "logo-smoke-failure.mp4"), overwrite = true)
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { stream?.release() }
            file.delete(); background.recycle(); next.recycle(); logo.recycle()
        }
    }
}
