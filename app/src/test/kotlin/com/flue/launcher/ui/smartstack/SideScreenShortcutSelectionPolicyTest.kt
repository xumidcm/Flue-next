package com.flue.launcher.ui.smartstack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideScreenShortcutSelectionPolicyTest {
    @Test
    fun `app without exposed shortcuts is added directly`() {
        assertEquals(
            SideScreenShortcutSelection.ADD_OPEN_APP,
            sideScreenShortcutSelection(shortcutCount = 0),
        )
    }

    @Test
    fun `app with exposed shortcuts opens a second level chooser`() {
        assertEquals(
            SideScreenShortcutSelection.CHOOSE_ACTION,
            sideScreenShortcutSelection(shortcutCount = 2),
        )
    }

    @Test
    fun `side screen queries only public app shortcuts and pins the selected action`() {
        val repository = source("com/example/wlauncher/data/repository/AppRepository.kt")
        val stack = source("com/example/wlauncher/ui/smartstack/SmartStackLayer.kt")

        assertTrue(repository.contains("FLAG_MATCH_MANIFEST"))
        assertTrue(repository.contains("FLAG_MATCH_DYNAMIC"))
        assertTrue(repository.contains("FLAG_MATCH_PINNED"))
        assertTrue(repository.contains("launcherApps.pinShortcuts("))
        assertTrue(stack.contains("onQueryAppShortcuts"))
        assertTrue(stack.contains("ShortcutActionRow("))
        assertTrue(stack.contains("side_shortcut_open_app"))
    }

    private fun source(relative: String): String {
        val root = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        return File(root, relative).readText()
    }
}
