package com.flue.launcher.applist.geometry

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LinearItemPlacement(
    val key: AppItemKey,
    val contentCenter: PixelPoint,
)

data class DrawerLayoutRequest(
    val viewport: ViewportPx,
    val deviceProfile: DeviceProfile,
    val itemPlacements: List<LinearItemPlacement>,
    val scrollOffsetPx: Float,
    val itemSize: PixelSize,
    val fisheye: FisheyeParameters,
) {
    init {
        require(scrollOffsetPx.isFinite()) {
            "Scroll offset must be finite"
        }
        require(itemPlacements.map { it.key }.distinct().size == itemPlacements.size) {
            "Layout item keys must be unique"
        }
    }
}

data class DrawerItemGeometry(
    val key: AppItemKey,
    val bounds: PixelRect,
    val scale: Float,
    val alpha: Float,
    val blurBucket: BlurBucket,
    val hitRegion: PixelRect,
    val zOrder: Float,
    val launchOrigin: NormalizedPoint,
)

/**
 * Reusable, allocation-free result used by the native View hot path.
 *
 * [DrawerLayoutEngine.layout] intentionally keeps returning immutable geometry
 * for snapshot/tests. Native adapters use [DrawerLayoutSession.resolve] with
 * one caller-owned instance instead, so a scroll frame does not allocate a
 * list, a rect, or a point for every attached icon.
 */
internal class MutableDrawerItemGeometry(
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var scale: Float = 1f,
    var alpha: Float = 1f,
    var spacingCompression: Float = 0f,
    var effectProgress: Float = 0f,
    var blurBucket: BlurBucket = BlurBucket.NONE,
) {
    fun reset(centerX: Float, centerY: Float): MutableDrawerItemGeometry {
        this.centerX = centerX
        this.centerY = centerY
        scale = 1f
        alpha = 1f
        spacingCompression = 0f
        effectProgress = 0f
        blurBucket = BlurBucket.NONE
        return this
    }
}

/**
 * Geometry snapshot for one viewport/configuration.
 *
 * The outline and fish-eye parameters are built once when a LayoutManager's
 * viewport or settings change. [resolve] then only performs scalar math and
 * writes to the supplied output object.
 */
internal class DrawerLayoutSession internal constructor(
    private val viewport: ViewportPx,
    private val outline: SafeOutline,
    private val itemSize: PixelSize,
    private val fisheye: FisheyeParameters,
) {
    fun resolve(
        desiredCenterX: Float,
        desiredCenterY: Float,
        output: MutableDrawerItemGeometry,
    ): MutableDrawerItemGeometry {
        if (!desiredCenterX.isFinite() || !desiredCenterY.isFinite()) {
            return output.reset(desiredCenterX, desiredCenterY)
        }
        val fisheyeVisuals = calculateFisheyeVisuals(
            screenCenterY = desiredCenterY,
            safeCenterY = outline.center.y,
            safeHalfHeight = outline.halfHeight,
            parameters = fisheye,
        )
        val compressedCenterX = desiredCenterX
        val compressedCenterY = outline.center.y +
            (desiredCenterY - outline.center.y) *
            (1f - fisheyeVisuals.spacingCompression)
        outline.constrainCenter(
            desiredCenterX = compressedCenterX,
            desiredCenterY = compressedCenterY,
            hitSize = itemSize,
            output = output,
        )
        output.scale = fisheyeVisuals.scale
        output.alpha = fisheyeVisuals.alpha
        output.spacingCompression = fisheyeVisuals.spacingCompression
        output.effectProgress = fisheyeVisuals.effectProgress
        output.blurBucket = blurBucketFor(
            progress = fisheyeVisuals.effectProgress,
            maximum = fisheye.maximumBlur,
        )
        return output
    }

    val viewportWidth: Float
        get() = viewport.width

    val viewportHeight: Float
        get() = viewport.height
}

