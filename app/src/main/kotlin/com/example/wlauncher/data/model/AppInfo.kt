package com.flue.launcher.data.model

import androidx.compose.ui.graphics.ImageBitmap

const val BUILTIN_SETTINGS_ENTRY_PACKAGE = "com.flue.launcher.internal"
const val BUILTIN_SETTINGS_ENTRY_ACTIVITY = "builtin.SettingsEntry"
const val BUILTIN_SETTINGS_ENTRY_COMPONENT = "$BUILTIN_SETTINGS_ENTRY_PACKAGE/$BUILTIN_SETTINGS_ENTRY_ACTIVITY"
const val APP_LIST_SHORTCUT_KEY_PREFIX = "shortcut:"
const val APP_LIST_FOLDER_KEY_PREFIX = "folder:"

enum class AppListItemType {
    APP,
    SHORTCUT,
    FOLDER
}

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val cachedIcon: ImageBitmap,
    val cachedBlurredIcon: ImageBitmap,
    val cachedPlainIcon: ImageBitmap? = null,
    val cachedPlainBlurredIcon: ImageBitmap? = null,
    val isBuiltInSettingsEntry: Boolean = false,
    val itemType: AppListItemType = AppListItemType.APP,
    val shortcutIntentUri: String? = null,
    val pinnedShortcutId: String? = null,
    val folderItemKeys: List<String> = emptyList()
) {
    val componentKey: String
        get() = when {
            isBuiltInSettingsEntry -> BUILTIN_SETTINGS_ENTRY_COMPONENT
            itemType == AppListItemType.SHORTCUT -> "$APP_LIST_SHORTCUT_KEY_PREFIX$activityName"
            itemType == AppListItemType.FOLDER -> "$APP_LIST_FOLDER_KEY_PREFIX$activityName"
            else -> "$packageName/$activityName"
        }

    val isAppListShortcut: Boolean
        get() = itemType == AppListItemType.SHORTCUT

    val isFolder: Boolean
        get() = itemType == AppListItemType.FOLDER
}

/** 图标的显示用位图。[blurred] 走的是预渲染的柔化版，不是运行时模糊。 */
fun AppInfo.iconForDisplay(blurred: Boolean = false): ImageBitmap {
    return if (blurred) cachedBlurredIcon else cachedIcon
}

/** 不带圆形遮罩的原样图标，供 MD3 密集蜂窝那类自己裁形状的排布用。 */
fun AppInfo.plainIconForDisplay(blurred: Boolean = false): ImageBitmap {
    return if (blurred) {
        cachedPlainBlurredIcon ?: cachedBlurredIcon
    } else {
        cachedPlainIcon ?: cachedIcon
    }
}
