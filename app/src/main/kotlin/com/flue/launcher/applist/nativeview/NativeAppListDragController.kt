package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import kotlin.math.hypot

internal interface NativeAppListDragHost {
    val viewportHeightPx: Float
    /** Strict visual hit used to acquire a gesture source. */
    fun keyAt(x: Float, y: Float): AppItemKey?
    /** Reorder may use a nearby cell after a strict source has already been acquired. */
    fun reorderKeyAt(x: Float, y: Float): AppItemKey? = keyAt(x, y)
    fun folderKeyAt(x: Float, y: Float): AppItemKey? = keyAt(x, y)
    fun currentKeys(): List<AppItemKey>
    fun planMove(source: AppItemKey, target: AppItemKey): AppListCommand.Move?
    fun planFolder(source: AppItemKey, target: AppItemKey): AppListCommand.CreateFolder?
    fun beginDragPreview(source: AppItemKey): Boolean = false
    fun previewMove(source: AppItemKey, target: AppItemKey): Boolean = false
    fun finishDragPreview(commit: Boolean): AppListCommand.Move? = null
    fun completeDragPreview(accepted: Boolean) = Unit
    fun previewFolderCommit(source: AppItemKey, target: AppItemKey): Boolean = false
    fun previewFolderCommit(
        source: AppItemKey,
        target: AppItemKey,
        folderKey: AppItemKey?,
    ): Boolean = previewFolderCommit(source, target)
    /** Blanks the exact source/target Views before a synchronous owner mutation. */
    fun prepareFolderDispatchVisuals(source: AppItemKey, target: AppItemKey) = Unit
    /** Restores a rejected preparation or permanently releases accepted rows. */
    fun completeFolderDispatchVisuals(accepted: Boolean) = Unit
    /**
     * Dispatches a folder command and returns the authoritative folder key
     * when the command owner can provide it.  The default keeps old hosts and
     * tests source-compatible; the result's folderKey may be null for legacy
     * owners.
     */
    fun dispatchFolder(command: AppListCommand.CreateFolder): NativeFolderDispatchResult =
        NativeFolderDispatchResult(accepted = dispatch(command), folderKey = null)
    fun discardDragPreviewAwaitingAuthoritative() = Unit
    /**
     * Dispatches a command and reports whether the owner accepted it.
     *
     * Folder creation is optimistic while the pointer is still owned by the
     * drag controller.  The boolean lets the controller keep that preview
     * when the repository rejects the command, so the normal move rollback
     * path can restore it exactly once.
     */
    fun dispatch(command: AppListCommand): Boolean
    fun suppressLongClick(key: AppItemKey, suppress: Boolean)
    fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float)
    fun updateDrag(pointerX: Float, pointerY: Float)
    fun endDrag(committed: Boolean)
    fun setFolderHover(key: AppItemKey?)
    fun scrollBy(deltaPx: Float)
    fun performDragHaptic()
}

internal data class NativeFolderDispatchResult(
    val accepted: Boolean,
    val folderKey: AppItemKey?,
)

internal interface NativeDragScheduler {
    val nowMillis: Long
    fun postLongPress(delayMillis: Long, action: () -> Unit)
    fun cancelLongPress()
    fun postFolderHover(delayMillis: Long, action: () -> Unit)
    fun cancelFolderHover()
    fun postFrame(action: () -> Unit)
    fun cancelFrame()
}

