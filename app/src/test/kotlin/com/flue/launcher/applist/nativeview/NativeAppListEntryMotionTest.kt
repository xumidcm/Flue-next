package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用列表入场动画的判据。
 *
 * 老师报的是「应用列表压根没有甩尾入场动画，倒是设置页有」。根因有两条，这里各盯一条：
 *  1. Apple 风格（默认风格）下 `entryMotionEnabled` 被写死成 false，
 *     [nativeStaggeredEntryProgress] 一进来就 return 1f，图标直接就位。
 *  2. 入场是**线性**推到位的，且图标格那两条路只做位移不做缩放，
 *     观感是「平移进来然后停住」，没有落定感（老师要的「回弹和大小缩放」）。
 */
class NativeAppListEntryMotionTest {

    private fun config(
        motionEnabled: Boolean = true,
        stagger: Float = 0.65f,
        progress: Float = 1f,
    ) = NativeAppListConfig(
        entryMotionEnabled = motionEnabled,
        entryStaggerStrength = stagger,
        entryProgress = progress,
    )

    @Test
    fun `stagger delays lower rows without stranding them short of the target`() {
        val cfg = config(progress = 0.5f)
        val top = nativeStaggeredEntryProgress(cfg, childCenterY = 0f, viewportHeight = 400f)
        val bottom = nativeStaggeredEntryProgress(cfg, childCenterY = 400f, viewportHeight = 400f)
        assertTrue("上面的行应该更早到位：top=$top bottom=$bottom", top > bottom)

        // 全局进度到 1 时，最后一行也必须到位 —— 否则底部永远差一截。
        val done = config(progress = 1f)
        assertEquals(
            1f,
            nativeStaggeredEntryProgress(done, childCenterY = 400f, viewportHeight = 400f),
            1e-4f,
        )
    }

    @Test
    fun `disabling entry motion skips the animation entirely`() {
        val cfg = config(motionEnabled = false, progress = 0f)
        assertEquals(
            1f,
            nativeStaggeredEntryProgress(cfg, childCenterY = 10f, viewportHeight = 400f),
            0f,
        )
    }

    /**
     * 回弹：末段要越过目标再落回，两个端点必须精确，而**线性段必须保留**。
     *
     * 线性段是甩尾能被看见的前提：如果整条曲线是 back-out，t=0.5 时值就已经 0.98，
     * 动画前半程结束、行与行的延迟差异被压平，甩尾等于白做。
     */
    @Test
    fun `the entry curve overshoots only in the tail and keeps the ramp linear`() {
        assertEquals(0f, nativeEntryOvershoot(0f), 0f)
        assertEquals(1f, nativeEntryOvershoot(1f), 0f)

        // 线性段：值随时间等比推进（斜率恒定），甩尾的行间差因此完整保留。
        val quarter = nativeEntryOvershoot(0.205f)
        val half = nativeEntryOvershoot(0.41f)
        assertEquals("线性段应等比推进", 2f, half / quarter, 0.01f)
        assertTrue("线性段不该提前到位：$half", half < 0.6f)

        val peak = (82..99).maxOf { nativeEntryOvershoot(it / 100f) }
        assertTrue("应该有过冲，实测峰值 $peak", peak > 1f)
        // 手表屏幕小，过冲超过 8% 会被看成抖动。
        assertTrue("过冲太大：$peak", peak < 1.08f)
    }

    @Test
    fun `entry scale ramps from the shrunken state up to one`() {
        assertEquals(0.90f, nativeEntryScale(0f), 1e-4f)
        assertEquals(1f, nativeEntryScale(1f), 1e-4f)
        // 过冲进来时缩放要跟着略微超过 1，但夹在上限内。
        val overshot = nativeEntryScale(nativeEntryOvershoot(0.91f))
        assertTrue("过冲时应略大于 1：$overshot", overshot > 1f)
        assertTrue("缩放不能失控：$overshot", overshot <= 1.05f)
    }

    /** 非有限输入不能把变换算成 NaN —— 那会让整行从屏幕上消失。 */
    @Test
    fun `non finite input falls back to the settled state`() {
        assertEquals(1f, nativeEntryOvershoot(Float.NaN), 0f)
        assertEquals(
            1f,
            nativeStaggeredEntryProgress(config(), childCenterY = Float.NaN, viewportHeight = 400f),
            0f,
        )
    }
}
