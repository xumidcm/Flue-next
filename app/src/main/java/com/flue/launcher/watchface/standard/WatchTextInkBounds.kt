package com.flue.launcher.watchface.standard

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Advance widths position lines; glyph ink, including overhang, bounds their shader mask. */
internal fun watchTtfTextInkBounds(
    paint: Paint,
    lines: List<String>,
    lineStartXs: List<Float>,
    baselines: List<Float>,
): RectF {
    val result = RectF()
    val glyphBounds = Rect()
    lines.forEachIndexed { index, line ->
        if (line.isEmpty()) return@forEachIndexed
        paint.getTextBounds(line, 0, line.length, glyphBounds)
        if (!glyphBounds.isEmpty) {
            val x = lineStartXs[index]
            val baseline = baselines[index]
            result.union(x + glyphBounds.left, baseline + glyphBounds.top,
                x + glyphBounds.right, baseline + glyphBounds.bottom)
        }
    }
    return result
}

/** Matches drawBitmapGlyphRun's kerning/cursor rules, but unions destination ink rectangles. */
internal fun watchBitmapTextInkBounds(
    font: WatchBitmapFont,
    runs: List<Triple<String, Float, Float>>,
    scale: Float,
): RectF {
    val result = RectF()
    runs.forEach { (text, startX, lineTop) ->
        var cursorX = startX
        var previous: Int? = null
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val glyph = font.glyphs[codePoint]
            if (glyph != null) {
                cursorX += (previous?.let { font.kerning(it, codePoint) } ?: 0) * scale
                if (glyph.width > 0 && glyph.height > 0) {
                    result.union(cursorX + glyph.xOffset * scale, lineTop + glyph.yOffset * scale,
                        cursorX + (glyph.xOffset + glyph.width) * scale,
                        lineTop + (glyph.yOffset + glyph.height) * scale)
                }
                cursorX += glyph.xAdvance * scale
                previous = codePoint
            }
            index += Character.charCount(codePoint)
        }
    }
    return result
}
