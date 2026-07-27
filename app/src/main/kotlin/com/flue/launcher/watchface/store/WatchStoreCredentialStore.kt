package com.flue.launcher.watchface.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 「记住账号 / 记住密码」的读取结果。字段为 null 表示对应项未保存。 */
data class WatchStoreSavedCredentials(
    val username: String?,
    val password: String?,
    val rememberUsername: Boolean,
    val rememberPassword: Boolean,
)

/**
 * 商店登录凭据的落盘岛，与 [WatchStoreAuthStore]（只存令牌）互补。
 *
 * - 记住账号：用户名按用户勾选明文保存。
 * - 记住密码：密码只允许经 Android Keystore 的 AES/GCM 密钥加密后落盘，
 *   严禁任何形式的明文密码写入磁盘；记住密码隐含记住账号。
 * - 密钥失效、密文损坏或加密失败时静默降级为「未记住密码」：清掉存量
 *   密文与开关，用户名保留（体验上等同于只勾选了记住账号）。
 *
 * 所有方法都可能做阻塞 IO / Keystore 调用，调用方需放到 Dispatchers.IO。
 */
class WatchStoreCredentialStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 读取已保存的凭据；解密失败时自动降级并返回降级后的状态。 */
    fun read(): WatchStoreSavedCredentials {
        val rememberUsername = preferences.getBoolean(KEY_REMEMBER_USERNAME, false)
        val rememberPassword = preferences.getBoolean(KEY_REMEMBER_PASSWORD, false)
        val username = preferences.getString(KEY_USERNAME, null)?.takeIf(String::isNotBlank)
        if (!rememberUsername || username == null) {
            // 开关与数据不一致（例如用户名丢失）时整体清空，避免半残状态。
            if (rememberUsername || rememberPassword || username != null) clear()
            return WatchStoreSavedCredentials(null, null, rememberUsername = false, rememberPassword = false)
        }
        if (!rememberPassword) {
            return WatchStoreSavedCredentials(username, null, rememberUsername = true, rememberPassword = false)
        }
        val password = decryptPassword(preferences.getString(KEY_PASSWORD_CIPHERTEXT, null))
        if (password == null) {
            downgradeToUsernameOnly()
            return WatchStoreSavedCredentials(username, null, rememberUsername = true, rememberPassword = false)
        }
        return WatchStoreSavedCredentials(username, password, rememberUsername = true, rememberPassword = true)
    }

    /**
     * 按两个开关持久化。记住密码隐含记住账号；两个开关都关时清空全部。
     * 加密失败时静默回退为只记住账号，绝不落明文。
     */
    fun persist(
        username: String,
        password: String,
        rememberUsername: Boolean,
        rememberPassword: Boolean,
    ) {
        if (!rememberUsername && !rememberPassword) {
            clear()
            return
        }
        val editor = preferences.edit()
            .putBoolean(KEY_REMEMBER_USERNAME, true)
            .putString(KEY_USERNAME, username)
        val ciphertext = if (rememberPassword) encryptPassword(password) else null
        if (ciphertext != null) {
            editor
                .putBoolean(KEY_REMEMBER_PASSWORD, true)
                .putString(KEY_PASSWORD_CIPHERTEXT, ciphertext)
        } else {
            editor
                .putBoolean(KEY_REMEMBER_PASSWORD, false)
                .remove(KEY_PASSWORD_CIPHERTEXT)
        }
        editor.apply()
    }

    /** 用户登出 / 主动清除时调用：抹掉用户名、密文与两个开关。 */
    fun clear() {
        preferences.edit()
            .remove(KEY_REMEMBER_USERNAME)
            .remove(KEY_REMEMBER_PASSWORD)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD_CIPHERTEXT)
            .apply()
    }

    private fun downgradeToUsernameOnly() {
        preferences.edit()
            .putBoolean(KEY_REMEMBER_PASSWORD, false)
            .remove(KEY_PASSWORD_CIPHERTEXT)
            .apply()
    }

    private fun encryptPassword(password: String): String? = runCatching {
        val key = obtainSecretKey() ?: return null
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        // 版本前缀 + IV + 密文；IV 由 Keystore 随机生成，随密文一同保存。
        buildString {
            append(CIPHERTEXT_VERSION)
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }.getOrNull()

    private fun decryptPassword(stored: String?): String? = runCatching {
        val payload = stored?.takeIf { it.startsWith(CIPHERTEXT_VERSION) } ?: return null
        val parts = payload.removePrefix(CIPHERTEXT_VERSION).split(':')
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        if (iv.isEmpty() || ciphertext.isEmpty()) return null
        val key = obtainSecretKey() ?: return null
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** 取现有密钥；没有则生成。任何 Keystore 故障返回 null（触发降级）。 */
    private fun obtainSecretKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
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
                generateKey()
            }
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "flue_watch_store_credentials"
        const val KEY_REMEMBER_USERNAME = "remember_username"
        const val KEY_REMEMBER_PASSWORD = "remember_password"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD_CIPHERTEXT = "password_ciphertext"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "flue_watch_store_password"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT_VERSION = "v1:"
        const val GCM_TAG_BITS = 128
    }
}
