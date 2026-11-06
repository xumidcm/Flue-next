package com.flue.launcher.ui.anim

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 30, 31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyBackdropPixelsTest {
    @Test
    fun clearSceneAndReflectedEdgesUseTheSameRealPixelsWithMaterialOff() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0..99) for (x in 0..99) source.setPixel(x, y, Color.rgb(20 + x * 2, 20 + y * 2, 30))
        val capture = LegacyWatchFaceCapturedFrame("face-a", "100x100", 7L, source)
        val frames = requireNotNull(prepareCapturedBackdropFrames(capture, IntSize(100, 100), Color.BLACK, false))
        assertFalse(frames.hasPreparedBlur)
        assertSame(frames.clear, frames.darkBlurred)
        val output = render(frames, scale = .8f, translationY = 8f)
        // Pixels outside the shrunken centre reflect its adjacent edge, not a blurred backdrop.
        for ((x, y) in listOf(1 to 1, 5 to 45, 95 to 45, 50 to 5, 50 to 95, 50 to 50)) {
            val sx = reflected((x - 10f) / .8f)
            val sy = reflected((y - 18f) / .8f)
            val pixel = output.getPixel(x, y)
            assertEquals(255, Color.alpha(pixel))
            assertEquals((20 + sx * 2).toDouble(), Color.red(pixel).toDouble(), 5.0)
            assertEquals((20 + sy * 2).toDouble(), Color.green(pixel).toDouble(), 5.0)
        }
    }

    @Test
    fun compositeAppliesParentAlphaOnlyOnceAndDisabledBlurDoesNotTintTheScene() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val frames = requireNotNull(prepareCapturedBackdropFrames(
            LegacyWatchFaceCapturedFrame("face", "bounds", 1L, source), IntSize(100, 100), Color.BLACK, false,
        ))
        val green = Bitmap.createBitmap(frames.clear.width, frames.clear.height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.GREEN) }.asImageBitmap()
        val prepared = frames.copy(darkBlurred = green, lightBlurred = green, hasPreparedBlur = true)
        val disabled = render(prepared, alpha = .5f, blur = false, blurProgress = .5f)
        assertEquals(128.0, Color.alpha(disabled.getPixel(50, 50)).toDouble(), 1.0)
        assertEquals(255, Color.red(disabled.getPixel(50, 50)))
        val mixed = render(prepared, alpha = .5f, blur = true, blurProgress = .5f)
        assertEquals(128.0, Color.alpha(mixed.getPixel(50, 50)).toDouble(), 1.0)
        assertTrue(Color.green(mixed.getPixel(50, 50)) > 100)
    }

    @Test
    fun wrongViewportOrRecycledCaptureCannotBecomeAPreparedFrame() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val capture = LegacyWatchFaceCapturedFrame("face", "bounds", 2L, bitmap)
        assertNull(prepareCapturedBackdropFrames(capture, IntSize(80, 100), Color.BLACK, false))
        bitmap.recycle()
        assertNull(prepareCapturedBackdropFrames(capture, IntSize(100, 100), Color.BLACK, true))
    }

    @Test
    fun clearFastPathDimsBeforeApplyingOpacityAndDoesNotDarkenTheBackground() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val frames = requireNotNull(prepareCapturedBackdropFrames(
            LegacyWatchFaceCapturedFrame("face", "bounds", 3L, source), IntSize(100, 100), Color.BLACK, false,
        ))
        val output = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output.asImageBitmap()), Size(100f, 100f)) {
            LegacyBackdropPainter().draw(this, frames, .9f, 3f, .5f, 1f, .7f, false, true)
        }
        val pixel = output.getPixel(50, 50)
        assertEquals(255, Color.alpha(pixel))
        assertEquals(102.0, Color.red(pixel).toDouble(), 1.0)
        assertEquals(127.0, Color.blue(pixel).toDouble(), 1.0)
    }

    private fun reflected(value: Float): Int = (if (value < 0) -value - 1 else if (value >= 100) 199 - value else value).toInt()

    private fun render(
        frames: LegacyWatchFaceBackdropFrames,
        scale: Float = 1f,
        translationY: Float = 0f,
        alpha: Float = 1f,
        blur: Boolean = false,
        blurProgress: Float = 0f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(100f, 100f)) {
            LegacyBackdropPainter().draw(this, frames, scale, translationY, alpha, 0f, blurProgress, blur, true)
        }
        return bitmap
    }
}
