package com.flue.launcher.applist.nativeview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
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
    // 图标阴影走真平台高程（RenderNode ambient+spot 双层高斯，机制与 Compose
    // 版 `graphicsLayer { shadowElevation = 8.dp }` 完全同源）。以前的
    // BlurMaskFilter 平涂近似是"黑环"观感的来源：30% 黑单层、几何外扩 4%、
    // 无光源方向感。轮廓由下方的 outlineProvider 按 iconBounds 提供。
    private val fallbackBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconShaderMatrix = Matrix()
    private val iconShaderSourceRect = RectF()
    private var iconShaderBitmap: Bitmap? = null
    private val fallbackForegroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rowBounds = RectF()
    private val borderBounds = RectF()
    private val iconBounds = RectF()
    private val fallbackPath = Path()
    private val iconClipPath = Path()
    private val pressOverlayPath = Path()
    private var pressOverlayUsesPath = false
    private val labelFontMetrics = Paint.FontMetrics()

    private var config = NativeAppListConfig()
    private val iconRenderOwner = NativeAppListIconRenderOwner()
    private var boundRow: NativeAppListBoundRow<Bitmap>? = null
    private var folderRemovalPending = false
    private var folderRemovalAlphaBeforePending = 1f
    /**
     * Accepted source/target rows can remain in RecyclerView's disappearing
     * child layer after their binding has been cleared. Keep the RenderNode
     * itself invisible until this View is genuinely rebound; relying on a new
     * empty onDraw display list races with that removal hand-off and can expose
     * the cached old icon as a background ghost.
     */
    private var committedFolderRemovalHidden = false
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
        // 高程阴影的轮廓按图标（而非整行）提供：阴影只属于图标本体。
        // clipToOutline 保持 false，绘制内容不受轮廓裁剪。
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                if (folderRemovalPending || boundRow == null) {
                    outline.setEmpty()
                    return
                }
                ensureGeometry()
                if (iconBounds.isEmpty) {
                    outline.setEmpty()
                    return
                }
                when (effectiveIconShape()) {
                    NativeAppListIconShape.CIRCLE -> outline.setOval(
                        iconBounds.left.toInt(),
                        iconBounds.top.toInt(),
                        iconBounds.right.toInt(),
                        iconBounds.bottom.toInt(),
                    )
                    NativeAppListIconShape.ROUNDED_RECT -> outline.setRoundRect(
                        iconBounds.left.toInt(),
                        iconBounds.top.toInt(),
                        iconBounds.right.toInt(),
                        iconBounds.bottom.toInt(),
                        config.iconCornerRadiusPx.coerceAtMost(
                            min(iconBounds.width(), iconBounds.height()) / 2f,
                        ),
                    )
                }
                // Keep elevation geometrically stable and fade only its
                // opacity. Toggling elevation at alpha ~= 0 produces a
                // different platform shadow on the final return frame.
                outline.alpha = view.alpha.coerceIn(0f, 1f)
            }
        }
        clipToOutline = false
        updatePaints()
    }

    /**
     * 图标本体的实际形状。蜂窝布局恒为圆形：格子只比圆图标略宽（见
     * pressOverlay 的同款规则），塞方角图标会顶到邻格，而且阴影轮廓也会
     * 在圆图标底下露出方角。MD3 / MIUIX 皮肤把 iconShape 设成
     * ROUNDED_RECT 是给线性 / 密网格用的，蜂窝不跟。
     */
    private fun effectiveIconShape(): NativeAppListIconShape =
        if (
            config.presentation == NativeAppListPresentation.HONEYCOMB ||
            config.presentation == NativeAppListPresentation.FLOW ||
            // 星环的格子只比圆图标略宽（RING_ICON_CELL_FRACTION 那圈空隙就是全部余量），
            // 与蜂窝同理：塞方角图标会顶到邻格。
            config.presentation == NativeAppListPresentation.RING
        ) {
            NativeAppListIconShape.CIRCLE
        } else {
            config.iconShape
        }

    /**
     * Elevation is a stable geometry property. Shadow opacity follows the
     * outline's alpha above, so return/fisheye fades remain continuous without
     * an 8dp -> 0dp hard switch at the final frame. Drag overlays still use
     * their higher 12/18dp elevation.
     */
    private fun syncShadowElevation() {
        val density = resources.displayMetrics.density
        val targetDp = when {
            !config.iconShadowEnabled -> 0f
            dragVisual -> if (config.presentation == NativeAppListPresentation.LINEAR) 18f else 12f
            else -> 8f
        }
        val target = targetDp * density
        if (elevation != target) elevation = target
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        // Outline.alpha, unlike View.alpha alone, is applied to the platform
        // ambient/spot shadow. Updating the outline fades it on the same clock
        // while preserving one elevation recipe through the whole transition.
        invalidateOutline()
    }

    fun bind(
        row: NativeAppListBoundRow<Bitmap>,
        config: NativeAppListResolvedConfig,
    ) {
        // RecyclerView may synchronously reuse a source/target View for the new
        // folder while the folder-dispatch callback is still on the stack. A
        // pending row was made alpha=0 before the owner mutation, so a genuine
        // rebind must restore every RenderNode visibility property atomically.
        // Merely clearing folderRemovalPending here leaves the newly-bound
        // folder transparent and makes completion skip it because its stable
        // key has already changed.
        if (committedFolderRemovalHidden || folderRemovalPending) {
            committedFolderRemovalHidden = false
            folderRemovalPending = false
            folderRemovalAlphaBeforePending = 1f
            visibility = VISIBLE
            alpha = 1f
        }
        applyConfig(config)
        boundRow = row
        iconRenderOwner.bind(row.icon)
        iconEffectRenderer?.invalidateContent()
        label = row.entry.label
        contentDescription = row.entry.label
        isClickable = true
        invalidateOutline()
        invalidate()
    }

    /**
     * Temporarily blanks a row without throwing away its binding. Folder
     * dispatch can synchronously move the View into RecyclerView's hidden
     * remove layer; keeping the binding lets a rejected command restore it.
     */
    fun setFolderRemovalPending(pending: Boolean) {
        if (folderRemovalPending == pending) return
        if (pending) {
            folderRemovalAlphaBeforePending = alpha
            // Alpha is a RenderNode property and takes effect even when the
            // old icon display list has already moved to RecyclerView's
            // disappearing layer. This closes the one-frame/stuck-icon race.
            alpha = 0f
            // Alpha alone is not authoritative here: the host's edge/fisheye
            // visual pass can run synchronously while the repository publishes
            // the new folder and write its computed alpha back onto this old
            // source/target View. INVISIBLE keeps the disappearing RecyclerView
            // child blank even during that same-frame pass.
            visibility = INVISIBLE
        }
        folderRemovalPending = pending
        if (pending) {
            iconEffectRenderer?.discardContent()
        } else {
            if (!committedFolderRemovalHidden) {
                visibility = VISIBLE
                alpha = folderRemovalAlphaBeforePending
            }
            folderRemovalAlphaBeforePending = 1f
            iconEffectRenderer?.invalidateContent()
        }
        invalidateOutline()
        invalidate()
    }

    /** Applies host edge/fisheye alpha without reviving a folder-removal row. */
    fun setHostVisualAlpha(value: Float) {
        if (folderRemovalPending || committedFolderRemovalHidden) {
            if (visibility != INVISIBLE) visibility = INVISIBLE
            if (alpha != 0f) alpha = 0f
            return
        }
        if (visibility != VISIBLE) visibility = VISIBLE
        if (alpha != value) alpha = value
    }

    /** Permanently hides an accepted folder source/target until holder rebind. */
    fun commitFolderRemoval() {
        committedFolderRemovalHidden = true
        clearBinding()
        visibility = INVISIBLE
        alpha = 0f
        invalidateOutline()
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
                this.config.iconLabelGapPx != next.iconLabelGapPx ||
                this.config.iconShape != next.iconShape ||
                this.config.iconCornerRadiusPx != next.iconCornerRadiusPx ||
                this.config.denseGridIconInnerPaddingPx != next.denseGridIconInnerPaddingPx
        this.config = next
        geometryDirty = true
        iconEffectRenderer?.invalidateContent()
        updatePaints()
        syncShadowElevation()
        if (drawingMetricsChanged) {
            requestLayout()
            invalidateOutline()
        }
        invalidate()
    }

    fun clearBinding() {
        val keepCommittedRemovalHidden = committedFolderRemovalHidden
        folderRemovalPending = false
        folderRemovalAlphaBeforePending = 1f
        iconRenderOwner.clear()
        boundRow = null
        iconEffectRenderer?.discardContent()
        // The shader holds the recycled bitmap; a stale reference here would
        // paint the previous app's icon through the next bind's first frame.
        iconShaderPaint.shader = null
        iconShaderBitmap = null
        label = ""
        contentDescription = null
        // A recycled holder can be drawn once before its next bind. Clear all
        // geometry paths here so a previous icon/shadow never flashes in that
        // hand-off frame.
        iconClipPath.reset()
        pressOverlayPath.reset()
        pressOverlayUsesPath = false
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
        alpha = if (keepCommittedRemovalHidden) 0f else 1f
        visibility = if (keepCommittedRemovalHidden) INVISIBLE else VISIBLE
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
        invalidateOutline()
        invalidate()
    }

    fun setIconBlurRadiusPx(radiusPx: Float, tier: NativeBlurTier) {
        val nextBucket = if (tier == NativeBlurTier.SYSTEM && Build.VERSION.SDK_INT >= 31) {
            quantizeNativeBlurRadiusPx(radiusPx)
        } else {
            0
        }
        if (nextBucket == iconBlurRadiusBucketPx) return
        if (iconBlurRadiusBucketPx == 0 && nextBucket > 0) {
            // 从直绘路径切回 RenderNode 路径的首帧必须重录内容：bucket 为 0
            // 期间本节点不在视图显示列表里，系统可能已回收其录制内容，沿用
            // 旧显示列表会画空——正是「滚动尾端图标进入模糊带就直接消失」
            // 的来源之一。只在 0 → 非 0 的切换帧触发，滚动中 bucket 在非零
            // 档位间变化不重录，热路径无额外开销。
            iconEffectRenderer?.invalidateContent()
        }
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

    /** Stable identity currently owned by this View, used to guard recycle races. */
    fun boundStableKeyOrNull(): com.flue.launcher.applist.model.AppItemKey? =
        boundRow?.stableKey

    fun setDragVisualState(enabled: Boolean) {
        if (dragVisual == enabled) return
        dragVisual = enabled
        updateInteractionScale()
        syncShadowElevation()
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
            presentation = config.presentation,
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
            // Compose 版走 FastOutSlowIn；ValueAnimator 的默认
            // AccelerateDecelerate 前段慢半拍，按下去像隔了一层。
            interpolator = PressInterpolator
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

    /**
     * 命中测试用的图标中心：**含月牙位移，不含重排预览位移**。
     *
     * ## 为什么必须含月牙
     *
     * 心流布局把每行按弧线横向推开（峰值可达内容宽度的一半以上）。命中测试若读不含月牙的
     * 坐标，算出来的「最近图标」是**没有弯之前**那一排的排序结果 —— 用户手指明明压在某个
     * 图标上，判定却落到另一个上。这就是老师报的「心流样式没法拖动创建文件夹」：悬停目标
     * 与看到的图标对不上，文件夹候选永远凑不齐。
     *
     * ## 为什么仍然不含预览
     *
     * 重排预览位移是**上一帧判定的结果**。把它喂回下一帧的判定会形成帧率反馈环（提交记录里
     * 「整个列表抽搐」那次）。月牙不同：它只是行 y 的函数，与拖动判定无关，喂回来是稳定的。
     */
    fun iconCenterXInParentForHitTest(): Float {
        ensureGeometry()
        return left + iconBounds.centerX() +
            moveTranslationX + dragTranslationX + flowTranslationX
    }

    /** [iconCenterXInParentForHitTest] 的纵向对应量（月牙也有纵向分量时一并计入）。 */
    fun iconCenterYInParentForHitTest(): Float {
        ensureGeometry()
        return top + iconBounds.centerY() +
            moveTranslationY + dragTranslationY + contentTranslationY + flowTranslationY
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
            NativeAppListPresentation.FLOW ->
                config.flowCellSizePx.toInt().coerceAtLeast(config.iconSizePx)
            NativeAppListPresentation.RING ->
                config.ringCellSizePx.toInt().coerceAtLeast(config.iconSizePx)
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
            NativeAppListPresentation.FLOW ->
                config.flowCellSizePx.toInt().coerceAtLeast(config.iconSizePx)
            NativeAppListPresentation.RING ->
                config.ringCellSizePx.toInt().coerceAtLeast(config.iconSizePx)
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
        invalidateOutline()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // RecyclerView 的 itemView 缓存复用（stable-id 命中）不会重新 bind，
        // 也就不会触发 invalidateContent；而脱窗期间图标效果 RenderNode 的
        // 显示列表可能已被系统回收，沿用会画空。重新入窗的首帧强制重录，
        // 保证滚动尾端刚挂回的行第一帧就画出（模糊的）图标。
        iconEffectRenderer?.invalidateContent()
    }

    /**
     * Re-records the icon RenderNode after the RecyclerView host itself was
     * hidden, blurred, or reactivated. Scrolling used to be the only operation
     * that forced these cached rows to refresh, leaving a stale row of icons on
     * the background until the user traversed the whole list.
     */
    fun refreshCompositedContent() {
        iconEffectRenderer?.invalidateContent()
        invalidateOutline()
        invalidate()
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
        if (folderRemovalPending) return
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
            if (pressOverlayUsesPath) {
                canvas.drawPath(pressOverlayPath, overlayPaint)
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
        val drawableBitmap = iconRenderOwner.bitmapForDraw()
        if (drawableBitmap != null) {
            // clipPath 没有抗锯齿：圆形/圆角裁剪的边缘是硬台阶，是图标边缘
            // 锯齿的来源之一。BitmapShader + drawPath 让裁形边缘吃到
            // ANTI_ALIAS，位图内容不变。
            if (iconShaderBitmap !== drawableBitmap) {
                iconShaderPaint.shader = BitmapShader(
                    drawableBitmap,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP,
                )
                iconShaderBitmap = drawableBitmap
            }
            iconShaderSourceRect.set(
                0f,
                0f,
                drawableBitmap.width.toFloat(),
                drawableBitmap.height.toFloat(),
            )
            iconShaderMatrix.setRectToRect(
                iconShaderSourceRect,
                iconBounds,
                Matrix.ScaleToFit.FILL,
            )
            iconShaderPaint.shader?.setLocalMatrix(iconShaderMatrix)
            canvas.drawPath(iconClipPath, iconShaderPaint)
        } else {
            val saveCount = canvas.save()
            canvas.clipPath(iconClipPath)
            canvas.drawRoundRect(
                iconBounds,
                iconBounds.width() * 0.25f,
                iconBounds.height() * 0.25f,
                fallbackBackgroundPaint,
            )
            canvas.drawPath(fallbackPath, fallbackForegroundPaint)
            canvas.restoreToCount(saveCount)
        }
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
            NativeAppListPresentation.HONEYCOMB,
            // 心流的格子也是「方格居中放一个图标」，与蜂窝逐位相同。
            NativeAppListPresentation.FLOW,
            // 星环也是方格居中放一个图标；图标比格子小的那圈空隙由 config.iconSizePx
            // 决定（nativeRingIconSizePx 已经按格边长算好），这里不再二次内缩。
            NativeAppListPresentation.RING -> {
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
        when (effectiveIconShape()) {
            NativeAppListIconShape.CIRCLE -> {
                iconClipPath.addCircle(
                    iconBounds.centerX(),
                    iconBounds.centerY(),
                    min(iconBounds.width(), iconBounds.height()) / 2f,
                    Path.Direction.CW,
                )
            }
            NativeAppListIconShape.ROUNDED_RECT -> {
                val radius = config.iconCornerRadiusPx.coerceAtMost(
                    min(iconBounds.width(), iconBounds.height()) / 2f,
                )
                iconClipPath.addRoundRect(iconBounds, radius, radius, Path.Direction.CW)
            }
        }

        // 高亮描什么形状的判定挪到 NativePressOverlayPolicy —— 它是纯函数，能直接
        // 单元测试；留在 View 里只能靠肉眼看。这里只负责按判定结果把路径搭出来。
        //
        // 三种紧凑布局（蜂窝 / 心流 / 星环）走 iconClipPath 而不是再自己 addCircle：
        // effectiveIconShape() 已经为这三种强制了 CIRCLE，所以 iconClipPath 本来就是
        // 那个圆，重画一遍只是把同一条规则写在两个地方。
        pressOverlayPath.reset()
        pressOverlayUsesPath = when (
            nativePressOverlayShape(
                presentation = config.presentation,
                iconShape = config.iconShape,
                rowBackgroundVisible = config.palette.rowBackgroundColorArgb ushr 24 != 0L,
            )
        ) {
            NativePressOverlayShape.ICON_SHAPE -> {
                pressOverlayPath.addPath(iconClipPath)
                true
            }
            NativePressOverlayShape.ROW_ROUNDED_RECT -> false
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

/** Compose 的 FastOutSlowInEasing（0.4, 0, 0.2, 1），按压缩放共用。 */
private val PressInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
