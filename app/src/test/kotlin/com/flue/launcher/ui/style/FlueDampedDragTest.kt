package com.flue.launcher.ui.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3：APPLE 档开关 / 滑块的手感规则。
 *
 * 这几段是照 Kyant `DampedDragAnimation` 抄过来的取值与判定。它们**写错了不会崩**，只会
 * 让手感不对，而手感在 CI 里没法看 —— 所以逐个钉住，改动时至少能发现「数变了」。
 */
class FlueDampedDragTest {

    @Test
    fun `velocity deformation is clamped at twelve percent`() {
        // 上限从源码的 20% 收到 12%：老师报「拖动时白块的粗细变化不流畅」，幅度越大，
        // 速度噪声换算出来的跳变就越显眼。公式仍是 scaleX /= 1 - clamp(v * 0.75, ±L)，
        // 所以最大拉伸 = 1 / 0.88。
        assertEquals(1f / 0.88f, FlueDampedDrag.stretchScaleX(100f), 0.0001f)
        assertEquals(1f / 0.88f, FlueDampedDrag.stretchScaleX(1_000_000f), 0.0001f)
        // 反向同样被夹住：1 / 1.12。
        assertEquals(1f / 1.12f, FlueDampedDrag.stretchScaleX(-100f), 0.0001f)
        // scaleY *= 1 - clamp(v * 0.25, ±L)。
        assertEquals(0.88f, FlueDampedDrag.squashScaleY(100f), 0.0001f)
        assertEquals(1.12f, FlueDampedDrag.squashScaleY(-100f), 0.0001f)
    }

    @Test
    fun `the pressed thumb material stays inside sane bounds`() {
        // 「按住时变透明 + 描边 + 高光」这三个取值写飞了不会崩，只会让滑块看不清 ——
        // 钉一下量级：仍然是白的（不是灰块）、描边与高光都只是点缀。
        assertTrue(FlueDampedDrag.PRESSED_ALPHA in 0.4f..0.9f)
        assertTrue(FlueDampedDrag.PRESSED_EDGE_ALPHA in 0.05f..0.4f)
        assertTrue(FlueDampedDrag.PRESSED_HIGHLIGHT_ALPHA in 0.2f..0.8f)
        assertTrue(FlueDampedDrag.PRESSED_HIGHLIGHT_FRACTION in 0.2f..0.6f)
    }

    @Test
    fun `zero velocity leaves the shape untouched`() {
        // 静止时必须**正好**是 1：差一点点就会在待机态看到一个被压扁的滑块。
        assertEquals(1f, FlueDampedDrag.stretchScaleX(0f), 0f)
        assertEquals(1f, FlueDampedDrag.squashScaleY(0f), 0f)
    }

    @Test
    fun `stretch and squash pull in opposite directions`() {
        // 「体积守恒」的错觉靠的就是这个反向：拉长的同时压扁。
        val v = FlueDampedDrag.normalizedVelocity(60f, FlueDampedDrag.SWITCH_VELOCITY_DIVISOR)
        assertTrue(FlueDampedDrag.stretchScaleX(v) > 1f)
        assertTrue(FlueDampedDrag.squashScaleY(v) < 1f)
    }

    @Test
    fun `the switch and the slider normalize velocity differently`() {
        // 滑块行程比开关长得多，同样手速要更明显的形变，所以除数更小（10 对 50）。
        val fast = 500f
        assertEquals(10f, FlueDampedDrag.normalizedVelocity(fast, FlueDampedDrag.SWITCH_VELOCITY_DIVISOR), 0.0001f)
        assertEquals(50f, FlueDampedDrag.normalizedVelocity(fast, FlueDampedDrag.SLIDER_VELOCITY_DIVISOR), 0.0001f)
        // 除数为 0 不能炸成 NaN/Inf —— 那会让整个 graphicsLayer 画不出来。
        assertEquals(0f, FlueDampedDrag.normalizedVelocity(fast, 0f), 0f)
    }

    @Test
    fun `release waits for the value to enter a two and a half percent neighbourhood`() {
        // 收放大的时序判据。松早了就是「先缩回原大小、再慢慢滑到位」，看着像卡了一下。
        assertTrue(FlueDampedDrag.isSettled(0.98f, 1f))
        assertTrue(FlueDampedDrag.isSettled(1.02f, 1f))
        assertTrue(!FlueDampedDrag.isSettled(0.9f, 1f))
        assertTrue(FlueDampedDrag.isSettled(0.02f, 0f))
        assertTrue(!FlueDampedDrag.isSettled(0.2f, 0f))
    }

    @Test
    fun `dragging past half way snaps on`() {
        assertTrue(!FlueDampedDrag.snapToOn(0.49f))
        // 正好半程算「开」—— 与 material 的吸附方向一致，免得同一台机上两种控件反向。
        assertTrue(FlueDampedDrag.snapToOn(0.5f))
        assertTrue(FlueDampedDrag.snapToOn(0.51f))
    }

    @Test
    fun `a gesture without movement is a tap`() {
        // 无 slop：动过就是拖，一点没动才当点按。设阈值会让小幅拖动被吞成 tap，
        // 手感上像「点了一下却跳到反面」。
        assertTrue(!FlueDampedDrag.isDrag(0f))
        assertTrue(FlueDampedDrag.isDrag(0.5f))
        assertTrue(FlueDampedDrag.isDrag(-0.5f))
    }

    @Test
    fun `quantize snaps to the nearest step and keeps the ends reachable`() {
        // steps 与 material3.Slider 同义：中间刻度数，所以 steps = 3 是四段五档。
        assertEquals(0f, FlueDampedDrag.quantize(0.1f, 3), 0.0001f)
        assertEquals(0.25f, FlueDampedDrag.quantize(0.2f, 3), 0.0001f)
        assertEquals(0.5f, FlueDampedDrag.quantize(0.5f, 3), 0.0001f)
        assertEquals(1f, FlueDampedDrag.quantize(0.95f, 3), 0.0001f)
        // 无刻度时原样返回，只夹取。
        assertEquals(0.37f, FlueDampedDrag.quantize(0.37f, 0), 0.0001f)
        assertEquals(0f, FlueDampedDrag.quantize(-1f, 0), 0f)
        assertEquals(1f, FlueDampedDrag.quantize(2f, 0), 0f)
        assertEquals(1f, FlueDampedDrag.quantize(2f, 5), 0f)
    }

    @Test
    fun `the pressed scale is the fifty percent growth ios uses`() {
        // 40×24 的滑块按下去涨到 60×36，会溢出 64×28 的轨道 —— iOS 就是这么做的，
        // 所以父级不能 clip、行高要留够。这条断言是为了让「1.5」不被随手改小。
        assertEquals(1.5f, FlueDampedDrag.PRESSED_SCALE, 0f)
    }
}
