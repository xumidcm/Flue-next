package com.flue.launcher.applist.nativeview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.MotionEvent
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
    private val iconClipPath = Path()
    private val labelFontMetrics = Paint.FontMetrics()

    private var config = NativeAppListConfig()
    private val iconRenderOwner = NativeAppListIconRenderOwner()
    private var label: String = ""
    private var geometryDirty = true
    private var folderHover = false
    private var moveTranslationX = 0f
    private var moveTranslationY = 0f
    private var dragTranslationY = 0f
    private var flowTranslationY = 0f
    private var visualScale = 1f
    private var interactionScale = 1f
    private var dragVisual = false
    private var interactionAnimator: ValueAnimator? = null
    private var iconBlurRadiusBucketPx = 0
    private val iconEffectRenderer: NativeIconEffectRenderer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderNodeNativeIconEffectRenderer()
        } else {
            null
        }
    private val drawIconContentBlock: (Canvas) -> Unit = ::drawIconContent

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
        iconEffectRenderer?.invalidateContent()
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
                || this.config.iconShape != next.iconShape
                || this.config.iconCornerRadiusPx != next.iconCornerRadiusPx
        this.config = next
        geometryDirty = true
        iconEffectRenderer?.invalidateContent()
        updatePaints()
        if (drawingMetricsChanged) {
            requestLayout()
        }
        invalidate()
    }

    fun clearBinding() {
        iconRenderOwner.clear()
        iconEffectRenderer?.discardContent()
        label = ""
        contentDescription = null
        setOnClickListener(null)
        setOnLongClickListener(null)
        isClickable = false
        isLongClickable = false
        isPressed = false
        folderHover = false
        dragVisual = false
        interactionAnimator?.cancel()
        interactionAnimator = null
        visualScale = 1f
        interactionScale = 1f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        moveTranslationX = 0f
        moveTranslationY = 0f
        dragTranslationY = 0f
        flowTranslationY = 0f
        rotation = 0f
        setIconBlurRadiusPx(0f, NativeBlurTier.OFF)
        invalidate()
    }

    fun setIconBlurRadiusPx(radiusPx: Float, tier: NativeBlurTier) {
        val nextBucket = if (tier == NativeBlurTier.SYSTEM && Build.VERSION.SDK_INT >= 31) {
            quantizeNativeBlurRadiusPx(radiusPx)
        } else {
            0
        }
        if (nextBucket == iconBlurRadiusBucketPx) return
        iconBlurRadiusBucketPx = nextBucket
        iconEffectRenderer?.setBlurRadiusBucket(nextBucket)
        invalidate()
    }

    fun setFolderHoverState(enabled: Boolean) {
        if (folderHover == enabled) return
        folderHover = enabled
        updateInteractionScale()
        invalidate()
    }

    fun setVisualScale(scale: Float) {
        val next = scale.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 1f
        if (visualScale == next) return
        visualScale = next
        applyCompositeScale()
    }

    fun setDragVisualState(enabled: Boolean) {
        if (dragVisual == enabled) return
        dragVisual = enabled
        updateInteractionScale()
    }

    fun setMoveTranslation(x: Float, y: Float) {
        moveTranslationX = x
        moveTranslationY = y
        applyCompositeTranslation()
    }

    fun setDragTranslationY(y: Float) {
        dragTranslationY = y
        applyCompositeTranslation()
    }

    fun setFlowTranslationY(y: Float) {
        flowTranslationY = y
        applyCompositeTranslation()
    }

    fun positionTranslationYWithoutFlow(): Float = moveTranslationY + dragTranslationY

    private fun applyCompositeTranslation() {
        translationX = moveTranslationX
        translationY = moveTranslationY + dragTranslationY + flowTranslationY
    }

    private fun updateInteractionScale() {
        val target = nativeInteractionScale(isPressed, dragVisual, folderHover)
        if (target == interactionScale) return
        interactionAnimator?.cancel()
        if (!isLaidOut) {
            interactionScale = target
            applyCompositeScale()
            return
        }
        interactionAnimator = ValueAnimator.ofFloat(interactionScale, target).apply {
            duration = 170L
            addUpdateListener { animator ->
                interactionScale = animator.animatedValue as Float
                applyCompositeScale()
            }
            start()
        }
    }

    private fun applyCompositeScale() {
        val scale = visualScale * interactionScale
        scaleX = scale
        scaleY = scale
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
        iconEffectRenderer?.invalidateContent()
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

        if (iconBlurRadiusBucketPx > 0 && iconEffectRenderer != null) {
            iconEffectRenderer.draw(canvas, width, height, drawIconContentBlock)
        } else {
            drawIconContent(canvas)
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

        if (isPressed || folderHover) {
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
        updateInteractionScale()
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        ensureGeometry()
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !rowBounds.contains(event.x, event.y)) {
            return false
        }
        return super.onTouchEvent(event)
    }

    private fun drawIconContent(canvas: Canvas) {
        val saveCount = canvas.save()
        canvas.clipPath(iconClipPath)
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
        canvas.restoreToCount(saveCount)
    }

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
        rowBounds.set(
            config.leftSafeInsetPx.toFloat().coerceAtMost(width.toFloat()),
            0f,
            width.toFloat(),
            height.toFloat(),
        )

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

        iconClipPath.reset()
        when (config.iconShape) {
            NativeAppListIconShape.CIRCLE -> iconClipPath.addCircle(
                iconBounds.centerX(),
                iconBounds.centerY(),
                min(iconBounds.width(), iconBounds.height()) / 2f,
                Path.Direction.CW,
            )
            NativeAppListIconShape.ROUNDED_RECT -> {
                val radius = config.iconCornerRadiusPx.coerceAtMost(
                    min(iconBounds.width(), iconBounds.height()) / 2f,
                )
                iconClipPath.addRoundRect(iconBounds, radius, radius, Path.Direction.CW)
            }
        }

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
