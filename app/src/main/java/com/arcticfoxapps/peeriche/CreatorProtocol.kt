package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal data class CreatorMetadata(val name: String, val description: String, val channelId: Long, val privacy: Int, val nsfw: Boolean = false) {
    fun validate() {
        if (name.trim().length !in 3..120 || description.length > 10000 || channelId <= 0 || privacy !in 1..3) throw AuthFailure("invalid_publication")
        if (description.isNotBlank() && description.length < 3) throw AuthFailure("invalid_description")
    }
    fun json() = JsonObject().apply {
        validate(); addProperty("name", name.trim()); addProperty("channelId", channelId)
        // PeerTube validates a supplied description as 3..10000 characters.
        // An optional empty description must be omitted, for both upload and live.
        if (description.isNotBlank()) addProperty("description", description)
        addProperty("privacy", privacy); addProperty("nsfw", nsfw); addProperty("waitTranscoding", true)
        addProperty("commentsEnabled", true); addProperty("downloadEnabled", true)
    }
}

internal const val UPLOAD_PATH = "/api/v1/videos/upload-resumable"
/** A server-supplied continuation URL is never trusted as a destination for a bearer token. */
internal fun safeUploadUrl(server: String, location: String): String {
    val origin = authServerOrigin(server).toHttpUrl()
    val url = origin.resolve(location) ?: throw AuthFailure("invalid_upload_location")
    if (url.scheme != "https" || url.host != origin.host || url.port != origin.port || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.encodedPath != UPLOAD_PATH || url.fragment != null || url.queryParameterNames != setOf("upload_id") ||
        url.queryParameterValues("upload_id").size != 1 || url.queryParameter("upload_id").orEmpty().let { it.isBlank() || it.length > 512 || it.any { c -> c.isISOControl() } }) throw AuthFailure("invalid_upload_location")
    return url.toString()
}

internal class UploadOperation private constructor(val kind: String, val size: Long, val location: String = "", val offset: Long = 0,
    val bytes: ByteArray = byteArrayOf(), val metadata: JsonObject? = null, val mime: String = "video/mp4", val uuid: String = "") {
    companion object {
        fun init(size: Long, mime: String, filename: String, metadata: CreatorMetadata): UploadOperation {
            if (size <= 0 || !mime.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) || filename.isBlank() || filename.length > 255 || filename.any { it == '/' || it == '\\' || it.isISOControl() }) throw AuthFailure("invalid_file")
            return UploadOperation("init", size, metadata = metadata.json().apply { addProperty("filename", filename) }, mime = mime)
        }
        fun probe(location: String, size: Long) = UploadOperation("probe", size.also { require(it > 0) }, location)
        fun chunk(location: String, size: Long, offset: Long, bytes: ByteArray): UploadOperation {
            require(size > 0 && offset >= 0 && bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024 && offset <= size - bytes.size)
            return UploadOperation("chunk", size, location, offset, bytes)
        }
        fun cancel(location: String) = UploadOperation("cancel", 0, location)
        fun cover(uuid: String, bytes: ByteArray, mime: String): UploadOperation {
            require(uuid.matches(Regex("[a-zA-Z0-9_-]{1,128}")) && bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024 && mime in listOf("image/jpeg", "image/png", "image/webp"))
            return UploadOperation("cover", 0, bytes = bytes, mime = mime, uuid = uuid)
        }
    }
}

internal data class UploadReply(val code: Int, val location: String? = null, val offset: Long = 0, val videoUuid: String? = null)
internal fun uploadOffset(range: String?, total: Long, allowEmpty: Boolean = false): Long {
    if (range.isNullOrBlank()) return 0
    // node-uploadx 7 sends bytesWritten - 1 even for an empty session.
    // Accept its exact sentinel only for a zero-length status probe.
    if (allowEmpty && range.trim() == "bytes=0--1") return 0
    val last = Regex("bytes=0-([0-9]+)").matchEntire(range.trim())?.groupValues?.get(1)?.toLongOrNull() ?: throw AuthFailure("invalid_response")
    if (last < 0 || last >= total) throw AuthFailure("invalid_response")
    return last + 1
}
internal fun uploadedUuid(json: JsonObject): String = runCatching { json.getAsJsonObject("video").get("uuid").asString }
    .getOrNull()?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) } ?: throw AuthFailure("invalid_response")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun executeUpload(http: OkHttpClient, server: String, token: String, operation: UploadOperation): UploadReply = withContext(Dispatchers.IO) {
    val origin = authServerOrigin(server)
    val url = when (operation.kind) {
        "init" -> origin + UPLOAD_PATH
        "cover" -> "$origin/api/v1/videos/${operation.uuid}"
        else -> safeUploadUrl(server, operation.location)
    }
    val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
    val binary = "application/octet-stream".toMediaType()
    when (operation.kind) {
        "init" -> builder.header("X-Upload-Content-Length", operation.size.toString()).header("X-Upload-Content-Type", operation.mime)
            .post(operation.metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        "probe" -> builder.header("Content-Range", "bytes */${operation.size}").put(byteArrayOf().toRequestBody(binary))
        "chunk" -> builder.header("Content-Range", "bytes ${operation.offset}-${operation.offset + operation.bytes.size - 1}/${operation.size}").put(operation.bytes.toRequestBody(binary))
        "cancel" -> builder.header("Content-Length", "0").delete()
        "cover" -> builder.put(MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("thumbnailfile", "cover.${operation.mime.substringAfter('/').replace("jpeg", "jpg")}", operation.bytes.toRequestBody(operation.mime.toMediaType())).build())
        else -> throw AuthFailure("invalid_response")
    }
    val request = builder.build()
    val response = suspendCancellableCoroutine<Response> { continuation ->
        val call = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response, onCancellation = { response.close() }) }
        })
    }
    try { response.use { result ->
        val source = result.body?.source()
        if (source?.request(1_048_577) == true) throw AuthFailure("invalid_response")
        val text = source?.readUtf8().orEmpty()
        val json = runCatching { Gson().fromJson(text, JsonObject::class.java) }.getOrNull()
        val valid308 = result.code == 308 && operation.kind in listOf("probe", "chunk") && result.header("Location") == null
        if (!result.isSuccessful && !valid308) throw AuthFailure(when (result.code) {
            413 -> "upload_quota"; 415 -> "upload_format"; 404 -> "upload_expired"; 409 -> "upload_range"; 422 -> "upload_unreadable"
            else -> authErrorReason(result.code, json)
        }, result.code)
        when {
            operation.kind == "init" && result.code in listOf(200, 201) -> UploadReply(result.code, safeUploadUrl(origin, result.header("Location") ?: throw AuthFailure("invalid_upload_location")))
            valid308 -> UploadReply(308, offset = uploadOffset(result.header("Range"), operation.size, allowEmpty = operation.kind == "probe"))
            operation.kind in listOf("probe", "chunk") && result.code == 200 -> UploadReply(200, offset = operation.size, videoUuid = uploadedUuid(json ?: throw AuthFailure("invalid_response")))
            operation.kind in listOf("cancel", "cover") && result.code == 204 -> UploadReply(204)
            else -> throw AuthFailure("invalid_response")
        }
    } } catch (failure: AuthFailure) {
        if (failure.reason == "invalid_response") throw AuthFailure("upload_response", failure.status)
        throw failure
    }
}
