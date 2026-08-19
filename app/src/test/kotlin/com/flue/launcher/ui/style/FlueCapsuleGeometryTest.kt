package com.flue.launcher.ui.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 圆屏胶囊几何。
 *
 * 存在理由是老师报的「圆屏文字挤到溢出、又贴边」：旧实现是写死的
 * `if (round) 22.dp else 16.dp`，与行高无关。两个错叠在一起 ——
 * 22dp 在窄屏上接近官方 5.2% 基线的两倍（白吃两侧近 20dp），而圆角弧啃进来的深度
 * 又完全没被计入（于是高行照样贴边）。这套测试把「基线按屏宽、弧深按行高」钉住。
 */
class FlueCapsuleGeometryTest {

    @Test
    fun `baseline margin follows the official 5_2 percent of screen width`() {
        assertEquals(0.052f, FlueCapsuleGeometry.ROUND_BASELINE_FRACTION, 0f)
        assertEquals(23.608f, FlueCapsuleGeometry.roundBaselineMarginPx(454f), 1e-3f)
        // 窄屏上基线自动变小，而写死的 22dp 做不到这件事。
        assertTrue(
            FlueCapsuleGeometry.roundBaselineMarginPx(320f) <
                FlueCapsuleGeometry.roundBaselineMarginPx(454f),
        )
    }

    @Test
    fun `degenerate widths give no margin`() {
        assertEquals(0f, FlueCapsuleGeometry.roundBaselineMarginPx(0f), 0f)
        assertEquals(0f, FlueCapsuleGeometry.roundBaselineMarginPx(-100f), 0f)
        assertEquals(0f, FlueCapsuleGeometry.roundBaselineMarginPx(Float.NaN), 0f)
    }

    @Test
    fun `radius takes half height when nothing caps it`() {
        assertEquals(
            28f,
            FlueCapsuleGeometry.capsuleRadiusPx(rowHeightPx = 56f, widthPx = 400f),
            0f,
        )
    }

    @Test
    fun `radius is capped by the style limit`() {
        // MIUIX / MD3 停在自己的平滑圆角档上，不被拉成药丸。
        assertEquals(
            22f,
            FlueCapsuleGeometry.capsuleRadiusPx(
                rowHeightPx = 56f,
                widthPx = 400f,
                maxRadiusPx = 22f,
            ),
            0f,
        )
        // 上限比半高还大时不起作用。
        assertEquals(
            28f,
            FlueCapsuleGeometry.capsuleRadiusPx(
                rowHeightPx = 56f,
                widthPx = 400f,
                maxRadiusPx = 999f,
            ),
            0f,
        )
    }

    @Test
    fun `radius is capped by half width so tall narrow blocks are not chewed`() {
        // 181×400 的竖卡片：取半高会左右各顶一个大半圆，把图标文字全啃掉。
        assertEquals(
            90.5f,
            FlueCapsuleGeometry.capsuleRadiusPx(rowHeightPx = 400f, widthPx = 181f),
            1e-3f,
        )
    }

    @Test
    fun `degenerate sizes give no radius`() {
        assertEquals(0f, FlueCapsuleGeometry.capsuleRadiusPx(0f, 400f), 0f)
        assertEquals(0f, FlueCapsuleGeometry.capsuleRadiusPx(-56f, 400f), 0f)
        assertEquals(0f, FlueCapsuleGeometry.capsuleRadiusPx(Float.NaN, 400f), 0f)
    }

    @Test
    fun `arc inset is zero for zero height content and equals the radius at full diameter`() {
        // h = 0：内容退化成一条线，正好穿过圆心高度，弧不啃它。
        assertEquals(0f, FlueCapsuleGeometry.capsuleContentInsetPx(28f, 0f), 0f)
        // h = 2R：内容顶到圆角上下两端，弧深等于半径。
        assertEquals(28f, FlueCapsuleGeometry.capsuleContentInsetPx(28f, 56f), 1e-3f)
        // 超过 2R 也停在 R —— 再高也不可能啃得比半径更深。
        assertEquals(28f, FlueCapsuleGeometry.capsuleContentInsetPx(28f, 500f), 1e-3f)
    }

