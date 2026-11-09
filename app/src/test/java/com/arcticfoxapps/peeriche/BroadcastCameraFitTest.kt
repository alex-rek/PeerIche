package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class BroadcastCameraFitTest {
    @Test fun portraitCameraFitsLandscapeWithoutStretchingOrCropping() {
        val (x, y) = cameraFitScale(9f / 16, 16f / 9)
        assertEquals(1f, y, .0001f)
        assertEquals(9f / 16, (16f / 9) * x / y, .0001f)
        assertTrue(x < 1f)
    }
    @Test fun landscapeCameraFitsPortraitAndMatchingFramesStayUnscaled() {
        val (x, y) = cameraFitScale(16f / 9, 9f / 16)
        assertEquals(1f, x, .0001f)
        assertEquals(16f / 9, (9f / 16) * x / y, .0001f)
        assertEquals(1f to 1f, cameraFitScale(9f / 16, 9f / 16))
    }
}
