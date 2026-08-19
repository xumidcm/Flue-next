package com.flue.launcher.watchface.store

import android.content.Context
import android.content.SharedPreferences

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
)

/**
 * SharedPreferences-backed token island for the community store. Only the JWT,
 * the username and a conservative local expiry are stored. The local expiry is
 * one hour earlier than the server's 7-day token lifetime, so an almost-expired
 * token never starts a large upload that is doomed to fail with 401.
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
        )
    }

    fun write(
        token: String,
        username: String,
        loginAtEpochMs: Long = System.currentTimeMillis(),
    ): WatchStoreAuthSession {
        val session = WatchStoreAuthSession(
            token = token,
            username = username,
            expiresAtEpochMs = loginAtEpochMs + TOKEN_LIFETIME_MS - EXPIRY_SAFETY_MARGIN_MS,
        )
        preferences.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USERNAME, session.username)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochMs)
            .apply()
        return session
    }

    /**
     * 把 `/my/me` 的资料写进当前会话。
     *
     * 没有会话时什么都不做 —— 资料属于某一次登录，不该独立存在。
     */
    fun writeProfile(account: WatchStoreAccount, nowEpochMs: Long = System.currentTimeMillis()) {
        val session = read(nowEpochMs) ?: return
        // 用户名理论上不会变，但换号之后旧资料绝不能留在新会话上。
        if (!account.username.equals(session.username, ignoreCase = false)) return
        preferences.edit()
            .putString(KEY_DESCRIPTION, account.description)
            .putString(KEY_TIMEZONE, account.timezone)
            .putString(KEY_LANGUAGE, account.language)
            .apply {
                if (account.avatarUrl.isNullOrBlank()) {
                    remove(KEY_AVATAR_URL)
                } else {
                    putString(KEY_AVATAR_URL, account.avatarUrl)
                }
            }
            .apply()
    }

    /** 只更新头像地址（上传完头像之后）。 */
    fun writeAvatarUrl(avatarUrl: String?) {
        preferences.edit()
            .apply {
                if (avatarUrl.isNullOrBlank()) remove(KEY_AVATAR_URL) else putString(KEY_AVATAR_URL, avatarUrl)
            }
            .apply()
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

        /** The service issues 7-day JWTs. */
        const val TOKEN_LIFETIME_MS = 7L * 24L * 60L * 60L * 1_000L

        /** Treat tokens as dead one hour early. */
        const val EXPIRY_SAFETY_MARGIN_MS = 60L * 60L * 1_000L
    }
}
