package com.arcticfoxapps.peeriche

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun validatedImportUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull() ?: throw AuthFailure("invalid_import_url")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || value.trim().length > 8192)
        throw AuthFailure("invalid_import_url")
    return url.toString()
}

/** Blank fields deliberately use PeerTube's existing source metadata extractor. */
internal fun importMetadata(metadata: CreatorMetadata): com.google.gson.JsonObject {
    val data = metadata.copy(name = metadata.name.ifBlank { "Import" }).json()
    if (metadata.name.isBlank()) data.remove("name")
    return data
}

internal fun importedUuid(json: com.google.gson.JsonObject): String? {
    if (json.objectAt("video") != null) return uploadedUuid(json)
    // Some versions acknowledge the import job before attaching its video.
    if (json.longAt("id", 0) > 0 && json.has("targetUrl") && json.has("state")) return null
    throw AuthFailure("invalid_response")
}
internal fun recentImport(json: com.google.gson.JsonObject, now: Long = System.currentTimeMillis()): Boolean =
    serverTimestamp(json.stringAt("createdAt"))?.let { it in (now - 180_000)..(now + 60_000) } == true
