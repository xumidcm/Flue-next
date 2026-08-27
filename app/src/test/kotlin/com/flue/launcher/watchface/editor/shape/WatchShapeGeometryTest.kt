package com.flue.launcher.watchface.editor.shape

import android.graphics.Path
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 形状几何的回归测试。
 *
 * 这一套的由来：`shape` 的 7 个合法字形里，以前只有 Square / Circle / Triangle 画对，
 * Star / Pentagon / Hexagon / Heart **全部落到 `drawRect`**（画成矩形）；`corner_type`
 * 那张 0..16 的表根本没人读，`rounded` 永远四角全圆，而 `chart` 把这个枚举当成像素
 * 半径用。
 *
 * 这类错在本地跑一遍表盘**看不出来**：画面上有东西，只是形状不对。所以必须有测试
 * 对着规范逐个值比。
 *
 * 表的出处：`upstream/wm-def/WM_FORMAT_1.0.md:405-425`（TL/TR/BL/BR 四列），
 * 与 `upstream/clox/.../wf_render.lua:176-181` 的 `CORNERS` 逐位一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchShapeGeometryTest {

    /** Android `addRoundRect` 的数组顺序：TL,TL, TR,TR, BR,BR, BL,BL。 */
    private fun radii(cornerType: Int): FourCorners {
        val array = watchShapeCornerRadii(cornerType, 10f)
        assertEquals("radii array must have 8 entries", 8, array.size)
        // 每个角的两个半径必须相等 —— 我们只支持正圆角，不支持椭圆角。
        assertEquals(array[0], array[1], 0f)
        assertEquals(array[2], array[3], 0f)
        assertEquals(array[4], array[5], 0f)
        assertEquals(array[6], array[7], 0f)
        return FourCorners(
            topLeft = array[0] > 0f,
            topRight = array[2] > 0f,
            bottomRight = array[4] > 0f,
            bottomLeft = array[6] > 0f,
        )
    }

    private data class FourCorners(
        val topLeft: Boolean,
        val topRight: Boolean,
        val bottomRight: Boolean,
        val bottomLeft: Boolean,
    )

    private fun corners(tl: Boolean, tr: Boolean, bl: Boolean, br: Boolean) =
        FourCorners(topLeft = tl, topRight = tr, bottomRight = br, bottomLeft = bl)

    @Test
    fun `corner type table matches the specification`() {
        // 规范 §7.12 的表，逐行照抄（列顺序 TL / TR / BL / BR）。
        assertEquals(corners(tl = false, tr = false, bl = false, br = false), radii(0))
        assertEquals(corners(tl = true, tr = true, bl = true, br = true), radii(1))
        assertEquals(corners(tl = true, tr = true, bl = true, br = false), radii(2))
        assertEquals(corners(tl = true, tr = true, bl = false, br = true), radii(3))
        assertEquals(corners(tl = true, tr = false, bl = true, br = true), radii(4))
        assertEquals(corners(tl = false, tr = true, bl = true, br = true), radii(5))
        assertEquals(corners(tl = true, tr = true, bl = false, br = false), radii(6))
        assertEquals(corners(tl = false, tr = false, bl = true, br = true), radii(7))
        assertEquals(corners(tl = true, tr = false, bl = true, br = false), radii(8))
        assertEquals(corners(tl = false, tr = true, bl = false, br = true), radii(9))
        assertEquals(corners(tl = true, tr = false, bl = false, br = true), radii(10))
        assertEquals(corners(tl = false, tr = true, bl = true, br = false), radii(11))
        assertEquals(corners(tl = true, tr = false, bl = false, br = false), radii(12))
        assertEquals(corners(tl = false, tr = true, bl = false, br = false), radii(13))
        assertEquals(corners(tl = false, tr = false, bl = true, br = false), radii(14))
        assertEquals(corners(tl = false, tr = false, bl = false, br = true), radii(15))
        // 16 与 0 同义 —— 规范里就是这么定的，不是笔误。
        assertEquals(corners(tl = false, tr = false, bl = false, br = false), radii(16))
    }

    @Test
    fun `unknown corner type falls back to all four corners`() {
        // 与 clox 的 `CORNERS[...] or CORNERS[1]` 同样兜底。
        assertEquals(corners(tl = true, tr = true, bl = true, br = true), radii(99))
        assertEquals(corners(tl = true, tr = true, bl = true, br = true), radii(-3))
    }

    @Test
    fun `zero radius means no rounded corner regardless of corner type`() {
        assertFalse(watchShapeHasRoundedCorner(cornerType = 1, radius = 0f))
        assertTrue(watchShapeHasRoundedCorner(cornerType = 1, radius = 1f))
        // corner_type 0 / 16 无论半径多大都是全直角。
        assertFalse(watchShapeHasRoundedCorner(cornerType = 0, radius = 40f))
        assertFalse(watchShapeHasRoundedCorner(cornerType = 16, radius = 40f))
    }

    @Test
    fun `corner radius clamps to half the shorter side`() {
        // 规范：clamped to half the smaller dimension（clox 是 min(radius, w/2, h/2)）。
        assertEquals(30f, watchShapeClampCornerRadius(999f, width = 100f, height = 60f), 0f)
        assertEquals(12f, watchShapeClampCornerRadius(12f, width = 100f, height = 60f), 0f)
        assertEquals(0f, watchShapeClampCornerRadius(-5f, width = 100f, height = 60f), 0f)
    }

    @Test
    fun `shape names map to the seven specification kinds`() {
        assertEquals(WatchShapeKind.SQUARE, watchShapeKindOf("Square"))
        assertEquals(WatchShapeKind.CIRCLE, watchShapeKindOf("Circle"))
        assertEquals(WatchShapeKind.TRIANGLE, watchShapeKindOf("Triangle"))
        assertEquals(WatchShapeKind.PENTAGON, watchShapeKindOf("Pentagon"))
        assertEquals(WatchShapeKind.HEXAGON, watchShapeKindOf("Hexagon"))
        assertEquals(WatchShapeKind.STAR, watchShapeKindOf("Star"))
        assertEquals(WatchShapeKind.HEART, watchShapeKindOf("Heart"))
        // 大小写不敏感：表盘里 shape 的大小写不统一是常态。
        assertEquals(WatchShapeKind.HEXAGON, watchShapeKindOf("hexagon"))
        // 缺省与无法识别的值都退回方形（旧渲染器的 else 分支也是矩形）。
        assertEquals(WatchShapeKind.SQUARE, watchShapeKindOf(null))
        assertEquals(WatchShapeKind.SQUARE, watchShapeKindOf(""))
        assertEquals(WatchShapeKind.SQUARE, watchShapeKindOf("blob"))
    }

    @Test
    fun `Square slash Rectangle is not mistaken for a triangle`() {
        // 老表盘里出现过 "Square/Rectangle" 这种带后缀的写法。判定用 contains，
        // 所以顺序必须让 rectangle 先落地 —— 否则 "…Rectangle" 里的 "angle"
        // 会被 triangle 的子串匹配抢走。
        assertEquals(WatchShapeKind.SQUARE, watchShapeKindOf("Square/Rectangle"))
        assertEquals(WatchShapeKind.CIRCLE, watchShapeKindOf("Circle/Ellipse"))
    }

    @Test
    fun `attribute values round trip through the enum`() {
        WATCH_SHAPE_ATTRIBUTE_VALUES.forEach { literal ->
            val kind = watchShapeKindOf(literal)
            assertEquals(
                "writing $literal back must preserve the spec's capitalisation",
                literal,
                watchShapeAttributeValue(kind),
            )
        }
    }

    @Test
    fun `polygonal shapes fill their declared box and stay centred on the origin`() {
        val bounds = RectF()
        // 心形单独测（见下一条）：它的极值点是**控制点**而不是曲线上的点。
        (WatchShapeKind.entries - WatchShapeKind.HEART).forEach { kind ->
            val path = buildWatchShapePath(kind, width = 100f, height = 60f, into = Path())
            path.computeBounds(bounds, true)
            assertFalse("$kind must not be empty", path.isEmpty)
            // 以原点为中心：渲染器画图层时 canvas 已经吃过该层的矩阵。
            assertEquals("$kind left", -50f, bounds.left, 0.6f)
            assertEquals("$kind right", 50f, bounds.right, 0.6f)
            assertEquals("$kind top", -30f, bounds.top, 0.6f)
            assertEquals("$kind bottom", 30f, bounds.bottom, 0.6f)
        }
    }

    @Test
    fun `heart stays inside its declared box`() {
        // 心形的四段三次贝塞尔里，最外侧的坐标是**控制点**而不是曲线上的点，所以实际
        // 包围盒比声明盒子小一圈（横向约 3%、纵向顶部约 17%），而且顶部余量比底部大 ——
        // 那正是心形该有的样子。这是 wftools 预览器的原始几何（`WmPreview.kt:1828-1833`），
        // 与它对齐比填满盒子更重要，所以这里只要求「不越界、横向居中、底尖贴底」。
        val bounds = RectF()
        buildWatchShapePath(WatchShapeKind.HEART, 100f, 60f, Path()).computeBounds(bounds, true)
        assertTrue("heart overflows horizontally", bounds.left >= -50.01f && bounds.right <= 50.01f)
        assertTrue("heart overflows vertically", bounds.top >= -30.01f && bounds.bottom <= 30.01f)
        assertEquals("heart horizontal centring", -bounds.left, bounds.right, 0.6f)
        // 底部那个尖必须正好落在盒子底边，否则心形会看起来在往上飘。
        assertEquals("heart bottom tip", 30f, bounds.bottom, 0.6f)
        // 横向至少填到 90%，纵向至少 80% —— 再窄就说明控制点被改坏了。
        assertTrue("heart too narrow", bounds.width() >= 90f)
        assertTrue("heart too short", bounds.height() >= 48f)
    }

    @Test
    fun `shadow offset shifts the whole path`() {
        val bounds = RectF()
        buildWatchShapePath(WatchShapeKind.STAR, 100f, 60f, Path(), dx = 7f, dy = -3f)
            .computeBounds(bounds, true)
        assertEquals(-43f, bounds.left, 0.6f)
        assertEquals(57f, bounds.right, 0.6f)
        assertEquals(-33f, bounds.top, 0.6f)
        assertEquals(27f, bounds.bottom, 0.6f)
    }

    @Test
    fun `non rectangular shapes are not rectangles`() {
        // 这条是整套改动的核心回归：以前这四种全落到 drawRect。
        // 判据用「路径不是矩形」而不是比顶点 —— 后者会把测试锁死在具体坐标上。
        listOf(
            WatchShapeKind.TRIANGLE,
            WatchShapeKind.PENTAGON,
            WatchShapeKind.HEXAGON,
            WatchShapeKind.STAR,
            WatchShapeKind.HEART,
        ).forEach { kind ->
            val path = buildWatchShapePath(kind, 100f, 100f, Path())
            assertFalse("$kind must not degrade to a rectangle", path.isRect(null))
        }
        assertTrue(buildWatchShapePath(WatchShapeKind.SQUARE, 100f, 100f, Path()).isRect(null))
    }

    @Test
    fun `rounded rect honours the corner type`() {
        val bounds = RectF()
        val path = buildWatchRoundedRectPath(
            width = 100f,
            height = 60f,
            radius = 12f,
            cornerType = 6,
            into = Path(),
        )
        path.computeBounds(bounds, true)
        assertEquals(-50f, bounds.left, 0.1f)
        assertEquals(30f, bounds.bottom, 0.1f)
        // 只圆上面两个角时不可能是纯矩形。
        assertFalse(path.isRect(null))
        // corner_type 0 = 四角全直，退化成矩形。
        assertTrue(
            buildWatchRoundedRectPath(100f, 60f, 12f, cornerType = 0, into = Path())
                .isRect(null),
        )
    }

    @Test
    fun `chart bars can be rounded in an arbitrary rectangle`() {
        val bounds = RectF()
        buildWatchRoundedRectPathIn(
            left = 10f,
            top = 80f,
            right = 30f,
            bottom = 20f,
            radius = 5f,
            cornerType = 6,
            into = Path(),
        ).computeBounds(bounds, true)
        // top / bottom 传反了也要得到同一个盒子 —— 柱状图的柱子会按数值正负颠倒。
        assertEquals(10f, bounds.left, 0.1f)
        assertEquals(30f, bounds.right, 0.1f)
        assertEquals(20f, bounds.top, 0.1f)
        assertEquals(80f, bounds.bottom, 0.1f)
    }
}
