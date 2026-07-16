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

class DrawerLayoutEngine {
    fun layout(request: DrawerLayoutRequest): List<DrawerItemGeometry> {
        val outline = SafeOutline.create(
            viewport = request.viewport,
            deviceProfile = request.deviceProfile,
            itemSize = request.itemSize,
        )
        return request.itemPlacements.map { placement ->
            val screenCenter = PixelPoint(
                x = placement.contentCenter.x,
                y = placement.contentCenter.y - request.scrollOffsetPx,
            )
            val fisheyeVisuals = calculateFisheyeVisuals(
                screenCenterY = screenCenter.y,
                safeCenterY = outline.center.y,
                safeHalfHeight = outline.halfHeight,
                parameters = request.fisheye,
            )
            val compressedCenter = PixelPoint(
                x = screenCenter.x,
                y = outline.center.y +
                    (screenCenter.y - outline.center.y) *
                    (1f - fisheyeVisuals.spacingCompression),
            )
            val finalCenter = outline.constrainCenter(
                desiredCenter = compressedCenter,
                hitSize = request.itemSize,
            )
            val bounds = PixelRect.centeredAt(
                center = finalCenter,
                size = request.itemSize.scaledBy(fisheyeVisuals.scale),
            )
            val hitRegion = PixelRect.centeredAt(
                center = finalCenter,
                size = request.itemSize,
            )

            DrawerItemGeometry(
                key = placement.key,
                bounds = bounds,
                scale = fisheyeVisuals.scale,
                alpha = fisheyeVisuals.alpha,
                blurBucket = blurBucketFor(
                    progress = fisheyeVisuals.effectProgress,
                    maximum = request.fisheye.maximumBlur,
                ),
                hitRegion = hitRegion,
                zOrder = 1f - fisheyeVisuals.effectProgress,
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

private sealed interface SafeOutline {
    val center: PixelPoint
    val halfHeight: Float

    fun constrainCenter(
        desiredCenter: PixelPoint,
        hitSize: PixelSize,
    ): PixelPoint

    data class Rectangle(
        val safeBounds: PixelRect,
    ) : SafeOutline {
        override val center: PixelPoint = PixelPoint(
            x = safeBounds.centerX,
            y = safeBounds.centerY,
        )
        override val halfHeight: Float = safeBounds.height / 2f

        override fun constrainCenter(
            desiredCenter: PixelPoint,
            hitSize: PixelSize,
        ): PixelPoint {
            val halfWidth = hitSize.width / 2f
            val halfItemHeight = hitSize.height / 2f
            return PixelPoint(
                x = desiredCenter.x.coerceIn(
                    minimumValue = safeBounds.left + halfWidth,
                    maximumValue = safeBounds.right - halfWidth,
                ),
                y = desiredCenter.y.coerceIn(
                    minimumValue = safeBounds.top + halfItemHeight,
                    maximumValue = safeBounds.bottom - halfItemHeight,
                ),
            )
        }
    }

    data class Circle(
        override val center: PixelPoint,
        val radius: Float,
    ) : SafeOutline {
        override val halfHeight: Float = radius

        override fun constrainCenter(
            desiredCenter: PixelPoint,
            hitSize: PixelSize,
        ): PixelPoint {
            val halfWidth = hitSize.width / 2f
            val halfItemHeight = hitSize.height / 2f
            val maximumVerticalOffset = (
                sqrt((radius * radius - halfWidth * halfWidth).coerceAtLeast(0f)) -
                    halfItemHeight
                ).coerceAtLeast(0f)
            val constrainedY = desiredCenter.y.coerceIn(
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
            return PixelPoint(
                x = desiredCenter.x.coerceIn(
                    minimumValue = center.x - maximumHorizontalOffset,
                    maximumValue = center.x + maximumHorizontalOffset,
                ),
                y = constrainedY,
            )
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
