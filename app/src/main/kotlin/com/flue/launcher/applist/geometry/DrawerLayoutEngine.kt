package com.flue.launcher.applist.geometry

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

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
        /**
         * 这一格实际要留位的命中尺寸，默认取会话建立时的 item 尺寸。
         *
         * 单独开成入参而不是塞进会话：鱼眼让**每个子项**的画出尺寸都不同，若把它计入
         * 会话的重建判据，滚动一帧内扫过的每个子项都会重建一次 SafeOutline —— 这个类
         * 存在的唯一理由就是热路径零分配。轮廓几何（圆心 / 半径、安全矩形）本来就与
         * item 尺寸无关，尺寸只在 [SafeOutline.constrainCenter] 里参与留位计算。
         */
        hitWidth: Float = itemSize.width,
        hitHeight: Float = itemSize.height,
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
            hitWidth = hitWidth.takeIf { it.isFinite() && it > 0f } ?: itemSize.width,
            hitHeight = hitHeight.takeIf { it.isFinite() && it > 0f } ?: itemSize.height,
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

/**
 * 圆屏轮廓的**软钳**：把任意纵向偏移压进 `(-limit, limit)`，且对输入**严格单调**。
 *
 * ## 为什么不能用饱和的 coerceIn
 *
 * 原来这里是 `desiredCenterY.coerceIn(center.y ± limit)`。饱和钳位在越界区是常函数 ——
 * 所有超出的行被映射到**同一个 Y**，于是顶部（或底部）连续几行的图标叠在一条线上，
 * 正是老师报的「圆屏顶上和底下会有图标重叠」。行的先后顺序也被抹平，滚动时相邻两行
 * 会互相穿过。
 *
 * ## 形状
 *
 * 内侧 [SOFT_CLAMP_KNEE_FRACTION] 那一段保持**恒等**（正常可见的行不受任何影响，
 * 观感与从前一致），越过膝点之后把剩下的 `span = limit - knee` 那点余量用 `tanh`
 * 摊给整条无穷尾巴：
 *
 * ```
 * |y| <= knee :  y
 * |y| >  knee :  knee + span * tanh((|y| - knee) / span)
 * ```
 *
 * 三条性质正好是需要的：
 *
 * 1. `tanh` 严格递增 ⇒ 整个映射对输入严格单调 ⇒ **行序永不反转、越界行也不塌成一点**；
 * 2. `tanh < 1` ⇒ 结果严格小于 `limit` ⇒ 命中框四角仍在内切圆内（`constrainCenter`
 *    的横向余量是按这个结果反算的弦宽，`limit` 一旦被越过弦宽就会小于图标半宽）；
 * 3. 膝点处两段取值相同（都等于 `knee`），映射连续，不会在边界跳一下。
 *
 * `limit <= 0`（图标恰好塞满内切圆）是退化情形：没有任何余量可摊，只能钉在圆心。
 */
private fun softClampOffset(offset: Float, limit: Float): Float {
    if (!offset.isFinite()) return 0f
    if (limit <= 0f || !limit.isFinite()) return 0f
    val magnitude = abs(offset)
    if (magnitude <= 0f) return 0f
    val knee = limit * SOFT_CLAMP_KNEE_FRACTION
    val span = limit - knee
    val softMagnitude = if (magnitude <= knee || span <= 0f) {
        magnitude.coerceAtMost(limit)
    } else {
        knee + span * tanh((magnitude - knee) / span)
    }
    return if (offset < 0f) -softMagnitude else softMagnitude
}

/**
 * 软钳的膝点占可用余量的比例。
 *
 * 0.75 是折中：再高（比如 0.9）尾段只剩 10% 余量可摊，深度越界的相邻行差值会掉到
 * 亚像素级，重叠虽然消失但看起来仍像贴在一起；再低（比如 0.5）就会开始压缩**本来
 * 完全可见**的中段行距，圆屏列表的行距观感会变。
 */
private const val SOFT_CLAMP_KNEE_FRACTION = 0.75f

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
        hitWidth: Float,
        hitHeight: Float,
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
            hitWidth: Float,
            hitHeight: Float,
            output: MutableDrawerItemGeometry,
        ) {
            val halfWidth = hitWidth / 2f
            val halfItemHeight = hitHeight / 2f
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
            hitWidth: Float,
            hitHeight: Float,
            output: MutableDrawerItemGeometry,
        ) {
            val halfWidth = hitWidth / 2f
            val halfItemHeight = hitHeight / 2f
            val maximumVerticalOffset = (
                sqrt((radius * radius - halfWidth * halfWidth).coerceAtLeast(0f)) -
                    halfItemHeight
                ).coerceAtLeast(0f)
            val constrainedY = center.y + softClampOffset(
                offset = desiredCenterY - center.y,
                limit = maximumVerticalOffset,
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
