package com.arcticfoxapps.peeriche

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun releaseVersion(tag: String): List<Int>? {
    val parts = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(tag)?.groupValues?.drop(1) ?: return null
    return parts.map { it.toIntOrNull() ?: return null }
}

internal fun newerVersion(candidate: String, current: String): Boolean {
    val next = releaseVersion(candidate) ?: return false
    val old = releaseVersion(current) ?: return false
    return next.zip(old).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

private data class AppRelease(val version: String, val url: String, val checksum: String)

private object GitHubUpdates {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()
    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "PeerIche-Android").build()
    private fun getText(url: String): String = client.newCall(request(url)).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        requireNotNull(it.body).string()
    }
    suspend fun check(context: Context): AppRelease? = withContext(Dispatchers.IO) {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val releases = JsonParser.parseString(getText("https://api.github.com/repos/alex-rek/PeerIche/releases?per_page=30")).asJsonArray
        releases.mapNotNull { element ->
            val release = element.asJsonObject
            val tag = release.get("tag_name").asString
            if (release.get("draft").asBoolean || !newerVersion(tag, current)) return@mapNotNull null
            val assets = release.getAsJsonArray("assets").map { it.asJsonObject }
            val apk = assets.firstOrNull { it.get("name").asString == "PeerIche-${tag.removePrefix("v")}.apk" } ?: return@mapNotNull null
            val url = apk.get("browser_download_url").asString
            if (!url.startsWith("https://github.com/alex-rek/PeerIche/releases/download/")) return@mapNotNull null
            val digest = apk.get("digest")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val hash = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else {
                val sidecar = assets.firstOrNull { it.get("name").asString == apk.get("name").asString + ".sha256" } ?: return@mapNotNull null
                val hashUrl = sidecar.get("browser_download_url").asString
                if (!hashUrl.startsWith("https://github.com/alex-rek/PeerIche/releases/download/")) return@mapNotNull null
                getText(hashUrl).trim().substringBefore(' ')
            }
            if (!hash.matches(Regex("[a-fA-F0-9]{64}"))) return@mapNotNull null
            AppRelease(tag.removePrefix("v"), url, hash)
        }.maxWithOrNull(compareBy<AppRelease> { releaseVersion(it.version)!![0] }
            .thenBy { releaseVersion(it.version)!![1] }.thenBy { releaseVersion(it.version)!![2] })
    }

    @Suppress("DEPRECATION")
    suspend fun download(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "update.apk")
        val partial = File(directory, "update.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request(release.url)).execute().use { response ->
                check(response.isSuccessful)
                val body = requireNotNull(response.body)
                require(body.contentLength() <= 200L * 1024 * 1024)
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 200L * 1024 * 1024)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                } }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash.equals(release.checksum, ignoreCase = true)) { "Checksum mismatch" }
            val manager = context.packageManager
            val archive = requireNotNull(manager.getPackageArchiveInfo(partial.path, PackageManager.GET_SIGNATURES))
            val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            require(archive.packageName == context.packageName && archive.versionName == release.version)
            require(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed))
            val expected = installed.signatures.orEmpty().map { it.toCharsString() }.toSet()
            require(expected.isNotEmpty() && expected == archive.signatures.orEmpty().map { it.toCharsString() }.toSet()) { "Signing certificate mismatch" }
            partial.copyTo(target, overwrite = true)
            target
        } finally { partial.delete() }
    }
}

@Composable internal fun AppUpdateFeature(manualRequest: Int) {
    val context = LocalContext.current
    var available by remember { mutableStateOf<AppRelease?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var downloadRequest by remember { mutableIntStateOf(0) }
    val preferences = remember { context.getSharedPreferences("updates", Context.MODE_PRIVATE) }
    LaunchedEffect(manualRequest) {
        if (manualRequest == 0 && System.currentTimeMillis() - preferences.getLong("last_check", 0) < 6 * 60 * 60 * 1000) return@LaunchedEffect
        try {
            val release = GitHubUpdates.check(context)
            preferences.edit().putLong("last_check", System.currentTimeMillis()).apply()
            available = release
            if (release == null && manualRequest > 0) message = R.string.update_current
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (manualRequest > 0) message = R.string.update_check_failed }
    }
    LaunchedEffect(downloadRequest) {
        if (downloadRequest == 0) return@LaunchedEffect
        val release = available ?: return@LaunchedEffect
        downloading = true
        try { ready = GitHubUpdates.download(context, release) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = R.string.update_download_failed }
        finally { downloading = false }
    }
    if (available != null) AlertDialog(
        onDismissRequest = { if (!downloading) { available = null; ready = null } },
        title = { Text(stringResource(R.string.update_available, available!!.version)) },
        text = { if (downloading) CircularProgressIndicator() else Text(stringResource(if (ready != null) R.string.update_ready else R.string.update_description)) },
        confirmButton = { TextButton(enabled = !downloading, onClick = {
            val apk = ready
            if (apk == null) downloadRequest++ else try {
                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
                } else {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            } catch (_: Exception) { message = R.string.update_install_failed }
        }) { Text(stringResource(if (ready != null) R.string.update_install else R.string.update_download)) } },
        dismissButton = { TextButton(enabled = !downloading, onClick = { available = null; ready = null }) { Text(stringResource(R.string.update_later)) } }
    )
    message?.let { resource -> AlertDialog(onDismissRequest = { message = null },
        title = { Text(stringResource(R.string.app_updates)) }, text = { Text(stringResource(resource)) },
        confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(android.R.string.ok)) } }) }
}
