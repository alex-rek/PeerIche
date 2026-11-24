package com.arcticfoxapps.peeriche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySyncTest {
    @Test fun firstPassAddsServerHistoryBelowWithoutReordering() {
        val plan = planHistoryImport(listOf("a", "b", "c"), local = setOf("b"), previousNewest = null, pendingRemoval = emptySet())
        assertTrue(plan.top.isEmpty())
        assertEquals(listOf("a", "c"), plan.bottom)
        assertEquals("a", plan.newest)
    }

    @Test fun videosWatchedElsewhereSinceLastPassGoToTop() {
        val plan = planHistoryImport(listOf("new2", "new1", "old", "older"), local = setOf("new1", "old"), previousNewest = "old", pendingRemoval = emptySet())
        assertEquals(listOf("new2", "new1"), plan.top)
        assertEquals(listOf("older"), plan.bottom)
        assertEquals("new2", plan.newest)
    }

    @Test fun nothingNewWhenServerTopIsUnchanged() {
        val plan = planHistoryImport(listOf("old", "older"), local = setOf("old", "older"), previousNewest = "old", pendingRemoval = emptySet())
        assertTrue(plan.top.isEmpty() && plan.bottom.isEmpty())
    }

    @Test fun removalsNotYetOnServerAreNotBroughtBack() {
        val plan = planHistoryImport(listOf("removed", "kept"), local = emptySet(), previousNewest = null, pendingRemoval = setOf("removed"))
        assertEquals(listOf("kept"), plan.bottom)
        assertEquals("kept", plan.newest)
    }

    @Test fun lostMarkerDoesNotReorder() {
        val plan = planHistoryImport(listOf("x", "y"), local = setOf("y"), previousNewest = "gone", pendingRemoval = emptySet())
        assertTrue(plan.top.isEmpty())
        assertEquals(listOf("x"), plan.bottom)
    }

    @Test fun videoRemovedOnServerIsRemovedHere() {
        val plan = planHistoryImport(listOf("kept"), local = setOf("kept", "gone", "phoneOnly"), previousNewest = "kept",
            pendingRemoval = emptySet(), seen = setOf("kept", "gone"), complete = true)
        assertEquals(listOf("gone"), plan.removeLocal)
        assertEquals(setOf("kept"), plan.seen)
    }

    @Test fun partialServerListNeverRemovesAnything() {
        val plan = planHistoryImport(listOf("kept"), local = setOf("kept", "older"), previousNewest = "kept",
            pendingRemoval = emptySet(), seen = setOf("kept", "older"), complete = false)
        assertTrue(plan.removeLocal.isEmpty())
        assertEquals(setOf("kept", "older"), plan.seen)
    }

    @Test fun clearedServerHistoryRemovesOnlyWhatWasThere() {
        val plan = planHistoryImport(emptyList(), local = setOf("synced", "phoneOnly"), previousNewest = "synced",
            pendingRemoval = emptySet(), seen = setOf("synced"), complete = true)
        assertEquals(listOf("synced"), plan.removeLocal)
        assertEquals(null, plan.newest)
    }

    @Test fun onlyTheNewestEntriesAreAdded() {
        val plan = planHistoryImport(listOf("a", "b", "c"), local = emptySet(), previousNewest = null,
            pendingRemoval = emptySet(), complete = true, addLimit = 2)
        assertEquals(listOf("a", "b"), plan.bottom)
        assertEquals(setOf("a", "b", "c"), plan.seen)
    }

    @Test fun keysIgnoreHostAndCase() {
        assertEquals(historyKey("https://a.example|ABC"), historyKey("abc"))
    }

    @Test fun historyRoutesAreExpected() {
        assertEquals(listOf("api", "v1", "users", "me", "history", "videos"), AccountOperation.history(0).segments)
        val remove = AccountOperation.removeHistoryVideo(12)
        assertEquals("DELETE", remove.method)
        assertEquals(listOf("api", "v1", "users", "me", "history", "videos", "12"), remove.segments)
        val clear = AccountOperation.clearHistory("2026-11-24T10:00:00.000Z")
        assertEquals(listOf("api", "v1", "users", "me", "history", "videos", "remove"), clear.segments)
        assertEquals("2026-11-24T10:00:00.000Z", clear.body?.get("beforeDate")?.asString)
        val view = AccountOperation.view("123e4567-e89b-42d3-a456-426614174000", 42)
        assertEquals("POST", view.method)
        assertEquals(42, view.body?.get("currentTime")?.asInt)
    }
}
