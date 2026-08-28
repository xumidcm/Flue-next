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
        assertEquals(listOf(true), host.completePreviewCalls)
    }

    @Test
    fun `failed preview acquisition cancels before creating a drag overlay`() {
        val host = FakeDragHost().apply { previewBeginAllowed = false }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)

        controller.onDown(10f, 100f, 0L)
        scheduler.advanceTo(620L)
        val consumed = controller.onMove(10f, 121f, 621L)

        assertFalse(consumed)
        assertFalse(controller.isActive)
        assertFalse(controller.isDragging)
        assertEquals(0, host.beginDragCount)
        assertEquals(null, host.draggingKey)
        assertEquals(listOf(false), host.finishPreviewCalls)
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
        // 文件夹功能关闭时仍有实时避让预览。
        val controller = NativeAppListDragController(host, scheduler, policy, allowFolderCreation = { false })
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 630L)
        host.hitKey = key("d")
        controller.onMove(10f, 220f, 640L)
        host.hitKey = key("c")
        controller.onMove(10f, 180f, 650L)

        assertEquals(3, host.previewMoveCount)
        assertFalse(host.commands.any { it is AppListCommand.Move })

        controller.onUp(10f, 150f, 651L)

        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            host.commands.filterIsInstance<AppListCommand.Move>(),
        )
    }

    @Test
    fun `with folder creation enabled moving previews reorder and drop still commits the move`() {
        val host = FakeDragHost().apply {
            keys = listOf(key("a"), key("b"), key("c"), key("d"))
        }
        val scheduler = FakeDragScheduler()
        // 文件夹功能开启时移动中照常播避让预览；只有悬停武装（静止凑满 dwell、
        // 高亮亮起）才冻结。落手时仍提交排序。
        val controller = NativeAppListDragController(host, scheduler, policy, allowFolderCreation = { true })
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 630L)
        host.hitKey = key("d")
        controller.onMove(10f, 220f, 640L)
        host.hitKey = key("c")
        controller.onMove(10f, 180f, 650L)

        // 三次快速掠过都超速，dwell 从未武装 → 每个目标都正常播避让。
        assertEquals(3, host.previewMoveCount)
        assertFalse(host.commands.any { it is AppListCommand.Move })

        controller.onUp(10f, 150f, 651L)

        // 有预览时虚拟列表随避让变动，落到 c 的位置等于插到 c 之前。
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

        host.hitKey = key("b")
        controller.onMove(10f, 398f, 700L)
        assertTrue(scheduler.hasFrame)
        scheduler.runFrame()
        assertTrue(host.scrollDeltas.single() > 0f)
        assertTrue(scheduler.hasFrame)
        // 自动滚动这帧也播避让：快速进入边带超速、dwell 未武装，冻结不再适用。
        assertEquals(1, host.previewMoveCount)

        assertTrue(controller.onUp(10f, 398f, 720L))
        assertFalse(scheduler.hasFrame)
        assertEquals(null, host.draggingKey)
        assertFalse(host.longClickSuppressed)
    }

    @Test
    fun `auto scroll keeps reorder preview active outside the folder core`() {
        // 起拖必须先拿到严格命中的源（onDown 用 keyAt），所以 strictHitKey 在 onDown
        // 时不能是 null；拖起来之后再把它清掉，模拟「指针落在图标核心区之外」。
        val host = FakeDragHost().apply { hitKey = key("a") }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(
            host = host,
            scheduler = scheduler,
            policy = policy,
            allowFolderCreation = { true },
        )
        startDrag(controller, scheduler)
        host.strictHitKey = null
        host.reorderHitKey = key("b")

        controller.onMove(10f, 50f, 800L)
        assertEquals(1, host.previewMoveCount)

        host.reorderHitKey = key("c")
        scheduler.advanceTo(816L)
        scheduler.runFrame()

        assertEquals(2, host.previewMoveCount)
    }

    @Test
    fun `auto scroll frames do not turn a moving pointer sample into a settled folder dwell`() {
        val host = FakeDragHost().apply { hitKey = key("a") }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 50f, 800L)
        assertEquals(null, host.folderHoverKey)

        scheduler.advanceTo(1_100L)
        scheduler.runFrame()

        assertEquals(null, host.folderHoverKey)
    }

    @Test
    fun `drag auto scroll distance is stable across different frame intervals`() {
        fun scrollDistance(frameTimes: List<Long>): Float {
            val host = FakeDragHost().apply { hitKey = key("a") }
            val scheduler = FakeDragScheduler()
            val controller = NativeAppListDragController(host, scheduler, policy)
            startDrag(controller, scheduler)
            controller.onMove(10f, 398f, 700L)
            frameTimes.forEach { frameTime ->
                scheduler.advanceTo(frameTime)
                scheduler.runFrame()
            }
            controller.onCancel()
            return host.scrollDeltas.sum()
        }

        val oneSixtyHertzFrame = scrollDistance(listOf(716L))
        val twoOneTwentyHertzFrames = scrollDistance(listOf(708L, 716L))

        assertEquals(oneSixtyHertzFrame, twoOneTwentyHertzFrames, 0.01f)
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
    fun `stationary icon center dwell arms folder without another move event`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        scheduler.advanceTo(1_199L)
        assertEquals(null, host.folderHoverKey)

        scheduler.advanceTo(1_200L)
        assertEquals(key("b"), host.folderHoverKey)
        // 悬停期间（武装前）移动中避让照常播过一次；武装后预览冻结、计数不再涨。
        assertEquals(1, host.previewMoveCount)
        controller.onUp(10f, 150f, 1_201L)

        assertEquals(
            listOf(AppListCommand.CreateFolder(key("a"), key("b"))),
            structuralCommands(host),
        )
        assertEquals(key("a") to key("b"), host.folderCommitPreview)
        assertEquals(1, host.discardWithoutRestoreCount)
        assertTrue(host.finishPreviewCalls.isEmpty())
    }

    @Test
    fun `accepted folder dispatch forwards authoritative stable key to settle preview`() {
        val authoritativeFolderKey = key("folder:new")
        val host = FakeDragHost().apply {
            dispatchedFolderKey = authoritativeFolderKey
        }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        scheduler.advanceTo(1_200L)
        controller.onUp(10f, 150f, 1_201L)

        assertEquals(authoritativeFolderKey, host.folderCommitDestinationKey)
        assertEquals(1, host.discardWithoutRestoreCount)
        assertEquals(
            listOf("prepare", "dispatch", "complete:true"),
            host.folderDispatchVisualEvents,
        )
    }

    @Test
    fun `starting folder dwell does not cancel pending long press`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)

        controller.onDown(10f, 100f, 0L)
        host.hitKey = key("b")
        controller.onMove(10f, 101f, 100L)

        scheduler.advanceTo(policy.longPressMillis)

        assertTrue(host.commands.any { it is AppListCommand.ShowActions })
    }

    @Test
    fun `release before hover callback does not synthesize a folder dwell`() {
        val host = FakeDragHost()
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        controller.onUp(10f, 150f, 1_200L)

        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            structuralCommands(host),
        )
        assertFalse(scheduler.hasDelayed)
    }

    @Test
    fun `all native presentations create folders with their frozen drag policy`() {
        val profiles = NativeDragPolicyProfiles(density = 1f)
        listOf(
            NativeAppListPresentation.LINEAR,
            NativeAppListPresentation.HONEYCOMB,
            NativeAppListPresentation.DENSE_GRID,
        ).forEach { presentation ->
            val presentationPolicy = profiles.forPresentation(presentation)
            val host = FakeDragHost()
            val scheduler = FakeDragScheduler()
            val controller = NativeAppListDragController(host, scheduler, presentationPolicy)
            startDrag(controller, scheduler)

            host.hitKey = key("b")
            controller.onMove(10f, 150f, 700L)
            scheduler.advanceTo(700L + presentationPolicy.folderHoverMillis)
            controller.onUp(10f, 150f, 701L + presentationPolicy.folderHoverMillis)

            assertEquals(
                presentation.name,
                listOf(AppListCommand.CreateFolder(key("a"), key("b"))),
                structuralCommands(host),
            )
        }
    }

    @Test
    fun `existing folder is a valid stable key folder target`() {
        val host = FakeDragHost().apply {
            keys = listOf(key("a"), key("folder"), key("c"))
        }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("folder")
        controller.onMove(10f, 150f, 700L)
        scheduler.advanceTo(1_200L)
        controller.onUp(10f, 150f, 1_201L)

        assertEquals(
            listOf(AppListCommand.CreateFolder(key("a"), key("folder"))),
            structuralCommands(host),
        )
    }

    @Test
    fun `folder policy rejection falls back to exactly one stable key move`() {
        val host = FakeDragHost().apply { folderPlanAllowed = false }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        scheduler.advanceTo(1_200L)
        controller.onUp(10f, 150f, 1_201L)

        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            structuralCommands(host),
        )
    }

    @Test
    fun `repository rejection of a planned folder restores one move preview`() {
        val host = FakeDragHost().apply { rejectFolderCommands = true }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        scheduler.advanceTo(1_200L)
        controller.onUp(10f, 150f, 1_201L)

        assertEquals(
            listOf(
                AppListCommand.CreateFolder(key("a"), key("b")),
                AppListCommand.Move(key("a"), beforeKey = key("c")),
            ),
            host.dispatchAttempts.filter {
                it is AppListCommand.Move || it is AppListCommand.CreateFolder
            },
        )
        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            structuralCommands(host),
        )
        assertEquals(0, host.discardWithoutRestoreCount)
        assertEquals(null, host.folderCommitPreview)
        assertEquals(listOf(true), host.finishPreviewCalls)
        assertEquals(listOf(true), host.completePreviewCalls)
    }

    @Test
    fun `repository rejection of a normal move rolls back virtual preview without a second command`() {
        val host = FakeDragHost().apply { rejectMoveCommands = true }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.hitKey = key("b")
        controller.onMove(10f, 150f, 700L)
        controller.onUp(10f, 150f, 701L)

        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            host.dispatchAttempts.filterIsInstance<AppListCommand.Move>(),
        )
        assertTrue(host.commands.none { it is AppListCommand.Move })
        assertEquals(listOf(false), host.completePreviewCalls)
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
        assertEquals(
            listOf(AppListCommand.Move(key("a"), beforeKey = key("c"))),
            structuralCommands(host),
        )
    }

    @Test
    fun `a list containing folders remains sortable`() {
        val host = FakeDragHost().apply {
            keys = listOf(key("folder"), key("a"), key("b"), key("c"))
            hitKey = key("folder")
        }
        val scheduler = FakeDragScheduler()
        val controller = NativeAppListDragController(host, scheduler, policy)
        startDrag(controller, scheduler)

        host.strictHitKey = null
        host.reorderHitKey = key("b")
        controller.onMove(10f, 180f, 700L)
        controller.onUp(10f, 180f, 701L)

        assertEquals(
            listOf(AppListCommand.Move(key("folder"), beforeKey = key("c"))),
            structuralCommands(host),
        )
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

    private fun structuralCommands(host: FakeDragHost): List<AppListCommand> =
        host.commands.filter {
            it is AppListCommand.Move || it is AppListCommand.CreateFolder
        }

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
        val dispatchAttempts = mutableListOf<AppListCommand>()
        val scrollDeltas = mutableListOf<Float>()
        var draggingKey: AppItemKey? = null
        var dragPointerX = 0f
        var folderHoverKey: AppItemKey? = null
        var longClickSuppressed = false
        var endDragCount = 0
        var hapticCount = 0
        var currentKeysReadCount = 0
        var previewMoveCount = 0
        var folderPlanAllowed = true
        var previewBeginAllowed = true
        var beginDragCount = 0
        var discardWithoutRestoreCount = 0
        var rejectFolderCommands = false
        var rejectMoveCommands = false
        var folderCommitPreview: Pair<AppItemKey, AppItemKey>? = null
        var dispatchedFolderKey: AppItemKey? = null
        var folderCommitDestinationKey: AppItemKey? = null
        val folderDispatchVisualEvents = mutableListOf<String>()
        val finishPreviewCalls = mutableListOf<Boolean>()
        val completePreviewCalls = mutableListOf<Boolean>()
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
        ): AppListCommand.CreateFolder? = if (folderPlanAllowed) {
            AppListMovePlanner.folderFromKeys(keys, source, target)
        } else {
            null
        }
        override fun beginDragPreview(source: AppItemKey): Boolean =
            previewBeginAllowed && dragPreview.begin(source, keys)
        override fun previewMove(source: AppItemKey, target: AppItemKey): Boolean {
            previewMoveCount++
            return dragPreview.moveTo(target) != null
        }
        override fun finishDragPreview(commit: Boolean): AppListCommand.Move? {
            finishPreviewCalls += commit
            return dragPreview.finish(commit)
        }
        override fun completeDragPreview(accepted: Boolean) {
            completePreviewCalls += accepted
        }

        override fun previewFolderCommit(source: AppItemKey, target: AppItemKey): Boolean {
            folderCommitPreview = source to target
            return true
        }
        override fun previewFolderCommit(
            source: AppItemKey,
            target: AppItemKey,
            folderKey: AppItemKey?,
        ): Boolean {
            folderCommitDestinationKey = folderKey
            return previewFolderCommit(source, target)
        }
        override fun prepareFolderDispatchVisuals(source: AppItemKey, target: AppItemKey) {
            folderDispatchVisualEvents += "prepare"
        }
        override fun completeFolderDispatchVisuals(accepted: Boolean) {
            folderDispatchVisualEvents += "complete:$accepted"
        }
        override fun dispatchFolder(
            command: AppListCommand.CreateFolder,
        ): NativeFolderDispatchResult {
            folderDispatchVisualEvents += "dispatch"
            val accepted = dispatch(command)
            return NativeFolderDispatchResult(
                accepted = accepted,
                folderKey = dispatchedFolderKey.takeIf { accepted },
            )
        }
        override fun discardDragPreviewAwaitingAuthoritative() {
            discardWithoutRestoreCount++
            dragPreview.reset()
        }
        override fun dispatch(command: AppListCommand): Boolean {
            dispatchAttempts += command
            val accepted = when (command) {
                is AppListCommand.CreateFolder -> !rejectFolderCommands
                is AppListCommand.Move -> !rejectMoveCommands
                else -> true
            }
            if (accepted) commands += command
            return accepted
        }
        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            longClickSuppressed = suppress
        }
        override fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float) {
            beginDragCount++
            draggingKey = key
            dragPointerX = pointerX
        }
        override fun updateDrag(pointerX: Float, pointerY: Float) { dragPointerX = pointerX }
        override fun endDrag(committed: Boolean) { draggingKey = null; endDragCount++ }
        override fun setFolderHover(key: AppItemKey?) { folderHoverKey = key }
        override fun scrollBy(deltaPx: Float) { scrollDeltas += deltaPx }
        override fun performDragHaptic() { hapticCount++ }
    }

    private class FakeDragScheduler : NativeDragScheduler {
        private var now = 0L
        private var longPress: (() -> Unit)? = null
        private var longPressAt = 0L
        private var folderHover: (() -> Unit)? = null
        private var folderHoverAt = 0L
        private var frame: (() -> Unit)? = null

        override val nowMillis: Long get() = now
        val hasDelayed: Boolean get() = longPress != null || folderHover != null
        val hasFrame: Boolean get() = frame != null

        override fun postLongPress(delayMillis: Long, action: () -> Unit) {
            longPress = action
            longPressAt = now + delayMillis
        }
        override fun cancelLongPress() { longPress = null }
        override fun postFolderHover(delayMillis: Long, action: () -> Unit) {
            folderHover = action
            folderHoverAt = now + delayMillis
        }
        override fun cancelFolderHover() { folderHover = null }
        override fun postFrame(action: () -> Unit) { frame = action }
        override fun cancelFrame() { frame = null }

        fun advanceTo(value: Long) {
            now = value
            if (value >= longPressAt) longPress.also { longPress = null }?.invoke()
            if (value >= folderHoverAt) folderHover.also { folderHover = null }?.invoke()
        }
        fun runFrame() {
            frame.also { frame = null }?.invoke()
        }
    }
}
