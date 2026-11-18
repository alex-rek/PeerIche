package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Local proxy fixtures only, fake proxy credentials, public HTTPS reads; no server/account writes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun proxySmoke(test: Instrumentation) {
    val context = test.targetContext
    val original = ProxyNetwork.state.value
    val existed = File(context.noBackupFilesDir, "network-proxy.bin").exists()
    val suffix = UUID.randomUUID().toString()
    val alias = "peeriche.proxy-test.$suffix"
    val storeFile = "proxy-test-$suffix.bin"
    val storage = ProxySettingsStore(context, storeFile, alias)
    var player: ExoPlayer? = null
    val result = Bundle(); var code = Activity.RESULT_OK
    try {
        check(android.os.Build.HARDWARE.contains("ranchu"))
        check(!original.storageError)
        val stored = ProxyConfiguration("http", "127.0.0.1", 8080, "storage-user", "storage-password")
        storage.save(stored)
        check(ProxySettingsStore(context, storeFile, alias).load() == stored)
        val raw = File(context.noBackupFilesDir, storeFile).readBytes().toString(Charsets.ISO_8859_1)
        check(!raw.contains("storage-password") && !raw.contains("storage-user"))
        val api = PeerTubeClients.server("https://tube.articfox.cc") // Reuse one cached client through all settings changes.
        var media = ""
        val passed = mutableListOf<String>()
        runBlocking {
            for ((type, port, authenticated) in listOf(Triple("http", 18180, false), Triple("socks5", 18181, false),
                Triple("http", 18182, true), Triple("socks5", 18183, true))) {
                ProxyNetwork.save(ProxyConfiguration(type, "10.0.2.2", port,
                    if (authenticated) "peeriche-test" else "", if (authenticated) "fixture-password" else ""))
                test.runOnMainSync {
                    ProxyNetwork.calls().newCall(okhttp3.Request.Builder().url("https://example.com/").build()).cancel()
                }
                val page = api.videoListing(count = 1, isLocal = true).use { parsePeerTubeVideoPage(it.string()) }
                check(page.data.isNotEmpty())
                val thumbnail = page.data.first().thumbnailCandidates("https://tube.articfox.cc").first()
                val image = Coil.imageLoader(context).execute(ImageRequest.Builder(context).data(thumbnail).size(80).allowHardware(false)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
                check(image is SuccessResult) { "Proxy image request failed" }
                if (media.isEmpty()) media = videoDownloadOptions(PeerTubeClients.video("https://0ch.tv", "ddd30b7a-f7fa-4878-8fa1-31cc45245d98")).last().url
                withContext(Dispatchers.IO) {
                    val source = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(ProxyNetwork.calls())).createDataSource()
                    try {
                        source.open(DataSpec.Builder().setUri(media).setLength(4096).build())
                        check(source.read(ByteArray(4096), 0, 4096) > 0)
                    } finally { source.close() }
                }
                check(ProxySettingsStore(context).load() == ProxyNetwork.state.value.config)
                passed += "$type:${if (authenticated) "auth" else "no-auth"}"
            }
            ProxyNetwork.save(ProxyConfiguration("http", "10.0.2.2", 18182, "peeriche-test", "fixture-password"))
        }
        test.runOnMainSync {
            player = ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context, OkHttpDataSource.Factory(ProxyNetwork.calls())))).build().apply {
                volume = 0f; setMediaItem(MediaItem.fromUri(media)); prepare(); play()
            }
        }
        var played = false
        var playbackFailure: String? = null
        repeat(150) {
            test.runOnMainSync {
                played = played || (player!!.playbackState == Player.STATE_READY && player!!.currentPosition > 200)
                playbackFailure = player!!.playerError?.errorCodeName
            }
            if (played || playbackFailure != null) return@repeat
            Thread.sleep(100)
        }
        check(played) { "Playback did not start: $playbackFailure" }
        test.runOnMainSync { player?.release(); player = null }
        runBlocking {
            ProxyNetwork.save(ProxyConfiguration("http", "10.0.2.2", 18182, "peeriche-test", "wrong-password"))
            check(runCatching { api.videoListing(count = 1).close() }.isFailure) { "Wrong proxy credentials must not bypass the proxy" }
        }
        result.putString("stream", "PASS: encrypted proxy settings survive reload; cached API, Coil and media range requests over ${passed.joinToString()}; real muted playback over authenticated CONNECT; wrong credentials fail closed\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED
        result.putString("stream", "FAIL proxy smoke: ${failure.javaClass.simpleName}: ${failure.message}\n")
    } finally {
        test.runOnMainSync { player?.release() }
        context.stopService(Intent(context, PlaybackService::class.java))
        runBlocking { if (existed) ProxyNetwork.save(original.config) else ProxyNetwork.reset() }
        storage.clear()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    test.finish(code, result)
}
