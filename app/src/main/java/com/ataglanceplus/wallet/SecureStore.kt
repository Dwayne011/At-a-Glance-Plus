package com.ataglanceplus.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets and synced Wallet data, encrypted with a key held in the phone's Android Keystore (it
 * never leaves the phone): small values in their own files, and whole files of data.
 */
object SecureStore {

    private const val ALIAS = "wallet_secure_store"
    private const val DIR = "secure"

    private fun file(context: Context, name: String) = File(File(context.filesDir, DIR).apply { mkdirs() }, name)

    fun putString(context: Context, name: String, value: String?) {
        if (value == null) file(context, name).delete() else write(context, name, value.toByteArray())
    }

    fun getString(context: Context, name: String): String? = read(context, name)?.let { String(it) }

    fun write(context: Context, name: String, data: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(data)
        val target = file(context, name)
        val temp = File(target.path + ".tmp")
        temp.writeBytes(sealed)
        temp.renameTo(target)
    }

    fun read(context: Context, name: String): ByteArray? = runCatching {
        val bytes = file(context, name).takeIf { it.exists() }?.readBytes() ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
            .doFinal(bytes, 12, bytes.size - 12)
    }.getOrNull()

    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unbase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}
