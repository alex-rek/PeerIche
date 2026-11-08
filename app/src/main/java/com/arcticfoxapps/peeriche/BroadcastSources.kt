// Bitmap-to-SurfaceTexture integration informed by RootEncoder BitmapSource,
// Copyright (C) 2024 pedroSG94, Apache-2.0. See assets/licenses/RootEncoder-Apache-2.0.txt.
package com.arcticfoxapps.peeriche

import android.graphics.*
import android.view.Surface
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import kotlinx.coroutines.*

/** Replaces capture rather than continuing to read a muted microphone. */
internal class BroadcastSilenceSource : AudioSource() {
    private var job: Job? = null
    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean) = sampleRate > 0
    override fun start(getMicrophoneData: GetMicrophoneData) {
        stop()
        job = CoroutineScope(Dispatchers.Default).launch {
            val bytes = ByteArray(1024 * 2 * if (isStereo) 2 else 1)
            var frames = 0L
            val origin = System.nanoTime()
            while (isActive) {
                getMicrophoneData.inputPCMData(Frame(bytes, 0, bytes.size, System.nanoTime() / 1000))
                frames += 1024
                val remaining = origin + frames * 1_000_000_000 / sampleRate - System.nanoTime()
                if (remaining > 0) delay((remaining / 1_000_000).coerceAtLeast(1))
            }
        }
    }
    override fun stop() { job?.cancel(); job = null }
    override fun isRunning() = job?.isActive == true
    override fun release() = stop()
}

/** Bitmap-to-SurfaceTexture source; camera is stopped/released by StreamBase on source switch.
 * Uses RootEncoder's documented VideoSource contract (Apache-2.0), with aspect-preserving drawing and reusable lifecycle. */
internal class BroadcastSlateSource(private val bitmap: Bitmap) : VideoSource() {
    private var job: Job? = null
    private var surface: Surface? = null
    override fun create(width: Int, height: Int, fps: Int, rotation: Int) = width > 0 && height > 0 && fps > 0
    override fun start(surfaceTexture: SurfaceTexture) {
        stop(); surfaceTexture.setDefaultBufferSize(width, height)
        val output = Surface(surfaceTexture); surface = output
        // Unlike a camera buffer, a Canvas producer has no sensor rotation transform.
        // Draw in encoded-frame coordinates, then map to the input texture to avoid portrait stretching.
        val portrait = rotation == 90 || rotation == 270
        val logicalWidth = if (portrait) height else width
        val logicalHeight = if (portrait) width else height
        val scale = minOf(logicalWidth.toFloat() / bitmap.width, logicalHeight.toFloat() / bitmap.height)
        val target = RectF((logicalWidth - bitmap.width * scale) / 2, (logicalHeight - bitmap.height * scale) / 2,
            (logicalWidth + bitmap.width * scale) / 2, (logicalHeight + bitmap.height * scale) / 2)
        job = CoroutineScope(Dispatchers.Default).launch {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            while (isActive) {
                try {
                    val canvas = output.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.BLACK)
                        canvas.save(); canvas.scale(width.toFloat() / logicalWidth, height.toFloat() / logicalHeight)
                        canvas.drawBitmap(bitmap, null, target, paint); canvas.restore()
                    }
                    finally { output.unlockCanvasAndPost(canvas) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (!isActive) break }
                delay((1000L / fps.coerceAtLeast(1)).coerceAtLeast(1))
            }
        }
    }
    override fun stop() { job?.cancel(); job = null; surface?.release(); surface = null }
    override fun isRunning() = job?.isActive == true
    override fun release() = stop()
}
