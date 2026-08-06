package com.flue.launcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆屏胶囊形状的尺寸护栏。
 *
 * 钉住的是「什么形状算行状」这条分界：直接用 [androidx.compose.foundation.shape.CircleShape]
 * 会按短边取半径，竖长卡片就被啃成竖药丸（见 [RowCapsuleShape] 的注释）。
 * 尺寸取圆屏实测值：466px / 2.0 密度 ≈ 233dp 屏宽，减两侧 26dp 内边距 ≈ 181dp 内容宽。
 */
class RowCapsuleShapeTest {

    private val fallbackRadius = 18.dp
    private val shape = RowCapsuleShape(fallback = RoundedCornerShape(fallbackRadius))
    private val density = Density(density = 2f)

    @Test
    fun `普通设置行取真胶囊`() {
        // 181 × 56，宽高比 3.23 ≥ 2.4
        assertTrue(shape.isCapsuleAt(Size(width = 362f, height = 112f)))
    }

    @Test
    fun `滑块行退回兜底圆角`() {
        // 181 × 100（标题 + 数值 + 轨道），宽高比 1.81 < 2.4。
        // 取满半高会是 50dp 圆角，两端会啃到滑轨。
        assertFalse(shape.isCapsuleAt(Size(width = 362f, height = 200f)))
    }

    @Test
    fun `关于页竖长卡片退回兜底圆角`() {
        // 181 × 400，宽高比 0.45
        assertFalse(shape.isCapsuleAt(Size(width = 362f, height = 800f)))
    }

    @Test
    fun `零高度不当成胶囊`() {
        // 首帧测量出 0 高时别去除以零，直接交给兜底形状。
        assertFalse(shape.isCapsuleAt(Size(width = 362f, height = 0f)))
    }

    @Test
    fun `胶囊圆角等于半高`() {
        val size = Size(width = 362f, height = 112f)
        val outline = shape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded
        assertEquals(56f, outline.roundRect.topLeftCornerRadius.x, 0.01f)
        assertEquals(56f, outline.roundRect.bottomRightCornerRadius.y, 0.01f)
    }

    @Test
    fun `兜底时圆角来自被包装的形状`() {
        val size = Size(width = 362f, height = 800f)
        val outline = shape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded
        // 18dp × 2.0 密度 = 36px，来自 fallback 而不是半高 400px。
        assertEquals(CornerRadius(36f, 36f), outline.roundRect.topLeftCornerRadius)
    }

    @Test
    fun `显式胶囊角色让高滑块也取半高圆角`() {
        val capsule = RoundElementShape(
            fallback = RoundedCornerShape(fallbackRadius),
            role = RoundElementRole.CAPSULE,
        )
        val outline = capsule.createOutline(
            Size(width = 362f, height = 200f),
            LayoutDirection.Ltr,
            density,
        ) as Outline.Rounded

        assertEquals(100f, outline.roundRect.topLeftCornerRadius.x, 0.01f)
        assertEquals(100f, outline.roundRect.bottomRightCornerRadius.y, 0.01f)
    }

    @Test
    fun `面板角色始终保留风格圆角`() {
        val panel = RoundElementShape(
            fallback = RoundedCornerShape(fallbackRadius),
            role = RoundElementRole.PANEL,
        )
        val outline = panel.createOutline(
            Size(width = 362f, height = 112f),
            LayoutDirection.Ltr,
            density,
        ) as Outline.Rounded

        assertEquals(CornerRadius(36f, 36f), outline.roundRect.topLeftCornerRadius)
    }
}
