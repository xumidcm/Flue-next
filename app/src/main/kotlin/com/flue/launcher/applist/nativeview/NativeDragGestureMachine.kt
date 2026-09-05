package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.hypot

internal sealed interface NativeDragEffect {
    data class ShowActions(val key: AppItemKey) : NativeDragEffect
    data object DismissActions : NativeDragEffect
    data class StartDrag(val key: AppItemKey) : NativeDragEffect
    data class Move(val source: AppItemKey, val target: AppItemKey) : NativeDragEffect
    data class FolderCandidateChanged(val target: AppItemKey?) : NativeDragEffect
    data class FolderHoverChanged(val target: AppItemKey?) : NativeDragEffect
    data class CreateFolder(val source: AppItemKey, val target: AppItemKey) : NativeDragEffect
    /** The pointer was released; commit the local reorder preview once. */
    data object CommitDrag : NativeDragEffect
    /** The gesture was cancelled or invalidated; discard the local preview. */
    data object FinishDrag : NativeDragEffect
}

/**
 * Pure gesture state for the native drawer. Android touch dispatch, hit testing,
 * frame scheduling and rendering stay in [NativeAppListDragController].
 */
internal enum class NativeDragSampleKind { POINTER, START, HOVER, AUTO_SCROLL }

internal class NativeDragGestureMachine(
    private val defaultPolicy: NativeDragPolicy? = null,
) {
    private var activePolicy: NativeDragPolicy? = null
    private var folderHoverGate: NativeFolderHoverGate? = null
    private var sourceKey: AppItemKey? = null
    private var downX = 0f
    private var downY = 0f
    private var downAtMillis = 0L
    private var longPressDispatched = false
    private var lastMoveTarget: AppItemKey? = null
    private var lastAcceptedMoveTarget: AppItemKey? = null
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var pendingMoveDeltaX = 0f
    private var pendingMoveDeltaY = 0f
    private var lastAcceptedMoveDeltaX = 0f
    private var lastAcceptedMoveDeltaY = 0f
    private var lastTravelDeltaX = 0f
    private var lastTravelDeltaY = 0f
    private var reverseTravelPx = 0f
    private var armedFolderTarget: AppItemKey? = null

    var isTracking: Boolean = false
        private set
    var isDragging: Boolean = false
        private set
    val hasLongPressed: Boolean
        get() = longPressDispatched
    val folderHoverTarget: AppItemKey?
        get() = armedFolderTarget

    val folderCandidateTarget: AppItemKey?
        get() = folderHoverGate?.targetKey?.let(::AppItemKey)
    val folderDeadlineMillis: Long?
        get() = folderHoverGate?.deadlineMillis

    /** Records a reorder target only after the host accepted its visual preview. */
    fun acceptMovePreview(target: AppItemKey) {
        if (isDragging && target != sourceKey) {
            lastMoveTarget = target
            lastAcceptedMoveTarget = target
            if (hypot(pendingMoveDeltaX, pendingMoveDeltaY) > 0.0001f) {
                lastAcceptedMoveDeltaX = pendingMoveDeltaX
                lastAcceptedMoveDeltaY = pendingMoveDeltaY
            }
            reverseTravelPx = 0f
        }
    }

    /** Reverts a host-rejected preview without losing the previous stable target. */
    fun rejectMovePreview(target: AppItemKey) {
        if (isDragging && lastMoveTarget == target) {
            lastMoveTarget = lastAcceptedMoveTarget ?: sourceKey
            if (lastAcceptedMoveTarget == null) {
                lastAcceptedMoveDeltaX = 0f
                lastAcceptedMoveDeltaY = 0f
            }
        }
    }

    fun onDown(
        sourceKey: AppItemKey,
        pointerX: Float,
        pointerY: Float,
        nowMillis: Long,
        policy: NativeDragPolicy = requireNotNull(defaultPolicy),
        folderCreationEnabled: Boolean = true,
    ) {
        resetState()
        activePolicy = policy
        folderHoverGate = NativeFolderHoverGate(policy).takeIf { folderCreationEnabled }
        this.sourceKey = sourceKey
        downX = pointerX
        downY = pointerY
        lastPointerX = pointerX
        lastPointerY = pointerY
        pendingMoveDeltaX = 0f
        pendingMoveDeltaY = 0f
        lastAcceptedMoveDeltaX = 0f
        lastAcceptedMoveDeltaY = 0f
        downAtMillis = nowMillis
        isTracking = true
    }

    fun onTime(nowMillis: Long): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
        val policy = activePolicy ?: return emptyList()
        if (
            !isTracking ||
            longPressDispatched ||
            nowMillis - downAtMillis < policy.longPressMillis
        ) {
            return emptyList()
        }
        longPressDispatched = true
        return listOf(NativeDragEffect.ShowActions(source))
    }

    fun onMove(
        pointerX: Float,
        pointerY: Float,
        targetKey: AppItemKey?,
        nowMillis: Long,
        folderCandidateKey: AppItemKey? = targetKey,
        sampleKind: NativeDragSampleKind = NativeDragSampleKind.POINTER,
    ): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
        val policy = activePolicy ?: return emptyList()
        if (!isTracking) return emptyList()

        if (sampleKind == NativeDragSampleKind.POINTER) {
            recordPointerMotion(pointerX, pointerY)
        } else if (sampleKind != NativeDragSampleKind.START) {
            // Time/layout observations carry no new pointer direction evidence.
            pendingMoveDeltaX = 0f
            pendingMoveDeltaY = 0f
        }

        val distance = hypot(pointerX - downX, pointerY - downY)
        if (!longPressDispatched) {
            if (distance > policy.startThresholdPx) resetState()
            return emptyList()
        }

        if (!isDragging) {
            if (distance <= policy.startThresholdPx) return emptyList()
            isDragging = true
            lastMoveTarget = source
            lastAcceptedMoveTarget = null
            return listOf(
                NativeDragEffect.DismissActions,
                NativeDragEffect.StartDrag(source),
            )
        }

        val effects = mutableListOf<NativeDragEffect>()
        val previousCandidate = folderCandidateTarget
        val nextArmedTarget = folderHoverGate?.update(
            candidateKey = folderCandidateKey?.takeUnless { it == source }?.value,
            x = pointerX,
            y = pointerY,
            nowMillis = nowMillis,
        )?.let(::AppItemKey)
        if (previousCandidate != folderCandidateTarget) {
            effects += NativeDragEffect.FolderCandidateChanged(folderCandidateTarget)
        }
        if (nextArmedTarget != armedFolderTarget) {
            armedFolderTarget = nextArmedTarget
            effects += NativeDragEffect.FolderHoverChanged(nextArmedTarget)
        }
        if (
            targetKey != null &&
            targetKey != source &&
            (
                targetKey != lastMoveTarget ||
                    canRevisitLastMoveTargetAfterDirectionChange()
            ) &&
            // Reserve the candidate before dwell, so reorder cannot move it away.
            folderCandidateTarget == null
        ) {
            // Record the candidate before returning so direct state-machine
            // callers suppress duplicate effects as well. The controller can
            // roll this back if its host rejects the visual preview.
            lastMoveTarget = targetKey
            effects += NativeDragEffect.Move(source, targetKey)
        }
        return effects
    }

    fun onUp(
        currentFolderTargetKey: AppItemKey?,
        currentReorderTargetKey: AppItemKey? = currentFolderTargetKey,
        pointerX: Float = lastPointerX,
        pointerY: Float = lastPointerY,
    ): List<NativeDragEffect> {
        if (!isTracking) return emptyList()
        val source = sourceKey
        val wasDragging = isDragging
        if (pointerX != lastPointerX || pointerY != lastPointerY) recordPointerMotion(pointerX, pointerY)
        val folderTarget = armedFolderTarget?.takeIf {
            it == currentFolderTargetKey && it != source &&
                folderHoverGate?.containsStablePointer(pointerX, pointerY) == true
        }
        val effects = mutableListOf<NativeDragEffect>()
        if (folderCandidateTarget != null) effects += NativeDragEffect.FolderCandidateChanged(null)
        if (armedFolderTarget != null) effects += NativeDragEffect.FolderHoverChanged(null)
        if (source != null && folderTarget != null) {
            effects += NativeDragEffect.CreateFolder(source, folderTarget)
        }
        if (source != null && wasDragging && folderTarget == null) {
            currentReorderTargetKey
                ?.takeIf { it != source && (it != lastMoveTarget ||
                    canRevisitLastMoveTargetAfterDirectionChange(useLastPointerMotion = true)) }
                ?.let { effects += NativeDragEffect.Move(source, it) }
        }
        if (wasDragging) effects += NativeDragEffect.CommitDrag
        resetState()
        return effects
    }

    fun reconcileCurrentKeys(keys: List<AppItemKey>): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
        if (source !in keys) return cancel()
        if (folderCandidateTarget != null && folderCandidateTarget !in keys) {
            val effects = mutableListOf<NativeDragEffect>(NativeDragEffect.FolderCandidateChanged(null))
            if (armedFolderTarget != null) effects += NativeDragEffect.FolderHoverChanged(null)
            folderHoverGate?.reset()
            armedFolderTarget = null
            return effects
        }
        return emptyList()
    }

    fun cancel(): List<NativeDragEffect> {
        if (!isTracking) return emptyList()
        val effects = mutableListOf<NativeDragEffect>()
        if (folderCandidateTarget != null) effects += NativeDragEffect.FolderCandidateChanged(null)
        if (armedFolderTarget != null) effects += NativeDragEffect.FolderHoverChanged(null)
        if (isDragging) effects += NativeDragEffect.FinishDrag
        resetState()
        return effects
    }

    private fun resetState() {
        sourceKey = null
        isTracking = false
        isDragging = false
        longPressDispatched = false
        lastMoveTarget = null
        lastAcceptedMoveTarget = null
        lastPointerX = 0f
        lastPointerY = 0f
        pendingMoveDeltaX = 0f
        pendingMoveDeltaY = 0f
        lastAcceptedMoveDeltaX = 0f
        lastAcceptedMoveDeltaY = 0f
        reverseTravelPx = 0f
        lastTravelDeltaX = 0f
        lastTravelDeltaY = 0f
        armedFolderTarget = null
        folderHoverGate?.reset()
        folderHoverGate = null
        activePolicy = null
    }

    private fun recordPointerMotion(pointerX: Float, pointerY: Float) {
        pendingMoveDeltaX = pointerX - lastPointerX
        pendingMoveDeltaY = pointerY - lastPointerY
        lastPointerX = pointerX
        lastPointerY = pointerY
        val deltaLength = hypot(pendingMoveDeltaX, pendingMoveDeltaY)
        val previousLength = hypot(lastAcceptedMoveDeltaX, lastAcceptedMoveDeltaY)
        val reverseDot = pendingMoveDeltaX * lastAcceptedMoveDeltaX +
            pendingMoveDeltaY * lastAcceptedMoveDeltaY
        if (deltaLength > 0.0001f) {
            lastTravelDeltaX = pendingMoveDeltaX
            lastTravelDeltaY = pendingMoveDeltaY
            if (lastAcceptedMoveTarget != null && previousLength > 0.0001f && reverseDot < 0f) {
                reverseTravelPx += deltaLength
            } else if (reverseDot > 0f) {
                reverseTravelPx = 0f
            }
        }
    }

    private fun canRevisitLastMoveTargetAfterDirectionChange(useLastPointerMotion: Boolean = false): Boolean {
        if (lastAcceptedMoveTarget == null) return false
        val deltaX = if (useLastPointerMotion) lastTravelDeltaX else pendingMoveDeltaX
        val deltaY = if (useLastPointerMotion) lastTravelDeltaY else pendingMoveDeltaY
        val currentLengthSquared = deltaX * deltaX + deltaY * deltaY
        if (!currentLengthSquared.isFinite() || currentLengthSquared <= 0.0001f) return false
        val previousLengthSquared =
            lastAcceptedMoveDeltaX * lastAcceptedMoveDeltaX +
                lastAcceptedMoveDeltaY * lastAcceptedMoveDeltaY
        if (!previousLengthSquared.isFinite()) return false
        if (previousLengthSquared <= 0.0001f) return true
        val directionDot =
            deltaX * lastAcceptedMoveDeltaX + deltaY * lastAcceptedMoveDeltaY
        return directionDot < 0f &&
            reverseTravelPx >= (activePolicy?.reorderHysteresisPx ?: 12f)
    }
}
