package com.arcticfoxapps.peeriche

/** Encoder and preview rotations are independent; preview undoes the selected recording rotation. */
internal fun broadcastPreviewTransform(portraitOutput: Boolean, fixedPreview: Boolean, displayRotation: Int): Pair<Int, Int> =
    if (fixedPreview) { if (portraitOutput) 0 to 0 else 270 to 90 }
    else (if (displayRotation == 0) 270 else displayRotation - 90) to 0

internal fun broadcastPreviewAspect(width: Int, height: Int): Float =
    if (width > 0 && height > 0) minOf(width, height).toFloat() / maxOf(width, height) else 9f / 16f
