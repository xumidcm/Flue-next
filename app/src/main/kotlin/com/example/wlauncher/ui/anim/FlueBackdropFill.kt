package com.flue.launcher.ui.anim

import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import kotlin.math.max
import kotlin.math.roundToInt

private const val BACKDROP_CAPTURE_INTERVAL_MS = 1_000L
private const val BACKDROP_BLUR_STEP_PX = 2
private const val BACKDROP_MAX_BLUR_RADIUS_PX = 64
private const val LEGACY_PREBLUR_RADIUS_DP = 18f
private const val LEGACY_PREBLUR_CENTER_ALPHA = 0.20f
private const val LEGACY_PREBLUR_INNER_ALPHA = 0.50f
private const val LEGACY_PREBLUR_OUTER_ALPHA = 0.30f

private val LEGACY_PREBLUR_OFFSETS = listOf(
    1f to 0f,
    -1f to 0f,
    0f to 1f,
    0f to -1f,
    0.707f to 0.707f,
    -0.707f to 0.707f,
    0.707f to -0.707f,
    -0.707f to -0.707f,
)

/**
 * The backdrop animation is sampled every frame. Reusing a small fixed set of
 * effects keeps that sampling cheap while a two-pixel step remains visually
 * continuous on a full-screen, darkened backdrop.
 */
private object BackdropBlurEffectCache {
    private val effects = mutableMapOf<Int, RenderEffect>()

    fun get(radiusPx: Int): RenderEffect = synchronized(effects) {
        effects.getOrPut(radiusPx) {
            BlurEffect(
                radiusX = radiusPx.toFloat(),
                radiusY = radiusPx.toFloat(),
                edgeTreatment = TileMode.Mirror,
            )
        }
    }
}

internal fun quantizeBackdropBlurRadiusPx(progress: Float): Int {
    if (!progress.isFinite() || progress <= 0f) return 0
    val radius = progress.coerceIn(0f, 1f) * BACKDROP_MAX_BLUR_RADIUS_PX
    return ((radius / BACKDROP_BLUR_STEP_PX).roundToInt() * BACKDROP_BLUR_STEP_PX)
        .coerceIn(BACKDROP_BLUR_STEP_PX, BACKDROP_MAX_BLUR_RADIUS_PX)
}

internal data class LegacyBackdropBlend(
    val clearAlpha: Float,
    val blurredAlpha: Float,
)

internal fun legacyBackdropBlend(progress: Float): LegacyBackdropBlend {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return LegacyBackdropBlend(clearAlpha = 1f - p, blurredAlpha = p)
}

/**
 * Builds the fixed maximum-blur source only when the cached watch-face frame
 * changes. Transition frames later draw this layer as-is and vary only alpha.
 */
private fun DrawScope.recordLegacyBlurredBackdrop(
    targetLayer: GraphicsLayer,
    sourceLayer: GraphicsLayer,
    layerSize: IntSize,
) {
    val maxRadiusPx = LEGACY_PREBLUR_RADIUS_DP * density
    val centerPaint = Paint().apply { alpha = LEGACY_PREBLUR_CENTER_ALPHA }
    val innerPaint = Paint().apply {
        alpha = LEGACY_PREBLUR_INNER_ALPHA / LEGACY_PREBLUR_OFFSETS.size
    }
    val outerPaint = Paint().apply {
        alpha = LEGACY_PREBLUR_OUTER_ALPHA / LEGACY_PREBLUR_OFFSETS.size
    }
    targetLayer.record(this, layoutDirection, layerSize) {
        fun drawTap(dx: Float, dy: Float, paint: Paint) {
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            translate(left = dx, top = dy) {
                drawLayer(sourceLayer)
            }
            drawContext.canvas.restore()
        }

        drawTap(0f, 0f, centerPaint)
        LEGACY_PREBLUR_OFFSETS.forEach { (dx, dy) ->
            drawTap(dx * maxRadiusPx * 0.42f, dy * maxRadiusPx * 0.42f, innerPaint)
        }
        LEGACY_PREBLUR_OFFSETS.forEach { (dx, dy) ->
            drawTap(dx * maxRadiusPx, dy * maxRadiusPx, outerPaint)
        }
    }
}

