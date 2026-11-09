package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class BroadcastLogoTest {
    @Test fun logoFitsAllCornersAndBothOrientationsWithoutDistortion() {
        for ((w,h) in listOf(1280 to 720, 720 to 1280)) for (corner in listOf("top_left", "top_right", "bottom_left", "bottom_right")) {
            val b = broadcastLogoBounds(w, h, 400, 100, BroadcastOptions(logoCorner = corner, logoSize = 40, logoMargin = 12))
            assertTrue(b.left >= 0 && b.top >= 0 && b.left + b.width <= w && b.top + b.height <= h)
            assertEquals(4f, b.width / b.height, .001f)
            assertEquals(86.4f, if (corner.endsWith("right")) w - b.left - b.width else b.left, .01f)
            assertEquals(86.4f, if (corner.startsWith("bottom")) h - b.top - b.height else b.top, .01f)
        }
    }
    @Test fun legacyOptionsKeepLogoDisabledAndNewOptionsRoundTrip() {
        val gson = com.google.gson.Gson()
        assertFalse(gson.fromJson("{}", BroadcastOptions::class.java).checked().logoEnabled)
        val value = BroadcastOptions(logoEnabled = true, logoCorner = "bottom_left", logoSize = 30, logoOpacity = 60, logoMargin = 5)
        assertEquals(value, gson.fromJson(gson.toJson(value), BroadcastOptions::class.java).checked())
    }
}
