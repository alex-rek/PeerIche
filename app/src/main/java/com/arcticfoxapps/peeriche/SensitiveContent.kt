package com.arcticfoxapps.peeriche

// PeerTube's "true" means sensitive videos only, not ordinary videos plus sensitive ones.
internal fun sensitiveContentFilter(includeSensitive: Boolean): String = if (includeSensitive) "both" else "false"

/**
 * The one rule for every feed: sensitive videos are listed only when they are included and not set to "Hide".
 * Search, Home, subscriptions, server and live pages and Up next all follow it.
 */
internal fun AppOptions.allowSensitive(): Boolean = includeSensitive && sensitiveDisplay != "hide"

/**
 * Drops sensitive videos unless [allow]. Needed on top of the request filter because some sources
 * (PeerSeek, channel lists that follow the server's default) do not apply it.
 */
internal fun List<Video>.withSensitivePolicy(allow: Boolean): List<Video> = if (allow) this else filterNot { it.nsfw }
