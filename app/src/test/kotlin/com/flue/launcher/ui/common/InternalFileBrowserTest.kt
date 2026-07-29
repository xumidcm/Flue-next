package com.flue.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalFileBrowserTest {
    @Test
    fun normalizesExtensionsCaseAndLeadingDot() {
        assertEquals(
            setOf("watch", "png", "jpg"),
            normalizeInternalFileExtensions(setOf(".WATCH", " png ", "jpg", "")),
        )
    }

    @Test
    fun filtersByFinalExtensionCaseInsensitively() {
        val accepted = setOf("watch", "wff")
        assertTrue(matchesInternalFileExtension("Face.WATCH", accepted))
        assertTrue(matchesInternalFileExtension("face.WFF", accepted))
        assertFalse(matchesInternalFileExtension("face.watch.zip", accepted))
        assertFalse(matchesInternalFileExtension("watch", accepted))
    }

    @Test
    fun emptyFilterAcceptsEveryFile() {
        assertTrue(matchesInternalFileExtension("anything.bin", emptySet()))
    }
}
