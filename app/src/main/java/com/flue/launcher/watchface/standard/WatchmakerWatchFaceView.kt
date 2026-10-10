package com.flue.launcher.watchface.standard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.core.graphics.PathParser
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import com.flue.launcher.R
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.ui.anim.createLegacyPrebuiltBlur
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.watchface.editor.WatchCanvasSize
import com.flue.launcher.watchface.editor.shape.WatchShapeKind
import com.flue.launcher.watchface.editor.shape.buildWatchRoundedRectPath
import com.flue.launcher.watchface.editor.shape.buildWatchRoundedRectPathIn
import com.flue.launcher.watchface.editor.shape.buildWatchShapePath
import com.flue.launcher.watchface.editor.shape.watchShapeClampCornerRadius
import com.flue.launcher.watchface.editor.shape.watchShapeHasRoundedCorner
import com.flue.launcher.watchface.editor.shape.watchShapeKindOf
import com.flue.launcher.watchface.agent.tools.WatchAgentExpressionClassifier
import com.flue.launcher.watchface.agent.tools.WatchAgentExpressionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Identity of a last-successful animated image fallback.
 *
 * The material expression is deliberately part of the key. Layer indices and optional XML
 * ids can be reused after deletion; keying only by that slot makes a newly created blank
 * looping layer draw the deleted layer's last frame.
 */
internal data class WatchImageFallbackKey(
    val layerCacheKey: String,
    val materialExpression: String,
    val variant: String,
)

/**
 * 一个 video 图层这一帧的最终变换与不透明度。
 *
 * [matrix] 是 [WatchmakerWatchFaceView.videoLayerGeometry] 内部 scratch 的拷贝
 * 语义：宿主拿到后应立即喂给 TextureView，不要长期持有。
 */
internal data class WatchVideoLayerGeometry(
    val matrix: Matrix,
    val alpha: Float,
)

/** renderPlan 里一个可播放 video 图层的位置与素材路径。 */
internal data class WatchVideoLayerPlanEntry(
    val planIndex: Int,
    val layerIndex: Int,
    val absolutePath: String,
)

internal fun shouldRenderWatchLayer(
    alpha: Float,
    isDisplayed: Boolean,
    hasTapAction: Boolean,
): Boolean = isDisplayed && (alpha > 0f || hasTapAction)

internal data class WatchTapBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Local-space tap bounds shared by primitives that own a rectangular box. */
internal fun watchLayerTapBox(type: String, width: Float, height: Float): WatchTapBox? {
    if (type.lowercase(Locale.ROOT) !in setOf("shape", "rounded", "text_ring")) return null
    val safeWidth = width.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: return null
    val safeHeight = height.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: return null
    return WatchTapBox(
        left = -safeWidth / 2f,
        top = -safeHeight / 2f,
        right = safeWidth / 2f,
        bottom = safeHeight / 2f,
    )
}

internal inline fun evaluateWatchFaceTickRedraw(
    luaCallbackRan: Boolean,
    shouldRedrawThisTick: () -> Boolean,
): Boolean {
    val tickRequiresRedraw = shouldRedrawThisTick()
    return luaCallbackRan || tickRequiresRedraw
}

internal fun watchFaceNeedsLuaRuntime(
    initialScript: String?,
    layers: List<WatchmakerLayer>,
): Boolean {
    if (!initialScript.isNullOrBlank()) return true
    return layers.any { layer ->
        WatchTapActionParser.parse(
            layer.attributes["tap_action"] ?: layer.attributes["action"],
        ) is WatchTapAction.Script || watchmakerLayerAttributeValues(layer).any(::watchFaceAttributeUsesLua)
    }
}

/**
 * Inline Lua is a complete program even when it does not mention a face global.
 * Keep this gate in one place so the import path and the frame evaluator agree.
 */
private fun watchFaceAttributeUsesLua(value: String): Boolean =
    WatchAgentExpressionClassifier.classify(value).kind == WatchAgentExpressionKind.LUA_EXPRESSION

/** Lua-only expressions need the sandbox even when the face has no script file or tap action. */
private val EXTERNAL_DATA_REFERENCE = Regex("\\bWF\\s*(?:\\.|\\[)", RegexOption.IGNORE_CASE)

/** MediaPlayer 能直接播的容器；序列帧导入产物走 image 路径，不在此列。 */
internal val PLAYABLE_VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "3gp")

/** `video`/`map` 兜底时可以当静态图片画的扩展名。 */
private val STATIC_MEDIA_IMAGE_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

/**
 * A deliberately self-contained native renderer for the open Watchmaker
 * `.watch` format.  The view owns the frame clock and asset loading; Compose
 * only hosts this one coarse-grained view.  Unsupported layer types remain
 * invisible instead of being routed through an unsupported plugin renderer.
 *
 * This is the first production seam for the format.  It implements the common
 * static/time layers used by real 1.0 and Studio 2.0 faces (image, text, ring,
 * progress, image_cond, shape, rounded, markers and text curves).  The parser
 * keeps every unknown attribute so additional primitives can be added without
 * changing storage or the launcher contract.
 */
class WatchmakerWatchFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val lifetimeJob = SupervisorJob()
    private val mainScope = CoroutineScope(lifetimeJob + Dispatchers.Main.immediate)
    private var assetGenerationJob: Job = SupervisorJob(lifetimeJob)
    private var assetScope = CoroutineScope(assetGenerationJob + Dispatchers.IO)
    private var loadJob: Job? = null
    private var bindGeneration = 0L
    private var disposed = false
    private var source: PreparedSource? = null
    /** Extra video-compositing segments borrow the primary segment's face VM. */
    private var inheritedLuaRuntime: WatchFaceLuaRuntime? = null
    private var descriptorKey: String? = null
    private var loadedDescriptorKey: String? = null
    private var loadedRefreshToken: Int? = null
    /**
     * Full outgoing frame used for an atomic cross-watch hand-off. The new
     * source is allowed to start decoding underneath it; the snapshot is
     * removed only after that generation has no pending visual asset loads.
     */
    private var sourceTransitionFrame: Bitmap? = null
    private var sourceTransitionGeneration = -1L
    private var sourceTransitionStartedAtUptimeMs = 0L
    /**
     * 逐帧记录「本帧有图层该画却拿不到素材」。
     *
     * 原来只在跨表盘过渡期间打开（`trackTransitionAssetReadiness`），现在每帧都记 ——
     * 桌面那层的加载页也要靠它判断渲染是否已经静默（见 [watchRenderReadiness]）。
     * 只是置一个布尔，逐帧开着没有成本。
     */
    private var trackVisualAssetReadiness = false
    private var frameHasUnreadyVisualAsset = false
    /** 渲染就绪回调与去重键：一个 (表盘, refreshToken) 只报一次。 */
    private var onRenderReadyCallback: ((Int) -> Unit)? = null
    private var renderReadyDescriptorKey: String? = null
    private var renderReadyRefreshToken: Int? = null
    private var renderReadyDeadlineUptimeMs = 0L
    private var faceVisible = false
    private val faceSoundPlayback = WatchFaceSoundPlayback(::faceSoundEligible)
    private fun faceSoundEligible(): Boolean = !disposed && faceVisible && isAttachedToWindow &&
        windowVisibility == VISIBLE && editorPreviewPlaying && !ambientRequested && physicalScreenInteractive
    private fun releaseFaceSound() = faceSoundPlayback.stop()
    private fun playFaceSound(name: String) {
        if (!faceSoundEligible()) return
        val prepared = source ?: return
        val resolved = watchSoundAssetCandidates(name).firstNotNullOfOrNull { path ->
            val key = normalizeRelativeAsset(prepared.watchDirectoryKey, path)
            val asset = key?.let(prepared.exactFiles::get) ?: prepared.exactFiles[path.lowercase(Locale.ROOT)]
            asset?.takeIf { it.file.canonicalFile.toPath().startsWith(prepared.root.canonicalFile.toPath()) }
        } ?: return
        faceSoundPlayback.play(resolved.file)
    }
    private var physicalScreenInteractive = true
    private var screenReceiverRegistered = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    physicalScreenInteractive = false
                    releaseFaceSound()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val becameBright = !physicalScreenInteractive
                    physicalScreenInteractive = true
                    if (becameBright && !ambientRequested) restartTextAnimation(WatchTextAnimationTrigger.BRIGHT)
                }
            }
        }
    }
    private fun restartTextAnimation(trigger: WatchTextAnimationTrigger) {
        textAnimationTrigger = trigger
        textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
        textAnimationEpochs = textAnimationEpochs.triggered(trigger, textAnimationStartedAtUptimeMs)
        if (trigger == WatchTextAnimationTrigger.BRIGHT) brightSinceUptimeMs = textAnimationStartedAtUptimeMs
        cancelTicks()
        scheduleTicks()
        requestFullRepaint()
    }
    private var ambientMode = false
    private var ambientRequested = false
    private var ambientTransition: Runnable? = null
    private var ambientTransitionDeadlineMs = 0L
    private var brightSinceUptimeMs = SystemClock.uptimeMillis()
    private val displayAttributeCache = java.util.IdentityHashMap<WatchmakerLayer, Map<String, String>>()
    private fun displayAttributes(layer: WatchmakerLayer): Map<String, String> =
        displayAttributeCache.getOrPut(layer) { watchmakerDisplayAttributes(layer, ambientMode) }

    fun setAmbientMode(ambient: Boolean) {
        if (disposed || ambientRequested == ambient) return
        ambientRequested = ambient
        if (ambient) releaseFaceSound()
        ambientTransition?.let(::removeCallbacks)
        ambientTransition = null
        ambientTransitionDeadlineMs = 0L
        notifyVideoPlayback()
        val prepared = source
        val exitDuration = if (ambient && !ambientMode && faceVisible && textExitAnimationEnabled &&
            !editorAnimationPlaybackLimited && prepared != null) prepared.definition.layers
                .filter { it.type.startsWith("text", true) && !it.attributes["anim_out"].isNullOrBlank() }
                .maxOfOrNull { dynamicNumber(it.attributes["dur_out"], 0.5f, prepared).coerceIn(0f, 2f) * 1000 }
                ?.toLong() ?: 0L else 0L
        if (exitDuration > 0L) {
            setTextExitAnimationActive(true)
            val task = Runnable {
                ambientTransition = null
                if (!disposed && ambientRequested) applyAmbientMode(true)
            }
            ambientTransition = task
            ambientTransitionDeadlineMs = SystemClock.uptimeMillis() + exitDuration
            postDelayed(task, exitDuration)
        } else applyAmbientMode(ambient)
    }

    private fun applyAmbientMode(ambient: Boolean) {
        ambientTransitionDeadlineMs = 0L
        ambientMode = ambient
        displayAttributeCache.clear()
        frameDataSnapshotAtMs = 0L
        source?.recycleBakedSegments()
        if (!ambient) {
            brightSinceUptimeMs = SystemClock.uptimeMillis()
            restartTextAnimation(WatchTextAnimationTrigger.BRIGHT)
        }
        setTextExitAnimationActive(false)
        cancelTicks()
        scheduleTicks()
        requestFullRepaint()
        frameGeometryListener?.invoke()
    }

    private var textEntryAnimationEnabled = true
    private var textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
    private var textAnimationTrigger = WatchTextAnimationTrigger.LOAD
    private var textAnimationEpochs = WatchTextAnimationEpochs()
    private var borrowsTextAnimationEpochs = false
    private var textExitAnimationEnabled = true
    private var textExitAnimationActive = false
    private var textExitAnimationStartedAtUptimeMs = 0L
    /** Editor-only gate: null freezes every animated image layer. */
    private var editorAnimationPlaybackLimited = false
    private var editorAnimatedImageLayerIndex: Int? = null
    private var editorAnimationInteractionFrozen = false
    private var editorAnimationFrozenElapsedMs = 0L
    /** Launcher overlay transitions keep the last composed frame stable. */
    private var overlayRenderFrozen = false
    private var overlayFrozenElapsedMs = 0L
    /**
     * 编辑器预览的统一播放/暂停。一个开关同时管住序列帧(image_gif)、视频图层
     * 和重力景深:暂停时三者都停在当前帧/当前位移,恢复时从同一位置继续,不跳回
     * 首帧。与 [editorAnimationInteractionFrozen] 分开是因为后者是手势期间的临时
     * 冻结(自动),这里是用户的显式意图(手动)。
     */
    private var editorPreviewPlaying = true
    private var editorPreviewPausedElapsedMs = 0L
    private var editorPreviewPausedGyroX = 0f
    private var editorPreviewPausedGyroY = 0f
    private var editorVideoPlaybackListener: ((Boolean) -> Unit)? = null
    private var frameGeometryListener: (() -> Unit)? = null
    private var transitionOverlayListener: ((Boolean) -> Unit)? = null
    private var refreshToken = 0
    /** 供 [adoptRenderStateFrom] 复制给补画段；只读不改。 */
    private var boundDescriptor: WatchFaceDescriptor? = null
    private val boundRefreshToken: Int get() = refreshToken
    private var longPressAction: (() -> Unit)? = null
    private var doubleTapAction: (() -> Unit)? = null
    private var tapActionHandler: ((WatchTapAction) -> Unit)? = null
    private var tickPosted = false
    private var dataProvider: WatchDataProvider = EmptyWatchDataProvider
    private var frameDataSnapshot = WatchDataSnapshot()
    private val frameTagResolutionCache = WatchmakerFrameTagResolutionCache()
    /** Caps synchronous static baking so a complex face cannot monopolize one frame. */
    private val flattenBakeBudget = WatchmakerFrameBudget(MAX_FLATTEN_BAKE_BUDGET_NANOS)

    /** 上次取快照的时刻（wall clock）；同 tick 时间窗内复用，见 [refreshFrameDataSnapshot]。 */
    private var frameDataSnapshotAtMs = 0L

    /**
     * 刷新当帧数据快照。
     *
     * tick 判定（[shouldRedrawThisTick]）与紧随其后的绘制（[drawPreparedSource]）
     * 原本各取一次 snapshot —— 30fps 下间隔只有几毫秒，等于每秒白算 30 次。
     * 快照里的量（电量、天气、步数、传感器）本身变化周期都远大于一次 tick，
     * 所以同一 tick 时间窗内直接复用上一份；窗口只覆盖 tick→draw 的间隔、
     * 不跨 tick，除了毫秒级的取值时刻外行为与逐帧取快照完全一致。
     */
    private fun refreshFrameDataSnapshot(nowMs: Long) {
        if (nowMs - frameDataSnapshotAtMs < SNAPSHOT_REUSE_WINDOW_MS) return
        frameDataSnapshotAtMs = nowMs
        frameTagResolutionCache.beginFrame(nowMs)
        frameDataSnapshot = runCatching { dataProvider.snapshot(nowMs) }
            .getOrDefault(WatchDataSnapshot()).let { snapshot ->
                snapshot.copy(brightMode = !ambientMode && snapshot.brightMode != false,
                    millisSinceBright = if (ambientMode) null else SystemClock.uptimeMillis() - brightSinceUptimeMs)
            }
    }
    /**
     * External-data writes arrive through a process-wide receiver, while this View may be
     * rendering a static face with only a one-second tick. Subscribe once per render segment
     * so a slot write schedules an immediate frame instead of waiting for that tick. The Lua
     * runtime still reads the store lazily; this callback only invalidates the View and never
     * touches Lua state from the receiver thread.
     */
    private val externalDataStore = WatchExternalDataStore.of(context)
    private val externalDataListener: (Int) -> Unit = {
        if (!disposed) {
            post {
                if (!disposed && faceVisible) {
                    requestFullRepaint()
                    frameGeometryListener?.invoke()
                }
            }
        }
    }
    /**
     * 画布铺法。取代旧的 `forceFill: Boolean` —— 后者只是这里的
     * [WatchCanvasPreset.FIT] / [WatchCanvasPreset.FILL] 两种取值。
     */
    private var canvasSettings = WatchCanvasSettings.DEFAULT
    private var gravityParallaxEnabled = false
    /**
     * 重力景深表盘底部那层模糊背景图的开关。默认开（保持现有观感）。
     *
     * 关掉之后走 clox 的观感：底下只留根 `bg_color`，不再垫一张自己糊出来的
     * 背景。作者本来就叠了一层背景素材时，两层叠一起会打架。
     */
    private var gravityBackdropBlurEnabled = true
    private var smoothedGyroscopeX = 0f
    private var smoothedGyroscopeY = 0f
    private var gravityBackdropFrame: Bitmap? = null
    private var gravityBackdropBlurFrame: Bitmap? = null
    private var gravityBackdropBlurPrepared = false
    private val gravityBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** 收到 DOWN 后才接受后续事件；取消或隐藏后丢弃旧手势的 UP。 */
    private var touchGestureActive = false
    private var tapHapticsEnabled = true

    /**
     * 本实例负责的 renderPlan 区间；null 表示全量。
     *
     * 视频图层是独立 Surface，像素进不了 Canvas，所以有 video 的表盘要被
     * [WatchmakerWatchFaceStageView] 切成「Canvas 段 / TextureView / Canvas 段」
     * 交替的若干层，每个 Canvas 段是一个只画自己那段的本类实例。
     */
    private var renderRange: IntRange? = null

    /**
     * 是否画根背景。只有首段该画：后续段若也铺一遍不透明 bg_color，
     * 会把夹在中间的 TextureView 整块糊死。
     */
    private var drawBackground = true

    /**
     * 分段渲染的配置。只在段结构变化时调用，不是逐帧接口。
     *
     * [range] 为 null 时恢复全量渲染。
     */
    fun setRenderSegment(range: IntRange?, drawBackground: Boolean) {
        if (renderRange == range && this.drawBackground == drawBackground) return
        renderRange = range
        this.drawBackground = drawBackground
        if (!disposed) requestFullRepaint()
    }

    /**
     * 离屏出图必须无视分段：预览图/缩略图/景深底图都是一张完整的表盘，
     * 而 TextureView 在软件 Canvas 里根本不存在。
     */
    private inline fun <T> withFullRenderPlan(block: () -> T): T {
        val savedRange = renderRange
        val savedBackground = drawBackground
        val savedSuppress = flattenBakingSuppressed
        renderRange = null
        drawBackground = true
        // 离屏出图是一次性的：预览图/缩略图/景深底图各画一次就丢，烘出来的位图
        // 没有第二帧可复用，纯属白占内存。
        flattenBakingSuppressed = true
        return try {
            block()
        } finally {
            renderRange = savedRange
            drawBackground = savedBackground
            flattenBakingSuppressed = savedSuppress
        }
    }

    /** 见 [withFullRenderPlan]：离屏出图期间不烘焙。 */
    private var flattenBakingSuppressed = false

    /**
     * 已经排了一次「素材落地 → 作废烘焙产物」的 post，用于合并同一批解码。
     *
     * 只在主线程读写（[finishAssetLoad] 的 post 体与调度点都在主线程侧）。
     */
    private var pendingFlattenAssetInvalidation = false

    /**
     * 正在烘焙的这个段是否碰到了**还没就绪的素材**（缓存 miss，解码在异步路上）。
     *
     * 由 [requestBitmap] / [requestMovie] 在 miss 分支置位，[bakeFlattenSegments]
     * 每烘一个段前清零、烘完读取 —— 段与段之间不串味，因为烘焙循环是顺序的、
     * 全程在主线程。非烘焙路径也会置位它，无所谓：下一次烘段前一定先清零。
     *
     * 这是「素材落地后只作废缺素材的段」的判据。没有它就只能整批回收：312 有
     * 300+ 图层在异步解码，一波持续两三秒，每次落地把 16 段全部作废重建，
     * 每帧配额只是把白烧封顶成 2 段，永远收敛不了。
     */
    private var flattenBakeAssetMiss = false

    /** 编辑器正在实时改写某个图层的变换 —— 它可能落在某个烘焙段里。 */
    private fun editorLiveTransformActive(): Boolean = liveTransformLayerIndex >= 0 || liveLayerTransforms.isNotEmpty()

    /**
     * 视频图层在 View 空间的最终变换，供宿主喂给 TextureView。
     *
     * 与 Canvas 路径共用 [composeLayerMatrix]，并按同一套 anchor 语义把
     * 单位矩形映射到图层声明的 width/height —— TextureView 的内容默认铺满自身
     * 尺寸，所以这里返回的矩阵包含「View 全尺寸 → 图层目标矩形」那一步。
     *
     * 返回 null 表示这一帧不该显示（opacity 为 0、display 求值为假、
     * 或图层不存在）。
     */
    internal fun videoLayerGeometry(layerIndex: Int): WatchVideoLayerGeometry? {
        if (disposed || width <= 0 || height <= 0) return null
        val prepared = source ?: return null
        val item = prepared.renderPlan.firstOrNull { it.layerIndex == layerIndex } ?: return null
        val layer = prepared.definition.layers.getOrNull(layerIndex) ?: return null

        val matrix = videoMatrixScratch
        // 自算而不是复用上一帧 drawPreparedSource 留下的值：宿主可能在首帧
        // 之前就来问几何，那时 canvasBaseMatrix 还是单位矩阵。
        matrix.set(
            composeCanvasBaseMatrix(
                canvasBaseMatrix,
                watchCanvasTransform(
                    viewWidth = width.toFloat(),
                    viewHeight = height.toFloat(),
                    settings = canvasSettings,
                    designWidth = prepared.designExtentWidth,
                    designHeight = prepared.designExtentHeight,
                ),
                prepared.designExtentWidth,
                prepared.designExtentHeight,
            ),
        )
        var alpha = 1f
        // 祖先组的变换按外→内累乘，与 drawRenderItem 的遍历顺序一致。
        var ancestorCount = 0
        var ancestor = item.ancestorChain
        while (ancestor != null && ancestorCount < ancestorIndexScratch.size) {
            ancestorIndexScratch[ancestorCount++] = ancestor.layerIndex
            ancestor = ancestor.outer
        }
        for (scratchIndex in ancestorCount - 1 downTo 0) {
            val ancestorIndex = ancestorIndexScratch[scratchIndex]
            val ancestorLayer = prepared.definition.layers[ancestorIndex]
            val attributes = displayAttributes(ancestorLayer)
            alpha *= dynamicNumber(attributes["opacity"], 100f, prepared)
                .coerceIn(0f, 100f) / 100f
            if (alpha <= 0f || !isDisplayed(attributes["display"], prepared)) return null
            matrix.preConcat(
                composeLayerMatrix(
                    layerMatrixScratch,
                    attributes,
                    prepared,
                    ancestorIndex,
                    ancestorLayer.id,
                ),
            )
        }
        val attrs = displayAttributes(layer)
        alpha *= dynamicNumber(attrs["opacity"], 100f, prepared).coerceIn(0f, 100f) / 100f
        if (alpha <= 0f || !isDisplayed(attrs["display"], prepared)) return null
        matrix.preConcat(
            composeLayerMatrix(layerMatrixScratch, attrs, prepared, layerIndex, layer.id),
        )

        // 视频没有「原生尺寸」可依（解码尺寸要等 prepare 完成，而几何每帧都要），
        // 所以按编辑器侧同一套约定：未声明就占满设计画布。
        val sharedSize = attrs["size"]
        val designWidth = dynamicNumber(
            attrs["width"] ?: sharedSize,
            prepared.designExtentWidth,
            prepared,
        ).coerceAtLeast(1f)
        val designHeight = dynamicNumber(
            attrs["height"] ?: sharedSize,
            prepared.designExtentHeight,
            prepared,
        ).coerceAtLeast(1f)
        val dst = anchoredRect(designWidth, designHeight, attrs["alignment"])
        // TextureView 内容铺满 View 全尺寸，先把它压缩到 1×1 再放大到目标矩形，
        // 这样上面那串设计空间的变换才作用在正确的盒子上。
        matrix.preTranslate(dst.left, dst.top)
        matrix.preScale(designWidth / width, designHeight / height)

        return WatchVideoLayerGeometry(matrix, alpha)
    }

    /**
     * renderPlan 里全部 video 图层的位置，按绘制顺序返回。
     *
     * 宿主用它切分段：每个 planIndex 之前的部分是一个 Canvas 段。
     */
    internal fun videoLayerPlanEntries(): List<WatchVideoLayerPlanEntry> {
        if (disposed) return emptyList()
        val prepared = source ?: return emptyList()
        return prepared.renderPlan.mapIndexedNotNull { planIndex, item ->
            val layer = prepared.definition.layers.getOrNull(item.layerIndex)
                ?: return@mapIndexedNotNull null
            if (!layer.type.equals("video", ignoreCase = true)) return@mapIndexedNotNull null
            val rawPath = layer.attributes["path"] ?: layer.attributes["source"] ?: ""
            val evaluated = evaluateText(rawPath, prepared)
            val resolved = resolveAsset(evaluated, prepared, conventionalDirectory = "videos")
                ?: return@mapIndexedNotNull null
            if (resolved.file.extension.lowercase(Locale.ROOT) !in PLAYABLE_VIDEO_EXTENSIONS) {
                return@mapIndexedNotNull null
            }
            WatchVideoLayerPlanEntry(
                planIndex = planIndex,
                layerIndex = item.layerIndex,
                absolutePath = resolved.file.absolutePath,
            )
        }
    }

    // 编辑器画布拖拽的实时变换覆盖（按图层文档序号定位）。
    private var liveTransformLayerIndex = -1
    private var liveTransformLayerId: String? = null
    private var liveTransformX = 0f
    private var liveTransformY = 0f
    private var liveTransformRotation = 0f
    private var liveTransformScaleX = 1f
    private var liveTransformScaleY = 1f
    private var liveLayerTransforms = emptyList<WatchmakerLiveLayerTransform>()
    private var editorPreviewRoundOverride: Boolean? = null
    private val liveTransformHandoff = WatchLiveTransformHandoff()

    /**
     * 编辑器手势期间逐帧调用：被拖图层的表盘像素立即跟随手指渲染，
     * 不等 XML 落盘。scale 传 1.0 基准（内部即画布 scale 因子）。
     */
    fun setLiveLayerTransform(
        layerIndex: Int,
        layerId: String? = null,
        x: Float,
        y: Float,
        rotationDegrees: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        liveTransformLayerIndex = layerIndex
        liveLayerTransforms = emptyList()
        liveTransformLayerId = layerId?.takeIf(String::isNotBlank)
        liveTransformX = x
        liveTransformY = y
        liveTransformRotation = rotationDegrees
        liveTransformScaleX = scaleX
        liveTransformScaleY = scaleY
        requestFullRepaint()
    }

    fun setLiveLayerTransforms(transforms: List<WatchmakerLiveLayerTransform>) {
        liveTransformLayerIndex = -1
        liveTransformLayerId = null
        liveLayerTransforms = transforms.toList()
        requestFullRepaint()
    }

    fun setEditorPreviewRoundOverride(round: Boolean?) {
        if (editorPreviewRoundOverride == round) return
        editorPreviewRoundOverride = round
        requestFullRepaint()
    }

    fun resolveEditorLayerTransform(layerIndex: Int): WatchmakerLiveLayerTransform? {
        val prepared = source ?: return null
        val layer = prepared.definition.layers.getOrNull(layerIndex) ?: return null
        val attrs = displayAttributes(layer)
        val shared = attrs["anim_scale"] ?: attrs["scale"]
        return WatchmakerLiveLayerTransform(
            layerIndex, layer.id,
            dynamicNumber(attrs["x"], 0f, prepared), dynamicNumber(attrs["y"], 0f, prepared),
            dynamicNumber(attrs["rotation"], 0f, prepared),
            dynamicNumber(attrs["anim_scale_x"] ?: attrs["scale_x"] ?: shared, 100f, prepared) / 100f,
            dynamicNumber(attrs["anim_scale_y"] ?: attrs["scale_y"] ?: shared, 100f, prepared) / 100f,
        )
    }

    /** Called when a new pointer gesture takes ownership of the live override. */
    fun cancelLiveLayerTransformHandoff() {
        liveTransformHandoff.cancel()
    }

    /** 手势结束（提交/取消）后清除覆盖，回到以 XML 为准的渲染。 */
    fun clearLiveLayerTransform() {
        liveTransformHandoff.cancel()
        if (clearLiveLayerTransformInternal()) invalidate()
    }

    /**
     * Keeps the release-frame transform visible until [refreshToken] has been
     * parsed and published. The actual clear happens inside [bind], in the same
     * main-thread transaction that swaps [source], so no old-geometry frame can
     * appear between pointer-up and the committed renderer state.
     */
    fun handoffLiveLayerTransformOnSourceReady(refreshToken: Int) {
        if (!editorLiveTransformActive()) return
        val key = descriptorKey ?: return
        liveTransformHandoff.arm(key, refreshToken)
        if (
            loadedDescriptorKey == key &&
            loadedRefreshToken == refreshToken &&
            liveTransformHandoff.consumeIfReady(key, refreshToken)
        ) {
            if (clearLiveLayerTransformInternal()) invalidate()
        }
    }

    private fun clearLiveLayerTransformInternal(): Boolean {
        if (!editorLiveTransformActive()) return false
        liveTransformLayerIndex = -1
        liveTransformLayerId = null
        liveLayerTransforms = emptyList()
        return true
    }

    private fun completeLiveTransformHandoffIfReady(
        descriptorKey: String,
        refreshToken: Int,
    ) {
        if (liveTransformHandoff.consumeIfReady(descriptorKey, refreshToken)) {
            clearLiveLayerTransformInternal()
        }
    }

    /**
     * 编辑器图层面板缩略图：把单个图层（含其祖先组的变换链）按整表比例
     * 渲染进 [sizePx]² 位图。完全复用 drawRenderItem 主渲染路径与已就绪的
     * 资源缓存，不另建一套图元实现（编辑器侧近似渲染会和真实效果偏差）。
     */
    fun renderLayerThumbnail(layerIndex: Int, sizePx: Int): Bitmap? {
        if (disposed || sizePx <= 0) return null
        val prepared = source ?: return null
        val item = prepared.renderPlan.firstOrNull { it.layerIndex == layerIndex } ?: return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = sizePx / DESIGN_SIZE
        canvas.scale(scale, scale)
        canvas.translate(DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        // baseScale 传 1：这张缩略图自己的 canvas.scale 是等比的，画布基变换与它
        // 无关（那是主渲染路径的状态，此刻可能还停在上一帧）。
        runCatching {
            withFullRenderPlan { drawRenderItem(canvas, item, prepared, baseScaleX = 1f, baseScaleY = 1f) }
        }
        return bitmap
    }

    /**
     * Resolves a text layer exactly like the live face, but returns only its
     * current textual content. The editor uses this to build an independent,
     * centered thumbnail without inheriting the layer's x/y/rotation/scale.
     */
    fun resolveTextLayerPreview(layerIndex: Int): String? {
        if (disposed) return null
        val prepared = source ?: return null
        val layer = prepared.definition.layers.getOrNull(layerIndex) ?: return null
        if (!layer.type.lowercase(Locale.ROOT).contains("text")) return null
        val raw = displayAttributes(layer)["text"].orEmpty()
            .ifBlank { displayAttributes(layer)["value"].orEmpty() }
        if (raw.isBlank()) return null
        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot()).let { snapshot ->
                snapshot.copy(brightMode = !ambientMode && snapshot.brightMode != false,
                    millisSinceBright = if (ambientMode) null else SystemClock.uptimeMillis() - brightSinceUptimeMs)
            }
        val resolved = evaluateText(raw, prepared)
        return when (displayAttributes(layer)["transform"].orEmpty().lowercase(Locale.ROOT)) {
            "uc", "uppercase" -> resolved.uppercase(Locale.getDefault())
            "lc", "lowercase" -> resolved.lowercase(Locale.getDefault())
            else -> resolved
        }.takeIf(String::isNotBlank)
    }

    /**
     * Renders one plain text layer as an independent, tightly-fitted thumbnail.
     *
     * The live renderer supplies the expression result, font (including BMFont),
     * colour, outline and alignment. We intentionally do not apply the layer's
     * x/y/rotation/scale or ancestor transforms: the layer list needs a readable
     * material preview, not a crop of its position on the watch canvas.
     */
    fun renderTextLayerThumbnail(layerIndex: Int, sizePx: Int): Bitmap? {
        if (disposed || sizePx <= 0) return null
        val prepared = source ?: return null
        val layer = prepared.definition.layers.getOrNull(layerIndex) ?: return null
        val normalizedType = prepared.normalizedLayerTypes.getOrNull(layerIndex)
            ?: layer.type.lowercase(Locale.ROOT)
        if (normalizedType !in setOf("text", "text_curved", "text_ring")) return null
        val raw = displayAttributes(layer)["text"].orEmpty()
            .ifBlank { displayAttributes(layer)["value"].orEmpty() }
        if (raw.isBlank()) return null
        val thumbnailAttributes = if (displayAttributes(layer)["text"].isNullOrBlank()) {
            displayAttributes(layer) + ("text" to raw)
        } else {
            displayAttributes(layer)
        }

        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot()).let { snapshot ->
                snapshot.copy(brightMode = !ambientMode && snapshot.brightMode != false,
                    millisSinceBright = if (ambientMode) null else SystemClock.uptimeMillis() - brightSinceUptimeMs)
            }

        // Render once in the canonical design space so even small text retains
        // enough antialiasing detail before it is fitted into the 40dp row tile.
        val workingSize = DESIGN_SIZE.roundToInt().coerceAtLeast(1)
        val working = Bitmap.createBitmap(workingSize, workingSize, Bitmap.Config.ARGB_8888)
        val workingCanvas = Canvas(working)
        val designScale = workingSize / DESIGN_SIZE
        workingCanvas.scale(designScale, designScale)
        workingCanvas.translate(DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        runCatching {
            when (normalizedType) {
                "text_curved" -> drawCurvedText(workingCanvas, thumbnailAttributes, 1f, prepared)
                "text_ring" -> drawTextRing(workingCanvas, thumbnailAttributes, 1f, prepared)
                else -> drawText(workingCanvas, thumbnailAttributes, 1f, prepared)
            }
        }

        val pixels = IntArray(workingSize * workingSize)
        working.getPixels(pixels, 0, workingSize, 0, 0, workingSize, workingSize)
        var left = workingSize
        var top = workingSize
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            if (color ushr 24 == 0) return@forEachIndexed
            val x = index % workingSize
            val y = index / workingSize
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right < left || bottom < top) {
            working.recycle()
            return null
        }

        val sourcePadding = 3
        val sourceRect = Rect(
            (left - sourcePadding).coerceAtLeast(0),
            (top - sourcePadding).coerceAtLeast(0),
            (right + sourcePadding + 1).coerceAtMost(workingSize),
            (bottom + sourcePadding + 1).coerceAtMost(workingSize),
        )
        val available = (sizePx - 6).coerceAtLeast(1).toFloat()
        val fitScale = min(
            available / sourceRect.width().coerceAtLeast(1),
            available / sourceRect.height().coerceAtLeast(1),
        )
        val drawnWidth = (sourceRect.width() * fitScale).coerceAtLeast(1f)
        val drawnHeight = (sourceRect.height() * fitScale).coerceAtLeast(1f)
        val destination = RectF(
            (sizePx - drawnWidth) / 2f,
            (sizePx - drawnHeight) / 2f,
            (sizePx + drawnWidth) / 2f,
            (sizePx + drawnHeight) / 2f,
        )
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            working,
            sourceRect,
            destination,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        working.recycle()
        return output
    }

    /**
     * 把整块表盘离屏渲染成位图，用于重新生成 preview.jpg。
     *
     * 与 [onDraw] 同一套坐标契约（居中原点 + 方形/圆形裁剪 + 背景色 + 全部
     * renderPlan），但不依赖 View 的宽高，所以未上屏也能出图。编辑器保存时
     * 用它覆盖草稿里作者自带的 preview.jpg —— 否则表盘列表和商店永远显示
     * 原作者那张预览（老师报的「改完保存甚至上传都不换预览图」）。
     */
    fun renderFaceBitmap(sizePx: Int): Bitmap? {
        if (disposed || sizePx <= 0) return null
        val prepared = source ?: return null
        val extentW = prepared.designExtentWidth
        val extentH = prepared.designExtentHeight
        // sizePx 作为长边预算，短边按声明比例收窄：方形表盘仍出 sizePx² 位图，
        // 非方表盘出的 preview.jpg 比例才和实际渲染一致。
        val scale = sizePx / max(extentW, extentH)
        val bitmapWidth = (extentW * scale).roundToInt().coerceAtLeast(1)
        val bitmapHeight = (extentH * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val backdrop = if (
            gravityParallaxEnabled && gravityBackdropBlurEnabled && prepared.hasGyroParallax
        ) {
            resolveGravityBackdrop(prepared)
        } else {
            null
        }
        withFullRenderPlan {
            drawPreparedSource(
                canvas = Canvas(bitmap),
                prepared = prepared,
                targetWidth = bitmapWidth.toFloat(),
                targetHeight = bitmapHeight.toFloat(),
                gravityBackdrop = backdrop,
                // Saved thumbnails represent the authored neutral pose, not the
                // sensor sample that happened to be active when Save was pressed.
                gyroscopeX = 0f,
                gyroscopeY = 0f,
            )
        }
        return bitmap
    }

    private val bitmapCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    /** Small transient LRU so long frame sequences cannot evict static face assets. */
    private val animatedBitmapCache = LinkedHashMap<String, Bitmap>(4, 0.75f, true)

    /**
     * 位图字体图集专用 LRU，**独立于 [bitmapCache] 的字节预算**。
     *
     * 图集不降采样（见 [MAX_FONT_ATLAS_DECODE_DIMENSION_PX]），一张 1042² 就是
     * 4.3MB、2048² 是 16MB —— 混在 24MB 的图片预算里，它一进来就把背景图挤出去，
     * 下一层画背景又把它挤出去，两边都是 `recycle()`，于是每帧解两次几 MB 的图。
     * 图集这边的 `pageBitmaps` 还持着已回收的引用，`isRecycled` 守卫一触发就再解
     * 一次 —— 这就是「背景图切换时卡」和「怎么滑都掉帧」的同一个根因。
     *
     * 一张表盘的图集通常只有 1~2 张，给它自己一档预算，两边就都不会再互相驱逐。
     */
    private val fontAtlasCache = LinkedHashMap<String, Bitmap>(4, 0.75f, true)
    /** Retired bitmaps wait for two UI frames before RenderThread ownership is released. */
    private val pendingBitmapRetirements = IdentityHashMap<Bitmap, WatchmakerBitmapLease>()
    private val bitmapRetirementHandler = Handler(Looper.getMainLooper())
    private val movieCache = LinkedHashMap<String, Movie>(4, 0.75f, true)
    /**
     * 帧序列图层上一次成功画出的位图，按图层的完整 path 表达式索引。
     *
     * GIF 帧序列（`a.png`b.png`c.png`）每帧是独立文件、各有各的 cacheRevision，
     * 所以 [requestBitmap] 里那套「同一素材的上一帧」兜底跨不过帧边界：切帧那
     * 一瞬新键必然未命中，整层直接 return 什么都不画 —— 每换一帧黑一下，就是
     * GIF 持续闪烁的根因。这里按图层维度记住最后画成功的那张，解码没跟上时
     * 继续画它，宁可停在旧帧也绝不闪黑。
     *
     * 只在主线程的 onDraw 里读写，不需要 cacheLock。
     */
    private val lastDrawnFrameByLayer =
        LinkedHashMap<WatchImageFallbackKey, Bitmap>(MAX_LAST_FRAME_ENTRIES, 0.75f, true)
    /** Raw GIF equivalent of [lastDrawnFrameByLayer], used while a replacement Movie decodes. */
    private val lastDrawnMovieByLayer = LinkedHashMap<WatchImageFallbackKey, Movie>(4, 0.75f, true)
    /** Avoids scanning the tiny fallback LRUs unless a layer actually changes material. */
    private val lastMaterialExpressionByLayer = HashMap<String, String>()
    private var bitmapCacheBytes = 0L
    private var animatedBitmapCacheBytes = 0L
    private var fontAtlasCacheBytes = 0L
    private val activeAssetLoads = HashSet<AssetLoadClaim>(MAX_CONCURRENT_ASSET_LOADS)
    /** Asset key -> bind generation which owns the in-flight decode. */
    private val bitmapLoading = HashMap<String, Long>()
    private val movieLoading = HashMap<String, Long>()
    /** Failed assets are retried only after a cooldown, never once per frame. */
    private val bitmapFailures = LinkedHashMap<String, BitmapFailure>(16, 0.75f, true)
    private val movieFailures = LinkedHashMap<String, BitmapFailure>(8, 0.75f, true)
    /** Typeface key -> bind generation which owns the in-flight load. */
    private val typefaceLoading = HashMap<String, Long>()
    private val typefaceCache = LinkedHashMap<String, Typeface>(8, 0.75f, true)
    private val cacheLock = Any()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val textShaderPainter = WatchTextShaderPainter()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceTransitionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** 描边通道专用；绝不复用 textPaint/shapePaint，避免污染其 style/strokeWidth。 */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    /** 位图字体描边副本专用（带 SRC_IN 滤色把字形染成描边色）。 */
    private val bitmapOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /**
     * 阴影通道专用。阴影是本体的**实心**偏移拷贝（clox 里就是一份 setColor 过的
     * label/sprite），所以必须是 FILL —— 不能蹭 [outlinePaint]，那个是 STROKE。
     */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
    }
    /** 位图字体阴影副本专用（SRC_IN 把字形整串染成阴影色）。 */
    private val bitmapShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pathScratch = Path()
    private val rectScratch = RectF()
    /** 环端帽（[drawRingCap]）专用；与 [pathScratch] 分开，避免和图层裁剪路径争用。 */
    private val ringCapPath = Path()
    private val ringCapRect = RectF()
    /** `addRoundRect` 的八个半径（四角各 x/y）；每次画帽前整块重填。 */
    private val ringCapRadii = FloatArray(8)
    /** [composeLayerMatrix] 的逐帧复用缓冲；concat 会立即读走内容。 */
    private val layerMatrixScratch = Matrix()
    /** 视频图层几何计算专用，不与 [layerMatrixScratch] 争用。 */
    private val videoMatrixScratch = Matrix()
    /** 设计空间 → View 空间的画布变换，每帧在 [drawPreparedSource] 里重算。 */
    private val canvasBaseMatrix = Matrix()

    /**
     * 本帧画布基变换的两轴缩放，[drawPreparedSource] 每帧写一次。
     *
     * STRETCH 预设与「画布调节」里 zoomX≠zoomY 都会让它非等比，那一样会把字形
     * 横向拉扁 —— 与图层自身的 `anim_scale_x` 串联，共同决定 [textNeedsLinearRaster]。
     */
    private var frameCanvasScaleX = 1f
    private var frameCanvasScaleY = 1f

    /**
     * [composeLayerMatrix] 每次求值后写下这一层的两轴缩放，供 [drawRenderItem]
     * 沿祖先链累乘。刻意做成字段而不是返回值：那个函数的返回值是 Matrix，
     * 视频图层路径也在用，不能改签名。
     */
    private var lastComposedScaleX = 1f
    private var lastComposedScaleY = 1f

    /**
     * 当前正在绘制的图层「祖先链 × 自身」的累计缩放，[drawRenderItem] 逐层写入。
     *
     * 文字绘制路径读它判断要不要开 [Paint.LINEAR_TEXT_FLAG]（见
     * [textNeedsLinearRaster]）。烘焙离屏路径也走 drawRenderItem，所以两条路径
     * 的判定天然一致。
     */
    private var layerScaleX = 1f
    private var layerScaleY = 1f
    private val bitmapFontSourceRect = Rect()
    private val bitmapFontDestRect = RectF()
    private val ancestorIndexScratch = IntArray(MAX_WATCH_PARENT_DEPTH)
    private val calendar = Calendar.getInstance()
    private var frameStartedAtUptimeMs = SystemClock.uptimeMillis()
    private val tapTargets = ArrayList<TapTarget>(8)

    /**
     * `color_switch_*` 选中的强调色，`null` 表示用表盘自己的 `ucolor_default`。
     *
     * 存这一层而不是直接改 `definition.rootAttributes`：后者是导入期产物、被多个
     * PreparedSource 共享，改了会污染同一份定义的其它实例（编辑器预览与桌面同屏时
     * 就是两个实例）。
     */
    private var userColorOverride: String? = null

    /** 已经为哪些表盘弹过位置权限（按 `rootCacheKey`）。拒了就不再反复问。 */
    private val locationPromptedFaceKeys = HashSet<String>(2)
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = faceVisible

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (faceVisible) doubleTapAction?.invoke()
            return faceVisible
        }

        // Release is actionable immediately; double-tap locking may follow it.
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (!faceVisible) return false
            dispatchTap(event.x, event.y)
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            if (faceVisible) longPressAction?.invoke()
        }
    }
    private val gestureDetector = GestureDetector(context, gestureListener).apply {
        // Without a lock action, every tap (including rapid taps) is independent.
        setOnDoubleTapListener(null)
    }

    private fun cancelPendingGesture() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            gestureDetector.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        touchGestureActive = false
    }

    fun setTapHapticsEnabled(enabled: Boolean) {
        tapHapticsEnabled = enabled
    }

    init {
        externalDataStore.addListener(externalDataListener)
    }

    /**
     * 让补画段跟随首段渲染同一张表盘。
     *
     * 只复制「画出正确像素」所需的状态：素材来源、数据源、画布铺法、重力开关、
     * 播放位置。手势回调、tap_action、过渡帧一概不传 —— 那些是首段独有的职责，
     * 补画段重复持有只会让同一次点击被处理多次。
     *
     * ⚠️ 画布配置必须整份复制（不只是预设）：各段共用一个设计空间，任一段的
     * zoom / offset 与首段不一致，视频段与 Canvas 段之间就会错开一截。
     */
    internal fun adoptRenderStateFrom(primary: WatchmakerWatchFaceView) {
        if (disposed) return
        val descriptor = primary.boundDescriptor ?: return
        borrowsTextAnimationEpochs = true
        inheritedLuaRuntime = primary.source?.luaRuntime
        setWatchDataProvider(primary.dataProvider)
        setCanvasSettings(primary.canvasSettings)
        setGravityParallaxEnabled(primary.gravityParallaxEnabled)
        setGravityBackdropBlurEnabled(primary.gravityBackdropBlurEnabled)
        bind(
            descriptor = descriptor,
            refreshToken = primary.boundRefreshToken,
            isFaceVisible = primary.faceVisible,
            onLongPress = null,
            onDoubleTap = null,
            onLoadFailure = { _, _ -> },
            textEntryAnimationEnabled = primary.textEntryAnimationEnabled,
            textExitAnimationEnabled = primary.textExitAnimationEnabled,
        )
        // 播放位置必须对齐，否则各段的序列帧会各播各的。
        if (ambientMode != primary.ambientMode || ambientRequested != primary.ambientRequested) {
            ambientTransition?.let(::removeCallbacks)
            ambientTransition = null
            ambientRequested = primary.ambientRequested
            applyAmbientMode(primary.ambientMode)
            val remaining = primary.ambientTransitionDeadlineMs - SystemClock.uptimeMillis()
            if (primary.ambientRequested && !primary.ambientMode && remaining > 0L) {
                ambientTransitionDeadlineMs = primary.ambientTransitionDeadlineMs
                val task = Runnable {
                    ambientTransition = null
                    if (!disposed && ambientRequested) applyAmbientMode(true)
                }
                ambientTransition = task
                postDelayed(task, remaining)
            }
        }
        textAnimationTrigger = primary.textAnimationTrigger
        textAnimationStartedAtUptimeMs = primary.textAnimationStartedAtUptimeMs
        textAnimationEpochs = primary.textAnimationEpochs
        textExitAnimationStartedAtUptimeMs = primary.textExitAnimationStartedAtUptimeMs
        textExitAnimationActive = primary.textExitAnimationActive
        brightSinceUptimeMs = primary.brightSinceUptimeMs
        frameStartedAtUptimeMs = primary.frameStartedAtUptimeMs
        editorPreviewPlaying = primary.editorPreviewPlaying
        editorPreviewPausedElapsedMs = primary.editorPreviewPausedElapsedMs
        // 手势期间的实时变换与冻结状态也要跟过来。刚导入视频那一刻分段会被重建，
        // 如果此时正在拖一个压在视频上的图层，新建出来的补画段不继承覆盖就会
        // 瞬间跳回 XML 里的旧位置。
        editorAnimationInteractionFrozen = primary.editorAnimationInteractionFrozen
        editorAnimationFrozenElapsedMs = primary.editorAnimationFrozenElapsedMs
        setOverlayRenderFrozen(primary.overlayRenderFrozen)
        overlayFrozenElapsedMs = primary.overlayFrozenElapsedMs
        editorPreviewRoundOverride = primary.editorPreviewRoundOverride
        if (primary.liveLayerTransforms.isNotEmpty()) {
            setLiveLayerTransforms(primary.liveLayerTransforms)
        } else if (primary.liveTransformLayerIndex >= 0) {
            setLiveLayerTransform(
                layerIndex = primary.liveTransformLayerIndex,
                layerId = primary.liveTransformLayerId,
                x = primary.liveTransformX,
                y = primary.liveTransformY,
                rotationDegrees = primary.liveTransformRotation,
                scaleX = primary.liveTransformScaleX,
                scaleY = primary.liveTransformScaleY,
            )
        } else {
            clearLiveLayerTransformInternal()
        }
    }

    fun bind(
        descriptor: WatchFaceDescriptor,
        refreshToken: Int,
        isFaceVisible: Boolean,
        onLongPress: (() -> Unit)?,
        onDoubleTap: (() -> Unit)?,
        onLoadFailure: (WatchFaceDescriptor, Throwable) -> Unit,
        onTapAction: ((WatchTapAction) -> Unit)? = null,
        onSourceReady: ((Int) -> Unit)? = null,
        /**
         * 渲染真正静默（本帧画出来的就是完整画面）时回调，重新绑定就绪内容时同步状态。
         * 与 [onSourceReady] 的区别：后者只表示结构就绪，位图/GIF/字体还没来。
         */
        onRenderReady: ((Int) -> Unit)? = null,
        textEntryAnimationEnabled: Boolean = true,
        textExitAnimationEnabled: Boolean = true,
    ) {
        if (disposed) return
        require(descriptor.isWatchmaker) { "Watchmaker view requires a standard .watch descriptor" }
        if (descriptorKey != descriptor.stableKey || this.refreshToken != refreshToken ||
            (faceVisible && !isFaceVisible) ||
            ((doubleTapAction != null) != (onDoubleTap != null))
        ) {
            cancelPendingGesture()
        }
        gestureDetector.setOnDoubleTapListener(if (onDoubleTap != null) gestureListener else null)
        frameTagResolutionCache.clear()
        onRenderReadyCallback = onRenderReady
        this.faceVisible = isFaceVisible
        notifyVideoPlayback()
        this.textEntryAnimationEnabled = textEntryAnimationEnabled
        this.textExitAnimationEnabled = textExitAnimationEnabled
        this.longPressAction = onLongPress
        this.doubleTapAction = onDoubleTap
        this.tapActionHandler = onTapAction
        this.boundDescriptor = descriptor
        if (descriptorKey == descriptor.stableKey && this.refreshToken == refreshToken) {
            if (faceVisible || textExitAnimationActive) scheduleTicks() else cancelTicks()
            if (
                loadedDescriptorKey == descriptor.stableKey &&
                loadedRefreshToken == refreshToken
            ) {
                completeLiveTransformHandoffIfReady(descriptor.stableKey, refreshToken)
            }
            // ⚠️ 这条路径**不能**无条件走 requestFullRepaint()（老师报的「312 滑下进
            // 控制中心大卡一下，退出也卡」的第一个根因）。
            //
            // bind() 是从 [WatchmakerWatchFaceHost] 的 AndroidView update 块里调的，
            // 也就是**每次重组都会跑一遍**。滑动手势期间宿主每帧重组，descriptor 与
            // refreshToken 全程不变，于是每帧都落到这个早退分支；而
            // requestFullRepaint() 会 invalidateAll() 把慢桶追踪器打回 pristine，
            // 下一帧烘焙路径 consume 到失效信号就 recycleBakedSegments() —— 312 的
            // 16 张段位图**每帧**回收重建一遍，正好压在转场动画上。
            //
            // 这个分支里真正会改变渲染结果的只有入场动画时钟被重置（enteringFace /
            // enablingAnimation，都是一次性边沿）。其余入参（canvasSettings、
            // dataProvider、重力开关）各有自己的 setter，且每个都自带相等性判断和
            // 自己的 requestFullRepaint()，不需要这里代劳。
            //
            // 慢桶取值真变了也不会漏：poll() 每拍都无条件比对，变化由它置位。
            if (!overlayRenderFrozen) {
                invalidate()
            }
            if (
                loadedDescriptorKey == descriptor.stableKey &&
                loadedRefreshToken == refreshToken
            ) {
                onSourceReady?.invoke(refreshToken)
            }
            // 内容仍在时，重新绑定的宿主也需要拿到就绪状态。绘制路径按表盘/令牌
            // 去重，只靠下一帧不会再次通知，宿主会误等到加载覆层超时。
            if (
                renderReadyDescriptorKey == descriptor.stableKey &&
                renderReadyRefreshToken == refreshToken
            ) {
                onRenderReady?.invoke(refreshToken)
            }
            return
        }
        val previousDescriptorKey = loadedDescriptorKey
        val reloadMode = watchSourceReloadMode(
            currentDescriptorKey = descriptorKey,
            nextDescriptorKey = descriptor.stableKey,
            hasPreparedSource = source != null,
        )
        descriptorKey = descriptor.stableKey
        this.refreshToken = refreshToken
        // 新一代开始：就绪保底时限从这里起算，并清掉上一代的「已报」标记。
        renderReadyDeadlineUptimeMs = SystemClock.uptimeMillis() + WATCH_RENDER_READY_MAX_HOLD_MS
        renderReadyDescriptorKey = null
        renderReadyRefreshToken = null
        val generation = ++bindGeneration
        loadJob?.cancel()
        assetGenerationJob.cancel()
        assetGenerationJob = SupervisorJob(lifetimeJob)
        assetScope = CoroutineScope(assetGenerationJob + Dispatchers.IO)
        if (reloadMode == WatchSourceReloadMode.CLEAR_BEFORE_LOAD) {
            cancelTicks()
            source?.clearRuntimeCaches()
            source = null
            clearAssetCaches()
            invalidate()
        } else {
            synchronized(cacheLock) {
                bitmapLoading.clear()
                movieLoading.clear()
                bitmapFailures.clear()
                movieFailures.clear()
                typefaceLoading.clear()
            }
        }
        val root = descriptor.sourceDirPath?.let(::File)
        if (root == null) {
            descriptorKey = null
            onLoadFailure(descriptor, IllegalArgumentException(".watch 表盘没有安装目录"))
            return
        }
        loadJob = mainScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    prepareSource(root, generation)
                }
                if (
                    generation != bindGeneration ||
                    prepared.sourceGeneration != generation ||
                    disposed
                ) {
                    prepared.clearRuntimeCaches()
                    return@launch
                }
                val current = source
                val replacement = current?.takeIf {
                    it.rootCacheKey == prepared.rootCacheKey &&
                        it.definitionRevision == prepared.definitionRevision
                } ?: prepared
                if (replacement !== prepared) prepared.clearRuntimeCaches()
                if (
                    replacement !== current &&
                    current != null &&
                    previousDescriptorKey != null &&
                    previousDescriptorKey != descriptor.stableKey
                ) {
                    installSourceTransitionFrame(
                        frame = capturePreparedFrame(current),
                        generation = generation,
                    )
                }
                if (replacement !== current) {
                    current?.clearRuntimeCaches()
                    gravityBackdropFrame = null
                    gravityBackdropBlurFrame = null
                    gravityBackdropBlurPrepared = false
                }
                displayAttributeCache.clear()
                if (replacement !== current) releaseFaceSound()
                source = replacement
                // 换表盘要重新载入这张表盘自己的配色索引：索引是按 rootCacheKey 存的，
                // 上一张表盘循环到第 7 档，不该让下一张也从第 7 档开始。
                restoreWatchColorSwitchSelection(replacement)
                requestLocationPermissionIfFaceNeedsIt(replacement)
                loadedDescriptorKey = descriptor.stableKey
                loadedRefreshToken = refreshToken
                completeLiveTransformHandoffIfReady(descriptor.stableKey, refreshToken)
                // A genuine source replacement starts a new source timeline.
                // Borrowed segments already received the primary's two epochs;
                // their asynchronous parse must not overwrite that shared state.
                if (replacement !== current && !borrowsTextAnimationEpochs) {
                    restartTextAnimation(WatchTextAnimationTrigger.LOAD)
                }
                if (replacement !== current && previousDescriptorKey != descriptor.stableKey) {
                    // 不同表盘切换：重置 GIF / 帧序列计时，从第0帧开始播。
                    // 同表盘刷新（reloadMode == KEEP_CURRENT_UNTIL_READY）时保留
                    // 旧计时器，GIF 从中断处继续，不出现疯狂闪烁。
                    frameStartedAtUptimeMs = SystemClock.uptimeMillis()
                }
                scheduleTicks()
                requestFullRepaint()
                onSourceReady?.invoke(refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == bindGeneration) onLoadFailure(descriptor, error)
            }
        }
    }

    fun setFaceVisible(visible: Boolean) {
        if (disposed) return
        if (faceVisible == visible) {
            notifyVideoPlayback()
            return
        }
        if (!visible) cancelPendingGesture()
        faceVisible = visible
        notifyVideoPlayback()
        if (visible || textExitAnimationActive) scheduleTicks() else cancelTicks()
        requestFullRepaint()
    }

    fun setTextExitAnimationActive(active: Boolean) {
        val effective = active && textExitAnimationEnabled
        if (textExitAnimationActive == effective) return
        textExitAnimationActive = effective
        if (effective) textExitAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
        if (effective) {
            scheduleTicks()
        } else if (!faceVisible) {
            cancelTicks()
        }
        // The animation flag changes how existing layers are drawn; it must not
        // invalidate baked static segments. A plain invalidate keeps the cache
        // and starts/ends the fast tick cadence without rebuilding every bitmap.
        if (!disposed && !overlayRenderFrozen) invalidate()
    }

    fun setWatchDataProvider(provider: WatchDataProvider?) {
        val resolved = provider ?: EmptyWatchDataProvider
        if (dataProvider === resolved) return
        dataProvider = resolved
        if (!disposed) requestFullRepaint()
    }

    fun setCanvasSettings(settings: WatchCanvasSettings) {
        if (canvasSettings == settings) return
        canvasSettings = settings
        if (!disposed) requestFullRepaint()
    }

    /** Enables CloX-compatible gyro parallax only for faces that declare gyro layers. */
    fun setGravityParallaxEnabled(enabled: Boolean) {
        if (gravityParallaxEnabled == enabled) return
        gravityParallaxEnabled = enabled
        if (!enabled) {
            smoothedGyroscopeX = 0f
            smoothedGyroscopeY = 0f
            gravityBackdropFrame?.let(::retireBitmapAfterRender)
            gravityBackdropBlurFrame?.let(::retireBitmapAfterRender)
            gravityBackdropFrame = null
            gravityBackdropBlurFrame = null
            gravityBackdropBlurPrepared = false
        }
        if (!disposed) {
            if (enabled) scheduleTicks() else if (!textExitAnimationActive) scheduleTicks()
            requestFullRepaint()
        }
    }

    /**
     * 重力景深表盘底部模糊背景层的开关。关掉后底下只剩根 `bg_color`（对齐 clox）。
     *
     * 关的时候顺手把缓存的模糊位图丢掉：那两张图只服务这个效果，留着白占内存。
     */
    fun setGravityBackdropBlurEnabled(enabled: Boolean) {
        if (gravityBackdropBlurEnabled == enabled) return
        gravityBackdropBlurEnabled = enabled
        if (!enabled) {
            gravityBackdropFrame?.let(::retireBitmapAfterRender)
            gravityBackdropBlurFrame?.let(::retireBitmapAfterRender)
            gravityBackdropFrame = null
            gravityBackdropBlurFrame = null
            gravityBackdropBlurPrepared = false
        }
        if (!disposed) requestFullRepaint()
    }

    /** Limits animated image playback to the selected editor layer. */
    fun setEditorAnimatedImageLayer(layerIndex: Int?) {
        if (editorAnimationPlaybackLimited && editorAnimatedImageLayerIndex == layerIndex) return
        editorAnimationPlaybackLimited = true
        editorAnimatedImageLayerIndex = layerIndex
        frameStartedAtUptimeMs = SystemClock.uptimeMillis()
        // 选中图层变化不代表素材内容变化。清空帧缓存会让高分辨率循环背景在
        // 每次切换/拖动别的元素时重新解码首帧，既卡主画布又制造短暂消失；LRU
        // 已经会按内存上限自然淘汰，保留现有帧即可。
        cancelTicks()
        if (!disposed) {
            scheduleTicks()
            requestFullRepaint()
        }
    }

    /**
     * 编辑器拖动/缩放/旋转循环背景时固定在手势开始那一帧。松手后从同一播放
     * 位置继续，不跳回首帧；同时停止 30fps invalidate，避免拖动与解码争抢 UI。
     */
    fun setEditorAnimationInteractionFrozen(frozen: Boolean) {
        if (editorAnimationInteractionFrozen == frozen) return
        val now = SystemClock.uptimeMillis()
        if (frozen) {
            editorAnimationFrozenElapsedMs = (now - frameStartedAtUptimeMs).coerceAtLeast(0L)
        } else {
            frameStartedAtUptimeMs = now - editorAnimationFrozenElapsedMs
        }
        editorAnimationInteractionFrozen = frozen
        cancelTicks()
        if (!disposed) {
            scheduleTicks()
            requestFullRepaint()
        }
    }

    /**
     * Pauses the dynamic renderer while Compose animates a launcher overlay.
     *
     * The overlay backdrop owns the captured pixels. This view therefore only
     * freezes clocks and media, avoiding a second full watch-face render on
     * every gesture frame. Asset completion may still update caches, but it
     * must not invalidate the frozen display list until resume.
     */
    internal fun setOverlayRenderFrozen(frozen: Boolean) {
        if (disposed || overlayRenderFrozen == frozen) return
        val now = SystemClock.uptimeMillis()
        if (frozen) {
            overlayFrozenElapsedMs = animatedImageElapsedMs()
            overlayRenderFrozen = true
            cancelTicks()
        } else {
            overlayRenderFrozen = false
            if (editorPreviewPlaying && !editorAnimationInteractionFrozen) {
                frameStartedAtUptimeMs = now - overlayFrozenElapsedMs
            }
            scheduleTicks()
            requestFullRepaint()
        }
    }

    /**
     * 编辑器预览播放开关。一次点击同时作用于序列帧、视频与重力景深。
     *
     * 暂停时把播放位置与重力位移都定格:序列帧停在当前帧、视频 pause()、景深保持
     * 当前视角不再采样传感器,并退回 1s tick 省电。恢复播放时序列帧从同一位置继续
     * (不跳首帧)、视频 start()、景深重新跟随。
     */
    fun setEditorPreviewPlaying(playing: Boolean) {
        if (editorPreviewPlaying == playing) return
        val now = SystemClock.uptimeMillis()
        if (playing) {
            // 从暂停处接着走:把起点前移已播过的时长。
            frameStartedAtUptimeMs = now - editorPreviewPausedElapsedMs
        } else {
            editorPreviewPausedElapsedMs = (now - frameStartedAtUptimeMs).coerceAtLeast(0L)
            // 定格当前视角,后续帧不再向新的传感器读数插值。
            editorPreviewPausedGyroX = smoothedGyroscopeX
            editorPreviewPausedGyroY = smoothedGyroscopeY
        }
        editorPreviewPlaying = playing
        notifyVideoPlayback()
        cancelTicks()
        if (!disposed) {
            scheduleTicks()
            requestFullRepaint()
        }
    }

    /** 视频图层的播放/暂停回调,由宿主(编辑器)接上真正的播放器。 */
    fun setEditorVideoPlaybackListener(listener: ((Boolean) -> Unit)?) {
        editorVideoPlaybackListener = listener
        notifyVideoPlayback()
    }

    /** Propagates editor playback and real face visibility to video hosts. */
    private fun notifyVideoPlayback() {
        if (!faceSoundEligible()) releaseFaceSound()
        editorVideoPlaybackListener?.invoke(faceVisible && editorPreviewPlaying && !ambientRequested)
    }

    /**
     * 把重力景深恢复到默认正视角。暂停时用户要看的是"表盘本来的样子",
     * 所以不只是停止采样,还要把已经累积的平滑位移归零。
     */
    fun resetEditorPreviewViewpoint() {
        smoothedGyroscopeX = 0f
        smoothedGyroscopeY = 0f
        editorPreviewPausedGyroX = 0f
        editorPreviewPausedGyroY = 0f
        if (!disposed) requestFullRepaint()
    }

    private fun animatedImageElapsedMs(): Long =
        if (!editorPreviewPlaying) {
            editorPreviewPausedElapsedMs
        } else if (overlayRenderFrozen) {
            overlayFrozenElapsedMs
        } else if (editorAnimationInteractionFrozen) {
            editorAnimationFrozenElapsedMs
        } else {
            (SystemClock.uptimeMillis() - frameStartedAtUptimeMs).coerceAtLeast(0L)
        }

    private fun shouldAnimateImageLayer(layerIndex: Int): Boolean =
        !overlayRenderFrozen &&
            (!editorAnimationPlaybackLimited || editorAnimatedImageLayerIndex == layerIndex)

    fun dispose() {
        unregisterScreenReceiver()
        ambientTransition?.let(::removeCallbacks)
        ambientTransition = null
        releaseFaceSound()
        if (disposed) return
        cancelPendingGesture()
        disposed = true
        externalDataStore.removeListener(externalDataListener)
        bindGeneration++
        descriptorKey = null
        loadedDescriptorKey = null
        loadedRefreshToken = null
        liveTransformHandoff.cancel()
        clearLiveLayerTransformInternal()
        faceVisible = false
        pendingFlattenAssetInvalidation = false
        flattenBakeAssetMiss = false
        loadJob?.cancel()
        loadJob = null
        assetGenerationJob.cancel()
        mainScope.cancel()
        cancelTicks()
        clearAssetCaches()
        synchronized(cacheLock) {
            activeAssetLoads.clear()
        }
        longPressAction = null
        doubleTapAction = null
        tapActionHandler = null
        frameGeometryListener = null
        transitionOverlayListener = null
        onRenderReadyCallback = null
        boundDescriptor = null
        tapTargets.clear()
        touchGestureActive = false
        source?.clearRuntimeCaches()
        source = null
        sourceTransitionFrame?.let { frame ->
            retireBitmapAfterRender(frame)
        }
        sourceTransitionFrame = null
        sourceTransitionGeneration = -1L
        sourceTransitionStartedAtUptimeMs = 0L
        gravityBackdropFrame?.let { frame ->
            retireBitmapAfterRender(frame)
        }
        gravityBackdropFrame = null
        gravityBackdropBlurFrame?.let { frame ->
            retireBitmapAfterRender(frame)
        }
        gravityBackdropBlurFrame = null
        gravityBackdropBlurPrepared = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!screenReceiverRegistered && !disposed) {
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(context, screenReceiver,
                    android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                    }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }.onSuccess { screenReceiverRegistered = true }
            val interactive = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive != false
            if (interactive && !physicalScreenInteractive && !ambientRequested) restartTextAnimation(WatchTextAnimationTrigger.BRIGHT)
            physicalScreenInteractive = interactive
        }
        if (!disposed) scheduleTicks()
    }

    override fun onDetachedFromWindow() {
        cancelPendingGesture()
        releaseFaceSound()
        unregisterScreenReceiver()
        cancelTicks()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) releaseFaceSound()
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { context.unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disposed || !faceVisible) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchGestureActive = true
        } else if (!touchGestureActive) {
            return false
        }
        // 整手势消费：长按进编辑器、双击、tap_action 都由本视图的
        // gestureDetector 识别。曾试过"只在命中 tap target 时才消费"，
        // 结果 DOWN 不消费 → 视图收不到后续事件 → 长按进入表盘编辑直接
        // 失效（老师实机反馈）。启动器侧的手势由 Compose 父层自行观察，
        // 不依赖这里放行。
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            touchGestureActive = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val prepared = source
        if (prepared == null || width <= 0 || height <= 0) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val transition = sourceTransitionFrame?.takeIf { !it.isRecycled }
        trackVisualAssetReadiness = true
        frameHasUnreadyVisualAsset = false
        val gravityBackdrop = if (
            gravityParallaxEnabled && gravityBackdropBlurEnabled && prepared.hasGyroParallax
        ) {
            resolveGravityBackdrop(prepared)
        } else {
            null
        }
        // The fallback is rendered inside drawPreparedSource after the root
        // background and before authored layers. Drawing it against the View
        // bounds used to put it outside the 512x512 watch canvas, where the
        // canvas' opaque black bg_color immediately covered it.
        drawPreparedSource(
            canvas = canvas,
            prepared = prepared,
            targetWidth = width.toFloat(),
            targetHeight = height.toFloat(),
            gravityBackdrop = gravityBackdrop,
        )
        // 刻意放在绘制**之后**：本帧真正要画的层已经抢过 [claimAssetLoadLocked] 的额度，
        // 预暖只吃剩下的，永远不会把可见内容的解码挤掉。也刻意只挂在 onDraw 上 ——
        // 另外三个 [drawPreparedSource] 调用点都是 [withFullRenderPlan] 的离屏一次性
        // 出图，那里暖缓存没有下一帧可言。
        if (!overlayRenderFrozen) {
            warmOpacityGatedImages(prepared)
            val gyroEnabledForSource = gravityParallaxEnabled && prepared.hasGyroParallax
            if (!editorPreviewPlaying) {
                // 预览暂停:定格在暂停那一刻的视角,不再向新的传感器读数插值。
                // 直接赋值而非插值,否则暂停后画面还会继续缓慢滑动一小段。
                smoothedGyroscopeX = editorPreviewPausedGyroX
                smoothedGyroscopeY = editorPreviewPausedGyroY
            } else {
                val rawGyroX = if (gyroEnabledForSource) frameDataSnapshot.gyroscopeX ?: 0f else 0f
                val rawGyroY = if (gyroEnabledForSource) frameDataSnapshot.gyroscopeY ?: 0f else 0f
                smoothedGyroscopeX += (rawGyroX - smoothedGyroscopeX) * 0.18f
                smoothedGyroscopeY += (rawGyroY - smoothedGyroscopeY) * 0.18f
            }
        }
        trackVisualAssetReadiness = false

        if (transition != null) {
            canvas.drawBitmap(transition, null, Rect(0, 0, width, height), sourceTransitionPaint)
            val transitionStillLoading = synchronized(cacheLock) {
                activeAssetLoads.any { claim -> claim.generation == sourceTransitionGeneration }
            }
            val transitionTimedOut =
                SystemClock.uptimeMillis() - sourceTransitionStartedAtUptimeMs >=
                    SOURCE_TRANSITION_MAX_HOLD_MS
            val canRevealReplacement =
                !transitionStillLoading && !frameHasUnreadyVisualAsset
            if (
                sourceTransitionGeneration != bindGeneration ||
                transitionTimedOut ||
                canRevealReplacement
            ) {
                sourceTransitionFrame = null
                sourceTransitionGeneration = -1L
                sourceTransitionStartedAtUptimeMs = 0L
                // 过渡帧撤掉了，视频面可以重新露出来。
                transitionOverlayListener?.invoke(false)
                // Do not recycle here. A hardware display list may still refer
                // to this bitmap after onDraw returns; dropping our reference
                // lets the runtime reclaim it safely once that frame retires.
                invalidate()
            } else if (!transitionStillLoading) {
                // A cancelled older generation can briefly occupy all decode
                // slots. Retry on the next frame instead of revealing a face
                // whose first image request has not even entered the queue yet.
                postInvalidateOnAnimation()
            }
        }
        reportRenderReadinessAfterDraw()
    }

    /**
     * 本帧画完之后重估「渲染就绪」，就绪就报给宿主（桌面据此撤加载页）。
     *
     * 判定本身在 [watchRenderReadiness] 里，纯函数、可单测。这里只负责取状态、
     * 去重、以及在没人会来叫醒我们的那种情况下自己推下一帧。
     */
    private fun reportRenderReadinessAfterDraw() {
        val callback = onRenderReadyCallback ?: return
        val key = descriptorKey ?: return
        val token = refreshToken
        // 一个 (表盘, refreshToken) 只报一次 —— bind() 每次重组都会跑，
        // 照 loadedDescriptorKey / loadedRefreshToken 那套去重写法。
        if (renderReadyDescriptorKey == key && renderReadyRefreshToken == token) return
        val pendingAssetLoads = synchronized(cacheLock) {
            activeAssetLoads.count { claim -> claim.generation == bindGeneration } +
                bitmapLoading.values.count { it == bindGeneration } +
                movieLoading.values.count { it == bindGeneration } +
                typefaceLoading.values.count { it == bindGeneration }
        }
        val readiness = watchRenderReadiness(
            faceVisible = faceVisible,
            sourceReady = loadedDescriptorKey == key && loadedRefreshToken == token,
            transitionFrameVisible = sourceTransitionFrame?.isRecycled == false,
            pendingAssetLoads = pendingAssetLoads,
            frameHasUnreadyVisualAsset = frameHasUnreadyVisualAsset,
            holdExpired = SystemClock.uptimeMillis() >= renderReadyDeadlineUptimeMs,
        )
        when (readiness) {
            WatchRenderReadiness.WAITING -> return
            WatchRenderReadiness.RETRY_NEXT_FRAME -> {
                postInvalidateOnAnimation()
                return
            }
            WatchRenderReadiness.READY -> Unit
        }
        renderReadyDescriptorKey = key
        renderReadyRefreshToken = token
        callback(token)
    }

    private fun capturePreparedFrame(prepared: PreparedSource): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                withFullRenderPlan {
                    drawPreparedSource(Canvas(bitmap), prepared, width.toFloat(), height.toFloat())
                }
            }
        }.getOrNull()
    }

    /**
     * Builds the gyro fallback in the face's own design space, never in the
     * surrounding View space. While image assets are still decoding we retain
     * a clear frame as a temporary bottom layer; once the generation is ready
     * it is recaptured and converted to the same prebuilt box blur used by the
     * Android 12-below backdrop path.
     */
    private fun resolveGravityBackdrop(prepared: PreparedSource): Bitmap? {
        var clearBackdrop = gravityBackdropFrame?.takeIf { !it.isRecycled }
        if (clearBackdrop == null) {
            clearBackdrop = captureGravityBackdrop(prepared)
            gravityBackdropFrame = clearBackdrop
        }
        if (!gravityBackdropBlurPrepared) {
            val visualAssetsPending = synchronized(cacheLock) {
                activeAssetLoads.any { claim -> claim.generation == prepared.sourceGeneration }
            }
            if (!visualAssetsPending) {
                // The first capture may have initiated asynchronous image loads.
                // Recapture once they settle so the permanent blur contains the
                // complete watch rather than a black placeholder frame.
                captureGravityBackdrop(prepared)?.let { refreshed ->
                    clearBackdrop = refreshed
                    gravityBackdropFrame = refreshed
                }
                gravityBackdropBlurFrame = clearBackdrop
                    ?.takeIf { !it.isRecycled }
                    ?.let { source ->
                        runCatching { createLegacyPrebuiltBlur(source) }.getOrNull()
                    }
                gravityBackdropBlurPrepared = true
            }
        }
        return gravityBackdropBlurFrame?.takeIf { !it.isRecycled }
            ?: clearBackdrop?.takeIf { !it.isRecycled }
    }

    private fun captureGravityBackdrop(prepared: PreparedSource): Bitmap? {
        val bitmapWidth = prepared.designExtentWidth.roundToInt().coerceIn(1, 1_024)
        val bitmapHeight = prepared.designExtentHeight.roundToInt().coerceIn(1, 1_024)
        return runCatching {
            Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                withFullRenderPlan {
                    drawPreparedSource(
                        canvas = Canvas(bitmap),
                        prepared = prepared,
                        targetWidth = bitmapWidth.toFloat(),
                        targetHeight = bitmapHeight.toFloat(),
                        gravityBackdrop = null,
                        gyroscopeX = 0f,
                        gyroscopeY = 0f,
                    )
                }
            }
        }.getOrNull()
    }

    private fun installSourceTransitionFrame(frame: Bitmap?, generation: Long) {
        val previous = sourceTransitionFrame
        sourceTransitionFrame = frame
        sourceTransitionGeneration = if (frame == null) -1L else generation
        sourceTransitionStartedAtUptimeMs =
            if (frame == null) 0L else SystemClock.uptimeMillis()
        // 过渡帧是画在 Canvas 最上层的不透明整屏位图，但 TextureView 是独立
        // Surface，会直接穿透它露出正在播的视频。所以切表盘期间必须让宿主把
        // 视频面藏掉，等新表盘揭晓后再恢复。
        transitionOverlayListener?.invoke(frame != null)
        // As above, an outgoing hardware display list may still own [previous].
        // Releasing the Kotlin reference is safer than eager recycle().
        if (previous != null && previous !== frame) retireBitmapAfterRender(previous)
    }

    /**
     * 切表盘过渡帧的显示/隐藏，由 [WatchmakerWatchFaceStageView] 消费。
     *
     * true 表示过渡帧正盖在最上层，此时视频面必须隐藏。
     */
    internal fun setTransitionOverlayListener(listener: ((Boolean) -> Unit)?) {
        transitionOverlayListener = listener
    }

    /**
     * 设计空间 → View 空间的画布变换。
     *
     * Canvas 路径与视频图层路径都从这里取值：前者 concat 进 Canvas，后者作为
     * TextureView 变换的最外层。两边各写一份必然随时间漂移。
     */
    private fun composeCanvasBaseMatrix(
        into: Matrix,
        transform: WatchCanvasTransform,
        extentW: Float,
        extentH: Float,
    ): Matrix {
        into.reset()
        into.preTranslate(transform.left, transform.top)
        into.preScale(transform.scaleX, transform.scaleY)
        // Watchmaker coordinates are centered on (0, 0), unlike Android's
        // top-left canvas coordinates. Keeping this translation at the single
        // renderer boundary makes every primitive (including groups and
        // rings) use the same coordinate contract.
        into.preTranslate(extentW / 2f, extentH / 2f)
        return into
    }

    private fun drawPreparedSource(
        canvas: Canvas,
        prepared: PreparedSource,
        targetWidth: Float,
        targetHeight: Float,
        gravityBackdrop: Bitmap? = null,
        gyroscopeX: Float = smoothedGyroscopeX,
        gyroscopeY: Float = smoothedGyroscopeY,
    ) {
        // 设计空间跟随表盘自己声明的画布比例（基准边恒 512）。方形声明算出来
        // 就是 512×512，所以这里对存量表盘是恒等变换。
        val extentW = prepared.designExtentWidth
        val extentH = prepared.designExtentHeight
        val transform = watchCanvasTransform(
            viewWidth = targetWidth,
            viewHeight = targetHeight,
            settings = canvasSettings,
            designWidth = extentW,
            designHeight = extentH,
        )
        canvas.save()
        // 视频图层的 TextureView 活在 View 空间、拿不到 Canvas 的当前矩阵，只能
        // 吃 Matrix。两条路径共用这一个矩阵，几何才不会随时间漂移。
        canvas.concat(composeCanvasBaseMatrix(canvasBaseMatrix, transform, extentW, extentH))
        // 画布基变换本身可能非等比（STRETCH 预设、zoomX≠zoomY），那一样会拉扁字形。
        // 记下来给 [textNeedsLinearRaster] 当种子。
        frameCanvasScaleX = transform.scaleX
        frameCanvasScaleY = transform.scaleY
        canvas.clipRect(-extentW / 2f, -extentH / 2f, extentW / 2f, extentH / 2f)
        if (editorPreviewRoundOverride ?: (prepared.definition.shape == WatchFaceShape.ROUND)) {
            pathScratch.reset()
            // 圆形取内切圆：非方画布上按长边画会把圆裁出画布外。
            pathScratch.addCircle(0f, 0f, min(extentW, extentH) / 2f, Path.Direction.CW)
            canvas.clipPath(pathScratch)
        }
        calendar.timeInMillis = System.currentTimeMillis()
        refreshFrameDataSnapshot(calendar.timeInMillis)
        // 分段渲染时 tapTargets 必须只在首段清空，否则后面的段会把前面段
        // 已经登记的可点区域抹掉，整块表盘只有最后一段能响应点击。
        if (renderRange?.first?.let { it <= 0 } != false) tapTargets.clear()
        if (drawBackground) {
            backgroundPaint.color =
                resolveColor(prepared.definition.rootAttributes["bg_color"], Color.BLACK, prepared)
            canvas.drawRect(-extentW / 2f, -extentH / 2f, extentW / 2f, extentH / 2f, backgroundPaint)
        }
        gravityBackdrop?.takeIf { !it.isRecycled }?.let { backdrop ->
            // Slightly overscan inside the already-clipped watch canvas. The
            // blurred bottom layer therefore reaches every edge when foreground
            // layers move, but it can never bleed beyond the face boundary.
            val overscanX = extentW * 0.05f
            val overscanY = extentH * 0.05f
            canvas.drawBitmap(
                backdrop,
                null,
                RectF(
                    -extentW / 2f - overscanX,
                    -extentH / 2f - overscanY,
                    extentW / 2f + overscanX,
                    extentH / 2f + overscanY,
                ),
                gravityBackdropPaint,
            )
        }
        val plan = prepared.renderPlan
        val range = renderRange
        val fromIndex = range?.first?.coerceIn(0, plan.size) ?: 0
        val toIndex = range?.let { (it.last + 1).coerceIn(fromIndex, plan.size) } ?: plan.size
        val segments = resolveActiveFlattenSegments(prepared, extentW, extentH)
        var planIndex = fromIndex
        while (planIndex < toIndex) {
            val segment = segments?.firstOrNull { it.fromIndex == planIndex }
            // 段必须整段落在本次绘制区间内：跨出去就退回逐层，宁可不省。
            val baked = segment
                ?.takeIf { it.toIndex < toIndex }
                ?.let { prepared.bakedSegmentBitmaps[it.fromIndex] }
                ?.takeIf { !it.isRecycled }
            if (segment != null && baked != null) {
                canvas.drawBitmap(baked, -extentW / 2f, -extentH / 2f, null)
                planIndex = segment.toIndex + 1
                continue
            }
            drawRenderItem(canvas, plan[planIndex], prepared, gyroscopeX, gyroscopeY)
            planIndex++
        }
        canvas.restore()
    }

    /**
     * 取本帧可用的烘焙段，必要时（首帧、尺寸变化、慢桶 tag 变化）重烘。
     *
     * 返回 null 表示这一帧不走烘焙（编辑器、离屏出图、尺寸异常）。
     */
    private fun resolveActiveFlattenSegments(
        prepared: PreparedSource,
        extentW: Float,
        extentH: Float,
    ): List<WatchFlattenSegment>? {
        if (
            prepared.flattenSegments.isEmpty() ||
            flattenBakingSuppressed ||
            overlayRenderFrozen
        ) return null
        // 编辑器里图层随时被实时变换/选中高亮改写，烘了就会看到"改了没反应"。
        if (editorLiveTransformActive() || editorAnimationPlaybackLimited) return null
        val width = extentW.toInt()
        val height = extentH.toInt()
        if (width < 1 || height < 1) return null
        if (width > MAX_FLATTEN_BITMAP_SIZE || height > MAX_FLATTEN_BITMAP_SIZE) return null
        val sizeChanged = prepared.bakedSegmentWidth != width || prepared.bakedSegmentHeight != height
        // ⚠️ 顺序很重要：consume 有副作用（清位），三个信号必须**无条件**全取，
        // 不能被 `||` 短路掉 —— 尺寸变化那一帧若跳过 consume，失效信号会残留到
        // 下一帧，白烘一次。按桶集合更严重：只在需要时才取会让它越攒越多，
        // 下次真要用时早已退化成「全都变了」。
        val fullyInvalidated = prepared.slowTagTracker.consumeFullInvalidation()
        val slowTagsInvalidated = prepared.slowTagTracker.consumeSlowTagInvalidation()
        val changedBuckets = prepared.slowTagTracker.consumeChangedSlowBuckets()
        // 这里刻意**没有**时间节流器兜底。快桶表盘的慢桶信号由 shouldRedrawThisTick()
        // 里无条件的 poll() 送达，不需要「每秒强制重烘」—— 那个兜底会让 377 层的表盘
        // 每秒回收重建全部段位图，滑动撞上就掉帧（周期性「一下巨卡」）。
        if (sizeChanged || fullyInvalidated) {
            // 尺寸变了，所有段位图的宽高都不对；全量失效（压暗、主题、首帧、编辑器
            // 改属性）语义是「什么都可能变了」。两者都只能整批重来。
            prepared.recycleBakedSegments()
            prepared.bakedSegmentWidth = width
            prepared.bakedSegmentHeight = height
        } else if (slowTagsInvalidated && changedBuckets.isNotEmpty()) {
            // 慢桶变化走**按段**作废：只有桶命中的段才重烘（老师报的「还是卡」）。
            //
            // ⚠️ `changedBuckets` 为空时**什么都不做**才是对的。慢桶位与桶集合由
            // 同一个 poll() 成对置位，为空说明这个信号来自别处 —— 而真正的「别处」
            // 只有全量入口，它已经在上面那条分支里整批回收过了。这里再退回整批
            // 就等于把按桶优化整个抵消掉。
            prepared.recycleBakedSegmentsForBuckets(changedBuckets)
        }
        // 分帧烘焙的判据：**还有段没烘且预算装得下**，而不是「段表空不空」。
        //
        // 原判据是 `bakedSegmentBitmaps.isEmpty()`，配合「一次调用烘完全部段」才成立。
        // 加了每帧配额（见 [bakeFlattenSegments]）之后，烘完头两段 map 就非空了，
        // 空判据会让剩下的段永远补不上，表盘一直停在逐层直绘。
        //
        // ⚠️ 上界必须是「预算**装得下**几段」而不是「一共有几段」：预算只够 4 段时
        // 用总段数当上界，这个判据永远为真 —— 于是每帧都进来重试一次 Bitmap 分配，
        // 正是 bakedSegmentsUnavailable 当初要挡的那种每帧重试。段尺寸一致，所以
        // 这个数直接算得出来，不用去发现。
        val bytesPerSegment = width.toLong() * height.toLong() * 4L
        val affordableSegments = if (bytesPerSegment <= 0L) {
            0
        } else {
            minOf(
                prepared.flattenSegments.size.toLong(),
                MAX_FLATTEN_TOTAL_BYTES / bytesPerSegment,
            ).toInt()
        }
        if (
            !prepared.bakedSegmentsUnavailable &&
            !prepared.bakedSegmentBakeStalled &&
            prepared.bakedSegmentBitmaps.size < affordableSegments
        ) {
            bakeFlattenSegments(prepared, extentW, extentH, width, height)
        }
        return prepared.flattenSegments.takeIf { prepared.bakedSegmentBitmaps.isNotEmpty() }
    }

    /**
     * 把每个段合成到一张离屏位图上。
     *
     * alpha 累积（`wf_render.lua:3093`）：位图初值全透明，逐层按 `SRC_OVER` 画进去，
     * 合成回主 Canvas 时**不再乘一次 alpha** —— 否则半透明层被压暗两次。
     *
     * **每帧只烘 [MAX_SEGMENT_BAKES_PER_FRAME] 段**（老师报的「312 滑下进控制中心
     * 大卡一下」）：312 有 377 层、16 个段，整分钟跳变或滑动触发的一次失效原来会在
     * **同一帧**内把 16 张 512² 位图全部重建 —— 16 次分配 + 上百层离屏绘制压在一次
     * `onDraw` 里，正好撞上转场动画的第一帧。这里摊成多帧：本帧没轮到的段暂时缺位，
     * [drawPreparedSource] 的段循环对缺位段本来就会退回逐层直绘，画面不会缺东西，
     * 只是那几段这一帧没享受到烘焙加速；下一帧继续补，几帧内收敛回全烘状态。
     *
     * 已经烘好的段直接跳过 —— 分帧的前提是这个函数可以被反复调用而不重复劳动。
     */
    private fun bakeFlattenSegments(
        prepared: PreparedSource,
        extentW: Float,
        extentH: Float,
        width: Int,
        height: Int,
    ) {
        flattenBakeBudget.start()
        val bytesPerSegment = width.toLong() * height.toLong() * 4L
        if (bytesPerSegment <= 0L || bytesPerSegment > MAX_FLATTEN_SEGMENT_BYTES) {
            prepared.bakedSegmentsUnavailable = true
            return
        }
        // 字节记账：别再开第四个不记账的位图缓存（计划 §3.5）。
        // 已烘的段照样占预算，所以从总额里先扣掉它们，分帧才不会超发。
        var budget = MAX_FLATTEN_TOTAL_BYTES - prepared.bakedSegmentBitmaps.size * bytesPerSegment
        var bakedThisFrame = 0
        // 「这一轮真的动手试过几个段」——用来区分 [PreparedSource.bakedSegmentBakeStalled]
        // 的两种零产出：配额用完了（正常，下一帧继续）与试了却全失败（卡住，停手）。
        var attempted = 0
        var quotaHit = false
        var budgetHit = false
        for (segment in prepared.flattenSegments) {
            if (bakedThisFrame >= MAX_SEGMENT_BAKES_PER_FRAME) {
                quotaHit = true
                break
            }
            if (!flattenBakeBudget.hasTimeRemaining()) {
                budgetHit = true
                break
            }
            if (budget < bytesPerSegment) break
            // 上一帧（或本轮更早）已经烘好的段不重烘。
            if (prepared.bakedSegmentBitmaps[segment.fromIndex]?.isRecycled == false) continue
            attempted++
            val bitmap = runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: continue
            val offscreen = Canvas(bitmap)
            // 段内图层的坐标是设计空间（原点在中心），位图原点在左上。
            offscreen.translate(extentW / 2f, extentH / 2f)
            var ok = true
            // 逐段清零：段内任一图层碰到未就绪的素材，requestBitmap / requestMovie
            // 会把它置起来（见 [flattenBakeAssetMiss]）。烘焙循环是顺序的、全程主
            // 线程，所以段与段之间不会串。
            flattenBakeAssetMiss = false
            for (index in segment.fromIndex..segment.toIndex) {
                val item = prepared.renderPlan.getOrNull(index) ?: continue
                if (!flattenBakeBudget.hasTimeRemaining()) {
                    ok = false
                    budgetHit = true
                    break
                }
                // 段内层按定义不含快依赖，陀螺仪传 0 即可。
                // baseScale 传 1：离屏位图是设计空间尺寸，画布基变换是合成回主
                // Canvas 时才施加的，这里不能把它算进字形缩放。
                if (
                    runCatching {
                        drawRenderItem(offscreen, item, prepared, 0f, 0f, 1f, 1f)
                    }.isFailure
                ) {
                    ok = false
                    break
                }
            }
            if (ok) {
                prepared.bakedSegmentBitmaps[segment.fromIndex] = bitmap
                // 缺素材就登记：这一段烘出来有空洞，等素材落地后单独重烘它，
                // 而不是让任何一次落地把 16 段整批回收（老师报的那串尖峰）。
                if (flattenBakeAssetMiss) {
                    prepared.segmentsAwaitingAssets += segment.fromIndex
                } else {
                    prepared.segmentsAwaitingAssets -= segment.fromIndex
                }
                budget -= bytesPerSegment
                bakedThisFrame++
            } else if (!bitmap.isRecycled) {
                // This bitmap has never been handed to the display list: it is
                // safe to release immediately when the frame budget interrupts
                // an offscreen bake.
                bitmap.recycle()
            }
        }
        flattenBakeAssetMiss = false
        // 一个都没烘出来（预算不够 / 分配失败 / 逐层绘制抛异常）：记下来，别每帧重试。
        prepared.bakedSegmentsUnavailable =
            !budgetHit && !quotaHit && prepared.bakedSegmentBitmaps.isEmpty()
        // 卡住判据：**动手试过**却一个都没成。
        //
        // 三种零产出要分开看：段表已经烘满（attempted == 0，没活干）、被每帧配额
        // 截断（quotaHit，正常的分帧中间态）、试了但 Bitmap 分配或段内绘制持续失败
        // （真卡住）。只有第三种该停手 —— 否则调用点的 `size < affordableSegments`
        // 一直为真，每帧都白试一次分配。quotaHit 在当前配额（>0）下必然伴随
        // bakedThisFrame > 0，留着是防配额被改成 0 时误判。
        prepared.bakedSegmentBakeStalled =
            attempted > 0 && !quotaHit && !budgetHit && bakedThisFrame == 0
        if (
            (budgetHit || quotaHit) &&
            !prepared.bakedSegmentsUnavailable &&
            prepared.bakedSegmentBitmaps.size < prepared.flattenSegments.size
        ) {
            // Static faces may have no tick to request the next frame. Continue
            // only when this invocation stopped for a normal frame bound.
            postInvalidateOnAnimation()
        }
    }

    private fun drawRenderItem(
        canvas: Canvas,
        item: WatchLayerRenderItem,
        source: PreparedSource,
        gyroscopeX: Float = smoothedGyroscopeX,
        gyroscopeY: Float = smoothedGyroscopeY,
        baseScaleX: Float = frameCanvasScaleX,
        baseScaleY: Float = frameCanvasScaleY,
    ) {
        val saveCount = canvas.save()
        try {
            var alpha = 1f
            // 字形栅格化质量要看「画布 × 祖先链 × 自身」的合成缩放，所以从画布基
            // 变换起累乘。烘焙/缩略图路径的离屏 Canvas 不带画布基变换，由调用方
            // 传 1f 进来。
            var scaleX = baseScaleX
            var scaleY = baseScaleY
            var ancestorCount = 0
            var ancestor = item.ancestorChain
            while (ancestor != null && ancestorCount < ancestorIndexScratch.size) {
                ancestorIndexScratch[ancestorCount++] = ancestor.layerIndex
                ancestor = ancestor.outer
            }
            for (scratchIndex in ancestorCount - 1 downTo 0) {
                val ancestorIndex = ancestorIndexScratch[scratchIndex]
                val ancestorLayer = source.definition.layers[ancestorIndex]
                val attributes = displayAttributes(ancestorLayer)
                alpha *= dynamicNumber(attributes["opacity"], 100f, source)
                    .coerceIn(0f, 100f) / 100f
                if (alpha <= 0f || !isDisplayed(attributes["display"], source)) return
                applyTransform(
                    canvas,
                    attributes,
                    source,
                    ancestorIndex,
                    ancestorLayer.id,
                    gyroscopeX,
                    gyroscopeY,
                )
                // 紧跟 applyTransform 读：composeLayerMatrix 的输出字段会被下一次
                // 调用覆盖（视频图层路径也在调它）。
                scaleX *= lastComposedScaleX
                scaleY *= lastComposedScaleY
            }
            val layer = source.definition.layers[item.layerIndex]
            val attributes = displayAttributes(layer)
            val hasTapAction = !attributes["tap_action"].isNullOrBlank() ||
                !attributes["action"].isNullOrBlank()
            alpha *= dynamicNumber(attributes["opacity"], 100f, source)
                .coerceIn(0f, 100f) / 100f
            // WatchMaker intentionally uses opacity=0 shapes as invisible touch
            // grids (Nexus 2.2 has 100 of them).  They must still reach the
            // primitive so it can register its hit rectangle.
            if (
                !shouldRenderWatchLayer(
                    alpha = alpha,
                    isDisplayed = isDisplayed(attributes["display"], source),
                    hasTapAction = hasTapAction,
                )
            ) return
            applyTransform(
                canvas,
                attributes,
                source,
                item.layerIndex,
                layer.id,
                gyroscopeX,
                gyroscopeY,
            )
            layerScaleX = scaleX * lastComposedScaleX
            layerScaleY = scaleY * lastComposedScaleY
            drawPrimitive(canvas, layer, item.layerIndex, alpha, source)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /**
     * 这一层的文字要不要走连续坐标栅格化。判据见 [watchTextNeedsLinearRaster]；
     * 这里只负责把当前图层的累计缩放喂进去。
     */
    private fun textNeedsLinearRaster(): Boolean =
        watchTextNeedsLinearRaster(layerScaleX, layerScaleY)

    /**
     * 图层变换的唯一真源：把 translate/rotate/scale/skew 折叠进一个 Matrix。
     *
     * Canvas 路径（[applyTransform]）与视频图层的 TextureView 路径都从这里取值。
     * 视频是独立 Surface，只吃 Matrix 不吃 Canvas 操作；两条路径各写一份变换必然
     * 会随时间漂移，所以这里刻意做成两者共用的唯一实现。
     *
     * 结果写进传入的 [into] 并原样返回，供逐帧调用复用同一个对象。
     */
    private fun composeLayerMatrix(
        into: Matrix,
        attrs: Map<String, String>,
        source: PreparedSource,
        layerIndex: Int = -1,
        layerId: String? = null,
        gyroscopeX: Float = smoothedGyroscopeX,
        gyroscopeY: Float = smoothedGyroscopeY,
    ): Matrix {
        into.reset()
        // 编辑器画布手势期间的实时覆盖：表盘像素立刻跟手，不等落盘。
        val live = watchmakerLiveTransformTargetsLayer(
            targetLayerId = liveTransformLayerId,
            targetLayerIndex = liveTransformLayerIndex,
            layerId = layerId,
            layerIndex = layerIndex,
        )
        val batch = liveLayerTransforms.firstOrNull {
            watchmakerLiveTransformTargetsLayer(it.layerId, it.layerIndex, layerId, layerIndex)
        }
        val baseX = batch?.x ?: if (live) liveTransformX else dynamicNumber(attrs["x"], 0f, source)
        val baseY = batch?.y ?: if (live) liveTransformY else dynamicNumber(attrs["y"], 0f, source)
        val gyroScale = if (gravityParallaxEnabled && !ambientMode) {
            attrs["gyro"]?.trim()?.takeIf(String::isNotEmpty)?.let {
                dynamicNumber(it, 0f, source).coerceIn(-32f, 32f)
            } ?: 0f
        } else {
            0f
        }
        // clox `applyPos` 的契约（wf_render.lua 自带注释，原文照抄）：
        //   "UL (screen coords, y-down): x += sgy*gyro, y += sgx*(-gyro)"
        // 它自己是 y 轴向上的 cocos 空间，两个分量写出来都是加号；Android Canvas
        // 是 y 轴向下，等价于上面那句 screen 坐标契约 —— y 分量必须取负，
        // 否则开了景深的图层会往反方向甩（老师报的第 3 项）。x 分量本来就对。
        into.preTranslate(
            baseX + gyroscopeY * gyroScale,
            baseY - gyroscopeX * gyroScale,
        )
        val rotation = batch?.rotationDegrees ?: if (live) {
            liveTransformRotation
        } else {
            dynamicNumber(attrs["rotation"], 0f, source)
        }
        if (rotation != 0f) into.preRotate(rotation)
        val sharedScale = attrs["anim_scale"] ?: attrs["scale"]
        val sx = batch?.scaleX ?: if (live) {
            liveTransformScaleX
        } else {
            dynamicNumber(
                attrs["anim_scale_x"] ?: attrs["scale_x"] ?: sharedScale,
                100f,
                source,
            ).coerceIn(-500f, 500f) / 100f
        }
        val sy = batch?.scaleY ?: if (live) {
            liveTransformScaleY
        } else {
            dynamicNumber(
                attrs["anim_scale_y"] ?: attrs["scale_y"] ?: sharedScale,
                100f,
                source,
            ).coerceIn(-500f, 500f) / 100f
        }
        if (sx != 1f || sy != 1f) into.preScale(sx, sy)
        // 供 [drawRenderItem] 沿祖先链累乘，判断字形要不要走连续坐标栅格化。
        lastComposedScaleX = sx
        lastComposedScaleY = sy
        val skewX = dynamicNumber(attrs["skew_x"], 0f, source)
        val skewY = dynamicNumber(attrs["skew_y"], 0f, source)
        if (skewX != 0f || skewY != 0f) {
            // cocos `setSkewX/Y` 的仿射矩阵是 (1, tan(sy), tan(sx), 1, 0, 0)，
            // 即在 **y 轴向上** 的空间里 x' = x + tan(sx)·v、v' = v + tan(sy)·x。
            // 代入 v = −y 换到 Android 的 y-down 空间，两个分量都会翻号：
            //   x' = x − tan(sx)·y      y' = y − tan(sy)·x
            // 所以喂给 preSkew 的两个值都要取负 —— 只翻一个的话会变成沿另一根
            // 轴镜像，正是老师报的「倾斜方向相反」（第 2 项）。
            into.preSkew(
                -kotlin.math.tan(Math.toRadians(skewX.toDouble())).toFloat(),
                -kotlin.math.tan(Math.toRadians(skewY.toDouble())).toFloat(),
            )
        }
        return into
    }

    private fun applyTransform(
        canvas: Canvas,
        attrs: Map<String, String>,
        source: PreparedSource,
        layerIndex: Int = -1,
        layerId: String? = null,
        gyroscopeX: Float = smoothedGyroscopeX,
        gyroscopeY: Float = smoothedGyroscopeY,
    ) {
        // concat 会立即读走矩阵内容，所以这里可以安全复用同一个 scratch 对象。
        canvas.concat(
            composeLayerMatrix(
                into = layerMatrixScratch,
                attrs = attrs,
                source = source,
                layerIndex = layerIndex,
                layerId = layerId,
                gyroscopeX = gyroscopeX,
                gyroscopeY = gyroscopeY,
            ),
        )
    }

    private fun drawPrimitive(
        canvas: Canvas,
        layer: WatchmakerLayer,
        layerIndex: Int,
        alpha: Float,
        source: PreparedSource,
    ) {
        val attrs = displayAttributes(layer)
        val normalizedType = source.normalizedLayerTypes.getOrNull(layerIndex)
            ?: layer.type.lowercase(Locale.ROOT)
        var animatedAlpha = alpha
        if (textExitAnimationActive && normalizedType.startsWith("text")) {
            val animation = watchTextExitAnimationFrame(
                animOut = attrs["anim_out"],
                durationOutSeconds = dynamicNumber(attrs["dur_out"], 0.5f, source),
                elapsedMs = SystemClock.uptimeMillis() - textExitAnimationStartedAtUptimeMs,
            )
            if (!animation.visible) return
            canvas.translate(animation.translationX, animation.translationY)
            if (animation.scale != 1f) canvas.scale(animation.scale, animation.scale)
            if (animation.rotation != 0f) canvas.rotate(animation.rotation)
            animatedAlpha *= animation.alpha
        } else if (!ambientMode && textEntryAnimationEnabled && normalizedType.startsWith("text") &&
            (!attrs["anim_in"].isNullOrBlank() || !attrs["anim_out"].isNullOrBlank())
        ) {
            val animationEpoch = textAnimationEpochs.epochFor(attrs)
            val animation = watchTextAnimationFrame(
                animIn = attrs["anim_in"],
                animOut = attrs["anim_out"],
                durationInSeconds = dynamicNumber(attrs["dur_in"], 0.5f, source),
                durationOnSeconds = dynamicNumber(attrs["dur_on"], 0f, source),
                durationOutSeconds = dynamicNumber(attrs["dur_out"], 0.5f, source),
                durationOffSeconds = dynamicNumber(attrs["dur_off"], 0f, source),
                delayStartSeconds = dynamicNumber(attrs["delay_start"], 0f, source),
                repeatCount = dynamicNumber(attrs["repeat_count"], 1f, source).roundToInt(),
                restartOnLoad = animationEpoch != null,
                elapsedMs = animationEpoch?.let { SystemClock.uptimeMillis() - it } ?: 0L,
            )
            if (!animation.visible) return
            canvas.translate(animation.translationX, animation.translationY)
            if (animation.scale != 1f) canvas.scale(animation.scale, animation.scale)
            if (animation.rotation != 0f) canvas.rotate(animation.rotation)
            animatedAlpha *= animation.alpha
        }
        // PreparedSource caches this immutable key for the frame hot path.
        val layerCacheKey = source.layerCacheKeys.getOrNull(layerIndex)
            ?: source.layerCacheKey(layer, layerIndex)
        when (normalizedType) {
            "image", "gif", "image_gif" -> drawImage(
                canvas = canvas,
                attrs = attrs,
                alpha = animatedAlpha,
                source = source,
                layerCacheKey = layerCacheKey,
                animateFrames = shouldAnimateImageLayer(layerIndex),
            )
            "image_tap" -> drawImage(canvas, attrs, animatedAlpha, source, layerCacheKey, tapAction = true)
            "image_cond" -> drawImage(canvas, attrs, animatedAlpha, source, layerCacheKey, sprite = true)
            "gallery", "gallery_2d" -> drawGallery2d(
                canvas = canvas,
                attrs = attrs,
                alpha = animatedAlpha,
                source = source,
                layerCacheKey = layerCacheKey,
                animateFrames = shouldAnimateImageLayer(layerIndex),
            )
            "text" -> drawText(canvas, attrs, animatedAlpha, source)
            "series" -> drawSeries(canvas, attrs, alpha, source)
            "calendar" -> drawCalendar(canvas, attrs, alpha, source)
            "ring" -> drawRing(canvas, attrs, alpha, source)
            "progress" -> drawProgress(canvas, attrs, alpha, source)
            "shape" -> drawShape(canvas, attrs, alpha, source)
            "rounded" -> drawRounded(canvas, attrs, alpha, source)
            "markers" -> drawMarkers(canvas, attrs, alpha, source)
            "markers_hm" -> drawHourMinuteMarkers(canvas, attrs, alpha, source)
            "ring_image" -> drawRingImage(canvas, attrs, alpha, source, layerCacheKey)
            "progress_image" -> drawProgressImage(canvas, attrs, alpha, source, layerCacheKey)
            "sunarc" -> drawStructuredAssetLayer(canvas, attrs, alpha, source, layerCacheKey, "sunarc")
            "daylight" -> drawStructuredAssetLayer(canvas, attrs, alpha, source, layerCacheKey, "daylight")
            "horizon" -> drawHorizon(canvas, attrs, alpha, source)
            "text_curved" -> drawCurvedText(canvas, attrs, animatedAlpha, source)
            "text_ring" -> drawTextRing(canvas, attrs, animatedAlpha, source)
            "chart" -> drawChart(canvas, attrs, alpha, source)
            "svg" -> drawSvg(canvas, attrs, alpha, source)
            // These families need a MediaPlayer/live map bridge.  If an
            // exporter points them at a static image, drawing that image is a
            // useful bounded fallback; otherwise leave the region transparent
            // and keep the capability in compatibilityReport.
            "video", "map" -> drawStaticMediaFallback(canvas, attrs, alpha, source, layerCacheKey)
        }
    }

    private fun drawGallery2d(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
        animateFrames: Boolean,
    ) {
        val rawPaths = sequenceOf(
            attrs["path"],
            attrs["paths"],
            attrs["images"],
            attrs["source"],
        ).firstOrNull { !it.isNullOrBlank() }
        val evaluatedPaths = rawPaths?.let { evaluateText(it, source) }
        val galleryPaths = parseWatchGalleryPaths(evaluatedPaths)
            .ifEmpty { source.galleryAssetPaths }
        if (galleryPaths.isEmpty()) return

        val durationOnSeconds = dynamicNumber(
            attrs["dur_on"],
            DEFAULT_WATCH_GALLERY_DURATION_SECONDS,
            source,
        )
        drawImage(
            canvas = canvas,
            attrs = attrs,
            alpha = alpha,
            source = source,
            layerCacheKey = layerCacheKey,
            pathExpressionOverride = galleryPaths.joinToString("`"),
            frameDelayMsOverride = watchGalleryFrameDurationMs(durationOnSeconds),
            animateFrames = animateFrames,
        )
    }

    private fun drawImage(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
        sprite: Boolean = false,
        tapAction: Boolean = false,
        pathExpressionOverride: String? = null,
        frameDelayMsOverride: Long? = null,
        animateFrames: Boolean = true,
    ) {
        val rawPathExpression = pathExpressionOverride ?: attrs["path"].orEmpty()
        forgetReplacedImageFallbacks(layerCacheKey, rawPathExpression)
        // 导入的视频帧会形成很长但完全静态的反引号路径表。没有 `{tag}` 时
        // 不经过表达式扫描；否则每一帧、每个拖动事件都要重新遍历几十万字符。
        val pathExpression = if ('{' in rawPathExpression) {
            evaluateText(rawPathExpression, source)
        } else {
            rawPathExpression
        }
        val framePaths = source.gifPathCache.getOrPut(pathExpression) {
            parseWatchGifPaths(pathExpression)
        }.orEmpty()
        val frameDelayMs = frameDelayMsOverride
            ?: dynamicNumber(attrs["gif_delay"], 50f, source).roundToInt().toLong()
        val frameElapsedMs = if (animateFrames) animatedImageElapsedMs() else 0L
        val frameIndex = if (animateFrames) {
            watchGifFrameIndex(framePaths.size, frameElapsedMs, frameDelayMs)
        } else {
            0
        }
        val path = framePaths.getOrNull(frameIndex) ?: pathExpression.substringBefore('`').trim()
        val wantsNightWeatherSheet = sprite &&
            attrs["weather_night"].equals("Y", ignoreCase = true) &&
            isWeatherNightFrame()
        val nightPath = if (wantsNightWeatherSheet) watchWeatherNightAssetReference(path) else null
        val nightResolved = nightPath?.let { candidate ->
            resolveAsset(candidate, source, conventionalDirectory = "images")
        }
        val usingNightWeatherSheet = nightResolved != null
        val selectedPath = if (usingNightWeatherSheet) nightPath.orEmpty() else path
        val fallbackKey = WatchImageFallbackKey(
            layerCacheKey = layerCacheKey,
            materialExpression = rawPathExpression,
            variant = if (usingNightWeatherSheet) "night" else "day",
        )
        // A dynamic expression can briefly resolve to an empty/missing path at
        // the exact frame where it switches. Do not turn that transient lookup
        // miss into a transparent frame: the per-layer fallback below remains
        // authoritative until a new asset is ready.
        val resolved = nightResolved
            ?: resolveAsset(path, source, conventionalDirectory = "images")
        val sharedSize = attrs["size"]
        val grid = if (sprite) {
            parseWatchGrid(
                attrs["cond_grid"]
                    ?: attrs["grid"]
                    ?: attrs["sprite_grid"]
                    ?: buildString {
                        append(attrs["columns"] ?: "1")
                        append('x')
                        append(attrs["rows"] ?: "1")
                    },
            )
        } else {
            1 to 1
        }
        val spriteDecodeDimensionPx = if (sprite) {
            watchSpriteDecodeDimensionPx(
                targetWidth = (attrs["width"] ?: sharedSize)?.trim()?.toFloatOrNull(),
                targetHeight = (attrs["height"] ?: sharedSize)?.trim()?.toFloatOrNull(),
                columns = grid.first,
                rows = grid.second,
                maximumDimensionPx = MAX_SPRITE_SHEET_DECODE_DIMENSION_PX,
            )
        } else {
            null
        }
        val requestedMovie = resolved
            ?.takeIf { it.file.extension.equals("gif", true) }
            ?.let { requestMovie(it, source) }
        var movie = requestedMovie
        if (requestedMovie != null) {
            lastDrawnMovieByLayer[fallbackKey] = requestedMovie
            while (lastDrawnMovieByLayer.size > MAX_LAST_MOVIE_ENTRIES) {
                val iterator = lastDrawnMovieByLayer.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        val requestedBitmap = if (requestedMovie == null && resolved != null) {
            requestBitmap(
                path = selectedPath,
                source = source,
                animatedFrame = animateFrames && framePaths.size > 1,
                minimumDecodeDimensionPx = spriteDecodeDimensionPx,
            )
        } else {
            null
        }
        // 帧序列 / gallery_2d 预取：把**下一拍真正会用到的那些帧**提前塞进解码队列。
        //
        // ⚠️ 预取深度必须跟着 gif_delay 走，不能写死成 `frameIndex + 1`。踩过的坑：
        // tick 固定 33ms，而 `gif_delay=17` 的表盘每拍前进 ⌈33/17⌉ = 2 帧 —— 只暖
        // frameIndex+1 的话，下一拍要的 frameIndex+2 永远是冷的，于是每拍都落回
        // lastDrawnFrameByLayer 的旧帧再重新发起一次解码。实机表现就是「拨两下
        // 停一下、拨两下停一下」：解码偶尔追上就走两帧，追不上就卡在旧帧。
        if (animateFrames && requestedMovie == null && framePaths.size > 1) {
            frameSequencePrefetchIndices(
                frameCount = framePaths.size,
                currentIndex = frameIndex,
                elapsedMs = frameElapsedMs,
                frameDelayMs = frameDelayMs,
            ).forEach { adjacentIndex ->
                val rawAdjacentPath = framePaths[adjacentIndex]
                val preferredPath = if (usingNightWeatherSheet) {
                    watchWeatherNightAssetReference(rawAdjacentPath)
                } else {
                    rawAdjacentPath
                }
                if (preferredPath == selectedPath) return@forEach
                val adjacentAsset = resolveAsset(
                    preferredPath,
                    source,
                    conventionalDirectory = "images",
                ) ?: resolveAsset(rawAdjacentPath, source, conventionalDirectory = "images")
                    ?: return@forEach
                if (adjacentAsset.file.extension.equals("gif", ignoreCase = true)) {
                    requestMovie(adjacentAsset, source)
                } else {
                    requestBitmap(adjacentAsset.key, source, animatedFrame = true)
                }
            }
        }
        // 跨帧兜底：新帧还没解码好就继续画这一层上次画成的那张。
        var drawingNightWeatherSheet = usingNightWeatherSheet
        val bitmap = if (requestedMovie == null) {
            val usable = requestedBitmap?.takeIf { !it.isRecycled }
            if (usable != null) {
                rememberLastDrawnFrame(fallbackKey, usable)
                while (lastDrawnFrameByLayer.size > MAX_LAST_FRAME_ENTRIES) {
                    val iterator = lastDrawnFrameByLayer.entries.iterator()
                    if (!iterator.hasNext()) break
                    val removed = iterator.next().value
                    iterator.remove()
                    recycleDetachedLastFrame(removed)
                }
                usable
            } else {
                // 回收过的位图不能画：命中就当成没有，避免画到已释放内存。
                lastDrawnFrameByLayer[fallbackKey]?.takeIf { !it.isRecycled }
                    ?: if (usingNightWeatherSheet) {
                        // 日夜切换时夜图也走异步解码；准备好之前沿用白天最后一帧，
                        // 避免条件图标短暂透明。
                        drawingNightWeatherSheet = false
                        lastDrawnFrameByLayer[fallbackKey.copy(variant = "day")]
                            ?.takeIf { !it.isRecycled }
                    } else {
                        null
                    }
            }
        } else {
            null
        }
        if (movie == null && bitmap == null) {
            // Switching a dynamic path from/to a raw GIF must retain the old
            // Movie until the new resource is decoded, just like bitmap frames.
            movie = lastDrawnMovieByLayer[fallbackKey]
        }
        if (
            trackVisualAssetReadiness &&
            resolved != null &&
            movie == null &&
            bitmap == null
        ) {
            frameHasUnreadyVisualAsset = true
        }
        val nativeWidth = movie?.width()?.toFloat() ?: bitmap?.width?.toFloat() ?: return
        val nativeHeight = movie?.height()?.toFloat() ?: bitmap?.height?.toFloat() ?: return
        val width = dynamicNumber(attrs["width"] ?: sharedSize, nativeWidth, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, nativeHeight, source).coerceAtLeast(1f)
        val index = if (sprite) {
            val dayIndex = watchSpriteIndex(
                dynamicNumber(
                    attrs["cond_value"] ?: attrs["condition"] ?: attrs["value"],
                    1f,
                    source,
                ),
                grid.first * grid.second,
            )
            if (drawingNightWeatherSheet && grid.first == 3 && grid.second == 3) {
                watchWeatherNightSpriteIndex(dayIndex)
            } else {
                dayIndex
            }
        } else {
            0
        }
        // wftools 的定尺怪癖（clox `setBaseScale`）：非方图被塞进方形框时两轴都按
        // 宽度比缩，图片保持原比例而不是被压成正方形 —— 老师报的第 4 项。
        // 判据用**单格**尺寸，因为 clox 给 sprite sheet 喂的就是 texture ÷ grid。
        val drawHeight = watchImageDrawHeight(
            texWidth = nativeWidth / grid.first.coerceAtLeast(1),
            texHeight = nativeHeight / grid.second.coerceAtLeast(1),
            boxWidth = width,
            boxHeight = height,
        )
        val dst = anchoredRect(width, drawHeight, attrs["alignment"])
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = tintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        if (movie != null) {
            val elapsed = animatedImageElapsedMs()
            // Movie.duration() is allowed to return 0 for GIFs without a
            // reliable duration header. Treating that as 1 ms pins setTime()
            // to frame zero forever; Android's own Movie sample uses a
            // one-second fallback so the decoder can still advance and loop.
            val durationMs = movie.duration().takeIf { it > 0 } ?: DEFAULT_MOVIE_DURATION_MS
            movie.setTime(if (animateFrames) (elapsed % durationMs).toInt() else 0)
            canvas.save()
            canvas.translate(dst.left, dst.top)
            canvas.scale(width / nativeWidth, drawHeight / nativeHeight)
            movie.draw(canvas, 0f, 0f, imagePaint)
            canvas.restore()
        } else {
            val nonNullBitmap = bitmap ?: return
            val cellWidth = (nonNullBitmap.width / grid.first.coerceAtLeast(1)).coerceAtLeast(1)
            val cellHeight = (nonNullBitmap.height / grid.second.coerceAtLeast(1)).coerceAtLeast(1)
            val col = (index % grid.first).coerceIn(0, grid.first - 1)
            val row = (index / grid.first).coerceIn(0, grid.second - 1)
            bitmapFontSourceRect.set(
                col * cellWidth,
                row * cellHeight,
                (col + 1) * cellWidth,
                (row + 1) * cellHeight,
            )
            canvas.drawBitmap(nonNullBitmap, bitmapFontSourceRect, dst, imagePaint)
        }
        // WM_FORMAT_1.0.md §3：tap_action 是任意图层的通用属性，不只属于
        // image_tap 专用图层；带显式 tap_action 的普通 image 也要可点。
        if (tapAction || !attrs["tap_action"].isNullOrBlank()) recordTapTarget(canvas, dst, attrs)
        imagePaint.colorFilter = null
    }

    /**
     * 把 opacity 表达式门控的图片层**提前**解进位图缓存。
     *
     * 要解决的是**冷启动**黑帧，不是 LRU 淘汰：wf528 那六张 400×400 轮换图整包解码
     * 也才 6MB，远够不上 24MB / 128 条的预算，稳态下根本不会被淘汰。真正的路径是
     * [drawRenderItem] 在 `alpha <= 0f` 处提前 return —— 表达式翻真之前 [drawImage]
     * 从没跑过，于是 [requestBitmap] 也从没跑过。轮到显示的那一拍才第一次发起异步
     * 解码，当帧只能拿到 null，[lastDrawnFrameByLayer] 里又没有这一层的旧帧
     * （旧帧只在真画成过的时候才写），`nativeWidth` 那行 `?: return` 直接跳过整层 ——
     * 就是老师看到的切换黑帧。这里在它还不可见的时候就把解码发出去。
     *
     * **每帧配额是硬要求，不是优化**：312 有 79 个动态 opacity 图片层，一帧全捅进
     * 解码队列就是在复刻任务 3 要修的那种卡顿。所以每帧只走
     * [MAX_OPACITY_GATED_WARM_PER_FRAME] 层，用 [PreparedSource.opacityGatedWarmCursor]
     * 轮转，若干帧内覆盖完整张表盘；[requestBitmap] 命中缓存时几乎零成本，暖过的层
     * 再轮到也只是查一次表。[claimAssetLoadLocked] 的并发上限还会再兜一层底：
     * 队列满时它返回 null，多余的请求这一帧直接落空，下一帧再来。
     */
    private fun warmOpacityGatedImages(prepared: PreparedSource) {
        val gated = prepared.opacityGatedImageLayers
        if (gated.isEmpty()) return
        val layers = prepared.definition.layers
        var cursor = prepared.opacityGatedWarmCursor
        if (cursor !in gated.indices) cursor = 0
        var warmed = 0
        var visited = 0
        while (warmed < MAX_OPACITY_GATED_WARM_PER_FRAME && visited < gated.size) {
            val layerIndex = gated[cursor]
            cursor = (cursor + 1) % gated.size
            visited++
            // 清单是构造时按 definition.layers 的下标算的，这里必然对得上；
            // getOrNull 只是防御性写法，不该发生。
            val attrs = layers.getOrNull(layerIndex)?.attributes ?: continue
            // 计划阶段已经排除了反引号帧序列和动态路径，所以这里不需要
            // evaluateText / parseWatchGifPaths，直接就是一条静态路径。
            val path = attrs["path"].orEmpty()
            val resolved = resolveAsset(path, prepared, conventionalDirectory = "images") ?: continue
            if (resolved.file.extension.equals("gif", ignoreCase = true)) {
                requestMovie(resolved, prepared)
            } else {
                // animatedFrame = false：这些是单帧静态图，归 bitmapCache 管，
                // 不该占 animatedBitmapCache 给帧序列留的那份预算。
                val layerType = prepared.normalizedLayerTypes.getOrNull(layerIndex)
                    ?: layers[layerIndex].type.lowercase(Locale.ROOT)
                val spriteDecodeDimensionPx = if (layerType == "image_cond") {
                    val grid = parseWatchGrid(
                        attrs["cond_grid"]
                            ?: attrs["grid"]
                            ?: attrs["sprite_grid"]
                            ?: "${attrs["columns"] ?: "1"}x${attrs["rows"] ?: "1"}",
                    )
                    val sharedSize = attrs["size"]
                    watchSpriteDecodeDimensionPx(
                        targetWidth = (attrs["width"] ?: sharedSize)?.trim()?.toFloatOrNull(),
                        targetHeight = (attrs["height"] ?: sharedSize)?.trim()?.toFloatOrNull(),
                        columns = grid.first,
                        rows = grid.second,
                        maximumDimensionPx = MAX_SPRITE_SHEET_DECODE_DIMENSION_PX,
                    )
                } else {
                    null
                }
                requestBitmap(
                    path = resolved.key,
                    source = prepared,
                    animatedFrame = false,
                    minimumDecodeDimensionPx = spriteDecodeDimensionPx,
                )
            }
            warmed++
        }
        prepared.opacityGatedWarmCursor = cursor
    }

    private fun isWeatherNightFrame(): Boolean {
        val weather = frameDataSnapshot.weather
        weather?.isDaytime?.let { return !it }
        return (weather?.icon ?: frameDataSnapshot.weatherIcon)
            ?.trim()
            ?.endsWith("n", ignoreCase = true) == true
    }

    // 文字布局缓存：文本/字号/字体不变时（时钟文字每秒才变一次）行分割与
    // measureText 都是重复劳动。measureText 要过 JNI + shaping，比一次 map
    // 查找贵一个量级。键里带 size 与 typeface 身份，命中路径零分配。
    private class TextLayoutEntry(
        val typefaceIdentity: Int,
        val size: Float,
        val lines: List<String>,
        val widths: List<Float>,
    )

    private val textLayoutCache = HashMap<String, TextLayoutEntry>()

    private fun cachedTextLayout(text: String, size: Float, typeface: Typeface): TextLayoutEntry {
        val typefaceIdentity = System.identityHashCode(typeface)
        val key = StringBuilder(text.length + 24)
            .append(typefaceIdentity)
            .append('\u0000')
            .append(size)
            .append('\u0000')
            .append(text)
            .toString()
        textLayoutCache[key]?.let { entry ->
            // identityHashCode 理论上会撞；再比对一次实例兜底。
            if (entry.typefaceIdentity == typefaceIdentity && entry.size == size) return entry
        }
        val lines = WatchTextLayout.lines(text)
        val widths = lines.map(textPaint::measureText)
        val entry = TextLayoutEntry(
            typefaceIdentity = typefaceIdentity,
            size = size,
            lines = lines,
            widths = widths,
        )
        if (textLayoutCache.size >= TEXT_LAYOUT_CACHE_LIMIT) textLayoutCache.clear()
        textLayoutCache[key] = entry
        return entry
    }

    private fun drawText(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val raw = attrs["text"].orEmpty()
        if (raw.isBlank()) return
        val value = evaluateText(raw, source)
        val designSize = dynamicNumber(attrs["text_size"] ?: attrs["size"], 24f, source)
            .coerceIn(1f, DESIGN_SIZE)
        resolveBitmapFont(attrs["font"], source)?.let { bitmapFont ->
            // 与 WFTools 一致：所有来源都以 text_size 为准，height 是图层元数据，
            // nominalSize 只作为图集缩放的分母，不能再把目标字号抬高。
            drawBitmapText(canvas, value, attrs, alpha, designSize, bitmapFont, source)
            return
        }
        val size = WatchTextLayout.ttfTextSize(designSize)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = size
        textPaint.isLinearText = textNeedsLinearRaster()
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        val transform = attrs["transform"].orEmpty().lowercase(Locale.ROOT)
        val text = when (transform) {
            "uc", "uppercase" -> value.uppercase(Locale.getDefault())
            "lc", "lowercase" -> value.lowercase(Locale.getDefault())
            else -> value
        }
        val align = attrs["alignment"].orEmpty().lowercase(Locale.ROOT)
        // The layer alignment anchors the whole multi-line block. Individual
        // lines then share that block's left edge; centering every line by its
        // own width turns an authored paragraph into a ragged centered stack.
        textPaint.textAlign = Paint.Align.LEFT
        val metrics = textPaint.fontMetrics
        val layout = cachedTextLayout(text, size, textPaint.typeface)
        val lines = layout.lines
        val lineWidths = layout.widths
        val lineStartXs = WatchTextLayout.lineStartXs(lineWidths, align)
        val lineHeight = (metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(0f)
        val baselines = WatchTextLayout.baselines(
            lineCount = lines.size,
            ascent = metrics.ascent,
            descent = metrics.descent,
            lineHeight = lineHeight,
            verticalAlignment = align,
        )
        if (!attrs["tap_action"].isNullOrBlank()) {
            // 文字图层（如心率读数）也可以携带 tap_action；用文字包围盒记录
            // 点按区域，坐标随当前画布矩阵映射回视图空间。
            lines.forEachIndexed { index, line ->
                val textWidth = lineWidths[index]
                val left = lineStartXs[index]
                val baseline = baselines[index]
                if (index == 0) {
                    rectScratch.set(
                        left,
                        baseline + metrics.ascent,
                        left + textWidth,
                        baseline + metrics.descent,
                    )
                } else {
                    rectScratch.union(
                        left,
                        baseline + metrics.ascent,
                        left + textWidth,
                        baseline + metrics.descent,
                    )
                }
            }
            recordTapTarget(canvas, rectScratch, attrs)
        }
        // 描边通道：先 STROKE 后 FILL——Android 描边以字形轮廓为中心线，
        // 内半会被随后的正文填充盖住，因此外观宽度 = o_size 需要
        // strokeWidth = 2×o_size。Glow 用多环 STROKE 由外向内递增透明度。
        resolveOutline(attrs, source)?.let { outline ->
            lines.forEachIndexed { index, line ->
                drawTextOutline(canvas, line, lineStartXs[index], baselines[index], outline, alpha)
            }
        }
        // 阴影在描边之上、本体之下 —— clox 里描边拷贝 z=-2、阴影拷贝 z=-1，
        // cocos 按 z 升序绘制，所以叠放顺序就是 描边 → 阴影 → 本体。
        resolveShadow(attrs, source)?.let { shadow ->
            lines.forEachIndexed { index, line ->
                drawTextShadow(canvas, line, lineStartXs[index], baselines[index], shadow, alpha)
            }
        }
        val fillBounds = watchTtfTextInkBounds(textPaint, lines, lineStartXs, baselines)
        drawTextShaderFill(canvas, attrs, source, fillBounds, textPaint) {
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, lineStartXs[index], baselines[index], textPaint)
            }
        }
    }

    /**
     * 求值 `shader` + `u_1..u_5`，造出挂到 Paint 上的渐变 / 颜色矩阵。
     *
     * 没有 shader 时返回 null，渲染路径零开销。`u_*` 允许挂表达式，所以数值与颜色
     * 都通过 [dynamicNumber] / [resolveColor] 求值 —— 这也是为什么这一层要留在
     * View 里，而纯数学放在 [buildWatchShapeShaderEffect]。
     *
     * 规范里只有 `shape` 与 `rounded` 支持 shader（`progress` 明确不支持），
     * 所以只有那两条绘制路径调用它。
     */
    private fun drawTextShaderFill(
        canvas: Canvas,
        attrs: Map<String, String>,
        source: PreparedSource,
        bounds: RectF,
        maskPaint: Paint,
        drawMask: () -> Unit,
    ) = textShaderPainter.draw(canvas, bounds, maskPaint,
        resolveColor(attrs["color"], Color.WHITE, source),
        resolveWatchShapeShader(attrs, bounds.width(), bounds.height(), source), drawMask)

    private fun resolveWatchShapeShader(
        attrs: Map<String, String>,
        width: Float,
        height: Float,
        source: PreparedSource,
    ): WatchShapePaintEffect? {
        val kind = watchShapeShaderKindOf(attrs["shader"])
        if (kind == WatchShapeShaderKind.NONE) return null
        val baseColor = resolveColor(attrs["color"], Color.WHITE, source)
        return buildWatchShapeShaderEffect(
            kind = kind,
            width = width,
            height = height,
            baseColor = baseColor,
            number = { key, fallback -> dynamicNumber(attrs[key], fallback, source) },
            color = { key, fallback -> resolveColor(attrs[key], fallback, source) },
        )
    }

    /**
     * 求值 outline/o_size/o_color/o_opacity 四个属性。没有描边（含尺寸或
     * 不透明度为 0）时返回 null，渲染路径零开销。o_size 经全局封顶
     * [MAX_WATCH_OUTLINE_SIZE] 收口，防恶意表盘拖帧。
     */
    private fun resolveOutline(attrs: Map<String, String>, source: PreparedSource): ResolvedOutline? {
        val mode = watchOutlineMode(attrs["outline"])
        if (mode == WatchOutlineMode.NONE) return null
        val size = watchOutlineSize(
            mode,
            attrs["o_size"]?.let { raw -> dynamicNumber(raw, watchOutlineDefaultSize(mode), source) },
        )
        if (size <= 0f) return null
        val opacity = dynamicNumber(attrs["o_opacity"], 100f, source).coerceIn(0f, 100f) / 100f
        if (opacity <= 0f) return null
        return ResolvedOutline(
            mode = mode,
            size = size,
            color = resolveColor(attrs["o_color"], Color.WHITE, source),
            opacity = opacity,
        )
    }

    /** TTF 文字描边。独立 [outlinePaint]，每次调用重设文字属性与 stroke。 */
    private fun drawTextOutline(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        outline: ResolvedOutline,
        alpha: Float,
    ) {
        outlinePaint.typeface = textPaint.typeface
        outlinePaint.textSize = textPaint.textSize
        outlinePaint.textAlign = textPaint.textAlign
        // 描边必须跟正文用同一套栅格化策略，否则两遍字形的轮廓对不齐、描边会露边。
        outlinePaint.isLinearText = textPaint.isLinearText
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.color = outline.color
        val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
        // 由外向内画：外环最淡、内环最实，后画的内环覆盖重叠区。
        for (ring in rings downTo 1) {
            outlinePaint.strokeWidth = outline.size * 2f * ring / rings
            val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                watchGlowRingAlpha(ring, rings)
            } else {
                1f
            }
            outlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
            canvas.drawText(text, x, y, outlinePaint)
        }
    }

    /**
     * 求值 shadow/w_distance/w_angle/w_color/w_opacity 五个属性。没有阴影
     * （模式为 NONE、份数为 0 或不透明度为 0）时返回 null，渲染路径零开销。
     *
     * `w_color` 缺省黑、`w_opacity` 缺省 100 —— 与 clox `bindEffects` 一致。
     */
    private fun resolveShadow(attrs: Map<String, String>, source: PreparedSource): ResolvedShadow? {
        val mode = watchShadowMode(attrs["shadow"])
        if (mode == WatchShadowMode.NONE) return null
        val distance = watchShadowDistance(
            attrs["w_distance"]?.let { raw -> dynamicNumber(raw, 5f, source) },
        )
        val copies = watchShadowCopyCount(mode, distance)
        if (copies <= 0) return null
        val opacity = dynamicNumber(attrs["w_opacity"], 100f, source).coerceIn(0f, 100f) / 100f
        if (opacity <= 0f) return null
        return ResolvedShadow(
            mode = mode,
            distance = distance,
            angleDegrees = watchShadowAngleDegrees(
                attrs["w_angle"]?.let { raw -> dynamicNumber(raw, 135f, source) },
            ),
            color = resolveColor(attrs["w_color"], Color.BLACK, source),
            opacity = opacity,
            copies = copies,
        )
    }

    /**
     * TTF 文字阴影：同一串字、换阴影色，按偏移平移后画在本体之下。
     *
     * 偏移在**图层局部坐标系**里加（此时 canvas 已经吃过该图层的旋转/倾斜矩阵），
     * 和 clox 把拷贝挂在 `rec.holder` 下同源 —— 阴影会跟着图层一起转，而不是
     * 永远指向屏幕的某个固定方向。
     */
    private fun drawTextShadow(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        shadow: ResolvedShadow,
        alpha: Float,
    ) {
        shadowPaint.typeface = textPaint.typeface
        shadowPaint.textSize = textPaint.textSize
        shadowPaint.textAlign = textPaint.textAlign
        shadowPaint.isLinearText = textPaint.isLinearText
        shadowPaint.color = shadow.color
        shadowPaint.alpha = paintAlpha(alpha * shadow.opacity)
        // 由远及近：Flat 的第 copies 份最远，先画，近处的覆盖在上面。
        for (copy in shadow.copies downTo 1) {
            val distance = watchShadowCopyDistance(shadow.mode, shadow.distance, copy)
            canvas.drawText(
                text,
                x + watchShadowOffsetX(distance, shadow.angleDegrees),
                y + watchShadowOffsetY(distance, shadow.angleDegrees),
                shadowPaint,
            )
        }
    }

    private fun drawBitmapText(
        canvas: Canvas,
        text: String,
        attrs: Map<String, String>,
        alpha: Float,
        designSize: Float,
        preparedFont: PreparedBitmapFont,
        source: PreparedSource,
    ) {
        val font = preparedFont.definition
        val renderedText = font.normalizeText(text)
        val lines = WatchTextLayout.lines(renderedText)
        val scale = watchBitmapGlyphScale(
            designSize = designSize,
            nominalSize = font.nominalSize,
            lineHeight = font.lineHeight,
        )
        val align = attrs["alignment"].orEmpty().lowercase(Locale.ROOT)
        val lineHeight = font.lineHeight * scale
        val firstLineTop = when {
            align.startsWith('t') -> 0f
            align.startsWith('b') -> -lineHeight * lines.size
            else -> -lineHeight * lines.size / 2f
        }
        val lineWidths = lines.map { line -> font.measureAdvance(line) * scale }
        val lineStartXs = WatchTextLayout.lineStartXs(lineWidths, align)
        val runs = lines.mapIndexed { index, line ->
            val startX = lineStartXs[index]
            Triple(line, startX, firstLineTop + lineHeight * index)
        }
        if (!attrs["tap_action"].isNullOrBlank()) {
            runs.forEachIndexed { index, (line, startX, lineTop) ->
                val width = font.measureAdvance(line) * scale
                if (index == 0) {
                    rectScratch.set(startX, lineTop, startX + width, lineTop + lineHeight)
                } else {
                    rectScratch.union(startX, lineTop, startX + width, lineTop + lineHeight)
                }
            }
            recordTapTarget(canvas, rectScratch, attrs)
        }
        // 描边通道：整串字形按 8 方向偏移重绘成描边色剪影。硬描边 = 一环，
        // Glow = 多环（环数 ceil(o_size/2)，MAX_WATCH_GLOW_RINGS 封顶）×
        // 8 方向、透明度随环衰减。每帧成本上限 = 8 环 × 8 方向 = 64 次
        // 整串重绘，由环数封顶 + o_size 全局封顶（120）双重兜底。
        resolveOutline(attrs, source)?.let { outline ->
            bitmapOutlinePaint.colorFilter = cachedTintFilter(
                outline.color,
                android.graphics.PorterDuff.Mode.SRC_IN,
                srcInTintFilters,
            )
            val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
            for (ring in rings downTo 1) {
                val radius = outline.size * ring / rings
                val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                    watchGlowRingAlpha(ring, rings)
                } else {
                    1f
                }
                bitmapOutlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
                var direction = 0
                while (direction < BITMAP_OUTLINE_DIRECTIONS.size) {
                    runs.forEach { (line, startX, lineTop) ->
                        drawBitmapGlyphRun(
                            canvas = canvas,
                            text = line,
                            preparedFont = preparedFont,
                            source = source,
                            scale = scale,
                            startX = startX,
                            lineTop = lineTop,
                            offsetX = BITMAP_OUTLINE_DIRECTIONS[direction] * radius,
                            offsetY = BITMAP_OUTLINE_DIRECTIONS[direction + 1] * radius,
                            paint = bitmapOutlinePaint,
                        )
                    }
                    direction += 2
                }
            }
            bitmapOutlinePaint.colorFilter = null
        }
        // 位图字阴影：整串字形按偏移重绘成阴影色剪影，在描边之上、本体之下。
        // 成本上限 = Flat 的 floor(w_distance) 份，由 MAX_WATCH_SHADOW_DISTANCE 收口。
        resolveShadow(attrs, source)?.let { shadow ->
            bitmapShadowPaint.colorFilter = cachedTintFilter(
                shadow.color,
                android.graphics.PorterDuff.Mode.SRC_IN,
                srcInTintFilters,
            )
            bitmapShadowPaint.alpha = paintAlpha(alpha * shadow.opacity)
            for (copy in shadow.copies downTo 1) {
                val distance = watchShadowCopyDistance(shadow.mode, shadow.distance, copy)
                runs.forEach { (line, startX, lineTop) ->
                    drawBitmapGlyphRun(
                        canvas = canvas,
                        text = line,
                        preparedFont = preparedFont,
                        source = source,
                        scale = scale,
                        startX = startX,
                        lineTop = lineTop,
                        offsetX = watchShadowOffsetX(distance, shadow.angleDegrees),
                        offsetY = watchShadowOffsetY(distance, shadow.angleDegrees),
                        paint = bitmapShadowPaint,
                    )
                }
            }
            bitmapShadowPaint.colorFilter = null
        }
        imagePaint.alpha = paintAlpha(alpha)
        // 文字走替换语义，不是图片那套调制：图集只是字形蒙版，图集本身什么颜色
        // 不该影响结果。详见 [textTintFilter] 的 f1.png 黄图集实证。
        imagePaint.colorFilter = textTintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        val fillBounds = watchBitmapTextInkBounds(font, runs, scale)
        drawTextShaderFill(canvas, attrs, source, fillBounds, imagePaint) {
        runs.forEach { (line, startX, lineTop) ->
            drawBitmapGlyphRun(
                canvas = canvas,
                text = line,
                preparedFont = preparedFont,
                source = source,
                scale = scale,
                startX = startX,
                lineTop = lineTop,
                offsetX = 0f,
                offsetY = 0f,
                paint = imagePaint,
            )
        }
        }
        imagePaint.colorFilter = null
    }

    /** 用 [paint] 在 (offsetX, offsetY) 偏移处绘制整串位图字形（含字距）。 */
    private fun drawBitmapGlyphRun(
        canvas: Canvas,
        text: String,
        preparedFont: PreparedBitmapFont,
        source: PreparedSource,
        scale: Float,
        startX: Float,
        lineTop: Float,
        offsetX: Float,
        offsetY: Float,
        paint: Paint,
    ) {
        val font = preparedFont.definition
        var cursorX = startX + offsetX
        val top = lineTop + offsetY
        var previous: Int? = null
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val glyph = font.glyphs[codePoint]
            if (glyph != null) {
                cursorX += (previous?.let { font.kerning(it, codePoint) } ?: 0) * scale
                val bitmap = preparedFont.pageBitmaps.getOrNull(glyph.page)
                    ?.takeUnless(Bitmap::isRecycled)
                    ?: preparedFont.pageAssetPaths.getOrNull(glyph.page)
                        ?.let { path -> requestBitmap(path, source, fontAtlas = true) }
                        ?.also { loaded ->
                            preparedFont.pageBitmaps[glyph.page] = loaded
                        }
                if (bitmap != null && !bitmap.isRecycled) {
                    val sourceRect = mapWatchBitmapGlyphSourceRect(
                        glyph = glyph,
                        atlasWidth = font.atlasWidth,
                        atlasHeight = font.atlasHeight,
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                    )
                    if (sourceRect != null) {
                        bitmapFontSourceRect.set(
                            sourceRect.left,
                            sourceRect.top,
                            sourceRect.right,
                            sourceRect.bottom,
                        )
                        bitmapFontDestRect.set(
                            cursorX + glyph.xOffset * scale,
                            top + glyph.yOffset * scale,
                            cursorX + (glyph.xOffset + glyph.width) * scale,
                            top + (glyph.yOffset + glyph.height) * scale,
                        )
                        canvas.drawBitmap(bitmap, bitmapFontSourceRect, bitmapFontDestRect, paint)
                    }
                }
                cursorX += glyph.xAdvance * scale
                previous = codePoint
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun drawSeries(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val series = attrs["series"] ?: attrs["name"] ?: attrs["text"] ?: return
        val currentPos = attrs["current_pos"].orEmpty()
        val seriesKey = buildString {
            append(series).append('|').append(currentPos).append('|')
            append(WatchSeries.currentIndex(series, calendar)).append('|')
            append(calendar.get(Calendar.YEAR)).append('|').append(calendar.get(Calendar.MONTH))
        }
        if (source.seriesCache.size >= MAX_SERIES_CACHE_ENTRIES && seriesKey !in source.seriesCache) {
            source.seriesCache.clear()
        }
        val items = source.seriesCache.getOrPut(seriesKey) { WatchSeries.items(series, calendar, currentPos) }
        if (items.isEmpty()) return
        val currentDesignSize = dynamicNumber(attrs["text_size"], 40f, source).coerceIn(1f, DESIGN_SIZE)
        val otherDesignSize = dynamicNumber(attrs["text_size2"], currentDesignSize, source)
            .coerceIn(1f, DESIGN_SIZE)
        val currentSize = WatchTextLayout.ttfTextSize(currentDesignSize)
        val otherSize = WatchTextLayout.ttfTextSize(otherDesignSize)
        val spacing = dynamicNumber(attrs["spacing"], 10f, source)
        val orientation = attrs["orientation"].orEmpty().equals("v", true)
        val currentColor = resolveColor(attrs["color"], Color.WHITE, source)
        val otherColor = resolveColor(attrs["color2"], Color.GRAY, source)
        val currentTypeface = resolveTypeface(attrs["font"], source)
        val otherTypeface = resolveTypeface(attrs["font2"] ?: attrs["font"], source)
        val advance = spacing + currentSize * 1.4f
        textPaint.isLinearText = textNeedsLinearRaster()
        items.asSequence()
            .filter { item -> kotlin.math.abs(item.offset * advance) <= DESIGN_SIZE / 2f + currentSize * 2f }
            .forEach { item ->
            val size = if (item.isCurrent) currentSize else otherSize
            val offset = item.offset * advance
            textPaint.typeface = if (item.isCurrent) currentTypeface else otherTypeface
            textPaint.textSize = size
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = if (item.isCurrent) currentColor else otherColor
            textPaint.alpha = paintAlpha(alpha)
            val metrics = textPaint.fontMetrics
            val baseline = -(metrics.ascent + metrics.descent) / 2f
            canvas.save()
            if (orientation) canvas.translate(0f, offset) else canvas.translate(offset, 0f)
            canvas.drawText(item.value, 0f, baseline, textPaint)
            canvas.restore()
        }
    }

    private fun drawCalendar(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val cellWidth = dynamicNumber(attrs["cell_width"], 48f, source).coerceAtLeast(1f)
        val cellHeight = dynamicNumber(attrs["cell_height"], 42f, source).coerceAtLeast(1f)
        val marginX = dynamicNumber(attrs["margin_x"], 8f, source)
        val marginY = dynamicNumber(attrs["margin_y"], 8f, source)
        val headerDesignSize = dynamicNumber(attrs["text_size"], 14f, source).coerceIn(1f, DESIGN_SIZE)
        val dateDesignSize = dynamicNumber(attrs["text_size2"], headerDesignSize, source)
            .coerceIn(1f, DESIGN_SIZE)
        val headerSize = WatchTextLayout.ttfTextSize(headerDesignSize)
        val dateSize = WatchTextLayout.ttfTextSize(dateDesignSize)
        val headerColor = resolveColor(attrs["color"], Color.WHITE, source)
        val dateColor = resolveColor(attrs["color2"], Color.rgb(33, 150, 243), source)
        val start = attrs["dow_start"]?.toIntOrNull()?.coerceIn(0, 6) ?: 1
        val headerLetters = attrs["no_letters_header"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val outsideOpacity = dynamicNumber(attrs["outside_opacity"], 60f, source).coerceIn(0f, 100f) / 100f
        val dayKey = "${calendar.get(Calendar.YEAR)}|${calendar.get(Calendar.MONTH)}|${calendar.get(Calendar.DAY_OF_MONTH)}|$start"
        if (source.calendarCellsCache.size >= MAX_CALENDAR_CACHE_ENTRIES && dayKey !in source.calendarCellsCache) {
            source.calendarCellsCache.clear()
        }
        val grid = source.calendarCellsCache.getOrPut(dayKey) { WatchCalendarGrid.cells(calendar, start) }
        val labelKey = "$start|$headerLetters|${Locale.getDefault().toLanguageTag()}"
        val labels = source.calendarLabelsCache.getOrPut(labelKey) {
            WatchCalendarGrid.weekdayLabels(start, headerLetters)
        }
        val pitchX = cellWidth + marginX
        val pitchY = cellHeight + marginY
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = headerSize
        textPaint.isLinearText = textNeedsLinearRaster()
        textPaint.color = headerColor
        textPaint.alpha = paintAlpha(alpha)
        val left = -pitchX * 3.5f
        val top = -((pitchY * 7f) / 2f)
        val metrics = textPaint.fontMetrics
        val headerBaseline = top - marginY - metrics.descent
        labels.forEachIndexed { index, label ->
            canvas.drawText(label, left + pitchX * (index + 0.5f), headerBaseline, textPaint)
        }
        textPaint.typeface = resolveTypeface(attrs["font2"] ?: attrs["font"], source)
        textPaint.textSize = dateSize
        val dateMetrics = textPaint.fontMetrics
        grid.forEach { cell ->
            val x = left + pitchX * (cell.column + 0.5f)
            val y = top + pitchY * (cell.row + 1.5f)
            val valueColor = if (cell.isToday) contrastColor(dateColor) else dateColor
            if (cell.isToday) {
                shapePaint.color = dateColor
                shapePaint.alpha = paintAlpha(alpha)
                canvas.drawCircle(x, y - (dateMetrics.ascent + dateMetrics.descent) / 2f, dateSize * 0.62f, shapePaint)
            }
            textPaint.color = valueColor
            textPaint.alpha = paintAlpha(alpha * if (cell.isOutside) outsideOpacity else 1f)
            canvas.drawText(cell.day.toString(), x, y - (dateMetrics.ascent + dateMetrics.descent) / 2f, textPaint)
        }
    }

    private fun contrastColor(background: Int): Int {
        val luminance = (Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114) / 1000
        return if (luminance > 128) Color.BLACK else Color.WHITE
    }

    /**
     * `type="ring"`：环形进度。语义对齐 clox `wf_render.lua` 的 `Builders.ring`
     * （它照的是 UL Ring shader 与 wftools 预览，**不是** WM_FORMAT_1.0 §7.3 的旧文字）：
     *
     * - `color3` 是**未填充轨道**，只铺 `angle..angle_total` 这一段，超出 `angle_total`
     *   的部分保持透明。之前 Flue 画的是整整 360° 的圆，`angle_total < 360` 的表盘
     *   会多出一圈本不该存在的轨道。
     * - 填充段 `0..angle` 走 `color` → `color2` 渐变，插值参数是 `sweep / angle_total`
     *   （分母是**总量**不是当前值，所以进度涨落时同一角度的颜色不会飘）。
     * - 填充与轨道两端都是圆头（clox 的 `ringCap` = 半厚度的实心圆）。
     * - `radius_inner > radius_outer` 时互换，容错写反的表盘。
     *
     * 未对齐 clox 的一处是**有意**的：clox 恒定圆头，Flue 保留 `end_style` 显式写
     * `butt`/`square` 时的平头，默认才是圆头 —— 作者显式写了就按写的来。
     */
    private fun drawRing(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        // 缺省 100 与 clox 的 `r:evalNum("radius_outer", 100)` 一致。
        //
        // **这里以前拿 `radius` 当兜底，那是错的**：`ring` 的 `radius` 是**端帽倒角**
        // （见下面 capRounding），不是外半径。只写了 `radius="4"` 求平帽的环会被画成
        // 半径 4 的一个小点。clox 的参考实现从来没有这条兜底。
        val rawOuter = dynamicNumber(attrs["radius_outer"], 100f, source)
        // clox 的 radius_inner 默认是 radius_outer * 0.8（按比例），不是减固定像素。
        val rawInner = dynamicNumber(attrs["radius_inner"], rawOuter * 0.8f, source)
        // 写反了就互换（clox: `if ri > ro then ri, ro = ro, ri end`）。
        val outer = max(rawOuter, rawInner).coerceAtLeast(1f)
        val inner = min(rawOuter, rawInner).coerceIn(0f, outer - 0.5f)
        val stroke = (outer - inner).coerceAtLeast(1f)
        // 描边画在中线上，半厚度正好等于 clox 的 capR。
        val radius = inner + stroke / 2f
        val total = dynamicNumber(attrs["angle_total"], 360f, source).coerceAtLeast(1f)
        val sweep = dynamicNumber(attrs["angle"], 0f, source).coerceIn(0f, total)
        val clockwise = !attrs["is_clockwise"].equals("N", ignoreCase = true)
        // 环心跟随 alignment：clox 把图层盒定义成 2×radius_outer 再按锚点位移。
        // 注意 anchoredRect 返回的就是 rectScratch 本身，先把圆心读成局部量，
        // 下一行覆写它才安全。
        val centerX: Float
        val centerY: Float
        anchoredRect(outer * 2f, outer * 2f, attrs["alignment"]).let {
            centerX = it.centerX()
            centerY = it.centerY()
        }
        rectScratch.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        strokePaint.strokeWidth = stroke
        strokePaint.style = Paint.Style.STROKE
        // 端帽倒角（`radius`）。上游文档的语义：帽子**横跨整个环厚**、沿扫掠方向凸出
        // `radius`，只有凸出去的那两个角按 `radius` 倒圆；夹在 [0, 环厚/2]，
        // 省略时取上限 —— 上限正好让两个圆角相接，退化成经典半圆帽（也就是改动前的
        // 无条件行为）。所以**不写 radius 的表盘一帧都不会变**。
        //
        // `end_style` 是 Flue 早先自己加的扩展（clox 的 ring 完全不读它）。保留它是
        // 为了不回退按 Flue 写出来的表盘，但 `radius` 一旦显式出现就以 `radius` 为准 ——
        // 那是上游规范里的字段。
        val cap = watchRingCap(
            radiusValue = attrs["radius"]
                ?.takeIf(String::isNotBlank)
                ?.let { dynamicNumber(it, stroke / 2f, source) },
            endStyle = attrs["end_style"],
            stroke = stroke,
        )
        val capRounding = cap.rounding
        val squareCap = cap.square
        // Canvas 的 0° 在 3 点钟、顺时针为正；表盘的 0° 在 12 点钟。
        val startDegrees = -90f

        // 未填充轨道 [angle..angle_total]。只有作者真写了 color3 才画 —— 缺省值
        // Color.TRANSPARENT 会被随后的 strokePaint.alpha 覆写成不透明黑，
        // 无 color3 的环会平白多出一圈黑轨道。
        val trackRaw = attrs["color3"]
        if (!trackRaw.isNullOrBlank() && sweep < total) {
            val outsideOpacity = dynamicNumber(attrs["outside_opacity"], 100f, source).coerceIn(0f, 100f)
            strokePaint.shader = null
            strokePaint.color = resolveColor(trackRaw, Color.BLACK, source)
            strokePaint.alpha = paintAlpha(alpha * outsideOpacity / 100f)
            if (strokePaint.alpha > 0) {
                // 顺时针：轨道从 sweep 扫到 total；逆时针：从 -total 扫到 -sweep。
                val trackSweep = total - sweep
                val trackStart = if (clockwise) sweep else -total
                // 轨道两端也要帽子（clox 对 color3 段同样调两次 ringCap）。
                val trackCapColor = (strokePaint.color and 0x00FFFFFF) or
                    (strokePaint.alpha shl 24)
                drawRingArc(
                    canvas = canvas,
                    centerX = centerX,
                    centerY = centerY,
                    midRadius = radius,
                    stroke = stroke,
                    startDegrees = startDegrees + trackStart,
                    sweepDegrees = trackSweep,
                    capRounding = capRounding,
                    squareCap = squareCap,
                    startCapColor = trackCapColor,
                    endCapColor = trackCapColor,
                )
            }
        }

        // 填充段 [0..angle]。sweep == 0 时 clox 整段跳过（连端帽都不画）。
        if (sweep <= 0f) {
            strokePaint.shader = null
            return
        }
        val fillAlpha = paintAlpha(alpha)
        val startColor = resolveColor(attrs["color"], Color.WHITE, source) or OPAQUE_ALPHA_MASK
        val endRaw = attrs["color2"]
        val endColor = if (endRaw.isNullOrBlank()) {
            startColor
        } else {
            resolveColor(endRaw, Color.WHITE, source) or OPAQUE_ALPHA_MASK
        }
        val gradient = if (startColor == endColor) {
            null
        } else {
            ringGradient(centerX, centerY, startColor, endColor, total, clockwise, fillAlpha)
        }
        strokePaint.shader = gradient
        if (gradient == null) {
            strokePaint.color = startColor
            strokePaint.alpha = fillAlpha
        } else {
            // alpha 已经烘进 stop 色，paint alpha 必须置满：Skia 会拿 paint alpha
            // 再乘一遍着色器输出，不置满就是平方衰减（14.90% 的环会淡到看不见）。
            strokePaint.alpha = 255
        }
        // 帽子用**平色**（clox：`lerpRGB(ca, cb, s/total)` 取该扫掠位置的一个定值），
        // 不吃渐变着色器 —— 画帽子时要转画布坐标系，而着色器活在用户空间里，跟着转就
        // 会错位。平色本身也正是参考实现的行为。
        val fillStartCap = (startColor and 0x00FFFFFF) or (fillAlpha shl 24)
        val fillEndCap = (lerpRingRgb(startColor, endColor, sweep / total) and 0x00FFFFFF) or
            (fillAlpha shl 24)
        drawRingArc(
            canvas = canvas,
            centerX = centerX,
            centerY = centerY,
            midRadius = radius,
            stroke = stroke,
            startDegrees = startDegrees,
            sweepDegrees = if (clockwise) sweep else -sweep,
            capRounding = capRounding,
            squareCap = squareCap,
            startCapColor = fillStartCap,
            endCapColor = fillEndCap,
        )
        strokePaint.shader = null
    }

    /**
     * 画一段环（弧身 + 两个端帽），[strokePaint] 的颜色 / 着色器 / alpha 由调用方备好。
     *
     * 倒角到顶（也就是绝大多数环：没写 `radius`）时**只画一次 arc**，靠
     * [Paint.Cap.ROUND] 出半圆帽 —— 那与手工画两个圆角矩形的结果逐像素相同，却省下每帧
     * 两次 `drawPath`。秒针环每秒重画，这条快路不能省。
     *
     * @param midRadius 环中线半径（描边画在中线上）。
     * @param sweepDegrees 带符号扫掠量：正 = 顺时针。
     * @param capRounding 端帽倒角，已夹在 `[0, stroke/2]`。
     * @param squareCap Flue 扩展 `end_style="square"`；与 `radius` 互斥，由调用方判好。
     */
    private fun drawRingArc(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        midRadius: Float,
        stroke: Float,
        startDegrees: Float,
        sweepDegrees: Float,
        capRounding: Float,
        squareCap: Boolean,
        startCapColor: Int,
        endCapColor: Int,
    ) {
        val capMax = stroke / 2f
        if (squareCap) {
            strokePaint.strokeCap = Paint.Cap.SQUARE
            canvas.drawArc(rectScratch, startDegrees, sweepDegrees, false, strokePaint)
            return
        }
        if (capRounding >= capMax - RING_CAP_EPSILON) {
            strokePaint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(rectScratch, startDegrees, sweepDegrees, false, strokePaint)
            return
        }
        strokePaint.strokeCap = Paint.Cap.BUTT
        canvas.drawArc(rectScratch, startDegrees, sweepDegrees, false, strokePaint)
        if (capRounding <= RING_CAP_EPSILON) return
        val inner = midRadius - capMax
        val outer = midRadius + capMax
        val savedShader = strokePaint.shader
        val savedColor = strokePaint.color
        val savedAlpha = strokePaint.alpha
        strokePaint.shader = null
        strokePaint.style = Paint.Style.FILL
        // 起点帽朝扫掠的**反**方向凸出，终点帽朝扫掠方向凸出。
        strokePaint.color = startCapColor
        drawRingCap(canvas, centerX, centerY, inner, outer, startDegrees, capRounding, sweepDegrees < 0f)
        strokePaint.color = endCapColor
        drawRingCap(
            canvas,
            centerX,
            centerY,
            inner,
            outer,
            startDegrees + sweepDegrees,
            capRounding,
            sweepDegrees > 0f,
        )
        strokePaint.style = Paint.Style.STROKE
        strokePaint.shader = savedShader
        strokePaint.color = savedColor
        strokePaint.alpha = savedAlpha
    }

    /**
     * 一个端帽：横跨环厚、沿切向凸出 [rounding]，只有凸出端的两个角倒圆。
     *
     * 做法是把画布转到「该角度的径向 = +X」，帽子就退化成一个圆角矩形：`x ∈ [内, 外]`、
     * `y ∈ [0, rounding]`（或负向）。倒角半径等于凸出深度，所以 `rounding == 环厚/2` 时
     * 两个圆角刚好相接 —— 正是半圆帽。
     *
     * 转画布的前提是 [strokePaint] 此刻**没有着色器**（调用方已置空）：着色器在用户空间里，
     * 跟着 canvas 一起转就会画错。
     *
     * @param protrudeForward true = 朝 canvas 角度增大的一侧凸出（旋转后的 +Y）。
     */
    private fun drawRingCap(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        angleDegrees: Float,
        rounding: Float,
        protrudeForward: Boolean,
    ) {
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(angleDegrees)
        // 顺序是 [左上x,左上y, 右上x,右上y, 右下x,右下y, 左下x,左下y]。
        java.util.Arrays.fill(ringCapRadii, 0f)
        if (protrudeForward) {
            ringCapRect.set(innerRadius, 0f, outerRadius, rounding)
            // 凸出端 = 矩形下边。
            ringCapRadii[4] = rounding
            ringCapRadii[5] = rounding
            ringCapRadii[6] = rounding
            ringCapRadii[7] = rounding
        } else {
            ringCapRect.set(innerRadius, -rounding, outerRadius, 0f)
            // 凸出端 = 矩形上边。
            ringCapRadii[0] = rounding
            ringCapRadii[1] = rounding
            ringCapRadii[2] = rounding
            ringCapRadii[3] = rounding
        }
        ringCapPath.reset()
        ringCapPath.addRoundRect(ringCapRect, ringCapRadii, Path.Direction.CW)
        canvas.drawPath(ringCapPath, strokePaint)
        canvas.restore()
    }

    /** 逐通道线性插值（只用 RGB；alpha 由调用方另外合成）。 */
    private fun lerpRingRgb(startColor: Int, endColor: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val r = Color.red(startColor) + ((Color.red(endColor) - Color.red(startColor)) * t)
        val g = Color.green(startColor) + ((Color.green(endColor) - Color.green(startColor)) * t)
        val b = Color.blue(startColor) + ((Color.blue(endColor) - Color.blue(startColor)) * t)
        return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    /**
     * 圆环填充的 `color` → `color2` 扫描渐变。
     *
     * 等价于 clox 的 `fillArc(..., function(s) return lerpRGB(ca, cb, s / total) end)`：
     * 两端 stop 落在扫掠 0 与 `angle_total` 处，中间由 Skia 线性插值 ——
     * clox 靠 3° 一片的三角带手工插值，这里一次成型，无接缝也无重叠混色。
     *
     * 关键是**方向对齐**：[android.graphics.SweepGradient] 的参数 0 在 +X（3 点钟）、
     * 随 Canvas 角度顺时针增长，而表盘扫掠从 12 点钟起算。矩阵把着色器空间旋到表盘
     * 空间：顺时针环转 -90°；逆时针环先沿 Y 翻转（让参数随 Canvas 角度**递减**）再
     * 转 -90°。
     *
     * alpha 直接烘进 stop 色而不依赖 `Paint.alpha`，避免「着色器输出再乘一次 paint
     * alpha」的实现差异。
     */
    // ring 渐变按参数小缓存：秒针环的跨度（total）与圆心每帧都一样，颜色只在
    // alpha 变化时才重烘。线性扫小数组比较字段，命中路径零分配； miss 时才建
    // SweepGradient（含 colors/positions 数组与专属 Matrix）。绘制只在主线程。
    private class RingGradientCacheEntry {
        var centerX: Float = 0f
        var centerY: Float = 0f
        var startColor: Int = 0
        var endColor: Int = 0
        var total: Float = 0f
        var clockwise: Boolean = false
        var gradient: android.graphics.SweepGradient? = null
    }

    private val ringGradientCache = Array(SMALL_GRADIENT_CACHE_SIZE) { RingGradientCacheEntry() }
    private var ringGradientCacheCursor = 0

    /**
     * 线性渐变小缓存。进度条/进度环静止时几何与颜色逐帧不变，命中零分配；
     * 数值动画期间退化为每帧新建（与原行为一致）加一次字段比较。
     * 绘制只在主线程，无需同步。
     */
    private class LinearGradientCacheEntry {
        var x0: Float = 0f
        var y0: Float = 0f
        var x1: Float = 0f
        var y1: Float = 0f
        var startColor: Int = 0
        var endColor: Int = 0
        var gradient: android.graphics.LinearGradient? = null
    }

    private class SmallLinearGradientCache {
        private val entries = Array(SMALL_GRADIENT_CACHE_SIZE) { LinearGradientCacheEntry() }
        private var cursor = 0

        fun get(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            startColor: Int,
            endColor: Int,
        ): android.graphics.LinearGradient? {
            for (index in 0 until SMALL_GRADIENT_CACHE_SIZE) {
                val entry = entries[index]
                val cached = entry.gradient ?: continue
                if (entry.x0 == x0 && entry.y0 == y0 && entry.x1 == x1 && entry.y1 == y1 &&
                    entry.startColor == startColor && entry.endColor == endColor
                ) {
                    return cached
                }
            }
            return null
        }

        fun put(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            startColor: Int,
            endColor: Int,
            gradient: android.graphics.LinearGradient,
        ) {
            val entry = entries[cursor]
            cursor = (cursor + 1) % SMALL_GRADIENT_CACHE_SIZE
            entry.x0 = x0
            entry.y0 = y0
            entry.x1 = x1
            entry.y1 = y1
            entry.startColor = startColor
            entry.endColor = endColor
            entry.gradient = gradient
        }
    }

    private val progressFillGradients = SmallLinearGradientCache()
    private val ringImageGradients = SmallLinearGradientCache()
    private fun ringGradient(
        centerX: Float,
        centerY: Float,
        startColor: Int,
        endColor: Int,
        total: Float,
        clockwise: Boolean,
        alpha: Int,
    ): android.graphics.SweepGradient {
        val alphaBits = alpha shl 24
        val bakedStart = (startColor and 0x00FFFFFF) or alphaBits
        val bakedEnd = (endColor and 0x00FFFFFF) or alphaBits
        // angle_total > 360 的环，渐变在绕满一圈时就走到 color2 并保持。
        val position = (total / 360f).coerceIn(0.001f, 1f)
        for (index in 0 until SMALL_GRADIENT_CACHE_SIZE) {
            val entry = ringGradientCache[index]
            val cached = entry.gradient ?: continue
            if (entry.centerX == centerX && entry.centerY == centerY &&
                entry.startColor == bakedStart && entry.endColor == bakedEnd &&
                entry.total == position && entry.clockwise == clockwise
            ) {
                return cached
            }
        }
        val colors = intArrayOf(bakedStart, bakedEnd)
        val positions = floatArrayOf(0f, position)
        val matrix = android.graphics.Matrix()
        if (clockwise) {
            matrix.setRotate(-90f, centerX, centerY)
        } else {
            matrix.setScale(1f, -1f, centerX, centerY)
            matrix.postRotate(-90f, centerX, centerY)
        }
        val gradient = android.graphics.SweepGradient(centerX, centerY, colors, positions).apply {
            setLocalMatrix(matrix)
        }
        val entry = ringGradientCache[ringGradientCacheCursor]
        ringGradientCacheCursor = (ringGradientCacheCursor + 1) % SMALL_GRADIENT_CACHE_SIZE
        entry.centerX = centerX
        entry.centerY = centerY
        entry.startColor = bakedStart
        entry.endColor = bakedEnd
        entry.total = position
        entry.clockwise = clockwise
        entry.gradient = gradient
        return gradient
    }

    /**
     * `progress` —— 线性进度条。
     *
     * 以前这里只画「color3 全铺 + color 按百分比铺」两条圆角矩形，与规范差了 5 件事：
     *
     * 1. **`margin`**：规范原文是「The `color3` track and `color4` border are only drawn
     *    when `margin` > 0」（`WM_FORMAT_1.0.md:219`）—— margin 为 0 时未填充区应当
     *    **完全透明**。以前无条件画 color3，于是所有没写 margin 的进度条都多出一条
     *    深灰底；反过来，写了 margin 的表盘又看不到 color4 边框。
     * 2. **`corner_type`**：根本没读，永远四角全圆。
     * 3. **`color2`**：填充段应当是 `color → color2` 的渐变（沿填充方向）。
     * 4. **`angle`**：填充**轴向** —— 90 或缺省是横向左→右，0 / 180 是纵向下→上
     *    （`WM_FORMAT_1.0.md:218`）。以前恒为横向，竖直进度条全画错。
     * 5. **`end_style`**：前沿端头圆/平。
     *
     * 缺省值也一并对齐 clox `applyBox`（`wf_render.lua:1578-1585`）：
     * `width` 120、`height` 24、`radius` 50、`corner_type` 1、`angle` 90。
     */
    private fun drawProgress(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val sharedSize = attrs["size"]
        val width = dynamicNumber(attrs["width"] ?: sharedSize, 120f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, 24f, source).coerceAtLeast(1f)
        val pct = dynamicNumber(attrs["pct_complete"], 0f, source).coerceIn(0f, 100f) / 100f
        val margin = dynamicNumber(attrs["margin"], 0f, source).coerceAtLeast(0f)
        val cornerType = dynamicNumber(attrs["corner_type"], 1f, source).toInt()
        val radius = watchShapeClampCornerRadius(
            dynamicNumber(attrs["radius"], 50f, source),
            width,
            height,
        )
        // angle 是轴向不是旋转：只有 0 / 180 才算纵向，别的值（含缺省 90）都按横向。
        val angle = dynamicNumber(attrs["angle"], 90f, source)
        val vertical = angle == 0f || angle == 180f
        val outsideOpacity = dynamicNumber(attrs["outside_opacity"], 100f, source)
            .coerceIn(0f, 100f) / 100f
        val roundEnd = attrs["end_style"]?.trim()?.equals("round", ignoreCase = true) == true

        if (!attrs["tap_action"].isNullOrBlank() || !attrs["action"].isNullOrBlank()) {
            rectScratch.set(-width / 2f, -height / 2f, width / 2f, height / 2f)
            recordTapTarget(canvas, rectScratch, attrs)
        }

        // ① margin > 0 才画边框与轨道。margin == 0 时未填充区完全透明。
        val innerWidth = (width - margin * 2f).coerceAtLeast(0f)
        val innerHeight = (height - margin * 2f).coerceAtLeast(0f)

        // 描边 / 阴影：几何取**外框**（整条进度条的轮廓），不随 pct_complete 变 ——
        // 描边跟着填充头跑的话，进度一动整圈边就在抖，那不是任何人想要的效果。
        //
        // ⚠️ 这是 **Flue 扩展**：`WM_EFFECTS.md` §7 的支持矩阵里 progress 的
        // Shadow / Outline / Glow 都是 `—`，所以带描边的进度条导到官方 WatchMaker /
        // CloX / wftools 里只会画出没边的本体，不会报错也不会画错。`rounded` 在同一张
        // 矩阵里也是 `—` 而 Flue 一直在画（见 [drawRounded]），所以这里不算新开先例。
        //
        // 顺序与 [drawShape] / [drawRounded] 一致：先描边、再阴影、最后本体
        // （clox 的 z 序是描边 -2、阴影 -1）。
        val progressRounded = watchShapeHasRoundedCorner(cornerType, radius)
        resolveOutline(attrs, source)?.let { outline ->
            drawRoundedOutline(canvas, width, height, radius, cornerType, progressRounded, outline, alpha)
        }
        resolveShadow(attrs, source)?.let { shadow ->
            drawRoundedShadow(canvas, width, height, radius, cornerType, progressRounded, shadow, alpha)
        }

        if (margin > 0f) {
            shapePaint.shader = null
            shapePaint.color = resolveColor(attrs["color4"], Color.BLACK, source)
            shapePaint.alpha = paintAlpha(alpha)
            fillProgressBox(canvas, width, height, radius, cornerType)
            if (innerWidth > 0f && innerHeight > 0f) {
                shapePaint.color = resolveColor(attrs["color3"], Color.DKGRAY, source)
                shapePaint.alpha = paintAlpha(alpha * outsideOpacity)
                fillProgressBox(
                    canvas,
                    innerWidth,
                    innerHeight,
                    watchShapeClampCornerRadius(radius, innerWidth, innerHeight),
                    cornerType,
                )
            }
        }

        if (innerWidth <= 0f || innerHeight <= 0f || pct <= 0f) {
            shapePaint.shader = null
            return
        }

        // ② 填充段：裁进内框的圆角轮廓，再按轴向铺一条 color→color2 渐变。
        //    用 clip 而不是自己算「填充部分的圆角」——后者在 pct 很小时会算出比
        //    半径还窄的角，画出来是个尖楔子。
        val innerRadius = watchShapeClampCornerRadius(radius, innerWidth, innerHeight)
        val startColor = resolveColor(attrs["color"], Color.WHITE, source)
        val endColor = attrs["color2"]?.takeIf { it.isNotBlank() }
            ?.let { resolveColor(it, startColor, source) } ?: startColor
        canvas.save()
        canvas.clipPath(
            buildWatchRoundedRectPath(innerWidth, innerHeight, innerRadius, cornerType, pathScratch),
        )
        val fillLength = if (vertical) innerHeight * pct else innerWidth * pct
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (vertical) {
            // 纵向从**下往上**长（规范 §7.6：0 / 180 = 从底部向上填充）。
            left = -innerWidth / 2f
            right = innerWidth / 2f
            bottom = innerHeight / 2f
            top = bottom - fillLength
        } else {
            left = -innerWidth / 2f
            right = left + fillLength
            top = -innerHeight / 2f
            bottom = innerHeight / 2f
        }
        shapePaint.color = startColor
        shapePaint.alpha = paintAlpha(alpha)
        shapePaint.shader = if (endColor != startColor) {
            // 渐变只跨**已填充**那一段（规范：gradient spans the filled portion）。
            val gx0 = if (vertical) 0f else left
            val gy0 = if (vertical) bottom else 0f
            val gx1 = if (vertical) 0f else right
            val gy1 = if (vertical) top else 0f
            progressFillGradients.get(gx0, gy0, gx1, gy1, startColor, endColor)
                ?: android.graphics.LinearGradient(
                    gx0,
                    gy0,
                    gx1,
                    gy1,
                    startColor,
                    endColor,
                    android.graphics.Shader.TileMode.CLAMP,
                ).also {
                    progressFillGradients.put(gx0, gy0, gx1, gy1, startColor, endColor, it)
                }
        } else {
            null
        }
        if (roundEnd) {
            // 前沿端头圆：整段按内半径倒圆，起始那侧被 clip 修回内框轮廓。
            canvas.drawRoundRect(left, top, right, bottom, innerRadius, innerRadius, shapePaint)
        } else {
            canvas.drawRect(left, top, right, bottom, shapePaint)
        }
        shapePaint.shader = null
        canvas.restore()
    }

    /** 按 `corner_type` 决定走圆角路径还是矩形快路径。 */
    private fun fillProgressBox(
        canvas: Canvas,
        width: Float,
        height: Float,
        radius: Float,
        cornerType: Int,
    ) {
        if (watchShapeHasRoundedCorner(cornerType, radius)) {
            canvas.drawPath(
                buildWatchRoundedRectPath(width, height, radius, cornerType, pathScratch),
                shapePaint,
            )
        } else {
            canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, shapePaint)
        }
    }

    private fun drawShape(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val sharedSize = attrs["size"]
        val width = dynamicNumber(attrs["width"] ?: sharedSize, 64f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, width, source).coerceAtLeast(1f)
        // 7 种合法字形统一走 WatchShapeGeometry。以前这里只认 circle / triangle，
        // Star / Pentagon / Hexagon / Heart 全落到 else 分支画成矩形。
        val kind = watchShapeKindOf(attrs["shape"])
        if (!attrs["tap_action"].isNullOrBlank() || !attrs["action"].isNullOrBlank()) {
            rectScratch.set(-width / 2f, -height / 2f, width / 2f, height / 2f)
            recordTapTarget(canvas, rectScratch, attrs)
        }
        // 描边通道：与文字同一规格，先 STROKE 环、后 FILL 本体（内半被填充盖住）。
        resolveOutline(attrs, source)?.let { outline ->
            drawShapeOutline(canvas, kind, width, height, outline, alpha)
        }
        // 阴影在描边之上、本体之下（同文字：clox 描边 z=-2、阴影 z=-1）。
        resolveShadow(attrs, source)?.let { shadow ->
            drawShapeShadow(canvas, kind, width, height, shadow, alpha)
        }
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        // shader 通道：GradientLinear / Rainbow / Progress… 共 11 种，直接挂在 Paint 上，
        // 所以描边与阴影不受影响（它们用自己的 Paint），与规范「shader 只作用于填充」一致。
        val shader = resolveWatchShapeShader(attrs, width, height, source)
        shapePaint.shader = shader?.shader
        shapePaint.colorFilter = shader?.colorFilter
        if (kind == WatchShapeKind.CIRCLE) {
            canvas.drawOval(-width / 2f, -height / 2f, width / 2f, height / 2f, shapePaint)
        } else if (kind == WatchShapeKind.SQUARE) {
            canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, shapePaint)
        } else {
            canvas.drawPath(buildWatchShapePath(kind, width, height, pathScratch), shapePaint)
        }
        // Paint 是复用的实例，用完必须摘干净，否则下一层跟着套同一个渐变。
        shapePaint.shader = null
        shapePaint.colorFilter = null
    }

    /** shape 描边：硬描边一环 / Glow 多环，几何与本体完全一致。 */
    private fun drawShapeOutline(
        canvas: Canvas,
        kind: WatchShapeKind,
        width: Float,
        height: Float,
        outline: ResolvedOutline,
        alpha: Float,
    ) {
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.color = outline.color
        val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
        for (ring in rings downTo 1) {
            outlinePaint.strokeWidth = outline.size * 2f * ring / rings
            val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                watchGlowRingAlpha(ring, rings)
            } else {
                1f
            }
            outlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
            if (kind == WatchShapeKind.CIRCLE) {
                canvas.drawOval(-width / 2f, -height / 2f, width / 2f, height / 2f, outlinePaint)
            } else if (kind == WatchShapeKind.SQUARE) {
                canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, outlinePaint)
            } else {
                canvas.drawPath(buildWatchShapePath(kind, width, height, pathScratch), outlinePaint)
            }
        }
    }

    /**
     * shape 阴影：本体的**实心**偏移拷贝，几何与本体完全一致（FILL，不是描边环）。
     *
     * 偏移直接加进顶点坐标，不走 `canvas.translate` —— 省掉每份拷贝一次
     * save/restore；而且此处 canvas 已经吃过该图层的矩阵，加出来的偏移天然是
     * **图层局部**的，会跟着图层一起旋转/倾斜，和 clox 把拷贝挂在 `rec.holder`
     * 下同源。
     */
    private fun drawShapeShadow(
        canvas: Canvas,
        kind: WatchShapeKind,
        width: Float,
        height: Float,
        shadow: ResolvedShadow,
        alpha: Float,
    ) {
        shadowPaint.color = shadow.color
        shadowPaint.alpha = paintAlpha(alpha * shadow.opacity)
        // 由远及近：Flat 的第 copies 份最远，先画，近处的覆盖在上面。
        for (copy in shadow.copies downTo 1) {
            val distance = watchShadowCopyDistance(shadow.mode, shadow.distance, copy)
            val dx = watchShadowOffsetX(distance, shadow.angleDegrees)
            val dy = watchShadowOffsetY(distance, shadow.angleDegrees)
            if (kind == WatchShapeKind.CIRCLE) {
                canvas.drawOval(
                    dx - width / 2f,
                    dy - height / 2f,
                    dx + width / 2f,
                    dy + height / 2f,
                    shadowPaint,
                )
            } else if (kind == WatchShapeKind.SQUARE) {
                canvas.drawRect(
                    dx - width / 2f,
                    dy - height / 2f,
                    dx + width / 2f,
                    dy + height / 2f,
                    shadowPaint,
                )
            } else {
                canvas.drawPath(
                    buildWatchShapePath(kind, width, height, pathScratch, dx, dy),
                    shadowPaint,
                )
            }
        }
    }

    /**
     * `rounded` —— 带 `corner_type` 的圆角矩形。
     *
     * 三处以前与上游不一致的地方：
     * - **`corner_type` 根本没读**，永远四角全圆；规范 §7.12 那张 0..16 的表
     *   （`WM_FORMAT_1.0.md:405-425`）决定哪几个角圆。
     * - **`radius` 缺省算成 `min(w,h)/4`**，而 clox 是 `evalNum("radius", 0)`
     *   （`wf_render.lua:1477`）—— 没写 radius 的图层本该是直角矩形。
     * - **缺省宽高是 64×64**，clox 是 `width` 100 / `height` 60（`:1476`）。
     * - **没有描边与阴影通道**，同为形状族的 `shape` 有 —— 同一张表盘换个图层类型
     *   效果就没了。
     */
    private fun drawRounded(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val sharedSize = attrs["size"]
        val width = dynamicNumber(attrs["width"] ?: sharedSize, 100f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, 60f, source).coerceAtLeast(1f)
        val radius = watchShapeClampCornerRadius(
            dynamicNumber(attrs["radius"], 0f, source),
            width,
            height,
        )
        val cornerType = dynamicNumber(attrs["corner_type"], 1f, source).toInt()
        if (!attrs["tap_action"].isNullOrBlank() || !attrs["action"].isNullOrBlank()) {
            watchLayerTapBox("rounded", width, height)?.let { box ->
                rectScratch.set(box.left, box.top, box.right, box.bottom)
                recordTapTarget(canvas, rectScratch, attrs)
            }
        }
        val rounded = watchShapeHasRoundedCorner(cornerType, radius)
        resolveOutline(attrs, source)?.let { outline ->
            drawRoundedOutline(canvas, width, height, radius, cornerType, rounded, outline, alpha)
        }
        resolveShadow(attrs, source)?.let { shadow ->
            drawRoundedShadow(canvas, width, height, radius, cornerType, rounded, shadow, alpha)
        }
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        val shader = resolveWatchShapeShader(attrs, width, height, source)
        shapePaint.shader = shader?.shader
        shapePaint.colorFilter = shader?.colorFilter
        if (rounded) {
            canvas.drawPath(
                buildWatchRoundedRectPath(width, height, radius, cornerType, pathScratch),
                shapePaint,
            )
        } else {
            canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, shapePaint)
        }
        shapePaint.shader = null
        shapePaint.colorFilter = null
    }

    /** `rounded` 的描边：几何与本体完全一致，Glow 多环，与 [drawShapeOutline] 同规格。 */
    private fun drawRoundedOutline(
        canvas: Canvas,
        width: Float,
        height: Float,
        radius: Float,
        cornerType: Int,
        rounded: Boolean,
        outline: ResolvedOutline,
        alpha: Float,
    ) {
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.color = outline.color
        val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
        for (ring in rings downTo 1) {
            outlinePaint.strokeWidth = outline.size * 2f * ring / rings
            val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                watchGlowRingAlpha(ring, rings)
            } else {
                1f
            }
            outlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
            if (rounded) {
                canvas.drawPath(
                    buildWatchRoundedRectPath(width, height, radius, cornerType, pathScratch),
                    outlinePaint,
                )
            } else {
                canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, outlinePaint)
            }
        }
    }

    /** `rounded` 的阴影：本体的实心偏移拷贝，与 [drawShapeShadow] 同规格。 */
    private fun drawRoundedShadow(
        canvas: Canvas,
        width: Float,
        height: Float,
        radius: Float,
        cornerType: Int,
        rounded: Boolean,
        shadow: ResolvedShadow,
        alpha: Float,
    ) {
        shadowPaint.color = shadow.color
        shadowPaint.alpha = paintAlpha(alpha * shadow.opacity)
        for (copy in shadow.copies downTo 1) {
            val distance = watchShadowCopyDistance(shadow.mode, shadow.distance, copy)
            val dx = watchShadowOffsetX(distance, shadow.angleDegrees)
            val dy = watchShadowOffsetY(distance, shadow.angleDegrees)
            if (rounded) {
                canvas.drawPath(
                    buildWatchRoundedRectPath(width, height, radius, cornerType, pathScratch, dx, dy),
                    shadowPaint,
                )
            } else {
                canvas.drawRect(
                    dx - width / 2f,
                    dy - height / 2f,
                    dx + width / 2f,
                    dy + height / 2f,
                    shadowPaint,
                )
            }
        }
    }

    /**
     * `markers` —— 一圈刻度。
     *
     * 对齐 clox `Builders.markers`（缺省值也照抄：`radius` 200、`m_width` 10、
     * `m_height` 30；改动前这三个分别写成 220 / 6 / 20，整圈刻度的粗细位置都偏）。
     *
     * 三件以前缺的：
     * - **`shape`**：`Circle` 以前是拿 [Paint.Cap.ROUND] 画一条**长 m_height 的线**，
     *   而参考实现画的是直径 `m_width` 的**实心圆**；`Triangle` 根本没实现。
     * - **`squarify`**：把刻度圈从圆形拉向方形（见 [squarifiedRadius]），方屏表盘常用。
     * - **`radius` 的语义**：它是刻度的**外沿**（clox 把中心半径取成 `radius - m_height/2`），
     *   以前当成中心半径用，整圈外扩了半个刻度高。
     *
     * 2.0 新增的两个属性按文档补上，并标明不确定性：
     * - **`markers_shape`**（`Circle` / `Rounded`）：clox 的渲染器**完全不读它**，Studio
     *   的导入器只用它做「字形消歧」。所以这里按最保守的读法用：`shape` 缺席时它顶上当
     *   字形，`Rounded` 则把方块刻度的四角按 `m_width/2` 倒圆。
     * - **`radius_inner`**：给了就用 `[radius_inner, radius]` 这段径向跨度，`m_height` 让位。
     */
    private fun drawMarkers(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val count = watchMarkerCount(dynamicNumber(attrs["m_count"], 12f, source))
        val radius = dynamicNumber(attrs["radius"], 200f, source)
        val markWidth = dynamicNumber(attrs["m_width"], 10f, source).coerceAtLeast(1f)
        val markHeight = dynamicNumber(attrs["m_height"], 30f, source).coerceAtLeast(1f)
        val squarify = dynamicNumber(attrs["squarify"], 0f, source)
        val rawInner = attrs["radius_inner"]
        val innerRadius = if (rawInner.isNullOrBlank()) {
            null
        } else {
            dynamicNumber(rawInner, 0f, source).takeIf { it in 0f..(radius - 0.5f) }
        }
        val glyph = watchMarkerGlyph(attrs["shape"], attrs["markers_shape"])
        val rounded = attrs["markers_shape"].equals("Rounded", ignoreCase = true)
        shapePaint.shader = null
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        if (shapePaint.alpha <= 0) return
        repeat(count) { index ->
            // Canvas 的 0° 在 3 点钟、顺时针为正，所以 12 点钟起算要减 90°。
            val degrees = index * 360f / count - 90f
            val length = if (innerRadius != null) radius - innerRadius else markHeight
            // squarify 作用在**中心半径**上（clox：`squarifyRadius(radius - mh/2, th, sq)`）。
            val center = squarifiedRadius(radius - length / 2f, degrees, squarify)
            drawMarkerGlyph(
                canvas = canvas,
                degrees = degrees,
                centerRadius = center,
                length = length,
                width = markWidth,
                glyph = glyph,
                rounded = rounded,
            )
        }
    }

    /**
     * `markers_hm` —— 时针 / 分针两套刻度。
     *
     * 改动前整层是写死的：3 单位宽、时针 22 / 分针 10 长、只认一个 `color`，
     * `m_hour`、`m_minute`、`color2`、`squarify` 全部忽略。现在按 clox
     * `Builders.markers_hm` 的公式来：
     *
     * - 档位 → 尺寸：`None`=0、`Tiny`=1、`Small`=2、`Medium`=3、`Large`=4（缺省 Medium）；
     *   长宽由档位推 —— 分针 `档位×3` 长、`档位×0.9` 宽，时针 `档位×6` 长、`档位×1.8` 宽。
     * - `color` 是时针刻度色，`color2` 是分针刻度色（没写 `color2` 就两套同色）。
     * - 分针跳过 `i % 5 == 0`，那几格留给时针刻度。
     * - `radius` 缺省 240，且它是**外沿**；刻度从外沿往里长。
     *
     * 2.0 文档把档位表扩到 `None/Tiny/Small/Medium/Large/XLarge/Circle/Triangle`，而 clox
     * 只认前五个。多出来的三个按最合理的方式落地并标注：`XLarge` 顺着数列取 5，
     * `Circle` / `Triangle` 是**字形**而不是尺寸，按 Medium 的尺寸配对应字形画。
     */
    private fun drawHourMinuteMarkers(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val radius = dynamicNumber(attrs["radius"], 240f, source)
        val squarify = dynamicNumber(attrs["squarify"], 0f, source)
        val hour = watchHourMinuteMarkerPreset(attrs["m_hour"])
        val minute = watchHourMinuteMarkerPreset(attrs["m_minute"])
        val hourColor = resolveColor(attrs["color"], Color.WHITE, source)
        val rawMinuteColor = attrs["color2"]
        val minuteColor = if (rawMinuteColor.isNullOrBlank()) {
            hourColor
        } else {
            resolveColor(rawMinuteColor, Color.WHITE, source)
        }
        val paintedAlpha = paintAlpha(alpha)
        if (paintedAlpha <= 0) return
        shapePaint.shader = null
        if (minute.step > 0) {
            shapePaint.color = minuteColor
            shapePaint.alpha = paintedAlpha
            val length = minute.step * 3f
            val width = minute.step * 0.9f
            repeat(60) { index ->
                // 每 5 格让位给时针刻度。
                if (index % 5 == 0) return@repeat
                val degrees = index * 6f - 90f
                val outer = squarifiedRadius(radius, degrees, squarify)
                drawMarkerGlyph(
                    canvas = canvas,
                    degrees = degrees,
                    centerRadius = outer - length / 2f,
                    length = length,
                    width = width,
                    glyph = minute.glyph,
                    rounded = false,
                )
            }
        }
        if (hour.step > 0) {
            shapePaint.color = hourColor
            shapePaint.alpha = paintedAlpha
            val length = hour.step * 6f
            val width = hour.step * 1.8f
            repeat(12) { index ->
                val degrees = index * 30f - 90f
                val outer = squarifiedRadius(radius, degrees, squarify)
                drawMarkerGlyph(
                    canvas = canvas,
                    degrees = degrees,
                    centerRadius = outer - length / 2f,
                    length = length,
                    width = width,
                    glyph = hour.glyph,
                    rounded = false,
                )
            }
        }
    }

    /**
     * 画一个刻度字形。图层原点已经是刻度圈圆心，所以只要把画布转到「该角度的径向 = +X」，
     * 字形就退化成一个轴对齐的矩形 / 三角 / 圆 —— 不用逐点算三角函数。
     *
     * [shapePaint] 的颜色与 alpha 由调用方备好；这里不碰它们（一圈刻度只设一次色）。
     */
    private fun drawMarkerGlyph(
        canvas: Canvas,
        degrees: Float,
        centerRadius: Float,
        length: Float,
        width: Float,
        glyph: WatchMarkerGlyph,
        rounded: Boolean,
    ) {
        val half = length / 2f
        val inner = centerRadius - half
        val outer = centerRadius + half
        canvas.save()
        canvas.rotate(degrees)
        when (glyph) {
            // 实心圆：直径取 m_width，圆心落在刻度中心（clox 的 drawSolidCircle）。
            WatchMarkerGlyph.CIRCLE -> canvas.drawCircle(centerRadius, 0f, width / 2f, shapePaint)

            // 尖端朝外的等腰三角。
            WatchMarkerGlyph.TRIANGLE -> {
                pathScratch.reset()
                pathScratch.moveTo(outer, 0f)
                pathScratch.lineTo(inner, width / 2f)
                pathScratch.lineTo(inner, -width / 2f)
                pathScratch.close()
                canvas.drawPath(pathScratch, shapePaint)
            }

            WatchMarkerGlyph.SQUARE -> if (rounded) {
                canvas.drawRoundRect(
                    inner,
                    -width / 2f,
                    outer,
                    width / 2f,
                    width / 2f,
                    width / 2f,
                    shapePaint,
                )
            } else {
                canvas.drawRect(inner, -width / 2f, outer, width / 2f, shapePaint)
            }
        }
        canvas.restore()
    }

    /** Studio 2.0 textured progress ring. A missing texture is intentionally a no-op. */
    private fun drawRingImage(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
    ) {
        val authoredPath = attrs["path"] ?: attrs["image"]
        if (!authoredPath.isNullOrBlank()) {
            drawStaticMediaFallback(canvas, attrs + ("path" to authoredPath), alpha, source, layerCacheKey)
            return
        }
        val radius = dynamicNumber(attrs["radius"], 180f, source).coerceIn(1f, DESIGN_SIZE)
        val stroke = dynamicNumber(attrs["line_width"], 18f, source).coerceIn(1f, radius)
        val total = dynamicNumber(attrs["angle_total"], 360f, source).coerceAtLeast(1f)
        val sweep = watchRingImageSweep(
            percent = dynamicNumber(attrs["pct_complete"] ?: attrs["percent"], 0f, source),
            angleTotal = total,
        )
        val clockwise = !attrs["is_clockwise"].equals("N", ignoreCase = true)
        val outer = radius
        val center = anchoredRect(outer * 2f, outer * 2f, attrs["alignment"])
        rectScratch.set(center.left + stroke / 2f, center.top + stroke / 2f, center.right - stroke / 2f, center.bottom - stroke / 2f)
        val color = resolveColor(attrs["color"], Color.WHITE, source)
        val endColor = resolveColor(attrs["color2"], color, source)
        val outsideAlpha = dynamicNumber(attrs["outside_opacity"], 100f, source)
            .coerceIn(0f, 100f) / 100f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = stroke
        strokePaint.strokeCap = Paint.Cap.ROUND
        // `outside_opacity` belongs to the incomplete track. Draw it first;
        // the active arc below keeps the authored color/gradient untouched.
        if (outsideAlpha > 0f) {
            strokePaint.shader = null
            strokePaint.color = endColor
            strokePaint.alpha = paintAlpha(alpha * outsideAlpha)
            canvas.drawArc(rectScratch, -90f, if (clockwise) total else -total, false, strokePaint)
        }
        if (sweep <= 0f) {
            strokePaint.alpha = 0
            return
        }
        strokePaint.shader = if (color == endColor) null else {
            ringImageGradients.get(
                rectScratch.left,
                rectScratch.top,
                rectScratch.right,
                rectScratch.bottom,
                color,
                endColor,
            ) ?: android.graphics.LinearGradient(
                rectScratch.left,
                rectScratch.top,
                rectScratch.right,
                rectScratch.bottom,
                color,
                endColor,
                android.graphics.Shader.TileMode.CLAMP,
            ).also {
                ringImageGradients.put(
                    rectScratch.left,
                    rectScratch.top,
                    rectScratch.right,
                    rectScratch.bottom,
                    color,
                    endColor,
                    it,
                )
            }
        }
        strokePaint.color = color
        strokePaint.alpha = paintAlpha(alpha)
        canvas.drawArc(rectScratch, -90f, if (clockwise) sweep else -sweep, false, strokePaint)
        strokePaint.shader = null
    }

    /** Studio 2.0 repeated-icon progress bar. A missing source asset is a
     * deliberate no-op: the archive, rather than the launcher, owns that
     * visual resource. */
    private fun drawProgressImage(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
    ) {
        val shape = attrs["progress_shape"] ?: attrs["shape"] ?: "arrow1"
        val authoredPath = attrs["path"] ?: attrs["image"]
        val candidates = listOfNotNull(authoredPath) +
            watchStructuredAssetCandidates("progress_image", shape)
        val path = candidates.firstOrNull {
            resolveAsset(it, source, conventionalDirectory = "images") != null
        } ?: return
        val repeatCount = dynamicNumber(attrs["repeat_count"], 5f, source)
            .roundToInt()
            .coerceIn(1, 20)
        val width = dynamicNumber(attrs["width"], 220f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"], 55f, source).coerceAtLeast(1f)
        val percent = dynamicNumber(attrs["pct_complete"] ?: attrs["percent"], 0f, source)
            .coerceIn(0f, 100f) / 100f
        val activeCount = (repeatCount * percent).roundToInt().coerceIn(0, repeatCount)
        val iconWidth = width / repeatCount
        val baseAttrs = attrs + mapOf(
            "path" to path,
            "width" to iconWidth.toString(),
            "height" to height.toString(),
            "alignment" to "cc",
        )
        val outsideAlpha = dynamicNumber(attrs["outside_opacity"], 25f, source)
            .coerceIn(0f, 100f) / 100f
        val box = anchoredRect(width, height, attrs["alignment"])
        repeat(repeatCount) { index ->
            val isActive = index < activeCount
            canvas.save()
            canvas.translate(box.left + iconWidth * index + iconWidth / 2f, box.centerY())
            // The incomplete portion is still visible, but follows the
            // authored outside_opacity contract instead of vanishing.
            drawStaticMediaFallback(
                canvas = canvas,
                attrs = baseAttrs,
                alpha = alpha * if (isActive) 1f else outsideAlpha,
                source = source,
                layerCacheKey = "$layerCacheKey:progress:$index",
            )
            canvas.restore()
        }
    }

    /** Sunarc/daylight assets are bundled by Studio under predictable names. */
    private fun drawStructuredAssetLayer(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
        type: String,
    ) {
        val preset = attrs["${type}_sun_preset"] ?: attrs["sun_preset"]
        val map = attrs["${type}_map"]
        val candidates = listOfNotNull(
            attrs["path"],
            attrs["image"],
            watchStructuredAssetCandidates(type, map ?: preset).firstOrNull(),
        )
        val path = candidates.firstOrNull { resolveAsset(it, source, conventionalDirectory = "images") != null }
            ?: return
        drawStaticMediaFallback(canvas, attrs + ("path" to path), alpha, source, layerCacheKey)
    }

    /** Optional horizon primitive used by sunarc exports without a bitmap. */
    private fun drawHorizon(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val width = dynamicNumber(attrs["width"], DESIGN_SIZE, source).coerceIn(1f, DESIGN_SIZE)
        val y = dynamicNumber(attrs["y"], 0f, source).coerceIn(-DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        val lineWidth = dynamicNumber(attrs["line_width"], 2f, source).coerceIn(1f, 32f)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = lineWidth
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.shader = null
        strokePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        strokePaint.alpha = paintAlpha(alpha)
        canvas.drawLine(-width / 2f, y, width / 2f, y, strokePaint)
    }

    /**
     * `text_curved`：逐字沿弧排布，**不用** `drawTextOnPath`。
     *
     * 对照 CloX `wf_render.lua:2602` `Builders.text_curved` 与 WFTools
     * `WmPreview.kt:2156`：每个字形占一段**自己的宽度 × 60°/radius** 的角槽
     * （见 [WatchTextLayout.curvedGlyphSlots]），整串以弧中点为中心展开，
     * 再按切线旋转。`drawTextOnPath` 用的是真弧长 57.3°/px，会把字距压紧约 5%，
     * 环形排版里累积成整串偏移 —— 就是老师看到的「对不齐」。
     *
     * `curve_dir="Up"` 走上半弧（默认），`"Down"` 走下半弧；两者都从顶点居中展开。
     */
    private fun drawCurvedText(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val text = evaluateText(attrs["text"].orEmpty(), source)
        if (text.isBlank()) return
        val radius = dynamicNumber(attrs["radius"], 180f, source).coerceAtLeast(1f)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        val fontPx = WatchTextLayout.ttfTextSize(
            dynamicNumber(attrs["text_size"] ?: attrs["size"], 24f, source),
        )
        textPaint.textSize = fontPx
        textPaint.isLinearText = textNeedsLinearRaster()
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        textPaint.textAlign = Paint.Align.CENTER
        val glyphs = text.codePoints().toArray()
        val glyphWidths = FloatArray(glyphs.size)
        // 每个字形单独量宽：WM 的角槽按**各自的宽度**分配，不是等宽。
        for (index in glyphs.indices) {
            val single = String(Character.toChars(glyphs[index]))
            glyphWidths[index] = textPaint.measureText(single)
        }
        val isSpace = List(glyphs.size) { glyphs[it] == ' '.code || glyphs[it] == '\u00a0'.code }
        val slots = watchCurvedTextSlots(
            glyphWidths = glyphWidths.toList(),
            isSpace = isSpace,
            fontPx = fontPx,
            radius = radius,
        )
        val up = !attrs["curve_dir"].equals("Down", ignoreCase = true)
        // 整串在弧上居中：从 -总角/2 起算，逐字推进「半槽 → 画 → 半槽」。
        // 下面这段与 WFTools `WmPreview.kt:2175-2189` 逐行对应（含 Up/Down 的
        // 位置与旋转符号），不要凭直觉重推 —— Down 的旋转取负号是有意为之。
        var wdeg = -slots.sum() / 2f
        val extent = radius + fontPx
        drawTextShaderFill(canvas, attrs, source, RectF(-extent, -extent, extent, extent), textPaint) {
            for (index in glyphs.indices) {
                // wdeg 以 12 点钟方向为 0，向右（顺时针）为正。
                wdeg += slots[index] / 2f
                if (!isSpace[index]) {
                    val label = String(Character.toChars(glyphs[index]))
                    val radians = Math.toRadians(wdeg.toDouble())
                    val offsetX = (radius * sin(radians)).toFloat()
                    // Up：弧在上半，圆心在原点下方 → y 取负；Down 反之。
                    val offsetY = (if (up) -radius * cos(radians) else radius * cos(radians)).toFloat()
                    canvas.save()
                    canvas.translate(offsetX, offsetY)
                    canvas.rotate(if (up) wdeg else -wdeg)
                    canvas.drawText(label, 0f, 0f, textPaint)
                    canvas.restore()
                }
                wdeg += slots[index] / 2f
            }
        }
    }

    private fun drawTextRing(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val radius = dynamicNumber(attrs["radius"], 180f, source).coerceAtLeast(1f)
        val start = dynamicNumber(attrs["angle_start"], 0f, source)
        val end = dynamicNumber(attrs["angle_end"], 360f, source)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = WatchTextLayout.ttfTextSize(
            dynamicNumber(attrs["text_size"] ?: attrs["size"], 24f, source),
        )
        textPaint.isLinearText = textNeedsLinearRaster()
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        textPaint.textAlign = Paint.Align.CENTER
        val hiddenValues = attrs["hide_text"].orEmpty()
            .split(',')
            .mapNotNull { value -> value.trim().toIntOrNull() }
            .toSet()
        val showEvery = dynamicNumber(attrs["show_every"], 1f, source)
            .roundToInt()
            .coerceAtLeast(1)
        val items = watchTextRingItems(
            // ring_type is a range/enumeration literal (for example 1-12),
            // not arithmetic. Evaluating it turns the authored range into -11.
            ringType = watchTextRingType(attrs["ring_type"]),
            showEvery = showEvery,
            hiddenValues = hiddenValues,
            startDegrees = start,
            endDegrees = end,
        )
        val transform = attrs["transform"].orEmpty().lowercase(Locale.ROOT)
        val rotateLabels = attrs["rotated_text"].equals("y", ignoreCase = true)
        val metrics = textPaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
        val fillExtent = radius + textPaint.textSize
        drawTextShaderFill(canvas, attrs, source, RectF(-fillExtent, -fillExtent, fillExtent, fillExtent), textPaint) {
        items.forEach { item ->
            val label = when (transform) {
                "uc", "uppercase" -> item.label.uppercase(Locale.getDefault())
                "lc", "lowercase" -> item.label.lowercase(Locale.getDefault())
                else -> item.label
            }
            val radians = Math.toRadians(item.angleDegrees.toDouble())
            val x = sin(radians).toFloat() * radius
            val y = -cos(radians).toFloat() * radius
            if (rotateLabels) {
                canvas.save()
                canvas.rotate(item.angleDegrees)
                canvas.drawText(label, 0f, -radius + baselineOffset, textPaint)
                canvas.restore()
            } else {
                canvas.drawText(label, x, y + baselineOffset, textPaint)
            }
        }
        }
        if (!attrs["tap_action"].isNullOrBlank() || !attrs["action"].isNullOrBlank()) {
            val extent = radius + textPaint.textSize
            watchLayerTapBox("text_ring", extent * 2f, extent * 2f)?.let { box ->
                rectScratch.set(box.left, box.top, box.right, box.bottom)
                recordTapTarget(canvas, rectScratch, attrs)
            }
        }
    }

    /**
     * Bounded Canvas chart renderer for the common WM_FORMAT chart family.
     * It intentionally treats missing/unsupported data as an empty series,
     * never as a literal expression or a face-wide failure.
     */
    private fun drawChart(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val width = dynamicNumber(attrs["width"], 300f, source).coerceIn(1f, DESIGN_SIZE)
        val height = dynamicNumber(attrs["height"], 120f, source).coerceIn(1f, DESIGN_SIZE)
        val rawValues = evaluateText(
            attrs["data_points"]
                ?: attrs["values"]
                ?: attrs["data"]
                ?: attrs["points"]
                ?: attrs["lines"]
                ?: "",
            source,
        )
        val values = source.chartValuesCache.getOrPut(rawValues) {
            parseWatchChartValues(rawValues)
        }.orEmpty()
        if (values.isEmpty()) return
        val minMax = parseChartMinMax(attrs["min_max"], values)
        val minValue = minMax.first
        val maxValue = minMax.second
        val range = (maxValue - minValue).takeIf { it.isFinite() && it > 0f } ?: 1f
        val left = -width / 2f
        val top = -height / 2f
        val chartType = attrs["chart_type"]?.lowercase(Locale.ROOT)
            ?: attrs["type"]?.lowercase(Locale.ROOT)
            ?: "line"
        val color = resolveColor(attrs["color"], Color.WHITE, source)
        val markerColor = resolveColor(attrs["color4"], color, source)
        val lineWidth = dynamicNumber(attrs["line_width"], 4f, source).coerceIn(1f, 32f)
        val markerSize = dynamicNumber(attrs["marker_size"], lineWidth * 1.5f, source)
            .coerceIn(0f, 32f)

        fun yFor(value: Float): Float =
            top + height - ((value - minValue) / range).coerceIn(0f, 1f) * height

        // Optional horizontal reference lines.  They are parsed with the
        // same point cap and do not allocate an unbounded shader/label list.
        val rawReferenceLines = evaluateText(attrs["reference_lines"].orEmpty(), source)
        val referenceLines = source.chartValuesCache.getOrPut(rawReferenceLines) {
            parseWatchChartValues(rawReferenceLines)
        }.orEmpty()
        repeat(minOf(referenceLines.size, MAX_CHART_REFERENCE_LINES)) { index ->
            val reference = referenceLines[index]
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 1f
            strokePaint.color = resolveColor(attrs["color3"], Color.GRAY, source)
            strokePaint.alpha = paintAlpha(alpha * 0.6f)
            canvas.drawLine(left, yFor(reference), left + width, yFor(reference), strokePaint)
        }

        if (chartType.contains("bar")) {
            val spacing = width / values.size.coerceAtLeast(1)
            val thickness = dynamicNumber(attrs["bar_thickness"], 72f, source)
                .coerceIn(5f, 100f) / 100f
            val barWidth = (spacing * thickness).coerceAtLeast(1f)
            // corner_type 是 0..16 的**枚举**（规范 §7.12 那张表），不是像素半径。
            // 以前这里直接把它当半径用：corner_type="1"（四角全圆）画出来是 1px
            // 圆角≈直角，corner_type="16"（四角全直）反而给了 16px 圆角，语义整个反了。
            val cornerType = dynamicNumber(attrs["corner_type"], 0f, source).toInt()
            // 枚举本身不带半径，柱状图按「半个柱宽」取上限做胶囊角，与上游 bar 的观感一致。
            val barRadius = min(barWidth, height) / 2f
            val rounded = watchShapeHasRoundedCorner(cornerType, barRadius)
            values.forEachIndexed { index, value ->
                val cx = left + spacing * (index + 0.5f)
                val y = yFor(value)
                val baseline = yFor(minValue)
                shapePaint.color = color
                shapePaint.alpha = paintAlpha(alpha)
                if (rounded) {
                    canvas.drawPath(
                        buildWatchRoundedRectPathIn(
                            cx - barWidth / 2f,
                            min(y, baseline),
                            cx + barWidth / 2f,
                            max(y, baseline),
                            barRadius,
                            cornerType,
                            pathScratch,
                        ),
                        shapePaint,
                    )
                } else {
                    canvas.drawRect(
                        cx - barWidth / 2f,
                        min(y, baseline),
                        cx + barWidth / 2f,
                        max(y, baseline),
                        shapePaint,
                    )
                }
            }
            return
        }

        pathScratch.reset()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) left + width / 2f else {
                left + width * index / (values.lastIndex.toFloat())
            }
            val y = yFor(value)
            if (index == 0) pathScratch.moveTo(x, y) else pathScratch.lineTo(x, y)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = lineWidth
        strokePaint.color = color
        strokePaint.alpha = paintAlpha(alpha)
        canvas.drawPath(pathScratch, strokePaint)
        if (markerSize > 0f) {
            shapePaint.color = markerColor
            shapePaint.alpha = paintAlpha(alpha)
            values.forEachIndexed { index, value ->
                val x = if (values.size == 1) left + width / 2f else {
                    left + width * index / (values.lastIndex.toFloat())
                }
                canvas.drawCircle(x, yFor(value), markerSize / 2f, shapePaint)
            }
        }
    }

    private fun parseChartMinMax(raw: String?, values: List<Float>): Pair<Float, Float> {
        val parts = raw.orEmpty().split(Regex("\\s+to\\s+|[,;]"), limit = 2)
        val explicitMin = parts.getOrNull(0)?.trim()?.toFloatOrNull()
        val explicitMax = parts.getOrNull(1)?.trim()?.toFloatOrNull()
        val minValue = explicitMin ?: values.minOrNull() ?: 0f
        val maxValue = explicitMax ?: values.maxOrNull() ?: 1f
        return if (maxValue > minValue) minValue to maxValue else (minValue - 1f) to (maxValue + 1f)
    }

    /**
     * Render the bounded SVG subset prepared off the UI thread. Both XML and
     * native Path parsing finish before source publication, so a frame only
     * reads revision-scoped immutable objects.
     */
    private fun drawSvg(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val path = evaluateText(
            attrs["svg_path"] ?: attrs["svg_asset"] ?: attrs["path"] ?: "",
            source,
        )
        val resolved = resolveAsset(path, source, conventionalDirectory = "images")
            ?: resolveAsset(path, source, conventionalDirectory = "svg")
            ?: return
        val document = source.svgDocuments[resolved.cacheRevision] ?: return
        if (document.paths.isEmpty()) return
        val viewBox = attrs["svg_viewbox"]
            ?.split(Regex("[,\\s]+"))
            ?.mapNotNull(String::toFloatOrNull)
            ?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
            ?: document.viewBox
        val viewWidth = viewBox[2].coerceAtLeast(1f)
        val viewHeight = viewBox[3].coerceAtLeast(1f)
        val width = dynamicNumber(attrs["width"], document.width ?: viewWidth, source)
            .coerceIn(1f, DESIGN_SIZE)
        val height = dynamicNumber(attrs["height"], document.height ?: viewHeight, source)
            .coerceIn(1f, DESIGN_SIZE)
        val dst = anchoredRect(width, height, attrs["alignment"])
        val scaleX = width / viewWidth
        val scaleY = height / viewHeight
        canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.scale(scaleX, scaleY)
        canvas.translate(-viewBox[0], -viewBox[1])
        document.paths.forEach { pathData ->
            val fill = pathData.fill?.takeUnless { it.equals("currentColor", true) }
            shapePaint.color = resolveColor(fill ?: attrs["color"], Color.WHITE, source)
            shapePaint.alpha = paintAlpha(alpha)
            canvas.drawPath(pathData.path, shapePaint)
        }
        canvas.restore()
    }

    /**
     * `video`/`map` 的静态兜底。
     *
     * 视频有两种情况都要走这里：离屏出图（预览图/缩略图/景深底图 —— TextureView
     * 在软件 Canvas 里根本不存在），以及宿主没能给这一层配上播放面（同屏视频数
     * 超限时的降级）。两者都画首帧，绝不留透明空洞。
     */
    private fun drawStaticMediaFallback(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
    ) {
        val path = evaluateText(attrs["path"] ?: attrs["source"] ?: "", source)
        // 素材可能被作者放在 images/ 也可能在 videos/，两个约定目录都试。
        val resolved = resolveAsset(path, source, conventionalDirectory = "images")
            ?: resolveAsset(path, source, conventionalDirectory = "videos")
            ?: return
        val extension = resolved.file.extension.lowercase(Locale.ROOT)
        when {
            extension in STATIC_MEDIA_IMAGE_EXTENSIONS ->
                drawImage(canvas, attrs + ("path" to path), alpha, source, layerCacheKey)
            extension in PLAYABLE_VIDEO_EXTENSIONS ->
                drawVideoFirstFrame(canvas, attrs, alpha, source, resolved)
        }
    }

    /**
     * 按 [videoLayerGeometry] 同一套盒子语义画视频首帧。
     *
     * 首帧解码走和图片资源一样的 LRU + 失败冷却，所以逐帧调用不会反复解码；
     * 解码没跟上时不画，下一帧再来 —— 视频图层通常是背景，短暂空一帧远好过
     * 阻塞 onDraw。
     */
    private fun drawVideoFirstFrame(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        resolved: ResolvedAsset,
    ) {
        val frame = requestVideoFirstFrame(resolved, source)?.takeIf { !it.isRecycled } ?: return
        val sharedSize = attrs["size"]
        val width = dynamicNumber(
            attrs["width"] ?: sharedSize,
            source.designExtentWidth,
            source,
        ).coerceAtLeast(1f)
        val height = dynamicNumber(
            attrs["height"] ?: sharedSize,
            source.designExtentHeight,
            source,
        ).coerceAtLeast(1f)
        val dst = anchoredRect(width, height, attrs["alignment"])
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = null
        canvas.drawBitmap(frame, null, dst, imagePaint)
    }

    private fun anchoredRect(width: Float, height: Float, alignment: String?): RectF {
        val anchor = alignment.orEmpty().lowercase(Locale.ROOT).ifBlank { "cc" }
        val left = when {
            anchor.endsWith('l') -> 0f
            anchor.endsWith('r') -> -width
            else -> -width / 2f
        }
        val top = when {
            anchor.startsWith('t') -> 0f
            anchor.startsWith('b') -> -height
            else -> -height / 2f
        }
        return rectScratch.apply { set(left, top, left + width, top + height) }
    }

    private fun prepareSource(root: File, sourceGeneration: Long): PreparedSource {
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory) { ".watch 表盘安装目录不存在" }
        val watchXml = File(canonicalRoot, "watch.xml").takeIf(File::isFile)
            ?: canonicalRoot.walkTopDown().firstOrNull { it.isFile && it.name.equals("watch.xml", true) }
            ?: error(".watch 表盘缺少 watch.xml")
        val definition = WatchmakerDefinitionParser().parse(watchXml)
        val rootCacheKey = canonicalRoot.absolutePath
        val watchDirectoryKey = (watchXml.parentFile ?: canonicalRoot)
            .relativeTo(canonicalRoot)
            .invariantSeparatorsPath
            .takeUnless { it == "." }
            .orEmpty()
            .lowercase(Locale.ROOT)
        val files = canonicalRoot.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(canonicalRoot).invariantSeparatorsPath.lowercase(Locale.ROOT) }
            .toList()
        val exact = HashMap<String, ResolvedAsset>(files.size)
        val basename = HashMap<String, ResolvedAsset?>()
        val revisionByAbsolutePath = HashMap<String, String>(files.size)
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath.lowercase(Locale.ROOT)
            val cacheRevision = watchAssetCacheRevision(
                rootKey = rootCacheKey,
                relativePath = relative,
                length = file.length(),
                lastModifiedEpochMs = file.lastModified(),
            )
            val resolved = ResolvedAsset(relative, file, cacheRevision)
            exact[relative] = resolved
            revisionByAbsolutePath[file.absolutePath] = cacheRevision
            val key = file.name.lowercase(Locale.ROOT)
            basename[key] = if (basename.containsKey(key)) null else resolved
            sourceDigest.update(cacheRevision.toByteArray(Charsets.UTF_8))
            sourceDigest.update(0.toByte())
        }
        // Editor saves can happen more than once within a filesystem timestamp
        // quantum and may keep the same byte length. Hash bounded executable/
        // definition content so refreshes cannot be missed without re-reading
        // every image/font/movie asset.
        watchXml.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sourceDigest.update(buffer, 0, count)
            }
        }
        val renderPlanResult = buildWatchLayerRenderPlanResult(definition.layers)
        val initialScriptFile = files.firstOrNull { file ->
            file.relativeTo(canonicalRoot).invariantSeparatorsPath.equals("scripts/script.txt", true)
        }?.takeIf { file -> file.length() in 1..MAX_INITIAL_SCRIPT_BYTES }
        val initialScript = initialScriptFile
            ?.readText(Charsets.UTF_8)
        initialScriptFile?.let { file ->
            val metadataRevision = revisionByAbsolutePath[file.absolutePath].orEmpty()
            sourceDigest.update(
                watchExecutableContentRevision(metadataRevision, initialScript)
                    .toByteArray(Charsets.UTF_8),
            )
            sourceDigest.update(0.toByte())
        }
        val bitmapFonts = buildBitmapFontIndex(canonicalRoot, files)
        // SVG XML and native Path construction are both bounded and happen on
        // the IO preparation dispatcher. The generation-gated PreparedSource
        // publishes immutable paths which are only read by the main thread.
        val svgDocuments = HashMap<String, PreparedWatchSvgDocument<Path>>()
        var svgBytes = 0L
        files.asSequence()
            .filter { it.extension.equals("svg", true) }
            .take(MAX_SVG_ASSET_COUNT)
            .forEach { file ->
                if (svgBytes >= MAX_SVG_TOTAL_BYTES) return@forEach
                val remaining = (MAX_SVG_TOTAL_BYTES - svgBytes)
                    .coerceAtMost(MAX_SVG_BYTES.toLong())
                    .toInt()
                val bytes = runCatching {
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(remaining + 1)
                        var offset = 0
                        while (offset < buffer.size) {
                            val count = input.read(buffer, offset, buffer.size - offset)
                            if (count < 0) break
                            if (count == 0) continue
                            offset += count
                        }
                        buffer.copyOf(offset)
                    }
                }.getOrNull() ?: return@forEach
                if (bytes.size > remaining) return@forEach
                svgBytes += bytes.size
                val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                    .lowercase(Locale.ROOT)
                val resolved = exact[relative] ?: return@forEach
                // SVG Path objects live inside PreparedSource, so metadata-only
                // revisions are insufficient when an editor rewrites equal-size
                // content inside a coarse filesystem timestamp quantum.
                sourceDigest.update(bytes)
                sourceDigest.update(0.toByte())
                val document = runCatching {
                    parseWatchSvgDocument(bytes.toString(Charsets.UTF_8))
                }.getOrNull()
                prepareWatchSvgDocument(document) { pathData ->
                    runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull()
                }?.let { preparedDocument ->
                    svgDocuments[resolved.cacheRevision] = preparedDocument
                }
            }
        val definitionRevision = sourceDigest.digest().joinToString("") { "%02x".format(it) }
        val galleryAssetPaths = exact.keys.asSequence()
            .filter { relative ->
                val extension = relative.substringAfterLast('.', "").lowercase(Locale.ROOT)
                extension in WATCH_GALLERY_IMAGE_EXTENSIONS && (
                    relative.startsWith("gallery/") ||
                        relative.startsWith("images/gallery/") ||
                        "/gallery/" in relative
                    )
            }
            .sorted()
            .take(MAX_WATCH_GIF_FRAME_PATHS)
            .toList()
        val hasGyroParallax = definition.layers.any { layer ->
            layer.attributes["gyro"]?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
                raw.toFloatOrNull()?.let { it != 0f } ?: true
            } == true
        }
        val expressionScope = WatchExpressionScope.fromInitialScript(initialScript)
        val inheritedRuntime = inheritedLuaRuntime
        val initialScriptNow = Calendar.getInstance()
        val initialScriptSnapshot = runCatching {
            dataProvider.snapshot(initialScriptNow.timeInMillis)
        }.getOrDefault(WatchDataSnapshot())
        val initialScriptResolver: (String) -> WatchTagResolution = { tag ->
            val normalized = tag.trim().lowercase(Locale.ROOT)
            resolveWatchCounterTag(normalized, elapsedMs = 0L)
                ?.let(WatchTagResolution::Resolved)
                ?: WatchmakerTagResolver.resolve(
                    tag = normalized,
                    now = initialScriptNow,
                    snapshot = initialScriptSnapshot,
                    userColor = definition.rootAttributes["ucolor_default"],
                )
        }
        val luaRuntime = inheritedRuntime ?: if (
            watchFaceNeedsLuaRuntime(initialScript, definition.layers)
        ) {
            runCatching {
                WatchFaceLuaRuntime.create(
                    initialScript = initialScript,
                    resolveTag = initialScriptResolver,
                    // clox External Data API（B5）。惰性读：广播写进来的值下一帧就生效，
                    // 不需要跨线程往 Lua 状态机里推。
                    readExternalSlot = externalDataStore::slot,
                    externalDataVersion = externalDataStore::version,
                )
            }.getOrNull()
        } else {
            null
        }
        // 表达式预编译。对照 wf_expr.lua:11「Import is allowed to be slow; the
        // running face is not.」——这里已经在 IO dispatcher 上、已经受
        // sourceGeneration 保护，是天然的导入期。先把全部属性原文编译好塞进缓存，
        // 首帧就是全命中，渲染线程不必现编译。
        val expressionSources = definition.layers
            .take(MAX_WATCH_LAYER_COUNT)
            .flatMapTo(LinkedHashSet()) { layer -> watchmakerLayerAttributeValues(layer) }
            .also { it += definition.rootAttributes.values }
        val expressionCompileReport = WatchmakerExpressionEvaluator.precompile(
            sources = expressionSources,
            scope = expressionScope,
        )
        val luaExpressionSources = expressionSources
            .filter(::watchFaceAttributeUsesLua)
            .mapTo(LinkedHashSet()) { it.trim() }
        return PreparedSource(
            sourceGeneration = sourceGeneration,
            animationStartedAtUptimeMs = SystemClock.uptimeMillis(),
            root = canonicalRoot,
            rootCacheKey = rootCacheKey,
            definitionRevision = definitionRevision,
            watchDirectoryKey = watchDirectoryKey,
            definition = definition,
            hasGyroParallax = hasGyroParallax,
            exactFiles = exact,
            uniqueBasenames = basename,
            galleryAssetPaths = galleryAssetPaths,
            revisionByAbsolutePath = revisionByAbsolutePath,
            fontIndex = WatchFontIndex.build(canonicalRoot, files),
            bitmapFonts = bitmapFonts,
            expressionScope = expressionScope,
            luaRuntime = luaRuntime,
            ownsLuaRuntime = luaRuntime != null && inheritedRuntime == null,
            luaExpressionSources = luaExpressionSources,
            expressionCompileReport = expressionCompileReport,
            renderPlan = renderPlanResult.items,
            renderPlanDiagnostics = renderPlanResult.diagnostics,
            svgDocuments = svgDocuments.toMap(),
            needsFastTicks = definition.layers
                .take(MAX_WATCH_LAYER_COUNT)
                .any { layer ->
                    val type = layer.type.lowercase(Locale.ROOT)
                    watchLayerRequiresFrameTicks(type, layer.attributes) ||
                        (type == "chart" && watchmakerLayerAttributeValues(layer).any { value ->
                            DYNAMIC_TAG_PATTERN.containsMatchIn(value)
                        }) ||
                        watchmakerLayerAttributeValues(layer).any { value ->
                            WatchmakerTagResolver.requiresFastTicks(value)
                        }
                },
            // T-14：这张表盘有没有用到 {alat} 一族。位置权限只在真用得上的时候申请，
            // 顺手把它算在导入期 —— 每帧扫一遍 66 个图层的属性显然不行。
            usesLocationTags = expressionSources.any(WatchmakerTagResolver::usesLocationTags),
        )
    }

    private fun buildBitmapFontIndex(
        root: File,
        files: List<File>,
    ): Map<String, PreparedBitmapFont?> {
        val index = HashMap<String, PreparedBitmapFont?>()
        val ambiguous = HashSet<String>()
        fun putUnique(key: String, value: PreparedBitmapFont) {
            if (key.isBlank() || key in ambiguous) return
            val previous = index[key]
            if (previous == null && !index.containsKey(key)) {
                index[key] = value
            } else if (previous?.file != value.file) {
                index[key] = null
                ambiguous += key
            }
        }
        files.asSequence()
            .filter { file ->
                file.extension.equals("fnt", true) && file.length() in 1..MAX_BMFONT_DEFINITION_BYTES
            }
            .forEach { file ->
                val definition = runCatching {
                    parseWatchBitmapFont(file.readText(Charsets.UTF_8))
                }.getOrNull() ?: return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath.lowercase(Locale.ROOT)
                val relativeDirectory = relative.substringBeforeLast('/', "")
                val prepared = PreparedBitmapFont(
                    file = file,
                    relativeDirectory = relativeDirectory,
                    definition = definition,
                    pageAssetPaths = definition.pages.map { page ->
                        if (relativeDirectory.isBlank()) page else "$relativeDirectory/$page"
                    },
                )
                putUnique(relative, prepared)
                putUnique(file.name.lowercase(Locale.ROOT), prepared)
                putUnique(file.nameWithoutExtension.lowercase(Locale.ROOT), prepared)
                definition.face.trim().lowercase(Locale.ROOT)
                    .takeIf(String::isNotBlank)
                    ?.let { face -> putUnique(face, prepared) }
            }
        return index
    }

    private fun resolveBitmapFont(
        reference: String?,
        source: PreparedSource,
    ): PreparedBitmapFont? {
        val raw = reference.orEmpty().trim()
        if (raw.isBlank()) return null
        val explicitlyBitmap = raw.startsWith("bm:", ignoreCase = true)
        if (!explicitlyBitmap && source.fontIndex.resolve(raw) != null) return null
        val normalized = (if (explicitlyBitmap) raw.substring(3) else raw)
            .replace('\\', '/')
            .trimStart('/')
            .lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized.contains('\u0000')) return null
        val segments = normalized.split('/').filter(String::isNotBlank)
        if (segments.any { it == "." || it == ".." }) return null
        val path = segments.joinToString("/")
        val withExtension = if (path.endsWith(".fnt")) path else "$path.fnt"
        val candidates = linkedSetOf(
            path,
            withExtension,
            path.substringAfterLast('/'),
            withExtension.substringAfterLast('/'),
            "fonts_bm/$withExtension",
            "fonts/$withExtension",
        )
        return candidates.firstNotNullOfOrNull { key -> source.bitmapFonts[key] }
    }

    private fun requestBitmap(
        path: String,
        source: PreparedSource,
        animatedFrame: Boolean = false,
        fontAtlas: Boolean = false,
        minimumDecodeDimensionPx: Int? = null,
    ): Bitmap? {
        if (path.isBlank()) return null
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return null
        val assetPrefix = "${source.rootCacheKey}\u0000${resolved.key}\u0000"
        val decodeDimensionPx = when {
            fontAtlas -> MAX_FONT_ATLAS_DECODE_DIMENSION_PX
            minimumDecodeDimensionPx != null -> max(
                bitmapDecodeDimensionPx(),
                minimumDecodeDimensionPx.coerceAtMost(MAX_SPRITE_SHEET_DECODE_DIMENSION_PX),
            )
            else -> bitmapDecodeDimensionPx()
        }
        // A low-resolution request must never satisfy a later sprite-sheet
        // request for the same file. Include the decode budget in the cache key
        // so editor warm-up ordering cannot permanently pin a blurry bitmap.
        val key = "${resolved.cacheRevision}\u0000decode=$decodeDimensionPx"
        synchronized(cacheLock) {
            // 图集走自己的 LRU，绝不和图片/帧序列共享字节预算（见 [fontAtlasCache]）。
            val primaryCache = when {
                fontAtlas -> fontAtlasCache
                animatedFrame -> animatedBitmapCache
                else -> bitmapCache
            }
            primaryCache[key]?.let { return it }
            if (!fontAtlas) {
                val secondaryCache = if (animatedFrame) bitmapCache else animatedBitmapCache
                secondaryCache[key]?.let { return it }
            }
            val previousFrame = primaryCache.entries
                .lastOrNull { (cachedKey, bitmap) -> cachedKey.startsWith(assetPrefix) && !bitmap.isRecycled }
                ?.value
            // Do not start new image/font decodes while an overlay owns the
            // frame. Existing in-flight work may finish and populate caches,
            // but the next visible frame is requested only after resume.
            if (overlayRenderFrozen) return previousFrame
            bitmapFailures[key]?.let { failure ->
                val inCooldown = failure.generation == bindGeneration &&
                    SystemClock.uptimeMillis() - failure.failedAtUptimeMs < BITMAP_FAILURE_RETRY_MS
                if (inCooldown) return previousFrame
                bitmapFailures.remove(key)
            }
            // 走到这里 = 两级缓存都没命中、也不在失败冷却里 = **素材尚未就绪**。
            // 不管是这次才发起加载，还是别人已经在加载路上，对烘焙都是同一件事：
            // 这一段现在烘出来会缺东西，得等落地后重烘（见 [flattenBakeAssetMiss]）。
            // 只有 resolveAsset 失败（路径无效，永远不会落地）走不到这里 —— 那种
            // 段不该被记成「等素材」，否则每次任意素材落地都白烘它一次。
            flattenBakeAssetMiss = true
            if (bitmapLoading[key] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(
                    kind = AssetLoadKind.BITMAP,
                    key = key,
                    generation = generation,
                    // 帧序列帧不进烘焙段，落地后不该触发重烘（见 finishAssetLoad）。
                    affectsFlatten = !animatedFrame,
                )
                if (claim != null) {
                    bitmapLoading[key] = generation
                    // 编辑器预览最多只需要当前 View 像素级的清晰度。把 2K/4K
                    // 视频帧解到 1024px 再缩进几百像素画布，会平白增加解码、内存
                    // 带宽和每次拖动的绘制成本。
                    //
                    // 但**位图字体图集必须排除在这个降档之外**。图集是一张几百到
                    // 两千像素的图里塞几十个字形，单个字形只占几十像素；按 View
                    // 尺寸给整张图 inSampleSize 降采样，等于把每个字形的分辨率
                    // 同比砍掉，放大绘制必然糊（老师报的「位图字体非常模糊，
                    // 以前不会这样」就是这里）。图集另给一档上限，不吃编辑器降档。
                    val job = assetScope.launch {
                        var decoded: Bitmap? = null
                        try {
                            decoded = decodeBitmapSafely(resolved.file, decodeDimensionPx)
                            withContext(Dispatchers.Main.immediate) {
                                val result = decoded
                                var currentRequest = false
                                synchronized(cacheLock) {
                                    currentRequest = bitmapLoading[key] == generation
                                    if (currentRequest) bitmapLoading.remove(key)
                                    // 缓存按内容寻址（cacheRevision），旧 generation 解出的
                                    // 位图对新 generation 一样有效。应用表盘的头一秒里
                                    // refreshToken / 权限 / 可见性会连着触发好几次 bind，
                                    // 每次都推进 generation —— 原来的
                                    // `generation == bindGeneration` 把这些解码结果全部
                                    // 丢弃重来，GIF/图片层在屏上反复消失又出现，
                                    // 就是「应用后剧烈闪烁几次」的来源。
                                    if (currentRequest && !disposed) {
                                        if (result != null) {
                                            val staleKeys = primaryCache.keys
                                                .filter { cachedKey ->
                                                    cachedKey.startsWith(assetPrefix) && cachedKey != key
                                                }
                                            staleKeys.forEach { staleKey ->
                                                primaryCache.remove(staleKey)?.let { staleBitmap ->
                                                    val staleBytes = staleBitmap.safeAllocationByteCount()
                                                    when {
                                                        fontAtlas -> fontAtlasCacheBytes -= staleBytes
                                                        animatedFrame -> animatedBitmapCacheBytes -= staleBytes
                                                        else -> bitmapCacheBytes -= staleBytes
                                                    }
                                                    if (
                                                        staleBitmap !== result &&
                                                        staleBitmap !in lastDrawnFrameByLayer.values
                                                    ) {
                                                        retireBitmapAfterRender(staleBitmap)
                                                    }
                                                }
                                            }
                                            when {
                                                fontAtlas -> putFontAtlasLocked(key, result)
                                                animatedFrame -> putAnimatedBitmapLocked(key, result)
                                                else -> putBitmapLocked(key, result)
                                            }
                                            bitmapFailures.remove(key)
                                            decoded = null // Ownership transferred to the LRU cache.
                                        } else {
                                            putBoundedLocked(
                                                cache = bitmapFailures,
                                                key = key,
                                                value = BitmapFailure(
                                                    generation = generation,
                                                    failedAtUptimeMs = SystemClock.uptimeMillis(),
                                                ),
                                                maximumEntries = MAX_BITMAP_FAILURE_ENTRIES,
                                            )
                                        }
                                    }
                                }
                                if (currentRequest && !disposed && !overlayRenderFrozen) invalidate()
                            }
                        } finally {
                            // Cancellation can happen after decode but before the main-thread
                            // hand-off. In that case the bitmap never entered the cache.
                            decoded?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return previousFrame
        }
    }

    private fun requestMovie(resolved: ResolvedAsset, source: PreparedSource): Movie? {
        val key = resolved.cacheRevision
        synchronized(cacheLock) {
            movieCache[key]?.let { return it }
            movieFailures[key]?.let { failure ->
                val inCooldown = failure.generation == bindGeneration &&
                    SystemClock.uptimeMillis() - failure.failedAtUptimeMs < BITMAP_FAILURE_RETRY_MS
                if (inCooldown) return null
                movieFailures.remove(key)
            }
            if (overlayRenderFrozen) return null
            // 同 requestBitmap：缓存没命中且不在失败冷却 = 素材尚未就绪，这一段
            // 现在烘出来会缺东西。
            flattenBakeAssetMiss = true
            if (movieLoading[key] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(AssetLoadKind.MOVIE, key, generation)
                if (claim != null) {
                    movieLoading[key] = generation
                    val job = assetScope.launch {
                        val loaded = runCatching {
                            Movie.decodeFile(resolved.file.absolutePath)
                        }.getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            synchronized(cacheLock) {
                                if (movieLoading[key] == generation) movieLoading.remove(key)
                                // 同 requestBitmap：内容寻址缓存不需要 generation 门槛，
                                // 连续 bind 不再把解码好的 Movie 丢掉重来（GIF 闪烁根因）。
                                if (!disposed && loaded != null) {
                                    movieCache[key] = loaded
                                    movieFailures.remove(key)
                                    while (movieCache.size > MAX_MOVIE_CACHE_ENTRIES) {
                                        val iterator = movieCache.entries.iterator()
                                        if (!iterator.hasNext()) break
                                        iterator.next()
                                        iterator.remove()
                                    }
                                } else if (generation == bindGeneration && !disposed) {
                                    putBoundedLocked(
                                        cache = movieFailures,
                                        key = key,
                                        value = BitmapFailure(
                                            generation = generation,
                                            failedAtUptimeMs = SystemClock.uptimeMillis(),
                                        ),
                                        maximumEntries = MAX_MOVIE_FAILURE_ENTRIES,
                                    )
                                }
                            }
                            if (generation == bindGeneration && !disposed && !overlayRenderFrozen) invalidate()
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return null
        }
    }

    /**
     * 视频首帧的位图缓存键。**唯一真源** —— 逐帧绘制路径与离屏预热路径都从这里取，
     * 两边各写一份必然漂移，漂移的后果是预热白做、预览图仍然缺一层。
     */
    private fun videoFirstFrameCacheKey(resolved: ResolvedAsset): String =
        "video-frame\u0000${resolved.cacheRevision}"

    /**
     * 离屏出图前把视频图层的首帧**同步**烘进位图缓存。
     *
     * 为什么需要它：[requestVideoFirstFrame] 是异步的，首次调用一定返回 null，那一帧
     * 视频层什么都不画。上屏时无所谓（下一帧补上），但离屏出图**只画一次** —— 视频当
     * 背景的表盘保存出来的 preview.jpg 于是几乎全黑（老师报的问题）。
     *
     * 调用约定：**在主线程调**（解析素材要读 View 持有的 source 与 Lua 运行时），
     * 耗时的 MediaMetadataRetriever 由内部切到 IO，绝不占主线程。
     */
    suspend fun prewarmVideoFirstFrames() {
        if (disposed) return
        val targets = videoFirstFrameTargets()
        if (targets.isEmpty()) return
        val decodeDimensionPx = bitmapDecodeDimensionPx()
        for ((key, file) in targets) {
            if (disposed) return
            val alreadyCached = synchronized(cacheLock) { bitmapCache[key]?.isRecycled == false }
            if (alreadyCached) continue
            val bitmap = withContext(Dispatchers.IO) {
                extractVideoFirstFrame(file, decodeDimensionPx)
            } ?: continue
            var adopted = false
            synchronized(cacheLock) {
                if (!disposed && bitmapCache[key]?.isRecycled != false) {
                    putBitmapLocked(key, bitmap)
                    // 之前的失败冷却必须一起清掉，否则绘制路径仍会走「冷却中，跳过」。
                    bitmapFailures.remove(key)
                    adopted = true
                }
            }
            if (!adopted && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Loads every bitmap-font page before a one-shot offscreen preview render. */
    suspend fun prewarmBitmapFontAtlases() {
        if (disposed) return
        val prepared = source ?: return
        val fonts = prepared.bitmapFonts.values
            .filterNotNull()
            .distinctBy { it.file.absolutePath }
        for (font in fonts) {
            for ((pageIndex, path) in font.pageAssetPaths.withIndex()) {
                if (disposed) return
                val resolved = resolveAsset(path, prepared, conventionalDirectory = "images") ?: continue
                val key = "${resolved.cacheRevision}\u0000decode=$MAX_FONT_ATLAS_DECODE_DIMENSION_PX"
                val cached = synchronized(cacheLock) {
                    fontAtlasCache[key]?.takeUnless(Bitmap::isRecycled)
                }
                if (cached != null) {
                    font.pageBitmaps[pageIndex] = cached
                    continue
                }
                val decoded = withContext(Dispatchers.IO) {
                    decodeBitmapSafely(resolved.file, MAX_FONT_ATLAS_DECODE_DIMENSION_PX)
                } ?: continue
                var adopted: Bitmap? = null
                synchronized(cacheLock) {
                    if (!disposed) {
                        adopted = fontAtlasCache[key]?.takeUnless(Bitmap::isRecycled) ?: decoded.also {
                            putFontAtlasLocked(key, it)
                            bitmapFailures.remove(key)
                        }
                    }
                }
                if (adopted !== decoded && !decoded.isRecycled) decoded.recycle()
                adopted?.let { font.pageBitmaps[pageIndex] = it }
            }
        }
    }

    /**
     * 每个视频图层的（首帧缓存键，文件）对。只解析、不解码。
     *
     * 解析顺序必须与 [drawStaticMediaFallback] **完全一致**（先 images/ 再 videos/），
     * 否则预热出来的键和绘制时查的键不是同一个。
     */
    private fun videoFirstFrameTargets(): List<Pair<String, File>> {
        val prepared = source ?: return emptyList()
        val seen = HashSet<String>()
        return prepared.renderPlan.mapNotNull { item ->
            val layer = prepared.definition.layers.getOrNull(item.layerIndex)
                ?: return@mapNotNull null
            if (!layer.type.equals("video", ignoreCase = true)) return@mapNotNull null
            val attrs = displayAttributes(layer)
            val path = evaluateText(attrs["path"] ?: attrs["source"] ?: "", prepared)
            val resolved = resolveAsset(path, prepared, conventionalDirectory = "images")
                ?: resolveAsset(path, prepared, conventionalDirectory = "videos")
                ?: return@mapNotNull null
            if (resolved.file.extension.lowercase(Locale.ROOT) !in PLAYABLE_VIDEO_EXTENSIONS) {
                return@mapNotNull null
            }
            val key = videoFirstFrameCacheKey(resolved)
            // 同一个视频被多个图层引用时只提一次帧。
            if (!seen.add(key)) return@mapNotNull null
            key to resolved.file
        }
    }

    /**
     * 视频首帧，供离屏出图与降级路径使用。
     *
     * 结构照 [requestMovie]：内容寻址（cacheRevision）+ 失败冷却 + 主线程回填。
     * 首帧提取比图片解码贵得多（要起 MediaMetadataRetriever、seek 到 0），所以
     * 绝不能在 onDraw 里同步做，也绝不能失败后每帧重试。
     *
     * 离屏出图前请先调 [prewarmVideoFirstFrames]，理由见那里。
     */
    private fun requestVideoFirstFrame(
        resolved: ResolvedAsset,
        source: PreparedSource,
    ): Bitmap? {
        val key = videoFirstFrameCacheKey(resolved)
        synchronized(cacheLock) {
            bitmapCache[key]?.let { return it }
            bitmapFailures[key]?.let { failure ->
                val inCooldown = failure.generation == bindGeneration &&
                    SystemClock.uptimeMillis() - failure.failedAtUptimeMs < BITMAP_FAILURE_RETRY_MS
                if (inCooldown) return null
                bitmapFailures.remove(key)
            }
            if (bitmapLoading[key] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(AssetLoadKind.BITMAP, key, generation)
                if (claim != null) {
                    bitmapLoading[key] = generation
                    val targetPx = bitmapDecodeDimensionPx()
                    val job = assetScope.launch {
                        var decoded = extractVideoFirstFrame(resolved.file, targetPx)
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                val result = decoded
                                var currentRequest = false
                                synchronized(cacheLock) {
                                    currentRequest = bitmapLoading[key] == generation
                                    if (currentRequest) bitmapLoading.remove(key)
                                    if (currentRequest && !disposed) {
                                        if (result != null) {
                                            putBitmapLocked(key, result)
                                            bitmapFailures.remove(key)
                                            decoded = null // 所有权移交 LRU。
                                        } else {
                                            putBoundedLocked(
                                                cache = bitmapFailures,
                                                key = key,
                                                value = BitmapFailure(
                                                    generation = generation,
                                                    failedAtUptimeMs = SystemClock.uptimeMillis(),
                                                ),
                                                maximumEntries = MAX_BITMAP_FAILURE_ENTRIES,
                                            )
                                        }
                                    }
                                }
                                if (currentRequest && !disposed && !overlayRenderFrozen) invalidate()
                            }
                        } finally {
                            decoded?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return null
        }
    }

    private fun resolveTypeface(name: String?, source: PreparedSource): Typeface {
        val normalized = name.orEmpty().removePrefix("bm:").trim()
        if (normalized.isBlank() || normalized.equals("Roboto-Regular", true)) return Typeface.DEFAULT
        val key = normalized.lowercase(Locale.ROOT)
        val file = source.fontIndex.resolve(normalized)
        if (file == null) return Typeface.DEFAULT
        val relative = runCatching {
            file.relativeTo(source.root).invariantSeparatorsPath.lowercase(Locale.ROOT)
        }.getOrDefault(key)
        val fontPrefix = "${source.rootCacheKey}\u0000$relative\u0000"
        val revisionKey = source.revisionByAbsolutePath[file.absolutePath] ?: return Typeface.DEFAULT
        synchronized(cacheLock) {
            typefaceCache[revisionKey]?.let { return it }
            val previousTypeface = typefaceCache.entries
                .lastOrNull { (cachedKey, _) -> cachedKey.startsWith(fontPrefix) }
                ?.value
            if (typefaceLoading[revisionKey] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(AssetLoadKind.TYPEFACE, revisionKey, generation)
                if (claim != null) {
                    typefaceLoading[revisionKey] = generation
                    val job = assetScope.launch {
                        val loaded = runCatching { Typeface.createFromFile(file) }.getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            var currentRequest = false
                            synchronized(cacheLock) {
                                currentRequest = typefaceLoading[revisionKey] == generation
                                if (currentRequest) typefaceLoading.remove(revisionKey)
                                if (currentRequest && generation == bindGeneration && !disposed) {
                                    typefaceCache.keys.removeAll { cachedKey ->
                                        cachedKey.startsWith(fontPrefix) && cachedKey != revisionKey
                                    }
                                    putBoundedLocked(
                                        cache = typefaceCache,
                                        key = revisionKey,
                                        value = loaded ?: Typeface.DEFAULT,
                                        maximumEntries = MAX_TYPEFACE_CACHE_ENTRIES,
                                    )
                                }
                            }
                            if (currentRequest && generation == bindGeneration && !disposed && !overlayRenderFrozen) invalidate()
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return previousTypeface ?: Typeface.DEFAULT
        }
    }

    private fun bitmapDecodeDimensionPx(): Int {
        if (!editorAnimationPlaybackLimited) return MAX_DECODE_DIMENSION_PX
        // View 还没量到尺寸时**不能降档**：这里原来会 coerceAtLeast(256) 兜底，
        // 于是首帧之前发起的解码全部按 256px 上限跑。而位图缓存是按内容寻址
        // （cacheRevision），不含解码尺寸，那张 256px 的图会一直留在缓存里 ——
        // view 量好之后也不会重解。冷启动时序稍微一变，整块表盘就糊掉。
        val viewDimensionPx = maxOf(width, height)
        if (viewDimensionPx <= 0) return MAX_DECODE_DIMENSION_PX
        return viewDimensionPx
            .coerceAtLeast(MIN_EDITOR_DECODE_DIMENSION_PX)
            .coerceAtMost(MAX_EDITOR_DECODE_DIMENSION_PX)
    }

    private fun decodeBitmapSafely(
        file: File,
        maximumDimensionPx: Int = MAX_DECODE_DIMENSION_PX,
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (
                bounds.outWidth / sample > maximumDimensionPx ||
                bounds.outHeight / sample > maximumDimensionPx
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 抽视频首帧。
     *
     * `getScaledFrameAtTime` 只有 API 27+ 才有 —— 正好等于本应用的 minSdk，
     * 所以不需要兜底分支。用它而不是先取全尺寸再缩：4K 视频的整帧位图有
     * 30MB+，在低端机上足以直接 OOM。
     */
    private fun extractVideoFirstFrame(file: File, maximumDimensionPx: Int): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                if (width <= 0 || height <= 0) return null
                val scale = min(
                    1f,
                    maximumDimensionPx.toFloat() / max(width, height).toFloat(),
                )
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            } finally {
                runCatching { retriever.release() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /** Caller must hold [cacheLock]. */
    private fun putBitmapLocked(key: String, bitmap: Bitmap) {
        bitmapCache.put(key, bitmap)?.let { previous ->
            bitmapCacheBytes -= previous.safeAllocationByteCount()
            if (previous !== bitmap) retireBitmapAfterRender(previous)
        }
        bitmapCacheBytes += bitmap.safeAllocationByteCount()
        while (
            bitmapCache.size > MAX_BITMAP_CACHE_ENTRIES ||
            (bitmapCache.size > 1 && bitmapCacheBytes > MAX_BITMAP_CACHE_BYTES)
        ) {
            val iterator = bitmapCache.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next().value
            iterator.remove()
            bitmapCacheBytes -= eldest.safeAllocationByteCount()
            retireBitmapAfterRender(eldest)
        }
    }

    /**
     * 帧序列位图的入缓存，Caller must hold [cacheLock]。
     *
     * 这里的字节上限与 [MIN_ANIMATED_BITMAP_CACHE_ENTRIES] 保底条数是一对：
     * 帧序列是**循环访问**的工作集，每帧要当前帧、还要预取下一帧，所以缓存里
     * 至少得同时住得下两张，否则「放进下一帧」就会把「正在画的这一帧」挤掉，
     * 下一帧回来又得重解 —— 纯粹的乒乓，帧率直接对折。
     *
     * 保底条数必须优先于字节预算：解码上限是 1024px，一张 ARGB_8888 就是
     * 4.2MB，几张大帧很容易顶穿任何固定字节预算。宁可让字节短暂超出一点，
     * 也不能把缓存压到装不下一轮循环。
     */
    private fun putAnimatedBitmapLocked(key: String, bitmap: Bitmap) {
        animatedBitmapCache.put(key, bitmap)?.let { previous ->
            animatedBitmapCacheBytes -= previous.safeAllocationByteCount()
            if (previous !== bitmap) retireBitmapAfterRender(previous)
        }
        animatedBitmapCacheBytes += bitmap.safeAllocationByteCount()
        while (
            animatedBitmapCache.size > MAX_ANIMATED_BITMAP_CACHE_ENTRIES ||
            (animatedBitmapCache.size > MIN_ANIMATED_BITMAP_CACHE_ENTRIES &&
             animatedBitmapCacheBytes > MAX_ANIMATED_BITMAP_CACHE_BYTES)
        ) {
            val iterator = animatedBitmapCache.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next().value
            iterator.remove()
            animatedBitmapCacheBytes -= eldest.safeAllocationByteCount()
            retireBitmapAfterRender(eldest)
        }
    }

    /**
     * 位图字体图集的入缓存，Caller must hold [cacheLock]。
     *
     * 与 [putBitmapLocked] 的唯一区别是记在 [fontAtlasCacheBytes] 上 —— 也正是
     * 这一点让图集和图片层不再互相驱逐。这里 recycle 之前同样要避开
     * [lastDrawnFrameByLayer]：内容寻址的键理论上允许同一张文件既做图集又做
     * 图片层素材，撞上了就不能把别人还在画的实例回收掉。
     */
    private fun putFontAtlasLocked(key: String, bitmap: Bitmap) {
        fontAtlasCache.put(key, bitmap)?.let { previous ->
            fontAtlasCacheBytes -= previous.safeAllocationByteCount()
            if (previous !== bitmap) retireBitmapAfterRender(previous)
        }
        fontAtlasCacheBytes += bitmap.safeAllocationByteCount()
        while (WatchmakerFontAtlasCachePolicy.shouldEvict(fontAtlasCache.size, fontAtlasCacheBytes)) {
            val iterator = fontAtlasCache.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next().value
            iterator.remove()
            fontAtlasCacheBytes -= eldest.safeAllocationByteCount()
            retireBitmapAfterRender(eldest)
        }
    }

    /** Keeps an evicted animated frame alive until this layer draws its successor. */    private fun rememberLastDrawnFrame(key: WatchImageFallbackKey, bitmap: Bitmap) {
        val previous = lastDrawnFrameByLayer.put(key, bitmap)
        if (previous != null && previous !== bitmap) recycleDetachedLastFrame(previous)
    }

    private fun recycleDetachedLastFrame(bitmap: Bitmap) {
        retireBitmapAfterRender(bitmap)
    }

    private fun clearLastDrawnFrames() {
        val frames = lastDrawnFrameByLayer.values.distinct()
        lastDrawnFrameByLayer.clear()
        lastMaterialExpressionByLayer.clear()
        frames.forEach(::recycleDetachedLastFrame)
    }

    /** Drops a deleted/replaced layer's fallback before its XML slot can be reused. */
    private fun forgetReplacedImageFallbacks(layerCacheKey: String, materialExpression: String) {
        val previous = lastMaterialExpressionByLayer.put(layerCacheKey, materialExpression)
        if (previous == null || previous == materialExpression) return

        val detachedFrames = ArrayList<Bitmap>()
        val frameIterator = lastDrawnFrameByLayer.entries.iterator()
        while (frameIterator.hasNext()) {
            val entry = frameIterator.next()
            if (entry.key.layerCacheKey == layerCacheKey) {
                detachedFrames += entry.value
                frameIterator.remove()
            }
        }
        detachedFrames.distinct().forEach(::recycleDetachedLastFrame)
        lastDrawnMovieByLayer.keys.removeAll { it.layerCacheKey == layerCacheKey }
    }

    /** Caller must hold [cacheLock]. */
    private fun claimAssetLoadLocked(
        kind: AssetLoadKind,
        key: String,
        generation: Long,
        affectsFlatten: Boolean = true,
    ): AssetLoadClaim? {
        if (activeAssetLoads.size >= MAX_CONCURRENT_ASSET_LOADS) return null
        val claim = AssetLoadClaim(
            kind = kind,
            key = key,
            generation = generation,
            affectsFlatten = affectsFlatten,
        )
        return claim.takeIf(activeAssetLoads::add)
    }

    private fun finishAssetLoad(claim: AssetLoadClaim) {
        synchronized(cacheLock) {
            when (claim.kind) {
                AssetLoadKind.BITMAP -> {
                    if (bitmapLoading[claim.key] == claim.generation) bitmapLoading.remove(claim.key)
                }
                AssetLoadKind.MOVIE -> {
                    if (movieLoading[claim.key] == claim.generation) movieLoading.remove(claim.key)
                }
                AssetLoadKind.TYPEFACE -> {
                    if (typefaceLoading[claim.key] == claim.generation) typefaceLoading.remove(claim.key)
                }
            }
            activeAssetLoads.remove(claim)
        }
        if (disposed) return
        // 素材是异步解码的：烘焙时这张图可能还没就绪，段里会留一个空洞，而慢桶
        // tag 又没变 → 空洞永远不会被填上。所以素材落地后要重烘。
        //
        // ⚠️ 但只重烘**上次烘的时候真的缺素材**的那几段（[segmentsAwaitingAssets]），
        // 不是整批回收。312 有 300+ 图层在异步解码，一波持续两三秒；原来任何一次
        // 落地都把 16 段全部作废，每帧配额只是把白烧封顶成 2 段 × 120ms，
        // 收敛不了 —— framestats 里那串紧跟输入、8 帧 × 240ms 的尖峰就是它。
        //
        // 另外两层过滤保留：按 [AssetLoadClaim.affectsFlatten] 跳过帧序列/GIF 帧
        // （它们逐帧持续解码，落地速率接近每帧一次，且根本不可能出现在烘焙段里），
        // 以及用 pendingFlattenAssetInvalidation 把同一批解码合并成一次 post。
        //
        // 它们的重绘不会因此丢：解码落地时主线程侧已经 invalidate() 过一次
        // （见 [requestBitmap] / [requestMovie] 的 withContext(Main) 尾部）。
        if (claim.affectsFlatten && !pendingFlattenAssetInvalidation) {
            pendingFlattenAssetInvalidation = true
            post {
                pendingFlattenAssetInvalidation = false
                if (disposed) return@post
                if (source?.recycleSegmentsAwaitingAssets() == true && !overlayRenderFrozen) invalidate()
            }
        }
        if (claim.generation == sourceTransitionGeneration && !overlayRenderFrozen) {
            postInvalidateOnAnimation()
        }
    }

    /** Caller must hold [cacheLock]. */
    private fun <V> putBoundedLocked(
        cache: LinkedHashMap<String, V>,
        key: String,
        value: V,
        maximumEntries: Int,
    ) {
        cache[key] = value
        while (cache.size > maximumEntries) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun Bitmap.safeAllocationByteCount(): Long =
        runCatching { allocationByteCount.toLong() }
            .getOrElse { runCatching { byteCount.toLong() }.getOrDefault(0L) }

    /**
     * Retires a bitmap after it leaves every cache. Canvas drawing is recorded by
     * HWUI, so the Kotlin call stack ending does not mean RenderThread stopped
     * reading the bitmap. Keep an explicit render-thread lease for two frames.
     */
    private fun retireBitmapAfterRender(bitmap: Bitmap) {
        if (bitmap.isRecycled || isBitmapStillOwned(bitmap) || pendingBitmapRetirements.containsKey(bitmap)) {
            return
        }
        val lease = WatchmakerBitmapLease {
            synchronized(bitmap) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        lease.acquire(WatchmakerBitmapConsumer.RENDER_THREAD)
        pendingBitmapRetirements[bitmap] = lease
        lease.retire()
        bitmapRetirementHandler.postDelayed(
            {
                if (pendingBitmapRetirements[bitmap] === lease) {
                    pendingBitmapRetirements.remove(bitmap)
                    lease.release(WatchmakerBitmapConsumer.RENDER_THREAD)
                }
            },
            BITMAP_RETIREMENT_DELAY_MS,
        )
    }

    private fun isBitmapStillOwned(bitmap: Bitmap): Boolean =
        bitmapCache.values.any { it === bitmap } ||
            animatedBitmapCache.values.any { it === bitmap } ||
            fontAtlasCache.values.any { it === bitmap } ||
            lastDrawnFrameByLayer.values.any { it === bitmap }

    private fun clearAssetCaches() {
        val retiredBitmaps = IdentityHashMap<Bitmap, Boolean>()
        lastDrawnFrameByLayer.values.forEach { retiredBitmaps[it] = true }
        lastDrawnFrameByLayer.clear()
        lastMaterialExpressionByLayer.clear()
        lastDrawnMovieByLayer.clear()
        synchronized(cacheLock) {
            bitmapCache.values.forEach { retiredBitmaps[it] = true }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
            animatedBitmapCache.values.forEach { retiredBitmaps[it] = true }
            animatedBitmapCache.clear()
            animatedBitmapCacheBytes = 0L
            fontAtlasCache.values.forEach { retiredBitmaps[it] = true }
            fontAtlasCache.clear()
            fontAtlasCacheBytes = 0L
            movieCache.clear()
            bitmapLoading.clear()
            movieLoading.clear()
            bitmapFailures.clear()
            movieFailures.clear()
            typefaceLoading.clear()
            typefaceCache.clear()
        }
        // Do not recycle any bitmap synchronously: the just-finished display list
        // may still be consuming it on RenderThread.
        retiredBitmaps.keys.forEach(::retireBitmapAfterRender)
    }

    private fun scheduleTicks() {
        if (
            overlayRenderFrozen ||
            (!faceVisible && !textExitAnimationActive) ||
            !isAttachedToWindow ||
            tickPosted
        ) return
        tickPosted = true
        // 重力景深的采样与推进完全绑定 onDraw 频率(每帧 snapshot + 0.18 插值),
        // 所以它必须自己要求 30fps。这一项刻意跨越编辑器分支:早先它只写在
        // else 里,而编辑器一进来 editorAnimationPlaybackLimited 就是 true,
        // 于是重力条件永远不被求值,退化成 1s 一帧 —— 只有手指拖动素材时靠
        // 逐次 invalidate 才"看起来"正常。
        val gyroNeedsTicks = gravityParallaxEnabled &&
            source?.hasGyroParallax == true &&
            editorPreviewPlaying
        val needsFastTicks = when {
            textExitAnimationActive -> true
            editorAnimationPlaybackLimited ->
                gyroNeedsTicks ||
                    (
                        editorAnimatedImageLayerIndex != null &&
                            !editorAnimationInteractionFrozen &&
                            editorPreviewPlaying
                        )
            else -> source?.needsFastTicks == true ||
                source?.luaRuntime?.needsFrame() == true ||
                gyroNeedsTicks
        }
        val delay = watchmakerTickDelay(ambientMode, needsFastTicks)
        postDelayed(tickRunnable, delay)
    }

    private val tickRunnable = Runnable {
        tickPosted = false
        if (!overlayRenderFrozen && (faceVisible || textExitAnimationActive)) {
            val prepared = source
            val luaCallbackRan = if (prepared?.ownsLuaRuntime == true && prepared.luaRuntime != null) {
                prepared.luaRuntime.stepClock(
                    elapsedSeconds = SystemClock.uptimeMillis() / 1_000.0,
                    wallTimeMillis = System.currentTimeMillis(),
                ) { tag -> resolveTagResolutionWithoutLua(tag, calendar, prepared) }
                    .getOrDefault(false)
            } else {
                false
            }
            if (luaCallbackRan) frameTagResolutionCache.clear()
            if (evaluateWatchFaceTickRedraw(luaCallbackRan, ::shouldRedrawThisTick)) {
                invalidate()
                // 视频图层的几何要跟着表盘时钟走：图层的 x/y/rotation 可以绑定
                // 动态表达式，每帧都可能变。
                frameGeometryListener?.invoke()
            }
            scheduleTicks()
        }
    }

    /**
     * 阶段 2 的推模型判定：这一拍要不要重绘。
     *
     * 慢桶表盘（最快依赖到分钟）在 1s 拍子上先把慢桶 tag 全部重算一遍，**一个都
     * 没变就不重绘** —— 每分钟从 60 遍全量遍历降到 1 遍。快桶表盘、编辑器交互、
     * 文字退场动画、陀螺仪视差一律照旧每拍重绘，行为与改动前逐帧一致。
     *
     * ⚠️ 判定只能省掉「重绘」，绝不能省掉「重算」：
     * [WatchTagChangeTracker.poll] 内部把慢桶 tag 全部走完才返回。
     */
    private fun shouldRedrawThisTick(): Boolean {
        val prepared = source ?: return true
        // 编辑器交互、退场动画、过渡帧的时间来源都不是 tag，桶模型看不见它们。
        if (textExitAnimationActive || sourceTransitionFrame != null) return true
        if (editorAnimationPlaybackLimited || editorAnimatedImageLayerIndex != null) return true
        // 慢桶取值比对要用到当帧的 snapshot（电量、天气、步数都在里面）。
        calendar.timeInMillis = System.currentTimeMillis()
        refreshFrameDataSnapshot(calendar.timeInMillis)
        // ⚠️ [WatchTagChangeTracker.poll] 必须无条件先跑，**不能被快桶判定短路掉**。
        //
        // 它是慢桶失效信号（`hasPendingSlowTagInvalidation`）的唯一来源，而烘焙路径
        // [resolveActiveFlattenSegments] 全靠这个信号决定重烘。这里若对快桶表盘提前
        // return，信号永远到不了烘焙路径 —— 曾经为此在烘焙侧加过一个「每秒强制重烘」
        // 的节流器兜底，结果 377 层的表盘每秒把全部段位图回收重建一遍，滑动时撞上
        // 就掉帧，实机表现正是「一下流畅一下巨卡一下流畅」的周期性卡顿。
        //
        // 代价只是快桶表盘每帧多算一遍慢桶 tag：snapshot 是读缓存字段，tag 求值有
        // 阶段 1 的编译缓存，相比逐层绘制是小头。
        val slowTagsChanged = prepared.slowTagTracker.poll { tag ->
            when (val resolution = resolveTagResolution(tag, calendar, prepared)) {
                is WatchTagResolution.Resolved -> resolution.renderedValue
                // 不可用 / 不支持在一个 generation 内是稳定的，映射成同一个哨兵值即可。
                else -> null
            }
        }
        // poll() 对快桶表盘已经恒返回 true；这里再并一次是为了覆盖不在追踪器里的
        // 陀螺仪快依赖。
        return slowTagsChanged || requiresFastTicksNow(prepared)
    }

    /**
     * 陀螺仪视差的快依赖判定（计划 §2.3 第 1 点）。
     *
     * `smoothedGyroscopeX += (raw - smoothed) * 0.18f` 是低通滤波 —— 传感器停下后
     * 它还要收敛几十帧，所以「传感器值没变」不等于「渲染结果没变」，带 gyro 的
     * 表盘必须永远算快桶。
     */
    private fun requiresFastTicksNow(prepared: PreparedSource): Boolean =
        prepared.slowTagTracker.requiresRedrawEveryTick ||
            prepared.luaRuntime?.needsFrame() == true ||
            (gravityParallaxEnabled && prepared.hasGyroParallax && editorPreviewPlaying)

    /**
     * 逐帧回调，由 [WatchmakerWatchFaceStageView] 用来刷新 TextureView 几何。
     *
     * 挂在既有 tick 上而不是另起一个定时器：两套时钟必然错拍，视频会比
     * Canvas 图层慢半帧。
     */
    internal fun setFrameGeometryListener(listener: (() -> Unit)?) {
        frameGeometryListener = listener
    }

    private fun cancelTicks() {
        removeCallbacks(tickRunnable)
        tickPosted = false
    }

    /**
     * 全量重绘入口（对照 `wf_engine.lua:337` `repaint`），计划 §2.4。
     *
     * 凡是**渲染结果变了但 tag 没变**的路径都必须走这里，而不是裸 `invalidate()`：
     * 画布设置、数据源替换、重力开关、编辑器交互、可见性切换。它先清掉追踪器的
     * 取值缓存，保证紧接着的那一拍无条件重绘一次。
     *
     * 漏走这个入口的后果不是"慢一点"，而是图层**永久停在旧画面**上 —— 所以宁可
     * 多推，也不能少推。
     */
    private fun requestFullRepaint() {
        source?.slowTagTracker?.invalidateAll()
        frameTagResolutionCache.clear()
        if (!overlayRenderFrozen) invalidate()
    }

    private fun evaluateText(raw: String, source: PreparedSource): String {
        val value = raw.replace("&quot;", "\"").replace("&apos;", "'")
        source.luaRuntime?.evaluateFaceText(value) { tag ->
            resolveTagResolutionWithoutLua(tag, calendar, source)
        }?.let { return it.take(WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH) }
        if (source.luaExpressionSources.contains(value.trim())) {
            source.luaRuntime?.evaluateText(value) { tag ->
                resolveTagResolutionWithoutLua(tag, calendar, source)
            }?.let { return it.take(WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH) }
        }
        return WatchmakerExpressionEvaluator.evaluateText(value, source.expressionScope) { tag ->
            resolveTagResolution(tag, calendar, source)
        }
    }

    private fun resolveTagResolution(
        tag: String,
        now: Calendar,
        source: PreparedSource,
    ): WatchTagResolution {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        source.luaRuntime?.globalRenderedValue(normalized)?.let { value ->
            return WatchTagResolution.Resolved(value)
        }
        return resolveTagResolutionWithoutLua(normalized, now, source)
    }

    private fun resolveTagResolutionWithoutLua(
        tag: String,
        now: Calendar,
        source: PreparedSource,
    ): WatchTagResolution {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        resolveWatchCounterTag(
            rawTag = normalized,
            elapsedMs = SystemClock.uptimeMillis() - source.animationStartedAtUptimeMs,
        )?.let { value -> return WatchTagResolution.Resolved(value) }
        return WatchmakerTagResolver.resolve(
            tag = normalized,
            now = now,
            snapshot = frameDataSnapshot,
            // color_switch_* 选中的色优先于表盘声明的默认色。
            userColor = userColorOverride ?: source.definition.rootAttributes["ucolor_default"],
        )
    }

    private fun resolveFrameTag(
        tag: String,
        source: PreparedSource,
    ): WatchTagResolution {
        frameTagResolutionCache.beginFrame(frameDataSnapshotAtMs)
        return frameTagResolutionCache.resolve(tag) { normalized ->
            resolveTagResolution(normalized, calendar, source)
        }
    }

    private fun resolveFrameTagWithoutLua(
        tag: String,
        source: PreparedSource,
    ): WatchTagResolution {
        frameTagResolutionCache.beginFrame(frameDataSnapshotAtMs)
        return frameTagResolutionCache.resolve(tag) { normalized ->
            resolveTagResolutionWithoutLua(normalized, calendar, source)
        }
    }

    private fun resolveColor(raw: String?, fallback: Int, source: PreparedSource): Int {
        val literal = normalizeWatchColorLiteral(raw)
        val value = literal ?: evaluateText(raw.orEmpty(), source).trim()
        if (value.isBlank() || value == "--") return fallback
        return runCatching {
            val normalized = value.replace("#", "")
            Color.parseColor(if (normalized.length == 6 || normalized.length == 8) "#$normalized" else value)
        }.getOrDefault(fallback)
    }

    private fun isDisplayed(display: String?, source: PreparedSource): Boolean =
        watchmakerDisplayVisible(display, ambientMode) { value ->
            source.luaRuntime?.evaluateFaceBoolean(value) { tag ->
                resolveFrameTagWithoutLua(tag, source)
            } ?: if (source.luaExpressionSources.contains(value.trim())) {
                source.luaRuntime?.evaluateBoolean(value) { tag ->
                    resolveFrameTagWithoutLua(tag, source)
                } ?: run {
                    reportLuaAttributeFallback(source, "display", value)
                    false
                }
            } else {
                WatchmakerExpressionEvaluator.evaluateBoolean(value, false, source.expressionScope) {
                    tag -> resolveFrameTag(tag, source)
                }
            }
        }

    private fun dynamicNumber(value: String?, fallback: Float, source: PreparedSource): Float {
        val raw = value.orEmpty()
        source.luaRuntime?.evaluateFaceNumber(raw) { tag ->
            resolveFrameTagWithoutLua(tag, source)
        }?.let { return it.toFloat() }
        if (source.luaExpressionSources.contains(raw.trim())) {
            source.luaRuntime?.evaluateNumber(raw) { tag ->
                resolveFrameTagWithoutLua(tag, source)
            }?.takeIf(Double::isFinite)?.let { return it.toFloat() }
            reportLuaAttributeFallback(source, "number", raw)
        }
        return WatchmakerExpressionEvaluator.evaluateNumber(
            value,
            fallback,
            source.expressionScope,
        ) { tag -> resolveFrameTag(tag, source) }
    }

    private fun reportLuaAttributeFallback(source: PreparedSource, attribute: String, expression: String) {
        val summary = expression.replace(Regex("\\s+"), " ").take(180)
        val key = "$attribute\u0000$summary"
        val firstFailure = synchronized(source.reportedLuaFallbacks) {
            source.reportedLuaFallbacks.add(key)
        }
        if (firstFailure) {
            Log.w(TAG, "Lua $attribute attribute fell back safely: $summary")
        }
    }

    /**
     * Resolve an asset reference without touching the filesystem.  Watchmaker
     * exports commonly put watch.xml in a subdirectory while keeping images in
     * a sibling directory, so references are tried relative to watch.xml first
     * and then relative to the installed archive root.  Every candidate is
     * collapsed lexically and is rejected if it escapes the install root.
     */
    private fun resolveAsset(
        rawPath: String,
        source: PreparedSource,
        conventionalDirectory: String,
    ): ResolvedAsset? {
        val cacheKey = buildString {
            append(conventionalDirectory.lowercase(Locale.ROOT))
            append('\u0000')
            append(rawPath)
        }
        return source.assetResolutionCache.getOrPut(cacheKey) {
            val reference = normalizeAssetReference(rawPath) ?: return@getOrPut null
            val isRootRelative = rawPath.trim().replace('\\', '/').startsWith('/')
            val candidates = LinkedHashSet<String>()
            fun addCandidate(base: String, value: String) {
                normalizeRelativeAsset(base, value)?.let(candidates::add)
            }
            val base = if (isRootRelative) "" else source.watchDirectoryKey
            addCandidate(base, reference)
            if (!reference.startsWith("$conventionalDirectory/", ignoreCase = true)) {
                addCandidate(base, "$conventionalDirectory/$reference")
            }
            // A few 1.0 exporters deliberately use root-relative paths even
            // when watch.xml is nested. Keep this after the local interpretation.
            addCandidate("", reference)
            if (!reference.startsWith("$conventionalDirectory/", ignoreCase = true)) {
                addCandidate("", "$conventionalDirectory/$reference")
            }

            candidates.firstNotNullOfOrNull { key ->
                source.exactFiles[key]
            } ?: reference.substringAfterLast('/').let { basename ->
                source.uniqueBasenames[basename]
            }.also { resolved ->
                // T-13 的诊断口径：老师报「内置表盘编辑的图片全都显示不了」，但四条
                // 加载链路查下来都是对的，所以缺的是「到底试了哪几个 key」这一手证据。
                // 一次复现就能定位是路径多了一层、还是 basename 撞车被 uniqueBasenames
                // 丢掉了。命中时不打，别把正常表盘的 logcat 冲掉。
                if (resolved == null) {
                    Log.w(
                        TAG,
                        "asset miss raw=$rawPath ref=$reference dir=$conventionalDirectory " +
                            "candidates=$candidates basename=${reference.substringAfterLast('/')} " +
                            "basenameKnown=${source.uniqueBasenames.containsKey(reference.substringAfterLast('/'))}",
                    )
                }
            }
        }
    }

    private fun recordTapTarget(canvas: Canvas, bounds: RectF, attrs: Map<String, String>) {
        val raw = attrs["tap_action"] ?: attrs["action"]
        val action = WatchTapActionParser.parse(raw)
        val mapped = RectF(bounds)
        canvas.matrix.mapRect(mapped)
        // opacity 要在这里就求值：命中仲裁发生在手指落下那一刻，那时候已经不在绘制
        // 上下文里，拿不到 source 也拿不到图层 attrs 了。
        val opacity = source?.let { prepared ->
            attrs["opacity"]?.let { dynamicNumber(it, 100f, prepared) }
        } ?: 100f
        // 原始动作名要一起留住：解析器会把 `widget_weather` 这类**可配置**名字短路成
        // 强类型动作（WatchTapAction.Weather），名字一丢，运行时就再也不知道该去
        // 用户映射表里查哪个键了 —— 于是设置里明明指了应用、点上去还报打不开。
        if (!mapped.isEmpty) {
            tapTargets += TapTarget(mapped, action, raw?.trim().orEmpty(), opacity)
        }
    }

    /**
     * 命中仲裁：**中心最近**者胜，而不是取最上层。
     *
     * 以前这里是 `tapTargets.asReversed().firstOrNull { it.bounds.contains(x, y) }` ——
     * 取最上层包含该点的框。多页表盘里按钮几何是跨页重叠的（同一块屏幕区域，第 15 页
     * 是秒表按钮、第 17 页是配色按钮），XML 里声明更晚的那个永远压在上面，于是在第 15
     * 页点 START/STOP 实际触发的是第 17 页的 `color_switch_prev`。上游取的是中心距离最近
     * 者，外加 50 设计单位的容半径（`wf_render.lua:2878-2903`），所以同样的表盘在上游是对的。
     *
     * 容半径要换算到像素：`tapTargets` 里的框已经过 `canvas.matrix` 映射，是 View 像素，
     * 而 50 是设计空间（512×512）里的数。
     */
    private fun dispatchTap(x: Float, y: Float) {
        if (tapTargets.isEmpty()) return
        val graceRadiusPx = WATCH_TAP_GRACE_DESIGN_UNITS *
            ((frameCanvasScaleX + frameCanvasScaleY) / 2f).coerceAtLeast(0.0001f)
        val candidates = tapTargets.map { candidate ->
            WatchTapHitCandidate(
                containsPoint = candidate.bounds.contains(x, y),
                centerDistance = hypot(candidate.bounds.centerX() - x, candidate.bounds.centerY() - y),
                opacity = candidate.opacity,
            )
        }
        val index = selectNearestWatchTapHitIndex(candidates, graceRadiusPx) ?: return
        val target = tapTargets[index]
        // Acknowledges the accepted tap before Lua or external actions do work.
        if (tapHapticsEnabled && target.action !is WatchTapAction.Unsupported) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        if (target.action is WatchTapAction.ColorSwitch) {
            applyWatchColorSwitch(target.action.direction)
            return
        }
        if (target.action is WatchTapAction.Script) {
            routeTapAction(target.action, target.rawAction)
            return
        }
        // 用户映射优先于宿主接管：宿主（桌面）那套只认强类型动作，它不知道原始名字，
        // 接过去就等于把「用户指定的应用」这一层丢掉。
        if (launchUserMappedAction(target.rawAction)) return
        val handler = tapActionHandler
        if (handler != null) {
            handler(target.action)
        } else {
            routeTapAction(target.action, target.rawAction)
        }
    }

    /**
     * `color_switch_next` / `_prev` / `_select` —— 在 41 色强调色表里循环，重新发布
     * `{ucolor}` / `{ucolor_b}` 并整帧重绘。
     *
     * 索引 0 保留给表盘自己的 `ucolor_default`，所以一圈是 42 档（上游 `wf_render.lua:2779-2803`
     * 同样把默认色算作一档）。索引持久化到 SharedPreferences —— 上游存在脚本变量
     * `__color_idx` 里，跟着表盘存档走；Flue 没有脚本变量的持久层，所以按表盘 key 存偏好。
     */
    private fun applyWatchColorSwitch(direction: Int) {
        val prepared = source ?: return
        val prefs = context.getSharedPreferences(WATCH_COLOR_SWITCH_PREFS, Context.MODE_PRIVATE)
        val selection = selectWatchColor(
            defaultColor = prepared.definition.rootAttributes["ucolor_default"],
            currentIndex = prefs.getInt(prepared.rootCacheKey, 0),
            direction = direction,
        )
        prefs.edit().putInt(prepared.rootCacheKey, selection.index).apply()
        // 索引 0 = 回到表盘默认色，这时要把 override 摘掉而不是写死成默认色的字面量：
        // 表盘刷新后 ucolor_default 可能变（作者改了 XML），override 不该把旧值钉住。
        userColorOverride = selection.color.takeIf { selection.index != 0 }
        // {ucolor} 归 STATIC 桶（WatchmakerTagBuckets.kt:96），追踪器认为它永不变，
        // 依赖它的图层已经被烘死。invalidateAll() 把追踪器打回 pristine 才会重烘。
        requestFullRepaint()
    }

    /** 载入表盘时恢复它上次选中的配色档位。索引越界（调色板改过）时回落到默认色。 */
    private fun restoreWatchColorSwitchSelection(prepared: PreparedSource?) {
        if (prepared == null) {
            userColorOverride = null
            return
        }
        val stored = context.getSharedPreferences(WATCH_COLOR_SWITCH_PREFS, Context.MODE_PRIVATE)
            .getInt(prepared.rootCacheKey, 0)
        userColorOverride = if (stored in 1..WATCH_COLOR_SWITCH_PALETTE.size) {
            WATCH_COLOR_SWITCH_PALETTE[stored - 1]
        } else {
            null
        }
    }

    /**
     * T-14：表盘用到坐标 tag 时才申请位置权限。
     *
     * 以前唯一的运行时申请点在「设置 → 表盘天气 → 使用系统定位」里，没点过那一项的
     * 用户，`{alat}` 一族就永远没有数据源 —— 这正是「经纬度变量读不出来」的一半原因。
     *
     * 一张表盘只弹一次（按 rootCacheKey 记账）：用户拒了就不该每次回表盘再问一遍。
     * 拿不到 Activity 时安静跳过 —— 这个 View 也跑在小组件预览和编辑器缩略图里，
     * 那些上下文下弹权限框是错的。
     */
    private fun requestLocationPermissionIfFaceNeedsIt(prepared: PreparedSource?) {
        if (prepared == null || !prepared.usesLocationTags) return
        if (WatchLocationPermissionPolicy.isGranted(context)) return
        val activity = context.findHostActivity() ?: return
        if (!locationPromptedFaceKeys.add(prepared.rootCacheKey)) return
        WatchLocationPermissionPolicy.request(activity)
    }

    private fun Context.findHostActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return current as? Activity
    }

    /**
     * 按**原始动作名**查用户在「设置 → 表盘点击动作」里指定的应用并启动。
     *
     * 返回 true 表示已经处理掉了。只对 [WatchTapActionParser.CONFIGURABLE_ACTIONS]
     * 里的名字生效 —— 媒体键 / 音量 / 闹钟那些是上游定义的内建动作，用户配不了，
     * 也不该被这条路截走（上游的 dispatch 顺序是「built-in first, then configurable」）。
     */
    private fun launchUserMappedAction(rawAction: String): Boolean {
        if (!WatchTapActionParser.isConfigurable(rawAction)) return false
        val normalized = rawAction.trim().lowercase(Locale.ROOT)
        val intent = WatchTapActionMappings.of(context).launchIntent(context, normalized)
            ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun routeTapAction(action: WatchTapAction, rawAction: String = "") {
        if (action is WatchTapAction.PlaySound) {
            if (!ambientRequested) playFaceSound(action.name)
            return
        }
        if (action is WatchTapAction.Script) {
            val prepared = source
            val runtime = prepared?.luaRuntime
            val result = if (prepared == null || runtime == null) {
                Result.failure(IllegalStateException("face has no script runtime"))
            } else {
                runtime.runSnippet(action.source) { tag ->
                    resolveTagResolutionWithoutLua(tag, calendar, prepared)
                }
            }
            if (result.isSuccess) {
                prepared?.recycleBakedSegments()
                cancelTicks()
                scheduleTicks()
                requestFullRepaint()
            } else {
                Toast.makeText(context, "表盘脚本执行失败", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (action === WatchTapAction.HeartRate) {
            runCatching { dataProvider.requestRefresh() }
            requestFullRepaint()
            return
        }
        // 秒表按上游的归类在渲染层直接处理：改共享状态、立刻重绘，并按需要重排
        // tick 节奏 —— 跑起来之后 {swss}/{swsss} 需要亚秒刷新（见
        // WatchmakerTagResolver.requiresFastTicks 里加进去的那几个）。
        if (action === WatchTapAction.StopwatchStartStop ||
            action === WatchTapAction.StopwatchReset
        ) {
            if (action === WatchTapAction.StopwatchStartStop) {
                WatchStopwatch.toggle(SystemClock.elapsedRealtime())
            } else {
                WatchStopwatch.reset()
            }
            cancelTicks()
            scheduleTicks()
            requestFullRepaint()
            return
        }
        if (action is WatchTapAction.Media) {
            // **先活跃会话、后媒体键**（见 WatchMediaTransport 的注释）：手表上媒体键这条路
            // 经常断，而播放器一定实现 MediaSession 的传输控制。老师报「音乐播放没用」就是
            // 第一版只发媒体键的后果。
            val delivered = WatchMediaTransport.send(context, action.key)
            if (!delivered) {
                // 送不出去只有一个原因：没有任何应用在放。安静失败会被读成「这个动作不支持」，
                // 所以明确说出来。
                Toast.makeText(context, R.string.watch_face_action_no_media, Toast.LENGTH_SHORT).show()
                return
            }
            dataProvider.requestRefresh()
            requestFullRepaint()
            return
        }
        // 渲染器自己拥有的动作，不经宿主也不经 Intent：改 override + 整帧重绘就完事。
        if (action is WatchTapAction.ColorSwitch) {
            applyWatchColorSwitch(action.direction)
            return
        }
        if (action is WatchTapAction.Volume) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            runCatching {
                audio?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (action.raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    // 上游写的是「shows the volume UI」：不给 FLAG_SHOW_UI 的话表上完全没有反馈，
                    // 用户不知道自己调到哪儿了。
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            return
        }
        if (action is WatchTapAction.SetAlarm) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                // 刻意**不给** EXTRA_SKIP_UI：表盘上误触一下就静默建一个闹钟太惊悚，
                // 让时钟应用带着预填时间打开，用户按一下确认。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "没有可用的时钟应用", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (action is WatchTapAction.Named) {
            // 命名动作在上游要用户在设置里映射到具体应用，Flue 没有那张映射表，所以按名字的
            // **语义**去开系统默认应用 —— 音乐、拨号、相机这些都有标准 intent 或类目。
            // **用户映射优先**（设置 → 表盘点击动作）：上游就是让用户自己指的，
            // 猜系统默认只是没设置时的兜底。
            WatchTapActionMappings.of(context).launchIntent(context, action.value)?.let { mapped ->
                runCatching {
                    context.startActivity(mapped.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return
            }
            val intent = namedActionIntent(action.value)
            // **必须先问 PackageManager**：手表上 `CATEGORY_APP_*` 很多根本没人声明，
            // 直接 startActivity 会抛 ActivityNotFoundException，吞掉就变成「点了没反应」——
            // 老师报的「大量动作不可用」有一半是这个。查得到才打，查不到就说清楚是哪个动作。
            val resolvable = intent != null &&
                context.packageManager.resolveActivity(intent, 0) != null
            if (!resolvable) {
                Toast.makeText(
                    context,
                    context.getString(R.string.watch_face_action_unmapped_fmt, action.value),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.watch_face_action_unmapped_fmt, action.value),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        val intent = when (action) {
            WatchTapAction.Calendar -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            WatchTapAction.Weather -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_WEATHER)
            WatchTapAction.OpenSettings -> Intent(Settings.ACTION_SETTINGS)
            WatchTapAction.BatteryStatus -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            is WatchTapAction.DeepLink -> Intent(Intent.ACTION_VIEW, Uri.parse(action.uri))
            is WatchTapAction.Named -> null
            WatchTapAction.HeartRate -> null
            WatchTapAction.StopwatchStartStop -> null
            WatchTapAction.StopwatchReset -> null
            // 上面那句 `if (action is ColorSwitch)` 已经处理掉了，这里只是让 when 穷尽。
            is WatchTapAction.ColorSwitch -> null
            is WatchTapAction.Script -> null
            is WatchTapAction.PlaySound -> null
            is WatchTapAction.AppLaunch -> {
                val act = action.activityClass
                if (act != null) {
                    Intent(Intent.ACTION_MAIN).apply {
                        component = android.content.ComponentName(action.packageName, act)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    context.packageManager.getLaunchIntentForPackage(action.packageName)
                }
            }
            is WatchTapAction.Unsupported -> null
        }
        if (intent == null) {
            val reason = (action as? WatchTapAction.Unsupported)?.reason
                ?: context.getString(
                    R.string.watch_face_tap_missing_app_fmt,
                    (action as? WatchTapAction.AppLaunch)?.packageName.orEmpty(),
                )
            Toast.makeText(
                context,
                context.getString(R.string.watch_face_tap_unavailable_fmt, reason),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // **先问 PackageManager**：`CATEGORY_APP_WEATHER` 这类类目在手表上经常没人声明，
        // 直接 startActivity 会抛 ActivityNotFoundException，兜到下面那句就只剩一句
        // 「无法打开目标应用」，用户既不知道是哪个动作、也不知道该去哪儿配
        // （老师报的天气组件就是这条）。命名动作分支早就这么做了，这里补齐。
        if (context.packageManager.resolveActivity(intent, 0) == null) {
            Toast.makeText(
                context,
                context.getString(
                    R.string.watch_face_action_unmapped_fmt,
                    rawAction.ifBlank { action.javaClass.simpleName },
                ),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "无法打开目标应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 命名动作 → 系统默认应用的 intent。
     *
     * 只覆盖**有标准 intent 可用**的那些：音乐、浏览器、拨号、相机这一类系统都留了
     * `CATEGORY_APP_*` 或专用 action。`w_compass` / `w_keep` / `w_payment` / `socialmedia`
     * 之类没有通用入口，返回 null 让它安静收场（上游那边这些也要用户自己映射）。
     *
     * `tap_launcher` 也返回 null：表盘正显示在 Flue 自己的桌面里，再「打开启动器」
     * 没有意义，而这一层拿不到应用列表的导航入口。
     */
    private fun namedActionIntent(name: String): Intent? {
        fun appCategory(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)
        return when (name) {
            // 类目 → 当前持有媒体会话的应用 → 常见音乐包名，三级兜底。
            "w_music", "m_music" -> WatchMediaTransport.musicIntent(context)
            "browser" -> appCategory(Intent.CATEGORY_APP_BROWSER)
            "w_calculator", "m_calculator" -> appCategory(Intent.CATEGORY_APP_CALCULATOR)
            "w_contacts" -> appCategory(Intent.CATEGORY_APP_CONTACTS)
            "w_gallery" -> appCategory(Intent.CATEGORY_APP_GALLERY)
            "w_email", "m_email" -> appCategory(Intent.CATEGORY_APP_EMAIL)
            "w_messaging", "w_sms" -> appCategory(Intent.CATEGORY_APP_MESSAGING)
            "w_map", "w_maps" -> appCategory(Intent.CATEGORY_APP_MAPS)
            "w_calendar", "m_calendar", "w_agenda" -> appCategory(Intent.CATEGORY_APP_CALENDAR)
            "w_weather", "m_weather" -> appCategory(Intent.CATEGORY_APP_WEATHER)
            "w_dialer", "w_phone" -> Intent(Intent.ACTION_DIAL)
            "w_camera", "m_camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "w_recorder" -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            "w_clock", "m_clock", "w_alarm" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "w_timer" -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
            "w_settings" -> Intent(Settings.ACTION_SETTINGS)
            "voice_search" -> Intent(Intent.ACTION_VOICE_COMMAND)
            else -> null
        }
    }

    private fun normalizeAssetReference(raw: String): String? {
        if (raw.length > MAX_ASSET_REFERENCE_CHARS) return null
        val value = raw
            .substringBefore('`')
            .replace('\\', '/')
            .trim()
        if (value.isBlank() || value.indexOf('\u0000') >= 0 || WINDOWS_ABSOLUTE_PATH.matches(value)) {
            return null
        }
        return value.trimStart('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
            .lowercase(Locale.ROOT)
    }

    /** Returns a root-relative key, or null when [value] escapes the root. */
    private fun normalizeRelativeAsset(base: String, value: String): String? {
        val segments = ArrayDeque<String>()
        if (value.trim().replace('\\', '/').startsWith('/')) {
            // An explicit leading slash means archive-root-relative.
        } else {
            base.split('/').filter { it.isNotBlank() }.forEach(segments::addLast)
        }
        value.replace('\\', '/').split('/').forEach { segment ->
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(segment.lowercase(Locale.ROOT))
            }
        }
        return segments.joinToString("/").takeIf(String::isNotBlank)
    }

    private fun paintAlpha(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()

    /**
     * 图层 `color` 的染色滤镜。语义是**调制**（`结果 = 贴图 × 颜色`），不是整体替换。
     *
     * 对照 clox `wf_render.lua` 的 `bindTint`：它走 `sprite:setColor(c3b(hex))`，
     * 也就是 cocos2d 的顶点色调制 —— 片元 `rgb = texture.rgb × color.rgb`，
     * `alpha = texture.a`（`c3b` 不带 alpha）。贴图自带的明暗、渐变、纹理全部留着，
     * 颜色只是给它「乘一层色玻璃」。
     *
     * 之前用 [android.graphics.PorterDuff.Mode.SRC_IN] 是错的：那是拿滤镜色**整体
     * 替换** RGB、只留贴图 alpha，等于把贴图拍平成一块纯色剪影。对纯白图标看不出
     * 差别（白 × 色 == 色），但 wftools-2 转出来的 `wfs_adjustmentcolor_*.png`
     * 模板图**自带明暗**，312 有 28 个这样的图层染 `ebebe2`，SRC_IN 直接把顶部/底部
     * 那两块 448×133 的面板压成了均匀灰块 —— 就是老师圈出来的「灰蒙蒙」。
     *
     * [android.graphics.PorterDuff.Mode.MULTIPLY] 在 Skia 里是 `[Sa×Da, Sc×Dc]`，
     * 把滤镜色强制成不透明（`Sa = 1`）之后就退化成 `alpha 不变、rgb 逐通道相乘`，
     * 与上面的 cocos2d 公式逐位对齐。强制不透明这一步不能省：`resolveColor` 允许
     * 解析 8 位带 alpha 的色值，`Sa < 1` 会连带把贴图 alpha 也乘下去、图整体变淡；
     * clox 的 `c3b` 同样丢掉 alpha，这里保持一致。
     *
     * 白色仍走 `null` 快路径：白是乘法恒等元（×1.0），也正是 clox `"ffffff"` 默认值
     * 的含义，省掉一次逐像素滤镜。
     *
     * 调用点只有 [drawImage]（普通图片 / image_cond / GIF）。**文字不要用这个** ——
     * 文字的 `color` 是「字是什么颜色」，走 [textTintFilter]。
     */
    // 每帧每染色层 new 一个 PorterDuffColorFilter 是纯 GC 压力：一张表盘的
    // 染色色值就那么几个，按不透明色值缓存。绘制只在主线程，无需同步。
    private val multiplyTintFilters = HashMap<Int, android.graphics.PorterDuffColorFilter>()
    private val srcInTintFilters = HashMap<Int, android.graphics.PorterDuffColorFilter>()

    private fun cachedTintFilter(
        opaque: Int,
        mode: android.graphics.PorterDuff.Mode,
        cache: HashMap<Int, android.graphics.PorterDuffColorFilter>,
    ): android.graphics.PorterDuffColorFilter {
        cache[opaque]?.let { return it }
        val created = android.graphics.PorterDuffColorFilter(opaque, mode)
        if (cache.size >= TINT_FILTER_CACHE_LIMIT) cache.clear()
        cache[opaque] = created
        return created
    }

    private fun tintFilter(color: Int): android.graphics.ColorFilter? {
        val opaque = color or OPAQUE_ALPHA_MASK
        return if (opaque == Color.WHITE) {
            null
        } else {
            cachedTintFilter(opaque, android.graphics.PorterDuff.Mode.MULTIPLY, multiplyTintFilters)
        }
    }

    /**
     * 位图字体正文的染色滤镜。语义是**替换**（`结果 rgb = 颜色`，只留字形 alpha），
     * 与 [tintFilter] 的调制**故意不同**。
     *
     * 两者必须分开，因为 `color` 在两种图层上的含义不一样：
     * - 图片层：贴图是**画面**，`color` 是盖在上面的一层色玻璃 → 调制，保留明暗。
     * - 文字层：字形图集只是**蒙版**，`color` 就是「这些字是什么颜色」→ 替换。
     *   TTF 路径本来就是 `textPaint.color = ...`（纯替换），位图字体要与它同语义，
     *   否则同一个表盘换个字体类型颜色就变，作者无从下手。
     *
     * 实证：`小猫烧-动态` 的星期层写 `color="6ad8df"`（青），但它的位图图集
     * `fonts_bm/f1.png` 字形像素是**黄色 `f6fe00`**（作者随手拿黄底图切的）。
     * 调制算出来 `0xf6×0x6a, 0xfe×0xd8, 0x00×0xdf` = `66d700` —— 一片荒谬的绿，
     * 正是老师报的「渲染成 6ed90e」。而包内 `preview.jpg`（源 App 的真实渲染）
     * 在该区域取样为 `#69d8df`，即**作者写的色值原样出现**，图集的黄被整个丢弃。
     * clox 的 `lab:setColor(c3b(hex))` 是 cocos2d 顶点色调制，遇到这种非白图集
     * 会和调制一样跑偏；这里以作者预览图为准，不照抄。
     *
     * 白色仍走 `null`：图集若是作者精心画的彩色/渐变字（没写 `color`，
     * [resolveColor] 兜底给白），保持原样比拍成纯白正确。
     */
    private fun textTintFilter(color: Int): android.graphics.ColorFilter? {
        val opaque = color or OPAQUE_ALPHA_MASK
        return if (opaque == Color.WHITE) {
            null
        } else {
            cachedTintFilter(opaque, android.graphics.PorterDuff.Mode.SRC_IN, srcInTintFilters)
        }
    }

    private data class PreparedSource(
        val sourceGeneration: Long,
        val animationStartedAtUptimeMs: Long,
        val root: File,
        val rootCacheKey: String,
        val definitionRevision: String,
        val watchDirectoryKey: String,
        val definition: WatchmakerDefinition,
        val hasGyroParallax: Boolean,
        val exactFiles: Map<String, ResolvedAsset>,
        val uniqueBasenames: Map<String, ResolvedAsset?>,
        val galleryAssetPaths: List<String>,
        val revisionByAbsolutePath: Map<String, String>,
        val fontIndex: WatchFontIndex,
        val bitmapFonts: Map<String, PreparedBitmapFont?>,
        val expressionScope: WatchExpressionScope,
        val luaRuntime: WatchFaceLuaRuntime?,
        val ownsLuaRuntime: Boolean,
        val luaExpressionSources: Set<String> = emptySet(),
        /**
         * 导入期预编译的产物摘要。随 generation 一起换掉，所以不需要额外的失效逻辑。
         *
         * 阶段 2 的 tag 分桶直接读它的 `tagDependencies`，不必再扫一遍属性。
         */
        val expressionCompileReport: WatchExpressionCompileReport,
        val renderPlan: List<WatchLayerRenderItem>,
        val renderPlanDiagnostics: WatchLayerGraphDiagnostics,
        val svgDocuments: Map<String, PreparedWatchSvgDocument<Path>>,
        val needsFastTicks: Boolean,
        /**
         * 这张表盘的属性里出现过 `{alat}` / `{alon}` / `{aalt}` 一族（T-14）。
         *
         * 导入期算一次。位置权限的引导只对这种表盘弹 —— 以前唯一的申请点在「设置 →
         * 表盘天气」里，从没点过那一项的用户，坐标 tag 就永远没有数据源。
         */
        val usesLocationTags: Boolean = false,
    ) {
        /** Immutable per-layer values used by every visible frame. */
        val normalizedLayerTypes: List<String> = definition.layers.map { layer ->
            layer.type.lowercase(Locale.ROOT)
        }
        val layerCacheKeys: List<String> = definition.layers.mapIndexed { index, layer ->
            layerCacheKey(layer, index)
        }
        val reportedLuaFallbacks: MutableSet<String> = HashSet()

        fun layerCacheKey(layer: WatchmakerLayer, layerIndex: Int): String = buildString {
            append(rootCacheKey)
            append('\u0000')
            append(layer.id?.takeIf(String::isNotBlank) ?: layerIndex)
        }

        /**
         * 声明画布换算出的设计空间尺寸，构造时算一次。
         *
         * 根属性解析涉及字符串处理，放到 onDraw 里就是每帧一次；而安装目录在
         * 一个 PreparedSource 的生命周期内是不变的，缓存下来即可。
         * 方形声明（512²/466²/360²，以及不声明 width/height 的老表盘）算出来
         * 仍是 512×512，渲染路径与改动前逐像素一致。
         */
        val canvasSize = WatchCanvasSize.fromRootAttributes(definition.rootAttributes)
        val designExtentWidth: Float = canvasSize.designExtentWidth
        val designExtentHeight: Float = canvasSize.designExtentHeight

        /**
         * 阶段 2：慢桶 tag 的取值追踪器（对照 `wf_engine.lua` 的推模型）。
         *
         * Flue 是 Canvas 直绘，一次 `invalidate()` 就是整张重画，没法只推一个图层。
         * 所以它回答的是**这一拍到底要不要重绘**：一张最快依赖到分钟的表盘，
         * 改动前 1s 一拍每拍都全量重绘，改动后每分钟只重绘 1 次。
         *
         * `forceFastTicks` 直接沿用 [needsFastTicks]：它已经把 GIF、`anim_in`/
         * `anim_out`、毫秒级 tag 全算进去了（计划 §2.3 的非 tag 快依赖）。
         * 陀螺仪要看运行时开关，由 [requiresFastTicksNow] 在调用点单独并进来。
         */
        val slowTagTracker = WatchTagChangeTracker(
            tags = expressionCompileReport.referencedTags,
            forceFastTicks = needsFastTicks,
        )

        /**
         * 阶段 3：连续静态图层的烘焙段（对照 `wf_render.lua:3196` `flattenStatics`）。
         *
         * 构造时算一次。判定所需的一切都是不变量：图层属性、renderPlan 顺序、
         * tag 依赖都随 generation 一起换掉，所以不需要额外的失效逻辑。
         *
         * ⚠️ 这里**只算段的划分**，不建位图 —— 位图要等真正的画布尺寸，且必须在
         * 主线程的绘制路径上建（见 [bakedSegmentBitmaps]）。
         */
        val flattenSegments: List<WatchFlattenSegment> = run {
            val bakeable = ArrayList<Boolean>(renderPlan.size)
            // 与 [bakeable] 同序的每层慢依赖桶 —— 段的重烘判据由它们求并集得来。
            // ⚠️ 漏传过一次：`planSegments` 少给这个参数，段的 slowBuckets 全是空集，
            // 按桶回收因此永远匹配不上任何段，分钟跳变后烘焙段的像素永久冻住。
            val slowBuckets = ArrayList<Set<WatchTagBucket>>(renderPlan.size)
            for (item in renderPlan) {
                val layer = definition.layers.getOrNull(item.layerIndex)
                if (layer == null) {
                    bakeable += false
                    // ⚠️ 只能用 add：元素本身是 Set，`+=` 会被解析成
                    // `Collection.plus(Iterable)`（返回新 List 再赋回 val）而不是
                    // `plusAssign(element)`，直接编译不过。
                    slowBuckets.add(emptySet())
                    continue
                }
                val tags = LinkedHashSet<String>()
                // 依赖不明的属性（编译不过 / 查不到键）一律当不可烘：见
                // [WatchExpressionCompileReport.tagDependenciesOf] 里两种空集的区别。
                var dependenciesKnown = true
                for (value in watchmakerLayerAttributeValues(layer)) {
                    val dependencies = expressionCompileReport.tagDependenciesOf(value)
                    if (dependencies == null) {
                        dependenciesKnown = false
                        break
                    }
                    tags += dependencies
                }
                val profile = WatchLayerTagProfile.of(tags)
                bakeable += dependenciesKnown &&
                    WatchStaticFlatten.isBakeable(
                        layer = layer,
                        item = item,
                        profile = profile,
                        hasFaceScriptDependency = luaRuntime?.let { runtime ->
                            watchmakerLayerAttributeValues(layer).any(runtime::referencesFaceState)
                        } == true,
                    )
                slowBuckets.add(profile.slowBuckets)
            }
            // 视频分段边界：烘焙段跨过去会让两个 View 实例各烘一份重叠内容。
            val videoBoundaries = renderPlan.indices.filterTo(HashSet()) { index ->
                definition.layers.getOrNull(renderPlan[index].layerIndex)
                    ?.type?.trim()?.equals("video", ignoreCase = true) == true
            }
            WatchStaticFlatten.planSegments(bakeable, videoBoundaries, slowBuckets)
        }

        /**
         * 不可见图层也要暖在位图缓存里的清单（构造时算一次，见
         * [planOpacityGatedImageWarmup]）。由 [warmOpacityGatedImages] 消费。
         */
        val opacityGatedImageLayers: List<Int> = run {
            val layers = definition.layers
            val layerCount = layers.size
            planOpacityGatedImageWarmup(
                parentIndices = resolveWatchLayerParentIndices(layers),
                layerTypes = List(layerCount) { index -> layers[index].type },
                rawOpacityValues = List(layerCount) { index ->
                    layers[index].attributes["opacity"].orEmpty()
                },
                rawPathValues = List(layerCount) { index ->
                    layers[index].attributes["path"].orEmpty()
                },
            )
        }

        /**
         * [opacityGatedImageLayers] 的轮转游标：每帧只暖固定几层，下一帧从这里接着走。
         * 见 [warmOpacityGatedImages] 对配额的说明。
         */
        var opacityGatedWarmCursor: Int = 0

        /** Main-thread-only bounded memoization; the installed tree is immutable. */
        val assetResolutionCache = WatchStringLruCache<ResolvedAsset>(
            maximumEntries = MAX_ASSET_RESOLUTION_CACHE_ENTRIES,
            maximumKeyLength = MAX_ASSET_CACHE_KEY_CHARS,
        )
        val gifPathCache = WatchStringLruCache<List<String>>(
            maximumEntries = MAX_GIF_PATH_CACHE_ENTRIES,
            maximumKeyLength = MAX_WATCH_GIF_PATH_INPUT_CHARS,
        )
        val chartValuesCache = WatchStringLruCache<List<Float>>(
            maximumEntries = MAX_CHART_VALUE_CACHE_ENTRIES,
            maximumKeyLength = MAX_DYNAMIC_CACHE_KEY_CHARS,
        )
        val seriesCache: MutableMap<String, List<WatchSeriesItem>> = HashMap()
        val calendarCellsCache: MutableMap<String, List<WatchCalendarCell>> = HashMap()
        val calendarLabelsCache: MutableMap<String, List<String>> = HashMap()

        /**
         * 阶段 3 的烘焙产物：段起始下标 → 已合成的位图。
         *
         * 只在主线程的绘制路径上读写，所以用普通 HashMap。键是段的 `fromIndex`，
         * 与 [flattenSegments] 对齐。
         */
        val bakedSegmentBitmaps: MutableMap<Int, Bitmap> = HashMap()

        /** 烘焙时的画布尺寸。尺寸变了（旋屏、编辑器改画布）必须整批重烘。 */
        var bakedSegmentWidth: Int = 0
        var bakedSegmentHeight: Int = 0

        /**
         * 上一次烘焙尝试没能产出任何位图（超预算或分配失败）。
         *
         * 判据是「段表为空」而不是「有没有尝试过」：失败时 [bakedSegmentBitmaps] 为空，
         * 调用点若只看空不空就会**每帧**重试一次 Bitmap 分配 —— 正是烘焙想避免的开销。
         * 所以失败一次就记下来，等到下一次真正的失效（尺寸变化 / 慢桶 tag 变化 /
         * [recycleBakedSegments]）再重试。
         */
        var bakedSegmentsUnavailable: Boolean = false

        /**
         * 分帧烘焙**卡住**了：上一轮烘焙循环跑完整个段表，配额没到、预算还够，
         * 却一个位图都没产出（Bitmap 分配失败 / 段内逐层绘制抛异常）。
         *
         * 和 [bakedSegmentsUnavailable] 的区别在粒度：[bakedSegmentsUnavailable]
         * 只认「段表空不空」，分帧之后段表非空但缺位的中间态是常态，不能用它挡重试。
         * 这个旗标挡的是更窄的坏情况：**部分**烘焙永远补不完 —— 比如内存压力下
         * `Bitmap.createBitmap` 每帧都失败，而调用点的 `size < affordableSegments`
         * 判据一直为真，于是每帧都进来重试一次分配。记下「这轮没产出」就停手，
         * 等下一次真正的失效（[recycleBakedSegments]）再给机会。
         */
        var bakedSegmentBakeStalled: Boolean = false

        /**
         * 烘焙时素材还没就绪的那些段（键同 [bakedSegmentBitmaps]，段的 `fromIndex`）。
         *
         * 素材是异步解码的：一个段烘的时候图可能还在路上，烘出来就缺一块，而慢桶
         * tag 又没变 → 空洞永远补不上。所以素材落地后要重烘 —— 但**只重烘这些段**。
         *
         * 原来是任何素材落地就 [recycleBakedSegments] 整批回收。312 有 300+ 图层
         * 在异步解码，一波持续两三秒，每次落地把 16 段全作废，每帧配额只把白烧封顶
         * 成 2 段 × 120ms，永远收敛不了 —— 正是老师报的「紧跟输入的一串尖峰」。
         * 按段之后，素材落地只影响真的缺它的那一两段，且重烘时缓存已命中，集合
         * 自然收敛到空。
         */
        val segmentsAwaitingAssets: MutableSet<Int> = HashSet()

        /**
         * 丢掉全部烘焙位图。
         *
         * 慢桶 tag 变化、画布尺寸变化、走全量重绘入口都要调用它 —— 段内图层虽然
         * 不含快依赖，但分钟跳、天气变、压暗翻转都会改变它们的绘制结果。
         */
        fun recycleBakedSegments() {
            // 不 recycle()：硬件 display list 在 onDraw 返回后可能仍引用这些位图，
            // 丢掉引用让运行时在那一帧退役后自行回收（与过渡帧同一套契约）。
            bakedSegmentBitmaps.clear()
            bakedSegmentWidth = 0
            bakedSegmentHeight = 0
            // 作废等于「重新给一次机会」：尺寸可能变小了、内存可能腾出来了。
            bakedSegmentsUnavailable = false
            bakedSegmentBakeStalled = false
            // 全部段都要重烘，「缺素材」的旧结论一并作废：重烘时会重新登记。
            segmentsAwaitingAssets.clear()
        }

        /**
         * 只作废**上一次烘焙时缺素材**的那几段，并清空登记。
         *
         * 清空是安全的：素材落地才会调到这里，这些段重烘时缓存已命中；万一还缺
         * 别的素材，重烘会把它重新登记进来，等那个素材落地再作废一次。所以集合
         * 会随着解码波推进单调收敛到空，而不是每次落地都全量回收。
         */
        fun recycleSegmentsAwaitingAssets(): Boolean {
            if (segmentsAwaitingAssets.isEmpty()) return false
            var removedAny = false
            for (fromIndex in segmentsAwaitingAssets) {
                if (bakedSegmentBitmaps.remove(fromIndex) != null) removedAny = true
            }
            segmentsAwaitingAssets.clear()
            if (removedAny) {
                bakedSegmentsUnavailable = false
                bakedSegmentBakeStalled = false
            }
            return removedAny
        }

        /**
         * 只作废**慢桶命中**的那几段，其余段的位图原样留用。
         *
         * 这是「还是卡」的正解。原来任何慢桶变化（分钟跳变最常见）都走
         * [recycleBakedSegments] 整批回收，312 的 16 段全部重建 —— 单段离屏
         * 栅格化就要上百毫秒，摊到每帧 2 段也要连卡 8 帧。而一次分钟跳变实际
         * 只碰得到含分钟 tag 的那一两段，其余十几段的像素一个都没变。
         *
         * ⚠️ 不动 [bakedSegmentWidth] / [bakedSegmentHeight]：尺寸没变，留着的段
         * 仍然有效，清零会让下一帧的 `sizeChanged` 误判成真而整批回收，等于白改。
         */
        fun recycleBakedSegmentsForBuckets(changedBuckets: Set<WatchTagBucket>) {
            if (changedBuckets.isEmpty()) return
            var removedAny = false
            for (segment in flattenSegments) {
                if (segment.slowBuckets.none(changedBuckets::contains)) continue
                if (bakedSegmentBitmaps.remove(segment.fromIndex) != null) removedAny = true
                // 这一段要重烘了，「上次烘时缺素材」的旧结论作废 —— 重烘会重新登记。
                segmentsAwaitingAssets.remove(segment.fromIndex)
            }
            // 有段被腾空 → 给烘焙路径重新开一次门。两个旗标都是「上一轮没产出」的
            // 记录，段表变了就不再适用。
            if (removedAny) {
                bakedSegmentsUnavailable = false
                bakedSegmentBakeStalled = false
            }
        }

        fun clearRuntimeCaches() {
            assetResolutionCache.clear()
            gifPathCache.clear()
            chartValuesCache.clear()
            seriesCache.clear()
            calendarCellsCache.clear()
            calendarLabelsCache.clear()
            recycleBakedSegments()
            if (ownsLuaRuntime) luaRuntime?.close()
        }
    }

    private data class ResolvedAsset(
        val key: String,
        val file: File,
        val cacheRevision: String,
    )

    private data class PreparedBitmapFont(
        val file: File,
        val relativeDirectory: String,
        val definition: WatchBitmapFont,
        val pageAssetPaths: List<String>,
        val pageBitmaps: Array<Bitmap?> = arrayOfNulls(pageAssetPaths.size),
    )

    private data class TapTarget(
        val bounds: RectF,
        val action: WatchTapAction,
        /**
         * 图层上写的原始动作名（未经解析），例如 `widget_weather`。
         *
         * 解析器会把可配置名字短路成强类型动作，名字丢了就查不了用户映射表 ——
         * 见 [launchUserMappedAction]。
         */
        val rawAction: String = "",
        /**
         * 求值后的 `opacity` 属性值（0–100 的原始刻度，**不是**最终 alpha）。
         *
         * 上游只把 `opacity` 恰为 `-1` 当成「这块热区不参与命中」（`wf_render.lua:2869-2877`
         * 的 `tapEligible`）。`opacity=0` 仍然要参与 —— WM 生态里大量表盘用全透明图层
         * 当隐形按钮，那是刻意的写法，不是失误。
         */
        val opacity: Float = 100f,
    )

    /** 已求值的描边参数（mode 一定不是 NONE，size/opacity 一定大于 0）。 */
    private data class ResolvedOutline(
        val mode: WatchOutlineMode,
        val size: Float,
        val color: Int,
        val opacity: Float,
    )

    /**
     * 求值好的阴影参数。[copies] 已经是最终份数（Drop=1、Flat=floor(w_distance)），
     * 渲染路径只按 1..copies 循环取 [WatchmakerRuntimeContractsKt] 的偏移即可。
     */
    private data class ResolvedShadow(
        val mode: WatchShadowMode,
        val distance: Float,
        val angleDegrees: Float,
        val color: Int,
        val opacity: Float,
        val copies: Int,
    )

    private data class BitmapFailure(val generation: Long, val failedAtUptimeMs: Long)

    private enum class AssetLoadKind {
        BITMAP,
        MOVIE,
        TYPEFACE,
    }

    private data class AssetLoadClaim(
        val kind: AssetLoadKind,
        val key: String,
        val generation: Long,
        /**
         * 这次解码落地后是否需要作废烘焙产物（见 [finishAssetLoad]）。
         *
         * 帧序列 / GIF 帧恒为 false：[WatchStaticFlatten.isBakeable] 对
         * [watchLayerRequiresFrameTicks] 的图层一律返回 false，所以这些素材**不可能**
         * 出现在任何烘焙段里，重烘对它们毫无意义，只是白烧主线程。
         */
        val affectsFlatten: Boolean = true,
    )

    companion object {
        private const val TAG = "WatchmakerFace"
        private const val DESIGN_SIZE = WatchmakerGeometry.DESIGN_SIZE

        /**
         * 命中仲裁的容半径，单位是**设计空间**（512×512）。
         *
         * 抄上游 `wf_render.lua:2878-2903` 的 `dist < 50`：框外 50 单位内的热区也算候选，
         * 免得小按钮在手表上根本按不着。派发时要乘 `frameCanvasScale` 换到像素。
         */
        private const val WATCH_TAP_GRACE_DESIGN_UNITS = 50f

        /**
         * `color_switch_*` 的配色索引存这里，键是 `PreparedSource.rootCacheKey`（一张
         * 表盘一档）。上游把索引存在脚本变量 `__color_idx` 里跟着存档走，Flue 没有脚本
         * 变量的持久层，所以落到偏好文件。
         */
        private const val WATCH_COLOR_SWITCH_PREFS = "flue_watch_color_switch"

        /**
         * 同时在飞的素材解码上限。
         *
         * 4 太紧：帧序列一层就要「当前帧 + 预取 2 帧」= 3 个额度
         * （见 [frameSequencePrefetchIndices]），剩 1 个要分给位图字体图集、静态图、
         * 视频首帧。额度抢不到时 [requestBitmap] 直接返回上一帧、**不排队也不重试**，
         * 要等下一拍再碰运气 —— 帧序列于是一直追不上时钟，表现成「拨两下停一下」。
         *
         * 8 是按「2 个帧序列图层各 3 + 图集/静态图 2」算的。解码跑在 [assetScope]
         * 的 IO 线程池上，主线程只承担 `withContext(Main)` 里的入缓存，所以放宽的是
         * 后台并行度，不是主线程负载；真正的内存护栏仍是各 LRU 的字节预算。
         */
        private const val MAX_CONCURRENT_ASSET_LOADS = 8

        /**
         * 一帧最多预暖几个 opacity 门控图片层（见 [warmOpacityGatedImages]）。
         *
         * 2 是照 [MAX_CONCURRENT_ASSET_LOADS] 的账反推的：那 8 个额度先要留给**本帧
         * 真正在画**的层（帧序列一层就吃 3 个），预暖只能拿边角料。312 有 79 个门控层，
         * 按 2 轮转一圈是 40 帧、约 1.3 秒 —— 而门控表达式最快的也是 `{ds}` 的 4 秒一档，
         * 一圈跑完远早于下一次翻真，够用。
         */
        private const val MAX_OPACITY_GATED_WARM_PER_FRAME = 2

        /**
         * 阶段 3 烘焙位图的边长上限。超过就不烘 —— 一张 4096² ARGB 是 64MB，
         * 省下的那点遍历换不回来。
         */
        private const val MAX_FLATTEN_BITMAP_SIZE = 2_048

        /** 单张烘焙位图的字节上限（约一张 1024² ARGB_8888）。 */
        private const val MAX_FLATTEN_SEGMENT_BYTES = 4L * 1024L * 1024L

        /**
         * 全部烘焙位图的字节上限。
         *
         * 这是本视图的第四个位图缓存（另三个是 bitmapCache / animatedBitmapCache /
         * 过渡帧），必须记账 —— 不记账的缓存正是当初 wf_528 卡顿的根因。
         */
        private const val MAX_FLATTEN_TOTAL_BYTES = 16L * 1024L * 1024L

        /**
         * 一帧最多重烘几个段（见 [bakeFlattenSegments]）。
         *
         * 312 有 16 个段，一次失效原来在同一帧里全部重建 —— 老师报的「滑下进控制中心
         * 大卡一下」就是这一下。摊成每帧 2 段后，最坏单帧代价降到 1/8，16 段在 30fps
         * 下约 0.27 秒补齐；补齐之前缺位的段走逐层直绘，只是没享受到加速，不缺内容。
         *
         * 不设成 1：首次进入表盘时全部段都是缺的，配额太小会让「完全没烘焙」的状态
         * 持续得太久，反而拖长了启动阶段的逐层直绘。
         */
        private const val MAX_SEGMENT_BAKES_PER_FRAME = 2
        /** Keep synchronous offscreen work below a small slice of the frame budget. */
        private const val MAX_FLATTEN_BAKE_BUDGET_NANOS = 2_000_000L
        /** Two VSYNCs let the outgoing HWUI display list retire before recycle. */
        private const val BITMAP_RETIREMENT_DELAY_MS = 48L

        /**
         * 图片缓存的条数上限。
         *
         * 这里必须能装下**整帧的工作集**，否则就是逐帧抖动：LRU 装不下时，画到
         * 后面的图层把前面的踢掉并 `recycle()`，下一帧从头再来，于是每帧都在
         * 重解码好几张图。日志里同一条加载线程上 512² 与 1042² 交替出现十几次，
         * 就是 24 条时的实况 —— 而 377 层的表盘光是不同素材就远不止 24 张。
         *
         * 真正的护栏是 [MAX_BITMAP_CACHE_BYTES]：条数放宽只让小图都留得住，
         * 大图仍然被字节预算挡着，不会把内存吃穿。
         */
        private const val MAX_BITMAP_CACHE_ENTRIES = 128
        private const val MAX_BITMAP_CACHE_BYTES = 24L * 1024L * 1024L
        /** 染色滤镜缓存上限；超过按整表清空（一张表盘的色值远到不了这个量）。 */
        private const val TINT_FILTER_CACHE_LIMIT = 64
        /** 渐变小缓存（环/进度条/进度环）的槽数；一张表盘的渐变层就那么几个。 */
        private const val SMALL_GRADIENT_CACHE_SIZE = 8
        /** 文字布局缓存上限；超过按整表清空（一张表盘的文字层远到不了这个量）。 */
        private const val TEXT_LAYOUT_CACHE_LIMIT = 128
        /**
         * 同一 tick 内 tick 判定与绘制共用一份 [WatchDataSnapshot] 的时间窗
         * （毫秒）。取值以覆盖 33ms tick→draw 间隔为准、不跨 tick：跨 tick
         * 复用会让传感器类字段的取值时刻后移，重力景深的低通滤波手感会变。
         */
        private const val SNAPSHOT_REUSE_WINDOW_MS = 40L
        /**
         * 帧序列缓存的预算（见 [putAnimatedBitmapLocked]）。
         *
         * 原值是 3 条 / 8MB，注释写的是「小而短命的 LRU，免得长帧序列把静态
         * 素材冲掉」—— 隔离的方向没错，额度定反了。帧序列不是用一次就扔的
         * 过客，它每帧换一张、而且会循环回来；3 条 8MB 意味着一张 1024²
         * （4.2MB）进来就把上一张挤掉，于是**每帧都必然未命中**，一路重解到
         * 掉帧。老师报的「所有背景会变换的表盘都巨卡、以前的版本从来不会」
         * 就是它：`framePaths.size > 1` 的图层全部走这条分支，单张背景的表盘
         * 走 [bitmapCache]，所以只有前者卡。
         *
         * 保底条数按「3 个帧序列图层 × (当前帧 + 预取帧)」取 6，即便每张都是
         * 4.2MB 的极端尺寸也不会退化成乒乓；上限 48 条让小尺寸序列（几十 KB
         * 的动画图标）整条都留得住，真正的护栏仍是字节预算。
         */
        private const val MAX_ANIMATED_BITMAP_CACHE_ENTRIES = 48
        private const val MAX_ANIMATED_BITMAP_CACHE_BYTES = 24L * 1024L * 1024L
        private const val MIN_ANIMATED_BITMAP_CACHE_ENTRIES = 6

        /**
         * 位图字体图集的独立预算（见 [fontAtlasCache]）。
         *
         * 一些数字表盘会为时、分、日期和电量分别打包图集，活动工作集可达到 6 张
         * 以上；条目上限必须容纳整套工作集，否则即使总字节很小也会按绘制顺序循环
         * 驱逐并重解。字节上限仍保留 24MB，避免病态素材无限占用内存。
         */
        private const val MAX_MOVIE_CACHE_ENTRIES = 4

        /**
         * [lastDrawnFrameByLayer] 的条数上限。
         *
         * 原值 8 是按「同时有几个 GIF 图层已属极端」估的，但 [rememberLastDrawnFrame]
         * 其实对**每一个画成功的图片层**都记一条，不只帧序列。wf528 有 23 个图片层，
         * 8 条装不下一轮 opacity 门控轮换（6 张）加上常驻层 —— 兜底图刚写进去就被
         * 后面的层挤掉，真需要兜底那一拍反而是空的，这条通路等于形同虚设。24 条能
         * 完整覆盖 wf528，312 的 78 层也能覆盖住同屏那批。
         *
         * 这是**条数**上限而非字节上限，看着比各 LRU 松，但实际占用不是叠加的：
         * 绝大多数条目与 [bitmapCache] 指向同一张位图（画成功那一拍写进来的就是
         * 缓存里那张），只有被 LRU 淘汰过的才真正额外占内存，而它们下一次该层绘制
         * 就会被替换、由 [recycleDetachedLastFrame] 释放。
         */
        private const val MAX_LAST_FRAME_ENTRIES = 24
        private const val MAX_LAST_MOVIE_ENTRIES = 4
        private const val MAX_TYPEFACE_CACHE_ENTRIES = 16
        private const val MAX_BITMAP_FAILURE_ENTRIES = 64
        private const val MAX_MOVIE_FAILURE_ENTRIES = 16
        private const val MAX_ASSET_RESOLUTION_CACHE_ENTRIES = 128
        private const val MAX_GIF_PATH_CACHE_ENTRIES = 32
        private const val MAX_CHART_VALUE_CACHE_ENTRIES = 16
        private const val MAX_DYNAMIC_CACHE_KEY_CHARS = 4 * 1024
        private const val MAX_WATCH_GIF_PATH_INPUT_CHARS = 256 * 1024
        private const val MAX_ASSET_CACHE_KEY_CHARS = 1_024
        private const val MAX_ASSET_REFERENCE_CHARS = 1_000
        private const val MAX_CHART_REFERENCE_LINES = 16
        private const val MAX_SERIES_CACHE_ENTRIES = 32
        private const val MAX_CALENDAR_CACHE_ENTRIES = 8
        private const val MAX_DECODE_DIMENSION_PX = 1_024
        /** High-resolution sprite sheets keep per-cell detail, with an OOM guard. */
        private const val MAX_SPRITE_SHEET_DECODE_DIMENSION_PX = 4_096

        /**
         * 编辑器降档的下限。表盘设计画布是 512 见方，低于这个值就等于在
         * 原分辨率之下重采样，缩放到屏上必然肉眼可见地糊。
         */
        private const val MIN_EDITOR_DECODE_DIMENSION_PX = 512
        private const val MAX_EDITOR_DECODE_DIMENSION_PX = 768

        /**
         * 位图字体图集的解码上限，独立于 [MAX_DECODE_DIMENSION_PX] 与编辑器降档。
         *
         * 图集降采样一次，图里每个字形的分辨率就同比减半，而字形本身只有几十
         * 像素——放大到表盘上就是肉眼可见的模糊。2048 覆盖绝大多数表盘的图集
         * 原尺寸（等于不降采样），同时仍然给病态素材留一道 OOM 护栏。
         */
        private const val MAX_FONT_ATLAS_DECODE_DIMENSION_PX = 2_048
        private const val MAX_SVG_BYTES = MAX_WATCH_SVG_BYTES
        private const val MAX_SVG_ASSET_COUNT = 64
        private const val MAX_SVG_TOTAL_BYTES = 8L * 1024L * 1024L
        private const val MAX_INITIAL_SCRIPT_BYTES = 256L * 1024L
        private const val MAX_BMFONT_DEFINITION_BYTES = 1L * 1024L * 1024L
        private const val BITMAP_FAILURE_RETRY_MS = 30_000L
        private const val DEFAULT_MOVIE_DURATION_MS = 1_000
        private const val SOURCE_TRANSITION_MAX_HOLD_MS = 8_000L

        /** 强制色值不透明用的 alpha 位掩码；见 [tintFilter] 为什么必须补满 alpha。 */
        private const val OPAQUE_ALPHA_MASK = 0xFF000000.toInt()

        /**
         * 环端帽倒角的比较容差（画布单位）。
         *
         * 用来判「倒角已经到顶」与「倒角等于 0」两个端点。不给容差的话，作者写
         * `radius` 恰好等于环厚一半时会因为浮点误差走进手绘那条慢路，画出的东西和
         * [android.graphics.Paint.Cap.ROUND] 只差不到一个像素，白付两次 drawPath。
         */
        private const val RING_CAP_EPSILON = 0.01f
        private val WATCH_GALLERY_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[a-zA-Z]:[/\\\\].*")
        private val TEXT_RING_LABELS = (1..12).map(Int::toString)

        /**
         * 位图字体描边的 8 个方向单位向量（dx, dy 成对）。对角线按 1/√2
         * 归一化，保证 8 个副本落在同一半径的圆上。
         */
        private const val OUTLINE_DIAGONAL = 0.70710678f
        private val BITMAP_OUTLINE_DIRECTIONS = floatArrayOf(
            -1f, 0f,
            1f, 0f,
            0f, -1f,
            0f, 1f,
            -OUTLINE_DIAGONAL, -OUTLINE_DIAGONAL,
            OUTLINE_DIAGONAL, -OUTLINE_DIAGONAL,
            -OUTLINE_DIAGONAL, OUTLINE_DIAGONAL,
            OUTLINE_DIAGONAL, OUTLINE_DIAGONAL,
        )
        // ICU (Android 14+ 的 java.util.regex 后端) 要求 '}' 必须转义，且字符组内的
        // '{' 会被当成 UnicodeSet 的多字符串起始符。裸写 "\\{[^{}]+}" 在 SDK 34+ 上
        // 抛 PatternSyntaxException，并因为它是 companion object 的字段而升级成
        // ExceptionInInitializerError —— 整个表盘视图类无法初始化。
        private val DYNAMIC_TAG_PATTERN = Regex("\\{[^\\{\\}]+\\}")
    }
}

@Composable
fun WatchmakerWatchFaceHost(
    descriptor: WatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLongPress: (() -> Unit)?,
    onDoubleTap: (() -> Unit)?,
    onLoadFailure: (WatchFaceDescriptor, Throwable) -> Unit,
    dataProvider: WatchDataProvider? = null,
    /** Null selects [WatchWeatherSources.default]; callers may inject a deterministic source. */
    weatherSource: WatchWeatherSource? = null,
    onTapAction: ((WatchTapAction) -> Unit)? = null,
    /** 渲染静默（画面完整）时回调一次，桌面据此撤「表盘加载中」。 */
    onRenderReady: ((Int) -> Unit)? = null,
    canvasSettings: WatchCanvasSettings = WatchCanvasSettings.DEFAULT,
    tapHapticsEnabled: Boolean = true,
    gravityParallaxEnabled: Boolean = false,
    /** 重力景深底部模糊背景层；关掉后底下只剩根 `bg_color`（对齐 clox）。 */
    gravityBackdropBlurEnabled: Boolean = true,
    textEntryAnimationEnabled: Boolean = true,
    textExitAnimationEnabled: Boolean = true,
    textExitAnimationActive: Boolean = false,
    ambientMode: Boolean = false,
    overlayTransitionActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ownedDataProvider = rememberWatchmakerWatchDataProvider(
        active = dataProvider == null && isFaceVisible,
        weatherSource = weatherSource,
        ambientMode = ambientMode,
    )
    val resolvedDataProvider = dataProvider ?: ownedDataProvider
    // 圆屏判定只能来自这里（用户可在设置里覆盖系统上报值），且必须在
    // composable 体内读 —— update 块不是 composable 作用域。
    val roundScreen = isFlueScreenRound()
    AndroidView(
        factory = { context -> WatchmakerWatchFaceStageView(context) },
        modifier = modifier.fillMaxSize(),
        update = { stage ->
            val view = stage.renderer
            view.setWatchDataProvider(resolvedDataProvider)
            view.setTapHapticsEnabled(tapHapticsEnabled)
            view.setCanvasSettings(canvasSettings)
            view.setGravityParallaxEnabled(gravityParallaxEnabled)
            view.setGravityBackdropBlurEnabled(gravityBackdropBlurEnabled)
            view.bind(
                descriptor = descriptor,
                refreshToken = refreshToken,
                isFaceVisible = isFaceVisible,
                onLongPress = onLongPress,
                onDoubleTap = onDoubleTap,
                onLoadFailure = onLoadFailure,
                onTapAction = onTapAction,
                // 素材就绪才知道有几个可播 video、路径各是什么，此时才能分段。
                onSourceReady = { stage.syncVideoSegments() },
                onRenderReady = onRenderReady,
                textEntryAnimationEnabled = textEntryAnimationEnabled,
                textExitAnimationEnabled = textExitAnimationEnabled,
            )
            stage.setAmbientMode(ambientMode)
            stage.setOverlayTransitionActive(overlayTransitionActive)
            stage.setRoundClip(roundScreen)
        },
        onRelease = WatchmakerWatchFaceStageView::dispose,
    )
}