    @Test
    fun `arc inset matches the closed form`() {
        val r = 28f
        val h = 36f
        val expected = r - sqrt(r * r - (h / 2f) * (h / 2f))
        assertEquals(expected, FlueCapsuleGeometry.capsuleContentInsetPx(r, h), 1e-4f)
        // 内容越高啃得越深，这是整个修复的关键：写死的常数做不到。
        assertTrue(
            FlueCapsuleGeometry.capsuleContentInsetPx(r, 48f) >
                FlueCapsuleGeometry.capsuleContentInsetPx(r, 24f),
        )
    }

    @Test
    fun `square screens keep the existing margin untouched`() {
        // 方屏行为必须与改动前完全一致，否则这次几何重做会顺手改坏方屏。
        assertEquals(
            16f,
            FlueCapsuleGeometry.horizontalPaddingPx(
                round = false,
                screenWidthPx = 454f,
                radiusPx = 28f,
                rowHeightPx = 56f,
                contentHeightPx = 40f,
                squareMarginPx = 16f,
            ),
            0f,
        )
    }

    @Test
    fun `content that never reaches the corner is not charged any arc depth`() {
        // 高行配小圆角：100dp 的滑块行、22dp 圆角，内容 62dp 居中 —— 内容最上沿在
        // 19dp 处，仍在圆心（22dp）之上一点，所以只啃一点点；内容再矮一档就完全不啃。
        assertEquals(
            0f,
            FlueCapsuleGeometry.contentArcInsetPx(
                radiusPx = 22f,
                rowHeightPx = 100f,
                contentHeightPx = 40f,
            ),
            0f,
        )
        // 这正是旧式子算过头的地方：它会给出满额 22dp。
        assertEquals(
            22f,
            FlueCapsuleGeometry.capsuleContentInsetPx(radiusPx = 22f, contentHeightPx = 62f),
            1e-3f,
        )
        assertTrue(
            FlueCapsuleGeometry.contentArcInsetPx(22f, 100f, 62f) <
                FlueCapsuleGeometry.capsuleContentInsetPx(22f, 62f),
        )
    }

    @Test
    fun `the general formula reduces to the capsule one when the radius is half the row`() {
        val r = 28f
        val rowHeight = 56f
        listOf(0f, 12f, 24f, 40f, 56f).forEach { h ->
            assertEquals(
                "content height $h",
                FlueCapsuleGeometry.capsuleContentInsetPx(r, h),
                FlueCapsuleGeometry.contentArcInsetPx(r, rowHeight, h),
                1e-4f,
            )
        }
    }

    @Test
    fun `round padding is baseline plus arc depth`() {
        val screen = 454f
        val radius = 28f
        val rowHeight = 56f
        val content = 40f
        val expected = FlueCapsuleGeometry.roundBaselineMarginPx(screen) +
            FlueCapsuleGeometry.contentArcInsetPx(radius, rowHeight, content)
        assertEquals(
            expected,
            FlueCapsuleGeometry.horizontalPaddingPx(
                round = true,
                screenWidthPx = screen,
                radiusPx = radius,
                rowHeightPx = rowHeight,
                contentHeightPx = content,
                squareMarginPx = 16f,
            ),
            1e-4f,
        )
    }

    @Test
    fun `round padding respects the minimum`() {
        assertEquals(
            12f,
            FlueCapsuleGeometry.horizontalPaddingPx(
                round = true,
                screenWidthPx = 1f,
                radiusPx = 0f,
                rowHeightPx = 0f,
                contentHeightPx = 0f,
                squareMarginPx = 16f,
                minimumPx = 12f,
            ),
            0f,
        )
    }

