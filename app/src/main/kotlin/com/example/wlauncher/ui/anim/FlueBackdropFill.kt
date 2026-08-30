package com.flue.launcher.ui.anim

import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import kotlin.math.roundToInt

private const val BACKDROP_CAPTURE_INTERVAL_MS = 1_000L
private const val BACKDROP_BLUR_STEP_PX = 2
private const val BACKDROP_MAX_BLUR_RADIUS_PX = 64
private const val BACKDROP_SHARED_MAX_BLUR_RADIUS_PX = 128

/**
 * 九宫格镜像层重录的 progress 死区：低于这个变化量沿用上一次录制。
 * 0.5% 的 progress ≈ 2px 级别的镜像几何差，在 20% 压暗 + 全屏模糊之下不可见。
 */
private const val MAGNIFIED_RECORD_PROGRESS_EPSILON = 0.005f

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
    // Source-over compositing is not additive. Drawing both layers with
    // (1-p)/p over an opaque fallback leaks fallback colour in the middle
    // (at p=.5: 25% fallback), causing the visible dark/white pulse. Keep the
    // clear frame opaque and fade the opaque blurred frame over it instead:
    // result = clear*(1-p) + blurred*p with no backdrop contribution.
    // Bring the blur in a little earlier without introducing a non-zero floor:
    // p=0 remains the untouched live face, p=1 remains the prepared blur, and
    // the middle is only mildly ahead of a linear cross-fade.
    val earlyBlurAlpha = when (p) {
        0f -> 0f
        1f -> 1f
        else -> p * (1.18f - 0.18f * p)
    }
    return LegacyBackdropBlend(clearAlpha = 1f, blurredAlpha = earlyBlurAlpha)
}

internal fun legacyBackdropViewportMatches(
    viewportWidth: Int,
    viewportHeight: Int,
    layerSize: IntSize,
): Boolean = viewportWidth == layerSize.width && viewportHeight == layerSize.height

internal fun shouldDrawLiveBackdropContent(
    overlayActive: Boolean,
    hardwareCaptureRequired: Boolean = false,
    capturedFrameAvailable: Boolean,
): Boolean = when {
    !overlayActive -> true
    hardwareCaptureRequired -> false
    else -> !capturedFrameAvailable
}

