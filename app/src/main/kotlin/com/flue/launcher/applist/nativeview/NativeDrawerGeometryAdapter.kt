package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.geometry.DeviceProfile
import com.flue.launcher.applist.geometry.DrawerLayoutEngine
import com.flue.launcher.applist.geometry.DrawerLayoutRequest
import com.flue.launcher.applist.geometry.DrawerLayoutSession
import com.flue.launcher.applist.geometry.FisheyeParameters
import com.flue.launcher.applist.geometry.MutableDrawerItemGeometry
import com.flue.launcher.applist.geometry.PixelSize
import com.flue.launcher.applist.geometry.ViewportPx

/**
 * Bridges the allocation-free DrawerLayoutEngine session to native View
 * transforms. The session is rebuilt only when a viewport, item metric,
 * device profile, or fish-eye setting changes.
 */
internal class NativeDrawerGeometryAdapter {
    private val engine = DrawerLayoutEngine()
    private val resolved = MutableDrawerItemGeometry()
    private var session: DrawerLayoutSession? = null
    private var viewportWidth = Int.MIN_VALUE
    private var viewportHeight = Int.MIN_VALUE
    private var itemWidth = Float.NaN
    private var itemHeight = Float.NaN
    private var profileKey: NativeDeviceShape? = null
    private var leftInset = Int.MIN_VALUE
    private var topInset = Int.MIN_VALUE
    private var rightInset = Int.MIN_VALUE
    private var bottomInset = Int.MIN_VALUE
    private var preset: NativeFisheyePreset? = null
    private var enabled = false
    private var range = Float.NaN
    private var strength = Float.NaN
    private var spacingCompressionEnabled = false
    private var safeItemWidth = 1f
    private var safeItemHeight = 1f

    fun resolve(
        centerX: Float,
        centerY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        itemWidth: Float,
        itemHeight: Float,
        config: NativeAppListConfig,
        output: NativeSpatialChildVisual,
    ): NativeSpatialChildVisual {
        if (
            !centerX.isFinite() || !centerY.isFinite() ||
            !viewportWidth.isFinite() || viewportWidth <= 0f ||
            !viewportHeight.isFinite() || viewportHeight <= 0f ||
            !itemWidth.isFinite() || itemWidth <= 0f ||
            !itemHeight.isFinite() || itemHeight <= 0f
        ) {
            return output.set(1f, 1f, 0f, 0f, 0f)
        }
        resolveSafeHitSize(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            itemWidth = itemWidth,
            itemHeight = itemHeight,
            config = config,
        )
        val halfItemHeight = safeItemHeight / 2f
        if (
            centerY + halfItemHeight < 0f ||
            centerY - halfItemHeight > viewportHeight
        ) {
            // Do not pin recycled/off-screen rows to the safe outline. Only
            // visible icons are constrained; off-screen content must continue
            // moving naturally so scrolling never develops an edge pile-up.
            return output.set(1f, 1f, 0f, 0f, 0f)
        }
        ensureSession(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            itemWidth = safeItemWidth,
            itemHeight = safeItemHeight,
            config = config,
        )
        val activeSession = requireNotNull(session)
        activeSession.resolve(
            desiredCenterX = centerX,
            desiredCenterY = centerY,
            output = resolved,
        )
        return output.set(
            scale = resolved.scale,
            alpha = resolved.alpha,
            translationX = resolved.centerX - centerX,
            translationY = resolved.centerY - centerY,
            blurRadiusPx = 0f,
        )
    }

    private fun ensureSession(
        viewportWidth: Float,
        viewportHeight: Float,
        itemWidth: Float,
        itemHeight: Float,
        config: NativeAppListConfig,
    ) {
        val viewportWidthInt = viewportWidth.toInt()
        val viewportHeightInt = viewportHeight.toInt()
        val needsRebuild =
            session == null ||
                this.viewportWidth != viewportWidthInt ||
                this.viewportHeight != viewportHeightInt ||
                this.itemWidth != itemWidth ||
                this.itemHeight != itemHeight ||
                profileKey != config.deviceShape ||
                leftInset != config.leftSafeInsetPx ||
                topInset != config.topSafeInsetPx ||
                rightInset != config.rightSafeInsetPx ||
                bottomInset != config.bottomSafeInsetPx ||
                preset != config.fisheyePreset ||
                enabled != config.fisheyeEnabled ||
                range != config.fisheyeRange ||
                strength != config.fisheyeStrength ||
                spacingCompressionEnabled != config.edgeSpacingCompressionEnabled
        if (!needsRebuild) return

        this.viewportWidth = viewportWidthInt
        this.viewportHeight = viewportHeightInt
        this.itemWidth = itemWidth
        this.itemHeight = itemHeight
        profileKey = config.deviceShape
        leftInset = config.leftSafeInsetPx
        topInset = config.topSafeInsetPx
        rightInset = config.rightSafeInsetPx
        bottomInset = config.bottomSafeInsetPx
        preset = config.fisheyePreset
        enabled = config.fisheyeEnabled
        range = config.fisheyeRange
        strength = config.fisheyeStrength
        spacingCompressionEnabled = config.edgeSpacingCompressionEnabled

        val request = DrawerLayoutRequest(
            viewport = ViewportPx(viewportWidth, viewportHeight),
            deviceProfile = config.toDeviceProfile(viewportWidth, viewportHeight),
            itemPlacements = emptyList(),
            scrollOffsetPx = 0f,
            itemSize = PixelSize(itemWidth, itemHeight),
            fisheye = config.toFisheyeParameters(),
        )
        session = engine.session(request)
    }

