package com.flue.launcher.diagnostics

import android.app.Activity
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticsOverlayLifecycleTest {
    @Test fun `overlay belongs to resumed activity and retains independent switches across pause`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            DiagnosticsController.setFps(false)
            DiagnosticsController.setMemory(false)
            DiagnosticsController.setThreads(false)
            DiagnosticsController.onResume(activity)
            assertNull(activity.window.decorView.findViewWithTag<TextView>("flue-performance-overlay"))

            DiagnosticsController.setMemory(true)
            DiagnosticsController.setThreads(true)
            assertNotNull(activity.window.decorView.findViewWithTag<TextView>("flue-performance-overlay"))
            DiagnosticsController.setMemory(false)
            assertTrue(DiagnosticsController.config.value.threads)
            assertFalse(DiagnosticsController.config.value.memory)

            DiagnosticsController.onPause(activity)
            assertNull(activity.window.decorView.findViewWithTag<TextView>("flue-performance-overlay"))
            assertTrue(DiagnosticsController.config.value.threads)
            DiagnosticsController.onResume(activity)
            assertNotNull(activity.window.decorView.findViewWithTag<TextView>("flue-performance-overlay"))
            DiagnosticsController.setThreads(false)
            assertNull(activity.window.decorView.findViewWithTag<TextView>("flue-performance-overlay"))
        } finally {
            DiagnosticsController.setFps(false)
            DiagnosticsController.setMemory(false)
            DiagnosticsController.setThreads(false)
            DiagnosticsController.onPause(activity)
            controller.pause().stop().destroy()
        }
    }
}
