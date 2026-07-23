package com.flue.launcher.applist.model

object AppListMovePlanner {

    fun fromKeys(
        keys: List<AppItemKey>,
        movedKey: AppItemKey,
        targetKey: AppItemKey,
    ): AppListCommand.Move? = fromIndexes(
        keys = keys,
        fromIndex = keys.indexOf(movedKey),
        toIndex = keys.indexOf(targetKey),
    )

    fun folderFromKeys(
        keys: List<AppItemKey>,
        sourceKey: AppItemKey,
        targetKey: AppItemKey,
    ): AppListCommand.CreateFolder? = folderFromIndexes(
        keys = keys,
        sourceIndex = keys.indexOf(sourceKey),
        targetIndex = keys.indexOf(targetKey),
    )

    fun fromIndexes(
        keys: List<AppItemKey>,
        fromIndex: Int,
        toIndex: Int,
    ): AppListCommand.Move? {
        if (fromIndex !in keys.indices || toIndex !in keys.indices || fromIndex == toIndex) {
            return null
        }

        val movedKey = keys[fromIndex]
        val beforeKey = when {
            toIndex == keys.lastIndex -> null
            fromIndex < toIndex -> keys[toIndex + 1]
            else -> keys[toIndex]
        }

        return AppListCommand.Move(
            key = movedKey,
            beforeKey = beforeKey,
        )
    }

    fun folderFromIndexes(
        keys: List<AppItemKey>,
        sourceIndex: Int,
        targetIndex: Int,
    ): AppListCommand.CreateFolder? {
        if (
            sourceIndex !in keys.indices ||
            targetIndex !in keys.indices ||
            sourceIndex == targetIndex
        ) {
            return null
        }

        return AppListCommand.CreateFolder(
            source = keys[sourceIndex],
            target = keys[targetIndex],
        )
    }

    fun validatedFolderTarget(
        pendingTargetIndex: Int?,
        currentTargetIndex: Int?,
        sourceIndex: Int,
    ): Int? = pendingTargetIndex?.takeIf { pending ->
        pending != sourceIndex && pending == currentTargetIndex
    }
}
