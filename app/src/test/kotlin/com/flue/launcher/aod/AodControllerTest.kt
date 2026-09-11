package com.flue.launcher.aod

import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AodControllerTest {
    private fun withController(
        interactive: () -> Boolean = { true },
        overlay: () -> Boolean = { false },
        foreground: ((Intent) -> Unit)? = null,
        block: (Activity, AodController) -> Unit,
    ) {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.flue.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AodPreferences.preferences(activity).edit().clear().putBoolean("enabled", true).commit()
        val controller = AodController(activity, {}, screenInteractive = interactive,
            overlayAllowed = overlay, requestForeground = foreground ?: { activity.startActivity(it) })
        controller.start(); controller.onResume()
        try { block(activity, controller) } finally { controller.stop(); activity.finish() }
    }

    @Test fun timeoutDimsThenLocksAndDisableRestoresOriginalWindowBrightness() = withController { activity, controller ->
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.65f }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(controller.ambientMode)
        assertFalse(controller.gesturesLocked)
        assertEquals(0.1f, activity.window.attributes.screenBrightness, 0.0001f)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(controller.gesturesLocked)
        AodPreferences.preferences(activity).edit().putBoolean("enabled", false).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.ambientMode)
        assertFalse(controller.gesturesLocked)
        assertEquals(0.65f, activity.window.attributes.screenBrightness, 0.0001f)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertFalse(controller.ambientMode)
    }

    @Test fun pausedLauncherDoesNotEnterAmbientFromOldIdleTask() = withController { _, controller ->
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        controller.onPause()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(controller.ambientMode)
        controller.onResume()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(controller.ambientMode)
    }

    @Test fun wakingTouchAndLockedDoubleTapNeverPassThroughToChild() = withController { _, controller ->
        controller.enterAodNow()
        fun event(action: Int, down: Long, time: Long): Boolean {
            val event = MotionEvent.obtain(down, time, action, 10f, 10f, 0)
            return try { controller.onTouchEvent(event) } finally { event.recycle() }
        }
        assertTrue(event(MotionEvent.ACTION_DOWN, 100, 100))
        assertTrue(event(MotionEvent.ACTION_UP, 100, 150))
        assertTrue(controller.gesturesLocked)
        assertTrue(event(MotionEvent.ACTION_DOWN, 250, 250))
        assertTrue(event(MotionEvent.ACTION_UP, 250, 280))
        assertFalse(controller.gesturesLocked)
        assertFalse(controller.ambientMode)
        assertFalse(event(MotionEvent.ACTION_DOWN, 800, 800))
    }

    @Test fun shallowTouchBrightensWithoutExecutingChildOrDroppingDeepLock() = withController { activity, controller ->
        AodPreferences.preferences(activity).edit().putBoolean("shallow_sleep", true).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.enterAodNow()
        val down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 5f, 5f, 0)
        try { assertTrue(controller.onTouchEvent(down)) } finally { down.recycle() }
        assertFalse(controller.ambientMode)
        assertTrue(controller.gesturesLocked)
    }

    @Test fun heldGestureSuspendsIdleUntilRelease() = withController { _, controller ->
        val down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 5f, 5f, 0)
        try { assertFalse(controller.onTouchEvent(down)) } finally { down.recycle() }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertFalse(controller.ambientMode)
        val up = MotionEvent.obtain(100, 120_100, MotionEvent.ACTION_UP, 50f, 50f, 0)
        try { assertFalse(controller.onTouchEvent(up)) } finally { up.recycle() }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertFalse(controller.ambientMode)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(controller.ambientMode)
    }

    @Test fun screenOffAfterPauseStillReturnsWhenPauseWasCausedByScreenOff() {
        var interactive = true
        val starts = mutableListOf<Intent>()
        withController(interactive = { interactive }, foreground = { starts += it }) { activity, controller ->
            interactive = false
            controller.onPause()
            activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, starts.size)
            assertTrue(starts.single().getBooleanExtra(AodController.EXTRA_AOD_FOREGROUND, false))
            assertTrue(controller.ambientMode)
            controller.onResume()
            assertTrue(controller.gesturesLocked)
        }
    }

    @Test fun anotherAppScreenOffWithoutOverlayPermissionDoesNotWakeHome() {
        var interactive = true
        var starts = 0
        withController(interactive = { interactive }, foreground = { starts++ }) { activity, controller ->
            controller.onPause()
            interactive = false
            activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, starts)
            assertFalse(controller.ambientMode)
        }
    }

    @Test fun failedOrSilentlyDeniedForegroundRestoresWindowAndDoesNotRetry() {
        var starts = 0
        withController(overlay = { true }, foreground = { starts++ }) { activity, controller ->
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.7f }
            controller.onPause()
            activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(controller.ambientMode)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(controller.ambientMode)
            assertEquals(0.7f, activity.window.attributes.screenBrightness, 0.0001f)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals(1, starts)
        }
    }
}
