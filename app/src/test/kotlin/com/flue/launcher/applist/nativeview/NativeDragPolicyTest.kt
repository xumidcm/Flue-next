package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NativeDragPolicyTest {
    // 具名传参：folderHoverSettledMillis 插在速度门槛前面，位置参数会静默错位
    //（或者像这次一样撞类型错误）。
    private val policy = NativeDragPolicy(
        startThresholdPx = 20f,
        autoScrollEdgePx = 84f,
        maxAutoScrollPxPerFrame = 30f,
        folderHoverMillis = 500L,
        folderHoverSettledMillis = 240L,
        folderHoverMaxSpeedPxPerMs = 0.55f,
    )

    @Test
    fun `presentation maps to exact reusable drag profiles`() {
        val profiles = NativeDragPolicyProfiles(density = 2f)

        val linear = profiles.forPresentation(NativeAppListPresentation.LINEAR)
        val honeycomb = profiles.forPresentation(NativeAppListPresentation.HONEYCOMB)
        val denseGrid = profiles.forPresentation(NativeAppListPresentation.DENSE_GRID)

        assertEquals(
            NativeDragPolicy(
                startThresholdPx = 40f,
                autoScrollEdgePx = 168f,
                maxAutoScrollPxPerFrame = 30f,
                folderHoverMillis = 500L,
                folderHoverSettledMillis = 240L,
                folderHoverMaxSpeedPxPerMs = 0.55f,
                longPressMillis = 620L,
            ),
            linear,
        )
        assertEquals(
            NativeDragPolicy(
                startThresholdPx = 40f,
                autoScrollEdgePx = 192f,
                maxAutoScrollPxPerFrame = 26f,
                folderHoverMillis = 700L,
                folderHoverSettledMillis = 300L,
                folderHoverMaxSpeedPxPerMs = 0.55f,
                longPressMillis = 620L,
            ),
            honeycomb,
        )
        assertSame(honeycomb, denseGrid)
        assertSame(linear, profiles.forPresentation(NativeAppListPresentation.LINEAR))
    }

    @Test
    fun `auto scroll is proportional only inside edge zones`() {
        assertEquals(-30f, nativeAutoScrollDelta(0f, 400f, policy))
        assertEquals(0f, nativeAutoScrollDelta(200f, 400f, policy))
        assertEquals(30f, nativeAutoScrollDelta(400f, 400f, policy))
    }

    /**
     * 候选必须是同一个、且速度在门槛内，停留够久才武装。
     *
     * 这条原来断言的是硬编码的整 500ms（`1499L` 还不行、`1500L` 才行）。停留时长现在
     * 按停稳程度插值，那个数字不再成立 —— 改的正是老师要求去掉的那个硬值，所以这里
     * 跟着改成「按 0.2px/ms 这一档算出来的门槛」，同时保留它本来覆盖的另外两件事：
     * 换目标要重新计时、超速直接失效。
     */
    @Test
    fun `folder hover requires the same slow target and resets on a new one`() {
        val gate = NativeFolderHoverGate(policy)
        // 0.2 / 0.55 ≈ 0.3636 → 240 + (500-240)*0.3636 ≈ 334ms
        assertNull(gate.update("a", 0.2f, 1000L))
        assertNull(gate.update("a", 0.2f, 1333L))
        assertEquals("a", gate.update("a", 0.2f, 1334L))
        // 换目标：重新计时，立刻不再武装。
        assertNull(gate.update("b", 0.2f, 1340L))
        // 超过速度门槛：候选直接作废，停多久都不算。
        assertNull(gate.update("b", 0.8f, 2200L))
    }

    /**
     * 停得越稳，武装越快。
     *
     * 盯的是老师报的「合成的时间可以改得更自然，不需要硬死在 500ms」。同一个 500ms
     * 要同时服务两种意图完全不同的动作：手指已经停在图标正中（明摆着要合成却干等
     * 半秒），和手指正慢慢划过（这时立刻合成就是误触）。现在按「本次悬停最慢采样」
     * 在 folderHoverSettledMillis..folderHoverMillis 之间插值。
     */
    @Test
    fun `a settled pointer arms sooner than a drifting one`() {
        // 完全停住：取下限 240ms。
        val settled = NativeFolderHoverGate(policy)
        assertNull(settled.update("a", 0f, 1000L))
        assertNull(settled.update("a", 0f, 1239L))
        assertEquals("a", settled.update("a", 0f, 1240L))

        // 一直贴着速度门槛漂移：取上限 500ms，与改动前一致。
        val drifting = NativeFolderHoverGate(policy)
        assertNull(drifting.update("a", 0.55f, 1000L))
        assertNull(drifting.update("a", 0.55f, 1499L))
        assertEquals("a", drifting.update("a", 0.55f, 1500L))
    }

    /**
     * 用「最慢采样」而不是最新采样，所以要求只减不增 —— 停稳过之后再抖一下
     * 不会把门槛重新抬高，也就不会出现 hover 高亮反复闪。
     */
    @Test
    fun `a later jitter cannot raise the bar after the pointer has settled once`() {
        val gate = NativeFolderHoverGate(policy)
        assertNull(gate.update("a", 0f, 1000L))
        // 中途抖到接近门槛：若按最新采样算，要求会涨回 500ms 而在 1240ms 落空。
        assertNull(gate.update("a", 0.5f, 1100L))
        assertEquals("a", gate.update("a", 0.5f, 1240L))
    }

    /** 一旦武装就保持武装，直到候选变了或离开核心区 —— 避免高亮闪断。 */
    @Test
    fun `an armed target stays armed until the candidate changes`() {
        val gate = NativeFolderHoverGate(policy)
        assertNull(gate.update("a", 0f, 1000L))
        assertEquals("a", gate.update("a", 0f, 1240L))
        assertEquals("a", gate.update("a", 0.3f, 1250L))
        // 离开核心区（候选为 null）才解除。
        assertNull(gate.update(null, 0f, 1260L))
        assertNull(gate.update("a", 0f, 1270L))
    }
}
