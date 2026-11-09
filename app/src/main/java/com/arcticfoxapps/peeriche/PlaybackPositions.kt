package com.arcticfoxapps.peeriche

import android.content.Context

internal fun resumablePosition(position: Long, duration: Long, live: Boolean, ended: Boolean = false): Long =
    if (live || ended || position < 1_000 || (duration > 0 && position >= duration - 1_000)) 0 else position

/** Local checkpoints survive process death; apply writes asynchronously. No media URLs or credentials. */
internal class PlaybackPositions(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)
    fun read(identity: String, live: Boolean): Long = if (live) 0 else preferences.getLong(identity, 0).coerceAtLeast(0)
    fun save(identity: String, position: Long, duration: Long, live: Boolean, ended: Boolean = false) {
        val value = resumablePosition(position, duration, live, ended)
        preferences.edit().apply { if (value == 0L) remove(identity) else putLong(identity, value) }.apply()
    }
}
