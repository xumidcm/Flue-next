package com.flue.launcher.ui.common

import com.flue.launcher.R
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {
    @Test
    fun launcherAndSettingsAdaptiveIconResourcesArePackaged() {
        val ids = listOf(
            R.mipmap.ic_launcher,
            R.mipmap.ic_launcher_round,
            R.mipmap.ic_launcher_foreground,
            R.mipmap.ic_settings_launcher,
            R.mipmap.ic_settings_launcher_foreground,
            R.drawable.ic_launcher_monochrome,
            R.drawable.ic_settings_launcher_monochrome,
        )

        ids.forEach { id -> assertTrue("launcher icon resource ID must be non-zero", id != 0) }
    }
}
