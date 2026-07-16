package com.flue.launcher.applist.model

import org.junit.Assert.assertSame
import org.junit.Test

class AppListReducerSafetyTest {

    @Test
    fun `duplicate stable keys reject a move instead of moving an arbitrary entry`() {
        val entries = entries("a", "b", "b", "c")

        val result = AppListReducer.reduce(
            entries,
            AppListCommand.Move(
                key = AppItemKey("b"),
                beforeKey = AppItemKey("a"),
            ),
        )

        assertSame(entries, result)
    }

    @Test
    fun `moving an entry before its current successor is a no op`() {
        val entries = entries("a", "b", "c")

        val result = AppListReducer.reduce(
            entries,
            AppListCommand.Move(
                key = AppItemKey("b"),
                beforeKey = AppItemKey("c"),
            ),
        )

        assertSame(entries, result)
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
