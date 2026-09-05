package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import kotlin.math.abs

/**
 * Resolves an exact nearest-slot tie in the direction of the active drag.
 *
 * A dragged source occupies a virtual slot in the preview order.  If the
 * pointer lands exactly between that slot and its neighbour, iterating
 * children in adapter order can select the source again and suppress the next
 * preview move.  The directional tie-break keeps the gesture monotonic at the
 * boundary without changing ordinary nearest-slot hit testing.
 */
internal fun nativeReorderTiePrefersForwardCandidate(
    candidateDistance: Float,
    bestDistance: Float,
    candidateCenterX: Float,
    candidateCenterY: Float,
    bestCenterX: Float,
    bestCenterY: Float,
    directionX: Float,
    directionY: Float,
): Boolean {
    if (
        !candidateDistance.isFinite() ||
        !bestDistance.isFinite() ||
        !candidateCenterX.isFinite() ||
        !candidateCenterY.isFinite() ||
        !bestCenterX.isFinite() ||
        !bestCenterY.isFinite() ||
        !directionX.isFinite() ||
        !directionY.isFinite()
    ) return false
    if (abs(candidateDistance - bestDistance) > NATIVE_REORDER_TIE_TOLERANCE_PX) {
        return false
    }
    val directionLengthSquared = directionX * directionX + directionY * directionY
    if (!directionLengthSquared.isFinite() || directionLengthSquared <= 0.0001f) {
        return false
    }
    val candidateDeltaX = candidateCenterX - bestCenterX
    val candidateDeltaY = candidateCenterY - bestCenterY
    return candidateDeltaX * directionX + candidateDeltaY * directionY > 0.0001f
}

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
    fun completeDragPreview(accepted: Boolean) = Unit
    fun previewFolderCommit(source: AppItemKey, target: AppItemKey): Boolean = false
    fun previewFolderCommit(
        source: AppItemKey,
        target: AppItemKey,
        folderKey: AppItemKey?,
    ): Boolean = previewFolderCommit(source, target)
    /** Blanks the exact source/target Views before a synchronous owner mutation. */
    fun prepareFolderDispatchVisuals(source: AppItemKey, target: AppItemKey) = Unit
    /** Restores a rejected preparation or permanently releases accepted rows. */
    fun completeFolderDispatchVisuals(accepted: Boolean) = Unit
    /**
     * Dispatches a folder command and returns the authoritative folder key
     * when the command owner can provide it.  The default keeps old hosts and
     * tests source-compatible; the result's folderKey may be null for legacy
     * owners.
     */
    fun dispatchFolder(command: AppListCommand.CreateFolder): NativeFolderDispatchResult =
        NativeFolderDispatchResult(accepted = dispatch(command), folderKey = null)
    fun discardDragPreviewAwaitingAuthoritative() = Unit
    /**
     * Dispatches a command and reports whether the owner accepted it.
     *
     * Folder creation is optimistic while the pointer is still owned by the
     * drag controller.  The boolean lets the controller keep that preview
     * when the repository rejects the command, so the normal move rollback
     * path can restore it exactly once.
     */
    fun dispatch(command: AppListCommand): Boolean
    fun suppressLongClick(key: AppItemKey, suppress: Boolean)
    fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float)
    /** Starts the overlay while preserving the original DOWN-to-row offset. */
    fun beginDrag(
        key: AppItemKey,
        pointerX: Float,
        pointerY: Float,
        downX: Float,
        downY: Float,
    ) = beginDrag(key, pointerX, pointerY)
    fun updateDrag(pointerX: Float, pointerY: Float)
    fun endDrag(committed: Boolean)
    fun setFolderCandidate(key: AppItemKey?) = Unit
    fun setFolderHover(key: AppItemKey?)
    /** Returns actual consumed pixels, including zero at an edge. */
    fun scrollBy(deltaPx: Float): Float
    fun performDragHaptic()
    fun performFolderHaptic() = performDragHaptic()
}

internal data class NativeFolderDispatchResult(
    val accepted: Boolean,
    val folderKey: AppItemKey?,
)

internal interface NativeDragScheduler {
    val nowMillis: Long
    fun postLongPress(delayMillis: Long, action: () -> Unit)
    fun cancelLongPress()
    fun postFolderHover(delayMillis: Long, action: () -> Unit)
    fun cancelFolderHover()
    fun postFrame(action: () -> Unit)
    fun cancelFrame()
}

