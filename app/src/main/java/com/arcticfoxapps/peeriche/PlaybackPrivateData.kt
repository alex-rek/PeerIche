package com.arcticfoxapps.peeriche

import androidx.media3.common.MediaItem
import java.util.UUID

internal data class PlaybackPrivateEntry(val history: SavedVideo, val accountId: String?)

/**
 * Keeps history and account identity in this process. MediaMetadata extras are visible to
 * external media controllers, so they carry only an opaque reference to this data.
 */
internal object PlaybackPrivateData {
    const val EXTRA_REFERENCE = "private_ref"
    private const val LIMIT = 32
    private val entries = object : LinkedHashMap<String, PlaybackPrivateEntry>(LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaybackPrivateEntry>?) = size > LIMIT
    }
    @Synchronized fun put(entry: PlaybackPrivateEntry): String =
        UUID.randomUUID().toString().also { entries[it] = entry }
    @Synchronized fun get(reference: String?): PlaybackPrivateEntry? = reference?.let { entries[it] }
}

internal val MediaItem.privatePlaybackData: PlaybackPrivateEntry?
    get() = PlaybackPrivateData.get(mediaMetadata.extras?.getString(PlaybackPrivateData.EXTRA_REFERENCE))