/** Records one center copy plus mirrored strips/corners using a chosen source. */
private fun DrawScope.recordMirroredBackdrop(
    targetLayer: GraphicsLayer,
    sourceLayer: GraphicsLayer,
    layerSize: IntSize,
    insetScale: Float,
    translationY: Float,
) {
    val insetLeft = (size.width - size.width * insetScale) / 2f
    val insetTop = (size.height - size.height * insetScale) / 2f + translationY
    val insetRight = insetLeft + size.width * insetScale
    val insetBottom = insetTop + size.height * insetScale

    targetLayer.record(this, layoutDirection, layerSize) {
        fun drawSource(
            translateX: Float,
            translateY: Float,
            scaleX: Float,
            scaleY: Float,
        ) {
            translate(left = translateX, top = translateY) {
                scale(
                    scaleX = scaleX,
                    scaleY = scaleY,
                    pivot = Offset.Zero,
                ) {
                    drawLayer(sourceLayer)
                }
            }
        }

        clipRect(
            left = insetLeft.coerceAtLeast(0f),
            top = insetTop.coerceAtLeast(0f),
            right = insetRight.coerceAtMost(size.width),
            bottom = insetBottom.coerceAtMost(size.height),
        ) {
            drawSource(insetLeft, insetTop, insetScale, insetScale)
        }

        if (insetLeft > 0f) {
            clipRect(0f, insetTop.coerceAtLeast(0f), insetLeft, insetBottom.coerceAtMost(size.height)) {
                drawSource(insetLeft, insetTop, -insetScale, insetScale)
            }
        }
        if (insetRight < size.width) {
            clipRect(insetRight, insetTop.coerceAtLeast(0f), size.width, insetBottom.coerceAtMost(size.height)) {
                drawSource(insetRight + size.width * insetScale, insetTop, -insetScale, insetScale)
            }
        }
        if (insetTop > 0f) {
            clipRect(insetLeft.coerceAtLeast(0f), 0f, insetRight.coerceAtMost(size.width), insetTop) {
                drawSource(insetLeft, insetTop, insetScale, -insetScale)
            }
        }
        if (insetBottom < size.height) {
            clipRect(insetLeft.coerceAtLeast(0f), insetBottom, insetRight.coerceAtMost(size.width), size.height) {
                drawSource(insetLeft, insetBottom + size.height * insetScale, insetScale, -insetScale)
            }
        }
        if (insetLeft > 0f && insetTop > 0f) {
            clipRect(0f, 0f, insetLeft, insetTop) {
                drawSource(insetLeft, insetTop, -insetScale, -insetScale)
            }
        }
        if (insetRight < size.width && insetTop > 0f) {
            clipRect(insetRight, 0f, size.width, insetTop) {
                drawSource(insetRight + size.width * insetScale, insetTop, -insetScale, -insetScale)
            }
        }
        if (insetLeft > 0f && insetBottom < size.height) {
            clipRect(0f, insetBottom, insetLeft, size.height) {
                drawSource(insetLeft, insetBottom + size.height * insetScale, -insetScale, -insetScale)
            }
        }
        if (insetRight < size.width && insetBottom < size.height) {
            clipRect(insetRight, insetBottom, size.width, size.height) {
                drawSource(
                    insetRight + size.width * insetScale,
                    insetBottom + size.height * insetScale,
                    -insetScale,
                    -insetScale,
                )
            }
        }
    }
}

/** Draw-phase only state; changing it must never trigger recomposition. */
private class BackdropCaptureState {
    var hasFrame: Boolean = false
    var wasActive: Boolean = false
    var lastCaptureUptimeMs: Long = Long.MIN_VALUE
    var capturedSize: IntSize = IntSize.Zero
}

/**
 * clox 式覆层背衬：表盘按进度下压缩小时，边缘让出的区域用表盘最后一帧
 * 的四边/四角镜像像素向外填充，而不是露出纯黑底。
 *
 * 实现：把（已被 scaleBlurAlpha 变换过的）内容录进一个 GraphicsLayer，
 * 按当前表盘的 scale / translation 画中心副本，再分别镜像左右、上下和四角
 * 九个区域。它们共同组成一个没有透明缝的全屏层，随后统一模糊、压暗。
 * 录制只发生在绘制阶段，零重组；progress 走 lambda 逐帧读。
 *
 * 背衬使用覆层打开前的最后一帧，而不是再次录制已经缩小/下沉的当前帧。
 * 这样源图自身始终铺满屏幕，向外扩展时只需少量 overscan，边缘不会先
 * 混入透明像素再被模糊放大。静止时最多每秒更新一次缓存，避免高频双层录制。
 *
 * API 31+ 副本带硬件模糊；API 27-30 在静止帧更新时预制固定最大模糊帧，
 * 过渡期间只把清晰/模糊缓存按 (1-progress)/progress 交叉混合。两条路径
 * 都保留镜像填屏，且不跟随「高级材质」开关。
 * 注意：GraphicsLayer.record 录不到 SurfaceView / 视频内容。此时先铺
 * [fallbackColor]（应为表盘背景色）的不透明底色，避免视频首帧或录制空帧
 * 重新露出 Window 黑底；下一次可录制帧会自然替换这份兜底。
 */