    private fun resolveSafeHitSize(
        viewportWidth: Float,
        viewportHeight: Float,
        itemWidth: Float,
        itemHeight: Float,
        config: NativeAppListConfig,
    ) {
        val viewportWidthInt = viewportWidth.toInt().coerceAtLeast(1)
        val viewportHeightInt = viewportHeight.toInt().coerceAtLeast(1)
        val safeLeft = config.leftSafeInsetPx.coerceIn(0, viewportWidthInt - 1)
        val safeRight = config.rightSafeInsetPx.coerceIn(0, viewportWidthInt - safeLeft - 1)
        val safeTop = config.topSafeInsetPx.coerceIn(0, viewportHeightInt - 1)
        val safeBottom = config.bottomSafeInsetPx.coerceIn(0, viewportHeightInt - safeTop - 1)
        val availableWidth = (viewportWidth - safeLeft - safeRight).coerceAtLeast(1f)
        val availableHeight = (viewportHeight - safeTop - safeBottom).coerceAtLeast(1f)
        var width = itemWidth.coerceAtMost(availableWidth)
        var height = itemHeight.coerceAtMost(availableHeight)
        if (config.deviceShape == NativeDeviceShape.ROUND) {
            val radius = minOf(availableWidth, availableHeight) / 2f
            val halfHypot = kotlin.math.hypot(width / 2f, height / 2f)
            if (halfHypot > radius && halfHypot.isFinite() && halfHypot > 0f) {
                val factor = (radius / halfHypot).coerceIn(0f, 1f)
                width *= factor
                height *= factor
            }
        }
        safeItemWidth = width.coerceAtLeast(0.01f)
        safeItemHeight = height.coerceAtLeast(0.01f)
    }
}

private data class ViewportSafeInsets(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun NativeAppListConfig.safeInsetsForViewport(
    viewportWidth: Float,
    viewportHeight: Float,
): ViewportSafeInsets {
    val width = viewportWidth.toInt().coerceAtLeast(1)
    val height = viewportHeight.toInt().coerceAtLeast(1)
    val left = leftSafeInsetPx.coerceIn(0, width - 1).toFloat()
    val right = rightSafeInsetPx.coerceIn(0, (width - left).toInt() - 1).toFloat()
    val top = topSafeInsetPx.coerceIn(0, height - 1).toFloat()
    val bottom = bottomSafeInsetPx.coerceIn(0, (height - top).toInt() - 1).toFloat()
    return ViewportSafeInsets(left, top, right, bottom)
}

internal fun NativeAppListConfig.toDeviceProfile(
    viewportWidth: Float? = null,
    viewportHeight: Float? = null,
): DeviceProfile {
    val safe = if (
        viewportWidth != null && viewportHeight != null &&
        viewportWidth.isFinite() && viewportHeight.isFinite() &&
        viewportWidth > 0f && viewportHeight > 0f
    ) {
        safeInsetsForViewport(viewportWidth, viewportHeight)
    } else {
        ViewportSafeInsets(
            left = leftSafeInsetPx.toFloat(),
            top = topSafeInsetPx.toFloat(),
            right = rightSafeInsetPx.toFloat(),
            bottom = bottomSafeInsetPx.toFloat(),
        )
    }
    return when (deviceShape) {
    NativeDeviceShape.RECTANGULAR -> DeviceProfile.Rectangular(
        safeInsets = com.flue.launcher.applist.geometry.SafeInsetsPx(
            left = safe.left,
            top = safe.top,
            right = safe.right,
            bottom = safe.bottom,
        ),
    )

    NativeDeviceShape.ROUND -> DeviceProfile.Round(
        safeInsets = com.flue.launcher.applist.geometry.SafeInsetsPx(
            left = safe.left,
            top = safe.top,
            right = safe.right,
            bottom = safe.bottom,
        ),
    )
}
}

internal fun NativeAppListConfig.toFisheyeParameters(): FisheyeParameters {
    val safeStrength = fisheyeStrength.takeIf(Float::isFinite)?.coerceIn(0f, 2f) ?: 0f
    if (!fisheyeEnabled) {
        return FisheyeParameters.Classic(
            minScale = 1f,
            minAlpha = 1f,
            maximumSpacingCompression = 0f,
        )
    }
    return when (fisheyePreset) {
        NativeFisheyePreset.CLASSIC -> FisheyeParameters.Classic(
            minScale = (1f - 0.45f * safeStrength).coerceIn(0f, 1f),
            minAlpha = (1f - 0.35f * safeStrength).coerceIn(0f, 1f),
            // Classic is the Beta 1.2-compatible center-line curve. Its
            // spacing remains stable; only scale/alpha change at the edge.
            effectRadiusFraction = 1f,
            maximumSpacingCompression = 0f,
            maximumBlur = com.flue.launcher.applist.geometry.BlurBucket.NONE,
        )

        NativeFisheyePreset.NEXT -> FisheyeParameters.Next(
            minScale = (1f - 0.55f * safeStrength).coerceIn(0f, 1f),
            minAlpha = (1f - 0.45f * safeStrength).coerceIn(0f, 1f),
            stableRadiusFraction = (1f - fisheyeRange)
                .takeIf(Float::isFinite)
                ?.coerceIn(0f, 0.99f)
                ?: 0.6f,
            maximumSpacingCompression = if (edgeSpacingCompressionEnabled) {
                (0.30f * safeStrength).coerceIn(0f, 0.30f)
            } else {
                0f
            },
            maximumBlur = com.flue.launcher.applist.geometry.BlurBucket.NONE,
        )
    }
}
