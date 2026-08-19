package com.flue.launcher.applist.nativeview

import com.flue.launcher.ui.common.MIN_CHORD_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆屏列表模式的「曲线」——整行按所在高度的弦宽等比收进内切圆。
 *
 * 这里锁的是三条铁律里能在单测层面验证的两条：**只吃布局值**（入参全是布局量，没有任何
 * 变换后的边界）与**等比**（单一返回值同时喂 scaleX / scaleY，函数签名本身保证）。
 * 第三条「只通过变换施加」由调用点（`NativeAppListView.applyChildVisual` → `setVisualScale`）保证。
 */
class NativeRoundChordCurveTest {

    /** 方屏一律 1f：整条曲线退化成 no-op，行为与这个功能加进来之前逐位相同。 */
    @Test
    fun leavesRectangularScreensUntouched() {
        assertEquals(
            1f,
            nativeRoundChordScale(
                childCenterY = 0f,
                viewportWidthPx = 400f,
                viewportHeightPx = 400f,
                deviceShape = NativeDeviceShape.RECTANGULAR,
            ),
            0f,
        )
    }

    /** 屏幕垂直中心那一行走的是最宽的弦（直径），不缩。 */
    @Test
    fun keepsTheCenterRowAtFullWidth() {
        assertEquals(
            1f,
            nativeRoundChordScale(
                childCenterY = 200f,
                viewportWidthPx = 400f,
                viewportHeightPx = 400f,
                deviceShape = NativeDeviceShape.ROUND,
            ),
            0.0001f,
        )
    }

    /** 离中心越远缩得越狠，且上下对称。 */
    @Test
    fun shrinksSymmetricallyTowardBothEnds() {
        fun scaleAt(centerY: Float) = nativeRoundChordScale(
            childCenterY = centerY,
            viewportWidthPx = 400f,
            viewportHeightPx = 400f,
            deviceShape = NativeDeviceShape.ROUND,
        )

        val near = scaleAt(240f)
        val far = scaleAt(340f)

        assertTrue(far < near)
        assertTrue(near < 1f)
        assertEquals(scaleAt(240f), scaleAt(160f), 0.0001f)
        assertEquals(scaleAt(340f), scaleAt(60f), 0.0001f)
    }

    /**
     * 半径一半处的弦是 `2·√(R² − (R/2)²) = √3·R`，占直径 `√3/2 ≈ 0.866`。
     *
     * 手算这一档是为了确认接的确实是**弦长**公式，而不是某条随手拟的衰减曲线 ——
     * 设置页的圆屏适配用的就是同一条公式，两处漂移的话用户在设置页和应用列表之间
     * 来回切会看到两种「圆屏适配」。
     */
    @Test
    fun followsTheChordFormulaRatherThanAnAdHocCurve() {
        assertEquals(
            0.8660254f,
            nativeRoundChordScale(
                childCenterY = 300f,
                viewportWidthPx = 400f,
                viewportHeightPx = 400f,
                deviceShape = NativeDeviceShape.ROUND,
            ),
            0.0005f,
        )
    }

    /** 越过圆边缘（回弹 / 预取的离屏行）时夹到下限，不能返回 0 把行缩成一个点。 */
    @Test
    fun clampsBeyondTheCircleToTheSharedFloor() {
        assertEquals(
            MIN_CHORD_SCALE,
            nativeRoundChordScale(
                childCenterY = 900f,
                viewportWidthPx = 400f,
                viewportHeightPx = 400f,
                deviceShape = NativeDeviceShape.ROUND,
            ),
            0f,
        )
        assertEquals(
            MIN_CHORD_SCALE,
            nativeRoundChordScale(
                childCenterY = -400f,
                viewportWidthPx = 400f,
                viewportHeightPx = 400f,
                deviceShape = NativeDeviceShape.ROUND,
            ),
            0f,
        )
    }

    /** 视口还没测出来 / 输入非有限时退回 1f —— 首帧不能把行缩掉。 */
    @Test
    fun fallsBackToOneBeforeTheViewportIsKnown() {
        assertEquals(
            1f,
            nativeRoundChordScale(0f, 0f, 400f, NativeDeviceShape.ROUND),
            0f,
        )
        assertEquals(
            1f,
            nativeRoundChordScale(0f, 400f, 0f, NativeDeviceShape.ROUND),
            0f,
        )
        assertEquals(
            1f,
            nativeRoundChordScale(Float.NaN, 400f, 400f, NativeDeviceShape.ROUND),
            0f,
        )
    }
}