/** Bridges Android pointer events to stable-key commands without mutating adapter data. */
internal class NativeAppListDragController(
    private val host: NativeAppListDragHost,
    private val scheduler: NativeDragScheduler,
    private val policyProvider: () -> NativeDragPolicy,
    private val allowFolderCreation: () -> Boolean = { true },
    /**
     * 起拖总闸。覆层（文件夹展开 / 长按菜单）在场时为 false：Compose 覆层
     * 盖在上面，底下的 RecyclerView 仍然收得到长按并把图标拖走。
     */
    private val dragEnabled: () -> Boolean = { true },
) {
    constructor(
        host: NativeAppListDragHost,
        scheduler: NativeDragScheduler,
        policy: NativeDragPolicy,
        allowFolderCreation: () -> Boolean = { true },
        dragEnabled: () -> Boolean = { true },
    ) : this(host, scheduler, { policy }, allowFolderCreation, dragEnabled)

    private val machine = NativeDragGestureMachine()
    private var activePolicy: NativeDragPolicy? = null
    private var suppressedLongClickKey: AppItemKey? = null
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerAtMillis = 0L
    /**
     * 最近一次**真实指针采样**测出的速度。
     *
     * 合成帧（自动滚动帧、folder-hover 到期回调）没有新的指针采样，以前它们往
     * [NativeFolderHoverGate] 里塞的是 `0f` —— 等于谎报「手指已经完全停稳」，
     * `requiredDwellMillis()` 直接掉到下限 300ms。于是自动滚动期间任何划过探针
     * 的图标都会在 300ms 内武装合并，正是老师报的误触。改为复用这一份实测速度：
     * 合成帧不带来新信息，就不该改变 gate 对「停得多稳」的判断。
     */
    private var lastPointerSpeedPxPerMs = 0f
    private var lastAutoScrollFrameAtMillis: Long? = null
    private var frameScheduled = false
    private var folderCommandDispatched = false
    /**
     * Folder creation is deliberately optimistic.  When the policy or the
     * repository rejects it at release time, retain a stable-key move plan so
     * the same gesture can still settle as a normal reorder.
     */
    private var folderFallbackMove: AppListCommand.Move? = null
    private var scheduledFolderTarget: AppItemKey? = null
    private var scheduledFolderDeadlineMillis = Long.MIN_VALUE
    private var released = false

    val isActive: Boolean
        get() = !released && machine.isTracking
    val isDragging: Boolean
        get() = !released && machine.isDragging

    fun onDown(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released) return false
        cancelCurrentGesture()
        // 覆层在场：不登记手势、不排长按，列表这一层彻底不起拖。
        if (!dragEnabled()) return false
        val source = host.keyAt(x, y) ?: return false
        val policy = policyProvider()
        activePolicy = policy
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        // 新手势：还没有任何位移采样。合成帧只可能在 machine.isDragging 之后跑，
        // 那时至少已有一次 onMove 写过真实速度，所以这里的初值观察不到。
        lastPointerSpeedPxPerMs = 0f
        machine.onDown(source, x, y, eventTimeMillis, policy)
        suppressedLongClickKey = source
        host.suppressLongClick(source, true)
        scheduler.postLongPress(policy.longPressMillis) {
            if (!released) process(machine.onTime(scheduler.nowMillis))
        }
        return false
    }

    fun onMove(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released || !machine.isTracking) return false
        val elapsed = (eventTimeMillis - lastPointerAtMillis).coerceAtLeast(1L)
        val speed = hypot(x - lastPointerX, y - lastPointerY) / elapsed
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        lastPointerSpeedPxPerMs = speed
        val reorderTarget = host.reorderKeyAt(x, y)
        val folderCandidate = host.folderKeyAt(x, y).takeIf {
            allowFolderCreation()
        }
        // 排序预览与合成用**空间**消歧，不再靠「开了文件夹就整个关掉预览」。
        //
        // 上一版是 `if (allowFolderCreation()) null else reorderTarget` —— 开关一开，
        // 实时避让位移就全程消失，等于用「不能排序预览」换「两个手势不打架」。
        // 老师现在要两个都要：能自由拖动排序，也能拖到图标上合成。
        //
        // 判据本来就有，只是没被用上：folderKeyAt 带 42% 图标半径的核心区门槛，
        // 出了这个圈返回 null。于是两种意图在空间上天然分开 ——
        //
        //   指针落在图标间隙 / 外圈  → folderCandidate == null → 播避让预览
        //   指针落在某个图标核心区  → folderCandidate != null → 让位给合成高亮
        //
        // 这也正是成熟桌面（Launcher3 的 hit-region + alarm）的分法。
        //
        // 在核心区里把预览目标掐成 null 是**冻结**而不是回弹：状态机对 null 目标
        // 什么都不做（不发 Move 效果、lastMoveTarget 不变），所以位移停在上一帧，
        // 手指移出核心区后接着往下走，不会出现「一进图标中心整列弹回去」。
        val previewTarget = if (folderCandidate != null) null else reorderTarget
        val effects = machine.onMove(
            pointerX = x,
            pointerY = y,
            targetKey = previewTarget,
            speedPxPerMs = speed,
            nowMillis = eventTimeMillis,
            folderCandidateKey = folderCandidate,
        )
        process(effects)
        if (!machine.isTracking) {
            scheduler.cancelLongPress()
            restoreLongClick()
            return false
        }
        if (machine.isDragging) {
            host.updateDrag(x, y)
            updateFolderHoverSchedule(folderCandidate, speed, eventTimeMillis)
            updateAutoScrollSchedule()
        }
        return machine.isDragging || machine.hasLongPressed
    }

    fun onUp(x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (released || !machine.isTracking) return false
        lastPointerX = x
        lastPointerY = y
        lastPointerAtMillis = eventTimeMillis
        val consumed = machine.isDragging || machine.hasLongPressed
        val sampledFolderTarget = host.folderKeyAt(x, y).takeIf {
            allowFolderCreation()
        }
        val folderTarget = sampledFolderTarget.takeIf { it == machine.folderHoverTarget }
        val reorderTarget = host.reorderKeyAt(x, y)
        process(
            machine.onUp(
                currentFolderTargetKey = folderTarget,
                currentReorderTargetKey = reorderTarget,
            ),
        )
        cancelCallbacks()
        restoreLongClick()
        return consumed
    }

    fun onCancel(): Boolean {
        if (released || !machine.isTracking) return false
        val consumed = machine.isDragging || machine.hasLongPressed
        cancelCurrentGesture()
        return consumed
    }

    /**
     * Cancels the gesture for a reason outside the pointer stream (geometry or
     * presentation change, scene turning inactive). Unlike [onCancel] it keeps
     * the source row's long-click suppressed: the finger may still be down and
     * restoring long-click before the row's CheckForTap arms would let the
     * row's own long-press fire at stale coordinates. The real terminal sample
     * restores it through [finishAdditionalPointer] in the dispatch funnel.
     */
    fun invalidateForExternalChange() {
        if (released || !machine.isTracking) return
        process(machine.cancel())
        cancelCallbacks()
    }

    /**
     * Cancels a gesture after a second pointer arrives, but keeps the source
     * row's long-click suppressed until the real terminal event. Otherwise
     * Android's child View can fire its own long-click a few hundred ms later.
     */
    fun onAdditionalPointer(): Boolean {
        if (released || !machine.isTracking) return false
        val consumed = machine.isDragging || machine.hasLongPressed
        process(machine.cancel())
        cancelCallbacks()
        return consumed
    }

    fun finishAdditionalPointer() {
        restoreLongClick()
    }

    fun reconcileCurrentKeys() {
        if (released) return
        process(machine.reconcileCurrentKeys(host.currentKeys()))
        if (!machine.isTracking) {
            cancelCallbacks()
            restoreLongClick()
        }
    }

    fun release() {
        if (released) return
        cancelCurrentGesture()
        released = true
    }

    private fun process(effects: List<NativeDragEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is NativeDragEffect.ShowActions ->
                    {
                        host.performDragHaptic()
                        // The result is deliberately ignored: a host that
                        // suppresses the menu must not kill the gesture — the
                        // long-press still owns it and a following move starts
                        // a drag. The cross-gesture lock-up lived in the touch
                        // funnel (terminal-latched dragTouchOwned), not here.
                        host.dispatch(AppListCommand.ShowActions(effect.key))
                    }
                NativeDragEffect.DismissActions ->
                    host.dispatch(AppListCommand.DismissActions)
                is NativeDragEffect.StartDrag -> {
                    folderCommandDispatched = false
                    folderFallbackMove = null
                    if (!host.beginDragPreview(effect.key)) {
                        // The adapter snapshot changed after the long press.
                        // Never create an overlay without an owned stable-key
                        // preview; cancel the machine and restore input state.
                        process(machine.cancel())
                        return@forEach
                    }
                    host.performDragHaptic()
                    host.beginDrag(effect.key, lastPointerX, lastPointerY)
                }
                is NativeDragEffect.Move ->
                    host.previewMove(effect.source, effect.target)
                is NativeDragEffect.FolderHoverChanged ->
                    host.setFolderHover(effect.target.takeIf { allowFolderCreation() })
                is NativeDragEffect.CreateFolder ->
                    if (allowFolderCreation()) {
                        val command = host.planFolder(effect.source, effect.target)
                        if (command != null) {
                            host.prepareFolderDispatchVisuals(effect.source, effect.target)
                            val result = try {
                                host.dispatchFolder(command)
                            } catch (error: Throwable) {
                                host.completeFolderDispatchVisuals(accepted = false)
                                throw error
                            }
                            host.completeFolderDispatchVisuals(result.accepted)
                            if (result.accepted) {
                                host.previewFolderCommit(
                                    effect.source,
                                    effect.target,
                                    result.folderKey,
                                )
                                folderCommandDispatched = true
                            } else {
                                // The UI/repository rejected the folder
                                // request.  The target remains a valid
                                // reorder destination. Restore exactly one
                                // stable-key move preview so CommitDrag can
                                // settle the same gesture normally.
                                restoreFolderTargetAsMove(effect.source, effect.target)
                            }
                        } else {
                            // A target rejected by the existing folder policy
                            // remains a valid reorder destination.
                            restoreFolderTargetAsMove(effect.source, effect.target)
                        }
                    }
                NativeDragEffect.CommitDrag -> {
                    if (folderCommandDispatched) {
                        // The repository command is now authoritative. Keep
                        // the currently presented order until its new snapshot
                        // arrives instead of submitting the captured original
                        // rows and producing a reverse Diff animation.
                        host.discardDragPreviewAwaitingAuthoritative()
                        host.endDrag(committed = true)
                    } else {
                        val move = host.finishDragPreview(commit = true) ?: folderFallbackMove
                        val committed = move?.let(host::dispatch) == true
                        host.completeDragPreview(committed)
                        host.endDrag(committed = committed)
                    }
                    folderCommandDispatched = false
                    folderFallbackMove = null
                }
                NativeDragEffect.FinishDrag -> {
                    host.finishDragPreview(commit = false)
                    host.endDrag(committed = false)
                    folderCommandDispatched = false
                    folderFallbackMove = null
                }
            }
        }
        if (!machine.isTracking) activePolicy = null
    }

    private fun restoreFolderTargetAsMove(source: AppItemKey, target: AppItemKey) {
        // The virtual preview can fail if a concurrent adapter update removed
        // either row. Keep a separately planned command as a safe fallback;
        // both representations are keyed by stable IDs rather than positions.
        folderFallbackMove = host.planMove(source, target)
        host.previewMove(source, target)
    }

    private fun updateAutoScrollSchedule() {
        val policy = activePolicy ?: return
        val delta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (delta == 0f) {
            cancelFrame()
        } else if (!frameScheduled) {
            if (lastAutoScrollFrameAtMillis == null) {
                lastAutoScrollFrameAtMillis = lastPointerAtMillis
            }
            frameScheduled = true
            scheduler.postFrame(::onAutoScrollFrame)
        }
    }

    private fun updateFolderHoverSchedule(
        candidate: AppItemKey?,
        speedPxPerMs: Float,
        eventTimeMillis: Long,
    ) {
        val policy = activePolicy ?: return
        val eligible = candidate?.takeIf {
            allowFolderCreation() &&
                speedPxPerMs.isFinite() &&
                speedPxPerMs <= policy.folderHoverMaxSpeedPxPerMs
        }
        if (machine.folderHoverTarget == eligible) {
            cancelFolderHoverSchedule()
            return
        }
        if (eligible == scheduledFolderTarget) return
        cancelFolderHoverSchedule()
        if (eligible == null) return

        scheduledFolderTarget = eligible
        // 到期时刻与 gate 的门槛同源（见 nativeFolderDwellPollMillis 的注释），
        // 不再在这里单独读一遍 policy.folderHoverMillis。
        scheduledFolderDeadlineMillis = maxOf(
            eventTimeMillis,
            scheduler.nowMillis,
        ) + nativeFolderDwellPollMillis(policy)
        val delay = (scheduledFolderDeadlineMillis - scheduler.nowMillis).coerceAtLeast(0L)
        scheduler.postFolderHover(delay) {
            val expectedTarget = scheduledFolderTarget
            val deadline = scheduledFolderDeadlineMillis
            scheduledFolderTarget = null
            scheduledFolderDeadlineMillis = Long.MIN_VALUE
            if (
                released ||
                !machine.isDragging ||
                expectedTarget == null ||
                !allowFolderCreation()
            ) {
                return@postFolderHover
            }
            val currentTarget = host.folderKeyAt(lastPointerX, lastPointerY)
            val now = maxOf(scheduler.nowMillis, deadline)
            process(
                machine.onMove(
                    pointerX = lastPointerX,
                    pointerY = lastPointerY,
                    // 与 onMove 的 :165 同一条写法：**空间**消歧，而不是
                    // 「开了文件夹就整个关掉预览」。旧写法
                    // `if (allowFolderCreation()) null else …` 会在开关一开时
                    // 让实时避让位移全程消失 —— 老师报的「拖动到图标上面不避让」。
                    targetKey = if (currentTarget != null) {
                        null
                    } else {
                        host.reorderKeyAt(lastPointerX, lastPointerY)
                    },
                    // 合成帧不谎报「已停稳」，复用最近一次真实采样的速度。
                    speedPxPerMs = lastPointerSpeedPxPerMs,
                    nowMillis = now,
                    folderCandidateKey = currentTarget.takeIf { it == expectedTarget },
                ),
            )
        }
    }

    private fun cancelFolderHoverSchedule() {
        if (scheduledFolderTarget == null) return
        scheduler.cancelFolderHover()
        scheduledFolderTarget = null
        scheduledFolderDeadlineMillis = Long.MIN_VALUE
    }

    private fun onAutoScrollFrame() {
        frameScheduled = false
        if (released || !machine.isDragging) return
        val policy = activePolicy ?: return
        val baseDelta = nativeAutoScrollDelta(lastPointerY, host.viewportHeightPx, policy)
        if (baseDelta == 0f) {
            lastAutoScrollFrameAtMillis = null
            return
        }
        val nowMillis = scheduler.nowMillis
        val previousFrameMillis = lastAutoScrollFrameAtMillis
        val elapsedMillis = if (
            previousFrameMillis == null || nowMillis <= previousFrameMillis
        ) {
            AUTO_SCROLL_REFERENCE_FRAME_MILLIS
        } else {
            (nowMillis - previousFrameMillis)
                .toFloat()
                .coerceIn(MIN_AUTO_SCROLL_FRAME_MILLIS, MAX_AUTO_SCROLL_FRAME_MILLIS)
        }
        lastAutoScrollFrameAtMillis = nowMillis
        val delta = baseDelta * (elapsedMillis / AUTO_SCROLL_REFERENCE_FRAME_MILLIS)
        host.scrollBy(delta)
        host.updateDrag(lastPointerX, lastPointerY)
        val folderCandidate = host.folderKeyAt(lastPointerX, lastPointerY).takeIf {
            allowFolderCreation()
        }
        process(
            machine.onMove(
                pointerX = lastPointerX,
                pointerY = lastPointerY,
                // 与 onMove 的 :165 同一条写法。旧写法
                // `if (allowFolderCreation()) null else …` 在这里危害最大：自动滚动
                // 边带是 `viewportHeight * 0.16f`（454px 屏上下各约 73px），指针一进
                // 边带这一帧就每帧跑一次，于是**只要在边带里拖，避让就全程消失** ——
                // 老师报的「拖动到图标上面不避让」。
                targetKey = if (folderCandidate != null) {
                    null
                } else {
                    host.reorderKeyAt(lastPointerX, lastPointerY)
                },
                // 合成帧不谎报「已停稳」（以前是 0f，把 requiredDwellMillis 压到下限
                // 300ms，自动滚动期间划过的任何图标都会武装合并）。
                speedPxPerMs = lastPointerSpeedPxPerMs,
                nowMillis = scheduler.nowMillis,
                folderCandidateKey = folderCandidate,
            ),
        )
        updateFolderHoverSchedule(
            candidate = folderCandidate,
            speedPxPerMs = lastPointerSpeedPxPerMs,
            eventTimeMillis = scheduler.nowMillis,
        )
        updateAutoScrollSchedule()
    }

    private fun cancelCurrentGesture() {
        process(machine.cancel())
        cancelCallbacks()
        restoreLongClick()
    }

    private fun cancelCallbacks() {
        scheduler.cancelLongPress()
        scheduler.cancelFolderHover()
        scheduledFolderTarget = null
        scheduledFolderDeadlineMillis = Long.MIN_VALUE
        cancelFrame()
    }

    private fun cancelFrame() {
        lastAutoScrollFrameAtMillis = null
        if (frameScheduled) {
            frameScheduled = false
            scheduler.cancelFrame()
        }
    }

    private fun restoreLongClick() {
        suppressedLongClickKey?.let { host.suppressLongClick(it, false) }
        suppressedLongClickKey = null
    }

    private companion object {
        private const val AUTO_SCROLL_REFERENCE_FRAME_MILLIS = 1000f / 60f
        private const val MIN_AUTO_SCROLL_FRAME_MILLIS = 1f
        private const val MAX_AUTO_SCROLL_FRAME_MILLIS = 50f
    }

}
