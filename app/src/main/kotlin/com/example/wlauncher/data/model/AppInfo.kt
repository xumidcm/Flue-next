package com.flue.launcher.data.model

import androidx.compose.ui.graphics.ImageBitmap

const val BUILTIN_SETTINGS_ENTRY_PACKAGE = "com.flue.launcher.internal"
const val BUILTIN_SETTINGS_ENTRY_ACTIVITY = "builtin.SettingsEntry"
const val BUILTIN_SETTINGS_ENTRY_COMPONENT = "$BUILTIN_SETTINGS_ENTRY_PACKAGE/$BUILTIN_SETTINGS_ENTRY_ACTIVITY"

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val cachedIcon: ImageBitmap,
    val cachedBlurredIcon: ImageBitmap,
    val cachedTwoToneIcon: ImageBitmap? = null,
    val cachedTwoToneBlurredIcon: ImageBitmap? = null,
    val isBuiltInSettingsEntry: Boolean = false
) {
    val componentKey: String
        get() = if (isBuiltInSettingsEntry) {
            BUILTIN_SETTINGS_ENTRY_COMPONENT
        } else {
            "$packageName/$activityName"
        }
}

fun AppInfo.iconForDisplay(useTwoTone: Boolean, blurred: Boolean = false): ImageBitmap {
    if (useTwoTone) {
        val twoTone = if (blurred) cachedTwoToneBlurredIcon ?: cachedTwoToneIcon else cachedTwoToneIcon
        if (twoTone != null) return twoTone
    }
    return if (blurred) cachedBlurredIcon else cachedIcon
}
