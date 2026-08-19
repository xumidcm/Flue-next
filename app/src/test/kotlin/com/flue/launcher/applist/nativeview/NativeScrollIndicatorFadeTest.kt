package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指示器淡入淡出的时序。
 *
 * 老师要的是「和 Apple Watch 一样」的滑动进度指示器,而 watchOS 那条是滚动时出现、
 * 停下后淡出的。时序被抽成纯函数就是为了这套测试 —— 留在 View 里只能靠肉眼看,
 * 而「快进慢出」「反向不跳变」「一定收敛到 0」这几条恰恰是肉眼最难判的。
 */
class NativeScrollIndicatorFadeTest {

    // ---------- 目标不透明度 ----------

    @Test
    fun `scrolling holds the indicator fully opaque`() {
        assertEquals(
            1f,
            nativeScrollIndicatorTargetAlpha(scrolling = true, millisSinceScrollActivity = 0L),
            0f,
        )
    }

    /**
     * 按住列表不放、内容没动时也必须保持显示。
     *
     * 这条是分开取 `scrolling` 和 `millisSinceScrollActivity` 两个入参的理由:
     * 只看后者的话,按住的那几百毫秒里没有新的滚动量,会被判成该淡出 —— 手指还在屏幕上
     * 指示器却开始消失,松手又立刻回来,就是一次闪烁。
     */
    @Test
    fun `holding the list without moving it still counts as scrolling`() {
        assertEquals(
            1f,
            nativeScrollIndicatorTargetAlpha(
                scrolling = true,
                // 远超保持窗口,只有 scrolling 这一个理由撑着。
                millisSinceScrollActivity = NATIVE_SCROLL_INDICATOR_HOLD_MS * 10,
            ),
            0f,
        )
    }

    /** 停下之后先保持一段,再转向淡出。 */
    @Test
    fun `the hold window keeps it opaque briefly after the scroll ends`() {
        assertEquals(
            1f,
            nativeScrollIndicatorTargetAlpha(
                scrolling = false,
                millisSinceScrollActivity = NATIVE_SCROLL_INDICATOR_HOLD_MS - 1,
            ),
            0f,
        )
        assertEquals(
            0f,
            nativeScrollIndicatorTargetAlpha(
                scrolling = false,
                millisSinceScrollActivity = NATIVE_SCROLL_INDICATOR_HOLD_MS,
            ),
            0f,
        )
    }

    // ---------- alpha 推进 ----------

    /** 淡入必须比淡出快:慢淡入会被读成「卡了一下才反应」。 */
    @Test
    fun `fading in is faster than fading out`() {
        assertTrue(
            "淡入 $NATIVE_SCROLL_INDICATOR_FADE_IN_MS 应快于淡出 $NATIVE_SCROLL_INDICATOR_FADE_OUT_MS",
            NATIVE_SCROLL_INDICATOR_FADE_IN_MS < NATIVE_SCROLL_INDICATOR_FADE_OUT_MS,
        )
        val stepIn = nativeScrollIndicatorAdvanceAlpha(0f, 1f, 16L)
        val stepOut = 1f - nativeScrollIndicatorAdvanceAlpha(1f, 0f, 16L)
        assertTrue("同样 16ms 里淡入($stepIn) 应走得比淡出($stepOut) 多", stepIn > stepOut)
    }

    /** 走满时长正好到位,不多不少。 */
    @Test
    fun `a full duration lands exactly on the target`() {
        assertEquals(
            1f,
            nativeScrollIndicatorAdvanceAlpha(0f, 1f, NATIVE_SCROLL_INDICATOR_FADE_IN_MS),
            0f,
        )
        assertEquals(
            0f,
            nativeScrollIndicatorAdvanceAlpha(1f, 0f, NATIVE_SCROLL_INDICATOR_FADE_OUT_MS),
            0f,
        )
    }

    /**
     * 一定收敛到 0,不能留一条几乎看不见但仍在每帧重绘的杠。
     *
     * 这条守的是「线性推进而不是指数逼近」这个选择:指数逼近永远到不了 0,
     * 自我调度的失效帧就永远停不下来,等于一条隐形的耗电杠。
     */
    @Test
    fun `fading out reaches exactly zero in finite frames`() {
        var alpha = 1f
        var frames = 0
        while (alpha > 0f && frames < 1000) {
            alpha = nativeScrollIndicatorAdvanceAlpha(alpha, 0f, 16L)
            frames += 1
        }
        assertEquals("没有收敛到 0,停在 $alpha", 0f, alpha, 0f)
        assertTrue("$frames 帧才淡完,太久", frames < 40)
    }

