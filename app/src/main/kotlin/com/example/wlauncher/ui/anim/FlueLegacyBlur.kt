package com.flue.launcher.ui.anim

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.toIntSize

/**
 * API 27–30 没有 RenderEffect，覆层拉出时底层只能保持全清晰，和 31+ 上
 * 的毛玻璃观感差一整档。这里用「多抽样涂抹」近似高斯：把内容录进一个
 * GraphicsLayer，再按环形偏移叠画若干份低透明度副本。
 *
 * 纯绘制阶段实现：不分配位图、不做 RenderScript、零重组，代价只是每帧
 * 多画 [TAP_OFFSETS] 份图层。半径按进度插值，进度为 0 时直接走原内容。
 *
 * API 31+ 上不启用——那边由 RenderEffect 走硬件混合，质量和功耗都更好。
 */
private val TAP_OFFSETS: List<Pair<Float, Float>> = listOf(
    1f to 0f,
    -1f to 0f,
    0f to 1f,
    0f to -1f,
    0.7f to 0.7f,
    -0.7f to 0.7f,
    0.7f to -0.7f,
    -0.7f to -0.7f,
)

@Composable
fun Modifier.flueLegacyBlur(
    progress: () -> Float,
    enabled: Boolean,
    advancedMaterialEnabled: Boolean = true,
    maxRadiusPx: Float = 26f,
): Modifier {
    if (
        !enabled || overlayBlurBackend(
            sdkInt = Build.VERSION.SDK_INT,
            advancedMaterialEnabled = advancedMaterialEnabled,
        ) != OverlayBlurBackend.LEGACY
    ) return this
    val blurLayer = rememberGraphicsLayer()
    return this.drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0.02f) {
            drawContent()
            return@drawWithContent
        }
        blurLayer.record(this, layoutDirection, size.toIntSize()) {
            this@drawWithContent.drawContent()
        }
        val radius = maxRadiusPx * p
        // 主副本压暗一档，抽样副本再平摊剩余不透明度：总和保持 1，
        // 否则涂抹会整体提亮，浅色主题下覆层底下会发白。
        val tapAlpha = 0.62f / TAP_OFFSETS.size
        clipRect {
            blurLayer.alpha = 0.38f
            drawLayer(blurLayer)
            blurLayer.alpha = tapAlpha
            TAP_OFFSETS.forEach { (dx, dy) ->
                translate(left = dx * radius, top = dy * radius) {
                    drawLayer(blurLayer)
                }
            }
        }
        blurLayer.alpha = 1f
    }
}