class DrawerLayoutEngine {
    /** Builds a reusable geometry snapshot for native View adapters. */
    internal fun session(request: DrawerLayoutRequest): DrawerLayoutSession =
        DrawerLayoutSession(
            viewport = request.viewport,
            outline = SafeOutline.create(
                viewport = request.viewport,
                deviceProfile = request.deviceProfile,
                itemSize = request.itemSize,
            ),
            itemSize = request.itemSize,
            fisheye = request.fisheye,
        )

    fun layout(request: DrawerLayoutRequest): List<DrawerItemGeometry> {
        val session = session(request)
        return request.itemPlacements.map { placement ->
            val screenCenter = PixelPoint(
                x = placement.contentCenter.x,
                y = placement.contentCenter.y - request.scrollOffsetPx,
            )
            val resolved = session.resolve(
                desiredCenterX = screenCenter.x,
                desiredCenterY = screenCenter.y,
                output = MutableDrawerItemGeometry(),
            )
            val bounds = PixelRect.centeredAt(
                center = PixelPoint(resolved.centerX, resolved.centerY),
                size = request.itemSize.scaledBy(resolved.scale),
            )
            val hitRegion = PixelRect.centeredAt(
                center = PixelPoint(resolved.centerX, resolved.centerY),
                size = request.itemSize,
            )

            DrawerItemGeometry(
                key = placement.key,
                bounds = bounds,
                scale = resolved.scale,
                alpha = resolved.alpha,
                blurBucket = resolved.blurBucket,
                hitRegion = hitRegion,
                zOrder = 1f - resolved.effectProgress,
                launchOrigin = NormalizedPoint(
                    x = (bounds.centerX / request.viewport.width).coerceIn(0f, 1f),
                    y = (bounds.centerY / request.viewport.height).coerceIn(0f, 1f),
                ),
            )
        }
    }
}

private data class FisheyeVisuals(
    val scale: Float,
    val alpha: Float,
    val spacingCompression: Float,
    val effectProgress: Float,
)

private fun calculateFisheyeVisuals(
    screenCenterY: Float,
    safeCenterY: Float,
    safeHalfHeight: Float,
    parameters: FisheyeParameters,
): FisheyeVisuals {
    val distanceFromCenter = abs(screenCenterY - safeCenterY)
    val effectProgress = when (parameters) {
        is FisheyeParameters.Classic -> {
            val effectRadius = safeHalfHeight * parameters.effectRadiusFraction
            (distanceFromCenter / effectRadius).coerceIn(0f, 1f)
        }

        is FisheyeParameters.Next -> {
            val stableRadius = safeHalfHeight * parameters.stableRadiusFraction
            val edgeBandSize = safeHalfHeight - stableRadius
            val linearProgress =
                ((distanceFromCenter - stableRadius) / edgeBandSize).coerceIn(0f, 1f)
            smoothstep(linearProgress)
        }
    }
    return FisheyeVisuals(
        scale = lerp(1f, parameters.minScale, effectProgress),
        alpha = lerp(1f, parameters.minAlpha, effectProgress),
        spacingCompression = parameters.maximumSpacingCompression * effectProgress,
        effectProgress = effectProgress,
    )
}

private fun smoothstep(progress: Float): Float =
    progress * progress * (3f - 2f * progress)

private fun lerp(
    start: Float,
    end: Float,
    fraction: Float,
): Float = start + (end - start) * fraction

private fun blurBucketFor(
    progress: Float,
    maximum: BlurBucket,
): BlurBucket {
    if (progress <= 0f || maximum == BlurBucket.NONE) return BlurBucket.NONE
    val candidates = BlurBucket.entries.takeWhile { it.radiusDp <= maximum.radiusDp }
    val index = (progress.coerceIn(0f, 1f) * candidates.lastIndex).roundToInt()
    return candidates[index]
}

internal sealed interface SafeOutline {
    val center: PixelPoint
    val halfHeight: Float

