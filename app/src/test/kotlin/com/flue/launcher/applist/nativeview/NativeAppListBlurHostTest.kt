package com.flue.launcher.applist.nativeview

import android.content.Context
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.os.Looper
import android.os.SystemClock
import com.flue.launcher.applist.model.AppListCommand
import android.view.MotionEvent
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.IconKey
import com.flue.launcher.applist.model.LaunchTarget
import eightbitlab.com.blurview.BlurAlgorithm
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.BlurViewCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 30], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppListBlurHostTest {
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<Activity>>()

    @After fun closeWindows() {
        activities.forEach { it.pause().stop().destroy() }
    }
    @Test
    fun composeHostKeepsTheNativeListDirectAndDoesNotBlurRows() {
        val source = listOf(
            File("src/main/kotlin/com/flue/launcher/applist/nativeview/NativeAppListHost.kt"),
            File("app/src/main/kotlin/com/flue/launcher/applist/nativeview/NativeAppListHost.kt"),
        ).first(File::isFile).readText()
        assertTrue("The native list must be drawn directly", source.contains("NativeAppListView(context)"))
        assertTrue(
            "Android 12 and below must still wrap the list for overlay background blur",
            source.contains("NativeAppListBlurHost(context)"),
        )
        assertTrue(
            "The overlay blur kernel must be sampled during draw",
            source.contains("overlayBlurRadiusDp()"),
        )
        assertFalse(
            "Legacy row/transition blur must stay removed; only the overlay path may use the host",
            source.contains("transitionBlurRadiusDp()"),
        )
        assertTrue("Android 12 must retain its direct native list", source.contains("Build.VERSION_CODES.S"))
        assertTrue("The current motion profile must remain stable", source.contains("remember(config, motionProfile)"))
    }

    @Test
    fun overlayBlurKeepsTheKernelRadiusFixedAndOnlyRecomposesOnAWeightChange() {
        val algorithm = RecordingAlgorithm()
        val host = host { algorithm }
        host.contentView.background = leftHalfDrawable(Color.argb(128, 255, 0, 0))
        host.setOverlayBlurFrameSource(16f, { 1f }, enabled = true, visible = true)

        draw(host)
        assertEquals(1, algorithm.radii.size)

        // 覆层展开后画面静止：权重不变就一次卷积都不该再跑。
        repeat(4) {
            host.viewTreeObserver.dispatchOnPreDraw()
            draw(host).recycle()
        }
        assertEquals("An unchanged overlay must reuse the baked kernel", 1, algorithm.radii.size)

        // 权重由 1 降到 0.4 必须触发重算 —— 否则菜单背景会一直停在第一帧的权重。
        host.setOverlayBlurFrameSource(16f, { 0.4f }, enabled = true, visible = true)
        draw(host).recycle()
        assertEquals("A weight-only change must recompose the blend", 2, algorithm.radii.size)
        // 关键契约：重算的是混合权重，不是核 —— 核半径全程钉在同一个值上，
        // 绝不跟着权重漂移（那会让菜单在淡入过程里越糊越浅）。
        assertTrue(
            "The overlay kernel radius must never drift with the blend",
            algorithm.radii.distinct().size == 1,
        )
        host.release()
    }

    @Test
    fun firstDisplayedFrameCapturesOnlyOnceAndIdleDrawReusesTheResult() {
        var captures = 0
        val algorithm = RecordingAlgorithm()
        val host = host { algorithm }
        host.contentView.background = countingDrawable { captures++ }
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)

        draw(host)

        assertEquals("Initialization must not capture an extra frame", 1, captures)
        repeat(3) { host.viewTreeObserver.dispatchOnPreDraw(); draw(host) }
        assertEquals("An unchanged list must reuse its captured pixels", 1, captures)
        assertEquals("An unchanged radius must reuse its blurred pixels", 1, algorithm.radii.size)
        host.release()
    }

    @Test
    fun radiusOnlyChangeReusesPristineCaptureInsteadOfRecapturingOrBlurringBlurredPixels() {
        var captures = 0
        val algorithm = RecordingAlgorithm(replaceWithGreen = true)
        val host = host { algorithm }
        host.contentView.background = countingDrawable { captures++ }
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)

        host.updateBlurFrame(5f, 1f, enabled = true, visible = true)
        draw(host)

        assertEquals("Only radius changed; the list should not be redrawn", 1, captures)
        assertEquals("The second blur must start with the clear source", Color.RED, algorithm.capturedPixel)
        assertEquals(2, algorithm.radii.size)
        host.release()
    }

    @Test
    fun currentFrameRadiusUsesActualCaptureScaleAndUpdatesBeforeDrawing() {
        val algorithm = RecordingAlgorithm()
        val host = host { algorithm }
        host.updateBlurFrame(20f, 1f, enabled = true, visible = true)

        draw(host)

        assertEquals(80, algorithm.width)
        assertEquals(5f, algorithm.radii.last(), 0.001f)
        host.updateBlurFrame(5f, 0.4f, enabled = true, visible = true)
        draw(host)
        assertEquals(1.25f, algorithm.radii.last(), 0.001f)
        assertEquals(1f, host.alpha, 0f)
        host.release()
    }

    @Test
    fun populatedListReusesCaptureThroughThirtyFramesAndRecapturesAfterScrolling() {
        var captures = 0
        val algorithm = RecordingAlgorithm()
        val host = host { algorithm }
        val content = host.contentView
        content.background = countingDrawable { captures++ }
        content.setConfig(NativeAppListConfig(
            rowHeightPx = 48, iconSizePx = 32, centerEdgeItems = false,
            entryMotionEnabled = false, scrollIndicatorEnabled = false,
        ))
        val entries = (0 until 60).map { index ->
            AppListEntry(AppItemKey("app:$index"), "App $index", AppItemType.APP,
                LaunchTarget.Component("com.example", ".Main$index"), IconKey("icon:$index"), emptyList())
        }
        content.submit(NativeAppListContent(AppListSnapshot.create(1L, entries), emptyMap()))
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 320)
        content.scrollBy(0, 480)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(content.childCount > 0)
        for (frame in 1..30) {
            host.updateBlurFrame(frame / 2f + 1f, 1f, enabled = true, visible = true)
            draw(host).recycle()
        }
        assertEquals("Static icons must not be captured once per animation frame", 1, captures)
        assertEquals(30, algorithm.radii.size)
        content.scrollBy(0, 60)
        draw(host).recycle()
        assertEquals("Scrolling must invalidate the snapshot immediately", 2, captures)
        host.release()
    }

    @Test
    @Config(sdk = [27, 28, 30, 31])
    fun transitionRasterCacheKeepsTheLiveListAndIsReleasedWhenHiddenOrFinished() {
        val host = host { RecordingAlgorithm() }
        val content = host.contentView
        content.setTransitionRasterCacheEnabled(true)
        assertEquals(View.LAYER_TYPE_HARDWARE, content.layerType)
        content.setBackgroundColor(Color.RED)
        assertEquals(Color.RED, draw(host).getPixel(10, 10))
        content.setBackgroundColor(Color.BLUE)
        assertEquals("Content changes still render during a transform", Color.BLUE, draw(host).getPixel(10, 10))
        host.visibility = View.INVISIBLE
        assertEquals(View.LAYER_TYPE_NONE, content.layerType)
        host.visibility = View.VISIBLE
        assertEquals(View.LAYER_TYPE_HARDWARE, content.layerType)
        content.setTransitionRasterCacheEnabled(false)
        assertEquals(View.LAYER_TYPE_NONE, content.layerType)
        content.setTransitionRasterCacheEnabled(true)
        host.release()
        assertEquals(View.LAYER_TYPE_NONE, content.layerType)
        content.setTransitionRasterCacheEnabled(true)
        assertEquals(View.LAYER_TYPE_NONE, content.layerType)
    }

    @Test
    fun blurCapturesVisibleSourceButDrawsOnlyTransparentBlurredResult() {
        val algorithm = RecordingAlgorithm(replaceWithGreen = true)
        val host = host { algorithm }
        host.contentView.background = leftHalfDrawable(Color.argb(128, 255, 0, 0))
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)

        val result = draw(host)

        assertEquals(128, Color.alpha(algorithm.capturedPixel))
        assertEquals(Color.TRANSPARENT, result.getPixel(30, 30))
        assertEquals(Color.argb(128, 0, 255, 0), result.getPixel(270, 30))
        assertEquals(1f, host.contentView.alpha, 0f)
        assertTrue(host.contentView.parent is BlurTarget)
        assertTrue((0 until host.childCount).any { host.getChildAt(it) is BlurView })
        assertFalse((host.contentView.parent as BlurTarget).let { target ->
            (0 until target.childCount).any { target.getChildAt(it) is BlurView }
        })
        host.release()
    }

    @Test
    fun nearZeroRadiusHandsBackClearListAndStopsCapturing() {
        val algorithm = RecordingAlgorithm(replaceWithGreen = true)
        val host = host { algorithm }
        host.contentView.setBackgroundColor(Color.RED)
        host.updateBlurFrame(0.3f, 1f, enabled = true, visible = true)
        draw(host)
        assertTrue(algorithm.radii.last() < 1f)
        val captures = algorithm.radii.size

        host.updateBlurFrame(0f, 1f, enabled = true, visible = true)
        val clear = draw(host)
        host.viewTreeObserver.dispatchOnPreDraw()
        draw(host)

        assertEquals(Color.RED, clear.getPixel(30, 30))
        assertEquals(captures, algorithm.radii.size)
        assertEquals(0, algorithm.destroyCount)
        host.release()
        assertEquals(1, algorithm.destroyCount)
    }

    @Test
    fun nearZeroBlendPreservesTranslucentAlphaAndWeightsBothImages() {
        val sourceColor = Color.argb(128, 255, 0, 0)
        val identity = host { RecordingAlgorithm() }
        identity.contentView.setBackgroundColor(sourceColor)
        for (radius in listOf(0.25f, 0.5f, 0.75f)) {
            identity.updateBlurFrame(radius, 1f, enabled = true, visible = true)
            val pixel = draw(identity).getPixel(30, 30)
            assertEquals("Alpha changed at $radius", 128.0, Color.alpha(pixel).toDouble(), 1.0)
            assertEquals(255, Color.red(pixel))
        }
        identity.release()

        val shifted = host { RecordingAlgorithm(replaceWithGreen = true) }
        shifted.contentView.background = leftHalfDrawable(sourceColor)
        shifted.updateBlurFrame(0.5f, 1f, enabled = true, visible = true)
        val midpoint = draw(shifted)
        assertEquals(64.0, Color.alpha(midpoint.getPixel(30, 30)).toDouble(), 1.0)
        assertEquals(64.0, Color.alpha(midpoint.getPixel(270, 30)).toDouble(), 1.0)
        shifted.release()
    }

    @Test
    fun disableHideAndReleaseDestroyResourcesAndDoNotKeepPreDrawWork() {
        val algorithms = mutableListOf<RecordingAlgorithm>()
        val host = host { RecordingAlgorithm().also(algorithms::add) }
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)
        val captures = algorithms.single().radii.size
        repeat(3) { host.viewTreeObserver.dispatchOnPreDraw() }
        assertEquals(captures, algorithms.single().radii.size)

        host.updateBlurFrame(10f, 1f, enabled = false, visible = true)
        assertEquals(1, algorithms.single().destroyCount)
        draw(host)
        assertEquals(1, algorithms.size)
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)
        host.updateBlurFrame(10f, 1f, enabled = true, visible = false)
        assertEquals(1, algorithms.last().destroyCount)
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)
        host.release()
        host.release()
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)
        assertEquals(3, algorithms.size)
        assertTrue(algorithms.all { it.destroyCount == 1 })
    }

    @Test
    fun swallowedCaptureFailureRestoresOriginalAndRetriesOnlyNextTransition() {
        val algorithms = mutableListOf<RecordingAlgorithm>()
        val host = host { RecordingAlgorithm(replaceWithGreen = true).also(algorithms::add) }
        var failCapture = false
        host.contentView.background = object : Drawable() {
            override fun draw(canvas: Canvas) {
                if (failCapture && canvas is BlurViewCanvas) error("Hardware bitmap capture failed")
                canvas.drawColor(Color.RED)
            }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
            @Deprecated("Deprecated in Android")
            override fun getOpacity() = PixelFormat.OPAQUE
        }
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)
        failCapture = true
        host.onDescendantInvalidated(host.contentView, host.contentView)

        val fallback = draw(host)
        repeat(3) { draw(host) }

        assertEquals(Color.RED, fallback.getPixel(30, 30))
        assertEquals(1, algorithms.size)
        assertEquals(1, algorithms.single().destroyCount)
        host.updateBlurFrame(0f, 1f, enabled = true, visible = true)
        failCapture = false
        host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        draw(host)
        assertEquals(2, algorithms.size)
        host.release()
    }

    @Test
    fun initializationAndAlgorithmFailuresFallBackWithoutDrawingPartialBitmap() {
        var attempts = 0
        val initializationFailure = host {
            attempts++
            error("RenderScript unavailable")
        }
        initializationFailure.contentView.setBackgroundColor(Color.RED)
        initializationFailure.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        assertEquals(Color.RED, draw(initializationFailure).getPixel(30, 30))
        draw(initializationFailure)
        assertEquals(1, attempts)
        initializationFailure.release()

        val brokenAlgorithm = RecordingAlgorithm(failBlur = true)
        val blurFailure = host { brokenAlgorithm }
        blurFailure.contentView.setBackgroundColor(Color.RED)
        blurFailure.updateBlurFrame(10f, 1f, enabled = true, visible = true)
        assertEquals(Color.RED, draw(blurFailure).getPixel(30, 30))
        assertEquals(1, brokenAlgorithm.destroyCount)
        blurFailure.release()
    }

    @Test
    fun zeroAlphaAndInvalidRadiusNeverAllocateBlurResources() {
        var attempts = 0
        val host = host { attempts++; RecordingAlgorithm() }
        host.updateBlurFrame(10f, 0f, enabled = true, visible = true)
        draw(host)
        host.updateBlurFrame(Float.NaN, 1f, enabled = true, visible = true)
        draw(host)
        assertEquals(0, attempts)
        host.release()
    }

    @Test
    fun allFiveLayoutsKeepTheirRowsAndReceiveTouchThroughBlurSibling() {
        val host = host { RecordingAlgorithm() }
        val content = host.contentView
        val entries = (0 until 24).map { index ->
            AppListEntry(
                key = AppItemKey("component:blur-host-$index"),
                label = "App $index",
                type = AppItemType.APP,
                launchTarget = LaunchTarget.Component("com.example", ".MainActivity"),
                iconKey = IconKey("icon:blur-host-$index"),
                folderChildren = emptyList(),
            )
        }
        content.submit(NativeAppListContent(AppListSnapshot.create(1L, entries), emptyMap()))
        val commands = mutableListOf<AppListCommand>()
        content.onCommand = { commands += it; true }
        val strategies = listOf(
            NativeAppListPresentation.LINEAR to LinearNativeAppListLayoutStrategy,
            NativeAppListPresentation.DENSE_GRID to DenseGridNativeAppListLayoutStrategy,
            NativeAppListPresentation.HONEYCOMB to HoneycombNativeAppListLayoutStrategy,
            NativeAppListPresentation.FLOW to FlowNativeAppListLayoutStrategy,
            NativeAppListPresentation.RING to RingNativeAppListLayoutStrategy,
        )
        for ((presentation, strategy) in strategies) {
            content.setConfig(
                NativeAppListConfig(
                    presentation = presentation,
                    rowHeightPx = 48,
                    iconSizePx = 32,
                    entryMotionEnabled = false,
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
                ),
            )
            content.setLayoutStrategy(strategy)
            host.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 320, 320)
            host.updateBlurFrame(10f, 1f, enabled = true, visible = true)
            draw(host)
            assertEquals(24, content.adapter?.itemCount)
            assertTrue("No rows for $presentation", content.childCount > 0)
            commands.clear()
            val row = requireNotNull(content.layoutManager?.findViewByPosition(0))
            val x = row.left + row.width / 2f + row.translationX
            val y = row.top + row.height / 2f + row.translationY
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(downTime, downTime + action * 40L, action, x, y, 0).also { event ->
                    host.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Tap did not reach $presentation", listOf(entries.first().key),
                commands.filterIsInstance<AppListCommand.Open>().map { it.key })
        }
        host.release()
    }

    @Test
    @Config(sdk = [31])
    fun android12NeverCreatesLegacyLibraryViewsOrAlgorithm() {
        var attempts = 0
        val host = host { attempts++; RecordingAlgorithm() }
        host.updateBlurFrame(20f, 1f, enabled = true, visible = true)

        draw(host)

        assertEquals(0, attempts)
        assertEquals(1, host.childCount)
        assertSame(host, host.contentView.parent)
        host.release()
    }

    private fun host(factory: (Context) -> BlurAlgorithm): NativeAppListBlurHost =
        NativeAppListBlurHost(RuntimeEnvironment.getApplication(), factory).apply {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().also(activities::add)
            activity.get().setContentView(this)
            activity.visible()
            measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 320, 320)
        }

    private fun draw(host: NativeAppListBlurHost): Bitmap =
        Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).also {
            host.viewTreeObserver.dispatchOnPreDraw()
            host.draw(Canvas(it))
        }

    private fun leftHalfDrawable(color: Int) = object : Drawable() {
        private val paint = Paint().apply { this.color = color }
        override fun draw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, bounds.width() / 2f, bounds.height().toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun countingDrawable(onCapture: () -> Unit) = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (canvas is BlurViewCanvas) onCapture()
            canvas.drawColor(Color.RED)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    private class RecordingAlgorithm(
        val replaceWithGreen: Boolean = false,
        val failBlur: Boolean = false,
    ) : BlurAlgorithm {
        val radii = mutableListOf<Float>()
        var width = 0
        var capturedPixel = Color.TRANSPARENT
        var destroyCount = 0
        override fun blur(bitmap: Bitmap, blurRadius: Float): Bitmap {
            radii += blurRadius
            width = bitmap.width
            capturedPixel = bitmap.getPixel(10, 10)
            if (failBlur) error("Blur kernel failed")
            if (replaceWithGreen) {
                bitmap.eraseColor(Color.TRANSPARENT)
                Canvas(bitmap).drawRect(
                    bitmap.width / 2f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                    Paint().apply { color = Color.argb(128, 0, 255, 0) },
                )
            }
            return bitmap
        }
        override fun render(canvas: Canvas, bitmap: Bitmap) {
            canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        override fun destroy() { destroyCount++ }
        override fun canModifyBitmap() = true
        override fun getSupportedBitmapConfig() = Bitmap.Config.ARGB_8888
    }
}