    @Test
    fun `band inset matches the centred formula for a centred band`() {
        val radius = 32f
        val rowHeight = 121f
        listOf(0f, 20f, 44f, 64f, 121f).forEach { contentHeight ->
            val top = (rowHeight - contentHeight) / 2f
            assertEquals(
                "content height $contentHeight",
                FlueCapsuleGeometry.contentArcInsetPx(radius, rowHeight, contentHeight),
                FlueCapsuleGeometry.contentArcInsetForBandPx(
                    radiusPx = radius,
                    rowHeightPx = rowHeight,
                    bandTopPx = top,
                    bandBottomPx = top + contentHeight,
                ),
                1e-4f,
            )
        }
    }

    @Test
    fun `the arc bites deepest at the far end of the band, not at the corner centre`() {
        // 圆角矩形的左边界在直边段（y ∈ [R, H-R]）上内缩为零，离直边段越远内缩越大。
        // 所以一条带区的最深内缩在它的端点上，而不是在圆心高度上。
        val radius = 22f
        val rowHeight = 94f
        // 带区上沿 10 距上圆角圆心 12 → 22 - sqrt(484 - 144)
        val expected = radius - sqrt(radius * radius - 12f * 12f)
        assertEquals(
            expected,
            FlueCapsuleGeometry.contentArcInsetForBandPx(radius, rowHeight, 10f, 40f),
            1e-4f,
        )
        // 带区越往边缘伸，吃的弧越深。
        assertTrue(
            FlueCapsuleGeometry.contentArcInsetForBandPx(radius, rowHeight, 2f, 40f) >
                FlueCapsuleGeometry.contentArcInsetForBandPx(radius, rowHeight, 10f, 40f),
        )
    }

    @Test
    fun `a band inside the straight section is charged nothing`() {
        assertEquals(
            0f,
            FlueCapsuleGeometry.contentArcInsetForBandPx(
                radiusPx = 22f,
                rowHeightPx = 94f,
                bandTopPx = 30f,
                bandBottomPx = 60f,
            ),
            0f,
        )
    }

    @Test
    fun `the slider track band is charged more than the centred assumption`() {
        // 这条是整个 J 修复的核心断言：MIUIX 滑块行 94dp 高、22dp 圆角，轨道占
        // [56, 80]。按「44dp 内容居中」算得 0（旧行为，于是轨道顶到弧上），
        // 按真实带区算要收一截。
        val centred = FlueCapsuleGeometry.contentArcInsetPx(
            radiusPx = 22f,
            rowHeightPx = 94f,
            contentHeightPx = 44f,
        )
        val band = FlueCapsuleGeometry.contentArcInsetForBandPx(
            radiusPx = 22f,
            rowHeightPx = 94f,
            bandTopPx = 56f,
            bandBottomPx = 80f,
        )
        assertEquals(0f, centred, 0f)
        assertTrue("band inset was $band", band > 1f)
    }

    @Test
    fun `band inset is symmetric between the two corners`() {
        // 上部带与镜像下来的下部带必须收同样多，否则同一行左右内边距会不对称。
        val radius = 32f
        val rowHeight = 120f
        val upper = FlueCapsuleGeometry.contentArcInsetForBandPx(radius, rowHeight, 8f, 24f)
        val lower = FlueCapsuleGeometry.contentArcInsetForBandPx(
            radiusPx = radius,
            rowHeightPx = rowHeight,
            bandTopPx = rowHeight - 24f,
            bandBottomPx = rowHeight - 8f,
        )
        assertEquals(upper, lower, 1e-4f)
    }

    @Test
    fun `degenerate bands give no inset`() {
        assertEquals(0f, FlueCapsuleGeometry.contentArcInsetForBandPx(0f, 94f, 10f, 40f), 0f)
        assertEquals(0f, FlueCapsuleGeometry.contentArcInsetForBandPx(22f, 0f, 10f, 40f), 0f)
        assertEquals(
            0f,
            FlueCapsuleGeometry.contentArcInsetForBandPx(22f, 94f, Float.NaN, 40f),
            0f,
        )
    }

    @Test
    fun `reversed band bounds are normalised`() {
        assertEquals(
            FlueCapsuleGeometry.contentArcInsetForBandPx(32f, 121f, 59f, 107f),
            FlueCapsuleGeometry.contentArcInsetForBandPx(32f, 121f, 107f, 59f),
            0f,
        )
    }

