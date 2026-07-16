package com.flue.launcher.applist.nativeview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListSnapshot

class NativeAppListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private val generationGate = SnapshotGenerationGate()
    private val nativeAdapter = NativeAppListAdapter(::dispatchCommand)
    private val scrollController = NativeAppListScrollController(this)
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

    fun submitSnapshot(snapshot: AppListSnapshot): Boolean {
        if (released || !generationGate.tryAccept(snapshot.generation)) return false
        nativeAdapter.submitSnapshot(snapshot)
        return true
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
