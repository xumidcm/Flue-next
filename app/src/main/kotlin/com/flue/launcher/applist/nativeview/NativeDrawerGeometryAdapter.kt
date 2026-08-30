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
        /**
         * 这一帧图标**真正被画出来**的缩放倍数。
         *
         * 调用方传进来的 itemWidth/itemHeight 是布局值（`iconBounds`，不含 visualScale）。
         * 鱼眼把边缘行缩到 0.58 倍时，若轮廓仍按 1.0 倍留位，内切余量就少算了约 40%，
         * 边缘行被往屏内多推同样多 —— 老师报的「圆屏顶上和底下会有图标重叠」的三个成因
         * 之一。默认 1f 保住既有调用点（方屏 / 不缩放的场景本来就是 1）。
         */
        visualScale: Float = 1f,
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
        if (centerY < 0f || centerY > viewportHeight) {
            // 护栏按**布局槽位中心**判，不再按「整行完全出屏」判。
            //
            // 旧判据是 `centerY + halfItemHeight < 0`，也就是只有整行都看不见了才放行。
            // 454px 屏、96px 图标那一档，槽位在 y = −45 的那一行仍满足 −45 + 48 > 0，
            // 于是被安全轮廓从屏外**拽进屏内**钉到 53.1，跟 y = 91 那一行重叠约 38px ——
            // 这正是「顶上和底下图标重叠」的主因。槽心已经出了视口的行根本不该参与收边：
            // 它要么马上被回收，要么正随滚动进场，让它自然移动即可。
            return output.set(1f, 1f, 0f, 0f, 0f)
        }
        // 会话按**未缩放**的尺寸建立，缩放只在这一次 resolve 里生效：轮廓几何（圆心 /
        // 半径）与 item 尺寸无关，而鱼眼让每个子项的缩放都不同，把它计入会话的重建判据
        // 会让一帧里扫过的每个子项都重建一次 SafeOutline。
        ensureSession(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            itemWidth = safeItemWidth,
            itemHeight = safeItemHeight,
            config = config,
        )
        // 先按内切圆夹、再乘缩放（不是反过来）：夹取结果保证未缩放尺寸就已经塞得进圆，
        // 再往小乘必然仍然塞得进；顺序颠倒会让缩放档的图标反而拿到更宽松的留位。
        val scale = visualScale.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: 1f
        val activeSession = requireNotNull(session)
        activeSession.resolve(
            desiredCenterX = centerX,
            desiredCenterY = centerY,
            output = resolved,
            hitWidth = safeItemWidth * scale,
            hitHeight = safeItemHeight * scale,
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
        // 与 safeInsetsForViewport 走同一个夹取：两份实现各自演化的话，会给
        // SafeOutline.create 送进互相冲突的 safeBounds，圆屏布局期直接 require 失败。
        // resolve 每帧每个子项都会进来一次；insets 只随视口与 config 的 inset 值
        // 变化，按 (视口, inset) 缓存进可变对象，命中路径不再分配。
        val keyWidth = viewportWidth.toInt()
        val keyHeight = viewportHeight.toInt()
        if (
            cachedInsetsViewportWidth != keyWidth ||
            cachedInsetsViewportHeight != keyHeight ||
            cachedInsetsLeft != config.leftSafeInsetPx ||
            cachedInsetsTop != config.topSafeInsetPx ||
            cachedInsetsRight != config.rightSafeInsetPx ||
            cachedInsetsBottom != config.bottomSafeInsetPx
        ) {
            config.applySafeInsetsForViewport(viewportWidth, viewportHeight, cachedInsets)
            cachedInsetsViewportWidth = keyWidth
            cachedInsetsViewportHeight = keyHeight
            cachedInsetsLeft = config.leftSafeInsetPx
            cachedInsetsTop = config.topSafeInsetPx
            cachedInsetsRight = config.rightSafeInsetPx
            cachedInsetsBottom = config.bottomSafeInsetPx
        }
        val safe = cachedInsets
        val availableWidth = (viewportWidth - safe.left - safe.right).coerceAtLeast(1f)
        val availableHeight = (viewportHeight - safe.top - safe.bottom).coerceAtLeast(1f)
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

    private val cachedInsets = MutableViewportSafeInsets()
    private var cachedInsetsViewportWidth = Int.MIN_VALUE
    private var cachedInsetsViewportHeight = Int.MIN_VALUE
    private var cachedInsetsLeft = Int.MIN_VALUE
    private var cachedInsetsTop = Int.MIN_VALUE
    private var cachedInsetsRight = Int.MIN_VALUE
    private var cachedInsetsBottom = Int.MIN_VALUE
}

private class MutableViewportSafeInsets {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}

private data class ViewportSafeInsets(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 四条安全内缩夹到视口内的**唯一**实现。
 *
 * 原先这套夹取在本文件里有两份（`resolveSafeHitSize` 手写一遍、这里再写一遍）。
 * 两份在 `rightSafeInsetPx` 恒为 0 的年代恰好等价，一旦它变成非零就会分叉 ——
 * 内切尺寸按一套算、安全轮廓按另一套算，`DrawerLayoutEngine` 的
 * `require(safeBounds.width > 0f && ...)` 就会在圆屏布局期抛异常。
 */
private fun NativeAppListConfig.safeInsetsForViewport(
    viewportWidth: Float,
    viewportHeight: Float,
): ViewportSafeInsets {
    val output = MutableViewportSafeInsets()
    applySafeInsetsForViewport(viewportWidth, viewportHeight, output)
    return ViewportSafeInsets(output.left, output.top, output.right, output.bottom)
}

private fun NativeAppListConfig.applySafeInsetsForViewport(
    viewportWidth: Float,
    viewportHeight: Float,
    output: MutableViewportSafeInsets,
) {
    val width = viewportWidth.toInt().coerceAtLeast(1)
    val height = viewportHeight.toInt().coerceAtLeast(1)
    val left = leftSafeInsetPx.coerceIn(0, width - 1).toFloat()
    val right = rightSafeInsetPx.coerceIn(0, (width - left).toInt() - 1).toFloat()
    val top = topSafeInsetPx.coerceIn(0, height - 1).toFloat()
    val bottom = bottomSafeInsetPx.coerceIn(0, (height - top).toInt() - 1).toFloat()
    output.set(left, top, right, bottom)
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
                // 圆屏列表**不压缩间距**（2026-08 老师定稿）：向心压缩把中段几行拉密、
                // 边缘行被圆内软钳留在原位，一行密一行疏——老师先后报过「上下两角
                // 叠成一摞」「中间四个挤、剩下间距大」，根子都是它在动行距。圆屏的
                // 行距保持与滚动内容一致（均匀），边缘只靠图标缩放/淡化收尾，越界
                // 由圆内软钳兜底。方屏不走适配器，维持原档。
                if (deviceShape == NativeDeviceShape.ROUND) {
                    0f
                } else {
                    (0.30f * safeStrength).coerceIn(0f, 0.30f)
                }
            } else {
                0f
            },
            maximumBlur = com.flue.launcher.applist.geometry.BlurBucket.NONE,
        )
    }
}