    @Test
    fun `the apple radius cap keeps single rows a capsule but stops tall rows`() {
        // J1 的全部内容：APPLE 档 capsuleMaxRadius = 32dp。
        // 56dp 标准行仍取满半高 28（真胶囊），100dp 滑块行停在 32 而不是 50 ——
        // 50dp 的半圆正是老师看到的「撑到胶囊边缘」。
        val appleCap = 32f
        assertEquals(
            28f,
            FlueCapsuleGeometry.capsuleRadiusPx(56f, 454f, appleCap),
            0f,
        )
        assertEquals(
            appleCap,
            FlueCapsuleGeometry.capsuleRadiusPx(100f, 454f, appleCap),
            0f,
        )
        // 没有上限时它会一路涨到半高，弧深也跟着涨 —— 这是改动前的行为。
        assertEquals(50f, FlueCapsuleGeometry.capsuleRadiusPx(100f, 454f, null), 0f)
        assertTrue(
            FlueCapsuleGeometry.contentArcInsetForBandPx(50f, 100f, 56f, 80f) >
                FlueCapsuleGeometry.contentArcInsetForBandPx(appleCap, 100f, 56f, 80f),
        )
    }

    @Test
    fun `capped radius makes padding stop growing with row height`() {
        // 有了上限，长文字把行撑高也不会再让内边距继续涨 —— 这是「内边距可以按
        // 名义行高查表、不必做内在测量」的依据（内在测量那条路已经崩过一次）。
        val cap = 32f
        fun paddingAt(rowHeight: Float): Float = FlueCapsuleGeometry.horizontalPaddingPx(
            round = true,
            screenWidthPx = 454f,
            radiusPx = FlueCapsuleGeometry.capsuleRadiusPx(rowHeight, 454f, cap),
            rowHeightPx = rowHeight,
            contentHeightPx = rowHeight - 20f,
            squareMarginPx = 16f,
        )
        val tall = paddingAt(120f)
        val taller = paddingAt(200f)
        assertEquals(tall, taller, 0.01f)
    }

    @Test
    fun `group position covers single two and many rows`() {
        assertEquals(FlueGroupPosition.Single, flueGroupPositionAt(0, 1))
        assertEquals(FlueGroupPosition.First, flueGroupPositionAt(0, 2))
        assertEquals(FlueGroupPosition.Last, flueGroupPositionAt(1, 2))
        assertEquals(FlueGroupPosition.First, flueGroupPositionAt(0, 5))
        assertEquals(FlueGroupPosition.Middle, flueGroupPositionAt(1, 5))
        assertEquals(FlueGroupPosition.Middle, flueGroupPositionAt(3, 5))
        assertEquals(FlueGroupPosition.Last, flueGroupPositionAt(4, 5))
    }

    @Test
    fun `out of range indices fall back to single`() {
        // 宁可少合并，也不要画出朝向错误的圆角。
        assertEquals(FlueGroupPosition.Single, flueGroupPositionAt(-1, 5))
        assertEquals(FlueGroupPosition.Single, flueGroupPositionAt(5, 5))
        assertEquals(FlueGroupPosition.Single, flueGroupPositionAt(0, 0))
    }

    @Test
    fun `corner and divider flags follow the position`() {
        assertTrue(FlueGroupPosition.Single.roundsTop && FlueGroupPosition.Single.roundsBottom)
        assertTrue(FlueGroupPosition.First.roundsTop && !FlueGroupPosition.First.roundsBottom)
        assertTrue(!FlueGroupPosition.Middle.roundsTop && !FlueGroupPosition.Middle.roundsBottom)
        assertTrue(!FlueGroupPosition.Last.roundsTop && FlueGroupPosition.Last.roundsBottom)
        // 分隔线挂在组内非末行的底部。
        assertTrue(FlueGroupPosition.First.needsDivider)
        assertTrue(FlueGroupPosition.Middle.needsDivider)
        assertTrue(!FlueGroupPosition.Last.needsDivider)
        assertTrue(!FlueGroupPosition.Single.needsDivider)
    }
}
