package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 按压高亮的形状判定。
 *
 * 这组测试盯的是老师报的「网格底板关掉之后，按压高亮仍是整格圆角矩形」——
 * 底板是网格布局唯一那块整格实体，它被关掉之后格子里只剩一个圆图标，
 * 整格高亮就没有对应的实体可描了。
 */
class NativePressOverlayPolicyTest {

    private fun shapeOf(
        presentation: NativeAppListPresentation,
        iconShape: NativeAppListIconShape = NativeAppListIconShape.CIRCLE,
        rowBackgroundVisible: Boolean = false,
    ) = nativePressOverlayShape(presentation, iconShape, rowBackgroundVisible)

    /** 老师报的那条：底板关掉，高亮必须跟着图标而不是整格。 */
    @Test
    fun `dense grid traces the icon once the row plate is hidden`() {
        assertEquals(
            NativePressOverlayShape.ICON_SHAPE,
            shapeOf(NativeAppListPresentation.DENSE_GRID, rowBackgroundVisible = false),
        )
    }

    /** 底板还在的时候仍然描整格 —— 高亮和底板同形才像「整格按下去」。 */
    @Test
    fun `dense grid traces the whole cell while the row plate is visible`() {
        assertEquals(
            NativePressOverlayShape.ROW_ROUNDED_RECT,
            shapeOf(NativeAppListPresentation.DENSE_GRID, rowBackgroundVisible = true),
        )
    }

    /**
     * 网格的判定只看底板，不看 iconShape。
     *
     * 这两个是各自独立的设置：界面风格决定 iconShape，底板开关决定有没有底板。
     * 把 iconShape 掺进判据会让「MD3 风格 + 底板关」变成描整格，又回到那个 bug。
     */
    @Test
    fun `dense grid ignores icon shape and keys only on the plate`() {
        NativeAppListIconShape.entries.forEach { iconShape ->
            assertEquals(
                "iconShape=$iconShape 时底板关着仍应描图标",
                NativePressOverlayShape.ICON_SHAPE,
                shapeOf(
                    NativeAppListPresentation.DENSE_GRID,
                    iconShape = iconShape,
                    rowBackgroundVisible = false,
                ),
            )
            assertEquals(
                "iconShape=$iconShape 时底板开着仍应描整格",
                NativePressOverlayShape.ROW_ROUNDED_RECT,
                shapeOf(
                    NativeAppListPresentation.DENSE_GRID,
                    iconShape = iconShape,
                    rowBackgroundVisible = true,
                ),
            )
        }
    }

    /**
     * 蜂窝 / 心流 / 星环永远描图标，且不受底板影响。
     *
     * 这三种的格子只比圆图标略宽，整格高亮会糊满一格、盖掉格间空隙。
     */
    @Test
    fun `compact layouts always trace the icon regardless of the plate`() {
        listOf(
            NativeAppListPresentation.HONEYCOMB,
            NativeAppListPresentation.FLOW,
            NativeAppListPresentation.RING,
        ).forEach { presentation ->
            listOf(true, false).forEach { plateVisible ->
                assertEquals(
                    "$presentation plate=$plateVisible",
                    NativePressOverlayShape.ICON_SHAPE,
                    shapeOf(presentation, rowBackgroundVisible = plateVisible),
                )
            }
        }
    }

    /** 线性布局跟 iconShape：圆图标那档高亮圆盘，方图标那档高亮整行。 */
    @Test
    fun `linear layout follows the icon shape`() {
        assertEquals(
            NativePressOverlayShape.ICON_SHAPE,
            shapeOf(
                NativeAppListPresentation.LINEAR,
                iconShape = NativeAppListIconShape.CIRCLE,
            ),
        )
        assertEquals(
            NativePressOverlayShape.ROW_ROUNDED_RECT,
            shapeOf(
                NativeAppListPresentation.LINEAR,
                iconShape = NativeAppListIconShape.ROUNDED_RECT,
            ),
        )
    }

    /**
     * 每一种 presentation 都必须有判定，不能漏。
     *
     * 加新布局时（星环就是这么加进来的）漏掉一档，Kotlin 的穷尽 when 会在编译期
     * 拦住；这条测试额外挡住「为了让编译过而随手加个 else 分支」那种改法。
     */
    @Test
    fun `every presentation resolves to a shape`() {
        NativeAppListPresentation.entries.forEach { presentation ->
            listOf(true, false).forEach { plateVisible ->
                // 不抛异常即通过：判定必须对每一档都给出结果。
                shapeOf(presentation, rowBackgroundVisible = plateVisible)
            }
        }
    }
}
