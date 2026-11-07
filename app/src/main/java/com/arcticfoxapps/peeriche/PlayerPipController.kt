package com.arcticfoxapps.peeriche

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun Context.playerActivity(): MainActivity? {
    var candidate: Context = this
    while (candidate is ContextWrapper) {
        if (candidate is MainActivity) return candidate
        candidate = candidate.baseContext
    }
    return candidate as? MainActivity
}
internal fun safePipRatio(value: Float): Float = if (value.isFinite() && value > 0) value.coerceIn(1f / 2.39f, 2.39f) else 16f / 9f

internal class PlayerPipController(private val activity: MainActivity) {
    var active by mutableStateOf(false); private set
    private var entering = false
    private var owner: Any? = null
    private var visible = false
    private var playing = false
    private var suspended = false
    private var source: Rect? = null
    private var ratio = 16f / 9f
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 26 && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    val windowActive: Boolean get() = active || entering || (Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode)
    fun update(token: Any, show: Boolean, play: Boolean, bounds: Rect?, aspect: Float) {
        owner = token; visible = show; playing = play; source = bounds; ratio = aspect
        refresh()
    }
    fun clear(token: Any) { if (owner == token) disable() }
    fun disable() { owner = null; visible = false; playing = false; refresh() }
    fun suspendForExternalUi() { suspended = true; refresh() }
    fun onResume() { suspended = false; entering = false; refresh() }
    private fun refresh() {
        if (Build.VERSION.SDK_INT >= 26 && supported) runCatching { activity.setPictureInPictureParams(params()) }
    }
    @androidx.annotation.RequiresApi(26)
    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((safePipRatio(ratio) * 1000).toInt(), 1000))
        source?.takeUnless { it.isEmpty }?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(visible && playing && !suspended).setSeamlessResizeEnabled(true)
        return builder.build()
    }
    fun enter() {
        if (Build.VERSION.SDK_INT < 26 || !supported || !visible) return
        entering = true
        val accepted = runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
        if (!accepted) {
            entering = false
            Toast.makeText(activity, activity.getString(R.string.pip_unavailable), Toast.LENGTH_LONG).show()
        }
    }
    fun onUserLeaveHint() { if (Build.VERSION.SDK_INT in 26..30 && visible && playing && !suspended) enter() }
    fun onModeChanged(value: Boolean) {
        active = value; entering = false
        if (!value) activity.lifecycleScope.launch {
            delay(200)
            if (!active && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) PlaybackService.pausePlayback()
        }
    }
}