/** API 27-30 transition hot path: one cached bitmap draw, with no recording. */
private fun DrawScope.drawLegacyBackdropCover(
    image: ImageBitmap,
    frames: LegacyWatchFaceBackdropFrames,
    layerSize: IntSize,
    contentScale: Float,
    translationYPx: Float,
    alpha: Float,
) {
    if (alpha <= 0.001f) return
    val scale = contentScale.coerceIn(0.01f, 1f)
    val viewportWidth = frames.viewportWidth.toFloat()
    val viewportHeight = frames.viewportHeight.toFloat()
    val translatedViewportY = if (size.height > 0f) {
        translationYPx / size.height * viewportHeight
    } else {
        0f
    }
    val insetLeft = (viewportWidth - viewportWidth * scale) / 2f
    val insetTop = (viewportHeight - viewportHeight * scale) / 2f + translatedViewportY
    val sourceWidth = (viewportWidth / scale).roundToInt().coerceIn(1, image.width)
    val sourceHeight = (viewportHeight / scale).roundToInt().coerceIn(1, image.height)
    val sourceLeft = (frames.overscanX - insetLeft / scale)
        .roundToInt()
        .coerceIn(0, image.width - sourceWidth)
    val sourceTop = (frames.overscanY - insetTop / scale)
        .roundToInt()
        .coerceIn(0, image.height - sourceHeight)
    drawImage(
        image = image,
        srcOffset = IntOffset(sourceLeft, sourceTop),
        srcSize = IntSize(sourceWidth, sourceHeight),
        dstOffset = IntOffset.Zero,
        dstSize = layerSize,
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

/** Draw-phase only state; changing it must never trigger recomposition. */
private class BackdropCaptureState {
    var hasFrame: Boolean = false
    var wasActive: Boolean = false
    var lastCaptureUptimeMs: Long = Long.MIN_VALUE
    var capturedSize: IntSize = IntSize.Zero
    var capturedPixelCopyGeneration: Long = Long.MIN_VALUE
    /** 上次重录九宫格镜像层时的 progress；变化小于阈值就不重录。 */
    var recordedProgress: Float = Float.NaN
    val preparedRecordState = PreparedBackdropRecordState()
}

/**
 * Cache key for the API 31+ prepared-frame path.
 *
 * RecyclerView scrolling invalidates the launcher root on every frame. The
 * watch-face backdrop sits below the opaque app-list plate, so re-recording
 * its full-screen bitmap for an unchanged settled geometry only creates HWUI
 * buffers and native garbage. Keep primitive fields here so the check itself
 * remains allocation-free in the draw hot path.
 */
internal class PreparedBackdropRecordState {
    private var width = -1
    private var height = -1
    private var frameGeneration = Long.MIN_VALUE
    private var scale = Float.NaN
    private var translationYPx = Float.NaN

    fun shouldRecord(
        width: Int,
        height: Int,
        frameGeneration: Long,
        scale: Float,
        translationYPx: Float,
    ): Boolean {
        if (
            this.width == width &&
            this.height == height &&
            this.frameGeneration == frameGeneration &&
            this.scale == scale &&
            this.translationYPx == translationYPx
        ) {
            return false
        }
        this.width = width
        this.height = height
        this.frameGeneration = frameGeneration
        this.scale = scale
        this.translationYPx = translationYPx
        return true
    }
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
 * API 31+ 继续使用实时帧的镜像扩边与硬件模糊。API 27-30 不录制实时内容：
 * 标准/JB 读取 preview，内置图片读取原图，内置视频读取首帧，默认内置表盘
 * 生成固定封面；这些来源在后台预制清晰、浅色强模糊和深色强模糊三张缓存。
 * 手势帧只按 (1-progress)/progress 绘制两张全屏位图，因此 AndroidView、
 * TextureView 也不会再因为 GraphicsLayer 录制为空而露出浅色 Window 背景。
 */
@Composable
internal fun Modifier.flueBackdropFill(
    progress: () -> Float,
    enabled: Boolean,
    fallbackColor: Color = Color(0xFF141820),
    legacyFrames: LegacyWatchFaceBackdropFrames? = null,
    legacyDarkMode: Boolean = true,
    contentScale: (() -> Float)? = null,
    contentTranslationYFraction: (() -> Float)? = null,
    /** Same dp blur clock installed on the live face layer for API 31+. */
    contentBlurRadiusDp: (() -> Float)? = null,
    /** Actual window-composited frame for AndroidView / TextureView watch faces. */
    hardwareCapturedFrame: LegacyWatchFaceCapturedFrame? = null,
    /** Never fall back to preview/Compose recording when the face needs PixelCopy. */
    hardwareCaptureRequired: Boolean = false,
    /** Skip the live face once a matching captured frame is available. */
    freezeLiveContentWhenCaptured: Boolean = false,
    /** Disable system blur together with the advanced-material setting. */
    advancedMaterialEnabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val backdropLayer = rememberGraphicsLayer()
    val magnifiedLayer = rememberGraphicsLayer()
    val captureState = remember(hardwareCaptureRequired, hardwareCapturedFrame?.visualKey) {
        BackdropCaptureState()
    }
    val canHardwareBlur = overlayBlurBackend(
        sdkInt = Build.VERSION.SDK_INT,
        advancedMaterialEnabled = advancedMaterialEnabled,
    ) == OverlayBlurBackend.SYSTEM
    val hardwareCapturedImage = remember(hardwareCapturedFrame?.generation) {
        hardwareCapturedFrame
            ?.bitmap
            ?.takeUnless { it.isRecycled }
            ?.asImageBitmap()
    }
    return this.drawWithContent {
        val rawProgress = progress()
        val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
        val layerSize = size.toIntSize()
        fun recordPixelCopyFrameIfAvailable(): Boolean {
            val frame = hardwareCapturedFrame ?: return false
            val image = hardwareCapturedImage ?: return false
            if (
                frame.bitmap.isRecycled ||
                image.width != layerSize.width ||
                image.height != layerSize.height
            ) return false
            if (
                captureState.hasFrame &&
                captureState.capturedSize == layerSize &&
                captureState.capturedPixelCopyGeneration == frame.generation
            ) return true
            backdropLayer.record(this, layoutDirection, layerSize) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset.Zero,
                    dstSize = layerSize,
                    filterQuality = FilterQuality.Medium,
                )
            }
            captureState.hasFrame = true
            captureState.capturedSize = layerSize
            captureState.capturedPixelCopyGeneration = frame.generation
            captureState.lastCaptureUptimeMs = SystemClock.uptimeMillis()
            return true
        }
        if (p <= 0.001f) {
            if (canHardwareBlur) {
                val hasPixelCopy = if (hardwareCaptureRequired) {
                    recordPixelCopyFrameIfAvailable()
                } else {
                    false
                }
                if (!hardwareCaptureRequired || !hasPixelCopy) {
                    val now = SystemClock.uptimeMillis()
                    val sizeChanged = captureState.capturedSize != layerSize
                    val captureDue = now - captureState.lastCaptureUptimeMs >= BACKDROP_CAPTURE_INTERVAL_MS
                    if (!hardwareCaptureRequired && (
                            !captureState.hasFrame || captureState.wasActive || sizeChanged || captureDue
                        )
                    ) {
                        backdropLayer.record(this, layoutDirection, layerSize) {
                            this@drawWithContent.drawContent()
                        }
                        captureState.hasFrame = true
                        captureState.capturedPixelCopyGeneration = Long.MIN_VALUE
                        captureState.wasActive = false
                        captureState.lastCaptureUptimeMs = now
                        captureState.capturedSize = layerSize
                    }
                }
            }
            drawContent()
            return@drawWithContent
        }

        if (!canHardwareBlur) {
            // Old Android never enters the display-list recording path. The
            // selected watch face has already prepared these frames off the UI
            // thread, so a drag frame is exactly two bitmap draws + one veil.
            // Never stretch a cache prepared for a different measured layer.
            // The live face remains available while the correctly-sized blur
            // triplet is rebuilt in the background.
            val matchingFrames = legacyFrames?.takeIf {
                legacyBackdropViewportMatches(it.viewportWidth, it.viewportHeight, layerSize)
            }
            val capturedLegacyFrameAvailable =
                hardwareCapturedFrame?.let { captured ->
                    !captured.bitmap.isRecycled &&
                        matchingFrames?.capturedGeneration == captured.generation
                } == true
            val drawLiveContent = shouldDrawLiveBackdropContent(
                overlayActive = freezeLiveContentWhenCaptured,
                hardwareCaptureRequired = hardwareCaptureRequired,
                capturedFrameAvailable = capturedLegacyFrameAvailable,
            )
            val fallback = matchingFrames?.let { Color(it.fallbackArgb) } ?: Color(0xFF10151D)
            drawRect(fallback.copy(alpha = 1f))
            if (matchingFrames != null) {
                val blend = legacyBackdropBlend(p)
                val legacyScale = (contentScale?.invoke() ?: (1f - 0.08f * p))
                    .coerceIn(0.01f, 1f)
                val legacyTranslationY =
                    (contentTranslationYFraction?.invoke() ?: (0.03f * p)) * size.height
                val blurredFrame = if (legacyDarkMode) {
                    matchingFrames.darkBlurred
                } else {
                    matchingFrames.lightBlurred
                }
                // The prepared blur is the opaque edge fill behind the
                // shrinking live face. It never substitutes for the clear
                // watch face itself.
                drawLegacyBackdropCover(
                    image = if (!drawLiveContent) matchingFrames.clear else blurredFrame,
                    frames = matchingFrames,
                    layerSize = layerSize,
                    contentScale = legacyScale,
                    translationYPx = legacyTranslationY,
                    alpha = blend.clearAlpha,
                )
            }
            // Keep the real rendered watch face as the clear layer unless the
            // overlay already has a matching captured frame. In that case the
            // captured frame above is the clear layer and drawing the Android
            // view again would defeat the freeze.
            if (drawLiveContent) drawContent()
            if (matchingFrames != null) {
                drawLegacyBackdropCover(
                    image = if (legacyDarkMode) matchingFrames.darkBlurred else matchingFrames.lightBlurred,
                    frames = matchingFrames,
                    layerSize = layerSize,
                    contentScale = (contentScale?.invoke() ?: (1f - 0.08f * p)).coerceIn(0.01f, 1f),
                    translationYPx =
                        (contentTranslationYFraction?.invoke() ?: (0.03f * p)) * size.height,
                    alpha = legacyBackdropBlend(p).blurredAlpha,
                )
            }
            drawRect(
                if (legacyDarkMode) {
                    Color.Black.copy(alpha = 0.16f * p)
                } else {
                    Color.White.copy(alpha = 0.06f * p)
                }
            )
            return@drawWithContent
        }

        // 仅保留给 API 27-30 的预制帧兼容分支；API 31+ 外部/视频表盘走下面
        // 的真实 PixelCopy 当前帧，不再允许 preview.jpg 或视频首帧插进来。
        val preparedHardwareFrames = legacyFrames?.takeIf {
            legacyBackdropViewportMatches(it.viewportWidth, it.viewportHeight, layerSize)
        }
        if (preparedHardwareFrames != null) {
            val preparedFrameAvailable =
                hardwareCapturedFrame?.let { captured ->
                    !captured.bitmap.isRecycled &&
                        preparedHardwareFrames.capturedGeneration == captured.generation
                } == true
            val drawLiveContent = shouldDrawLiveBackdropContent(
                overlayActive = freezeLiveContentWhenCaptured,
                hardwareCaptureRequired = hardwareCaptureRequired,
                capturedFrameAvailable = preparedFrameAvailable,
            )
            val preparedScale = (contentScale?.invoke() ?: (1f - 0.08f * p))
                .coerceIn(0.01f, 1f)
            val preparedTranslationY =
                (contentTranslationYFraction?.invoke() ?: (0.03f * p)) * size.height
            drawRect(Color(preparedHardwareFrames.fallbackArgb))
            if (
                captureState.preparedRecordState.shouldRecord(
                    width = layerSize.width,
                    height = layerSize.height,
                    frameGeneration = preparedHardwareFrames.capturedGeneration,
                    scale = preparedScale,
                    translationYPx = preparedTranslationY,
                )
            ) {
                magnifiedLayer.record(this, layoutDirection, layerSize) {
                    drawLegacyBackdropCover(
                        image = preparedHardwareFrames.clear,
                        frames = preparedHardwareFrames,
                        layerSize = layerSize,
                        contentScale = preparedScale,
                        translationYPx = preparedTranslationY,
                        alpha = 1f,
                    )
                }
            }
            val requestedBlurDp = contentBlurRadiusDp?.invoke()
                ?.takeIf(Float::isFinite)
                ?.coerceAtLeast(0f)
            val preparedBlurRadius = if (requestedBlurDp != null) {
                with(this) { requestedBlurDp.dp.toPx() }.roundToInt()
                    .coerceIn(0, BACKDROP_SHARED_MAX_BLUR_RADIUS_PX)
            } else {
                quantizeBackdropBlurRadiusPx(p)
            }
            magnifiedLayer.renderEffect = if (preparedBlurRadius > 0) {
                BackdropBlurEffectCache.get(preparedBlurRadius)
            } else {
                null
            }
            magnifiedLayer.alpha = 1f
            clipRect { drawLayer(magnifiedLayer) }
            // Keep the old live path until an actual captured frame is
            // available. A captured frame can safely replace the live view for
            // the whole gesture, including Android 12 with advanced material
            // disabled.
            if (drawLiveContent) drawContent()
            drawRect(Color.Black.copy(alpha = 0.20f * p))
            return@drawWithContent
        }

        captureState.wasActive = true
        if (hardwareCaptureRequired) {
            recordPixelCopyFrameIfAvailable()
        }
        // Activity 若一开始就在覆层状态，尚不存在静止帧；只在这一帧补录一次，
        // 后续动画始终复用它，避免视频/复杂表盘在每个动画帧被双重录制。
        if (
            !hardwareCaptureRequired &&
            (!captureState.hasFrame || captureState.capturedSize != layerSize)
        ) {
            backdropLayer.record(this, layoutDirection, layerSize) {
                this@drawWithContent.drawContent()
            }
            captureState.hasFrame = true
            captureState.capturedPixelCopyGeneration = Long.MIN_VALUE
            captureState.lastCaptureUptimeMs = SystemClock.uptimeMillis()
            captureState.capturedSize = layerSize
        }
        if (!captureState.hasFrame || captureState.capturedSize != layerSize) {
            // PixelCopy 尚未返回时，硬件捕获路径不能继续画实时表盘：
            // Watchmaker 的 tick/media 已经冻结，继续 drawContent() 会让表盘
            // 在等待窗口里走另一条实时绘制路径，正是手势首帧卡顿的来源。
            // 捕获到达后才切成完整的单层扩边画面；普通录制路径仍保留实时
            // 内容，避免没有硬件帧时出现不必要的空白。
            val drawLiveContent = shouldDrawLiveBackdropContent(
                overlayActive = freezeLiveContentWhenCaptured,
                hardwareCaptureRequired = hardwareCaptureRequired,
                capturedFrameAvailable = false,
            )
            drawRect(fallbackColor.copy(alpha = 1f))
            if (drawLiveContent) drawContent()
            drawRect(Color.Black.copy(alpha = 0.20f * p))
            return@drawWithContent
        }
        val liveContentFrozen = !shouldDrawLiveBackdropContent(
            overlayActive = freezeLiveContentWhenCaptured,
            hardwareCaptureRequired = hardwareCaptureRequired,
            capturedFrameAvailable = captureState.hasFrame,
        )
        // 真实内容只负责推进内部弹簧/时钟，随后被完整不透明的单层扩边画面
        // 覆盖，不会再与回填层同时出现在屏幕上。
        if (!liveContentFrozen) drawContent()
        backdropLayer.renderEffect = null
        backdropLayer.alpha = 1f
        backdropLayer.colorFilter = null
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

        // 九宫格镜像重录节流：转场动画的每一帧 progress 都在变，但变化低于
        // 阈值时镜像几何的差异只有零点几像素，且整层还要过 20% 压暗 + 模糊，
        // 重录九个 clipRect 分区属于纯浪费。跳帧期间沿用上一次的录制内容。
        val magnifiedGeometryMoved = !(
            kotlin.math.abs(p - captureState.recordedProgress) < MAGNIFIED_RECORD_PROGRESS_EPSILON &&
                captureState.capturedSize == layerSize
            )
        if (magnifiedGeometryMoved) {
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
            captureState.recordedProgress = p
        }
        // Mirror handles convolution sampling beyond the physical screen;
        // the mirrored center/strips/corners already fill the visible area.
        val requestedBlurDp = contentBlurRadiusDp?.invoke()
            ?.takeIf(Float::isFinite)
            ?.coerceAtLeast(0f)
        val blurRadiusBucketPx = if (requestedBlurDp != null) {
            with(this) { requestedBlurDp.dp.toPx() }.roundToInt()
                .coerceIn(0, BACKDROP_SHARED_MAX_BLUR_RADIUS_PX)
        } else {
            quantizeBackdropBlurRadiusPx(p)
        }
        magnifiedLayer.renderEffect = if (blurRadiusBucketPx > 0) {
            BackdropBlurEffectCache.get(blurRadiusBucketPx)
        } else {
            null
        }
        magnifiedLayer.alpha = 1f
        clipRect {
            drawLayer(magnifiedLayer)
        }
        // Android 12+ 只画这张“中心 + 四边 + 四角”的完整扩边层。旧实现随后
        // 又 drawContent() 一次，等于同一表盘上下各一层、各走一次模糊，正是
        // 下沉时中心和回填割裂的来源。API 27-30 的预制帧分支保持原样。
        drawRect(Color.Black.copy(alpha = 0.20f * p))
    }
}
