package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「表盘渲染就绪」的静默判定。
 *
 * 老师报的是两件事同一个根因：冷启动重 Lua 表盘「加载中 UI 直接消失、过一会儿
 * 表盘才好」，以及「切换表盘之后还没渲染完也要显示转圈」。根因是加载页只认
 * `watchFaceSelectionReady`，而那个 flow 与渲染进度毫无关系 —— `prepareSource()`
 * 一张位图都不解码。
 */
class WatchmakerRenderReadinessTest {
    private fun readiness(
        faceVisible: Boolean = true,
        sourceReady: Boolean = true,
        transitionFrameVisible: Boolean = false,
        pendingAssetLoads: Int = 0,
        frameHasUnreadyVisualAsset: Boolean = false,
        holdExpired: Boolean = false,
    ) = watchRenderReadiness(
        faceVisible = faceVisible,
        sourceReady = sourceReady,
        transitionFrameVisible = transitionFrameVisible,
        pendingAssetLoads = pendingAssetLoads,
        frameHasUnreadyVisualAsset = frameHasUnreadyVisualAsset,
        holdExpired = holdExpired,
    )

    @Test
    fun `quiescent frame with a ready source is ready`() {
        assertEquals(WatchRenderReadiness.READY, readiness())
    }

    @Test
    fun `structure not ready keeps waiting even if nothing is loading`() {
        // 这一刻画面是纯黑（onDraw 的 prepared == null 早退），撤加载页会露出黑屏。
        assertEquals(
            WatchRenderReadiness.WAITING,
            readiness(sourceReady = false),
        )
        // 结构没好的时候「不可见」也不能抢先报就绪，否则冷启动第一帧就撤了。
        assertEquals(
            WatchRenderReadiness.WAITING,
            readiness(sourceReady = false, faceVisible = false),
        )
    }

    @Test
    fun `invisible face reports ready immediately`() {
        // 不可见时 tick 不跑，可能一帧都不再绘；而且拿加载页盖一块看不见的表盘没意义。
        assertEquals(
            WatchRenderReadiness.READY,
            readiness(faceVisible = false, pendingAssetLoads = 7, frameHasUnreadyVisualAsset = true),
        )
    }

    @Test
    fun `pending decodes wait without asking for an extra frame`() {
        // 每个解码完成都会 invalidate()，下一帧自然会来，不需要自己推帧。
        assertEquals(
            WatchRenderReadiness.WAITING,
            readiness(pendingAssetLoads = 1),
        )
        assertEquals(
            WatchRenderReadiness.WAITING,
            readiness(pendingAssetLoads = 3, frameHasUnreadyVisualAsset = true),
        )
    }

    @Test
    fun `unready asset with no in flight decode must retry next frame`() {
        // 这是「解码额度被上一代的取消请求占满，本帧的请求压根没进队列」那个窗口 ——
        // 没人会来叫醒我们，必须自己 postInvalidateOnAnimation()。
        assertEquals(
            WatchRenderReadiness.RETRY_NEXT_FRAME,
            readiness(frameHasUnreadyVisualAsset = true),
        )
    }

    @Test
    fun `transition frame still covering the new face keeps waiting`() {
        // 过渡帧是**上一块**表盘的快照。它还盖着就说明新表盘没露出来，
        // 这时撤加载页只会让用户看见旧表盘。
        assertEquals(
            WatchRenderReadiness.WAITING,
            readiness(transitionFrameVisible = true),
        )
    }

    @Test
    fun `expired hold reports ready so a broken asset cannot pin the loading page`() {
        // 失败素材进了 failure 冷却表就不再有在飞解码，但「解析得到却永远拿不到」
        // 会让 frameHasUnreadyVisualAsset 一直为真 —— 没有这道闸，一个坏素材能把
        // 加载页顶满整个重试冷却期。
        assertEquals(
            WatchRenderReadiness.READY,
            readiness(frameHasUnreadyVisualAsset = true, holdExpired = true),
        )
        assertEquals(
            WatchRenderReadiness.READY,
            readiness(pendingAssetLoads = 5, holdExpired = true),
        )
        assertEquals(
            WatchRenderReadiness.READY,
            readiness(transitionFrameVisible = true, holdExpired = true),
        )
        // 但保底不能越过「结构还没好」——那一步撤了就是黑屏。
        assertEquals(
            WatchRenderReadiness.WAITING,
            readiness(sourceReady = false, holdExpired = true),
        )
    }

    @Test
    fun `hold window is long enough for local asset decoding but short enough to not feel frozen`() {
        assertEquals(4_000L, WATCH_RENDER_READY_MAX_HOLD_MS)
    }
}
