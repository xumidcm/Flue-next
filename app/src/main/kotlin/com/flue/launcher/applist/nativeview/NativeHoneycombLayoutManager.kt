package com.flue.launcher.applist.nativeview

import android.util.SparseBooleanArray
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import kotlin.math.hypot

internal class NativeHoneycombLayoutManager(
    private val configProvider: () -> NativeAppListConfig,
) : RecyclerView.LayoutManager() {
    private var geometry: NativeHoneycombGeometry? = null
    private var geometryKey: GeometryKey? = null
    private var scrollOffsetPx = Float.NaN
    private val visibleBounds = IntArray(2)
    private val attachedPositions = SparseBooleanArray()
    private val childVisualScratch = NativeHoneycombChildVisual()

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun canScrollVertically(): Boolean = true

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0 || width <= 0 || height <= 0) {
            removeAndRecycleAllViews(recycler)
            geometry = null
            scrollOffsetPx = Float.NaN
            return
        }
        val current = ensureGeometry(state.itemCount)
        if (!scrollOffsetPx.isFinite()) scrollOffsetPx = current.maximumScrollOffsetPx
        scrollOffsetPx = scrollOffsetPx.coerceIn(
            current.minimumScrollOffsetPx,
            current.maximumScrollOffsetPx,
        )
        detachAndScrapAttachedViews(recycler)
        fillMissing(recycler, current)
        applyChildVisuals()
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        if (dy == 0 || state.itemCount == 0) return 0
        val current = ensureGeometry(state.itemCount)
        val oldOffset = scrollOffsetPx.takeIf(Float::isFinite)
            ?: current.maximumScrollOffsetPx
        val target = (oldOffset - dy).coerceIn(
            current.minimumScrollOffsetPx,
            current.maximumScrollOffsetPx,
        )
        val consumed = (oldOffset - target).roundToInt()
        if (consumed == 0) return 0
        scrollOffsetPx = oldOffset - consumed
        offsetChildrenVertical(-consumed)
        recycleOutsideBounds(recycler)
        fillMissing(recycler, current)
        applyChildVisuals()
        return consumed
    }

    override fun scrollToPosition(position: Int) {
        val current = geometry ?: return
        if (position !in current.placements.indices) return
        scrollOffsetPx = (-current.placements[position].centerY).coerceIn(
            current.minimumScrollOffsetPx,
            current.maximumScrollOffsetPx,
        )
        requestLayout()
    }

    fun findNearestVisiblePosition(
        x: Float,
        y: Float,
        maximumDistancePx: Float,
        excludedPosition: Int = -1,
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
    fun reapplyChildVisuals() {
        applyChildVisuals()
    }

    private fun ensureGeometry(itemCount: Int): NativeHoneycombGeometry {
        val config = configProvider()
        val key = GeometryKey(
            itemCount = itemCount,
            width = width,
            height = height,
            leftInset = config.leftSafeInsetPx,
            narrowColumns = config.honeycombNarrowColumns,
            iconSize = config.iconSizePx,
            cellSize = config.honeycombCellSizePx,
        )
        if (geometryKey != key) {
            geometryKey = key
            geometry = NativeHoneycombGeometry.create(
                itemCount = itemCount,
                viewportWidthPx = width,
                viewportHeightPx = height,
                leftSafeInsetPx = config.leftSafeInsetPx,
                narrowColumns = config.honeycombNarrowColumns,
                iconSizePx = config.iconSizePx.toFloat(),
                cellSizePx = config.honeycombCellSizePx.takeIf { it > 0f }
                    ?: config.iconSizePx * 1.02f,
            )
        }
        return requireNotNull(geometry)
    }

    private fun fillMissing(
        recycler: RecyclerView.Recycler,
        geometry: NativeHoneycombGeometry,
    ) {
        attachedPositions.clear()
        for (index in 0 until childCount) {
            attachedPositions.put(getPosition(requireNotNull(getChildAt(index))), true)
        }
        geometry.visiblePositionBounds(
            scrollOffsetPx = scrollOffsetPx,
            extraLayoutSpacePx = geometry.iconSizePx,
            output = visibleBounds,
        )
        val first = visibleBounds[0]
        val last = visibleBounds[1]
        if (first < 0 || last < first) return
        var position = first
        while (position <= last) {
            if (!attachedPositions.get(position)) {
                val child = recycler.getViewForPosition(position)
                addView(child)
                layoutChild(child, geometry, position)
            }
            position++
        }
    }

    private fun layoutChild(
        child: View,
        geometry: NativeHoneycombGeometry,
        position: Int,
    ) {
        val cell = configProvider().honeycombCellSizePx
            .takeIf { it > 0f }
            ?.roundToInt()
            ?: (geometry.iconSizePx * 1.02f).roundToInt()
        val layoutParams = child.layoutParams as RecyclerView.LayoutParams
        layoutParams.width = cell
        layoutParams.height = cell
        measureChildWithMargins(child, 0, 0)
        val placement = geometry.placements[position]
        val centerY = geometry.screenCenterY(position, scrollOffsetPx)
        val left = (placement.centerX - getDecoratedMeasuredWidth(child) / 2f).roundToInt()
        val top = (centerY - getDecoratedMeasuredHeight(child) / 2f).roundToInt()
        layoutDecoratedWithMargins(
            child,
            left,
            top,
            left + getDecoratedMeasuredWidth(child),
            top + getDecoratedMeasuredHeight(child),
        )
    }

    private fun recycleOutsideBounds(recycler: RecyclerView.Recycler) {
        val extra = configProvider().iconSizePx
        for (index in childCount - 1 downTo 0) {
            val child = requireNotNull(getChildAt(index))
            if (getDecoratedBottom(child) < -extra || getDecoratedTop(child) > height + extra) {
                removeAndRecycleView(child, recycler)
            }
        }
    }

    private fun applyChildVisuals() {
        val config = configProvider()
        for (index in 0 until childCount) {
            val child = requireNotNull(getChildAt(index))
            val centerX = getDecoratedLeft(child) + getDecoratedMeasuredWidth(child) / 2f
            val centerY = getDecoratedTop(child) + getDecoratedMeasuredHeight(child) / 2f
            val visual = nativeHoneycombChildVisual(
                centerX = centerX,
                centerY = centerY,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                config = config,
                output = childVisualScratch,
            )
            if (child is NativeAppListRowView) {
                child.setFlowTranslation(visual.translationX, visual.translationY)
                child.setVisualScale(visual.scale)
                child.setIconBlurRadiusPx(visual.blurRadiusPx, config.blurTier)
            } else {
                child.translationX = visual.translationX
                child.translationY = visual.translationY
                child.scaleX = visual.scale
                child.scaleY = visual.scale
            }
            child.alpha = visual.alpha
        }
    }

    private data class GeometryKey(
        val itemCount: Int,
        val width: Int,
        val height: Int,
        val leftInset: Int,
        val narrowColumns: Int,
        val iconSize: Int,
        val cellSize: Float,
    )
}
