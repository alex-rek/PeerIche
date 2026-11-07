package com.arcticfoxapps.peeriche

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object AccountCipher {
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == 12)
        return byteArrayOf(1) + iv + cipher.doFinal(plain)
    }
    fun decrypt(encrypted: ByteArray, key: SecretKey): ByteArray {
        require(encrypted.size >= 29 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        return cipher.doFinal(encrypted, 13, encrypted.size - 13)
    }
}

internal class EncryptedAccountStore(context: Context, fileName: String = "account-sessions.bin",
    private val alias: String = "peeriche.account-sessions.v1") : AccountSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val gson = Gson()
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun load(): StoredAccounts = withContext(Dispatchers.IO) {
        val secret = key() // Check Keystore before attempting a login, including a fresh install.
        val input = try { file.openRead() } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) throw missing
            return@withContext StoredAccounts()
        }
        val encrypted = input.use { stream ->
            val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer); if (count < 0) break
                require(result.size() + count <= 1_048_576)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        val plain = AccountCipher.decrypt(encrypted, secret)
        try { gson.fromJson(plain.toString(Charsets.UTF_8), StoredAccounts::class.java) ?: throw AuthFailure("storage_error") }
        finally { plain.fill(0) }
    }
    override suspend fun save(accounts: StoredAccounts) = withContext(Dispatchers.IO) {
        val plain = gson.toJson(accounts).toByteArray(Charsets.UTF_8)
        val encrypted = try { AccountCipher.encrypt(plain, key()) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        check(!file.baseFile.exists())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        Unit
    }
}

internal object PeerTubeAccounts {
    @Volatile private var instance: AccountSessionManager? = null
    fun get(context: Context): AccountSessionManager = instance ?: synchronized(this) {
        instance ?: AccountSessionManager(EncryptedAccountStore(context.applicationContext), PeerTubeAuthTransport()).also { instance = it }
    }
}
