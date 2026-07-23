package com.flue.launcher.applist.nativeview

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal interface NativeTouchScrollConsumptionReporter {
    fun beginTouchScrollCapture()

    fun takeUnconsumedTouchScrollDeltaPx(): Int
}

internal class NativeTouchScrollConsumptionTracker {
    private var capturing = false
    private var requestedDeltaPx = 0
    private var consumedDeltaPx = 0

    fun begin() {
        requestedDeltaPx = 0
        consumedDeltaPx = 0
        capturing = true
    }

    fun record(requestedDeltaPx: Int, consumedDeltaPx: Int) {
        if (!capturing) return
        this.requestedDeltaPx += requestedDeltaPx
        this.consumedDeltaPx += consumedDeltaPx
    }

    fun takeUnconsumed(): Int {
        if (!capturing) return 0
        capturing = false
        return requestedDeltaPx - consumedDeltaPx
    }
}

interface NativeAppListLayoutStrategy {
    val semanticKey: Any

    fun createLayoutManager(
        context: Context,
        configProvider: () -> NativeAppListConfig,
    ): RecyclerView.LayoutManager
}

internal fun usesHostChildVisualPass(strategy: NativeAppListLayoutStrategy): Boolean =
    strategy.semanticKey == LinearNativeAppListLayoutStrategy.semanticKey

object LinearNativeAppListLayoutStrategy : NativeAppListLayoutStrategy {
    override val semanticKey: Any = "linear"

    override fun createLayoutManager(
        context: Context,
        configProvider: () -> NativeAppListConfig,
    ): RecyclerView.LayoutManager =
        NativeLinearLayoutManager(context)
}

private class NativeLinearLayoutManager(
    context: Context,
) : LinearLayoutManager(context, RecyclerView.VERTICAL, false),
    NativeTouchScrollConsumptionReporter {
    private val touchScrollConsumption = NativeTouchScrollConsumptionTracker()

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        val consumed = super.scrollVerticallyBy(dy, recycler, state)
        touchScrollConsumption.record(dy, consumed)
        return consumed
    }

    /**
     * RecyclerView's default smooth-scrollbar estimator uses the transformed
     * child bounds and an average of the currently attached rows.  The native
     * app-list applies fisheye scale/translation to those rows, so that
     * estimator can report e.g. four pixels after a real twenty-pixel scroll.
     * The value is consumed by the pull hand-off and by the rotary controller;
     * returning an exact layout-space offset keeps the gesture remainder and
     * the visible scrollbar in the same coordinate system.
     */
    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        if (childCount == 0 || itemCount == 0) return 0
        val firstPosition = findFirstVisibleItemPosition().coerceAtLeast(0)
        val firstChild = findViewByPosition(firstPosition) ?: return 0
        val itemExtent = getDecoratedMeasuredHeight(firstChild).coerceAtLeast(1)
        return (
            firstPosition * itemExtent + paddingTop - getDecoratedTop(firstChild)
            ).coerceAtLeast(0)
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int =
        height.coerceAtLeast(0)

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        if (itemCount == 0) return height.coerceAtLeast(0)
        val firstChild = findViewByPosition(findFirstVisibleItemPosition())
        val itemExtent = firstChild?.let { getDecoratedMeasuredHeight(it) }?.coerceAtLeast(1)
            ?: 1
        return (
            itemCount * itemExtent + paddingTop + paddingBottom
            ).coerceAtLeast(height)
    }

    override fun beginTouchScrollCapture() = touchScrollConsumption.begin()

    override fun takeUnconsumedTouchScrollDeltaPx(): Int =
        touchScrollConsumption.takeUnconsumed()
}

object HoneycombNativeAppListLayoutStrategy : NativeAppListLayoutStrategy {
    override val semanticKey: Any = "honeycomb"

    override fun createLayoutManager(
        context: Context,
        configProvider: () -> NativeAppListConfig,
    ): RecyclerView.LayoutManager = NativeHoneycombLayoutManager(configProvider)
}

object DenseGridNativeAppListLayoutStrategy : NativeAppListLayoutStrategy {
    override val semanticKey: Any = "dense-grid"

    override fun createLayoutManager(
        context: Context,
        configProvider: () -> NativeAppListConfig,
    ): RecyclerView.LayoutManager = NativeDenseGridLayoutManager(configProvider)
}
