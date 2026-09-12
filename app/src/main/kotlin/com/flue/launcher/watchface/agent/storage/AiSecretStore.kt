package com.flue.launcher.watchface.agent.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secret values are kept outside provider metadata and are never logged. */
interface AiSecretStore {
    fun read(providerId: String): String?
    fun write(providerId: String, value: String): Boolean
    fun remove(providerId: String)
}

/** Useful for JVM tests; production code should use [AndroidKeystoreSecretStore]. */
class InMemoryAiSecretStore : AiSecretStore {
    private val values = linkedMapOf<String, String>()

    @Synchronized override fun read(providerId: String): String? = values[providerId]

    @Synchronized override fun write(providerId: String, value: String): Boolean {
        if (value.isBlank()) values.remove(providerId) else values[providerId] = value
        return true
    }

    @Synchronized override fun remove(providerId: String) {
        values.remove(providerId)
    }
}

/** AES/GCM ciphertext in preferences, with the AES key held by Android Keystore. */
class AndroidKeystoreSecretStore(context: Context) : AiSecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized override fun read(providerId: String): String? {
        val stored = preferences.getString(key(providerId), null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull() ?: run {
            // A reset/invalidated Keystore key must not leave an unusable ciphertext around.
            preferences.edit().remove(key(providerId)).apply()
            null
        }
    }

    @Synchronized override fun write(providerId: String, value: String): Boolean {
        if (value.isBlank()) {
            remove(providerId)
            return true
        }
        val ciphertext = runCatching { encrypt(value) }.getOrNull() ?: return false
        preferences.edit().putString(key(providerId), ciphertext).apply()
        return true
    }

    @Synchronized override fun remove(providerId: String) {
        preferences.edit().remove(key(providerId)).apply()
    }

    private fun key(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(providerId.toByteArray(Charsets.UTF_8))
        return "secret_" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, obtainKey()) }
        return VERSION + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        require(value.startsWith(VERSION))
        val parts = value.removePrefix(VERSION).split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
        }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val PREFERENCES = "flue_agent_secret_store"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "flue_agent_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1:"
        const val TAG_BITS = 128
    }
}

typealias KeystoreAiSecretStore = AndroidKeystoreSecretStore
