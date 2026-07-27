package com.flue.launcher.applist.nativeview

import android.util.SparseBooleanArray
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal interface NativeSpatialAppListLayoutManager {
    fun findNearestVisiblePosition(
        x: Float,
        y: Float,
        maximumDistancePx: Float,
        excludedPosition: Int = -1,
    ): Int

    fun reapplyChildVisuals()

    fun markGeometryDirty()

    /**
     * Called when an empty adapter list has actually committed, even if no
     * zero-item layout pass occurred between that commit and its replacement.
     */
    fun onAdapterItemCountCommitted(itemCount: Int)
}

/**
 * Shared RecyclerView kernel for the two non-linear native app-list modes.
 *
 * It owns only lifecycle, bounded recycling and scroll metrics. Concrete
 * strategies provide geometry, child measurement and visuals, keeping the
 * hot path free from presentation-specific branches and allocations.
 */
internal abstract class NativeAppGridLayoutManager(
    private val configProvider: () -> NativeAppListConfig,
) : RecyclerView.LayoutManager(),
    NativeSpatialAppListLayoutManager {
    private var geometry: NativeAppGridGeometry? = null
    private var geometryItemCount = -1
    private var geometryViewportWidthPx = -1
    private var geometryViewportHeightPx = -1
    private var geometryDirty = true
    private var scrollOffsetPx = Float.NaN
    /** Integer render coordinate; logical scrollOffsetPx may remain fractional. */
    private var renderedScrollPositionPx = Int.MIN_VALUE
    private var attachedRecyclerView: RecyclerView? = null
    private var pendingAnchor: GeometryAnchor? = null
    private var suppressAttachedAnchorCapture = false
    private var pendingScrollPosition = RecyclerView.NO_POSITION
    private val visibleBounds = IntArray(2)
    private val attachedPositions = SparseBooleanArray()

    protected abstract fun createGeometry(
        itemCount: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        config: NativeAppListConfig,
    ): NativeAppGridGeometry

    protected abstract fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean

    protected abstract fun rememberGeometryConfig(config: NativeAppListConfig)

    protected abstract fun layoutChild(
        child: View,
        geometry: NativeAppGridGeometry,
        position: Int,
        config: NativeAppListConfig,
    )

    protected abstract fun applyChildVisuals(
        geometry: NativeAppGridGeometry,
        config: NativeAppListConfig,
    )

    protected open fun extraLayoutSpacePx(
        geometry: NativeAppGridGeometry,
        config: NativeAppListConfig,
    ): Float = max(geometry.itemHeightPx, config.iconSizePx.toFloat())

    protected fun currentGeometryOrNull(): NativeAppGridGeometry? = geometry

    protected fun currentScrollOffsetPx(): Float = scrollOffsetPx

    /** Offset corresponding to the integer coordinate currently rendered on children. */
    protected fun currentRenderedScrollOffsetPx(): Float {
        val current = geometry ?: return scrollOffsetPx
        val renderedPosition = if (renderedScrollPositionPx != Int.MIN_VALUE) {
            renderedScrollPositionPx
        } else {
            renderedScrollPositionFor(
                current,
                scrollOffsetPx.takeIf(Float::isFinite) ?: current.maximumScrollOffsetPx,
            )
        }
        return current.maximumScrollOffsetPx - renderedPosition
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun canScrollVertically(): Boolean = true

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        attachedRecyclerView = null
        super.onDetachedFromWindow(view, recycler)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0 || width <= 0 || height <= 0) {
            removeAndRecycleAllViews(recycler)
            geometry = null
            geometryItemCount = -1
            geometryViewportWidthPx = -1
            geometryViewportHeightPx = -1
            geometryDirty = true
            scrollOffsetPx = Float.NaN
            renderedScrollPositionPx = Int.MIN_VALUE
            if (state.itemCount == 0) {
                pendingAnchor = null
                suppressAttachedAnchorCapture = false
            }
            return
        }
        val current = ensureGeometry(state.itemCount)
        if (!scrollOffsetPx.isFinite()) scrollOffsetPx = current.maximumScrollOffsetPx
        scrollOffsetPx = scrollOffsetPx.coerceIn(
            current.minimumScrollOffsetPx,
            current.maximumScrollOffsetPx,
        )
        renderedScrollPositionPx = renderedScrollPositionFor(current, scrollOffsetPx)
        detachAndScrapAttachedViews(recycler)
        fillMissing(recycler, current)
        applyChildVisuals(current, configProvider())
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        if (dy == 0 || state.itemCount == 0) {
            return 0
        }
        val previousGeometry = geometry
        val current = ensureGeometry(state.itemCount)
        if (previousGeometry !== current && childCount > 0) {
            detachAndScrapAttachedViews(recycler)
            fillMissing(recycler, current)
        }
        val oldOffset = scrollOffsetPx.takeIf(Float::isFinite)
            ?: current.maximumScrollOffsetPx
        val target = (oldOffset - dy).coerceIn(
            current.minimumScrollOffsetPx,
            current.maximumScrollOffsetPx,
        )
        val oldRenderedPosition = renderedScrollPositionFor(current, oldOffset)
        val targetRenderedPosition = renderedScrollPositionFor(current, target)
        val consumed = targetRenderedPosition - oldRenderedPosition
        scrollOffsetPx = target
        renderedScrollPositionPx = targetRenderedPosition
        if (consumed == 0) {
            return 0
        }
        offsetChildrenVertical(-consumed)
        recycleOutsideBounds(recycler, current)
        fillMissing(recycler, current)
        applyChildVisuals(current, configProvider())
        return consumed
    }

    override fun scrollToPosition(position: Int) {
        if (position < 0) return
        pendingScrollPosition = position
        pendingAnchor = null
        requestLayout()
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        val current = geometry ?: return 0
        val offset = scrollOffsetPx.takeIf(Float::isFinite)
            ?: current.maximumScrollOffsetPx
        return renderedScrollPositionFor(current, offset)
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int =
        height.coerceAtLeast(0)

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        val current = geometry ?: return height.coerceAtLeast(0)
        val scrollable = (
            current.maximumScrollOffsetPx - current.minimumScrollOffsetPx
            ).coerceAtLeast(0f)
        return (height.toFloat() + scrollable).roundToInt().coerceAtLeast(height)
    }

    final override fun findNearestVisiblePosition(
        x: Float,
        y: Float,
        maximumDistancePx: Float,
        excludedPosition: Int,
    ): Int {
        if (!x.isFinite() || !y.isFinite() || maximumDistancePx < 0f) return -1
        var nearestPosition = -1
        var nearestDistance = maximumDistancePx
        for (index in 0 until childCount) {
            val child = requireNotNull(getChildAt(index))
            if (getPosition(child) == excludedPosition) continue
            val centerX = child.left + child.width / 2f + child.translationX
            val centerY = child.top + child.height / 2f + child.translationY
            val distance = hypot(x - centerX, y - centerY)
            if (distance <= nearestDistance) {
                nearestDistance = distance
                nearestPosition = getPosition(child)
            }
        }
        return nearestPosition
    }

    /** Reapplies LayoutManager-owned transforms after a transient drag overlay ends. */
    final override fun reapplyChildVisuals() {
        val current = geometry ?: return
        applyChildVisuals(current, configProvider())
    }

    final override fun markGeometryDirty() {
        if (
            pendingScrollPosition == RecyclerView.NO_POSITION &&
            !suppressAttachedAnchorCapture
        ) {
            pendingAnchor = captureAnchor()
        }
        geometryDirty = true
        requestLayout()
    }

    final override fun onAdapterItemCountCommitted(itemCount: Int) {
        if (itemCount != 0) return
        pendingAnchor = null
        suppressAttachedAnchorCapture = true
        scrollOffsetPx = Float.NaN
        geometryDirty = true
    }

    protected fun invalidateGeometry() {
        geometryDirty = true
    }

    private fun ensureGeometry(itemCount: Int): NativeAppGridGeometry {
        val config = configProvider()
        if (
            geometryDirty ||
            geometry == null ||
            geometryItemCount != itemCount ||
            geometryViewportWidthPx != width ||
            geometryViewportHeightPx != height ||
            !hasSameGeometryConfig(config)
        ) {
            val anchor = when {
                pendingScrollPosition != RecyclerView.NO_POSITION -> null
                suppressAttachedAnchorCapture -> null
                else -> pendingAnchor ?: captureAnchor()
            }
            pendingAnchor = null
            suppressAttachedAnchorCapture = false
            geometry = createGeometry(itemCount, width, height, config)
            geometryItemCount = itemCount
            geometryViewportWidthPx = width
            geometryViewportHeightPx = height
            rememberGeometryConfig(config)
            val current = requireNotNull(geometry)
            scrollOffsetPx = restoreAnchor(current, anchor)
                ?: scrollOffsetPx.takeIf(Float::isFinite)?.coerceIn(
                    current.minimumScrollOffsetPx,
                    current.maximumScrollOffsetPx,
                )
                ?: current.maximumScrollOffsetPx
            geometryDirty = false
        }
        val current = requireNotNull(geometry)
        if (pendingScrollPosition != RecyclerView.NO_POSITION) {
            val position = pendingScrollPosition.coerceIn(0, current.itemCount - 1)
            pendingScrollPosition = RecyclerView.NO_POSITION
            scrollOffsetPx = scrollOffsetForPosition(current, position)
        }
        renderedScrollPositionPx = renderedScrollPositionFor(
            current,
            scrollOffsetPx.takeIf(Float::isFinite) ?: current.maximumScrollOffsetPx,
        )
        return current
    }

    private fun captureAnchor(): GeometryAnchor? {
        if (childCount == 0 || height <= 0) return null
        val anchorViewportHeight = geometry?.viewportHeightPx
            ?.takeIf { it > 0 }
            ?: geometryViewportHeightPx.takeIf { it > 0 }
            ?: height
        val viewportCenterY = anchorViewportHeight / 2f
        var closestChild: View? = null
        var closestDistance = Float.MAX_VALUE
        for (index in 0 until childCount) {
            val child = requireNotNull(getChildAt(index))
            val centerY = getDecoratedTop(child) + getDecoratedMeasuredHeight(child) / 2f
            val distance = kotlin.math.abs(centerY - viewportCenterY)
            if (distance < closestDistance) {
                closestDistance = distance
                closestChild = child
            }
        }
        val child = closestChild ?: return null
        val position = getPosition(child)
        if (position == RecyclerView.NO_POSITION) return null
        return GeometryAnchor(
            stableId = attachedRecyclerView?.getChildViewHolder(child)?.itemId
                ?: RecyclerView.NO_ID,
            fallbackPosition = position,
            layoutCenterOffsetY =
                getDecoratedTop(child) + getDecoratedMeasuredHeight(child) / 2f -
                    viewportCenterY,
        )
    }

    private fun restoreAnchor(
        current: NativeAppGridGeometry,
        anchor: GeometryAnchor?,
    ): Float? {
        if (anchor == null || current.itemCount == 0) return null
        val adapter = attachedRecyclerView?.adapter
        val position = if (
            adapter != null && adapter.hasStableIds() && anchor.stableId != RecyclerView.NO_ID
        ) {
            var match = RecyclerView.NO_POSITION
            var index = 0
            while (index < adapter.itemCount) {
                if (adapter.getItemId(index) == anchor.stableId) {
                    match = index
                    break
                }
                index++
            }
            match
        } else {
            RecyclerView.NO_POSITION
        }.takeIf { it in 0 until current.itemCount }
            ?: anchor.fallbackPosition.coerceIn(0, current.itemCount - 1)
        val contentCenterY = current.screenCenterY(position, 0f) - current.viewportHeightPx / 2f
        return (anchor.layoutCenterOffsetY - contentCenterY)
            .coerceIn(current.minimumScrollOffsetPx, current.maximumScrollOffsetPx)
    }

    private fun fillMissing(
        recycler: RecyclerView.Recycler,
        current: NativeAppGridGeometry,
    ) {
        attachedPositions.clear()
        for (index in 0 until childCount) {
            attachedPositions.put(getPosition(requireNotNull(getChildAt(index))), true)
        }
        current.visiblePositionBounds(
            scrollOffsetPx = scrollOffsetPx,
            extraLayoutSpacePx = extraLayoutSpacePx(current, configProvider()),
            output = visibleBounds,
        )
        val first = visibleBounds[0]
        val last = visibleBounds[1]
        if (first < 0 || last < first) return
        var position = first
        while (position <= last) {
            if (!attachedPositions.get(position)) {
                val child = recycler.getViewForPosition(position)
                addView(child, childIndexForAdapterPosition(position))
                layoutChild(child, current, position, configProvider())
            }
            position++
        }
    }

    private fun childIndexForAdapterPosition(position: Int): Int {
        var childIndex = 0
        while (childIndex < childCount) {
            if (getPosition(requireNotNull(getChildAt(childIndex))) > position) return childIndex
            childIndex++
        }
        return childCount
    }

    private fun recycleOutsideBounds(
        recycler: RecyclerView.Recycler,
        current: NativeAppGridGeometry,
    ) {
        // Recycle on the *union* of the layout slot and the transformed box,
        // with half a cell of hysteresis. The fisheye squashes edge rows toward
        // their pivot, so the transformed box alone can leave the retention
        // band while the layout slot is still inside `fillMissing`'s fill band.
        // Such a row was recycled here and re-created on the very next frame —
        // dropping its RenderNode, edge blur and bitmap shaders every frame,
        // which reads as "the bottom rows blink out line by line while
        // scrolling".
        val extra = extraLayoutSpacePx(current, configProvider()) +
            current.itemHeightPx * 0.5f
        for (index in childCount - 1 downTo 0) {
            val child = requireNotNull(getChildAt(index))
            val decoratedTop = getDecoratedTop(child).toFloat()
            val decoratedBottom = getDecoratedBottom(child).toFloat()
            val pivotY = child.top + child.pivotY
            val transformedTop = child.translationY + pivotY +
                (decoratedTop - pivotY) * child.scaleY
            val transformedBottom = child.translationY + pivotY +
                (decoratedBottom - pivotY) * child.scaleY
            val visibleTop = min(min(transformedTop, transformedBottom), decoratedTop)
            val visibleBottom = max(max(transformedTop, transformedBottom), decoratedBottom)
            if (visibleBottom < -extra || visibleTop > height + extra) {
                removeAndRecycleView(child, recycler)
            }
        }
    }

    private fun scrollOffsetForPosition(
        current: NativeAppGridGeometry,
        position: Int,
    ): Float = (-(current.screenCenterY(position, 0f) - current.viewportHeightPx / 2f))
        .coerceIn(current.minimumScrollOffsetPx, current.maximumScrollOffsetPx)

    private fun renderedScrollPositionFor(
        current: NativeAppGridGeometry,
        offset: Float,
    ): Int = (current.maximumScrollOffsetPx - offset)
        .coerceAtLeast(0f)
        .roundToInt()

    private data class GeometryAnchor(
        val stableId: Long,
        val fallbackPosition: Int,
        val layoutCenterOffsetY: Float,
    )
}
