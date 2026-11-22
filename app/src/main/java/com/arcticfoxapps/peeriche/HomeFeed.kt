package com.arcticfoxapps.peeriche

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

private val peerTubeDatePattern =
    Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})(?:T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:[0-9]{2}))?")

/** Parses ISO dates without SimpleDateFormat's X pattern, which is unavailable before API 24. */
internal fun parsePeerTubeDate(value: String): Long? {
    val parts = peerTubeDatePattern.matchEntire(value)?.groupValues ?: return null
    val zone = parts[8]
    val offsetMinutes = if (zone.isEmpty() || zone == "Z") 0 else {
        val hours = zone.substring(1, 3).toInt()
        val minutes = zone.substring(4, 6).toInt()
        if (hours > 23 || minutes > 59) return null
        (hours * 60 + minutes) * if (zone[0] == '-') -1 else 1
    }
    val millis = parts[7].padEnd(3, '0').take(3).toInt()
    return runCatching {
        GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            isLenient = false
            clear()
            set(parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt(),
                parts[4].ifEmpty { "0" }.toInt(), parts[5].ifEmpty { "0" }.toInt(), parts[6].ifEmpty { "0" }.toInt())
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis - offsetMinutes * 60_000L
    }.getOrNull()
}

internal fun sortHomeVideos(videos: List<Video>, sort: String, server: String): List<Video> {
    val dated = deduplicateVideos(videos, server).map { video -> video to video.publishedAt?.let(::parsePeerTubeDate) }
    return when (sort) {
        "oldest" -> dated.sortedWith(compareBy<Pair<Video, Long?>> { it.second == null }.thenBy { it.second })
        "views" -> dated.sortedWith(compareByDescending<Pair<Video, Long?>> { it.first.views }.thenByDescending { it.second })
        else -> dated.sortedWith(compareBy<Pair<Video, Long?>> { it.second == null }.thenByDescending { it.second })
    }.map { it.first }
}
