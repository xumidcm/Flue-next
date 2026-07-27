package com.flue.launcher.ui.anim

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.toIntSize

/**
 * clox 式覆层背衬：表盘按进度下压缩小时，边缘让出的区域用「表盘内容的
 * 放大模糊副本」向外填充，而不是露出纯黑底。
 *
 * 实现：把（已被 scaleBlurAlpha 变换过的）内容录进一个 GraphicsLayer，
 * 先画一份 1.62 倍放大、重模糊、压暗的副本铺底，再原样画一遍真实内容。
 * 录制只发生在绘制阶段，零重组；progress 走 lambda 逐帧读。
 *
 * 仅 API 31+ 且允许模糊时启用；否则边缘由覆层玻璃自身的暗色兜底。
 * 注意：GraphicsLayer.record 录不到 SurfaceView / 视频内容——这类表盘的
 * 副本区域是空的，观感退化为普通压暗，不炸不闪。
 */
@Composable
fun Modifier.flueBackdropFill(
    progress: () -> Float,
    enabled: Boolean,
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val backdropLayer = rememberGraphicsLayer()
    return this.drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0.001f) {
            drawContent()
            return@drawWithContent
        }
        backdropLayer.record(this, layoutDirection, size.toIntSize()) {
            this@drawWithContent.drawContent()
        }
        backdropLayer.renderEffect = BlurEffect(
            radiusX = 64f * p + 12f,
            radiusY = 64f * p + 12f,
            edgeTreatment = TileMode.Clamp,
        )
        // 副本必须不透明：半透明副本会让黑底透出来——表盘下压后边缘
        // 「露出黑色底边」的直接原因。像素延展全靠这份放大副本。
        backdropLayer.alpha = 1f
        clipRect {
            scale(scale = 1.62f) {
                drawLayer(backdropLayer)
            }
        }
        drawContent()
    }
}
