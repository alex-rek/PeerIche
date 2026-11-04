package com.arcticfoxapps.peeriche

/** Accumulates actual playing time, independently of seek position and playback speed. */
internal class WatchTimeTracker(private val thresholdMs: Long = 5_000) {
    private var identity: String? = null
    private var lastTime = 0L
    private var wasPlaying = false
    private var elapsed = 0L
    private var reported = false

    fun sample(id: String?, now: Long, playing: Boolean): Boolean {
        if (id != identity) {
            identity = id
            elapsed = 0
            reported = false
            wasPlaying = false
            lastTime = now
        }
        if (wasPlaying) elapsed += (now - lastTime).coerceAtLeast(0)
        lastTime = now
        wasPlaying = playing
        if (id.isNullOrBlank() || reported || elapsed < thresholdMs) return false
        reported = true
        return true
    }
}
