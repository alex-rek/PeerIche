package com.arcticfoxapps.peeriche

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

internal object SubtitleCueAdapter {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    suspend fun detectLanguage(text: String): String = kotlinx.coroutines.withTimeoutOrNull(5_000) {
        val detector = com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
        try { awaitModelTask(detector.identifyLanguage(text.take(1000))).takeUnless { it == "und" }.orEmpty() }
        finally { detector.close() }
    }.orEmpty()
    suspend fun load(url: String, language: String): List<SpeechCue> = withContext(Dispatchers.IO) {
        val uri = java.net.URI(url)
        val text = if (uri.scheme == "file") File(uri).inputStream().use { readBounded(it) }
        else {
            require(uri.scheme == "https" && uri.host != null)
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Caption download failed" }
                requireNotNull(response.body).byteStream().use { readBounded(it) }
            }
        }
        parse(text, language)
    }
    private fun readBounded(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 5_000_000) { "Caption file too large" }
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }
    fun parse(raw: String, language: String): List<SpeechCue> = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n")).mapNotNull { block ->
            val lines = block.lines()
            if (lines.firstOrNull()?.trim()?.let { it.startsWith("NOTE") || it.startsWith("STYLE") || it.startsWith("REGION") } == true) return@mapNotNull null
            val timing = lines.indexOfFirst { it.contains("-->") }
            if (timing < 0) return@mapNotNull null
            val times = lines[timing].split("-->")
            val start = timestamp(times[0].trim()) ?: return@mapNotNull null
            val end = timestamp(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
            val text = clean(lines.drop(timing + 1).joinToString(" "))
            if (end <= start || text.isBlank()) null else SpeechCue(start, end, text, language)
        }.sortedBy { it.startMs }
    private fun timestamp(value: String): Long? = runCatching {
        val parts = value.replace(',', '.').split(':')
        require(parts.size in 2..3)
        val seconds = parts.last().split('.')
        require(seconds.size == 2 && seconds[1].length == 3)
        val mins = parts[parts.size - 2].toLong()
        val secs = seconds[0].toLong()
        require(mins in 0..59 && secs in 0..59)
        (if (parts.size == 3) parts[0].toLong() * 3_600_000 else 0) + mins * 60_000 + secs * 1000 + seconds[1].toLong()
    }.getOrNull()
    fun clean(text: String): String = text.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()
    fun current(cues: List<SpeechCue>, position: Long): SpeechCue? {
        val matches = cues.filter { position >= it.startMs && position < it.endMs }
        val newest = matches.maxOfOrNull { it.startMs } ?: return null
        val active = matches.filter { it.startMs == newest }
        return if (active.isEmpty()) null else SpeechCue(active.minOf { it.startMs }, active.minOf { it.endMs }, active.map { it.text }.distinct().joinToString(" "), active.first().language)
    }
}
