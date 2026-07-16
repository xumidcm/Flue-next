package com.flue.launcher.applist.model

import java.util.Collections

@ConsistentCopyVisibility
data class AppListSnapshot private constructor(
    val generation: Long,
    val entries: List<AppListEntry>,
) {
    companion object {
        fun create(
            generation: Long,
            entries: List<AppListEntry>,
        ): AppListSnapshot {
            require(generation >= 0L) {
                "Snapshot generation must not be negative"
            }

            val ownedEntries = entries.map { entry ->
                entry.copy(folderChildren = immutableCopy(entry.folderChildren))
            }
            require(ownedEntries.map(AppListEntry::key).distinct().size == ownedEntries.size) {
                "App list snapshot keys must be unique"
            }

            return AppListSnapshot(
                generation = generation,
                entries = immutableCopy(ownedEntries),
            )
        }

        private fun <T> immutableCopy(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}