    fun constrainCenter(
        desiredCenterX: Float,
        desiredCenterY: Float,
        hitSize: PixelSize,
        output: MutableDrawerItemGeometry,
    )

    data class Rectangle(
        val safeBounds: PixelRect,
    ) : SafeOutline {
        override val center: PixelPoint = PixelPoint(
            x = safeBounds.centerX,
            y = safeBounds.centerY,
        )
        override val halfHeight: Float = safeBounds.height / 2f

        override fun constrainCenter(
            desiredCenterX: Float,
            desiredCenterY: Float,
            hitSize: PixelSize,
            output: MutableDrawerItemGeometry,
        ) {
            val halfWidth = hitSize.width / 2f
            val halfItemHeight = hitSize.height / 2f
            output.centerX = desiredCenterX.coerceIn(
                minimumValue = safeBounds.left + halfWidth,
                maximumValue = safeBounds.right - halfWidth,
            )
            output.centerY = desiredCenterY.coerceIn(
                minimumValue = safeBounds.top + halfItemHeight,
                maximumValue = safeBounds.bottom - halfItemHeight,
            )
        }
    }

    data class Circle(
        override val center: PixelPoint,
        val radius: Float,
    ) : SafeOutline {
        override val halfHeight: Float = radius

        override fun constrainCenter(
            desiredCenterX: Float,
            desiredCenterY: Float,
            hitSize: PixelSize,
            output: MutableDrawerItemGeometry,
        ) {
            val halfWidth = hitSize.width / 2f
            val halfItemHeight = hitSize.height / 2f
            val maximumVerticalOffset = (
                sqrt((radius * radius - halfWidth * halfWidth).coerceAtLeast(0f)) -
                    halfItemHeight
                ).coerceAtLeast(0f)
            val constrainedY = desiredCenterY.coerceIn(
                minimumValue = center.y - maximumVerticalOffset,
                maximumValue = center.y + maximumVerticalOffset,
            )
            val farthestVerticalEdge =
                abs(constrainedY - center.y) + halfItemHeight
            val halfChord = sqrt(
                (radius * radius - farthestVerticalEdge * farthestVerticalEdge)
                    .coerceAtLeast(0f),
            )
            val maximumHorizontalOffset = (halfChord - halfWidth).coerceAtLeast(0f)
            output.centerX = desiredCenterX.coerceIn(
                minimumValue = center.x - maximumHorizontalOffset,
                maximumValue = center.x + maximumHorizontalOffset,
            )
            output.centerY = constrainedY
        }
    }

    companion object {
        fun create(
            viewport: ViewportPx,
            deviceProfile: DeviceProfile,
            itemSize: PixelSize,
        ): SafeOutline {
            val insets = deviceProfile.safeInsets
            val safeBounds = PixelRect(
                left = insets.left,
                top = insets.top,
                right = viewport.width - insets.right,
                bottom = viewport.height - insets.bottom,
            )
            require(safeBounds.width > 0f && safeBounds.height > 0f) {
                "Safe insets must leave a non-empty viewport"
            }
            return when (deviceProfile) {
                is DeviceProfile.Rectangular -> {
                    require(
                        itemSize.width <= safeBounds.width &&
                            itemSize.height <= safeBounds.height,
                    ) {
                        "Item hit size must fit inside the rectangular safe area"
                    }
                    Rectangle(safeBounds)
                }

                is DeviceProfile.Round -> {
                    val radius = minOf(safeBounds.width, safeBounds.height) / 2f
                    require(
                        hypot(itemSize.width / 2f, itemSize.height / 2f) <= radius,
                    ) {
                        "Item hit size must fit inside the round safe area"
                    }
                    Circle(
                        center = PixelPoint(
                            x = safeBounds.centerX,
                            y = safeBounds.centerY,
                        ),
                        radius = radius,
                    )
                }
            }
        }
    }
}
