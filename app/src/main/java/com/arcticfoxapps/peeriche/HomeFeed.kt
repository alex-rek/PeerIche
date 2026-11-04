package com.arcticfoxapps.peeriche

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun sortHomeVideos(videos: List<Video>, sort: String, server: String): List<Video> {
    val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd").map {
        SimpleDateFormat(it, Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    }
    val dated = deduplicateVideos(videos, server).map { video ->
        video to video.publishedAt?.let { date -> formats.firstNotNullOfOrNull { format ->
            val position = java.text.ParsePosition(0)
            format.parse(date, position)?.takeIf { position.index == date.length }?.time
        } }
    }
    return when (sort) {
        "oldest" -> dated.sortedWith(compareBy<Pair<Video, Long?>> { it.second == null }.thenBy { it.second })
        "views" -> dated.sortedWith(compareByDescending<Pair<Video, Long?>> { it.first.views }.thenByDescending { it.second })
        else -> dated.sortedWith(compareBy<Pair<Video, Long?>> { it.second == null }.thenByDescending { it.second })
    }.map { it.first }
}
