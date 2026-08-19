package com.flue.launcher.ui.style

import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A2b：同类行合并成一张卡的两条判定。
 *
 * 位置本身（1 行 → Single、n 行 → First/Middle/Last）在 `FlueCapsuleGeometryTest` 里已经钉住，
 * 这里钉的是**什么时候才合并**与**间距归谁**：两条都是「看不见的规则」，写错了不会崩，只会
 * 让某一档风格的设置页悄悄变形。
 */
class FlueGroupPositionTest {

    @Test
    fun `only square screen miuix merges rows`() {
        // 老师定：圆屏保持独立胶囊。圆屏每行吃的鱼眼缩放比例不同，合并成一张卡左右边缘
        // 必然错茬 —— 这不是省事，是圆屏上合并本身就不成立。
        assertEquals(true, flueGroupingEnabled(UiStyle.MIUIX, isRoundScreen = false))
        assertEquals(false, flueGroupingEnabled(UiStyle.MIUIX, isRoundScreen = true))
    }

    @Test
    fun `the other two styles never merge`() {
        // APPLE / MD3 本来就是一行一张独立卡，合并会把它们改成别的风格。
        listOf(UiStyle.APPLE_WATCH, UiStyle.MATERIAL_3).forEach { style ->
            listOf(true, false).forEach { round ->
                assertEquals(
                    "$style round=$round",
                    false,
                    flueGroupingEnabled(style, isRoundScreen = round),
                )
            }
        }
    }

    @Test
    fun `spacing collapses inside a group and reappears after it`() {
        val listSpacing = 10.dp
        // 组内行之间一点缝都不能有，否则「一张卡」看着就是几张卡。
        assertEquals(0.dp, flueGroupBottomSpacing(FlueGroupPosition.First, listSpacing))
        assertEquals(0.dp, flueGroupBottomSpacing(FlueGroupPosition.Middle, listSpacing))
        // 末行出组间距，而不是列表行距 —— 组与组之间要比组内明显更开。
        assertEquals(FlueGroupSpacing, flueGroupBottomSpacing(FlueGroupPosition.Last, listSpacing))
    }

    @Test
    fun `an unmerged row keeps exactly the list spacing`() {
        // 没合并的行（另两档风格、圆屏、或组里只剩一行）必须拿回列表原本统一给的那个值，
        // 否则「改了合并」会顺带改掉所有未迁移页面的行距。
        listOf(6.dp, 10.dp, 0.dp).forEach { spacing ->
            assertEquals(spacing, flueGroupBottomSpacing(FlueGroupPosition.Single, spacing))
        }
    }
}