@Composable
fun Modifier.flueBackdropFill(
    progress: () -> Float,
    enabled: Boolean,
    fallbackColor: Color = Color(0xFF141820),
    contentScale: (() -> Float)? = null,
    contentTranslationYFraction: (() -> Float)? = null,
): Modifier {
    if (!enabled) return this
    val backdropLayer = rememberGraphicsLayer()
    val magnifiedLayer = rememberGraphicsLayer()
    val legacyBlurredBackdropLayer = rememberGraphicsLayer()
    val legacyMagnifiedLayer = rememberGraphicsLayer()
    val captureState = remember { BackdropCaptureState() }
    val canHardwareBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return this.drawWithContent {
        val rawProgress = progress()
        val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
        val layerSize = size.toIntSize()
        if (p <= 0.001f) {
            val now = SystemClock.uptimeMillis()
            val sizeChanged = captureState.capturedSize != layerSize
            val captureDue = now - captureState.lastCaptureUptimeMs >= BACKDROP_CAPTURE_INTERVAL_MS
            if (!captureState.hasFrame || captureState.wasActive || sizeChanged || captureDue) {
                backdropLayer.record(this, layoutDirection, layerSize) {
                    this@drawWithContent.drawContent()
                }
                if (!canHardwareBlur) {
                    recordLegacyBlurredBackdrop(
                        targetLayer = legacyBlurredBackdropLayer,
                        sourceLayer = backdropLayer,
                        layerSize = layerSize,
                    )
                }
                captureState.hasFrame = true
                captureState.wasActive = false
                captureState.lastCaptureUptimeMs = now
                captureState.capturedSize = layerSize
            }
            drawContent()
            return@drawWithContent
        }
        captureState.wasActive = true
        // Activity 若一开始就在覆层状态，尚不存在静止帧；只在这一帧补录一次，
        // 后续动画始终复用它，避免视频/复杂表盘在每个动画帧被双重录制。
        if (!captureState.hasFrame || captureState.capturedSize != layerSize) {
            backdropLayer.record(this, layoutDirection, layerSize) {
                this@drawWithContent.drawContent()
            }
            if (!canHardwareBlur) {
                recordLegacyBlurredBackdrop(
                    targetLayer = legacyBlurredBackdropLayer,
                    sourceLayer = backdropLayer,
                    layerSize = layerSize,
                )
            }
            captureState.hasFrame = true
            captureState.lastCaptureUptimeMs = SystemClock.uptimeMillis()
            captureState.capturedSize = layerSize
        }
        backdropLayer.renderEffect = null
        backdropLayer.alpha = 1f
        backdropLayer.colorFilter = null
        // 底色永远先铺满；即使 SurfaceView / 视频没有进入 GraphicsLayer
        // 录制结果，整个背衬也保持不透明，不会从透明像素看到 Window 黑底。
        drawRect(fallbackColor.copy(alpha = 1f))
        // 和前景表盘使用同一份 scale / translation。中心副本只占前景表盘
        // 实际所在的矩形；矩形外不是再放大一份中心画面，而是继续采样相邻
        // 边缘并翻转方向。contentScale 最大限制为 1：前景若在别的场景放大，
        // 自身已经覆盖全屏，不需要制造负宽度的镜像条带。
        val insetScale = (contentScale?.invoke() ?: (1f - 0.08f * p))
            .coerceIn(0.01f, 1f)
        val translationY = (contentTranslationYFraction?.invoke() ?: (0.03f * p)) * size.height
        val insetLeft = (size.width - size.width * insetScale) / 2f
        val insetTop = (size.height - size.height * insetScale) / 2f + translationY
        val insetRight = insetLeft + size.width * insetScale
        val insetBottom = insetTop + size.height * insetScale

        magnifiedLayer.record(this, layoutDirection, layerSize) {
            fun drawSource(
                translateX: Float,
                translateY: Float,
                scaleX: Float,
                scaleY: Float,
            ) {
                translate(left = translateX, top = translateY) {
                    scale(
                        scaleX = scaleX,
                        scaleY = scaleY,
                        pivot = Offset.Zero,
                    ) {
                        drawLayer(backdropLayer)
                    }
                }
            }

            // 中心：和正在下沉的前景保持完全相同的几何位置。
            clipRect(
                left = insetLeft.coerceAtLeast(0f),
                top = insetTop.coerceAtLeast(0f),
                right = insetRight.coerceAtMost(size.width),
                bottom = insetBottom.coerceAtMost(size.height),
            ) {
                drawSource(insetLeft, insetTop, insetScale, insetScale)
            }

            // 左右边：在中心矩形边界处连续，离开边界后反向读取源帧。
            if (insetLeft > 0f) {
                clipRect(0f, insetTop.coerceAtLeast(0f), insetLeft, insetBottom.coerceAtMost(size.height)) {
                    drawSource(insetLeft, insetTop, -insetScale, insetScale)
                }
            }
            if (insetRight < size.width) {
                clipRect(insetRight, insetTop.coerceAtLeast(0f), size.width, insetBottom.coerceAtMost(size.height)) {
                    drawSource(insetRight + size.width * insetScale, insetTop, -insetScale, insetScale)
                }
            }

            // 上下边。
            if (insetTop > 0f) {
                clipRect(insetLeft.coerceAtLeast(0f), 0f, insetRight.coerceAtMost(size.width), insetTop) {
                    drawSource(insetLeft, insetTop, insetScale, -insetScale)
                }
            }
            if (insetBottom < size.height) {
                clipRect(insetLeft.coerceAtLeast(0f), insetBottom, insetRight.coerceAtMost(size.width), size.height) {
                    drawSource(insetLeft, insetBottom + size.height * insetScale, insetScale, -insetScale)
                }
            }

            // 四角必须同时沿 X/Y 翻转；仅画四向 strip 会在圆屏顶底角留下
            // fallbackColor，模糊后仍能看到一圈暗缝。
            if (insetLeft > 0f && insetTop > 0f) {
                clipRect(0f, 0f, insetLeft, insetTop) {
                    drawSource(insetLeft, insetTop, -insetScale, -insetScale)
                }
            }
            if (insetRight < size.width && insetTop > 0f) {
                clipRect(insetRight, 0f, size.width, insetTop) {
                    drawSource(
                        insetRight + size.width * insetScale,
                        insetTop,
                        -insetScale,
                        -insetScale,
                    )
                }
            }
            if (insetLeft > 0f && insetBottom < size.height) {
                clipRect(0f, insetBottom, insetLeft, size.height) {
                    drawSource(
                        insetLeft,
                        insetBottom + size.height * insetScale,
                        -insetScale,
                        -insetScale,
                    )
                }
            }
            if (insetRight < size.width && insetBottom < size.height) {
                clipRect(insetRight, insetBottom, size.width, size.height) {
                    drawSource(
                        insetRight + size.width * insetScale,
                        insetBottom + size.height * insetScale,
                        -insetScale,
                        -insetScale,
                    )
                }
            }
        }
        if (canHardwareBlur) {
            // Mirror handles convolution sampling beyond the physical screen;
            // the mirrored center/strips/corners already fill the visible area.
            val blurRadiusBucketPx = quantizeBackdropBlurRadiusPx(p)
            magnifiedLayer.renderEffect = if (blurRadiusBucketPx > 0) {
                BackdropBlurEffectCache.get(blurRadiusBucketPx)
            } else {
                null
            }
            magnifiedLayer.alpha = 1f
            clipRect {
                drawLayer(magnifiedLayer)
            }
        } else {
            // API 27-30: the maximum-blur source was prepared together with the
            // stationary frame. Per-frame work only mirrors both cached sources
            // and crossfades them; blur radius is never recomputed while dragging.
            recordMirroredBackdrop(
                targetLayer = legacyMagnifiedLayer,
                sourceLayer = legacyBlurredBackdropLayer,
                layerSize = layerSize,
                insetScale = insetScale,
                translationY = translationY,
            )
            magnifiedLayer.renderEffect = null
            legacyMagnifiedLayer.renderEffect = null
            val blend = legacyBackdropBlend(p)
            magnifiedLayer.alpha = blend.clearAlpha
            legacyMagnifiedLayer.alpha = blend.blurredAlpha
            clipRect {
                if (blend.clearAlpha > 0.001f) drawLayer(magnifiedLayer)
                if (blend.blurredAlpha > 0.001f) drawLayer(legacyMagnifiedLayer)
            }
            magnifiedLayer.alpha = 1f
            legacyMagnifiedLayer.alpha = 1f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.12f * p)),
                    center = center,
                    radius = max(size.width, size.height) * 0.72f,
                )
            )
        }
        // 所有效果从 p=0 连续起步；中断后只要同一个时钟反向，就会沿原路径
        // 消失，不会在接近 0 时因固定 0.08 alpha 突然跳一帧。
        drawRect(Color.Black.copy(alpha = 0.20f * p))
        // On old Android the cached clear/blur blend is the watch face itself;
        // drawing the live clear face here would cover it and defeat the crossfade.
        if (canHardwareBlur) {
            drawContent()
        }
    }
}
