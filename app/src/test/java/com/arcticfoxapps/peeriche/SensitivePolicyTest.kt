package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivePolicyTest {
    @Test fun sensitiveVideosAreAllowedOnlyWhenIncludedAndNotHidden() {
        assertFalse(AppOptions(includeSensitive = false, sensitiveDisplay = "show").allowSensitive())
        assertFalse(AppOptions(includeSensitive = true, sensitiveDisplay = "hide").allowSensitive())
        listOf("blur", "warning", "show").forEach { display ->
            assertTrue(display, AppOptions(includeSensitive = true, sensitiveDisplay = display).allowSensitive())
        }
        assertFalse("off by default", AppOptions().allowSensitive())
    }

    @Test fun policyDropsSensitiveVideosThatASourceDidNotFilter() {
        val videos = listOf(Video(uuid = "a"), Video(uuid = "b", nsfw = true), Video(uuid = "c"))
        assertEquals(listOf("a", "c"), videos.withSensitivePolicy(false).map { it.uuid })
        assertEquals(videos, videos.withSensitivePolicy(true))
    }

    @Test fun requestFilterKeepsOrdinaryVideosWhenSensitiveAllowed() {
        assertEquals("both", sensitiveContentFilter(true))
        assertEquals("false", sensitiveContentFilter(false))
    }
}
