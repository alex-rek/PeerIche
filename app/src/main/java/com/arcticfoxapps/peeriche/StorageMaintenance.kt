package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

internal const val TRANSLATED_SUBTITLE_CACHE_BYTES = 100L * 1024 * 1024
internal const val MAX_HISTORY_ROWS = 2_000
internal const val MAX_PLAYBACK_POSITIONS = 1_000
private const val STALE_PARTIAL_MS = 60L * 60 * 1000
private const val STALE_UPDATE_MS = 24L * 60 * 60 * 1000

internal data class CachedFile(val name: String, val size: Long, val lastUsed: Long)

/** Selects the least recently used files until the rest fit, never selecting [keep]. */
internal fun cacheFilesToDelete(files: List<CachedFile>, maxBytes: Long, keep: String? = null): List<String> {
    var total = files.sumOf { it.size }
    if (total <= maxBytes) return emptyList()
    val selected = mutableListOf<String>()
    for (file in files.filter { it.name != keep }.sortedBy { it.lastUsed }) {
        if (total <= maxBytes) break
        selected += file.name
        total -= file.size
    }
    return selected
}

internal fun shouldDeleteDownloadedUpdate(archiveVersion: Long?, installedVersion: Long, ageMs: Long): Boolean =
    archiveVersion == null || archiveVersion <= installedVersion || ageMs > STALE_UPDATE_MS

internal fun trimTranslatedSubtitles(context: Context, keep: File? = null) {
    val directory = File(context.cacheDir, "translated-subtitles")
    val now = System.currentTimeMillis()
    val files = directory.listFiles().orEmpty().filter { it.isFile }
    val (partial, complete) = files.partition { it.name.endsWith(".part") }
    partial.filter { now - it.lastModified() > STALE_PARTIAL_MS }.forEach { it.delete() }
    val selected = cacheFilesToDelete(complete.map { CachedFile(it.name, it.length(), it.lastModified()) },
        TRANSLATED_SUBTITLE_CACHE_BYTES, keep?.name)
    selected.forEach { File(directory, it).delete() }
}

private fun cleanDownloadedUpdates(context: Context) {
    val directory = File(context.cacheDir, "updates")
    val now = System.currentTimeMillis()
    File(directory, "update.part").takeIf { it.isFile && now - it.lastModified() > STALE_PARTIAL_MS }?.delete()
    val apk = File(directory, "update.apk").takeIf { it.isFile } ?: return
    val manager = context.packageManager
    val installed = PackageInfoCompat.getLongVersionCode(manager.getPackageInfo(context.packageName, 0))
    val archive = manager.getPackageArchiveInfo(apk.path, 0)?.let(PackageInfoCompat::getLongVersionCode)
    if (shouldDeleteDownloadedUpdate(archive, installed, now - apk.lastModified())) apk.delete()
}

/** Runs off the main thread at startup. Failures must never affect the app. */
internal fun runStorageMaintenance(context: Context) {
    runCatching { trimTranslatedSubtitles(context) }
    runCatching { cleanDownloadedUpdates(context) }
    runCatching { PlaybackPositions(context).prune() }
}
