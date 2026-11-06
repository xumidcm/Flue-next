package com.flue.launcher.ui.anim

import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
    capturedFrameAvailable: Boolean,
    backend: OverlayBlurBackend = OverlayBlurBackend.SYSTEM,
): Boolean = !overlayActive || !capturedFrameAvailable

/**
 * 冻结期内进度回到起步位置也**不能**把实时表盘交回去（仅 Android 12+ 窗口帧路径）。
 *
 * API 31+ 的背衬中心画的就是那张窗口静止帧，和实时表盘是两个不同的画面。
 * 手指往回滑到起步位置时 progress 会触到 0，而手指可能仍按在屏幕上；此刻若走
 * 「画实时表盘」的捷径，中心就从静止帧直接硬切成当前时间——也就是老师报的
 * 「往回滑、还没松手就闪出实时表盘」，再往前拖又切回来，边界上成了来回跳跃闪烁。
 *
 * 因此冻结期内一律继续画同一张冻结帧，等覆层真正收尾（freeze 落回 false）再交还，
 * 即老师要的「松手再显示」。
 *
 * **不含 API 27-30**：那条路径的设计是「中心恒定画活的表盘、只叠预烘焙模糊」
 * （见文件顶部与 drawLegacyBackdropCover 处的说明），中心本来就不是静止帧，
 * 不存在硬切；反过来在起步位置改成画静止帧，等于把「一分钟前的静态帧」重新
 * 暴露出来，正是要避免的事。所以旧系统路径保持原样。
 */
internal fun shouldHoldFrozenBackdropAtRest(
    freezeLiveContentWhenCaptured: Boolean,
    progress: Float,
    hardwareBlurAvailable: Boolean,
    holdFrozenWhenAtRest: Boolean = true,
): Boolean = holdFrozenWhenAtRest &&
    freezeLiveContentWhenCaptured &&
    hardwareBlurAvailable &&
    progress <= 0.001f

internal enum class BackdropContentSource {
    LIVE,
    CAPTURED,
}

internal fun resolveBackdropContentSource(
    overlayActive: Boolean,
    hardwareCaptureRequired: Boolean,
    capturedFrameAvailable: Boolean,
    cachedFrameAvailable: Boolean,
): BackdropContentSource = if (
    overlayActive &&
    (capturedFrameAvailable || (hardwareCaptureRequired && cachedFrameAvailable))
) {
    BackdropContentSource.CAPTURED
} else {
    BackdropContentSource.LIVE
}

/**
 * Holds the complete prepared frame, including its pixels, for one overlay lifetime.
 *
 * 「一次覆层生命周期内不换帧」这条规则是为了避免手势中途被晚到的帧替换
 * （中途换帧会看到画面跳变）。但它有个副作用：快速退出一个覆层、紧接着进入
 * 另一个覆层时，如果两次之间没有回到静止态，`active` 会一直是 true、旧帧被
 * 保留下来 —— 于是新覆层用的是**上一个覆层的快照**（老师报的「快速退出进入
 * 切换其他三个页面时会出现不刷新快照」）。
 *
 * 所以除了「是否激活」，还要比对**这次激活是不是同一段连续激活**。调用方通过
 * [restart] 在「回到静止态」或「切换到另一个覆层」时显式打断连续性，让下一帧
 * 重新选取。
 */
internal class BackdropFrameSession<T> {
    private var active = false
    private var retained: T? = null

    /**
     * 打断「连续激活」状态，使下一次 [select] 必定重新取帧。
     *
     * 调用时机：覆层完全收起时、或活跃覆层目标发生变化时。不打断的话，
     * 覆层之间的接连切换会一直复用第一次进入时那张快照。
     */
    fun restart() {
        active = false
        retained = null
    }

    fun select(
        overlayActive: Boolean,
        candidate: T?,
        allowLateFrame: Boolean = false,
        isValid: (T) -> Boolean = { true },
    ): T? {
        if (retained?.let(isValid) == false) retained = null
        if (!active || !overlayActive || (allowLateFrame && retained == null)) {
            retained = candidate?.takeIf(isValid)
        }
        active = overlayActive
        // Both backends keep the same clear scene for the whole gesture.
        return retained
    }
}

