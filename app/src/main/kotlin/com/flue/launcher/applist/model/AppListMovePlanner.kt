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

        val reordered = keys.toMutableList()
        val movedKey = reordered.removeAt(fromIndex)
        reordered.add(toIndex, movedKey)
        val movedIndex = reordered.indexOf(movedKey)

        return AppListCommand.Move(
            key = movedKey,
            beforeKey = reordered.getOrNull(movedIndex + 1),
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
