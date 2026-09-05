package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDragGestureMachineTest {
    private val policy = NativeDragPolicy(
        startThresholdPx = 20f,
        autoScrollEdgePx = 84f,
        maxAutoScrollPxPerFrame = 30f,
        folderHoverMillis = 500L,

        longPressMillis = 620L,
    )

    @Test
    fun `long press shows actions once then movement beyond threshold starts drag`() {
        val machine = NativeDragGestureMachine(policy)
        machine.onDown(key("a"), pointerX = 10f, pointerY = 100f, nowMillis = 0L)

        assertEquals(emptyList<NativeDragEffect>(), machine.onTime(619L))
        assertEquals(
            listOf(NativeDragEffect.ShowActions(key("a"))),
            machine.onTime(620L),
        )
        assertEquals(emptyList<NativeDragEffect>(), machine.onTime(900L))
        assertEquals(
            listOf(
                NativeDragEffect.DismissActions,
                NativeDragEffect.StartDrag(key("a")),
            ),
            machine.onMove(
                pointerX = 10f,
                pointerY = 121f,
                targetKey = key("a"),
                nowMillis = 901L,
            ),
        )
        assertTrue(machine.isDragging)
    }

    @Test
    fun `movement before long press hands gesture back to scrolling`() {
        val machine = NativeDragGestureMachine(policy)
        machine.onDown(key("a"), pointerX = 0f, pointerY = 0f, nowMillis = 0L)

        assertEquals(
            emptyList<NativeDragEffect>(),
            machine.onMove(0f, 21f, key("b"), 100L),
        )
        assertEquals(emptyList<NativeDragEffect>(), machine.onTime(700L))
        assertFalse(machine.isTracking)
    }

    @Test
    fun `drag emits reorder only when stable target changes`() {
        val machine = draggingMachine()

        assertEquals(
            listOf(NativeDragEffect.Move(key("a"), key("b"))),
            machine.onMove(0f, 130f, key("b"), 700L, folderCandidateKey = null),
        )
        assertEquals(
            emptyList<NativeDragEffect>(),
            machine.onMove(0f, 132f, key("b"), 710L, folderCandidateKey = null),
        )
        assertEquals(
            listOf(NativeDragEffect.Move(key("a"), key("c"))),
            machine.onMove(0f, 180f, key("c"), 720L, folderCandidateKey = null),
        )
    }

    @Test
    fun `slow same target arms folder and release revalidates key`() {
        val machine = draggingMachine()

        assertEquals(
            listOf(NativeDragEffect.FolderCandidateChanged(key("b"))),
            machine.onMove(0f, 130f, key("b"), 700L),
        )
        assertEquals(
            listOf(NativeDragEffect.FolderHoverChanged(key("b"))),
            machine.onMove(0f, 131f, key("b"), 1200L),
        )
        assertEquals(
            listOf(
                NativeDragEffect.FolderCandidateChanged(null),
                NativeDragEffect.FolderHoverChanged(null),
                NativeDragEffect.CreateFolder(key("a"), key("b")),
                NativeDragEffect.CommitDrag,
            ),
            machine.onUp(currentFolderTargetKey = key("b")),
        )

        val changedTarget = draggingMachine()
        changedTarget.onMove(0f, 130f, key("b"), 700L)
        changedTarget.onMove(0f, 131f, key("b"), 1200L)
        assertEquals(
            listOf(
                NativeDragEffect.FolderCandidateChanged(null),
                NativeDragEffect.FolderHoverChanged(null),
                NativeDragEffect.Move(key("a"), key("c")),
                NativeDragEffect.CommitDrag,
            ),
            changedTarget.onUp(
                currentFolderTargetKey = key("c"),
                currentReorderTargetKey = key("c"),
            ),
        )
    }

    @Test
    fun `release before folder dwell falls back to reorder`() {
        val machine = draggingMachine()

        assertEquals(
            listOf(NativeDragEffect.FolderCandidateChanged(key("b"))),
            machine.onMove(0f, 130f, key("b"), 700L),
        )
        assertEquals(
            listOf(NativeDragEffect.FolderCandidateChanged(null),
                NativeDragEffect.Move(key("a"), key("b")), NativeDragEffect.CommitDrag),
            machine.onUp(
                currentFolderTargetKey = key("b"),
                currentReorderTargetKey = key("b"),
            ),
        )
    }

    @Test
    fun `reorder target may be nearest while folder candidate remains strict`() {
        val machine = draggingMachine()

        machine.onMove(
            pointerX = 0f,
            pointerY = 130f,
            targetKey = key("b"),
            nowMillis = 700L,
            folderCandidateKey = null,
        )
        val effects = machine.onMove(
            pointerX = 0f,
            pointerY = 131f,
            targetKey = key("b"),
            nowMillis = 1200L,
            folderCandidateKey = null,
        )

        assertFalse(effects.any { it is NativeDragEffect.FolderHoverChanged })
    }

    @Test
    fun `removing source from latest snapshot cancels drag idempotently`() {
        val machine = draggingMachine()

        assertEquals(
            listOf(NativeDragEffect.FinishDrag),
            machine.reconcileCurrentKeys(listOf(key("b"), key("c"))),
        )
        assertEquals(emptyList<NativeDragEffect>(), machine.cancel())
        assertFalse(machine.isTracking)
    }

    @Test
    fun `cancel clears armed folder and finishes once`() {
        val machine = draggingMachine()
        machine.onMove(0f, 130f, key("b"), 700L)
        machine.onMove(0f, 131f, key("b"), 1200L)

        assertEquals(
            listOf(
                NativeDragEffect.FolderCandidateChanged(null),
                NativeDragEffect.FolderHoverChanged(null),
                NativeDragEffect.FinishDrag,
            ),
            machine.cancel(),
        )
        assertEquals(emptyList<NativeDragEffect>(), machine.cancel())
    }

    @Test
    fun `stationary polls preserve reverse travel between real pointer samples`() {
        val machine = draggingMachine()
        machine.onMove(0f, 150f, key("b"), 700L, folderCandidateKey = null)
        machine.acceptMovePreview(key("b"))
        val moves = mutableListOf<NativeDragEffect.Move>()
        for (step in 1..14) {
            val effects = machine.onMove(0f, 150f - step, key("b"),
                700L + step * 16, folderCandidateKey = null)
            moves += effects.filterIsInstance<NativeDragEffect.Move>()
            if (moves.isNotEmpty()) break
            machine.onMove(0f, 150f - step, key("b"),
                708L + step * 16, folderCandidateKey = null)
        }
        assertEquals(listOf(NativeDragEffect.Move(key("a"), key("b"))), moves)
    }

    @Test
    fun `pending folder holds the target before arming and early release reorders`() {
        val machine = draggingMachine()
        val effects = machine.onMove(0f, 150f, key("b"), 700L)
        assertFalse(effects.any { it is NativeDragEffect.Move })
        assertFalse(effects.any { it is NativeDragEffect.FolderHoverChanged && it.target != null })
        assertTrue(machine.onUp(key("b")).contains(NativeDragEffect.Move(key("a"), key("b"))))
    }

    @Test
    fun `travel outside the jitter allowance restarts folder dwell`() {
        val machine = NativeDragGestureMachine(policy.copy(folderHoverMillis = 300L))
        machine.onDown(key("a"), 0f, 100f, 0L)
        machine.onTime(620L)
        machine.onMove(0f, 121f, key("a"), 621L)
        machine.onMove(0f, 150f, key("b"), 700L)
        machine.onMove(0f, 158f, key("b"), 900L)
        assertFalse(machine.onMove(0f, 158f, key("b"), 1000L)
            .any { it is NativeDragEffect.FolderHoverChanged && it.target != null })
        assertTrue(machine.onMove(0f, 158f, key("b"), 1200L)
            .contains(NativeDragEffect.FolderHoverChanged(key("b"))))
    }

    @Test
    fun `early release over a pending reverse target preserves the reverse reorder`() {
        val machine = draggingMachine()
        machine.onMove(0f, 150f, key("b"), 700L, folderCandidateKey = null)
        machine.acceptMovePreview(key("b"))
        machine.onMove(0f, 137f, key("b"), 750L)
        machine.onMove(0f, 137f, key("b"), 760L, sampleKind = NativeDragSampleKind.HOVER)
        assertTrue(machine.onUp(key("b"))
            .contains(NativeDragEffect.Move(key("a"), key("b"))))
    }

    private fun draggingMachine(): NativeDragGestureMachine =
        NativeDragGestureMachine(policy).apply {
            onDown(key("a"), 0f, 100f, 0L)
            onTime(620L)
            onMove(0f, 121f, key("a"), 621L)
        }

    private fun key(value: String) = AppItemKey(value)
}
