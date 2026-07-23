package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import kotlin.math.hypot

internal interface NativeAppListDragHost {
    val viewportHeightPx: Float
    /** Strict visual hit used to acquire a gesture source. */
    fun keyAt(x: Float, y: Float): AppItemKey?
    /** Reorder may use a nearby cell after a strict source has already been acquired. */
    fun reorderKeyAt(x: Float, y: Float): AppItemKey? = keyAt(x, y)
    fun folderKeyAt(x: Float, y: Float): AppItemKey? = keyAt(x, y)
    fun currentKeys(): List<AppItemKey>
    fun planMove(source: AppItemKey, target: AppItemKey): AppListCommand.Move?
    fun planFolder(source: AppItemKey, target: AppItemKey): AppListCommand.CreateFolder?
    fun beginDragPreview(source: AppItemKey): Boolean = false
    fun previewMove(source: AppItemKey, target: AppItemKey): Boolean = false
    fun finishDragPreview(commit: Boolean): AppListCommand.Move? = null
    fun dispatch(command: AppListCommand)
    fun suppressLongClick(key: AppItemKey, suppress: Boolean)
    fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float)
    fun updateDrag(pointerX: Float, pointerY: Float)
    fun endDrag()
    fun setFolderHover(key: AppItemKey?)
    fun scrollBy(deltaPx: Float)
    fun performDragHaptic()
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
    private val policyProvider: () -> NativeDragPolicy,
    private val allowFolderCreation: () -> Boolean = { true },
) {
    constructor(
        host: NativeAppListDragHost,
        scheduler: NativeDragScheduler,
        policy: NativeDragPolicy,
        allowFolderCreation: () -> Boolean = { true },
    ) : this(host, scheduler, { policy }, allowFolderCreation)

    private val machine = NativeDragGestureMachine()
    private var activePolicy: NativeDragPolicy? = null
    private var suppressedLongClickKey: AppItemKey? = null
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerAtMillis = 0L
    private var frameScheduled = false
    private var folderCommandDispatched = false
    private var released = false

    val isActive: Boolean
        get() = !released && machine.isTracking
    val isDragging: Boolean
        get() = !released && machine.isDragging

    fun onDown(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released) return false
        cancelCurrentGesture()
        val source = host.keyAt(x, y) ?: return false
        val policy = policyProvider()
        activePolicy = policy
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        machine.onDown(source, x, y, eventTimeMillis, policy)
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
        val speed = hypot(x - lastPointerX, y - lastPointerY) / elapsed
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        val effects = machine.onMove(
            pointerX = x,
            pointerY = y,
            targetKey = host.reorderKeyAt(x, y),
            speedPxPerMs = speed,
            nowMillis = eventTimeMillis,
            folderCandidateKey = host.folderKeyAt(x, y),
        )
        process(effects)
        if (!machine.isTracking) {
            scheduler.cancelDelayed()
            restoreLongClick()
            return false
        }
        if (machine.isDragging) {
            host.updateDrag(x, y)
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
        process(machine.onUp(host.folderKeyAt(x, y)))
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
                    {
                        host.performDragHaptic()
                        host.dispatch(AppListCommand.ShowActions(effect.key))
                    }
                NativeDragEffect.DismissActions ->
                    host.dispatch(AppListCommand.DismissActions)
                is NativeDragEffect.StartDrag -> {
                    host.performDragHaptic()
                    folderCommandDispatched = false
                    host.beginDragPreview(effect.key)
                    host.beginDrag(effect.key, lastPointerX, lastPointerY)
                }
                is NativeDragEffect.Move ->
                    host.previewMove(effect.source, effect.target)
                is NativeDragEffect.FolderHoverChanged ->
                    host.setFolderHover(effect.target.takeIf { allowFolderCreation() })
                is NativeDragEffect.CreateFolder ->
                    if (allowFolderCreation()) {
                        host.planFolder(effect.source, effect.target)?.let { command ->
                            folderCommandDispatched = true
                            host.dispatch(command)
                        }
                    }
                NativeDragEffect.CommitDrag -> {
                    host.finishDragPreview(commit = !folderCommandDispatched)
                        ?.let(host::dispatch)
                    host.endDrag()
                    folderCommandDispatched = false
                }
                NativeDragEffect.FinishDrag -> {
                    host.finishDragPreview(commit = false)
                    host.endDrag()
                    folderCommandDispatched = false
                }
            }
        }
        if (!machine.isTracking) activePolicy = null
    }

    private fun updateAutoScrollSchedule() {
        val policy = activePolicy ?: return
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
        val policy = activePolicy ?: return
        val delta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (delta == 0f) return
        host.scrollBy(delta)
        host.updateDrag(lastPointerX, lastPointerY)
        process(
            machine.onMove(
                pointerX = lastPointerX,
                pointerY = lastPointerY,
                targetKey = host.reorderKeyAt(lastPointerX, lastPointerY),
                speedPxPerMs = 0f,
                nowMillis = scheduler.nowMillis,
                folderCandidateKey = host.folderKeyAt(lastPointerX, lastPointerY),
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
