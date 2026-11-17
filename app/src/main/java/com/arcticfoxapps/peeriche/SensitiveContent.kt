package com.arcticfoxapps.peeriche

// PeerTube's "true" means sensitive videos only, not ordinary videos plus sensitive ones.
internal fun sensitiveContentFilter(includeSensitive: Boolean): String = if (includeSensitive) "both" else "false"
