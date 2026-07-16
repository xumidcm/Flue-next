package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListMovePlanner
import kotlin.math.abs

internal interface NativeAppListDragHost {
    val viewportHeightPx: Float
    fun keyAt(x: Float, y: Float): AppItemKey?
    fun currentKeys(): List<AppItemKey>
    fun dispatch(command: AppListCommand)
    fun suppressLongClick(key: AppItemKey, suppress: Boolean)
    fun beginDrag(key: AppItemKey, pointerY: Float)
    fun updateDrag(pointerY: Float)
    fun endDrag()
    fun setFolderHover(key: AppItemKey?)
    fun scrollBy(deltaPx: Float)
}

internal interface NativeDragScheduler {
    val nowMillis: Long
    fun postDelayed(delayMillis: Long, action: () -> Unit)
    fun cancelDelayed()
    fun postFrame(action: () -> Unit)
    fun cancelFrame()
}

/** Bridges Android pointer events to stable-key commands without mutating adapter data. */
internal class NativeAppListDragController(
    private val host: NativeAppListDragHost,
    private val scheduler: NativeDragScheduler,
    private val policy: NativeDragPolicy,
) {
    private val machine = NativeDragGestureMachine(policy)
    private var suppressedLongClickKey: AppItemKey? = null
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerAtMillis = 0L
    private var frameScheduled = false
    private var released = false

    val isActive: Boolean
        get() = !released && machine.isTracking
    val isDragging: Boolean
        get() = !released && machine.isDragging

    fun onDown(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released) return false
        cancelCurrentGesture()
        val source = host.keyAt(x, y) ?: return false
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        machine.onDown(source, x, y, eventTimeMillis)
        suppressedLongClickKey = source
        host.suppressLongClick(source, true)
        scheduler.postDelayed(policy.longPressMillis) {
            if (!released) process(machine.onTime(scheduler.nowMillis))
        }
        return false
    }

    fun onMove(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released || !machine.isTracking) return false
        val elapsed = (eventTimeMillis - lastPointerAtMillis).coerceAtLeast(1L)
        val speed = abs(y - lastPointerY) / elapsed
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        val effects = machine.onMove(
            pointerX = x,
            pointerY = y,
            targetKey = host.keyAt(x, y),
            speedPxPerMs = speed,
            nowMillis = eventTimeMillis,
        )
        process(effects)
        if (!machine.isTracking) {
            scheduler.cancelDelayed()
            restoreLongClick()
            return false
        }
        if (machine.isDragging) {
            host.updateDrag(y)
            updateAutoScrollSchedule()
        }
        return machine.isDragging || machine.hasLongPressed
    }

    fun onUp(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released || !machine.isTracking) return false
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        val consumed = machine.isDragging || machine.hasLongPressed
        process(machine.onUp(host.keyAt(x, y)))
        cancelCallbacks()
        restoreLongClick()
        return consumed
    }

    fun onCancel(): Boolean {
        if (released || !machine.isTracking) return false
        val consumed = machine.isDragging || machine.hasLongPressed
        cancelCurrentGesture()
        return consumed
    }

    fun reconcileCurrentKeys() {
        if (released) return
        process(machine.reconcileCurrentKeys(host.currentKeys()))
        if (!machine.isTracking) {
            cancelCallbacks()
            restoreLongClick()
        }
    }

    fun release() {
        if (released) return
        cancelCurrentGesture()
        released = true
    }

    private fun process(effects: List<NativeDragEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is NativeDragEffect.ShowActions ->
                    host.dispatch(AppListCommand.ShowActions(effect.key))
                NativeDragEffect.DismissActions ->
                    host.dispatch(AppListCommand.DismissActions)
                is NativeDragEffect.StartDrag ->
                    host.beginDrag(effect.key, lastPointerY)
                is NativeDragEffect.Move ->
                    AppListMovePlanner.fromKeys(
                        keys = host.currentKeys(),
                        movedKey = effect.source,
                        targetKey = effect.target,
                    )?.let(host::dispatch)
                is NativeDragEffect.FolderHoverChanged ->
                    host.setFolderHover(effect.target)
                is NativeDragEffect.CreateFolder ->
                    AppListMovePlanner.folderFromKeys(
                        keys = host.currentKeys(),
                        sourceKey = effect.source,
                        targetKey = effect.target,
                    )?.let(host::dispatch)
                NativeDragEffect.FinishDrag -> host.endDrag()
            }
        }
    }

    private fun updateAutoScrollSchedule() {
        val delta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (delta == 0f) {
            cancelFrame()
        } else if (!frameScheduled) {
            frameScheduled = true
            scheduler.postFrame(::onAutoScrollFrame)
        }
    }

    private fun onAutoScrollFrame() {
        frameScheduled = false
        if (released || !machine.isDragging) return
        val delta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (delta == 0f) return
        host.scrollBy(delta)
        process(
            machine.onMove(
                pointerX = lastPointerX,
                pointerY = lastPointerY,
                targetKey = host.keyAt(lastPointerX, lastPointerY),
                speedPxPerMs = 0f,
                nowMillis = scheduler.nowMillis,
            ),
        )
        updateAutoScrollSchedule()
    }

    private fun cancelCurrentGesture() {
        process(machine.cancel())
        cancelCallbacks()
        restoreLongClick()
    }

    private fun cancelCallbacks() {
        scheduler.cancelDelayed()
        cancelFrame()
    }

    private fun cancelFrame() {
        if (!frameScheduled) return
        frameScheduled = false
        scheduler.cancelFrame()
    }

    private fun restoreLongClick() {
        suppressedLongClickKey?.let { host.suppressLongClick(it, false) }
        suppressedLongClickKey = null
    }
}
