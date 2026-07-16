package com.flue.launcher.applist.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppListSnapshotTest {

    @Test
    fun `snapshot rejects duplicate stable keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppListSnapshot.create(
                generation = 1L,
                entries = entries("a", "b", "b"),
            )
        }
    }

    @Test
    fun `snapshot owns copies of the entry list and folder children`() {
        val childKeys = mutableListOf(AppItemKey("child:a"))
        val sourceEntries = mutableListOf(
            AppListEntry(
                key = AppItemKey("folder"),
                label = "Folder",
                type = AppItemType.FOLDER,
                launchTarget = null,
                iconKey = IconKey("icon:folder"),
                folderChildren = childKeys,
            ),
        )

        val snapshot = AppListSnapshot.create(generation = 7L, entries = sourceEntries)
        childKeys += AppItemKey("child:b")
        sourceEntries.clear()

        assertEquals(7L, snapshot.generation)
        assertEquals(listOf("folder"), snapshot.entries.map { it.key.value })
        assertEquals(
            listOf("child:a"),
            snapshot.entries.single().folderChildren.map { it.value },
        )
    }

    private fun entries(vararg values: String): List<AppListEntry> = values.map { value ->
        AppListEntry(
            key = AppItemKey(value),
            label = value,
            type = AppItemType.APP,
            launchTarget = null,
            iconKey = IconKey("icon:$value"),
            folderChildren = emptyList(),
        )
    }
}
