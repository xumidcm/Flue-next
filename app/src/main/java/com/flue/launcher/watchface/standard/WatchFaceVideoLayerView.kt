package com.flue.launcher.watchface.standard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView

/**
 * 表盘 `video` 图层的播放面。
 *
 * 与启动器那个循环背景播放器（`InternalLoopingVideoView`）的关键区别：它被夹在
 * 两段透明的表盘 Canvas 之间，所以**不能有任何不透明背景，也不能有占位符**——
 * 那会把下层图层糊成黑块。几何也不自己算：视频图层可以带祖先组变换链、gyro
 * 偏移和 anchor 对齐，只有渲染器算得出正确结果，这里只负责把算好的 Matrix 喂给
 * [setTransform]。
 */
internal class WatchFaceVideoLayerView(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private var currentPath: String? = null
    /**
     * 宿主希望播放与否。prepare 是异步的，期间宿主可能已经按过暂停，
     * 所以 onPrepared 里要按这个意图决定是否 start，而不是无条件播放。
     */
    private var shouldPlay = true
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    private val appliedMatrix = Matrix()
    private var hasAppliedGeometry = false
    private var appliedAlpha = Float.NaN
    private var appliedVisible: Boolean? = null

    init {
        // 透明面：下层 Canvas 段要能透过没有视频像素的区域。
        isOpaque = false
        surfaceTextureListener = this
    }

    /** 换源。传 null 释放播放器。同一路径重复调用是 no-op。 */
    fun bindSource(absolutePath: String?) {
        if (absolutePath == currentPath) return
        currentPath = absolutePath
        if (absolutePath.isNullOrBlank()) releasePlayer() else preparePlayer()
    }

    /** 播放/暂停。暂停停在当前帧，不跳回首帧。 */
    fun setPlaying(playing: Boolean) {
        if (shouldPlay == playing) return
        shouldPlay = playing
        val target = player ?: return
        // 播放器处于错误/未就绪状态时这两个调用都会抛 IllegalStateException。
        runCatching {
            if (playing) target.start() else if (target.isPlaying) target.pause()
        }
    }

    /**
     * 应用渲染器算好的最终变换。[matrix] 已包含画布变换、祖先组变换链、
     * 图层自身变换与目标矩形映射，直接就是 [setTransform] 要的东西。
     */
    fun applyGeometry(matrix: Matrix, alpha: Float, visible: Boolean) {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        if (
            hasAppliedGeometry &&
            appliedVisible == visible &&
            appliedAlpha == clampedAlpha &&
            appliedMatrix == matrix
        ) {
            return
        }
        hasAppliedGeometry = true
        appliedMatrix.set(matrix)
        appliedAlpha = clampedAlpha
        appliedVisible = visible
        setTransform(matrix)
        this.alpha = clampedAlpha
        // 用 INVISIBLE 而非 GONE：GONE 会触发父容器重新 layout，
        // 而隐藏往往只是某一帧的 display 求值结果，下一帧就回来了。
        visibility = if (visible) VISIBLE else INVISIBLE
        invalidate()
    }

    /** 取当前帧，用于切表盘过渡期间用静态像素顶替独立 Surface。 */
    fun currentFrameBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return runCatching { bitmap }.getOrNull()
    }

    /** 显式释放。可重复调用。 */
    fun release() {
        releasePlayer()
        surface?.release()
        surface = null
        currentPath = null
    }

    private fun preparePlayer() {
        val path = currentPath
        val targetSurface = surface
        // Surface 还没就绪时只记住路径，等 onSurfaceTextureAvailable 再真正起播。
        if (path.isNullOrBlank() || targetSurface == null) return
        releasePlayer()
        runCatching {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            mediaPlayer.setSurface(targetSurface)
            mediaPlayer.setDataSource(path)
            mediaPlayer.isLooping = true
            // 表盘是常驻界面，绝不能出声。
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.setOnPreparedListener { prepared ->
                prepared.setVolume(0f, 0f)
                if (shouldPlay) {
                    runCatching { prepared.start() }
                } else {
                    // 暂停态也必须往 Surface 上推一帧：TextureView 在没收到任何
                    // 帧之前是全透明的，而渲染器又把 video 那个 planIndex 从
                    // Canvas 段里排除了（像素归这个面负责），两边都不画的结果就是
                    // 视频区域全黑，必须点一次播放/暂停才出画（老师实机反馈）。
                    // seekTo 会解码并投递目标帧，是暂停态出画的唯一手段。
                    runCatching { prepared.seekTo(0) }
                }
            }
            // 交回 true 表示错误已消费，避免框架继续派发到 OnCompletion。
            mediaPlayer.setOnErrorListener { _, _, _ -> true }
            mediaPlayer.prepareAsync()
        }.onFailure { releasePlayer() }
    }

    private fun releasePlayer() {
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.setOnPreparedListener(null) }
            runCatching { mediaPlayer.setOnErrorListener(null) }
            runCatching { mediaPlayer.stop() }
            runCatching { mediaPlayer.release() }
        }
        player = null
    }

    override fun onSurfaceTextureAvailable(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surface = Surface(surfaceTexture)
        preparePlayer()
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        // 几何由宿主逐帧下发，尺寸变化不需要在这里补算。
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // No-op.
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        surface?.release()
        surface = null
        super.onDetachedFromWindow()
    }
}
