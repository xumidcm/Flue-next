package com.flue.launcher.applist.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.NormalizedPoint
import kotlin.math.min

internal class NativeAppListRowView(
    context: Context,
) : View(context) {
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fallbackBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fallbackForegroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rowBounds = RectF()
    private val borderBounds = RectF()
    private val iconBounds = RectF()
    private val fallbackPath = Path()
    private val labelFontMetrics = Paint.FontMetrics()

    private var config = NativeAppListConfig()
    private val iconRenderOwner = NativeAppListIconRenderOwner()
    private var label: String = ""
    private var geometryDirty = true

    init {
        isClickable = false
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updatePaints()
    }

    fun bind(
        row: NativeAppListBoundRow<Bitmap>,
        config: NativeAppListResolvedConfig,
    ) {
        applyConfig(config)
        iconRenderOwner.bind(row.icon)
        label = row.entry.label
        contentDescription = row.entry.label
        isClickable = true
        invalidate()
    }

    fun applyConfig(config: NativeAppListResolvedConfig) {
        val next = config.value
        if (this.config == next) return
        val drawingMetricsChanged =
            this.config.rowHeightPx != next.rowHeightPx ||
                this.config.iconSizePx != next.iconSizePx ||
                this.config.leftSafeInsetPx != next.leftSafeInsetPx ||
                this.config.rowPaddingStartPx != next.rowPaddingStartPx ||
                this.config.rowPaddingTopPx != next.rowPaddingTopPx ||
                this.config.rowPaddingEndPx != next.rowPaddingEndPx ||
                this.config.rowPaddingBottomPx != next.rowPaddingBottomPx ||
                this.config.iconLabelGapPx != next.iconLabelGapPx
        this.config = next
        geometryDirty = true
        updatePaints()
        if (drawingMetricsChanged) {
            requestLayout()
        }
        invalidate()
    }

    fun clearBinding() {
        iconRenderOwner.clear()
        label = ""
        contentDescription = null
        setOnClickListener(null)
        isClickable = false
        isPressed = false
        invalidate()
    }

    fun normalizedIconOrigin(viewport: RecyclerView): NormalizedPoint {
        ensureGeometry()
        return normalizedIconCenterAfterChildTransform(
            iconBounds = NativeAppListIconBounds(
                left = iconBounds.left,
                top = iconBounds.top,
                right = iconBounds.right,
                bottom = iconBounds.bottom,
            ),
            childLeft = left.toFloat(),
            childTop = top.toFloat(),
            childTranslationX = translationX,
            childTranslationY = translationY,
            childScaleX = scaleX,
            childScaleY = scaleY,
            childPivotX = pivotX,
            childPivotY = pivotY,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (
            config.leftSafeInsetPx.toLong() +
                config.rowPaddingStartPx.toLong() +
                config.iconSizePx.toLong() +
                config.iconLabelGapPx.toLong() +
                config.rowPaddingEndPx.toLong()
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        setMeasuredDimension(
            resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(config.rowHeightPx, heightMeasureSpec, 0),
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometryDirty = true
        ensureGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureGeometry()

        if (config.palette.rowBackgroundColorArgb ushr 24 != 0L) {
            canvas.drawRoundRect(
                rowBounds,
                config.rowBorder.cornerRadiusPx,
                config.rowBorder.cornerRadiusPx,
                rowPaint,
            )
        }

        if (config.rowBorder.enabled && config.rowBorder.widthPx > 0f) {
            val halfStroke = config.rowBorder.widthPx / 2f
            val maximumInset = min(rowBounds.width(), rowBounds.height()) / 2f
            val borderInset = halfStroke.coerceAtMost(maximumInset)
            borderBounds.set(rowBounds)
            borderBounds.inset(borderInset, borderInset)
            canvas.drawRoundRect(
                borderBounds,
                config.rowBorder.cornerRadiusPx,
                config.rowBorder.cornerRadiusPx,
                borderPaint,
            )
        }

        val drawableBitmap = iconRenderOwner.bitmapForDraw()
        if (drawableBitmap != null) {
            canvas.drawBitmap(drawableBitmap, null, iconBounds, iconPaint)
        } else {
            canvas.drawRoundRect(
                iconBounds,
                iconBounds.width() * 0.25f,
                iconBounds.height() * 0.25f,
                fallbackBackgroundPaint,
            )
            canvas.drawPath(fallbackPath, fallbackForegroundPaint)
        }

        val labelLeft = iconBounds.right + config.iconLabelGapPx
        val labelRight = width.toFloat() - config.rowPaddingEndPx
        if (label.isNotEmpty() && labelRight > labelLeft) {
            labelPaint.getFontMetrics(labelFontMetrics)
            val baseline = height / 2f -
                (labelFontMetrics.ascent + labelFontMetrics.descent) / 2f
            val saveCount = canvas.save()
            canvas.clipRect(labelLeft, 0f, labelRight, height.toFloat())
            canvas.drawText(label, labelLeft, baseline, labelPaint)
            canvas.restoreToCount(saveCount)
        }

        if (isPressed) {
            canvas.drawRoundRect(
                rowBounds,
                config.rowBorder.cornerRadiusPx,
                config.rowBorder.cornerRadiusPx,
                pressedPaint,
            )
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    private fun updatePaints() {
        rowPaint.color = config.palette.rowBackgroundColorArgb.toInt()
        borderPaint.color = config.rowBorder.colorArgb.toInt()
        borderPaint.strokeWidth = config.rowBorder.widthPx
        labelPaint.color = config.palette.labelColorArgb.toInt()
        labelPaint.textSize = config.labelTextSizePx
        pressedPaint.color = config.palette.pressedOverlayColorArgb.toInt()
        fallbackBackgroundPaint.color =
            config.palette.fallbackIconBackgroundColorArgb.toInt()
        fallbackForegroundPaint.color =
            config.palette.fallbackIconForegroundColorArgb.toInt()
    }

    private fun ensureGeometry() {
        if (!geometryDirty) return
        rowBounds.set(0f, 0f, width.toFloat(), height.toFloat())

        val availableIconHeight =
            height - config.rowPaddingTopPx - config.rowPaddingBottomPx
        val iconTop = config.rowPaddingTopPx +
            (availableIconHeight - config.iconSizePx) / 2f
        val iconLeft = config.leftSafeInsetPx + config.rowPaddingStartPx.toFloat()
        iconBounds.set(
            iconLeft,
            iconTop,
            iconLeft + config.iconSizePx,
            iconTop + config.iconSizePx,
        )

        val tileSize = iconBounds.width() * 0.16f
        val tileGap = iconBounds.width() * 0.08f
        val tileGridSize = tileSize * 2f + tileGap
        val tileLeft = iconBounds.centerX() - tileGridSize / 2f
        val tileTop = iconBounds.centerY() - tileGridSize / 2f
        fallbackPath.reset()
        fallbackPath.addRect(
            tileLeft,
            tileTop,
            tileLeft + tileSize,
            tileTop + tileSize,
            Path.Direction.CW,
        )
        fallbackPath.addRect(
            tileLeft + tileSize + tileGap,
            tileTop,
            tileLeft + tileGridSize,
            tileTop + tileSize,
            Path.Direction.CW,
        )
        fallbackPath.addRect(
            tileLeft,
            tileTop + tileSize + tileGap,
            tileLeft + tileSize,
            tileTop + tileGridSize,
            Path.Direction.CW,
        )
        fallbackPath.addRect(
            tileLeft + tileSize + tileGap,
            tileTop + tileSize + tileGap,
            tileLeft + tileGridSize,
            tileTop + tileGridSize,
            Path.Direction.CW,
        )
        geometryDirty = false
    }
}
