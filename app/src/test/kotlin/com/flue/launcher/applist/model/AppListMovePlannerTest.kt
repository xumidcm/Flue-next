package com.flue.launcher.applist.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppListMovePlannerTest {

    @Test
    fun `moving down captures the stable key that follows the moved item`() {
        val command = AppListMovePlanner.fromIndexes(
            keys = keys("a", "b", "c", "d"),
            fromIndex = 1,
            toIndex = 2,
        )

        assertEquals(
            AppListCommand.Move(
                key = AppItemKey("b"),
                beforeKey = AppItemKey("d"),
            ),
            command,
        )
    }

    @Test
    fun `moving up captures the target as the stable before key`() {
        val command = AppListMovePlanner.fromIndexes(
            keys = keys("a", "b", "c", "d"),
            fromIndex = 3,
            toIndex = 1,
        )

        assertEquals(
            AppListCommand.Move(
                key = AppItemKey("d"),
                beforeKey = AppItemKey("b"),
            ),
            command,
        )
    }

    @Test
    fun `moving to the final position uses a null before key`() {
        val command = AppListMovePlanner.fromIndexes(
            keys = keys("a", "b", "c", "d"),
            fromIndex = 1,
            toIndex = 3,
        )

        assertEquals(
            AppListCommand.Move(
                key = AppItemKey("b"),
                beforeKey = null,
            ),
            command,
        )
    }

    @Test
    fun `captured command still moves the intended app after a refresh`() {
        val command = requireNotNull(
            AppListMovePlanner.fromIndexes(
                keys = keys("a", "b", "c", "d"),
                fromIndex = 1,
                toIndex = 2,
            ),
        )
        val refreshed = entries("d", "a", "c", "b")

        val moved = AppListReducer.reduce(refreshed, command)

        assertEquals(listOf("b", "d", "a", "c"), moved.map { it.key.value })
    }

    @Test
    fun `invalid or unchanged indexes do not create a command`() {
        val keys = keys("a", "b", "c")

        assertNull(AppListMovePlanner.fromIndexes(keys, fromIndex = -1, toIndex = 1))
        assertNull(AppListMovePlanner.fromIndexes(keys, fromIndex = 1, toIndex = -1))
        assertNull(AppListMovePlanner.fromIndexes(keys, fromIndex = 3, toIndex = 1))
        assertNull(AppListMovePlanner.fromIndexes(keys, fromIndex = 1, toIndex = 3))
        assertNull(AppListMovePlanner.fromIndexes(keys, fromIndex = 1, toIndex = 1))
    }

    @Test
    fun `folder command captures source and target identities before ui delay`() {
        val command = AppListMovePlanner.folderFromIndexes(
            keys = keys("a", "b", "c"),
            sourceIndex = 2,
            targetIndex = 0,
        )

        assertEquals(
            AppListCommand.CreateFolder(
                source = AppItemKey("c"),
                target = AppItemKey("a"),
            ),
            command,
        )
        assertNull(
            AppListMovePlanner.folderFromIndexes(
                keys = keys("a", "b"),
                sourceIndex = 1,
                targetIndex = 1,
            ),
        )
    }

    @Test
    fun `folder release only accepts the target that is still under the pointer`() {
        assertEquals(
            2,
            AppListMovePlanner.validatedFolderTarget(
                pendingTargetIndex = 2,
                currentTargetIndex = 2,
                sourceIndex = 0,
            ),
        )
        assertNull(
            AppListMovePlanner.validatedFolderTarget(
                pendingTargetIndex = 2,
                currentTargetIndex = 1,
                sourceIndex = 0,
            ),
        )
        assertNull(
            AppListMovePlanner.validatedFolderTarget(
                pendingTargetIndex = 2,
                currentTargetIndex = null,
                sourceIndex = 0,
            ),
        )
        assertNull(
            AppListMovePlanner.validatedFolderTarget(
                pendingTargetIndex = 0,
                currentTargetIndex = 0,
                sourceIndex = 0,
            ),
        )
    }

    @Test
    fun `native drag resolves current indexes from stable keys after refresh`() {
        val refreshed = keys("c", "a", "b")

        assertEquals(
            AppListCommand.Move(AppItemKey("b"), AppItemKey("c")),
            AppListMovePlanner.fromKeys(refreshed, AppItemKey("b"), AppItemKey("c")),
        )
        assertEquals(
            AppListCommand.CreateFolder(AppItemKey("b"), AppItemKey("a")),
            AppListMovePlanner.folderFromKeys(refreshed, AppItemKey("b"), AppItemKey("a")),
        )
        assertNull(AppListMovePlanner.fromKeys(refreshed, AppItemKey("missing"), AppItemKey("a")))
    }

    private fun keys(vararg values: String): List<AppItemKey> = values.map(::AppItemKey)

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
