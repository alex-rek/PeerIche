package com.arcticfoxapps.peeriche

import androidx.compose.ui.geometry.Offset
import androidx.media3.common.Tracks
import org.junit.Assert.*
import org.junit.Test

class PlayerControlsTest {
    @Test fun floatingWindowStaysInsideScreenAfterDragging() {
        assertEquals(Offset(0f, 400f), clampFloatingPosition(Offset(-90f, 900f), 200f, 400f))
        assertEquals(Offset(200f, 0f), clampFloatingPosition(Offset(900f, -90f), 200f, 400f))
    }
    @Test fun floatingWindowKeepsValidPosition() {
        assertEquals(Offset(20f, 35f), clampFloatingPosition(Offset(20f, 35f), 200f, 400f))
    }
    @Test fun floatingWindowHandlesSmallOrResizedScreens() {
        assertEquals(Offset.Zero, clampFloatingPosition(Offset(200f, 300f), -10f, 0f))
    }
    @Test fun emptyOrUnpreparedPlayerHasNoSubtitleChoices() {
        assertTrue(subtitleTrackChoices(Tracks.EMPTY).isEmpty())
    }
}
