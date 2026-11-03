package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val LocalMetadataTranslationPaused = staticCompositionLocalOf { false }

internal suspend fun <T> awaitModelTask(task: Task<T>): T = withTimeout(600_000) {
    suspendCancellableCoroutine { continuation ->
        task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            .addOnCanceledListener { continuation.cancel() }
    }
}

object LocalTranslation {
    private val semaphore = Semaphore(2)
    private val subtitleMutex = Mutex()
    private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }
    val status = MutableStateFlow("")
    val modelRevision = MutableStateFlow(0)
    fun modelsChanged() { modelRevision.value++ }
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    suspend fun downloaded(): Set<String> = awaitModelTask(RemoteModelManager.getInstance()
        .getDownloadedModels(TranslateRemoteModel::class.java)).map { it.language }.toSet()
    suspend fun download(language: String, wifi: Boolean) {
        status.value = "downloading"
        try {
            val conditions = DownloadConditions.Builder().apply { if (wifi) requireWifi() }.build()
            awaitModelTask(RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(language).build(), conditions))
            status.value = "ready"
            modelsChanged()
        } catch (cancelled: CancellationException) { status.value = ""; throw cancelled
        } catch (error: Exception) { status.value = "error"; throw error }
    }
    suspend fun delete(language: String) {
        awaitModelTask(RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()))
        synchronized(cache) { cache.clear() }
        status.value = "ready"
    }

    suspend fun translate(context: Context, text: String, sourceHint: String? = null): String {
        if (text.isBlank()) return text
        val options = AppPreferences.get(context).options.value
        val target = TranslateLanguage.fromLanguageTag(options.translateTo) ?: return text
        val key = target + ":" + text
        synchronized(cache) { cache[key] }?.let { return it }
        return semaphore.withPermit { withContext(Dispatchers.IO) {
            val detector = LanguageIdentification.getClient()
            val source = try { TranslateLanguage.fromLanguageTag(sourceHint.orEmpty())
                ?: TranslateLanguage.fromLanguageTag(Tasks.await(detector.identifyLanguage(text.take(1000)), 20, TimeUnit.SECONDS)) }
                finally { detector.close() }
            if (source == null || source == target) return@withContext text
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
            try {
                status.value = "downloading"
                val conditions = DownloadConditions.Builder().apply { if (options.modelsWifiOnly) requireWifi() }.build()
                awaitModelTask(translator.downloadModelIfNeeded(conditions))
                status.value = "translating"
                val result = text.chunked(1000).joinToString("") {
                    Tasks.await(translator.translate(it), 30, TimeUnit.SECONDS)
                }
                synchronized(cache) { cache[key] = result }
                status.value = "ready"
                result
            } catch (error: Exception) { status.value = "error"; throw error }
            finally { translator.close() }
        } }
    }

    suspend fun subtitle(context: Context, caption: VideoCaption, server: String): File? = subtitleMutex.withLock { withContext(Dispatchers.IO) {
        val options = AppPreferences.get(context).options.value
        val url = caption.resolvedUrl(server) ?: return@withContext null
        if (caption.language?.id?.substringBefore('-') == options.translateTo) return@withContext null
        val hash = MessageDigest.getInstance("SHA-256").digest((url + options.translateTo).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val target = File(context.cacheDir, "translated-subtitles/$hash.vtt")
        if (target.isFile) return@withContext target
        val raw = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 5_000_000)
            body.byteStream().use { input ->
                val bytes = input.readBytesLimited(5_000_000)
                bytes.toString(Charsets.UTF_8)
            }
        }
        val detector = LanguageIdentification.getClient()
        val source = try { TranslateLanguage.fromLanguageTag(caption.language?.id.orEmpty())
            ?: TranslateLanguage.fromLanguageTag(Tasks.await(detector.identifyLanguage(raw.replace(Regex("<[^>]*>"), "").take(1000)), 20, TimeUnit.SECONDS)) }
            finally { detector.close() }
        require(source != null) { "Unknown subtitle language" }
        val destination = requireNotNull(TranslateLanguage.fromLanguageTag(options.translateTo))
        if (source == destination) return@withContext null
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(destination).build())
        val translated = try {
            status.value = "downloading"
            val conditions = DownloadConditions.Builder().apply { if (options.modelsWifiOnly) requireWifi() }.build()
            awaitModelTask(translator.downloadModelIfNeeded(conditions))
            status.value = "translating"
            translateVttPayloads(raw) { text ->
                if (text.isBlank()) text else Tasks.await(translator.translate(text), 30, TimeUnit.SECONDS)
            }.also { status.value = "ready" }
        } catch (error: Exception) { status.value = "error"; throw error }
        finally { translator.close() }
        target.parentFile?.mkdirs()
        val partial = File.createTempFile("subtitle-", ".part", target.parentFile)
        try {
            partial.writeText(translated)
            check(partial.renameTo(target) || target.isFile) { "Cannot save translated subtitles" }
        } finally { partial.delete() }
        target
    } }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Subtitle file too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal suspend fun translateVttPayloads(raw: String, translate: suspend (String) -> String): String {
    val blocks = raw.removePrefix("\uFEFF").replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
    val result = blocks.map { block ->
        val lines = block.lines()
        val timing = lines.indexOfFirst { it.contains(" --> ") || it.contains("-->") }
        if (timing < 0 || lines.firstOrNull()?.trimStart()?.let { it.startsWith("NOTE") || it.startsWith("STYLE") || it.startsWith("REGION") || it.startsWith("WEBVTT") } == true) block
        else {
            val text = lines.drop(timing + 1).joinToString("\n").replace(Regex("<[^>]*>"), "")
            lines.take(timing + 1).joinToString("\n") + "\n" + translate(text)
        }
    }.joinToString("\n\n")
    return if (result.startsWith("WEBVTT")) result else "WEBVTT\n\n$result"
}

@Composable internal fun translatedMetadata(text: String): String {
    val context = LocalContext.current
    val options by AppPreferences.get(context).options.collectAsState()
    val revision by LocalTranslation.modelRevision.collectAsState()
    val paused = LocalMetadataTranslationPaused.current
    val translated by produceState(text, text, options.translateMetadata, options.translateTo, revision, paused) {
        value = text
        if (options.translateMetadata && !paused) try { value = LocalTranslation.translate(context, text) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = text }
    }
    return translated
}
