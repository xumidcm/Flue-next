package com.flue.launcher.applist.background

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import com.flue.launcher.ui.anim.systemBlurEffectForRadiusPx
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「这一帧的应用列表背景该画成什么样」的完整描述。
 *
 * 抽成一个值对象是因为背景有**两个**绘制点：`LauncherActivity` 里那块不缩放的背板
 * （转场期间露出来的就是它）和 `NativeListDrawerScreen` 的层内底色。两处必须画得一模一样，
 * 否则缩放中途会看到两种背景的边界 —— 所以配置解析、图源、遮罩只算一次，两处共用。
 */
@Stable
data class AppListBackgroundPainting(
    val config: AppListBackgroundConfig = AppListBackgroundConfig.Default,
    /** 已解码（且在 30 及以下已烘焙好模糊）的底图。`null` = 还没准备好或不需要。 */
    val image: ImageBitmap? = null,
    /** 兜底纯色。图源没准备好、解码失败、纯色档都用它 —— 绝不出现透明或黑闪。 */
    val fallbackColor: Color = Color.Black,
    val darkMode: Boolean = true,
) {
    /** 这一帧到底画不画图。图没准备好就当纯色档处理。 */
    val usesImage: Boolean
        get() = config.effectiveMode.usesImage && image != null

    /** 纯色档（含所有兜底路径）的底色。 */
    val solidColor: Color
        get() = appListBackgroundSolidColor(config, fallbackColor)

    /**
     * 压在底图上、图标下的那层遮罩。
     *
     * 必须是**独立一层**而不是把颜色混进底色里：混进去纯色档也会跟着变暗，而遮罩的语义
     * 只是「压暗底图让图标文字读得清」。
     */
    val scrimColor: Color
        get() = Color(appListBackgroundScrimRgb(darkMode) or OPAQUE_ALPHA)
            .copy(alpha = appListBackgroundScrimAlpha(config.scrimPercent))

    /**
     * 绘制期要施加的模糊半径（dp）。
     *
     * 30 及以下恒为 0 —— 那边的模糊已经预烘焙进 [image] 了（见
     * [rememberAppListBackgroundImage]），再糊一遍就是双重代价。
     */
    val liveBlurRadiusDp: Float
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appListBackgroundBlurBucketDp(config.blurPercent)
        } else {
            0f
        }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF shl 24
    }
}

/**
 * 这份配置对应的**纯色底**。
 *
 * 除了背景本身，启动遮罩与直启列表的底板也要读它 —— 否则自定义成红底之后，打开应用会
 * 先闪一下系统黑白再换过来。这些调用点在 `BoxWithConstraints` 外面拿不到
 * [AppListBackgroundPainting]，所以抽成一个独立函数。
 */
fun appListBackgroundSolidColor(
    config: AppListBackgroundConfig,
    fallbackColor: Color,
): Color = if (config.effectiveMode == AppListBackgroundMode.SOLID_CUSTOM) {
    Color(config.customColorArgb)
} else {
    fallbackColor
}

/**
 * 组装这一帧的背景描述。图源解析、解码、烘焙全在里面，调用点只要把表盘信息传进来。
 *
 * @param viewportWidthPx / [viewportHeightPx] 视口像素尺寸，解码按它降采样。
 */
@Composable
fun rememberAppListBackgroundPainting(
    config: AppListBackgroundConfig,
    fallbackColor: Color,
    darkMode: Boolean,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    watchFaceId: String,
    watchFaceStableKey: String,
    watchFacePreviewPath: String?,
    builtInPhotoPath: String?,
    builtInVideoPath: String?,
    watchFaceRefreshToken: Int,
    blurPercentOverride: Int? = null,
): AppListBackgroundPainting {
    val source = remember(
        config.effectiveMode,
        config.imagePath,
        watchFaceId,
        watchFaceStableKey,
        watchFacePreviewPath,
        builtInPhotoPath,
        builtInVideoPath,
        watchFaceRefreshToken,
    ) {
        appListBackgroundImageSourceFor(
            config = config,
            watchFaceId = watchFaceId,
            watchFaceStableKey = watchFaceStableKey,
            watchFacePreviewPath = watchFacePreviewPath,
            builtInPhotoPath = builtInPhotoPath,
            builtInVideoPath = builtInVideoPath,
            refreshToken = watchFaceRefreshToken,
        )
    }
    val image = rememberAppListBackgroundImage(
        source = source,
        targetWidth = viewportWidthPx,
        targetHeight = viewportHeightPx,
        blurPercent = blurPercentOverride ?: config.blurPercent,
    )
    return remember(config, image, fallbackColor, darkMode) {
        AppListBackgroundPainting(
            config = config,
            image = image,
            fallbackColor = fallbackColor,
            darkMode = darkMode,
        )
    }
}

