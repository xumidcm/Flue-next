package com.flue.launcher.watchface.selection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceLongPressPersistenceContractTest {
    @Test
    fun `view model persists restores and resets the long press mode`() {
        val source = source("com/example/wlauncher/viewmodel/LauncherViewModel.kt")

        assertTrue(source.contains("stringPreferencesKey(\"watchface_long_press_mode\")"))
        assertTrue(source.contains("MutableStateFlow(WatchFaceLongPressMode.CARDS)"))
        assertTrue(source.contains("WatchFaceLongPressMode.decode(prefs[KEY_WATCHFACE_LONG_PRESS_MODE])"))
        assertTrue(source.contains("it[KEY_WATCHFACE_LONG_PRESS_MODE] = mode.encode()"))
        assertTrue(source.contains("_watchFaceLongPressMode.value = WatchFaceLongPressMode.CARDS"))
    }

    @Test
    fun `normal settings backup does not exclude the mode`() {
        val source = source("com/example/wlauncher/backup/FlueBackupManager.kt")
        val excludedBlock = source.substringAfter("private val settingsExcludedNames")
            .substringBefore("fun suggestedFileName")

        assertFalse(excludedBlock.contains("KEY_WATCHFACE_LONG_PRESS_MODE"))
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
