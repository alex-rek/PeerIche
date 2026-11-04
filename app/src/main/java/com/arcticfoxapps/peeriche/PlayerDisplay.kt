package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

internal fun videoAspectRatio(value: String): Float? = when (value) {
    "16:9" -> 16f / 9f
    "4:3" -> 4f / 3f
    "1:1" -> 1f
    "21:9" -> 21f / 9f
    else -> null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AspectPlayerView(context: Context) : PlayerView(context) {
    private var forcedRatio: Float? = null
    private var naturalRatio = 0f
    fun setDisplayFormat(value: String) {
        forcedRatio = videoAspectRatio(value)
        findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.setAspectRatio(forcedRatio ?: naturalRatio)
    }
    override fun onContentAspectRatioChanged(contentFrame: AspectRatioFrameLayout?, aspectRatio: Float) {
        naturalRatio = aspectRatio
        super.onContentAspectRatioChanged(contentFrame, forcedRatio ?: aspectRatio)
    }
}