    /** 淡入同样会真正到 1,不是无限逼近。 */
    @Test
    fun `fading in reaches exactly one in finite frames`() {
        var alpha = 0f
        var frames = 0
        while (alpha < 1f && frames < 1000) {
            alpha = nativeScrollIndicatorAdvanceAlpha(alpha, 1f, 16L)
            frames += 1
        }
        assertEquals(1f, alpha, 0f)
        assertTrue("$frames 帧才淡入,太久", frames < 20)
    }

    /**
     * 淡出没完又开始滚,要从**当前值**往回走,不能跳回 0 再重来。
     *
     * 跳变会被看成闪一下。这里断言的是连续性:反向第一帧的结果必须落在
     * 「打断时的值」和「目标」之间。
     */
    @Test
    fun `reversing mid fade continues from the current value`() {
        val interrupted = nativeScrollIndicatorAdvanceAlpha(1f, 0f, 100L)
        assertTrue("打断点应在中途,实际 $interrupted", interrupted in 0.01f..0.99f)

        val reversed = nativeScrollIndicatorAdvanceAlpha(interrupted, 1f, 16L)
        assertTrue("反向后不该低于打断点:$interrupted -> $reversed", reversed > interrupted)
        assertTrue("反向后不该越过目标:$reversed", reversed <= 1f)
    }

    /** 已经到位就原地不动,顺带保证同一帧被多次调用不会漂移。 */
    @Test
    fun `already at the target stays put`() {
        assertEquals(1f, nativeScrollIndicatorAdvanceAlpha(1f, 1f, 500L), 0f)
        assertEquals(0f, nativeScrollIndicatorAdvanceAlpha(0f, 0f, 500L), 0f)
        // elapsed 为 0（首帧没有上一帧可比）时也不动。
        assertEquals(0.4f, nativeScrollIndicatorAdvanceAlpha(0.4f, 1f, 0L), 0.0001f)
    }

    /** alpha 恒被钳在 0..1,越界或非法输入不能把绘制搞崩。 */
    @Test
    fun `alpha is always clamped and never NaN`() {
        listOf(
            nativeScrollIndicatorAdvanceAlpha(5f, 1f, 16L),
            nativeScrollIndicatorAdvanceAlpha(-3f, 0f, 16L),
            nativeScrollIndicatorAdvanceAlpha(Float.NaN, 1f, 16L),
            nativeScrollIndicatorAdvanceAlpha(0.5f, Float.NaN, 16L),
            nativeScrollIndicatorAdvanceAlpha(0.5f, 1f, -100L),
            nativeScrollIndicatorAdvanceAlpha(0.5f, 1f, Long.MAX_VALUE),
        ).forEach { alpha ->
            assertTrue("$alpha 不是有限值", alpha.isFinite())
            assertTrue("$alpha 越界", alpha in 0f..1f)
        }
    }

    /**
     * 一次完整的「滚 → 停 → 淡出」时间线。
     *
     * 单帧断言测不出时序错位（比如保持窗口在淡出之后才生效）,所以这里按 16ms 一帧
     * 走一整段,检查三个阶段各自的表现。
     */
    @Test
    fun `a whole scroll then stop timeline fades in holds then fades out`() {
        var alpha = 0f
        var elapsedSinceActivity = 0L

        // 阶段一：滚动中，几帧内就该全不透明。
        repeat(10) {
            alpha = nativeScrollIndicatorAdvanceAlpha(
                currentAlpha = alpha,
                targetAlpha = nativeScrollIndicatorTargetAlpha(
                    scrolling = true,
                    millisSinceScrollActivity = 0L,
                ),
                elapsedMillis = 16L,
            )
        }
        assertEquals("滚动中应已全不透明", 1f, alpha, 0f)

        // 阶段二：刚停下，保持窗口内不该掉。
        while (elapsedSinceActivity < NATIVE_SCROLL_INDICATOR_HOLD_MS) {
            alpha = nativeScrollIndicatorAdvanceAlpha(
                currentAlpha = alpha,
                targetAlpha = nativeScrollIndicatorTargetAlpha(
                    scrolling = false,
                    millisSinceScrollActivity = elapsedSinceActivity,
                ),
                elapsedMillis = 16L,
            )
            assertEquals(
                "保持窗口内($elapsedSinceActivity ms) 不该开始淡出",
                1f,
                alpha,
                0f,
            )
            elapsedSinceActivity += 16L
        }

        // 阶段三：保持窗口过后淡到 0。
        var frames = 0
        while (alpha > 0f && frames < 1000) {
            alpha = nativeScrollIndicatorAdvanceAlpha(
                currentAlpha = alpha,
                targetAlpha = nativeScrollIndicatorTargetAlpha(
                    scrolling = false,
                    millisSinceScrollActivity = elapsedSinceActivity,
                ),
                elapsedMillis = 16L,
            )
            elapsedSinceActivity += 16L
            frames += 1
        }
        assertEquals("最终应完全透明", 0f, alpha, 0f)
    }
}
