package com.arcticfoxapps.peeriche

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

internal data class LogoBounds(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun broadcastLogoBounds(frameWidth: Int, frameHeight: Int, imageWidth: Int, imageHeight: Int, options: BroadcastOptions): LogoBounds {
    require(frameWidth > 0 && frameHeight > 0 && imageWidth > 0 && imageHeight > 0)
    val edge = minOf(frameWidth, frameHeight).toFloat()
    val margin = edge * options.logoMargin / 100f
    val scale = minOf(edge * options.logoSize / 100f / maxOf(imageWidth, imageHeight),
        (frameWidth - margin * 2) / imageWidth, (frameHeight - margin * 2) / imageHeight)
    val width = imageWidth * scale; val height = imageHeight * scale
    return LogoBounds(if (options.logoCorner.endsWith("right")) frameWidth - margin - width else margin,
        if (options.logoCorner.startsWith("bottom")) frameHeight - margin - height else margin, width, height)
}

/** A transparent frame-sized texture keeps logo geometry independent of camera transforms. */
internal fun broadcastLogoFilter(image: Bitmap, width: Int, height: Int, options: BroadcastOptions): ImageObjectFilterRender {
    val bounds = broadcastLogoBounds(width, height, image.width, image.height, options)
    val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(overlay).drawBitmap(image, null, RectF(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = options.logoOpacity * 255 / 100 })
    return ImageObjectFilterRender().apply { setImage(overlay); setScale(100f, 100f); setPosition(0f, 0f) }
}
