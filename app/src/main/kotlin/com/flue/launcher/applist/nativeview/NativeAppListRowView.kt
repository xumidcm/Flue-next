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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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
    private val glidePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x36000000
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
    private val iconShadowPath = Path()
    private val shadowBounds = RectF()
    private val labelFontMetrics = Paint.FontMetrics()

    private var config = NativeAppListConfig()
    private val iconRenderOwner = NativeAppListIconRenderOwner()
    private var boundRow: NativeAppListBoundRow<Bitmap>? = null
    private var label: String = ""
    private var geometryDirty = true
    private var folderHover = false
    private var glidePressed = false
    private var moveTranslationX = 0f
    private var moveTranslationY = 0f
    private var previewTranslationX = 0f
    private var previewTranslationY = 0f
    private var dragTranslationY = 0f
    private var dragTranslationX = 0f
    private var flowTranslationY = 0f
    private var flowTranslationX = 0f
    private var contentTranslationY = 0f
    private var visualScale = 1f
    private var interactionScale = 1f
    private var dragVisual = false
    private var interactionAnimator: ValueAnimator? = null
    private var iconBlurRadiusBucketPx = 0
    private var liteSaturationBucket = 0
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
        boundRow = row
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
                || this.config.denseGridIconInnerPaddingPx != next.denseGridIconInnerPaddingPx
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
        boundRow = null
        iconEffectRenderer?.discardContent()
        label = ""
        contentDescription = null
        // A recycled holder can be drawn once before its next bind. Clear all
        // geometry paths here so a previous icon/shadow never flashes in that
        // hand-off frame.
        iconClipPath.reset()
        iconShadowPath.reset()
        fallbackPath.reset()
        geometryDirty = true
        setOnClickListener(null)
        setOnLongClickListener(null)
        isClickable = false
        isLongClickable = false
        isPressed = false
        folderHover = false
        glidePressed = false
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
        previewTranslationX = 0f
        previewTranslationY = 0f
        dragTranslationY = 0f
        dragTranslationX = 0f
        flowTranslationY = 0f
        flowTranslationX = 0f
        contentTranslationY = 0f
        rotation = 0f
        setIconBlurRadiusPx(0f, NativeBlurTier.OFF)
        setIconSaturation(1f)
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

    fun setGlidePressedState(enabled: Boolean) {
        if (glidePressed == enabled) return
        glidePressed = enabled
        updateInteractionScale()
        invalidate()
    }

    fun setVisualScale(scale: Float) {
        val next = scale.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 1f
        if (visualScale == next) return
        visualScale = next
        applyCompositeScale()
    }

    fun visualScaleForOverlay(): Float = visualScale

    fun iconBlurRadiusForOverlay(): Float = iconBlurRadiusBucketPx.toFloat()

    fun boundRowForOverlay(): NativeAppListBoundRow<Bitmap>? = boundRow

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

    fun setPreviewTranslation(x: Float, y: Float) {
        val nextX = x.takeIf(Float::isFinite) ?: 0f
        val nextY = y.takeIf(Float::isFinite) ?: 0f
        if (previewTranslationX == nextX && previewTranslationY == nextY) return
        previewTranslationX = nextX
        previewTranslationY = nextY
        applyCompositeTranslation()
    }

    fun previewTranslationX(): Float = previewTranslationX

    fun previewTranslationY(): Float = previewTranslationY

    fun setIconSaturation(saturation: Float) {
        val bucket = quantizeNativeLiteSaturation(saturation)
        if (liteSaturationBucket == bucket) return
        liteSaturationBucket = bucket
        iconPaint.colorFilter = NativeLiteIconEffectCache.filterForBucket(bucket)
        iconEffectRenderer?.invalidateContent()
        invalidate()
    }

    fun setDragTranslationY(y: Float) {
        setDragTranslation(dragTranslationX, y)
    }

    fun setDragTranslation(x: Float, y: Float) {
        dragTranslationX = x
        dragTranslationY = y
        applyCompositeTranslation()
    }

    fun setFlowTranslationY(y: Float) {
        setFlowTranslation(flowTranslationX, y)
    }

    fun setFlowTranslation(x: Float, y: Float) {
        flowTranslationX = x
        flowTranslationY = y
        applyCompositeTranslation()
    }

    fun setContentTranslationY(y: Float) {
        val next = y.takeIf(Float::isFinite) ?: 0f
        if (contentTranslationY == next) return
        contentTranslationY = next
        applyCompositeTranslation()
    }

    fun positionTranslationYWithoutFlow(): Float =
        moveTranslationY + previewTranslationY + dragTranslationY + contentTranslationY

    fun positionTranslationXWithoutFlow(): Float =
        moveTranslationX + previewTranslationX + dragTranslationX

    private fun applyCompositeTranslation() {
        translationX =
            moveTranslationX + previewTranslationX + dragTranslationX + flowTranslationX
        translationY =
            moveTranslationY + previewTranslationY + dragTranslationY +
            flowTranslationY + contentTranslationY
    }

    private fun updateInteractionScale() {
        val target = nativeInteractionScale(
            pressed = isPressed,
            dragged = dragVisual,
            folderHover = folderHover,
            glidePressed = glidePressed,
        )
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
        // RecyclerView may dispatch a click in the same frame as a newly
        // attached child (before the host visual pass has initialized the
        // transform pivot).  Android's implicit pivot is not a stable source
        // of truth in that window; an edge row can therefore report a launch
        // origin several pixels above its icon once the entry scale is
        // applied.  Rows own their transform, so make the invariant explicit
        // before projecting the icon center.
        ensureCenteredTransformPivot()
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

    fun iconCenterXInParentWithoutFlow(): Float {
        ensureGeometry()
        return left + iconBounds.centerX() + positionTranslationXWithoutFlow()
    }

    fun iconCenterYInParentWithoutFlow(): Float {
        ensureGeometry()
        return top + iconBounds.centerY() + positionTranslationYWithoutFlow()
    }

    fun iconCenterXInParentWithoutPreviewOrFlow(): Float {
        ensureGeometry()
        return left + iconBounds.centerX() +
            moveTranslationX + dragTranslationX
    }

    fun iconCenterYInParentWithoutPreviewOrFlow(): Float {
        ensureGeometry()
        return top + iconBounds.centerY() +
            moveTranslationY + dragTranslationY + contentTranslationY
    }

    fun iconWidthForSafeGeometry(): Float {
        ensureGeometry()
        return iconBounds.width()
    }

    fun iconHeightForSafeGeometry(): Float {
        ensureGeometry()
        return iconBounds.height()
    }

    /** Strict transformed icon-center hit used only for folder dwell. */
    fun containsTransformedIconPoint(
        parentX: Float,
        parentY: Float,
        centerFraction: Float,
    ): Boolean {
        if (!parentX.isFinite() || !parentY.isFinite()) return false
        ensureGeometry()
        ensureCenteredTransformPivot()
        val fraction = centerFraction.coerceIn(0f, 1f)
        val transformedCenterX = left + translationX + pivotX +
            (iconBounds.centerX() - pivotX) * scaleX
        val transformedCenterY = top + translationY + pivotY +
            (iconBounds.centerY() - pivotY) * scaleY
        val halfWidth = iconBounds.width() * abs(scaleX) * fraction / 2f
        val halfHeight = iconBounds.height() * abs(scaleY) * fraction / 2f
        return parentX in (transformedCenterX - halfWidth)..(transformedCenterX + halfWidth) &&
            parentY in (transformedCenterY - halfHeight)..(transformedCenterY + halfHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = when (config.presentation) {
            NativeAppListPresentation.HONEYCOMB ->
                config.honeycombCellSizePx.toInt().coerceAtLeast(config.iconSizePx)
            NativeAppListPresentation.DENSE_GRID ->
                config.denseGridMinimumItemWidthPx.roundToInt().coerceAtLeast(1)
            NativeAppListPresentation.LINEAR -> (
                config.leftSafeInsetPx.toLong() +
                    config.rowPaddingStartPx.toLong() +
                    config.iconSizePx.toLong() +
                    config.iconLabelGapPx.toLong() +
                    config.rowPaddingEndPx.toLong()
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val desiredHeight = when (config.presentation) {
            NativeAppListPresentation.HONEYCOMB ->
                config.honeycombCellSizePx.toInt().coerceAtLeast(config.iconSizePx)
            NativeAppListPresentation.DENSE_GRID ->
                config.denseGridMinimumItemHeightPx.roundToInt().coerceAtLeast(1)
            NativeAppListPresentation.LINEAR -> config.rowHeightPx
        }
        setMeasuredDimension(
            resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, 0),
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometryDirty = true
        ensureCenteredTransformPivot()
        iconEffectRenderer?.invalidateContent()
        ensureGeometry()
    }

    private fun ensureCenteredTransformPivot() {
        if (width > 0) {
            val centerX = width / 2f
            if (pivotX != centerX) pivotX = centerX
        }
        if (height > 0) {
            val centerY = height / 2f
            if (pivotY != centerY) pivotY = centerY
        }
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

        if (config.iconShadowEnabled) {
            canvas.drawPath(iconShadowPath, iconShadowPaint)
        }

        if (iconBlurRadiusBucketPx > 0 && iconEffectRenderer != null) {
            iconEffectRenderer.draw(canvas, width, height, drawIconContentBlock)
        } else {
            drawIconContent(canvas)
        }

        val labelLeft = iconBounds.right + config.iconLabelGapPx
        val labelRight = width.toFloat() - config.rowPaddingEndPx
        if (
            config.presentation == NativeAppListPresentation.LINEAR &&
            label.isNotEmpty() &&
            labelRight > labelLeft
        ) {
            labelPaint.getFontMetrics(labelFontMetrics)
            val baseline = height / 2f -
                (labelFontMetrics.ascent + labelFontMetrics.descent) / 2f
            val saveCount = canvas.save()
            canvas.clipRect(labelLeft, 0f, labelRight, height.toFloat())
            canvas.drawText(label, labelLeft, baseline, labelPaint)
            canvas.restoreToCount(saveCount)
        }

        if (isPressed || folderHover || glidePressed) {
            val overlayPaint = if (glidePressed && !isPressed && !folderHover) {
                glidePressedPaint
            } else {
                pressedPaint
            }
            if (config.iconShape == NativeAppListIconShape.CIRCLE) {
                canvas.drawPath(iconClipPath, overlayPaint)
            } else {
                canvas.drawRoundRect(
                    rowBounds,
                    config.rowBorder.cornerRadiusPx,
                    config.rowBorder.cornerRadiusPx,
                    overlayPaint,
                )
            }
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
        val pressedColor = config.palette.pressedOverlayColorArgb.toInt()
        val glideAlpha = ((pressedColor ushr 24) * (5f / 12f)).roundToInt()
            .coerceIn(0, 255)
        glidePressedPaint.color = (pressedColor and 0x00ffffff) or (glideAlpha shl 24)
        fallbackBackgroundPaint.color =
            config.palette.fallbackIconBackgroundColorArgb.toInt()
        fallbackForegroundPaint.color =
            config.palette.fallbackIconForegroundColorArgb.toInt()
    }

    private fun ensureGeometry() {
        if (!geometryDirty) return
        rowBounds.set(
            if (config.presentation == NativeAppListPresentation.LINEAR) {
                config.leftSafeInsetPx.toFloat().coerceAtMost(width.toFloat())
            } else {
                0f
            },
            0f,
            width.toFloat(),
            height.toFloat(),
        )

        val iconSize: Float
        val iconTop: Float
        val iconLeft: Float
        when (config.presentation) {
            NativeAppListPresentation.HONEYCOMB -> {
                iconSize = config.iconSizePx.toFloat()
                iconTop = (height - iconSize) / 2f
                iconLeft = (width - iconSize) / 2f
            }
            NativeAppListPresentation.DENSE_GRID -> {
                val innerPadding = config.denseGridIconInnerPaddingPx
                    .coerceAtMost(min(width, height) / 2f)
                iconSize = (min(width, height) - innerPadding * 2f).coerceAtLeast(1f)
                iconTop = (height - iconSize) / 2f
                iconLeft = (width - iconSize) / 2f
            }
            NativeAppListPresentation.LINEAR -> {
                iconSize = config.iconSizePx.toFloat()
                val availableIconHeight =
                    height - config.rowPaddingTopPx - config.rowPaddingBottomPx
                iconTop = config.rowPaddingTopPx +
                    (availableIconHeight - iconSize) / 2f
                iconLeft = config.leftSafeInsetPx + config.rowPaddingStartPx.toFloat()
            }
        }
        iconBounds.set(
            iconLeft,
            iconTop,
            iconLeft + iconSize,
            iconTop + iconSize,
        )

        iconClipPath.reset()
        iconShadowPath.reset()
        when (config.iconShape) {
            NativeAppListIconShape.CIRCLE -> {
                iconClipPath.addCircle(
                    iconBounds.centerX(),
                    iconBounds.centerY(),
                    min(iconBounds.width(), iconBounds.height()) / 2f,
                    Path.Direction.CW,
                )
                val shadowOffset = (iconBounds.height() * 0.055f).coerceAtLeast(1f)
                iconShadowPath.addCircle(
                    iconBounds.centerX(),
                    iconBounds.centerY() + shadowOffset,
                    min(iconBounds.width(), iconBounds.height()) * 0.52f,
                    Path.Direction.CW,
                )
            }
            NativeAppListIconShape.ROUNDED_RECT -> {
                val radius = config.iconCornerRadiusPx.coerceAtMost(
                    min(iconBounds.width(), iconBounds.height()) / 2f,
                )
                iconClipPath.addRoundRect(iconBounds, radius, radius, Path.Direction.CW)
                val shadowOffset = (iconBounds.height() * 0.055f).coerceAtLeast(1f)
                val shadowSpread = (iconBounds.width() * 0.02f).coerceAtLeast(0.5f)
                shadowBounds.set(iconBounds)
                shadowBounds.inset(-shadowSpread, -shadowSpread)
                shadowBounds.offset(0f, shadowOffset)
                iconShadowPath.addRoundRect(
                    shadowBounds,
                    radius + shadowSpread,
                    radius + shadowSpread,
                    Path.Direction.CW,
                )
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
