package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Synthetic local audio only; never accesses an account or publishes anything. */
internal fun playbackPositionSmoke(test: Instrumentation) {
    val context = test.targetContext
    val id = "resume-smoke-${System.nanoTime()}"
    val file = File(context.cacheDir, "$id.wav")
    var controller: MediaController? = null
    val result = Bundle()
    var code = Activity.RESULT_OK
    try {
        check(android.os.Build.HARDWARE.contains("ranchu"))
        val bytes = 8000 * 2 * 30
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(bytes + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array()
        file.outputStream().use { it.write(header); it.write(ByteArray(bytes)) }
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        test.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        controller = future.get(10, TimeUnit.SECONDS)
        test.runOnMainSync {
            controller!!.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(android.net.Uri.fromFile(file)).build(), 0)
            controller!!.prepare()
        }
        Thread.sleep(1500)
        test.runOnMainSync { controller!!.seekTo(12_345); controller!!.pause() }
        Thread.sleep(2500)
        check(PlaybackPositions(context).read(id, false) in 12_300L..12_400L)
        test.runOnMainSync { controller!!.seekTo(29_900); controller!!.play() }
        Thread.sleep(1500)
        check(PlaybackPositions(context).read(id, false) == 0L)
        test.runOnMainSync { controller!!.seekTo(12_345); controller!!.pause() }
        Thread.sleep(500)
        test.runOnMainSync { controller!!.release(); controller = null }
        context.stopService(Intent(context, PlaybackService::class.java))
        Thread.sleep(500)
        check(PlaybackPositions(context).read(id, false) in 12_300L..12_400L)
        check(PlaybackPositions(context).read(id, true) == 0L)
        PlaybackPositions(context).save(id, 30_000, 30_000, false, true)
        check(PlaybackPositions(context).read(id, false) == 0L)
        result.putString("stream", "PASS: playback service checkpoint after seek/pause survives service stop; live and completed playback reset\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED
        result.putString("stream", "FAIL: resume ${failure.javaClass.simpleName}: ${failure.message}\n")
    } finally {
        test.runOnMainSync { controller?.release() }
        context.stopService(Intent(context, PlaybackService::class.java))
        PlaybackPositions(context).save(id, 0, 0, false)
        file.delete()
    }
    test.finish(code, result)
}
