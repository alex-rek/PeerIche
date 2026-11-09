package com.arcticfoxapps.peeriche

import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.NoFilterRender

internal fun cameraFitScale(sourceAspect: Float, frameAspect: Float): Pair<Float, Float> {
    require(sourceAspect > 0 && frameAspect > 0)
    return if (sourceAspect < frameAspect) sourceAspect / frameAspect to 1f else 1f to frameAspect / sourceAspect
}

/** CameraRender normalizes its texture to the encoder FBO; undo aspect distortion with letterboxing. */
internal class BroadcastCameraFit(sourceAspect: Float, frameAspect: Float) : NoFilterRender() {
    init {
        val (x, y) = cameraFitScale(sourceAspect, frameAspect)
        Matrix.scaleM(MVPMatrix, 0, x, y, 1f)
    }
    override fun drawFilter() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        super.drawFilter()
    }
}
