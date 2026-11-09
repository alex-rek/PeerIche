package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class BroadcastPreviewTest {
    @Test fun fixedPreviewSizeDoesNotFollowRecordingOrientation() {
        assertEquals(broadcastPreviewAspect(1080,1920), broadcastPreviewAspect(1920,1080), .0001f)
        assertEquals(9f/16, broadcastPreviewAspect(0,0), .0001f)
    }
    @Test fun previewCompensatesRecordingRotationWithoutChangingCameraView() {
        assertEquals(0 to 0, broadcastPreviewTransform(true, true, 90))
        assertEquals(270 to 90, broadcastPreviewTransform(false, true, 90))
        for (display in listOf(0,90,180,270)) {
            val rotations = broadcastPreviewTransform(false,true,display)
            assertEquals(0, (rotations.first+rotations.second)%360)
        }
    }
}
