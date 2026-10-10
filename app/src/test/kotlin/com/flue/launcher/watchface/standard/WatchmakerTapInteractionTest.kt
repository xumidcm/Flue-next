package com.flue.launcher.watchface.standard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowView
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [WatchmakerTapInteractionTest.TapHapticShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchmakerTapInteractionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Implements(View::class)
    class TapHapticShadow : ShadowView() {
        var count = 0
        var onFeedback: (() -> Unit)? = null

        @Implementation
        override fun performHapticFeedback(type: Int): Boolean {
            count++
            onFeedback?.invoke()
            return true
        }
    }

    private inner class Fixture(
        action: String = "script:var_tapped = (var_tapped or 0) + 1",
        private val useStage: Boolean = false,
    ) : AutoCloseable {
        val root = temporaryFolder.newFolder()
        val stage = WatchmakerWatchFaceStageView(RuntimeEnvironment.getApplication())
        val view = stage.renderer
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val descriptor = WatchFaceDescriptor(
            "watch:tap", WatchFaceType.WATCHMAKER, "Tap", sourceDirPath = root.absolutePath,
        )
        val runtime: WatchFaceLuaRuntime

        init {
            root.resolve("scripts").mkdirs()
            root.resolve("scripts/script.txt").writeText("var_tapped = 0")
            root.resolve("watch.xml").writeText("""
                <Watch name="Tap">
                    <Layer type="shape" shape="Square" x="0" y="0" width="100" height="100"
                        color="ffffff" tap_action="$action" />
                </Watch>
            """.trimIndent())
            val source = requireNotNull(view.javaClass.getDeclaredMethod(
                "prepareSource", File::class.java, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(view, root, 0L))
            fun set(name: String, value: Any) {
                view.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(view, value)
            }
            set("source", source)
            set("descriptorKey", descriptor.stableKey)
            set("loadedDescriptorKey", descriptor.stableKey)
            set("refreshToken", 7)
            set("loadedRefreshToken", 7)
            runtime = source.javaClass.getDeclaredField("luaRuntime").apply { isAccessible = true }
                .get(source) as WatchFaceLuaRuntime
            runtime.runSnippet("var_tapped = 0").getOrThrow()
            val size = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
            stage.measure(size, size)
            stage.layout(0, 0, 320, 320)
            bind()
            view.draw(Canvas(bitmap))
            if (action.isNotBlank()) {
                val targets = view.javaClass.getDeclaredField("tapTargets").apply { isAccessible = true }
                    .get(view) as List<*>
                assertTrue("The real rendered button must be hittable", targets.isNotEmpty())
                val target = requireNotNull(targets.first())
                val bounds = target.javaClass.getDeclaredField("bounds").apply { isAccessible = true }
                    .get(target) as android.graphics.RectF
                assertTrue("Button bounds: $bounds", bounds.contains(160f, 160f))
            }
        }

        fun bind(doubleTap: (() -> Unit)? = null, longPress: (() -> Unit)? = null) {
            view.bind(descriptor, 7, true, longPress, doubleTap,
                onLoadFailure = { _, error -> throw AssertionError(error) })
        }

        fun tap() {
            val down = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, down)
            send(MotionEvent.ACTION_UP, down)
        }

        fun send(action: Int, down: Long, x: Float = 160f, y: Float = 160f) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                if (useStage) stage.dispatchTouchEvent(event) else view.onTouchEvent(event)
            } finally { event.recycle() }
        }

        fun count(): Double? = runtime.evaluateNumber("var_tapped")
        val haptics: TapHapticShadow get() = shadowOf(view) as TapHapticShadow

        fun advance(millis: Long = 70) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        }

        override fun close() {
            stage.dispose()
            bitmap.recycle()
        }
    }

    @Test fun singleTapExecutesScriptOnReleaseWithoutWaitingForDoubleTapTimeout() {
        Fixture().use { fixture ->
            fixture.tap()
            assertEquals(1.0, fixture.count())
        }
    }

    @Test fun rapidTapsAreIndependentWhenDoubleTapLockingIsDisabled() {
        Fixture().use { f ->
            repeat(3) { f.tap(); f.advance() }
            assertEquals(3.0, f.count())
            f.advance(500)
            assertEquals(3.0, f.count())
            assertEquals(3, f.haptics.count)
        }
    }

    @Test fun firstReleaseRunsActionAndSecondTapLocksWithoutAnotherAction() {
        Fixture().use { f ->
            var locks = 0
            f.bind(doubleTap = { locks++ })
            f.tap()
            assertEquals(1.0, f.count())
            f.advance()
            // Recomposition replaces the callback without resetting recognition.
            f.bind(doubleTap = { locks++ })
            f.tap()
            assertEquals(1, locks)
            assertEquals(1.0, f.count())
            f.advance(500)
            assertEquals(1.0, f.count())
            assertEquals(1, f.haptics.count)
        }
    }

    @Test fun longPressDoesNotAlsoExecuteTap() {
        Fixture().use { f ->
            var presses = 0
            f.bind(longPress = { presses++ })
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.advance((ViewConfiguration.getLongPressTimeout() + 100).toLong())
            f.send(MotionEvent.ACTION_UP, down)
            assertEquals(1, presses)
            assertEquals(0.0, f.count())
            assertEquals(0, f.haptics.count)
        }
    }

    @Test fun swipeAndCancelDoNotExecuteTap() {
        Fixture().use { f ->
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.send(MotionEvent.ACTION_MOVE, down, 280f, 280f)
            f.send(MotionEvent.ACTION_UP, down, 280f, 280f)
            f.send(MotionEvent.ACTION_DOWN, down)
            f.send(MotionEvent.ACTION_CANCEL, down)
            f.send(MotionEvent.ACTION_UP, down)
            f.advance(600)
            assertEquals(0.0, f.count())
            assertEquals(0, f.haptics.count)
        }
    }

    @Test fun hidingAndReturningCannotFinishAnOldGesture() {
        Fixture().use { f ->
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.view.setFaceVisible(false)
            f.view.setFaceVisible(true)
            f.send(MotionEvent.ACTION_UP, down)
            assertEquals(0.0, f.count())
            f.tap()
            assertEquals(1.0, f.count())
        }
    }

    @Test fun hidingThroughBindCancelsLongPressAndPendingTap() {
        Fixture().use { f ->
            var presses = 0
            f.bind(longPress = { presses++ })
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.view.bind(f.descriptor, 7, false, { presses++ }, null, { _, error -> throw error })
            f.bind(longPress = { presses++ })
            f.advance(600)
            f.send(MotionEvent.ACTION_UP, down)
            assertEquals(0, presses)
            assertEquals(0.0, f.count())
        }
    }

    @Test fun changingDoubleTapModeDiscardsPreviousTapHistory() {
        Fixture().use { f ->
            var locks = 0
            f.bind(doubleTap = { locks++ })
            f.tap()
            f.advance()
            f.bind()
            f.bind(doubleTap = { locks++ })
            f.tap()
            assertEquals(0, locks)
            assertEquals(2.0, f.count())
        }
    }

    @Test fun disposalCancelsPendingLongPress() {
        Fixture().use { f ->
            var presses = 0
            f.bind(longPress = { presses++ })
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.view.dispose()
            f.advance(600)
            assertEquals(0, presses)
        }
    }

    @Test fun feedbackPrecedesScriptAndTogglingItPreservesLuaState() {
        Fixture().use { f ->
            val valuesAtFeedback = mutableListOf<Double?>()
            f.haptics.onFeedback = { valuesAtFeedback += f.count() }
            f.tap()
            assertEquals(listOf(0.0), valuesAtFeedback)
            f.view.setTapHapticsEnabled(false)
            f.tap()
            assertEquals(2.0, f.count())
            assertEquals(1, f.haptics.count)
            f.view.setTapHapticsEnabled(true)
            f.tap()
            assertEquals(3.0, f.count())
            assertEquals(listOf(0.0, 2.0), valuesAtFeedback)
        }
    }

    @Test fun blankSpaceAndUnsupportedActionsDoNotVibrate() {
        Fixture().use { f ->
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down, 10f, 10f)
            f.send(MotionEvent.ACTION_UP, down, 10f, 10f)
            assertEquals(0, f.haptics.count)
            assertEquals(0.0, f.count())
        }
        Fixture(action = "set_alarm_99:99").use { f ->
            f.tap()
            assertEquals(0, f.haptics.count)
        }
    }

    @Test fun failedScriptStillAcknowledgesTapExactlyOnce() {
        Fixture(action = "script:error('failed')").use { f ->
            f.tap()
            assertEquals(1, f.haptics.count)
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("失败"))
        }
    }

    @Test fun builtInColorActionHasOnlyOneFeedbackAndObeysTheSwitch() {
        Fixture(action = "color_switch_next").use { f ->
            f.tap()
            assertEquals(1, f.haptics.count)
            f.view.setTapHapticsEnabled(false)
            f.tap()
            assertEquals(1, f.haptics.count)
        }
    }

    @Test fun successfulScriptRequestsDrawingBeforeNextPeriodicTick() {
        Fixture().use { f ->
            val shadow = shadowOf(f.view)
            shadow.clearWasInvalidated()
            assertFalse(shadow.wasInvalidated())
            f.tap()
            assertTrue(shadow.wasInvalidated())
        }
    }

    @Test fun videoStageRoutesEachTapOnlyToThePrimaryRenderer() {
        Fixture(useStage = true).use { f ->
            val videos = f.stage.javaClass.getDeclaredField("videoViews").apply { isAccessible = true }
                .get(f.stage)
            @Suppress("UNCHECKED_CAST")
            (videos as MutableList<WatchFaceVideoLayerView>).add(
                WatchFaceVideoLayerView(RuntimeEnvironment.getApplication()),
            )
            f.tap()
            assertEquals(1.0, f.count())
            assertEquals(1, f.haptics.count)
        }
    }

    @Test fun switchingSourceCannotFinishThePreviousGesture() {
        Fixture().use { f ->
            var presses = 0
            f.bind(longPress = { presses++ })
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.view.bind(f.descriptor.copy(id = "watch:other"), 8, true, { presses++ }, null,
                onLoadFailure = { _, error -> throw error })
            f.send(MotionEvent.ACTION_UP, down)
            f.advance(600)
            assertEquals(0, presses)
            assertEquals(0, f.haptics.count)
        }
    }

    @Test fun detachCancelsPendingLongPressAndRelease() {
        Fixture().use { f ->
            var presses = 0
            f.bind(longPress = { presses++ })
            val down = SystemClock.uptimeMillis()
            f.send(MotionEvent.ACTION_DOWN, down)
            f.view.javaClass.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }
                .invoke(f.view)
            f.advance(600)
            f.send(MotionEvent.ACTION_UP, down)
            assertEquals(0, presses)
            assertEquals(0.0, f.count())
        }
    }
}
