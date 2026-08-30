package com.flue.launcher.watchface.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject

/** A stored community-store login session. Passwords are never persisted. */
data class WatchStoreAuthSession(
    val token: String,
    val username: String,
    val expiresAtEpochMs: Long,
    /**
     * `GET /my/me` 拿到的资料，缓存下来只为**首屏不空**。
     *
     * 它是快照，不是真相：个人资料页照样要拉一次 `/my/me` 覆盖。存这几项的理由是手表上
     * 一次往返肉眼可见，先把上次的名字与头像画出来比转圈好。
     */
    val description: String = "",
    val timezone: String = "",
    val language: String = "",
    /** 相对 API 路径，取用前照样要过 URL 校验。 */
    val avatarUrl: String? = null,
    /** 头像内容的本地失效版本；URL 不变时也能强制重新取图。 */
    val avatarVersion: Long = 0L,
)

/**
 * SharedPreferences-backed token island for the community store. Only the JWT,
 * the username and the server-provided expiry are stored. Passwords never cross
 * this boundary.
 */
class WatchStoreAuthStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Returns the stored session, or null (and clears storage) once it is expired or corrupt. */
    fun read(nowEpochMs: Long = System.currentTimeMillis()): WatchStoreAuthSession? {
        val token = preferences.getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank)
        val username = preferences.getString(KEY_USERNAME, null)?.takeIf(String::isNotBlank)
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (token == null || username == null || expiresAt <= nowEpochMs) {
            if (token != null || username != null || expiresAt != 0L) clear()
            return null
        }
        return WatchStoreAuthSession(
            token = token,
            username = username,
            expiresAtEpochMs = expiresAt,
            description = preferences.getString(KEY_DESCRIPTION, null).orEmpty(),
            timezone = preferences.getString(KEY_TIMEZONE, null).orEmpty(),
            language = preferences.getString(KEY_LANGUAGE, null).orEmpty(),
            avatarUrl = preferences.getString(KEY_AVATAR_URL, null)?.takeIf(String::isNotBlank),
            avatarVersion = preferences.getLong(KEY_AVATAR_VERSION, 0L),
        )
    }

    fun write(
        token: String,
        username: String,
        loginAtEpochMs: Long = System.currentTimeMillis(),
    ): WatchStoreAuthSession {
        val sameUser = preferences.getString(KEY_USERNAME, null) == username
        val expiresAt = jwtExpiryEpochMs(token)
            ?: (loginAtEpochMs + TOKEN_LIFETIME_MS - EXPIRY_SAFETY_MARGIN_MS)
        val session = WatchStoreAuthSession(
            token = token,
            username = username,
            expiresAtEpochMs = expiresAt,
            description = if (sameUser) preferences.getString(KEY_DESCRIPTION, null).orEmpty() else "",
            timezone = if (sameUser) preferences.getString(KEY_TIMEZONE, null).orEmpty() else "",
            language = if (sameUser) preferences.getString(KEY_LANGUAGE, null).orEmpty() else "",
            avatarUrl = if (sameUser) {
                preferences.getString(KEY_AVATAR_URL, null)?.takeIf(String::isNotBlank)
            } else {
                null
            },
            avatarVersion = if (sameUser) preferences.getLong(KEY_AVATAR_VERSION, 0L) else 0L,
        )
        preferences.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USERNAME, session.username)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochMs)
            .putLong(KEY_AVATAR_VERSION, session.avatarVersion)
            .apply {
                if (!sameUser) {
                    remove(KEY_DESCRIPTION)
                    remove(KEY_TIMEZONE)
                    remove(KEY_LANGUAGE)
                    remove(KEY_AVATAR_URL)
                }
            }
            .apply()
        return session
    }

    /**
     * 把 `/my/me` 的资料写进当前会话。
     *
     * 没有会话时什么都不做 —— 资料属于某一次登录，不该独立存在。
     */
    fun writeProfile(
        account: WatchStoreAccount,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): WatchStoreAuthSession? {
        val session = read(nowEpochMs) ?: return null
        // 用户名理论上不会变，但换号之后旧资料绝不能留在新会话上。
        if (!account.username.equals(session.username, ignoreCase = false)) return null
        val nextAvatarVersion = session.avatarVersion + 1L
        preferences.edit()
            .putString(KEY_DESCRIPTION, account.description)
            .putString(KEY_TIMEZONE, account.timezone)
            .putString(KEY_LANGUAGE, account.language)
            .putLong(KEY_AVATAR_VERSION, nextAvatarVersion)
            .apply {
                if (account.avatarUrl.isNullOrBlank()) {
                    remove(KEY_AVATAR_URL)
                } else {
                    putString(KEY_AVATAR_URL, account.avatarUrl)
                }
            }
            .apply()
        return read(nowEpochMs)
    }

    /** 只更新头像地址（上传完头像之后），即使地址不变也递增版本。 */
    fun writeAvatarUrl(avatarUrl: String?): Long {
        val nextAvatarVersion = preferences.getLong(KEY_AVATAR_VERSION, 0L) + 1L
        preferences.edit()
            .putLong(KEY_AVATAR_VERSION, nextAvatarVersion)
            .apply {
                if (avatarUrl.isNullOrBlank()) remove(KEY_AVATAR_URL) else putString(KEY_AVATAR_URL, avatarUrl)
            }
            .apply()
        return nextAvatarVersion
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_DESCRIPTION)
            .remove(KEY_TIMEZONE)
            .remove(KEY_LANGUAGE)
            .remove(KEY_AVATAR_URL)
            .remove(KEY_AVATAR_VERSION)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "flue_watch_store_auth"
        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
        const val KEY_DESCRIPTION = "profile_description"
        const val KEY_TIMEZONE = "profile_timezone"
        const val KEY_LANGUAGE = "profile_language"
        const val KEY_AVATAR_URL = "profile_avatar_url"
        const val KEY_AVATAR_VERSION = "profile_avatar_version"

        /** The service issues 7-day JWTs. */
        const val TOKEN_LIFETIME_MS = 7L * 24L * 60L * 60L * 1_000L

        /** Treat tokens as dead one hour early. */
        const val EXPIRY_SAFETY_MARGIN_MS = 60L * 60L * 1_000L

        private fun jwtExpiryEpochMs(token: String): Long? = runCatching {
            val parts = token.split('.')
            require(parts.size == 3)
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val seconds = JSONObject(payload).optLong("exp", 0L)
            require(seconds > 0L)
            Math.multiplyExact(seconds, 1_000L)
        }.getOrNull()
    }
}
