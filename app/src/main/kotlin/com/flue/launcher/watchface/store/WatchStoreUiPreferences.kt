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

    private companion object {
        const val PREFERENCES_NAME = "flue_watch_store_ui"
        const val KEY_VIEW_MODE = "view_mode"
    }
}
