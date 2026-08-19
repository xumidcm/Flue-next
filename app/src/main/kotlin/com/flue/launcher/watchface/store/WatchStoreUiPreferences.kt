package com.flue.launcher.watchface.store

import android.content.Context
import android.content.SharedPreferences

/** Presentation mode of the public store browse page. */
enum class WatchStoreViewMode {
    LIST,
    GRID;

    companion object {
        /** Unknown/corrupt stored values silently fall back to the list view. */
        fun fromStorage(raw: String?): WatchStoreViewMode =
            if (raw == GRID.name) GRID else LIST
    }
}

/**
 * Small store-local UI preference island. The store deliberately does not talk
 * to the launcher-wide settings pipeline for pure presentation choices.
 */
class WatchStoreUiPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readViewMode(): WatchStoreViewMode =
        WatchStoreViewMode.fromStorage(preferences.getString(KEY_VIEW_MODE, null))

    fun writeViewMode(mode: WatchStoreViewMode) {
        preferences.edit().putString(KEY_VIEW_MODE, mode.name).apply()
    }

    fun readBaseUrl(): String = runCatching {
        WatchStoreUrlPolicy.normalizeBaseUrl(
            preferences.getString(KEY_BASE_URL, null) ?: DEFAULT_WATCH_STORE_BASE_URL,
        ).toASCIIString()
    }.getOrDefault(DEFAULT_WATCH_STORE_BASE_URL)

    /** Saves a normalized HTTP or HTTPS market base URL and returns it. */
    fun writeBaseUrl(raw: String): String {
        val normalized = WatchStoreUrlPolicy.normalizeBaseUrl(raw).toASCIIString()
        preferences.edit().putString(KEY_BASE_URL, normalized).apply()
        return normalized
    }

    fun resetBaseUrl() {
        preferences.edit().remove(KEY_BASE_URL).apply()
    }

    /**
     * 内置 WebView 的显示缩放（百分比，H6）。
     *
     * 这一项**要限范围**（与画布数值输入那条「不限量程」相反）：WebView 的缩放超出
     * 25–250 就直接排版崩坏，而 25 / 250 正是老师给的界。
     */
    fun readWebZoomPercent(): Int =
        normalizeWebZoomPercent(preferences.getInt(KEY_WEB_ZOOM, DEFAULT_WEB_ZOOM_PERCENT))

    fun writeWebZoomPercent(percent: Int): Int {
        val normalized = normalizeWebZoomPercent(percent)
        preferences.edit().putInt(KEY_WEB_ZOOM, normalized).apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "flue_watch_store_ui"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_BASE_URL = "base_url"
        const val KEY_WEB_ZOOM = "web_zoom_percent"
    }
}

const val MIN_WEB_ZOOM_PERCENT: Int = 25
const val MAX_WEB_ZOOM_PERCENT: Int = 250
const val DEFAULT_WEB_ZOOM_PERCENT: Int = 100

/** 夹到 25..250。存量脏数据与用户输入都走这一道，纯函数便于单测。 */
fun normalizeWebZoomPercent(raw: Int): Int =
    raw.coerceIn(MIN_WEB_ZOOM_PERCENT, MAX_WEB_ZOOM_PERCENT)
