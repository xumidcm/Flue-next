package com.flue.launcher.applist.model

@JvmInline
value class AppItemKey(val value: String) {
    init {
        require(value.trim().isNotEmpty()) {
            "App item key must not be empty or blank"
        }
    }
}

enum class AppItemType {
    APP,
    SHORTCUT,
    FOLDER,
}

sealed interface LaunchTarget {
    data class Component(
        val packageName: String,
        val className: String,
    ) : LaunchTarget

    data class IntentUri(
        val uri: String,
    ) : LaunchTarget
}

@JvmInline
value class IconKey(val value: String)

data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && x in 0f..1f) {
            "Normalized x must be finite and in 0..1"
        }
        require(y.isFinite() && y in 0f..1f) {
            "Normalized y must be finite and in 0..1"
        }
    }
}

data class AppListEntry(
    val key: AppItemKey,
    val label: String,
    val type: AppItemType,
    val launchTarget: LaunchTarget?,
    val iconKey: IconKey,
    val folderChildren: List<AppItemKey>,
)

sealed interface AppListCommand {
    data class Open(
        val key: AppItemKey,
        val origin: NormalizedPoint,
    ) : AppListCommand

    data class Move(
        val key: AppItemKey,
        val beforeKey: AppItemKey?,
    ) : AppListCommand

    data class CreateFolder(
        val source: AppItemKey,
        val target: AppItemKey,
    ) : AppListCommand

    data class Hide(
        val key: AppItemKey,
    ) : AppListCommand

    data class RemoveShortcut(
        val key: AppItemKey,
    ) : AppListCommand

    data class RenameFolder(
        val key: AppItemKey,
        val name: String,
    ) : AppListCommand

    data class DissolveFolder(
        val key: AppItemKey,
    ) : AppListCommand
}
