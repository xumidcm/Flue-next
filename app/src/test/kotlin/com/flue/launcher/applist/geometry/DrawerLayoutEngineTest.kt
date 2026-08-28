package com.flue.launcher.applist.geometry

import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerLayoutEngineTest {
    private val engine = DrawerLayoutEngine()

    @Test
    fun nextKeepsTheCentralSixtyPercentStable() {
        val result = engine.layout(
            request(
                itemCenters = listOf(
                    placement("center", x = 200f, y = 200f),
                    placement("stable-edge", x = 200f, y = 320f),
                ),
                fisheye = FisheyeParameters.Next(),
            ),
        )

        result.forEach { item ->
            assertEquals(1f, item.scale, EPSILON)
            assertEquals(1f, item.alpha, EPSILON)
            assertEquals(BlurBucket.NONE, item.blurBucket)
        }
        assertEquals(200f, result[0].bounds.centerY, EPSILON)
        assertEquals(320f, result[1].bounds.centerY, EPSILON)
    }

    @Test
    fun roundOutlinePreservesTheOrderOfRowsBeyondItsSoftBoundary() {
        val result = engine.layout(
            request(
                itemCenters = listOf(
                    placement("outer", x = 200f, y = -100f),
                    placement("inner", x = 200f, y = -99f),
                ),
                // 参数名跟 request() 的形参对齐（deviceProfile，不是 profile）。
                deviceProfile = DeviceProfile.Round(),
                fisheye = FisheyeParameters.Classic(
                    minScale = 1f,
                    minAlpha = 1f,
                    maximumSpacingCompression = 0f,
                ),
            ),
        )

        assertTrue(result[0].bounds.centerY < result[1].bounds.centerY)
    }

    @Test
    fun nextIsVerticallySymmetric() {
        val top = placement("top", x = 200f, y = 60f)
        val bottom = placement("bottom", x = 200f, y = 340f)

        val result = engine.layout(
            request(
                itemCenters = listOf(top, bottom),
                fisheye = FisheyeParameters.Next(),
            ),
        )

        assertEquals(result[0].scale, result[1].scale, EPSILON)
        assertEquals(result[0].alpha, result[1].alpha, EPSILON)
        assertEquals(result[0].blurBucket, result[1].blurBucket)
        assertEquals(400f, result[0].bounds.centerY + result[1].bounds.centerY, EPSILON)
        assertEquals(result[0].zOrder, result[1].zOrder, EPSILON)
    }

    @Test
    fun nextReachesFrozenEdgeValuesAndCompressesDistanceByThirtyPercent() {
        val result = engine.layout(
            request(
                itemCenters = listOf(placement("edge", x = 200f, y = 400f)),
                fisheye = FisheyeParameters.Next(),
            ),
        ).single()

        assertEquals(0.45f, result.scale, EPSILON)
        assertEquals(0.55f, result.alpha, EPSILON)
        assertEquals(340f, result.bounds.centerY, EPSILON)
        assertEquals(BlurBucket.DP_8, result.blurBucket)
        assertEquals(0f, result.zOrder, EPSILON)
    }

    @Test
    fun classicUsesConfigurableLinearCenterScaling() {
        val result = engine.layout(
            request(
                itemCenters = listOf(
                    placement("center", x = 200f, y = 200f),
                    placement("halfway", x = 200f, y = 100f),
                    placement("edge", x = 200f, y = 0f),
                ),
                fisheye = FisheyeParameters.Classic(
                    minScale = 0.4f,
                    minAlpha = 0.6f,
                    maximumBlur = BlurBucket.DP_4,
                ),
            ),
        )

        assertEquals(1f, result[0].scale, EPSILON)
        assertEquals(0.7f, result[1].scale, EPSILON)
        assertEquals(0.4f, result[2].scale, EPSILON)
        assertEquals(0.8f, result[1].alpha, EPSILON)
        assertEquals(0.6f, result[2].alpha, EPSILON)
        assertEquals(BlurBucket.DP_4, result[2].blurBucket)
    }

    @Test
    fun roundProfileKeepsEveryHitRegionCornerInsideTheSafeCircle() {
        val result = engine.layout(
            request(
                deviceProfile = DeviceProfile.Round(),
                itemSize = PixelSize(width = 80f, height = 80f),
                itemCenters = listOf(placement("top-left", x = 0f, y = 20f)),
                fisheye = FisheyeParameters.Next(),
            ),
        ).single()

        result.hitRegion.corners().forEach { corner ->
            assertTrue(
                "corner $corner must remain inside the round safe outline",
                hypot(corner.x - 200f, corner.y - 200f) <= 200f + EPSILON,
            )
        }
        assertTrue(result.bounds.left >= result.hitRegion.left)
        assertTrue(result.bounds.top >= result.hitRegion.top)
        assertTrue(result.bounds.right <= result.hitRegion.right)
        assertTrue(result.bounds.bottom <= result.hitRegion.bottom)
    }

    @Test
    fun rectangularSafeInsetsConstrainBoundsAndHitRegion() {
        val result = engine.layout(
            request(
                deviceProfile = DeviceProfile.Rectangular(
                    safeInsets = SafeInsetsPx(left = 30f, top = 40f, right = 50f, bottom = 60f),
                ),
                itemCenters = listOf(placement("outside", x = 0f, y = 0f)),
                fisheye = FisheyeParameters.Classic(),
            ),
        ).single()

        assertTrue(result.hitRegion.left >= 30f)
        assertTrue(result.hitRegion.top >= 40f)
        assertTrue(result.hitRegion.right <= 350f)
        assertTrue(result.hitRegion.bottom <= 340f)
        assertTrue(result.bounds.left >= 30f)
        assertTrue(result.bounds.top >= 40f)
    }

    @Test
    fun launchOriginMatchesTheFinalBoundsCenter() {
        val result = engine.layout(
            request(
                itemCenters = listOf(placement("origin", x = 120f, y = 280f)),
                scrollOffsetPx = 40f,
                fisheye = FisheyeParameters.Next(),
            ),
        ).single()

        assertEquals(result.bounds.centerX / 400f, result.launchOrigin.x, EPSILON)
        assertEquals(result.bounds.centerY / 400f, result.launchOrigin.y, EPSILON)
    }

    @Test
    fun scrollOffsetIsAppliedBeforeFisheyeGeometry() {
        val result = engine.layout(
            request(
                itemCenters = listOf(placement("scrolled", x = 200f, y = 250f)),
                scrollOffsetPx = 50f,
                fisheye = FisheyeParameters.Next(),
            ),
        ).single()

        assertEquals(200f, result.bounds.centerY, EPSILON)
        assertEquals(1f, result.scale, EPSILON)
    }

    private fun request(
        deviceProfile: DeviceProfile = DeviceProfile.Rectangular(),
        itemSize: PixelSize = PixelSize(width = 40f, height = 40f),
        itemCenters: List<LinearItemPlacement>,
        scrollOffsetPx: Float = 0f,
        fisheye: FisheyeParameters,
    ): DrawerLayoutRequest = DrawerLayoutRequest(
        viewport = ViewportPx(width = 400f, height = 400f),
        deviceProfile = deviceProfile,
        itemPlacements = itemCenters,
        scrollOffsetPx = scrollOffsetPx,
        itemSize = itemSize,
        fisheye = fisheye,
    )

    private fun placement(
        key: String,
        x: Float,
        y: Float,
    ): LinearItemPlacement = LinearItemPlacement(
        key = AppItemKey(key),
        contentCenter = PixelPoint(x = x, y = y),
    )

    private fun PixelRect.corners(): List<PixelPoint> = listOf(
        PixelPoint(left, top),
        PixelPoint(right, top),
        PixelPoint(right, bottom),
        PixelPoint(left, bottom),
    )

    private companion object {
        const val EPSILON = 0.0001f
    }
}
