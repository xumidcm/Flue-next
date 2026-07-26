package com.flue.launcher.ui.common

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.anim.quantizeSystemBlurRadiusDp
import com.flue.launcher.ui.anim.systemBlurEffectForRadiusPx
import kotlin.math.roundToInt

/**
 * 镜像外扩填充背衬。
 *
 * [content] 随进度缩小 + 下沉 + 模糊时，外圈会用**同一份内容的放大重模糊副本**铺满，
 * 因此永远不会露出底色。用法：主线把表盘层塞进 [content]，滑动进度由
 * [OverlayBackdropController] 提供。
 *
 * 三个踩过的坑，改这里之前先读：
 *
 * 1. **镜像层必须比裁剪区大**。`RenderEffect` 的高斯模糊会把图层自身边缘往外采样，
 *    边缘像素因此变半透明；镜像层若和裁剪区一样大，那一圈半透明会直接露出底色 ——
 *    这就是「黑边」。这里用 [OverlayBackdropDefaults.MirrorOverscan]（1.62×）外扩，
 *    并且 blur 用 `Shader.TileMode.CLAMP`（见 `BlurSupport.systemBlurEffectForRadiusPx`）。
 * 2. **压暗必须是独立的黑色 scrim 层**，不能用 brightness / colorFilter 压在镜像上：
 *    压在镜像上会让外扩的那一圈整体变暗，看起来还是黑边。
 * 3. **API < 31 不许用 cloudy**（位图回读，手表上直接掉帧）。降级方案是纯色径向渐变。
 *
 * 内容只**记录一次**（`GraphicsLayer.record`），镜像层复用同一份录制，
 * 不会把 [content] 组合两遍 —— 表盘里可能有 SurfaceView / 视频，组合两遍是灾难。
 * 代价是镜像层用的是上一帧的录制；对一个重度模糊的外圈来说不可见。
 */
@Composable
fun MirrorFillBackdrop(
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
    blurEnabled: Boolean = true,
    fallbackTint: Color = Color.Black,
    content: @Composable BoxScope.() -> Unit,
) {
    val contentLayer = rememberGraphicsLayer()
    val systemBlurAvailable = blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val controller = LocalOverlayBackdrop.current
    // 告诉覆层「表盘背衬已经接上了」，它们就不用再自己补兜底压暗。
    DisposableEffect(controller) {
        controller.setBackdropAttached(true)
        onDispose { controller.setBackdropAttached(false) }
    }

    Box(modifier = modifier) {
        // 1) 镜像外扩层。放大 1.62× 后再重模糊，铺满 content 缩小后让出来的外圈。
        if (systemBlurAvailable) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val progress = progressProvider()
                        // 起手就要有东西垫在外圈，所以透明度爬得比缩放快。
                        alpha = (overlayBackdropEase(progress) * 3.2f).coerceIn(0f, 1f)
                        scaleX = OverlayBackdropDefaults.MirrorOverscan
                        scaleY = OverlayBackdropDefaults.MirrorOverscan
                        clip = false
                        val radiusPx = quantizeSystemBlurRadiusDp(overlayMirrorBlurDp(progress)).dp
                            .toPx()
                            .roundToInt()
                            .coerceAtLeast(1)
                        renderEffect =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                systemBlurEffectForRadiusPx(radiusPx)
                            } else {
                                null
                            }
                    }
                    .drawBehind {
                        if (contentLayer.isReleased) return@drawBehind
                        val recorded = contentLayer.size
                        if (recorded.width <= 0 || recorded.height <= 0) return@drawBehind
                        drawLayer(contentLayer)
                    }
            )
        } else {
            // API < 31 降级：纯色径向渐变把外圈压掉，绝不走 cloudy 的位图回读。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val eased = overlayBackdropEase(progressProvider())
                        if (eased <= 0.002f) return@drawBehind
                        drawRect(
                            brush = Brush.radialGradient(
                                0.0f to fallbackTint.copy(alpha = 0f),
                                0.55f to fallbackTint.copy(alpha = 0.28f * eased),
                                1.0f to fallbackTint.copy(alpha = 0.94f * eased),
                                center = center,
                                radius = size.maxDimension * 0.72f,
                            )
                        )
                    }
            )
        }

        // 2) 独立压暗层。刻意不合进镜像层 —— 见上面第 2 条。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val scrim = overlayBackdropScrimAlpha(progressProvider())
                    if (scrim > 0.002f) drawRect(color = Color.Black, alpha = scrim)
                }
        )

        // 3) 前景：真正的内容，缩小 + 下沉 + 模糊，全部是进度的纯函数。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    applyOverlayBackdropPress(progressProvider(), blurEnabled)
                }
                .drawWithContent {
                    // 把内容录进图层再画一次：镜像层靠 drawLayer 复用这份录制，
                    // 内容只组合一次（表盘里有 SurfaceView，组合两遍会炸）。
                    // record 是 GraphicsLayer 的成员，接收 DrawScope 尺寸与布局
                    // 方向，不是 ContentDrawScope 的扩展 —— 这里必须显式调用。
                    contentLayer.record(
                        density = this,
                        layoutDirection = layoutDirection,
                        size = size.toIntSize(),
                    ) {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(contentLayer)
                },
            content = content,
        )
    }
}

/**
 * 主线接表盘层的成品入口：把表盘内容塞进 [content] 即可。
 *
 * ```kotlin
 * val backdrop = rememberOverlayBackdropController()
 * CompositionLocalProvider(LocalOverlayBackdrop provides backdrop) {
 *     OverlayBackdropHost(controller = backdrop, blurEnabled = blurEnabled) {
 *         // 原来的表盘 AnimatedContent(...)
 *     }
 *     // ... 三个覆层照旧
 * }
 * ```
 */
@Composable
fun OverlayBackdropHost(
    modifier: Modifier = Modifier,
    controller: OverlayBackdropController = LocalOverlayBackdrop.current,
    blurEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    MirrorFillBackdrop(
        progressProvider = { controller.progress },
        modifier = modifier,
        blurEnabled = blurEnabled,
        content = content,
    )
}
