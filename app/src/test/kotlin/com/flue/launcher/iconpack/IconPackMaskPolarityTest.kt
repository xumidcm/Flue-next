package com.flue.launcher.iconpack

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `iconmask` 极性判定。
 *
 * 这条测试盯的是老师报的「换图标包之后，图标包**没有**收录的应用显示成黑色空壳」。
 * 根因是 `iconmask` 有两套相反约定，而代码只按其中一套（`DST_IN`）处理：
 * 遇到 ColorOS 那种「白色圆角方压在不透明黑上」的反相 mask，DST_IN 会把图标本体
 * 整个擦掉，只剩周围那圈深色。适配过的应用走包里现成图片、不进模板合成，所以看起来
 * 是「有的正常有的全黑」。
 */
class IconPackMaskPolarityTest {

    /** ADW / Nova 文档那一套：形状处不透明、四角透明。 */
    @Test
    fun `an opaque shape on a transparent surround keeps the inside`() {
        assertEquals(
            IconPackMaskPolarity.KEEP_INSIDE,
            iconPackMaskPolarity(centerAlpha = 255, cornerAlpha = 0),
        )
    }

    /** ColorOS 那一套：形状处透明、四角不透明，mask 标的是要挖掉的地方。 */
    @Test
    fun `a transparent shape on an opaque surround keeps the outside`() {
        assertEquals(
            IconPackMaskPolarity.KEEP_OUTSIDE,
            iconPackMaskPolarity(centerAlpha = 0, cornerAlpha = 255),
        )
    }

    /**
     * 对比不够就不猜。
     *
     * 一张几乎均匀的图既不能当保留遮罩也不能当挖除遮罩 —— 按任一套硬用都是在赌，
     * 而赌错的代价是整排图标消失。这种情况下不遮，至少图标还在。
     */
    @Test
    fun `a nearly uniform mask carries no shape information`() {
        assertEquals(
            IconPackMaskPolarity.UNUSABLE,
            iconPackMaskPolarity(centerAlpha = 255, cornerAlpha = 255),
        )
        assertEquals(
            IconPackMaskPolarity.UNUSABLE,
            iconPackMaskPolarity(centerAlpha = 0, cornerAlpha = 0),
        )
        // 差 32 还不够，仍然算判不出来。
        assertEquals(
            IconPackMaskPolarity.UNUSABLE,
            iconPackMaskPolarity(centerAlpha = 160, cornerAlpha = 128),
        )
    }

    /** 半透明但对比足够的 mask 照样能判。 */
    @Test
    fun `partial alpha still resolves when the contrast is large enough`() {
        assertEquals(
            IconPackMaskPolarity.KEEP_INSIDE,
            iconPackMaskPolarity(centerAlpha = 200, cornerAlpha = 40),
        )
        assertEquals(
            IconPackMaskPolarity.KEEP_OUTSIDE,
            iconPackMaskPolarity(centerAlpha = 40, cornerAlpha = 200),
        )
    }

    /** 越界输入不能把判定搞崩：alpha 只可能是 0..255。 */
    @Test
    fun `out of range alpha is clamped`() {
        assertEquals(
            IconPackMaskPolarity.KEEP_INSIDE,
            iconPackMaskPolarity(centerAlpha = 999, cornerAlpha = -50),
        )
    }
}
