package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorExpressionReferenceTest {
    @Test
    fun curatedTagListStaysCompactAndWellFormed() {
        val entries = WATCH_EDITOR_EXPRESSION_GROUPS.flatMap { it.entries }
        assertTrue("expected 20..30 tags, got ${entries.size}", entries.size in 20..30)
        val tagPattern = Regex("^\\{[a-z0-9_]+\\}$")
        entries.forEach { entry ->
            assertTrue("malformed tag: ${entry.tag}", tagPattern.matches(entry.tag))
            assertTrue("blank description for ${entry.tag}", entry.description.isNotBlank())
        }
        assertEquals(entries.size, entries.map { it.tag }.distinct().size)
        WATCH_EDITOR_EXPRESSION_GROUPS.forEach { group ->
            assertTrue(group.title.isNotBlank())
            assertTrue(group.entries.isNotEmpty())
        }
    }

    @Test
    fun specCriticalTagsArePresent() {
        val tags = WATCH_EDITOR_EXPRESSION_GROUPS.flatMap { it.entries }.map { it.tag }.toSet()
        // Time, date, battery, heart rate and the spec-correct step tag {ssc}.
        listOf("{dh}", "{dhz}", "{dm}", "{ds}", "{dd}", "{dyy}", "{ddw}", "{bl}", "{shr}", "{ssc}")
            .forEach { assertTrue("missing $it", it in tags) }
    }

    @Test
    fun insertReplacesSelectionAndPlacesCursorAfterTag() {
        val (collapsed, cursor) = insertWatchEditorExpression("ab", 1, 1, "{dh}")
        assertEquals("a{dh}b", collapsed)
        assertEquals(5, cursor)

        val (replaced, replacedCursor) = insertWatchEditorExpression("hello", 1, 4, "{dm}")
        assertEquals("h{dm}o", replaced)
        assertEquals(5, replacedCursor)

        val (reversed, reversedCursor) = insertWatchEditorExpression("hello", 4, 1, "{dm}")
        assertEquals("h{dm}o", reversed)
        assertEquals(5, reversedCursor)
    }

    @Test
    fun insertClampsStaleOffsets() {
        val (appended, cursor) = insertWatchEditorExpression("ab", 99, 99, "{ds}")
        assertEquals("ab{ds}", appended)
        assertEquals(6, cursor)

        val (prefixed, prefixedCursor) = insertWatchEditorExpression("ab", -3, -3, "{ds}")
        assertEquals("{ds}ab", prefixed)
        assertEquals(4, prefixedCursor)
    }
}
