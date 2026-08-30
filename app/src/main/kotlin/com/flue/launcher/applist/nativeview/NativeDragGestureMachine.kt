package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.abs
import kotlin.math.hypot

internal sealed interface NativeDragEffect {
    data class ShowActions(val key: AppItemKey) : NativeDragEffect
    data object DismissActions : NativeDragEffect
    data class StartDrag(val key: AppItemKey) : NativeDragEffect
    data class Move(val source: AppItemKey, val target: AppItemKey) : NativeDragEffect
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
    private var armedFolderTarget: AppItemKey? = null

    var isTracking: Boolean = false
        private set
    var isDragging: Boolean = false
        private set
    val hasLongPressed: Boolean
        get() = longPressDispatched
    val folderHoverTarget: AppItemKey?
        get() = armedFolderTarget

    /** Records a reorder target only after the host accepted its visual preview. */
    fun acceptMovePreview(target: AppItemKey) {
        if (isDragging && target != sourceKey) {
            lastMoveTarget = target
            lastAcceptedMoveTarget = target
            lastAcceptedMoveDeltaX = pendingMoveDeltaX
            lastAcceptedMoveDeltaY = pendingMoveDeltaY
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
    ) {
        resetState()
        activePolicy = policy
        folderHoverGate = NativeFolderHoverGate(policy)
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
        speedPxPerMs: Float,
        nowMillis: Long,
        folderCandidateKey: AppItemKey? = targetKey,
    ): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
        val policy = activePolicy ?: return emptyList()
        if (!isTracking) return emptyList()

        pendingMoveDeltaX = pointerX - lastPointerX
        pendingMoveDeltaY = pointerY - lastPointerY
        lastPointerX = pointerX
        lastPointerY = pointerY

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
            // Seed the dwell clock on the same sample that starts a drag. The
            // controller may then arm it from a delayed callback even when the
            // finger remains perfectly still over the target.
            folderHoverGate?.update(
                candidateKey = folderCandidateKey?.takeUnless { it == source }?.value,
                speedPxPerMs = speedPxPerMs,
                nowMillis = nowMillis,
            )
            return listOf(
                NativeDragEffect.DismissActions,
                NativeDragEffect.StartDrag(source),
            )
        }

        val effects = mutableListOf<NativeDragEffect>()
        // 候选不再在这里按速度预过滤：gate 要区分「离开了图标」（整门重置）和
        // 「还在同一个图标上但这一采样超速」（只重置停留时钟），必须见到原始候选。
        // 速度合法性由 gate 自己判（它一直就有这道检查）。
        val folderCandidate = folderCandidateKey?.takeUnless { it == source }
        val nextArmedTarget = folderHoverGate?.update(
            candidateKey = folderCandidate?.value,
            speedPxPerMs = speedPxPerMs,
            nowMillis = nowMillis,
        )?.let(::AppItemKey)
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
            // A folder hover owns only the exact candidate it armed on. If
            // the pointer/layout has moved to another reorder slot in this
            // same sample, let that reorder through immediately instead of
            // waiting for a second MOVE after the hover poll.
            targetKey != nextArmedTarget
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
    ): List<NativeDragEffect> {
        if (!isTracking) return emptyList()
        val source = sourceKey
        val wasDragging = isDragging
        val folderTarget = armedFolderTarget?.takeIf {
            it == currentFolderTargetKey && it != source
        }
        val effects = mutableListOf<NativeDragEffect>()
        if (armedFolderTarget != null) effects += NativeDragEffect.FolderHoverChanged(null)
        if (source != null && folderTarget != null) {
            effects += NativeDragEffect.CreateFolder(source, folderTarget)
        }
        if (source != null && wasDragging && folderTarget == null) {
            currentReorderTargetKey
                ?.takeIf { it != source && it != lastMoveTarget }
                ?.let { effects += NativeDragEffect.Move(source, it) }
        }
        if (wasDragging) effects += NativeDragEffect.CommitDrag
        resetState()
        return effects
    }

    fun reconcileCurrentKeys(keys: List<AppItemKey>): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
        if (source in keys) return emptyList()
        return cancel()
    }

    fun cancel(): List<NativeDragEffect> {
        if (!isTracking) return emptyList()
        val effects = mutableListOf<NativeDragEffect>()
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
        armedFolderTarget = null
        folderHoverGate?.reset()
        folderHoverGate = null
        activePolicy = null
    }

    private fun canRevisitLastMoveTargetAfterDirectionChange(): Boolean {
        if (lastAcceptedMoveTarget == null) return false
        val currentLengthSquared =
            pendingMoveDeltaX * pendingMoveDeltaX + pendingMoveDeltaY * pendingMoveDeltaY
        if (!currentLengthSquared.isFinite() || currentLengthSquared <= 0.0001f) return false
        val previousLengthSquared =
            lastAcceptedMoveDeltaX * lastAcceptedMoveDeltaX +
                lastAcceptedMoveDeltaY * lastAcceptedMoveDeltaY
        if (!previousLengthSquared.isFinite()) return false
        if (previousLengthSquared <= 0.0001f) return true
        val directionDot =
            pendingMoveDeltaX * lastAcceptedMoveDeltaX +
                pendingMoveDeltaY * lastAcceptedMoveDeltaY
        return directionDot < 0f
    }
}
