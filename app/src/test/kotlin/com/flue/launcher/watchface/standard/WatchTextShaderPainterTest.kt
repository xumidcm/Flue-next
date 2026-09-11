package com.flue.launcher.watchface.standard

import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchTextShaderPainterTest {
    private val bounds = RectF(0f, 0f, 150f, 80f)
    private fun effect() = WatchShapePaintEffect(
        LinearGradient(-75f, 0f, 75f, 0f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP), null)

    private fun effectFor(ink: RectF) = WatchShapePaintEffect(
        LinearGradient(-ink.width() / 2f, 0f, ink.width() / 2f, 0f,
            Color.RED, Color.BLUE, Shader.TileMode.CLAMP), null)

    @Test fun bitmapNegativeBearingAndInkWiderThanAdvanceAreFullyColored() {
        val glyph = WatchBitmapGlyph('A'.code, 0, 0, 20, 20, -4, -3, 12, 0)
        val font = WatchBitmapFont("bearing", 20, 12, 10, 20, 20,
            listOf("atlas.png"), mapOf('A'.code to glyph), emptyMap())
        val ink = watchBitmapTextInkBounds(font, listOf(Triple("AA", 20f, 20f)), 1f)
        assertEquals(RectF(16f, 17f, 48f, 37f), ink)
        val atlas = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        WatchTextShaderPainter().draw(canvas, ink, paint, Color.WHITE, effectFor(ink)) {
            canvas.drawBitmap(atlas, null, RectF(16f, 17f, 36f, 37f), paint)
            canvas.drawBitmap(atlas, null, RectF(28f, 17f, 48f, 37f), paint)
        }
        // Both points lie outside the former [20,44] x [20,32] advance box.
        assertEquals(255, Color.alpha(output.getPixel(16, 17)))
        assertEquals(255, Color.alpha(output.getPixel(47, 36)))
        assertTrue(Color.red(output.getPixel(16, 17)) > Color.blue(output.getPixel(16, 17)))
        assertTrue(Color.blue(output.getPixel(47, 36)) > Color.red(output.getPixel(47, 36)))
        assertEquals(0, Color.alpha(output.getPixel(15, 17)))
    }

    @Test fun italicTrueTypeOverhangMatchesAllPixelsOfUnclippedGlyphMask() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            textSkewX = -0.75f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        val text = "fffj"
        val x = 24.25f
        val baseline = 74.5f
        val ink = watchTtfTextInkBounds(paint, listOf(text), listOf(x), listOf(baseline))
        val advanceRight = x + paint.measureText(text)
        val reference = Bitmap.createBitmap(240, 110, Bitmap.Config.ARGB_8888)
        Canvas(reference).drawText(text, x, baseline, paint)
        val actual = Bitmap.createBitmap(240, 110, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(actual)
        WatchTextShaderPainter().draw(canvas, ink, paint, Color.WHITE, effectFor(ink)) {
            canvas.drawText(text, x, baseline, paint)
        }
        var overhangPixels = 0
        for (row in 0 until actual.height) for (column in 0 until actual.width) {
            val referenceAlpha = Color.alpha(reference.getPixel(column, row))
            if (referenceAlpha == 0) continue
            if (column < x || column >= advanceRight) overhangPixels++
            assertEquals("The shader must retain the complete glyph at $column,$row",
                referenceAlpha, Color.alpha(actual.getPixel(column, row)))
        }
        assertTrue("Fixture must include ink beyond the text advance", overhangPixels > 0)
    }

    @Test fun trueTypeGlyphsShareOneGradientAndKeepTransparentSpace() {
        val bitmap = Bitmap.createBitmap(150, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 54f; typeface = Typeface.DEFAULT; color = Color.GREEN }
        WatchTextShaderPainter().draw(canvas, bounds, paint, Color.WHITE, effect()) {
            canvas.drawText("H", 10f, 60f, paint)
            canvas.drawText("H", 95f, 60f, paint)
        }
        assertGradientAcrossGlyphs(bitmap)
        assertEquals(0, Color.alpha(bitmap.getPixel(75, 40)))
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(Color.GREEN, paint.color)
        assertNull(paint.shader)
        assertNull(paint.colorFilter)
        canvas.drawText("H", 55f, 60f, paint)
        assertTrue((50 until 95).any { x -> (10 until 70).any { y -> Color.green(bitmap.getPixel(x, y)) > 200 } })
    }

    @Test fun bitmapFontAtlasHolesStayTransparentAndDoNotRestartGradientPerGlyph() {
        val atlas = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        Canvas(atlas).apply {
            drawColor(Color.YELLOW)
            drawRect(5f, 5f, 15f, 25f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }
        val bitmap = Bitmap.createBitmap(150, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val originalFilter = PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
        val paint = Paint().apply { alpha = 190; colorFilter = originalFilter }
        // Represents an outline/shadow already painted underneath the fill.
        canvas.drawRect(3f, 3f, 7f, 70f, Paint().apply { color = Color.CYAN })
        WatchTextShaderPainter().draw(canvas, bounds, paint, Color.WHITE, effect()) {
            canvas.drawBitmap(atlas, null, RectF(10f, 10f, 50f, 70f), paint)
            canvas.drawBitmap(atlas, null, RectF(95f, 10f, 135f, 70f), paint)
        }
        assertEquals(0, Color.alpha(bitmap.getPixel(30, 40)))
        assertEquals(0, Color.alpha(bitmap.getPixel(115, 40)))
        assertTrue(Color.red(bitmap.getPixel(12, 20)) > Color.blue(bitmap.getPixel(12, 20)))
        assertTrue(Color.blue(bitmap.getPixel(132, 20)) > Color.red(bitmap.getPixel(132, 20)))
        assertEquals(190, Color.alpha(bitmap.getPixel(12, 20)))
        assertEquals(Color.CYAN, bitmap.getPixel(5, 40))
        assertSame(originalFilter, paint.colorFilter)
        assertEquals(190, paint.alpha)
    }

    @Test fun failedMaskDrawingRestoresPaintAndCanvasState() {
        val bitmap = Bitmap.createBitmap(150, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.MAGENTA; alpha = 120 }
        val before = canvas.saveCount
        runCatching { WatchTextShaderPainter().draw(canvas, bounds, paint, Color.WHITE, effect()) { error("mask failed") } }
        assertEquals(before, canvas.saveCount)
        assertEquals(120, paint.alpha)
        assertEquals(Color.red(Color.MAGENTA), Color.red(paint.color))
        assertNull(paint.colorFilter)
    }

    private fun assertGradientAcrossGlyphs(bitmap: Bitmap) {
        var left = 0L; var leftCount = 0; var right = 0L; var rightCount = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) < 200) continue
            if (x < 75) { left += Color.red(color) - Color.blue(color); leftCount++ }
            else { right += Color.blue(color) - Color.red(color); rightCount++ }
        }
        assertTrue("Both true glyphs must rasterize", leftCount > 50 && rightCount > 50)
        assertTrue("Left glyph shares the red end", left / leftCount > 50)
        assertTrue("Right glyph shares the blue end", right / rightCount > 50)
    }
}
