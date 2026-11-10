package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Only a generated local channel is modified; no account writes or playable media. */
internal fun hiddenChannelsUiFixture(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val marker = File(context.cacheDir, "hidden-ui-finish").apply { delete() }
    val video = Video(uuid = "hidden-ui", name = "Layout fixture", host = "fixture.invalid",
        channel = Channel("A very long channel title for layout checking", "hidden_ui_${UUID.randomUUID().toString().replace("-", "")}", "fixture.invalid"))
    var activity: MainActivity? = null
    val result = Bundle(); var code = Activity.RESULT_OK
    try {
        check(android.os.Build.HARDWARE.contains("ranchu"))
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity!!.setContent {
                var settings by remember { mutableStateOf(false) }
                var dark by remember { mutableStateOf(false) }
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            Row {
                                TextButton(onClick = { settings = !settings }) { Text("Hidden settings") }
                                TextButton(onClick = { dark = !dark }) { Text("Theme") }
                            }
                            if (settings) HiddenChannelsSettings()
                            else Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                                WatchDetails(video, "https://fixture.invalid", false, false, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        runBlocking { repeat(180) { if (marker.exists()) return@runBlocking; delay(1000) } }
        result.putString("stream", "Hidden channel UI finished: local fixture only\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL hidden UI: ${failure.javaClass.simpleName}\n")
    } finally {
        instrumentation.runOnMainSync { activity?.finish() }
        runBlocking { video.hiddenChannelIdentity("https://fixture.invalid")?.let { PeerIcheDatabase.get(context).library().delete(it, HIDDEN_CHANNEL) } }
        marker.delete()
    }
    instrumentation.finish(code, result)
}
