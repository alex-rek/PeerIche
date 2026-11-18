package com.arcticfoxapps.peeriche

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class ProxySettingsStore(context: Context, fileName: String = "network-proxy.bin",
    private val alias: String = "peeriche.network-proxy.v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun load(): ProxyConfiguration {
        val bytes = try { file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 32 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
            return ProxyConfiguration()
        }
        val plain = AccountCipher.decrypt(bytes, key())
        return try { (Gson().fromJson(plain.toString(Charsets.UTF_8), ProxyConfiguration::class.java) ?: error("Invalid proxy settings")).checked() }
        finally { plain.fill(0) }
    }
    fun save(config: ProxyConfiguration) {
        val plain = Gson().toJson(config.checked()).toByteArray(Charsets.UTF_8)
        val encrypted = try { AccountCipher.encrypt(plain, key()) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun clear() { file.delete(); check(!file.baseFile.exists()) }
}

internal data class ProxyNetworkState(val config: ProxyConfiguration = ProxyConfiguration(), val storageError: Boolean = false)

internal object ProxyNetwork {
    private val mutable = MutableStateFlow(ProxyNetworkState())
    val state = mutable.asStateFlow()
    private val revisionState = MutableStateFlow(0L)
    val revision = revisionState.asStateFlow()
    private var store: ProxySettingsStore? = null
    private val base by lazy { OkHttpClient() }
    private val router = ProxyRouter({ mutable.value.config }, { !mutable.value.storageError })

    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        val storage = ProxySettingsStore(context.applicationContext)
        store = storage
        mutable.value = try { ProxyNetworkState(storage.load()) } catch (_: Exception) { ProxyNetworkState(storageError = true) }
        java.net.Authenticator.setDefault(SocksProxyAuthenticator { mutable.value.config })
    }
    fun calls(client: OkHttpClient = base): Call.Factory = router.calls(client)
    fun newCall(client: OkHttpClient, request: Request): Call = router.client(client).newCall(request)
    suspend fun save(config: ProxyConfiguration) = withContext(Dispatchers.IO) {
        val checked = config.checked()
        checkNotNull(store).save(checked)
        if (mutable.value != ProxyNetworkState(checked)) {
            mutable.value = ProxyNetworkState(checked); revisionState.value++
        }
    }
    suspend fun reset() = withContext(Dispatchers.IO) {
        checkNotNull(store).clear()
        mutable.value = ProxyNetworkState(); revisionState.value++
    }
}
