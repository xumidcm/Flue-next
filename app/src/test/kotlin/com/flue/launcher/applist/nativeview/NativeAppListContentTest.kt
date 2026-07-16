package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.IconKey
import com.flue.launcher.applist.model.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAppListContentTest {
    @Test
    fun rowsBindIconsByIconKeyWhileKeepingStableItemKeysAndGeneration() {
        val first = appEntry(
            key = "component:first",
            iconKey = "icon:shared-source:first",
        )
        val second = appEntry(
            key = "component:second",
            iconKey = "icon:missing",
        )
        val snapshot = AppListSnapshot.create(
            generation = 27L,
            entries = listOf(first, second),
        )

        val rows = bindNativeAppListRows(
            snapshot = snapshot,
            iconsByKey = mapOf(first.iconKey to "first-bitmap-token"),
        )

        assertEquals(listOf(first.key, second.key), rows.map { it.stableKey })
        assertEquals(listOf(27L, 27L), rows.map { it.snapshotGeneration })
        assertEquals("first-bitmap-token", rows[0].icon)
        assertNull(rows[1].icon)
    }

    private fun appEntry(
        key: String,
        iconKey: String,
    ): AppListEntry = AppListEntry(
        key = AppItemKey(key),
        label = key,
        type = AppItemType.APP,
        launchTarget = LaunchTarget.Component(
            packageName = "com.example",
            className = key,
        ),
        iconKey = IconKey(iconKey),
        folderChildren = emptyList(),
    )
}