/** API 27-30 transition hot path: one cached bitmap draw, with no recording. */
internal fun DrawScope.drawLegacyBackdropCover(
    image: ImageBitmap,
    frames: LegacyWatchFaceBackdropFrames,
    layerSize: IntSize,
    contentScale: Float,
    translationYPx: Float,
    alpha: Float,
    colorFilter: ColorFilter? = null,
) {
    if (alpha <= 0.001f) return
    val scale = contentScale.coerceIn(0.01f, 4f)
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
        colorFilter = colorFilter,
        filterQuality = FilterQuality.Medium,
    )
}

/** Draws an opaque mirrored scene, then applies the face alpha once to the entire result. */
internal class LegacyBackdropPainter {
    private val alphaPaint = androidx.compose.ui.graphics.Paint()
    // 20% black scrim has only 52 distinct 8-bit values. Reuse filters across frames.
    private val dimFilters = arrayOfNulls<ColorFilter>(52)

    fun draw(
        scope: DrawScope,
        frames: LegacyWatchFaceBackdropFrames,
        scale: Float,
        translationYPx: Float,
        alpha: Float,
        progress: Float,
        blurProgress: Float,
        blurEnabled: Boolean,
        darkMode: Boolean,
    ) = with(scope) {
        val opacity = alpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        if (opacity <= 0f) return@with
        if (!blurEnabled || !frames.hasPreparedBlur || blurProgress <= 0f) {
            val dim = (51f * progress.coerceIn(0f, 1f)).roundToInt()
            val filter = if (dim == 0) null else dimFilters[dim] ?: ColorFilter.tint(
                Color.Black.copy(alpha = dim / 255f), BlendMode.SrcAtop,
            ).also { dimFilters[dim] = it }
            // The prepared image is opaque. Apply dimming before opacity in one
            // bitmap draw, avoiding an extra full-screen offscreen buffer.
            drawLegacyBackdropCover(frames.clear, frames, size.toIntSize(), scale,
                translationYPx, opacity, filter)
            return@with
        }
        val grouped = opacity < 1f
        if (grouped) {
            alphaPaint.alpha = opacity
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), alphaPaint)
        }
        try {
            val extent = size.toIntSize()
            drawLegacyBackdropCover(frames.clear, frames, extent, scale, translationYPx, 1f)
            if (blurEnabled && frames.hasPreparedBlur) {
                drawLegacyBackdropCover(
                    if (darkMode) frames.darkBlurred else frames.lightBlurred,
                    frames, extent, scale, translationYPx, legacyBackdropBlend(blurProgress).blurredAlpha,
                )
            }
            drawRect(Color.Black.copy(alpha = 0.20f * progress.coerceIn(0f, 1f)))
        } finally {
            if (grouped) drawContext.canvas.restore()
        }
    }
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
    val frameSession = BackdropFrameSession<LegacyWatchFaceBackdropFrames>()
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
 * 所有系统使用转场前的真实窗口帧。API 31+ 使用硬件模糊；API 27-30 在后台
 * 为同一帧预制模糊，手势中仅混合缓存。材质关闭仍绘制清晰镜像扩边。
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
    contentAlpha: (() -> Float)? = null,
    /** Sample all transform values together instead of evaluating the spring once per property. */
    contentFrame: (() -> LayerAnimValues)? = null,
    /** Same dp blur clock installed on the live face layer for API 31+. */
    contentBlurRadiusDp: (() -> Float)? = null,
    /** Actual window-composited frame for AndroidView / TextureView watch faces. */
    hardwareCapturedFrame: LegacyWatchFaceCapturedFrame? = null,
    /** Never fall back to preview/Compose recording when the face needs PixelCopy. */
    hardwareCaptureRequired: Boolean = false,
    /** Skip the live face once a matching captured frame is available. */
    freezeLiveContentWhenCaptured: Boolean = false,
    /**
     * 手势回到起步位置（progress≈0）时是否继续画冻结帧，而不是交还实时表盘。
     *
     * 只有**覆层**（负一屏/小组件/控制中心/通知中心）该开 —— 那是老师报的
     * 「往回滑、还没松手就闪出实时表盘」。应用列表必须保持 false：它的背衬
     * 中心本来就要跟手势连续变化，钉住一张静止帧会变成「冻结表盘很怪」，
     * 返回时还会看到画面内容跳跃。
     */
    holdFrozenWhenAtRest: Boolean = false,
    /**
     * 当前活跃覆层的身份（负一屏 / 小组件 / 控制中心 / 通知中心 / 应用列表…）。
     *
     * 只要它变化，就打断「连续激活」并重新取帧。没有它的话，快速退出一个覆层
     * 紧接着进入另一个时，`BackdropFrameSession` 会一直保留第一次那张快照，
     * 新覆层上看到的是旧画面（老师报的「切换其他三个页面时不刷新快照」）。
     */
    overlaySessionKey: String = "",
    /** Disable system blur together with the advanced-material setting. */
    advancedMaterialEnabled: Boolean = true,
    visualKey: String = hardwareCapturedFrame?.visualKey.orEmpty(),
): Modifier {
    if (!enabled) return this
    val backdropLayer = rememberGraphicsLayer()
    val magnifiedLayer = rememberGraphicsLayer()
    // hardwareCaptureRequired flips exactly when an overlay starts. Keeping it
    // in the remember key discarded the clean idle recording on that frame,
    // leaving only a fallback colour until PixelCopy returned.
    val captureState = remember(visualKey) {
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
    // 活跃覆层身份变化 = 这是另一段覆层会话（例如快速从负一屏切到控制中心）。
    // 必须打断「连续激活」，否则 frameSession 会继续保留上一段的快照，新覆层
    // 显示的还是旧画面。这里用 remember 记录上一段的 key，绘制期发现不一致就重置。
    var lastOverlaySessionKey by remember { mutableStateOf(overlaySessionKey) }
    if (lastOverlaySessionKey != overlaySessionKey) {
        lastOverlaySessionKey = overlaySessionKey
        captureState.frameSession.restart()
        // 覆层换了，上一次的静止帧/镜像层都不再代表当前画面。
        captureState.hasFrame = false
        captureState.recordedProgress = Float.NaN
        captureState.capturedPixelCopyGeneration = Long.MIN_VALUE
    }
    return this.drawWithContent {
        val sampledFrame = contentFrame?.invoke()
        val rawProgress = progress()
        val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
        val layerSize = size.toIntSize()
        val preparedFrames = captureState.frameSession.select(
            overlayActive = freezeLiveContentWhenCaptured || p > 0.001f,
            candidate = legacyFrames?.takeIf {
                it.visualKey == visualKey &&
                    legacyBackdropViewportMatches(it.viewportWidth, it.viewportHeight, layerSize) &&
                    (it.capturedGeneration != Long.MIN_VALUE || !canHardwareBlur)
            },
            isValid = {
                it.visualKey == visualKey &&
                    legacyBackdropViewportMatches(it.viewportWidth, it.viewportHeight, layerSize)
            },
        )
        // Zoom and shrink-disabled animations already cover the viewport.
        // Draw the real layer so its alpha/blur remain intact, including the
        // old animation's fade to zero. Edge fill is only needed for exposed edges.
        val frameScale = sampledFrame?.scale ?: contentScale?.invoke() ?: (1f - 0.08f * p)
        val frameTranslationY = sampledFrame?.translationY ?: contentTranslationYFraction?.invoke() ?: (0.03f * p)
        if (!canHardwareBlur) {
            // Legacy Android keeps the live face as the clear layer. The
            // prepared frame is only an opaque edge/backdrop layer; waiting for
            // PixelCopy here makes the old path flash its fallback colour and
            // leaves a late bitmap to replace the face mid-transition.
            if (p <= 0.001f && !freezeLiveContentWhenCaptured) {
                drawContent()
                return@drawWithContent
            }
            val matchingFrames = preparedFrames?.takeIf {
                legacyBackdropViewportMatches(it.viewportWidth, it.viewportHeight, layerSize)
            }
            val fallback = matchingFrames?.let { Color(it.fallbackArgb) } ?: fallbackColor
            drawRect(fallback.copy(alpha = 1f))
            if (matchingFrames != null) {
                val legacyScale = frameScale.coerceIn(0.01f, 4f)
                val legacyTranslationY = frameTranslationY * size.height
                // 旧系统只能「叠预烘焙的过渡」：没有 RenderEffect，实时模糊不存在。
                //
                // 这里**绝不能**改画那张清晰的静止帧镜像 —— 试过了，代价是让
                // 「这张帧是几十秒前捕的」直接暴露给眼睛（老师报的「一分钟前的静态帧」）。
                // 预制模糊图自带 overscan（四边已镜像外扩），放大后本来就铺满全屏、
                // 不留黑边，而且重度模糊 + 20% 压暗之下，陈旧内容是看不出来的。
                // 表盘本身仍然画在最上面（下面的 drawContent），所以中心恒定是活的。
                drawLegacyBackdropCover(
                    image = if (legacyDarkMode) matchingFrames.darkBlurred else matchingFrames.lightBlurred,
                    frames = matchingFrames,
                    layerSize = layerSize,
                    contentScale = legacyScale,
                    translationYPx = legacyTranslationY,
                    alpha = 1f,
                )
            }
            drawContent()
            if (matchingFrames != null) {
                // The old scheme overlays the prepared blur rather than
                // replacing the live face. This avoids the sharp-to-blurry
                // swap when the capture arrives after the gesture starts.
                drawLegacyBackdropCover(
                    image = if (legacyDarkMode) matchingFrames.darkBlurred else matchingFrames.lightBlurred,
                    frames = matchingFrames,
                    layerSize = layerSize,
                    contentScale = frameScale.coerceIn(0.01f, 4f),
                    translationYPx = frameTranslationY * size.height,
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
        // 冻结期内回到起步位置也必须继续画冻结帧，不能交还实时表盘（老师：
        // 「往回滑、还没松手就闪出实时表盘」）。下面三处「画活表盘」的捷径
        // 都要让开，统一由这一个判据门控。
        //
        // [holdFrozenWhenAtRest] 由调用方显式指定，默认关闭：这条「静止时继续
        // 冻结」的规则只对**覆层**（负一屏/小组件/控制中心/通知中心）有意义 ——
        // 那是老师报的「往回滑没松手就闪出实时表盘」。应用列表不该套用它：
        // 它的背衬中心本来就该跟着手势走，一旦在 p≈0 处强行钉住一张静止帧，
        // 就会变成老师说的「冻结表盘之类很怪」，返回时还会看到画面内容跳跃。
        val holdFrozenFrameAtRest = shouldHoldFrozenBackdropAtRest(
            freezeLiveContentWhenCaptured = freezeLiveContentWhenCaptured,
            progress = p,
            hardwareBlurAvailable = canHardwareBlur,
            holdFrozenWhenAtRest = holdFrozenWhenAtRest,
        )
        if (
            canHardwareBlur &&
            frameScale >= 1f &&
            frameTranslationY == 0f &&
            !holdFrozenFrameAtRest
        ) {
            drawContent()
            return@drawWithContent
        }
        // A partial or late capture must never replace the live face mid-gesture.
        // Renderer ticks are paused separately; drawing its last display list is safe.
        if (
            canHardwareBlur &&
            freezeLiveContentWhenCaptured &&
            preparedFrames == null &&
            !holdFrozenFrameAtRest
        ) {
            drawRect(fallbackColor)
            drawContent()
            return@drawWithContent
        }
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
        if (p <= 0.001f && !holdFrozenFrameAtRest) {
            if (freezeLiveContentWhenCaptured) {
                drawContent()
                return@drawWithContent
            }
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
                when (
                    resolveBackdropContentSource(
                        overlayActive = freezeLiveContentWhenCaptured,
                        hardwareCaptureRequired = hardwareCaptureRequired,
                        capturedFrameAvailable = hasPixelCopy,
                        cachedFrameAvailable = captureState.hasFrame &&
                            captureState.capturedSize == layerSize &&
                            (!hardwareCaptureRequired ||
                                captureState.capturedPixelCopyGeneration != Long.MIN_VALUE),
                    )
                ) {
                    BackdropContentSource.CAPTURED -> {
                        backdropLayer.renderEffect = null
                        backdropLayer.alpha = 1f
                        backdropLayer.colorFilter = null
                        drawLayer(backdropLayer)
                    }
                    BackdropContentSource.LIVE -> drawContent()
                }
                return@drawWithContent
            }
            drawContent()
            return@drawWithContent
        }

        // 预制帧背衬分支。四条覆层（负一屏 / 小组件 / 控制中心 / 通知中心）在
        // 覆层激活时都会走到这里，两个 API 档位都一样：
        //   - API 27-30：`prepareCpuBlur = true`，帧里带预烘焙的三张模糊图。
        //   - API 31+：`prepareCpuBlur = false`，帧只由 PixelCopy 当前帧构成；
        //     此时 `capturedGeneration != Long.MIN_VALUE`（见 frameSession 的
        //     candidate 判定），所以同样会被 retain。模糊交给下面的 RenderEffect。
        // 早退分支只挡掉「scale >= 1 且无位移」——那时表盘本来就铺满屏幕，
        // 不需要任何边缘回填。
        //
        // 这里刻意保持**每帧重录**（scale / translationY 都进 key），与历史版本
        // 一致。曾改成「按 0.85 烘一次 + 绘制时放大」的静态层方案，理论更快，但
        // 那会改变「表盘 ↔ 应用列表」两段转场的实际观感（老师报的动画很怪、画面
        // 内容跳跃、闪烁）。性能优化不得以改变观感为代价，所以整体回退。
        val preparedHardwareFrames = preparedFrames?.takeIf {
            legacyBackdropViewportMatches(it.viewportWidth, it.viewportHeight, layerSize)
        }
        if (preparedHardwareFrames != null) {
            val preparedScale = frameScale
                .coerceIn(0.01f, 1f)
            val preparedTranslationY =
                frameTranslationY * size.height
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
            val requestedBlurDp = (sampledFrame?.blur ?: contentBlurRadiusDp?.invoke())
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
            magnifiedLayer.alpha = (sampledFrame?.alpha ?: contentAlpha?.invoke() ?: 1f).coerceIn(0f, 1f)
            clipRect { drawLayer(magnifiedLayer) }
            // 完整帧已经统一模糊，无需在其上再次绘制真实表盘。
            // 缓存缺失时上方分支直接绘制带自身 RenderEffect 的真实表盘。
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
            // PixelCopy 尚未返回且没有空闲缓存时，继续画已暂停的最后内容。
            // 动态 tick/media 已由 renderer gate 停掉，所以这里不会恢复复杂
            // 表盘工作；它只避免用纯色兜底制造可见黑帧。
            val drawLiveContent = shouldDrawLiveBackdropContent(
                overlayActive = freezeLiveContentWhenCaptured,
                capturedFrameAvailable = false,
            )
            drawRect(fallbackColor.copy(alpha = 1f))
            if (drawLiveContent) drawContent()
            drawRect(Color.Black.copy(alpha = 0.20f * p))
            return@drawWithContent
        }
        // LayerFrameSnapshot 直接读取动画时钟，无需额外绘制会被覆盖的内容。
        backdropLayer.renderEffect = null
        backdropLayer.alpha = 1f
        backdropLayer.colorFilter = null
        drawRect(fallbackColor.copy(alpha = 1f))
        // 和前景表盘使用同一份 scale / translation。中心副本只占前景表盘
        // 实际所在的矩形；矩形外不是再放大一份中心画面，而是继续采样相邻
        // 边缘并翻转方向。contentScale 最大限制为 1：前景若在别的场景放大，
        // 自身已经覆盖全屏，不需要制造负宽度的镜像条带。
        val insetScale = frameScale
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
