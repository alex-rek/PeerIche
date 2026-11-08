package com.arcticfoxapps.peeriche

import android.content.Context
import android.graphics.*
import android.net.Uri
import coil.ImageLoader
import coil.request.ImageRequest
import android.graphics.drawable.BitmapDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class BroadcastPreferences(private val context: Context) {
    private val settings = context.getSharedPreferences("broadcast-options", Context.MODE_PRIVATE)
    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    private fun file(id: String) = File(context.noBackupFilesDir, "broadcast-slate-${key(id)}.png")
    fun get(id: String): BroadcastOptions = runCatching {
        com.google.gson.Gson().fromJson(settings.getString(key(id), "{}"), BroadcastOptions::class.java)?.checked() ?: BroadcastOptions()
    }.getOrDefault(BroadcastOptions())
    fun save(id: String, options: BroadcastOptions) { settings.edit().putString(key(id), com.google.gson.Gson().toJson(options.checked())).apply() }
    fun hasImage(id: String) = file(id).exists()
    internal fun forget(id: String) { settings.edit().remove(key(id)).apply(); file(id).delete() }
    suspend fun removeImage(id: String) = withContext(Dispatchers.IO) { file(id).delete(); Unit }
    suspend fun saveImage(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AuthFailure("invalid_cover")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 10 * 1024 * 1024) throw AuthFailure("invalid_cover"); out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: throw AuthFailure("file_unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AuthFailure("invalid_cover")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw AuthFailure("invalid_cover")
        val atomic = android.util.AtomicFile(file(id)); val output = atomic.startWrite()
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
        finally { bitmap.recycle() }
    }
    suspend fun slate(owner: AccountSummary): Bitmap = withContext(Dispatchers.IO) {
        val own = file(owner.id)
        if (own.exists()) BitmapFactory.decodeFile(own.path)?.let { return@withContext it }
        owner.avatar?.let { url ->
            try {
                val response = coil.Coil.imageLoader(context).execute(ImageRequest.Builder(context).data(url).size(512).allowHardware(false).build())
                (response.drawable as? BitmapDrawable)?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)?.let { return@withContext it }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this); canvas.drawColor(Color.rgb(16, 23, 30))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 115, 70); textAlign = Paint.Align.CENTER; textSize = 180f }
            canvas.drawCircle(360f, 300f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(39, 49, 60) })
            canvas.drawText(owner.displayName.take(1).ifBlank { "P" }.uppercase(), 360f, 355f, paint)
            paint.textSize = 34f; paint.color = Color.WHITE
            canvas.drawText(owner.displayName.take(28), 360f, 550f, paint)
            paint.textSize = 28f; paint.color = Color.LTGRAY; canvas.drawText("PeerIche", 360f, 615f, paint)
        }
    }
}
