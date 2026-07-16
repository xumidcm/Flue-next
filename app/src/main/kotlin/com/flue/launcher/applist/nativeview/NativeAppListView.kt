package com.flue.launcher.applist.nativeview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.os.Build
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand

class NativeAppListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private val contentVersionGate = NativeAppListContentVersionGate()
    private var currentConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig())
    private val nativeAdapter = NativeAppListAdapter(::dispatchCommand, currentConfig)
    private val scrollController = NativeAppListScrollController(this) {
        currentConfig.value
    }
    private var activeLayoutStrategy: NativeAppListLayoutStrategy =
        LinearNativeAppListLayoutStrategy
    private val childVisualScratch = NativeLinearChildVisual(scale = 1f, alpha = 1f)
    private val childVisualScrollListener = object : OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            applyAttachedChildVisuals()
        }
    }
    private val childVisualAttachListener = object : OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            applyChildVisual(view)
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }
    private var released = false

    var onCommand: ((AppListCommand) -> Unit)? = null

    init {
        setHasFixedSize(true)
        layoutManager = activeLayoutStrategy.createLayoutManager(context)
        itemAnimator = null
        clipToPadding = false
        adapter = nativeAdapter
        addOnScrollListener(childVisualScrollListener)
        addOnChildAttachStateChangeListener(childVisualAttachListener)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        updateViewportPadding(bottom - top)
        super.onLayout(changed, left, top, right, bottom)
        applyAttachedChildVisuals()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return scrollController.onGenericMotionEvent(event) ||
            super.onGenericMotionEvent(event)
    }

    fun submit(content: NativeAppListContent): Boolean {
        if (
            released ||
            !contentVersionGate.tryAccept(
                snapshotGeneration = content.snapshot.generation,
                iconRevision = content.iconRevision,
            )
        ) {
            return false
        }
        nativeAdapter.submitContent(content)
        return true
    }

    fun setConfig(config: NativeAppListConfig) {
        if (released) return
        val next = NativeAppListResolvedConfig.from(config)
        if (currentConfig.value == next.value) return
        val resetInitialScroll =
            currentConfig.value.initialResetToken != next.value.initialResetToken
        currentConfig = next
        nativeAdapter.setConfig(next)
        applyContentOverlayBlur()
        updateViewportPadding(height)
        applyAttachedChildVisuals()
        if (resetInitialScroll) {
            stopScroll()
            scrollToPosition(0)
        }
    }

    fun setLayoutStrategy(strategy: NativeAppListLayoutStrategy) {
        if (
            released ||
            !shouldReplaceLayoutStrategy(
                currentSemanticKey = activeLayoutStrategy.semanticKey,
                nextSemanticKey = strategy.semanticKey,
            )
        ) {
            return
        }
        stopScroll()
        resetAttachedChildVisuals()
        val previousLayoutManager = layoutManager
        val savedLayoutState = previousLayoutManager?.onSaveInstanceState()
        val nextLayoutManager = strategy.createLayoutManager(context)
        activeLayoutStrategy = strategy
        layoutManager = nextLayoutManager
        if (
            savedLayoutState != null &&
            previousLayoutManager.javaClass == nextLayoutManager.javaClass
        ) {
            nextLayoutManager.onRestoreInstanceState(savedLayoutState)
        }
        applyAttachedChildVisuals()
    }

    fun release() {
        if (released) return
        released = true
        stopScroll()
        removeOnScrollListener(childVisualScrollListener)
        removeOnChildAttachStateChangeListener(childVisualAttachListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
        onCommand = null
        nativeAdapter.clearSynchronously()
        adapter = null
        layoutManager = null
        recycledViewPool.clear()
        clearOnScrollListeners()
        clearOnChildAttachStateChangeListeners()
    }

    private fun dispatchCommand(command: AppListCommand) {
        if (!released) {
            onCommand?.invoke(command)
        }
    }

    private fun applyAttachedChildVisuals() {
        if (released || height <= 0) return
        for (index in 0 until childCount) {
            applyChildVisual(getChildAt(index))
        }
    }

    private fun applyChildVisual(child: View) {
        if (activeLayoutStrategy.semanticKey != LinearNativeAppListLayoutStrategy.semanticKey) {
            return
        }
        val visual = nativeLinearChildVisual(
            childCenterY = child.top + child.height / 2f + child.translationY,
            viewportHeight = height,
            config = currentConfig.value,
            output = childVisualScratch,
        )
        val centerPivotX = child.width / 2f
        val centerPivotY = child.height / 2f
        if (child.pivotX != centerPivotX) child.pivotX = centerPivotX
        if (child.pivotY != centerPivotY) child.pivotY = centerPivotY
        if (child.scaleX != visual.scale) child.scaleX = visual.scale
        if (child.scaleY != visual.scale) child.scaleY = visual.scale
        if (child.alpha != visual.alpha) child.alpha = visual.alpha
        (child as? NativeAppListRowView)?.setIconBlurRadiusPx(
            radiusPx = visual.blurRadiusPx,
            tier = currentConfig.value.blurTier,
        )
    }

    private fun resetAttachedChildVisuals() {
        for (index in 0 until childCount) {
            resetChildVisual(getChildAt(index))
        }
    }

    private fun resetChildVisual(child: View) {
        if (child.scaleX != 1f) child.scaleX = 1f
        if (child.scaleY != 1f) child.scaleY = 1f
        if (child.alpha != 1f) child.alpha = 1f
        (child as? NativeAppListRowView)?.setIconBlurRadiusPx(0f, NativeBlurTier.OFF)
    }

    private fun updateViewportPadding(viewportHeight: Int) {
        val verticalPadding = if (currentConfig.value.centerEdgeItems) {
            ((viewportHeight - currentConfig.value.rowHeightPx) / 2).coerceAtLeast(0)
        } else {
            0
        }
        if (paddingTop != verticalPadding || paddingBottom != verticalPadding) {
            setPadding(0, verticalPadding, 0, verticalPadding)
        }
    }

    private fun applyContentOverlayBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val bucket = if (currentConfig.value.blurTier == NativeBlurTier.SYSTEM) {
            quantizeNativeBlurRadiusPx(currentConfig.value.contentOverlayBlurRadiusPx)
        } else {
            0
        }
        setRenderEffect(bucket.takeIf { it > 0 }?.let(NativeSystemBlurEffectCache::get))
    }
}
