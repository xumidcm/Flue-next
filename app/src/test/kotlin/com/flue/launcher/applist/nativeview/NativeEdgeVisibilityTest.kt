package com.flue.launcher.applist.nativeview

import com.flue.launcher.util.FlowCrescentDirection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class NativeEdgeVisibilityTest {
    @Test
    fun roundHoneycombKeepsSmallIconsAtBothEndsReachable() {
        val config = NativeAppListConfig(
            presentation = NativeAppListPresentation.HONEYCOMB, deviceShape = NativeDeviceShape.ROUND,
            honeycombNarrowColumns = 10, honeycombCellSizePx = 42.84f, iconSizePx = 42,
            fisheyeEnabled = false, edgeAlignEnabled = true, edgeAlignPaddingPx = 36f,
            entryMotionEnabled = false,
        )
        val grid = geometry(NativeHoneycombLayoutManager { config }, config, 100)
        assertEndRowsVisible(grid, config, 42f) { x, y ->
            nativeHoneycombChildVisual(x, y, 454f, 454f, config)
        }
    }

    @Test
    fun roundDenseGridUsesMeasuredIconSizeWhenProtectingItsEnds() {
        for (fisheye in listOf(false, true)) {
            val config = NativeAppListConfig(
                presentation = NativeAppListPresentation.DENSE_GRID, deviceShape = NativeDeviceShape.ROUND,
                denseGridColumns = 10, fisheyeEnabled = fisheye,
                edgeAlignEnabled = true, edgeAlignPaddingPx = 24f, entryMotionEnabled = false,
            )
            val grid = geometry(NativeDenseGridLayoutManager { config }, config, 80)
            val size = minOf(grid.itemWidthPx.roundToInt(), grid.itemHeightPx.roundToInt()) -
                2f * config.denseGridIconInnerPaddingPx
            assertEndRowsVisible(grid, config, size) { x, y ->
                nativeDenseGridChildVisual(x, y, 454f, 454f, config)
            }
        }
    }

    private fun assertEndRowsVisible(
        grid: NativeAppGridGeometry,
        config: NativeAppListConfig,
        iconSize: Float,
        visual: (Float, Float) -> NativeSpatialChildVisual,
    ) {
        for ((end, scroll) in listOf(0 to grid.maximumScrollOffsetPx,
            grid.itemCount - 1 to grid.minimumScrollOffsetPx)) {
            val rowY = grid.screenCenterY(end, scroll)
            for (position in 0 until grid.itemCount) {
                if (abs(grid.screenCenterY(position, scroll) - rowY) > 0.01f) continue
                val v = visual(grid.centerX(position), rowY)
                val distance = hypot(grid.centerX(position) + v.translationX - 227f,
                    rowY + v.translationY - 227f) + iconSize * v.scale / 2f
                // A wide honeycomb row can already exceed the screen horizontally.
                // Such a row must remain at least as accessible as it was at the centre.
                val bestUnscaledExtent = abs(grid.centerX(position) - 227f) + iconSize / 2f
                assertTrue("${config.presentation} item=$position y=$rowY distance=$distance",
                    distance <= maxOf(227f, bestUnscaledExtent) + 0.6f)
            }
        }
    }

    @Test
    fun deepCrescentKeepsBothEndRowsAccessibleAtSmallScale() {
        for (direction in FlowCrescentDirection.entries) {
            for (fisheye in listOf(false, true)) {
                for (count in listOf(1, 30)) {
                    val config = NativeAppListConfig(
                        presentation = NativeAppListPresentation.FLOW,
                        iconSizePx = 42, flowCellSizePx = 42.84f, flowColumns = 1,
                        flowCrescentBulgeFraction = 1.5f, flowCrescentDirection = direction,
                        fisheyeEnabled = fisheye, edgeAlignEnabled = true, edgeAlignPaddingPx = 8f,
                        entryMotionEnabled = false,
                    )
                    val geometry = geometry(NativeFlowLayoutManager { config }, config, count)
                    for ((position, scroll) in listOf(0 to geometry.maximumScrollOffsetPx,
                        count - 1 to geometry.minimumScrollOffsetPx)) {
                        val flow = geometry as NativeFlowGeometry
                        val y = flow.screenCenterY(position, scroll)
                        val visual = nativeHoneycombChildVisual(flow.centerX(position), y, 454f, 454f,
                            config, verticalOnlyFisheye = true)
                        val x = flow.centerX(position) + visual.translationX + nativeFlowCrescentTranslationX(
                            flow.crescentBulgePx, flow.crescentToRight, y, 454f)
                        val half = config.iconSizePx * visual.scale / 2f
                        assertTrue("$direction fisheye=$fisheye count=$count x=$x", x - half >= -0.5f && x + half <= 454.5f)
                    }
                }
            }
        }
    }

    @Test
    fun roundRingKeepsEveryEndRowIconInsideTheScreen() {
        for (columns in listOf(3, 10)) for (count in listOf(1, columns * 20 + 1)) {
            for (fisheye in listOf(false, true)) {
                val cell = 454f / columns
                val config = NativeAppListConfig(
                    presentation = NativeAppListPresentation.RING, deviceShape = NativeDeviceShape.ROUND,
                    honeycombNarrowColumns = columns, ringCellSizePx = cell,
                    iconSizePx = nativeRingIconSizePx(cell, 1f), fisheyeEnabled = fisheye,
                    edgeAlignEnabled = true, edgeAlignPaddingPx = 36f, entryMotionEnabled = false,
                )
                val ring = geometry(NativeRingLayoutManager { config }, config, count) as NativeRingGeometry
                for ((row, scroll) in listOf(0 to ring.maximumScrollOffsetPx,
                    ring.placements.last().row to ring.minimumScrollOffsetPx)) {
                    for (placement in ring.placements.filter { it.row == row }) {
                        val y = ring.screenCenterY(placement.position, scroll)
                        val visual = nativeRingChildVisual(placement.centerX, y, 454f, 454f, config)
                        val half = config.iconSizePx * visual.scale / 2f
                        val dx = abs(placement.centerX + visual.translationX - 227f)
                        val dy = abs(y + visual.translationY - 227f)
                        assertTrue("cols=$columns count=$count fisheye=$fisheye row=$row x=${placement.centerX} y=$y",
                            hypot(dx, dy) + half <= 227.6f)
                    }
                }
            }
        }
    }

    private fun geometry(manager: NativeAppGridLayoutManager, config: NativeAppListConfig, count: Int): NativeAppGridGeometry {
        val method = manager.javaClass.getDeclaredMethod("createGeometry", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, NativeAppListConfig::class.java)
        method.isAccessible = true
        return method.invoke(manager, count, 454, 454, config) as NativeAppGridGeometry
    }
}