/**
 * 把背景画在这个节点的**内容之下**。
 *
 * 用 `drawBehind` 而不是包一层 Box：两个调用点原本都是 `.background(color)`，换成这个
 * 修饰符是逐位等价的替换。更重要的是模糊**只作用在底图上** —— 如果把 `renderEffect` 挂到
 * 节点自己的 `graphicsLayer` 上，应用图标会跟着一起糊。
 *
 * ## 为什么所有 `remember` 都无条件先算
 *
 * 这个修饰符会因为「模式切了 / 图刚解码完 / 模糊拖到 0」而在同一个位置反复改变分支。
 * 把 `rememberGraphicsLayer` 放进条件分支就等于让组合结构跟着状态变，早退 + 条件
 * `remember` 混在一起最容易踩组不匹配。代价只是常驻一个 RenderNode，两个调用点而已。
 *
 * @param alpha 整层不透明度。`NativeListDrawerScreen` 用它做入场渐显，背板用 1f。
 * @param blurScale 模糊半径的缩放倍率，只给**缩微预览**用（见
 *   [AppListBackgroundPreviewCard]）：把整屏缩小 k 倍显示时，模糊也得跟着缩 k 倍才像同一块
 *   屏幕。两个真实绘制点都用 1f。夹在 0..1 是因为它的语义只有「缩小」，放大没有意义，
 *   传错一个大数就会变成一次极端半径的全屏模糊。
 */
@Composable
fun Modifier.appListBackground(
    painting: AppListBackgroundPainting,
    alpha: Float = 1f,
    blurScale: Float = 1f,
): Modifier {
    val blurLayer = rememberGraphicsLayer()
    val blurRadiusPx = with(LocalDensity.current) {
        (painting.liveBlurRadiusDp * blurScale.coerceIn(0f, 1f)).dp.toPx()
    }.roundToInt()
    val useLiveBlur = blurRadiusPx >= 1 &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        painting.usesImage
    val blurEffect = remember(blurRadiusPx, useLiveBlur) {
        if (useLiveBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            systemBlurEffectForRadiusPx(blurRadiusPx)
        } else {
            null
        }
    }
    val effectiveAlpha = alpha.coerceIn(0f, 1f)
    val image = painting.image
    val usesImage = painting.usesImage
    val solid = painting.solidColor
    val scrim = painting.scrimColor
    return this.drawBehind {
        if (effectiveAlpha <= 0.001f) return@drawBehind
        // 先铺兜底纯色：底图是 center-crop 的，理论上盖满，但模糊 + 极端宽高比下
        // 边缘仍可能露一线。露出纯色远好过露出下面的表盘。
        drawRect(solid.copy(alpha = solid.alpha * effectiveAlpha))
        if (!usesImage || image == null) return@drawBehind
        if (blurEffect != null) {
            blurLayer.record(this, layoutDirection, size.toIntSize()) {
                drawImageCropped(image, alpha = 1f)
            }
            blurLayer.renderEffect = blurEffect
            blurLayer.alpha = effectiveAlpha
            drawLayer(blurLayer)
        } else {
            drawImageCropped(image, alpha = effectiveAlpha)
        }
        if (scrim.alpha > 0.001f) {
            drawRect(scrim.copy(alpha = scrim.alpha * effectiveAlpha))
        }
    }
}

/**
 * center-crop 地画一张图：按短边撑满、长边居中裁掉多余部分。
 *
 * 不用 `ContentScale.Crop` 是因为这里在 `DrawScope` 里、没有 Composable 可用；而
 * `drawImage(srcOffset, srcSize, dstSize)` 正好能表达同一件事，且不额外分配。
 */
private fun DrawScope.drawImageCropped(image: ImageBitmap, alpha: Float) {
    if (image.width <= 0 || image.height <= 0) return
    if (size.width <= 0f || size.height <= 0f) return
    val scale = max(size.width / image.width, size.height / image.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
