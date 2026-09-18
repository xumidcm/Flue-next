package com.flue.launcher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceStatusPreferenceContractTest {
    @Test
    fun `view model persists restores and resets the independent dnd moon switch`() {
        val source = source("com/example/wlauncher/viewmodel/LauncherViewModel.kt")

        assertTrue(source.contains("booleanPreferencesKey(\"watchface_dnd_indicator\")"))
        assertTrue(source.contains("MutableStateFlow(true)"))
        assertTrue(source.contains("prefs[KEY_WATCHFACE_DND_INDICATOR] ?: true"))
        assertTrue(source.contains("it[KEY_WATCHFACE_DND_INDICATOR] = enabled"))
        assertTrue(source.contains("_watchFaceDndIndicator.value = true"))
    }

    @Test
    fun `settings exposes the independent dnd moon switch`() {
        val settings = source("com/example/wlauncher/SettingsActivity.kt")
        assertTrue(settings.contains("settings_watchface_dnd_indicator"))
        assertTrue(settings.contains("watchFaceDndIndicator"))
        assertTrue(settings.contains("setWatchFaceDndIndicatorEnabled"))
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
