package com.flue.launcher

import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchFaceTapHapticsPreferenceTest {

    private suspend fun <T> awaitValue(
        flow: Flow<T>,
        timeoutMs: Long = 10_000,
        predicate: (T) -> Boolean = { true },
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val result = withTimeoutOrNull(100) { flow.first(predicate) }
            if (result != null) return result
            delay(10)
        }
        error("Timed out waiting for flow condition after ${timeoutMs}ms")
    }

    @Test fun defaultsOnPersistsAcrossViewModelsAndResetsWithoutCouplingToRotaryHaptics() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val key = LauncherViewModel.KEY_WATCHFACE_TAP_HAPTICS_ENABLED
        val rotaryKey = LauncherViewModel.KEY_ROTARY_HAPTICS_ENABLED
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        try {
            assertNull(app.dataStore.data.first()[key])
            val first = LauncherViewModel(app).also { firstStore.put("launcher", it) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(first.watchFaceTapHapticsEnabled.value)
            app.dataStore.edit { it[rotaryKey] = false }
            awaitValue(first.rotaryHapticsEnabled) { !it }
            first.setWatchFaceTapHapticsEnabled(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(first.watchFaceTapHapticsEnabled.value)
            awaitValue(app.dataStore.data) { it[key] == false }
            assertFalse(first.rotaryHapticsEnabled.value)
            firstStore.clear()
            shadowOf(Looper.getMainLooper()).idle()

            val restored = LauncherViewModel(app).also { secondStore.put("launcher", it) }
            shadowOf(Looper.getMainLooper()).idle()
            awaitValue(restored.watchFaceTapHapticsEnabled) { !it }
            restored.setWatchFaceTapHapticsEnabled(true)
            shadowOf(Looper.getMainLooper()).idle()
            awaitValue(app.dataStore.data) { it[key] == true }
            assertFalse(app.dataStore.data.first()[rotaryKey]!!)
            restored.setWatchFaceTapHapticsEnabled(false)
            shadowOf(Looper.getMainLooper()).idle()
            awaitValue(app.dataStore.data) { it[key] == false }
            restored.resetSettings()
            shadowOf(Looper.getMainLooper()).idle()
            awaitValue(app.dataStore.data) { it[key] == true }
            // Reset writes every preference asynchronously; await the collector's readback.
            awaitValue(restored.watchFaceTapHapticsEnabled) { it }
            assertTrue(restored.watchFaceTapHapticsEnabled.value)
        } finally {
            firstStore.clear()
            secondStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
        Unit
    }
}
