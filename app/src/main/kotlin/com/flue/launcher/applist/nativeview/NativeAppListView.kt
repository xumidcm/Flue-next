package com.flue.launcher.applist.nativeview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand

class NativeAppListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private val generationGate = SnapshotGenerationGate()
    private val nativeAdapter = NativeAppListAdapter(::dispatchCommand)
    private var currentConfig = NativeAppListConfig().legalized()
    private val scrollController = NativeAppListScrollController(this) {
        currentConfig
    }
    private var activeLayoutStrategy: NativeAppListLayoutStrategy =
        LinearNativeAppListLayoutStrategy
    private var released = false

    var onCommand: ((AppListCommand) -> Unit)? = null

    init {
        setHasFixedSize(true)
        layoutManager = activeLayoutStrategy.createLayoutManager(context)
        itemAnimator = null
        adapter = nativeAdapter
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return scrollController.onGenericMotionEvent(event) ||
            super.onGenericMotionEvent(event)
    }

    fun submit(content: NativeAppListContent): Boolean {
        if (
            released ||
            !generationGate.tryAccept(content.snapshot.generation)
        ) {
            return false
        }
        nativeAdapter.submitContent(content)
        return true
    }

    fun setConfig(config: NativeAppListConfig) {
        if (released) return
        val next = config.legalized()
        if (currentConfig == next) return
        val resetInitialScroll =
            currentConfig.initialResetToken != next.initialResetToken
        currentConfig = next
        nativeAdapter.setConfig(next)
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
    }

    fun release() {
        if (released) return
        released = true
        stopScroll()
        onCommand = null
        nativeAdapter.clear()
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
}
