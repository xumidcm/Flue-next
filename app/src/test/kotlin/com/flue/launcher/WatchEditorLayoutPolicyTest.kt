package com.flue.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorLayoutPolicyTest {
    @Test
    fun staleTransformCommitDoesNotRestoreAnOlderSelection() {
        assertTrue(
            !shouldKeepWatchEditorSelectionForTransformCommit(
                transformStartSelectionGeneration = 4,
                currentSelectionGeneration = 5,
            ),
        )
        assertTrue(
            shouldKeepWatchEditorSelectionForTransformCommit(
                transformStartSelectionGeneration = 5,
                currentSelectionGeneration = 5,
            ),
        )
    }

    @Test
    fun compactWatchKeepsCanvasLargeEnoughForDirectManipulation() {
        val preview = watchEditorPreviewSizeDp(maxWidthDp = 240f, maxHeightDp = 280f)

        assertEquals(123.2f, preview, 0.001f)
        assertTrue(preview >= 120f)
        assertTrue(preview < 280f * 0.5f)
    }

    @Test
    fun largeCanvasRemainsBoundedToWatchmakerCoordinates() {
        assertEquals(
            512f,
            watchEditorPreviewSizeDp(maxWidthDp = 900f, maxHeightDp = 1_200f),
            0f,
        )
    }

    @Test
    fun narrowViewportWinsOverHeightBudget() {
        assertEquals(
            184f,
            watchEditorPreviewSizeDp(maxWidthDp = 200f, maxHeightDp = 800f),
            0f,
        )
    }

    @Test
    fun busyStateNeverSuppressesTransformRollback() {
        assertTrue(
            shouldHandleWatchEditorTransformEnd(
                cancelled = true,
                busy = true,
            ),
        )
        assertTrue(
            shouldHandleWatchEditorTransformEnd(
                cancelled = false,
                busy = false,
            ),
        )
        assertTrue(
            !shouldHandleWatchEditorTransformEnd(
                cancelled = false,
                busy = true,
            ),
        )
    }

    @Test
    fun pointerGesturesFinishAsIndependentTransactions() {
        val lifecycle = WatchEditorGestureLifecycle()

        lifecycle.begin()
        assertEquals(
            WatchEditorGestureEnd.COMMIT,
            lifecycle.finish(completedNormally = true),
        )
        lifecycle.begin()
        assertEquals(
            WatchEditorGestureEnd.ROLLBACK,
            lifecycle.finish(completedNormally = false),
        )
        assertEquals(null, lifecycle.finish(completedNormally = true))
    }

    @Test
    fun transformCommitGateRejectsTheNextGestureUntilThePreviousCommitFinishes() {
        val gate = WatchEditorTransformGate()

        assertTrue(gate.tryBeginGesture())
        assertTrue(gate.beginCommit())
        assertTrue(!gate.tryBeginGesture())
        assertTrue(gate.isBusy())

        gate.finishCommit()

        assertTrue(gate.tryBeginGesture())
        gate.cancelGesture()
        assertTrue(!gate.isBusy())
    }
}