/** Bridges Android pointer events to stable-key commands without mutating adapter data. */
internal class NativeAppListDragController(
    private val host: NativeAppListDragHost,
    private val scheduler: NativeDragScheduler,
    private val policyProvider: () -> NativeDragPolicy,
    private val allowFolderCreation: () -> Boolean = { true },
    /**
     * 起拖总闸。覆层（文件夹展开 / 长按菜单）在场时为 false：Compose 覆层
     * 盖在上面，底下的 RecyclerView 仍然收得到长按并把图标拖走。
     */
    private val dragEnabled: () -> Boolean = { true },
) {
    constructor(
        host: NativeAppListDragHost,
        scheduler: NativeDragScheduler,
        policy: NativeDragPolicy,
        allowFolderCreation: () -> Boolean = { true },
        dragEnabled: () -> Boolean = { true },
    ) : this(host, scheduler, { policy }, allowFolderCreation, dragEnabled)

    private val machine = NativeDragGestureMachine()
    private var activePolicy: NativeDragPolicy? = null
    private var suppressedLongClickKey: AppItemKey? = null
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerAtMillis = 0L
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var lastAutoScrollFrameAtMillis: Long? = null
    private var frameScheduled = false
    private var folderCommandDispatched = false
    /**
     * Folder creation is deliberately optimistic.  When the policy or the
     * repository rejects it at release time, retain a stable-key move plan so
     * the same gesture can still settle as a normal reorder.
     */
    private var folderFallbackMove: AppListCommand.Move? = null
    private var scheduledFolderTarget: AppItemKey? = null
    private var scheduledFolderDeadlineMillis = Long.MIN_VALUE
    private var released = false

    val isActive: Boolean
        get() = !released && machine.isTracking
    val isDragging: Boolean
        get() = !released && machine.isDragging

    fun onDown(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released) return false
        cancelCurrentGesture()
        // 覆层在场：不登记手势、不排长按，列表这一层彻底不起拖。
        if (!dragEnabled()) return false
        val source = host.keyAt(x, y) ?: return false
        val policy = policyProvider()
        activePolicy = policy
        gestureDownX = x
        gestureDownY = y
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        machine.onDown(
            source,
            x,
            y,
            eventTimeMillis,
            policy,
            folderCreationEnabled = allowFolderCreation(),
        )
        suppressedLongClickKey = source
        host.suppressLongClick(source, true)
        scheduler.postLongPress(policy.longPressMillis) {
            if (!released) process(machine.onTime(scheduler.nowMillis))
        }
        return false
    }

    fun onMove(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released || !machine.isTracking) return false
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        val wasDragging = machine.isDragging
        if (wasDragging) {
            // The overlay is the spatial source for round/flow hit testing.
            // Move it before sampling the target, otherwise every MOVE tests
            // the previous pointer position and a drag can stick between rows.
            host.updateDrag(x, y)
        }
        val reorderTarget = host.reorderKeyAt(x, y)
        val folderCandidate = if (allowFolderCreation()) host.folderKeyAt(x, y) else null
        val effects = machine.onMove(
            pointerX = x,
            pointerY = y,
            targetKey = reorderTarget,
            nowMillis = eventTimeMillis,
            folderCandidateKey = folderCandidate,
        )
        process(effects)
        if (!machine.isTracking) {
            scheduler.cancelLongPress()
            restoreLongClick()
            return false
        }
        if (machine.isDragging) {
            if (!wasDragging) {
                host.updateDrag(x, y)
                // The preview and overlay now exist: resample on this same MOVE.
                process(machine.onMove(
                    pointerX = x,
                    pointerY = y,
                    targetKey = host.reorderKeyAt(x, y),
                    nowMillis = eventTimeMillis,
                    folderCandidateKey = if (allowFolderCreation()) host.folderKeyAt(x, y) else null,
                    sampleKind = NativeDragSampleKind.START,
                ))
            }
            updateFolderHoverSchedule()
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
        if (machine.isDragging) host.updateDrag(x, y)
        val sampledFolderTarget = if (allowFolderCreation()) host.folderKeyAt(x, y) else null
        val folderTarget = sampledFolderTarget.takeIf { it == machine.folderHoverTarget }
        val reorderTarget = host.reorderKeyAt(x, y)
        process(
            machine.onUp(
                currentFolderTargetKey = folderTarget,
                currentReorderTargetKey = reorderTarget,
                pointerX = x,
                pointerY = y,
            ),
        )
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

    /**
     * Cancels the gesture for a reason outside the pointer stream (geometry or
     * presentation change, scene turning inactive). Unlike [onCancel] it keeps
     * the source row's long-click suppressed: the finger may still be down and
     * restoring long-click before the row's CheckForTap arms would let the
     * row's own long-press fire at stale coordinates. The real terminal sample
     * restores it through [finishAdditionalPointer] in the dispatch funnel.
     */
    fun invalidateForExternalChange() {
        if (released || !machine.isTracking) return
        process(machine.cancel())
        cancelCallbacks()
    }

    /**
     * Cancels a gesture after a second pointer arrives, but keeps the source
     * row's long-click suppressed until the real terminal event. Otherwise
     * Android's child View can fire its own long-click a few hundred ms later.
     */
    fun onAdditionalPointer(): Boolean {
        if (released || !machine.isTracking) return false
        val consumed = machine.isDragging || machine.hasLongPressed
        process(machine.cancel())
        cancelCallbacks()
        return consumed
    }

    fun finishAdditionalPointer() {
        restoreLongClick()
    }

    fun reconcileCurrentKeys() {
        if (released) return
        process(machine.reconcileCurrentKeys(host.currentKeys()))
        if (!machine.isTracking) {
            cancelCallbacks()
            restoreLongClick()
        } else {
            updateFolderHoverSchedule()
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
                        // The result is deliberately ignored: a host that
                        // suppresses the menu must not kill the gesture — the
                        // long-press still owns it and a following move starts
                        // a drag. The cross-gesture lock-up lived in the touch
                        // funnel (terminal-latched dragTouchOwned), not here.
                        host.dispatch(AppListCommand.ShowActions(effect.key))
                    }
                NativeDragEffect.DismissActions ->
                    host.dispatch(AppListCommand.DismissActions)
                is NativeDragEffect.StartDrag -> {
                    folderCommandDispatched = false
                    folderFallbackMove = null
                    if (!host.beginDragPreview(effect.key)) {
                        // The adapter snapshot changed after the long press.
                        // Never create an overlay without an owned stable-key
                        // preview; cancel the machine and restore input state.
                        process(machine.cancel())
                        return@forEach
                    }
                    host.performDragHaptic()
                    host.beginDrag(
                        key = effect.key,
                        pointerX = lastPointerX,
                        pointerY = lastPointerY,
                        downX = gestureDownX,
                        downY = gestureDownY,
                    )
                }
                is NativeDragEffect.Move -> {
                    if (host.previewMove(effect.source, effect.target)) {
                        machine.acceptMovePreview(effect.target)
                    } else {
                        machine.rejectMovePreview(effect.target)
                    }
                }
                is NativeDragEffect.FolderCandidateChanged ->
                    host.setFolderCandidate(effect.target.takeIf { allowFolderCreation() })
                is NativeDragEffect.FolderHoverChanged -> {
                    val target = effect.target.takeIf { allowFolderCreation() }
                    host.setFolderHover(target)
                    if (target != null) host.performFolderHaptic()
                }
                is NativeDragEffect.CreateFolder ->
                    if (allowFolderCreation()) {
                        val command = host.planFolder(effect.source, effect.target)
                        if (command != null) {
                            host.prepareFolderDispatchVisuals(effect.source, effect.target)
                            val result = try {
                                host.dispatchFolder(command)
                            } catch (error: Throwable) {
                                host.completeFolderDispatchVisuals(accepted = false)
                                throw error
                            }
                            host.completeFolderDispatchVisuals(result.accepted)
                            if (result.accepted) {
                                host.previewFolderCommit(
                                    effect.source,
                                    effect.target,
                                    result.folderKey,
                                )
                                folderCommandDispatched = true
                            } else {
                                // The UI/repository rejected the folder
                                // request.  The target remains a valid
                                // reorder destination. Restore exactly one
                                // stable-key move preview so CommitDrag can
                                // settle the same gesture normally.
                                restoreFolderTargetAsMove(effect.source, effect.target)
                            }
                        } else {
                            // A target rejected by the existing folder policy
                            // remains a valid reorder destination.
                            restoreFolderTargetAsMove(effect.source, effect.target)
                        }
                    }
                NativeDragEffect.CommitDrag -> {
                    if (folderCommandDispatched) {
                        // The repository command is now authoritative. Keep
                        // the currently presented order until its new snapshot
                        // arrives instead of submitting the captured original
                        // rows and producing a reverse Diff animation.
                        host.discardDragPreviewAwaitingAuthoritative()
                        host.endDrag(committed = true)
                    } else {
                        val move = host.finishDragPreview(commit = true) ?: folderFallbackMove
                        val committed = move?.let(host::dispatch) == true
                        host.completeDragPreview(committed)
                        host.endDrag(committed = committed)
                    }
                    folderCommandDispatched = false
                    folderFallbackMove = null
                }
                NativeDragEffect.FinishDrag -> {
                    host.finishDragPreview(commit = false)
                    host.endDrag(committed = false)
                    folderCommandDispatched = false
                    folderFallbackMove = null
                }
            }
        }
        if (!machine.isTracking) activePolicy = null
    }

    private fun restoreFolderTargetAsMove(source: AppItemKey, target: AppItemKey) {
        // The virtual preview can fail if a concurrent adapter update removed
        // either row. Keep a separately planned command as a safe fallback;
        // both representations are keyed by stable IDs rather than positions.
        folderFallbackMove = host.planMove(source, target)
        host.previewMove(source, target)
    }

    private fun updateAutoScrollSchedule() {
        val policy = activePolicy ?: return
        val delta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (delta == 0f) {
            cancelFrame()
        } else if (!frameScheduled) {
            if (lastAutoScrollFrameAtMillis == null) {
                lastAutoScrollFrameAtMillis = lastPointerAtMillis
            }
            frameScheduled = true
            scheduler.postFrame(::onAutoScrollFrame)
        }
    }

    private fun updateFolderHoverSchedule() {
        val target = machine.folderCandidateTarget.takeIf { allowFolderCreation() }
        val deadline = machine.folderDeadlineMillis
        if (target == null || deadline == null) {
            cancelFolderHoverSchedule()
            return
        }
        if (target == scheduledFolderTarget && deadline == scheduledFolderDeadlineMillis) return
        cancelFolderHoverSchedule()
        scheduledFolderTarget = target
        scheduledFolderDeadlineMillis = deadline
        scheduler.postFolderHover((deadline - scheduler.nowMillis).coerceAtLeast(0L)) {
            scheduledFolderTarget = null
            scheduledFolderDeadlineMillis = Long.MIN_VALUE
            if (released || !machine.isDragging || !allowFolderCreation()) return@postFolderHover
            process(machine.onMove(
                pointerX = lastPointerX,
                pointerY = lastPointerY,
                targetKey = host.reorderKeyAt(lastPointerX, lastPointerY),
                nowMillis = scheduler.nowMillis,
                folderCandidateKey = host.folderKeyAt(lastPointerX, lastPointerY),
                sampleKind = NativeDragSampleKind.HOVER,
            ))
            updateFolderHoverSchedule()
        }
    }

    private fun cancelFolderHoverSchedule() {
        if (scheduledFolderTarget == null) return
        scheduler.cancelFolderHover()
        scheduledFolderTarget = null
        scheduledFolderDeadlineMillis = Long.MIN_VALUE
    }

    private fun onAutoScrollFrame() {
        frameScheduled = false
        if (released || !machine.isDragging) return
        val policy = activePolicy ?: return
        val baseDelta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (baseDelta == 0f) {
            lastAutoScrollFrameAtMillis = null
            return
        }
        val nowMillis = scheduler.nowMillis
        val previousFrameMillis = lastAutoScrollFrameAtMillis
        val elapsedMillis = if (
            previousFrameMillis == null || nowMillis <= previousFrameMillis
        ) {
            AUTO_SCROLL_REFERENCE_FRAME_MILLIS
        } else {
            (nowMillis - previousFrameMillis)
                .toFloat()
                .coerceIn(MIN_AUTO_SCROLL_FRAME_MILLIS, MAX_AUTO_SCROLL_FRAME_MILLIS)
        }
        lastAutoScrollFrameAtMillis = nowMillis
        val delta = baseDelta * (elapsedMillis / AUTO_SCROLL_REFERENCE_FRAME_MILLIS)
        val consumedScroll = host.scrollBy(delta)
        host.updateDrag(lastPointerX, lastPointerY)
        val folderCandidate = if (consumedScroll == 0f && allowFolderCreation()) {
            host.folderKeyAt(lastPointerX, lastPointerY)
        } else {
            null
        }
        process(
            machine.onMove(
                pointerX = lastPointerX,
                pointerY = lastPointerY,
                // Actual content movement cancels dwell; newly exposed slots
                // remain eligible for reorder even without pointer movement.
                targetKey = host.reorderKeyAt(lastPointerX, lastPointerY),
                nowMillis = scheduler.nowMillis,
                folderCandidateKey = folderCandidate,
                sampleKind = NativeDragSampleKind.AUTO_SCROLL,
            ),
        )
        updateFolderHoverSchedule()
        updateAutoScrollSchedule()
    }

    private fun cancelCurrentGesture() {
        process(machine.cancel())
        cancelCallbacks()
        restoreLongClick()
    }

    private fun cancelCallbacks() {
        scheduler.cancelLongPress()
        scheduler.cancelFolderHover()
        scheduledFolderTarget = null
        scheduledFolderDeadlineMillis = Long.MIN_VALUE
        cancelFrame()
    }

    private fun cancelFrame() {
        lastAutoScrollFrameAtMillis = null
        if (frameScheduled) {
            frameScheduled = false
            scheduler.cancelFrame()
        }
    }

    private fun restoreLongClick() {
        suppressedLongClickKey?.let { host.suppressLongClick(it, false) }
        suppressedLongClickKey = null
    }

    private companion object {
        private const val AUTO_SCROLL_REFERENCE_FRAME_MILLIS = 1000f / 60f
        private const val MIN_AUTO_SCROLL_FRAME_MILLIS = 1f
        private const val MAX_AUTO_SCROLL_FRAME_MILLIS = 50f
    }

}

private const val NATIVE_REORDER_TIE_TOLERANCE_PX = 0.5f
