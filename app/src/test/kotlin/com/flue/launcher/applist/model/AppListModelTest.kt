package com.flue.launcher.applist.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AppListModelTest {

    @Test
    fun `app item key rejects empty and blank values`() {
        listOf("", " ", "\t", "\r\n").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                AppItemKey(value)
            }
        }
    }

    @Test
    fun `app item key preserves a valid original value`() {
        val value = " app:calendar "

        assertEquals(value, AppItemKey(value).value)
    }

    @Test
    fun `normalized point accepts finite boundary values`() {
        assertEquals(NormalizedPoint(0f, 0f), NormalizedPoint(0f, 0f))
        assertEquals(NormalizedPoint(1f, 1f), NormalizedPoint(1f, 1f))
    }

    @Test
    fun `normalized point rejects non finite and out of range coordinates`() {
        val invalidCoordinates = listOf(
            Float.NaN,
            Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            -0.001f,
            1.001f,
        )

        invalidCoordinates.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                NormalizedPoint(invalid, 0.5f)
            }
            assertThrows(IllegalArgumentException::class.java) {
                NormalizedPoint(0.5f, invalid)
            }
        }
    }

    @Test
    fun `model exposes the framework independent entry and command vocabulary`() {
        val source = AppItemKey("app:source")
        val target = AppItemKey("app:target")
        val point = NormalizedPoint(0.25f, 0.75f)
        val component = LaunchTarget.Component(
            packageName = "com.example.source",
            className = "com.example.source.MainActivity",
        )
        val intentUri = LaunchTarget.IntentUri("flue://shortcut/source")
        val iconKey = IconKey("icon:source")
        val entry = AppListEntry(
            key = source,
            label = "Source",
            type = AppItemType.FOLDER,
            launchTarget = null,
            iconKey = iconKey,
            folderChildren = listOf(target),
        )

        assertEquals(source, entry.key)
        assertEquals("Source", entry.label)
        assertEquals(AppItemType.FOLDER, entry.type)
        assertEquals(null, entry.launchTarget)
        assertEquals(iconKey, entry.iconKey)
        assertEquals(listOf(target), entry.folderChildren)
        assertEquals("com.example.source", component.packageName)
        assertEquals("com.example.source.MainActivity", component.className)
        assertEquals("flue://shortcut/source", intentUri.uri)
        assertArrayEquals(
            arrayOf(AppItemType.APP, AppItemType.SHORTCUT, AppItemType.FOLDER),
            AppItemType.entries.toTypedArray(),
        )
        assertEquals(AppListCommand.Open(source, point), AppListCommand.Open(source, point))
        assertEquals(AppListCommand.Move(source, target), AppListCommand.Move(source, target))
        assertEquals(
            AppListCommand.CreateFolder(source, target),
            AppListCommand.CreateFolder(source, target),
        )
        assertEquals(AppListCommand.Hide(source), AppListCommand.Hide(source))
        assertEquals(
            AppListCommand.RemoveShortcut(source),
            AppListCommand.RemoveShortcut(source),
        )
        assertEquals(
            AppListCommand.RenameFolder(source, "Renamed"),
            AppListCommand.RenameFolder(source, "Renamed"),
        )
        assertEquals(
            AppListCommand.DissolveFolder(source),
            AppListCommand.DissolveFolder(source),
        )
    }

    @Test
    fun `move uses stable keys after refresh changes old indexes`() {
        val commandCreatedBeforeRefresh = AppListCommand.Move(
            key = AppItemKey("b"),
            beforeKey = AppItemKey("d"),
        )
        val refreshedEntries = entries("d", "a", "c", "b")

        val moved = AppListReducer.reduce(refreshedEntries, commandCreatedBeforeRefresh)

        assertEquals(listOf("b", "d", "a", "c"), moved.ids())
    }

    @Test
    fun `move with no before key moves entry to the end`() {
        val current = entries("a", "b", "c", "d")

        val moved = AppListReducer.reduce(
            current,
            AppListCommand.Move(key = AppItemKey("b"), beforeKey = null),
        )

        assertEquals(listOf("a", "c", "d", "b"), moved.ids())
    }

    @Test
    fun `move with missing source key keeps the original list`() {
        val current = entries("a", "b", "c")

        val result = AppListReducer.reduce(
            current,
            AppListCommand.Move(
                key = AppItemKey("missing"),
                beforeKey = AppItemKey("b"),
            ),
        )

        assertSame(current, result)
    }

    @Test
    fun `move with missing target key keeps the original list`() {
        val current = entries("a", "b", "c")

        val result = AppListReducer.reduce(
            current,
            AppListCommand.Move(
                key = AppItemKey("a"),
                beforeKey = AppItemKey("missing"),
            ),
        )

        assertSame(current, result)
    }

    @Test
    fun `move before itself keeps the original list`() {
        val current = entries("a", "b", "c")
        val key = AppItemKey("b")

        val result = AppListReducer.reduce(
            current,
            AppListCommand.Move(key = key, beforeKey = key),
        )

        assertSame(current, result)
    }

    @Test
    fun `move preserves every entry exactly once and keeps unaffected relative order`() {
        val current = entries("a", "b", "c", "d", "e")

        val moved = AppListReducer.reduce(
            current,
            AppListCommand.Move(
                key = AppItemKey("d"),
                beforeKey = AppItemKey("b"),
            ),
        )

        assertEquals(listOf("a", "d", "b", "c", "e"), moved.ids())
        assertEquals(current.size, moved.size)
        assertEquals(current.map(AppListEntry::key).toSet(), moved.map(AppListEntry::key).toSet())
        assertEquals(current.ids().sorted(), moved.ids().sorted())
    }

    private fun entries(vararg ids: String): List<AppListEntry> = ids.map { id ->
        AppListEntry(
            key = AppItemKey(id),
            label = id.uppercase(),
            type = AppItemType.APP,
            launchTarget = LaunchTarget.Component(
                packageName = "com.example.$id",
                className = "com.example.$id.MainActivity",
            ),
            iconKey = IconKey("icon:$id"),
            folderChildren = emptyList(),
        )
    }

    private fun List<AppListEntry>.ids(): List<String> = map { it.key.value }
}
