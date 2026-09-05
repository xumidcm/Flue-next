package com.flue.launcher

import kotlin.random.Random
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashMessagePickerTest {
    @Test
    fun `selects different title and subtitle indices when both have variants`() {
        val selection = selectCrashMessageIndices(
            titleCount = 4,
            subtitleCount = 4,
            random = Random(7),
        )

        assertNotEquals(selection.titleIndex, selection.subtitleIndex)
    }

    @Test
    fun `keeps the only available index when a message has one variant`() {
        val selection = selectCrashMessageIndices(
            titleCount = 1,
            subtitleCount = 3,
            random = Random(7),
        )

        assertEquals(0, selection.titleIndex)
        assertEquals(true, selection.subtitleIndex in 0 until 3)
    }
}
