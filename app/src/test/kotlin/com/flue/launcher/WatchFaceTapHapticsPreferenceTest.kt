package com.flue.launcher

import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchFaceTapHapticsPreferenceTest {
    @Test fun defaultsOnPersistsAcrossViewModelsAndResetsWithoutCouplingToRotaryHaptics() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val key = LauncherViewModel.KEY_WATCHFACE_TAP_HAPTICS_ENABLED
        val rotaryKey = LauncherViewModel.KEY_ROTARY_HAPTICS_ENABLED
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        try {
            assertNull(app.dataStore.data.first()[key])
            val first = LauncherViewModel(app).also { firstStore.put("launcher", it) }
            assertTrue(first.watchFaceTapHapticsEnabled.value)
            app.dataStore.edit { it[rotaryKey] = false }
            withTimeout(10_000) { first.rotaryHapticsEnabled.first { !it } }
            first.setWatchFaceTapHapticsEnabled(false)
            assertFalse(first.watchFaceTapHapticsEnabled.value)
            withTimeout(10_000) { app.dataStore.data.first { it[key] == false } }
            assertFalse(first.rotaryHapticsEnabled.value)
            firstStore.clear()

            val restored = LauncherViewModel(app).also { secondStore.put("launcher", it) }
            withTimeout(10_000) { restored.watchFaceTapHapticsEnabled.first { !it } }
            restored.setWatchFaceTapHapticsEnabled(true)
            withTimeout(10_000) { app.dataStore.data.first { it[key] == true } }
            assertFalse(app.dataStore.data.first()[rotaryKey]!!)
            restored.setWatchFaceTapHapticsEnabled(false)
            withTimeout(10_000) { app.dataStore.data.first { it[key] == false } }
            restored.resetSettings()
            withTimeout(10_000) { app.dataStore.data.first { it[key] == true } }
            // Reset writes every preference asynchronously; await the collector's readback.
            withTimeout(10_000) { restored.watchFaceTapHapticsEnabled.first { it } }
            assertTrue(restored.watchFaceTapHapticsEnabled.value)
        } finally {
            firstStore.clear()
            secondStore.clear()
        }
        Unit
    }
}
