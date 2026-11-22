package com.arcticfoxapps.peeriche

import android.content.Context

internal fun resumablePosition(position: Long, duration: Long, live: Boolean, ended: Boolean = false): Long =
    if (live || ended || position < 1_000 || (duration > 0 && position >= duration - 1_000)) 0 else position

/** Entries saved before timestamps existed have no time and are treated as the oldest. */
internal fun positionsToEvict(identities: Set<String>, savedAt: Map<String, Long>, limit: Int): List<String> =
    if (identities.size <= limit) emptyList()
    else identities.sortedBy { savedAt[it] ?: 0L }.take(identities.size - limit)

/** Local checkpoints survive process death; apply writes asynchronously. No media URLs or credentials. */
internal class PlaybackPositions(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)
    private val times = context.applicationContext.getSharedPreferences("playback_position_times", Context.MODE_PRIVATE)
    fun read(identity: String, live: Boolean): Long = if (live) 0 else preferences.getLong(identity, 0).coerceAtLeast(0)
    fun save(identity: String, position: Long, duration: Long, live: Boolean, ended: Boolean = false) {
        val value = resumablePosition(position, duration, live, ended)
        preferences.edit().apply { if (value == 0L) remove(identity) else putLong(identity, value) }.apply()
        times.edit().apply { if (value == 0L) remove(identity) else putLong(identity, System.currentTimeMillis()) }.apply()
    }
    fun prune(limit: Int = MAX_PLAYBACK_POSITIONS) {
        val identities = preferences.all.keys.toSet()
        val savedAt = times.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
        val evicted = positionsToEvict(identities, savedAt, limit).toSet()
        val orphaned = savedAt.keys - identities
        if (evicted.isNotEmpty()) preferences.edit().apply { evicted.forEach(::remove) }.apply()
        if (evicted.isNotEmpty() || orphaned.isNotEmpty()) times.edit().apply { (evicted + orphaned).forEach(::remove) }.apply()
    }
}
