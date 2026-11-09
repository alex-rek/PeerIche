package com.arcticfoxapps.peeriche

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun validatedImportUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull() ?: throw AuthFailure("invalid_import_url")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || value.trim().length > 8192)
        throw AuthFailure("invalid_import_url")
    return url.toString()
}
