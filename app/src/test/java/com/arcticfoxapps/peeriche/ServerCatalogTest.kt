package com.arcticfoxapps.peeriche

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerCatalogTest {
    @Test fun catalogNormalizesDuplicatesAndRejectsNonDomainEntries() {
        val values = sequenceOf(" EXAMPLE.ORG. ", "example.org", "tube.example.net", "https://example.org",
            "127.0.0.1", "localhost", "server.local", "tube.invalid", "user@example.net", "example.net/path")
        assertEquals(listOf("example.org", "tube.example.net"), catalogHosts(values))
    }

    @Test fun partialRefreshPreservesExistingEntriesAndCompleteRefreshReplacesThem() {
        val saved = listOf("old.example", "common.example")
        val partial = updatedCatalog(saved, listOf("official.example"), null)
        assertTrue(partial.partial)
        assertEquals(listOf("common.example", "official.example", "old.example"), partial.hosts)
        val complete = updatedCatalog(saved, listOf("official.example", "common.example"), listOf("fedidb.example", "common.example"))
        assertFalse(complete.partial)
        assertEquals(listOf("common.example", "fedidb.example", "official.example"), complete.hosts)
        try { updatedCatalog(saved, null, null); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun selectionChoosesLowestResponseTimeAndLimitsConcurrentRequests() = runBlocking {
        var running = 0
        var maximum = 0
        val selected = fastestCatalogHost((1..32).map { "server$it.example" }) { host ->
            running++
            maximum = maxOf(maximum, running)
            delay(2)
            running--
            when (host) {
                "server20.example" -> 12L
                "server1.example" -> null
                else -> 100L
            }
        }
        assertEquals("server20.example", selected)
        assertTrue(maximum <= 8)
        assertNull(fastestCatalogHost(listOf("offline.example")) { null })
    }

    @Test fun autoSelectionAppliesWhenNoExplicitConnectionOrAuthenticatedAccountExists() {
        val account = AccountSummary("https://account.example|1", "https://account.example", "me", "Me", null, false)
        assertNull(preferredBrowsingServer(emptyList(), "", null))
        assertNull(preferredBrowsingServer(listOf("https://peertube.tv"), "", null))
        assertNull(preferredBrowsingServer(emptyList(), "", account.copy(needsLogin = true)))
        assertEquals(account.server, preferredBrowsingServer(emptyList(), "", account))
        assertEquals("https://selected.example", preferredBrowsingServer(listOf("https://selected.example"), "https://selected.example", account))
    }

    @Test fun changingServerCancelsPendingProbeOperations() = runBlocking {
        var cancelled = false
        val started = CompletableDeferred<Unit>()
        val selection = launch {
            fastestCatalogHost(listOf("slow.example")) {
                try { started.complete(Unit); awaitCancellation() }
                finally { cancelled = true }
            }
        }
        started.await()
        selection.cancelAndJoin()
        assertTrue(cancelled)
    }
}
