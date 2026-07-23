package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStableKeyDragPreviewTest {

    @Test
    fun `multiple visual moves collapse into one final stable-key command`() {
        val preview = NativeStableKeyDragPreview()
        assertTrue(preview.begin(key("a"), keys("a", "b", "c", "d")))

        assertEquals(keys("b", "a", "c", "d"), preview.moveTo(key("b")))
        assertEquals(keys("b", "c", "d", "a"), preview.moveTo(key("d")))
        assertEquals(keys("b", "a", "c", "d"), preview.moveTo(key("c")))

        assertEquals(
            AppListCommand.Move(key("a"), beforeKey = key("c")),
            preview.finish(commit = true),
        )
    }

    @Test
    fun `cancelled preview never creates a persisted move`() {
        val preview = NativeStableKeyDragPreview()
        assertTrue(preview.begin(key("c"), keys("a", "b", "c")))
        assertEquals(keys("a", "c", "b"), preview.moveTo(key("b")))

        assertNull(preview.finish(commit = false))
    }

    @Test
    fun `returning to original slot is a no-op on release`() {
        val preview = NativeStableKeyDragPreview()
        assertTrue(preview.begin(key("b"), keys("a", "b", "c")))
        preview.moveTo(key("c"))
        preview.moveTo(key("a"))
        preview.moveTo(key("a"))

        assertEquals(keys("a", "b", "c"), preview.currentOrder)
        assertNull(preview.finish(commit = true))
    }

    private fun keys(vararg values: String): List<AppItemKey> = values.map(::key)

    private fun key(value: String) = AppItemKey(value)
}
