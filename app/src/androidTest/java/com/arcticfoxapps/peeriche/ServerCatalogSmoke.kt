package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/** Public catalog/API reads only, with restoration of the saved catalog after the test. */
internal suspend fun serverCatalogSmoke(context: Context): String {
    val bundled = context.assets.open("peertube-servers.txt").bufferedReader().use { catalogHosts(it.lineSequence()) }
    check(bundled.size == 1956)
    val previous = context.peerIcheDataStore.data.first()[catalogKey]
    try {
        val update = refreshServerCatalog(context, bundled)
        val stored = context.peerIcheDataStore.data.first()[catalogKey]
        check(stored?.lines() == update.hosts)
        val selected = chooseCatalogServer(update.hosts)
        check(selected != null) { "No sampled server returned a video" }
        check(PeerTubeClients.checkServer(selected).data.isNotEmpty())
        return "PASS: bundled ${bundled.size}, updated ${update.hosts.size}, partial=${update.partial}, selected=$selected"
    } finally {
        context.peerIcheDataStore.edit {
            if (previous == null) it.remove(catalogKey) else it[catalogKey] = previous
        }
    }
}
