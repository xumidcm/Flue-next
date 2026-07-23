package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListMovePlanner
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
    fun `scheduled long press owns menu and release emits stable key command`() {
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
        assertEquals(2, host.hapticCount)

        host.hitKey = key("b")
        assertTrue(controller.onMove(40f, 150f, 700L))
        assertEquals(40f, host.dragPointerX, 0f)
        assertFalse(host.commands.any { it is AppListCommand.Move })

        assertTrue(controller.onUp(40f, 150f, 701L))
        assertEquals(
            AppListCommand.Move(key("a"), beforeKey = key("c")),
            host.commands.last(),
        )
        assertEquals(1, host.commands.count { it is AppListCommand.Move })
    }

    @Test
    fun `changing drag target does not materialize the complete current key list`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)
        host.hitKey = key("b")
        host.currentKeysReadCount = 0

        controller.onMove(40f, 150f, 700L)

        assertEquals(0, host.currentKeysReadCount)
        assertFalse(host.commands.any { it is AppListCommand.Move })
        controller.onUp(40f, 150f, 701L)
        assertEquals(
            AppListCommand.Move(key("a"), beforeKey = key("c")),
            host.commands.last(),
        )
    }

    @Test
    fun `multiple hover targets preview locally and release submits one final move`() {
        val host = FakeDragHost().apply {
            keys = listOf(key("a"), key("b"), key("c"), key("d"))
        }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        host.hitKey = key("d")
        controller.onMove(10f, 220f, 710L)
        host.hitKey = key("c")
        controller.onMove(10f, 180f, 720L)

        assertEquals(3, host.previewMoveCount)
        assertFalse(host.commands.any { it is AppListCommand.Move })

        controller.onUp(10f, 150f, 721L)

        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            host.commands.filterIsInstance<AppListCommand.Move>(),
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
    fun `gesture freezes policy selected on down and next down reads the new profile`() {
        val host = FakeDragHost().apply { hitKey = key("a") }
        val scheduler = FakeDragScheduler()
        var selectedPolicy = policy
        var policyReads = 0
        val spatialPolicy = policy.copy(
            autoScrollEdgePx = 96f,
            maxAutoScrollPxPerFrame = 26f,
            folderHoverMillis = 700L,
        )
        val controller = NativeAppListDragController(
            host = host,
            scheduler = scheduler,
            policyProvider = {
                policyReads++
                selectedPolicy
            },
        )
        startDrag(controller, scheduler)

        selectedPolicy = spatialPolicy
        controller.onMove(10f, 400f, 700L)
        scheduler.runFrame()
        assertEquals(30f, host.scrollDeltas.last(), 0f)
        assertEquals(1, policyReads)
        controller.onUp(10f, 400f, 720L)

        controller.onDown(10f, 100f, 1_000L)
        scheduler.advanceTo(1_620L)
        controller.onMove(10f, 121f, 1_621L)
        controller.onMove(10f, 400f, 1_700L)
        scheduler.runFrame()
        assertEquals(26f, host.scrollDeltas.last(), 0f)
        assertEquals(2, policyReads)
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
    fun `gesture source requires a strict hit while reorder may use a nearest target`() {
        val host = FakeDragHost().apply {
            strictHitKey = null
            reorderHitKey = key("b")
        }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)

        assertFalse(controller.onDown(10f, 100f, 0L))
        assertFalse(scheduler.hasDelayed)
        assertFalse(host.longClickSuppressed)

        host.strictHitKey = key("a")
        controller.onDown(10f, 100f, 1L)
        scheduler.advanceTo(621L)
        controller.onMove(10f, 121f, 622L)
        host.strictHitKey = null
        host.reorderHitKey = key("b")

        controller.onMove(40f, 150f, 700L)

        assertFalse(host.commands.any { it is AppListCommand.Move })
        controller.onUp(40f, 150f, 701L)
        assertEquals(
            AppListCommand.Move(key("a"), beforeKey = key("c")),
            host.commands.last(),
        )
    }

    @Test
    fun `folder release requires a strict hit even when reorder keeps a nearest target`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.strictHitKey = key("b")
        host.reorderHitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        controller.onMove(10f, 151f, 1200L)
        assertEquals(key("b"), host.folderHoverKey)

        host.strictHitKey = null
        controller.onUp(10f, 151f, 1201L)

        assertFalse(host.commands.any { it is AppListCommand.CreateFolder })
        assertEquals(null, host.folderHoverKey)
    }

    @Test
    fun `folder hover and creation stay disabled when the current setting is off`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(
            host = host,
            scheduler = scheduler,
            policy = policy,
            allowFolderCreation = { false },
        )
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        controller.onMove(10f, 151f, 1200L)
        controller.onUp(10f, 151f, 1201L)

        assertEquals(null, host.folderHoverKey)
        assertFalse(host.commands.any { it is AppListCommand.CreateFolder })
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
        var strictHitKey: AppItemKey? = AppItemKey("a")
        var reorderHitKey: AppItemKey? = AppItemKey("a")
        var hitKey: AppItemKey?
            get() = strictHitKey
            set(value) {
                strictHitKey = value
                reorderHitKey = value
            }
        val commands = mutableListOf<AppListCommand>()
        val scrollDeltas = mutableListOf<Float>()
        var draggingKey: AppItemKey? = null
        var dragPointerX = 0f
        var folderHoverKey: AppItemKey? = null
        var longClickSuppressed = false
        var endDragCount = 0
        var hapticCount = 0
        var currentKeysReadCount = 0
        var previewMoveCount = 0
        private val dragPreview = NativeStableKeyDragPreview()

        override val viewportHeightPx: Float = 400f
        override fun keyAt(x: Float, y: Float): AppItemKey? = strictHitKey
        override fun reorderKeyAt(x: Float, y: Float): AppItemKey? = reorderHitKey
        override fun folderKeyAt(x: Float, y: Float): AppItemKey? = strictHitKey
        override fun currentKeys(): List<AppItemKey> {
            currentKeysReadCount++
            return keys
        }
        override fun planMove(
            source: AppItemKey,
            target: AppItemKey,
        ): AppListCommand.Move? = AppListMovePlanner.fromKeys(keys, source, target)
        override fun planFolder(
            source: AppItemKey,
            target: AppItemKey,
        ): AppListCommand.CreateFolder? = AppListMovePlanner.folderFromKeys(keys, source, target)
        override fun beginDragPreview(source: AppItemKey): Boolean =
            dragPreview.begin(source, keys)
        override fun previewMove(source: AppItemKey, target: AppItemKey): Boolean {
            previewMoveCount++
            return dragPreview.moveTo(target) != null
        }
        override fun finishDragPreview(commit: Boolean): AppListCommand.Move? =
            dragPreview.finish(commit)
        override fun dispatch(command: AppListCommand) { commands += command }
        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            longClickSuppressed = suppress
        }
        override fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float) {
            draggingKey = key
            dragPointerX = pointerX
        }
        override fun updateDrag(pointerX: Float, pointerY: Float) { dragPointerX = pointerX }
        override fun endDrag() { draggingKey = null; endDragCount++ }
        override fun setFolderHover(key: AppItemKey?) { folderHoverKey = key }
        override fun scrollBy(deltaPx: Float) { scrollDeltas += deltaPx }
        override fun performDragHaptic() { hapticCount++ }
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
