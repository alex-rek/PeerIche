package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.*

/** Uses an in-memory release response; no APK is downloaded or installed. */
internal fun startupUpdateSmoke(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val settings = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    val existed = settings.contains("last_check")
    val previous = settings.getLong("last_check", 0)
    var activity: MainActivity? = null
    val result = Bundle()
    var code = Activity.RESULT_OK
    fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
        node ?: return false
        if (node.text?.toString() == text) return true
        return (0 until node.childCount).any { contains(node.getChild(it), text) }
    }
    try {
        check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"))
        settings.edit().putLong("last_check", System.currentTimeMillis() + 86_400_000).commit()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val checked = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync {
            activity!!.setContent { MaterialTheme {
                AppUpdateFeature(0, checkForUpdate = {
                    checked.complete(Unit)
                    AppRelease("0.9.99", "https://github.com/alex-rek/PeerIche/releases/download/v0.9.99/PeerIche-0.9.99.apk", "0".repeat(64))
                })
            } }
        }
        runBlocking {
            withTimeout(10000) { checked.await() }
            val title = activity!!.getString(R.string.update_available, "0.9.99")
            withTimeout(10000) { while (!contains(instrumentation.uiAutomation.rootInActiveWindow, title)) delay(100) }
        }
        result.putString("stream", "PASS: startup check ignores legacy cooldown and displays the available-update prompt without a manual request\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED
        result.putString("stream", "FAIL: startup update ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull { it.className.startsWith("com.arcticfoxapps.peeriche") }}\n")
    } finally {
        instrumentation.runOnMainSync { activity?.finish() }
        settings.edit().apply { if (existed) putLong("last_check", previous) else remove("last_check") }.commit()
    }
    instrumentation.finish(code, result)
}
