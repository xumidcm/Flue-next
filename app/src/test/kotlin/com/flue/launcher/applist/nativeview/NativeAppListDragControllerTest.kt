package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppListDragControllerTest {
    private val policy = NativeDragPolicy(
        startThresholdPx = 20f,
        autoScrollEdgePx = 84f,
        maxAutoScrollPxPerFrame = 30f,
        folderHoverMillis = 500L,
        folderHoverMaxSpeedPxPerMs = 0.55f,
        longPressMillis = 620L,
    )

    @Test
    fun `scheduled long press owns menu and drag emits stable key command`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)

        assertFalse(controller.onDown(10f, 100f, 0L))
        assertTrue(host.longClickSuppressed)
        scheduler.advanceTo(620L)
        assertEquals(listOf(AppListCommand.ShowActions(key("a"))), host.commands)

        assertTrue(controller.onMove(10f, 121f, 621L))
        assertEquals(key("a"), host.draggingKey)
        assertEquals(AppListCommand.DismissActions, host.commands.last())

        host.hitKey = key("b")
        assertTrue(controller.onMove(10f, 150f, 700L))
        assertEquals(
            AppListCommand.Move(key("a"), beforeKey = key("c")),
            host.commands.last(),
        )
    }

    @Test
    fun `edge auto scroll repeats by frame and stops on release`() {
        val host = FakeDragHost().apply { hitKey = key("a") }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        controller.onMove(10f, 398f, 700L)
        assertTrue(scheduler.hasFrame)
        scheduler.runFrame()
        assertTrue(host.scrollDeltas.single() > 0f)
        assertTrue(scheduler.hasFrame)

        assertTrue(controller.onUp(10f, 398f, 720L))
        assertFalse(scheduler.hasFrame)
        assertEquals(null, host.draggingKey)
        assertFalse(host.longClickSuppressed)
    }

    @Test
    fun `folder creation is revalidated against latest pointer target`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        controller.onMove(10f, 151f, 1200L)
        assertEquals(key("b"), host.folderHoverKey)

        host.hitKey = key("c")
        controller.onUp(10f, 151f, 1201L)
        assertFalse(host.commands.any { it is AppListCommand.CreateFolder })
        assertEquals(null, host.folderHoverKey)
    }

    @Test
    fun `snapshot source removal and release cancel callbacks once`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.keys = listOf(key("b"), key("c"))
        controller.reconcileCurrentKeys()
        controller.release()
        controller.release()

        assertEquals(1, host.endDragCount)
        assertFalse(scheduler.hasDelayed)
        assertFalse(scheduler.hasFrame)
        assertFalse(controller.isActive)
    }

    private fun startDrag(
        controller: NativeAppListDragController,
        scheduler: FakeDragScheduler,
    ) {
        controller.onDown(10f, 100f, 0L)
        scheduler.advanceTo(620L)
        controller.onMove(10f, 121f, 621L)
    }

    private fun key(value: String) = AppItemKey(value)

    private class FakeDragHost : NativeAppListDragHost {
        var keys = listOf(AppItemKey("a"), AppItemKey("b"), AppItemKey("c"))
        var hitKey: AppItemKey? = AppItemKey("a")
        val commands = mutableListOf<AppListCommand>()
        val scrollDeltas = mutableListOf<Float>()
        var draggingKey: AppItemKey? = null
        var folderHoverKey: AppItemKey? = null
        var longClickSuppressed = false
        var endDragCount = 0

        override val viewportHeightPx: Float = 400f
        override fun keyAt(x: Float, y: Float): AppItemKey? = hitKey
        override fun currentKeys(): List<AppItemKey> = keys
        override fun dispatch(command: AppListCommand) { commands += command }
        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            longClickSuppressed = suppress
        }
        override fun beginDrag(key: AppItemKey, pointerY: Float) { draggingKey = key }
        override fun updateDrag(pointerY: Float) = Unit
        override fun endDrag() { draggingKey = null; endDragCount++ }
        override fun setFolderHover(key: AppItemKey?) { folderHoverKey = key }
        override fun scrollBy(deltaPx: Float) { scrollDeltas += deltaPx }
    }

    private class FakeDragScheduler : NativeDragScheduler {
        private var now = 0L
        private var delayed: (() -> Unit)? = null
        private var delayedAt = 0L
        private var frame: (() -> Unit)? = null

        override val nowMillis: Long get() = now
        val hasDelayed: Boolean get() = delayed != null
        val hasFrame: Boolean get() = frame != null

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            delayed = action
            delayedAt = now + delayMillis
        }
        override fun cancelDelayed() { delayed = null }
        override fun postFrame(action: () -> Unit) { frame = action }
        override fun cancelFrame() { frame = null }

        fun advanceTo(value: Long) {
            now = value
            if (value >= delayedAt) delayed.also { delayed = null }?.invoke()
        }
        fun runFrame() {
            frame.also { frame = null }?.invoke()
        }
    }
}
