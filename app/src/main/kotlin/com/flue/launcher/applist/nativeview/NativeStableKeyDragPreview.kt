package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand

/**
 * Stable-key-only reorder state used while a drag is in progress.
 *
 * The preview is deliberately independent from the persisted app-list model:
 * every hover only changes this small local ordering, and [finish] produces at
 * most one command when the pointer is released.  Cancelling a gesture drops
 * the preview without ever touching the repository.
 */
internal class NativeStableKeyDragPreview {
    private var sourceKey: AppItemKey? = null
    private var originalOrder: List<AppItemKey> = emptyList()
    private var previewOrder: List<AppItemKey> = emptyList()

    val isActive: Boolean
        get() = sourceKey != null

    val currentOrder: List<AppItemKey>
        get() = previewOrder

    val originalOrderSnapshot: List<AppItemKey>
        get() = originalOrder

    fun begin(source: AppItemKey, keys: List<AppItemKey>): Boolean {
        if (source !in keys || keys.size != keys.toSet().size) {
            reset()
            return false
        }
        sourceKey = source
        originalOrder = keys.toList()
        previewOrder = originalOrder
        return true
    }

    /**
     * Moves the source to the target's current visual slot.  The returned
     * order is a new immutable snapshot suitable for an adapter submission.
     */
    fun moveTo(target: AppItemKey): List<AppItemKey>? {
        val source = sourceKey ?: return null
        val current = previewOrder
        val fromIndex = current.indexOf(source)
        val targetIndex = current.indexOf(target)
        if (
            fromIndex < 0 ||
            targetIndex < 0 ||
            fromIndex == targetIndex ||
            target == source
        ) {
            return null
        }

        val next = current.toMutableList().apply {
            removeAt(fromIndex)
            // The target index is intentionally not decremented when moving
            // down: after removing the source, inserting at the old target
            // index places it immediately after that target, matching the
            // AppListMovePlanner semantics.
            add(targetIndex, source)
        }
        previewOrder = next.toList()
        return previewOrder
    }

    /**
     * Ends the preview.  A committed drag emits one stable-key command; a
     * cancelled drag emits none and leaves [originalOrderSnapshot] available
     * to the adapter for visual restoration until this call returns.
     */
    fun finish(commit: Boolean): AppListCommand.Move? {
        val source = sourceKey
        val original = originalOrder
        val preview = previewOrder
        val command = if (commit && source != null && preview != original) {
            val sourceIndex = preview.indexOf(source)
            if (sourceIndex >= 0) {
                AppListCommand.Move(
                    key = source,
                    beforeKey = preview.getOrNull(sourceIndex + 1),
                )
            } else {
                null
            }
        } else {
            null
        }
        reset()
        return command
    }

    fun reset() {
        sourceKey = null
        originalOrder = emptyList()
        previewOrder = emptyList()
    }
}
