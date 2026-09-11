package com.flue.launcher.watchface.standard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/** Colors one complete glyph mask. Previously drawn outlines/shadows are outside this layer. */
internal class WatchTextShaderPainter {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sourceIn = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    private val whiteMask = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        maskPaint: Paint,
        baseColor: Int,
        effect: WatchShapePaintEffect?,
        drawMask: () -> Unit,
    ) {
        if (effect == null || bounds.isEmpty) { drawMask(); return }
        val color = maskPaint.color
        val filter = maskPaint.colorFilter
        val shader = maskPaint.shader
        val alpha = maskPaint.alpha
        val originalShaderMatrix = effect.shader?.let { gradient -> Matrix().also { gradient.getLocalMatrix(it) } }
        // Native glyph bounds use integer ink extents. Keep one pixel around
        // that domain for fractional positioning and antialias coverage; the
        // gradient itself still spans the complete text's actual ink bounds.
        val rasterBounds = RectF(bounds).apply { inset(-1f, -1f) }
        val save = canvas.saveLayer(rasterBounds, null)
        try {
            maskPaint.color = Color.WHITE
            maskPaint.alpha = alpha
            maskPaint.shader = null
            maskPaint.colorFilter = whiteMask
            drawMask()
            effect.shader?.let { gradient ->
                val local = Matrix()
                gradient.getLocalMatrix(local)
                local.postTranslate(bounds.centerX(), bounds.centerY())
                gradient.setLocalMatrix(local)
            }
            fillPaint.color = baseColor
            fillPaint.shader = effect.shader
            fillPaint.colorFilter = effect.colorFilter
            fillPaint.xfermode = sourceIn
            canvas.drawRect(rasterBounds, fillPaint)
        } finally {
            maskPaint.color = color
            maskPaint.alpha = alpha
            maskPaint.colorFilter = filter
            maskPaint.shader = shader
            fillPaint.shader = null
            fillPaint.colorFilter = null
            fillPaint.xfermode = null
            if (originalShaderMatrix != null) effect.shader?.setLocalMatrix(originalShaderMatrix)
            canvas.restoreToCount(save)
        }
    }
}
