package com.flue.launcher.watchface.standard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.min

internal fun shouldRouteStageTouchToPrimary(videoLayerCount: Int): Boolean =
    videoLayerCount > 0

internal fun shouldRefreshBorrowedSegmentState(
    appliedSignature: String?,
    nextSignature: String,
    videoLayerCount: Int,
): Boolean = videoLayerCount > 0 && appliedSignature == nextSignature

/**
 * `video` 图层的合成宿主。
 *
 * 视频是独立的 SurfaceTexture，像素永远进不了 [WatchmakerWatchFaceView] 的
 * Canvas，所以带 video 的表盘只能靠 View 层级的 z-order 来还原图层顺序：把
 * renderPlan 按 video 的位置切成若干段，Canvas 段与 TextureView 交替叠放。
 *
 * ```
 * WatchmakerWatchFaceStageView
 * ├── WatchmakerWatchFaceView(段 0)      只画 renderPlan[0, i0)，画背景
 * ├── WatchFaceVideoLayerView(i0)
 * ├── WatchmakerWatchFaceView(段 1)      只画 (i0, i1)，不画背景
 * └── ...                                 N 个 video → N+1 段
 * ```
 *
 * 没有 video 的表盘退化成单段，与改造前完全一致 —— 这是绝大多数表盘的路径，
 * 不能为了少数带视频的表盘让它多付出任何代价。
 */
internal class WatchmakerWatchFaceStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /**
     * 首段。它同时是整个 Stage 的对外代理：手势、数据源、绑定状态都由它持有，
     * 其余段只是被动的补画层。
     */
    private val primaryView = WatchmakerWatchFaceView(context).also(::addView)
    private val extraViews = ArrayList<WatchmakerWatchFaceView>(2)
    private val videoViews = ArrayList<WatchFaceVideoLayerView>(2)
    /** 与 [videoViews] 同序的图层文档序号，供逐帧几何查询。 */
    private val videoLayerIndices = ArrayList<Int>(2)

    private var appliedSignature: String? = null
    private var playing = true
    private val clipPath = Path()
    private var roundClip = false
    /**
     * 切表盘过渡期间为 true。TextureView 是独立 Surface，会穿透画在 Canvas
     * 最上层的过渡帧，所以这期间必须把视频面整个藏掉。
     */
    private var transitionOverlayVisible = false

    init {
        // 分段只在有 video 时发生，普通表盘一层都不多。
        setWillNotDraw(false)
        primaryView.setEditorVideoPlaybackListener(::onPlaybackChanged)
        primaryView.setFrameGeometryListener(::updateVideoGeometry)
        primaryView.setTransitionOverlayListener(::onTransitionOverlayChanged)
    }

    /** 对外暴露首段，让既有调用方继续拿到熟悉的 View 类型。 */
    val renderer: WatchmakerWatchFaceView get() = primaryView

    private fun onPlaybackChanged(shouldPlay: Boolean) {
        playing = shouldPlay
        videoViews.forEach { it.setPlaying(shouldPlay) }
    }

    private fun onTransitionOverlayChanged(overlayVisible: Boolean) {
        if (transitionOverlayVisible == overlayVisible) return
        transitionOverlayVisible = overlayVisible
        if (overlayVisible) {
            // 只隐藏、不 release：过渡通常只有几百毫秒，重建 MediaPlayer
            // 比让它空转贵得多。
            videoViews.forEach { it.applyGeometry(IDENTITY, 0f, visible = false) }
        } else {
            updateVideoGeometry()
        }
    }

    /**
     * 重新计算分段。绑定完成、素材就绪后调用；结构没变时是 no-op。
     *
     * 分段结构由「哪些 planIndex 是可播放 video」唯一决定，所以用它做签名，
     * 避免每帧都拆装 View 树。
     */
    fun syncVideoSegments() {
        val entries = primaryView.videoLayerPlanEntries()
        val signature = entries.joinToString("|") { "${it.planIndex}:${it.absolutePath}" }
        if (shouldRefreshBorrowedSegmentState(appliedSignature, signature, entries.size)) {
            // A face reload can keep the same video path/signature while its
            // Lua VM, data provider, or segment range has changed. Re-adopt the
            // primary state before updating geometry so extra Canvas segments
            // never keep a closed/stale VM.
            syncSegmentState()
            updateVideoGeometry()
            return
        }
        appliedSignature = signature

        if (entries.isEmpty()) {
            teardownSegments()
            return
        }

        // 同屏视频数上限：每个 video 都是一个 MediaPlayer + 一个 Surface。
        // 超出上限的 video 图层不开播放器，但**必须仍然留在 Canvas 段的区间内**
        // —— 它们会被 drawStaticMediaFallback 画成静态首帧。曾经把它们从区间里
        // 跳过，结果第 3 个视频图层整层消失。
        val playable = entries.take(MAX_CONCURRENT_VIDEO_LAYERS)

        while (extraViews.size < playable.size) {
            extraViews += WatchmakerWatchFaceView(context)
        }
        while (videoViews.size < playable.size) {
            videoViews += WatchFaceVideoLayerView(context)
        }
        // 多出来的段先摘掉再复用，避免上一张表盘的层数残留。
        while (extraViews.size > playable.size) {
            extraViews.removeAt(extraViews.lastIndex).also { removeView(it); it.dispose() }
        }
        while (videoViews.size > playable.size) {
            videoViews.removeAt(videoViews.lastIndex).also { removeView(it); it.release() }
        }

        removeAllViews()
        addView(primaryView)
        primaryView.setRenderSegment(0 until playable.first().planIndex, drawBackground = true)

        videoLayerIndices.clear()
        playable.forEachIndexed { index, entry ->
            val videoView = videoViews[index]
            videoView.bindSource(entry.absolutePath)
            videoView.setPlaying(playing)
            addView(videoView)
            videoLayerIndices += entry.layerIndex

            val segment = extraViews[index]
            val from = entry.planIndex + 1
            // 末段一路画到 renderPlan 结尾，把超限的 video 图层也包含进去。
            val to = playable.getOrNull(index + 1)?.planIndex ?: Int.MAX_VALUE
            // 后续段一律不画背景：不透明的 bg_color 会把下面的 TextureView 糊死。
            segment.setRenderSegment(from until to, drawBackground = false)
            addView(segment)
        }
        syncSegmentState()
        updateVideoGeometry()
    }

    /**
     * 把首段的绑定状态复制给补画段。
     *
     * 补画段不参与手势也不需要回调，只要素材与数据源一致就能画出正确像素。
     */
    private fun syncSegmentState() {
        extraViews.take(videoViews.size).forEach { segment ->
            segment.adoptRenderStateFrom(primaryView)
        }
    }

    /**
     * 逐帧把最新几何下发给每个 TextureView。
     *
     * 只读缓存下来的 layerIndex，绝不在这里重新调 [videoLayerPlanEntries] ——
     * 那里面有素材路径解析与文件系统查找，逐帧跑会把 30fps 直接吃掉。
     */
    fun updateVideoGeometry() {
        // 过渡帧盖在最上面时视频必须保持隐藏，否则每帧几何更新会立刻把它
        // 重新显示出来，从过渡动画里穿透露出。
        if (transitionOverlayVisible) return
        for (index in videoViews.indices) {
            val videoView = videoViews[index]
            val layerIndex = videoLayerIndices.getOrNull(index) ?: continue
            val geometry = primaryView.videoLayerGeometry(layerIndex)
            if (geometry == null) {
                videoView.applyGeometry(IDENTITY, 0f, visible = false)
            } else {
                videoView.applyGeometry(geometry.matrix, geometry.alpha, visible = true)
            }
        }
    }

    private fun teardownSegments() {
        extraViews.forEach { removeView(it); it.dispose() }
        extraViews.clear()
        videoViews.forEach { removeView(it); it.release() }
        videoViews.clear()
        videoLayerIndices.clear()
        primaryView.setRenderSegment(null, drawBackground = true)
        if (indexOfChild(primaryView) < 0) addView(primaryView)
    }

    /** 圆屏裁剪。TextureView 是独立 Surface，不吃 Canvas 段自己的 clipPath。 */
    fun setRoundClip(round: Boolean) {
        if (roundClip == round) return
        roundClip = round
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 用 clipPath 而不是 clipToOutline：后者没有抗锯齿，圆边会有硬齿，
        // 与 Canvas 段自己画的内切圆对不上。
        if (roundClip && videoViews.isNotEmpty() && width > 0 && height > 0) {
            val saveCount = canvas.save()
            clipPath.reset()
            clipPath.addCircle(
                width / 2f,
                height / 2f,
                min(width, height) / 2f,
                Path.Direction.CW,
            )
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (shouldRouteStageTouchToPrimary(videoViews.size)) {
            // TextureView sits above the primary renderer. FrameLayout does not retry
            // lower siblings after that top child declines ACTION_DOWN, so route the
            // complete gesture to the renderer that owns double-tap/long-press/taps.
            return primaryView.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        // 表盘列表滑动会把离屏的 Stage 摘下来。每个 video 都占一个编解码器实例，
        // 不在这里放掉，滑几屏就能把低端机的解码资源耗尽。
        videoViews.forEach(WatchFaceVideoLayerView::release)
        // 播放器已经放掉了，签名必须一起作废，否则重新上屏时
        // syncVideoSegments 会因为「结构没变」直接短路，视频再也不回来。
        appliedSignature = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 重新上屏时按当前分段结构把播放器建回来。
        if (videoLayerIndices.isNotEmpty()) syncVideoSegments()
    }

    fun dispose() {
        videoViews.forEach(WatchFaceVideoLayerView::release)
        videoViews.clear()
        extraViews.forEach(WatchmakerWatchFaceView::dispose)
        extraViews.clear()
        primaryView.dispose()
        removeAllViews()
    }

    private companion object {
        val IDENTITY = Matrix()

        /**
         * 同屏最多几个 video 图层真的起播放器。
         *
         * 每个都是一个 MediaPlayer + 一个 Surface；表盘列表快速滑动时会连着
         * 创建又销毁，低端机很容易在编解码器资源上撞墙。超出的图层退回静态
         * 首帧，视觉上仍然有内容。
         */
        const val MAX_CONCURRENT_VIDEO_LAYERS = 2
    }
}
