package com.flue.launcher.watchface.store

import android.content.Context
import android.content.SharedPreferences

/** A stored community-store login session. Passwords are never persisted. */
data class WatchStoreAuthSession(
    val token: String,
    val username: String,
    val expiresAtEpochMs: Long,
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
        return WatchStoreAuthSession(token = token, username = username, expiresAtEpochMs = expiresAt)
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

    fun clear() {
        preferences.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "flue_watch_store_auth"
        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_EXPIRES_AT = "expires_at_epoch_ms"

        /** The service issues 7-day JWTs. */
        const val TOKEN_LIFETIME_MS = 7L * 24L * 60L * 60L * 1_000L

        /** Treat tokens as dead one hour early. */
        const val EXPIRY_SAFETY_MARGIN_MS = 60L * 60L * 1_000L
    }
}
