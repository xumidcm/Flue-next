package com.flue.launcher.applist.model

object AppListReducer {

    fun reduce(
        entries: List<AppListEntry>,
        command: AppListCommand.Move,
    ): List<AppListEntry> {
        if (command.key == command.beforeKey) return entries
        if (entries.map(AppListEntry::key).distinct().size != entries.size) return entries

        val sourceIndex = entries.indexOfFirst { it.key == command.key }
        if (sourceIndex == -1) return entries

        val beforeKey = command.beforeKey
        if (beforeKey != null && entries.none { it.key == beforeKey }) return entries
        if (beforeKey != null && entries.getOrNull(sourceIndex + 1)?.key == beforeKey) return entries
        if (beforeKey == null && sourceIndex == entries.lastIndex) return entries

        val result = entries.toMutableList()
        val movedEntry = result.removeAt(sourceIndex)
        val targetIndex = beforeKey
            ?.let { key -> result.indexOfFirst { it.key == key } }
            ?: result.size

        result.add(targetIndex, movedEntry)
        return result
    }
}
