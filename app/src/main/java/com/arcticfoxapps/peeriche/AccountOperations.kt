package com.arcticfoxapps.peeriche

import com.google.gson.JsonObject

/** Fixed endpoint descriptions; account credentials never leave the auth transport. */
internal class AccountOperation private constructor(val method: String, val segments: List<String>,
    val query: Map<String, String> = emptyMap(), val body: JsonObject? = null, val empty: Boolean = false) {
    val writes: Boolean get() = method != "GET"
    companion object {
        private fun path(vararg values: String) = listOf("api", "v1") + values
        private fun id(value: String): String {
            if (!value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw AuthFailure("account_video_unavailable")
            return value
        }
        fun subscriptions(start: Int) = AccountOperation("GET", path("users", "me", "subscriptions"), mapOf("start" to start.coerceAtLeast(0).toString(), "count" to "100"))
        fun feed(start: Int, sensitive: Boolean) = AccountOperation("GET", path("users", "me", "subscriptions", "videos"),
            mapOf("start" to start.coerceAtLeast(0).toString(), "count" to "30", "sort" to "-publishedAt", "nsfw" to sensitive.toString()))
        fun ownChannels() = AccountOperation("GET", path("users", "me"))
        fun subscription(handle: String) = AccountOperation("GET", path("users", "me", "subscriptions", validSubscriptionHandle(handle)))
        fun subscribe(handle: String) = AccountOperation("POST", path("users", "me", "subscriptions"), body = JsonObject().apply { addProperty("uri", validSubscriptionHandle(handle)) }, empty = true)
        fun unsubscribe(handle: String) = AccountOperation("DELETE", path("users", "me", "subscriptions", validSubscriptionHandle(handle)), empty = true)
        fun video(uuid: String) = AccountOperation("GET", path("videos", id(uuid)))
        fun resolve(url: String) = AccountOperation("GET", path("search", "videos"), mapOf("search" to url, "count" to "10"))
        fun rating(uuid: String) = AccountOperation("GET", path("users", "me", "videos", id(uuid), "rating"))
        fun rate(uuid: String, rating: String): AccountOperation {
            require(rating in setOf("like", "dislike", "none"))
            return AccountOperation("PUT", path("videos", id(uuid), "rate"), body = JsonObject().apply { addProperty("rating", rating) }, empty = true)
        }
        fun comments(uuid: String, start: Int) = AccountOperation("GET", path("videos", id(uuid), "comment-threads"), mapOf("start" to start.coerceAtLeast(0).toString(), "count" to "20", "sort" to "-createdAt"))
        fun thread(uuid: String, thread: Int): AccountOperation {
            require(thread > 0)
            return AccountOperation("GET", path("videos", id(uuid), "comment-threads", thread.toString()))
        }
        fun comment(uuid: String, text: String, reply: Int? = null): AccountOperation {
            if (text.isBlank() || text.length > 10_000) throw AuthFailure("invalid_comment")
            if (reply != null) require(reply > 0)
            val route = if (reply == null) path("videos", id(uuid), "comment-threads") else path("videos", id(uuid), "comments", reply.toString())
            return AccountOperation("POST", route, body = JsonObject().apply { addProperty("text", text.trim()) })
        }
    }
}

internal fun validSubscriptionHandle(value: String): String {
    val name = value.substringBeforeLast('@', "")
    val host = value.substringAfterLast('@', "")
    if (!name.matches(Regex("[A-Za-z0-9_.:-]{1,200}")) || host.isBlank()) throw AuthFailure("invalid_channel")
    val origin = authServerOrigin("https://$host")
    return "$name@${java.net.URI(origin).authority}"
}

internal fun PeerTubeChannel.subscriptionHandle(fallback: String): String = validSubscriptionHandle(
    if (name.contains('@')) name else "$name@${host ?: java.net.URI(fallback).authority}")
internal fun Video.accountChannelHandle(fallback: String): String {
    val name = channel?.name?.takeIf { it.isNotBlank() } ?: throw AuthFailure("invalid_channel")
    return PeerTubeChannel(name = name, host = channel?.host).subscriptionHandle(sourceServer(fallback))
}

internal data class RatingCounts(val likes: Long, val dislikes: Long)
internal fun updatedRatingCounts(likes: Long, dislikes: Long, old: String, next: String) = RatingCounts(
    (likes - if (old == "like") 1 else 0) .let { (it + if (next == "like") 1 else 0).coerceAtLeast(0) },
    (dislikes - if (old == "dislike") 1 else 0).let { (it + if (next == "dislike") 1 else 0).coerceAtLeast(0) })
