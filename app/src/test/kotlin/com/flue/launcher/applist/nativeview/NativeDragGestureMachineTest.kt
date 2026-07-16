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
        folderHoverMaxSpeedPxPerMs = 0.55f,
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
                speedPxPerMs = 0.1f,
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
            machine.onMove(0f, 21f, key("b"), 0.5f, 100L),
        )
        assertEquals(emptyList<NativeDragEffect>(), machine.onTime(700L))
        assertFalse(machine.isTracking)
    }

    @Test
    fun `drag emits reorder only when stable target changes`() {
        val machine = draggingMachine()

        assertEquals(
            listOf(NativeDragEffect.Move(key("a"), key("b"))),
            machine.onMove(0f, 130f, key("b"), 1f, 700L),
        )
        assertEquals(
            emptyList<NativeDragEffect>(),
            machine.onMove(0f, 132f, key("b"), 1f, 710L),
        )
        assertEquals(
            listOf(NativeDragEffect.Move(key("a"), key("c"))),
            machine.onMove(0f, 180f, key("c"), 1f, 720L),
        )
    }

    @Test
    fun `slow same target arms folder and release revalidates key`() {
        val machine = draggingMachine()

        machine.onMove(0f, 130f, key("b"), 0.2f, 700L)
        assertEquals(
            listOf(NativeDragEffect.FolderHoverChanged(key("b"))),
            machine.onMove(0f, 131f, key("b"), 0.2f, 1200L),
        )
        assertEquals(
            listOf(
                NativeDragEffect.CreateFolder(key("a"), key("b")),
                NativeDragEffect.FolderHoverChanged(null),
                NativeDragEffect.FinishDrag,
            ),
            machine.onUp(currentTargetKey = key("b")),
        )

        val changedTarget = draggingMachine()
        changedTarget.onMove(0f, 130f, key("b"), 0.2f, 700L)
        changedTarget.onMove(0f, 131f, key("b"), 0.2f, 1200L)
        assertEquals(
            listOf(
                NativeDragEffect.FolderHoverChanged(null),
                NativeDragEffect.FinishDrag,
            ),
            changedTarget.onUp(currentTargetKey = key("c")),
        )
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
        machine.onMove(0f, 130f, key("b"), 0.2f, 700L)
        machine.onMove(0f, 131f, key("b"), 0.2f, 1200L)

        assertEquals(
            listOf(
                NativeDragEffect.FolderHoverChanged(null),
                NativeDragEffect.FinishDrag,
            ),
            machine.cancel(),
        )
        assertEquals(emptyList<NativeDragEffect>(), machine.cancel())
    }

    private fun draggingMachine(): NativeDragGestureMachine =
        NativeDragGestureMachine(policy).apply {
            onDown(key("a"), 0f, 100f, 0L)
            onTime(620L)
            onMove(0f, 121f, key("a"), 0.1f, 621L)
        }

    private fun key(value: String) = AppItemKey(value)
}
