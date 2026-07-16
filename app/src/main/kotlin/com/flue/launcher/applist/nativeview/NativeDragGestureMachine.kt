package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.hypot

internal sealed interface NativeDragEffect {
    data class ShowActions(val key: AppItemKey) : NativeDragEffect
    data object DismissActions : NativeDragEffect
    data class StartDrag(val key: AppItemKey) : NativeDragEffect
    data class Move(val source: AppItemKey, val target: AppItemKey) : NativeDragEffect
    data class FolderHoverChanged(val target: AppItemKey?) : NativeDragEffect
    data class CreateFolder(val source: AppItemKey, val target: AppItemKey) : NativeDragEffect
    data object FinishDrag : NativeDragEffect
}

/**
 * Pure gesture state for the native drawer. Android touch dispatch, hit testing,
 * frame scheduling and rendering stay in [NativeAppListDragController].
 */
internal class NativeDragGestureMachine(
    private val policy: NativeDragPolicy,
) {
    private val folderHoverGate = NativeFolderHoverGate(policy)
    private var sourceKey: AppItemKey? = null
    private var downX = 0f
    private var downY = 0f
    private var downAtMillis = 0L
    private var longPressDispatched = false
    private var lastMoveTarget: AppItemKey? = null
    private var armedFolderTarget: AppItemKey? = null

    var isTracking: Boolean = false
        private set
    var isDragging: Boolean = false
        private set
    val hasLongPressed: Boolean
        get() = longPressDispatched

    fun onDown(
        sourceKey: AppItemKey,
        pointerX: Float,
        pointerY: Float,
        nowMillis: Long,
    ) {
        resetState()
        this.sourceKey = sourceKey
        downX = pointerX
        downY = pointerY
        downAtMillis = nowMillis
        isTracking = true
    }

    fun onTime(nowMillis: Long): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
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
    ): List<NativeDragEffect> {
        val source = sourceKey ?: return emptyList()
        if (!isTracking) return emptyList()

        val distance = hypot(pointerX - downX, pointerY - downY)
        if (!longPressDispatched) {
            if (distance > policy.startThresholdPx) resetState()
            return emptyList()
        }

        if (!isDragging) {
            if (distance <= policy.startThresholdPx) return emptyList()
            isDragging = true
            lastMoveTarget = source
            return listOf(
                NativeDragEffect.DismissActions,
                NativeDragEffect.StartDrag(source),
            )
        }

        val effects = mutableListOf<NativeDragEffect>()
        val folderCandidate = targetKey?.takeUnless { it == source }
        val nextArmedTarget = folderHoverGate.update(
            candidateKey = folderCandidate?.value,
            speedPxPerMs = speedPxPerMs,
            nowMillis = nowMillis,
        )?.let(::AppItemKey)
        if (nextArmedTarget != armedFolderTarget) {
            armedFolderTarget = nextArmedTarget
            effects += NativeDragEffect.FolderHoverChanged(nextArmedTarget)
        }
        if (targetKey != null && targetKey != source && targetKey != lastMoveTarget) {
            lastMoveTarget = targetKey
            effects += NativeDragEffect.Move(source, targetKey)
        }
        return effects
    }

    fun onUp(currentTargetKey: AppItemKey?): List<NativeDragEffect> {
        if (!isTracking) return emptyList()
        val source = sourceKey
        val wasDragging = isDragging
        val folderTarget = armedFolderTarget?.takeIf { it == currentTargetKey && it != source }
        val effects = mutableListOf<NativeDragEffect>()
        if (source != null && folderTarget != null) {
            effects += NativeDragEffect.CreateFolder(source, folderTarget)
        }
        if (armedFolderTarget != null) effects += NativeDragEffect.FolderHoverChanged(null)
        if (wasDragging) effects += NativeDragEffect.FinishDrag
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
        armedFolderTarget = null
        folderHoverGate.reset()
    }
}
