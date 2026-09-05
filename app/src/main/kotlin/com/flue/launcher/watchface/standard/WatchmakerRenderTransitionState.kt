package com.flue.launcher.watchface.standard

import java.util.EnumMap

/** States shared by the watch-face renderer and its overlay host. */
internal enum class WatchmakerRenderState {
    IDLE,
    FREEZING,
    FROZEN_OVERLAY,
    RESUMING,
}

/** A small idempotent state machine for overlay transitions. */
internal class WatchmakerRenderTransitionStateMachine {
    var current: WatchmakerRenderState = WatchmakerRenderState.IDLE
        private set

    fun beginFreeze(): Boolean = transitionFrom(
        expected = WatchmakerRenderState.IDLE,
        next = WatchmakerRenderState.FREEZING,
    )

    fun markFrozenFrameReady(): Boolean = transitionFrom(
        expected = WatchmakerRenderState.FREEZING,
        next = WatchmakerRenderState.FROZEN_OVERLAY,
    )

    fun beginResume(): Boolean = when (current) {
        WatchmakerRenderState.FROZEN_OVERLAY,
        WatchmakerRenderState.FREEZING -> {
            current = WatchmakerRenderState.RESUMING
            true
        }
        else -> false
    }

    fun markResumeComplete(): Boolean = transitionFrom(
        expected = WatchmakerRenderState.RESUMING,
        next = WatchmakerRenderState.IDLE,
    )

    private fun transitionFrom(
        expected: WatchmakerRenderState,
        next: WatchmakerRenderState,
    ): Boolean {
        if (current != expected) return false
        current = next
        return true
    }
}

/**
 * Coordinates the renderer-side portion of a launcher overlay transition.
 *
 * The launcher may recompose many times while a gesture is in flight. Keeping
 * this boundary idempotent prevents repeated tick cancellation/rescheduling
 * and gives the Stage a single answer for whether dynamic rendering is safe.
 */
internal class WatchmakerOverlayRenderGate {
    private val stateMachine = WatchmakerRenderTransitionStateMachine()

    val state: WatchmakerRenderState
        get() = stateMachine.current

    val dynamicWorkPaused: Boolean
        get() = state != WatchmakerRenderState.IDLE

    fun beginOverlayFreeze(): Boolean = stateMachine.beginFreeze()

    fun markFrozenFrameReady(): Boolean = stateMachine.markFrozenFrameReady()

    fun beginOverlayResume(): Boolean = stateMachine.beginResume()

    fun markResumeComplete(): Boolean = stateMachine.markResumeComplete()
}

/**
 * Keeps a hidden Watchmaker renderer paused for the full lifetime of every
 * launcher backdrop, not only while the opening gesture owns the pointer.
 *
 * A settled app list or side scene still redraws its own content. If the watch
 * face remains active, its video, Lua, sensors and ticks compete for the same
 * UI/render threads even though the backdrop only needs one frozen frame.
 */
internal fun shouldFreezeWatchmakerForLauncherBackdrop(
    sideSceneOverlayActive: Boolean,
    appListBackdropActive: Boolean,
): Boolean = sideSceneOverlayActive || appListBackdropActive

/** The independent consumers that can still reference a captured frame. */
internal enum class WatchmakerBitmapConsumer {
    PIXEL_COPY,
    COMPOSE,
    RENDER_THREAD,
    TEXTURE_VIEW,
}

/** Reference-counted ownership guard for a bitmap crossing UI/render boundaries. */
internal class WatchmakerBitmapLease(
    private val onRecycle: () -> Unit = {},
) {
    private val counts = EnumMap<WatchmakerBitmapConsumer, Int>(WatchmakerBitmapConsumer::class.java)
    private var retired = false
    private var recycleCalled = false

    val canRecycle: Boolean
        get() = counts.values.all { it <= 0 }

    @Synchronized
    fun acquire(consumer: WatchmakerBitmapConsumer) {
        counts[consumer] = (counts[consumer] ?: 0) + 1
    }

    @Synchronized
    fun release(consumer: WatchmakerBitmapConsumer) {
        val count = counts[consumer] ?: return
        if (count <= 1) counts.remove(consumer) else counts[consumer] = count - 1
        recycleIfRetiredLocked()
    }

    /** Marks the bitmap as detached from its producer; active consumers may finish reading it. */
    @Synchronized
    fun retire() {
        retired = true
        recycleIfRetiredLocked()
    }

    private fun recycleIfRetiredLocked() {
        if (!retired || recycleCalled || !canRecycle) return
        recycleCalled = true
        onRecycle()
    }
}
