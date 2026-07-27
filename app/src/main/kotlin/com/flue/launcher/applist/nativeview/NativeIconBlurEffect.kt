package com.flue.launcher.applist.nativeview

import android.graphics.RenderEffect
import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi

private val NativeBlurRadiusBucketsPx = intArrayOf(
    0, 1, 2, 3, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64,
)

internal fun quantizeNativeBlurRadiusPx(radiusPx: Float): Int {
    if (!radiusPx.isFinite() || radiusPx < 1f) return 0
    var selected = 0
    for (bucket in NativeBlurRadiusBucketsPx) {
        if (bucket > radiusPx) break
        selected = bucket
    }
    return selected
}

internal fun nativeContentOverlayBlurBucketPx(config: NativeAppListConfig): Int =
    if (config.contentOverlayBlurEligible || config.effectiveBlurTier() == NativeBlurTier.SYSTEM) {
        quantizeNativeBlurRadiusPx(config.contentOverlayBlurRadiusPx)
    } else {
        0
    }

internal object NativeSystemBlurEffectCache {
    private val cache = LruCache<Int, RenderEffect>(NativeBlurRadiusBucketsPx.size)

    @RequiresApi(Build.VERSION_CODES.S)
    fun get(radiusPx: Int): RenderEffect {
        require(radiusPx > 0) { "Blur radius must be positive" }
        return synchronized(cache) {
            cache.get(radiusPx) ?: RenderEffect.createBlurEffect(
                radiusPx.toFloat(),
                radiusPx.toFloat(),
                Shader.TileMode.CLAMP,
            ).also { cache.put(radiusPx, it) }
        }
    }
}

internal interface NativeIconEffectRenderer {
    fun setBlurRadiusBucket(radiusPx: Int)
    fun invalidateContent()
    fun discardContent()
    fun draw(
        target: Canvas,
        width: Int,
        height: Int,
        drawContent: (Canvas) -> Unit,
    )
}

@RequiresApi(Build.VERSION_CODES.S)
internal class RenderNodeNativeIconEffectRenderer : NativeIconEffectRenderer {
    private val renderNode = RenderNode("flue-native-app-icon")
    /**
     * Effect currently installed on [renderNode]. A zero requested bucket only
     * bypasses this node in [NativeAppListRowView]; keeping the last effect
     * installed avoids detaching and reattaching every visible row when a
     * scroll starts and settles.
     */
    private var installedBlurRadiusBucketPx = 0
    private var recordedWidth = 0
    private var recordedHeight = 0
    private var contentDirty = true

    override fun setBlurRadiusBucket(radiusPx: Int) {
        if (radiusPx <= 0 || installedBlurRadiusBucketPx == radiusPx) return
        installedBlurRadiusBucketPx = radiusPx
        renderNode.setRenderEffect(NativeSystemBlurEffectCache.get(radiusPx))
    }

    override fun invalidateContent() {
        contentDirty = true
    }

    override fun discardContent() {
        renderNode.discardDisplayList()
        renderNode.setRenderEffect(null)
        installedBlurRadiusBucketPx = 0
        recordedWidth = 0
        recordedHeight = 0
        contentDirty = true
    }

    override fun draw(
        target: Canvas,
        width: Int,
        height: Int,
        drawContent: (Canvas) -> Unit,
    ) {
        // 根因（实机「开边缘模糊后，滚动尾端最后一行图标逐个直接消失且无
        // 模糊」）：这里以前只信任自家 contentDirty 决定是否重录，且
        // width/height <= 0 时静默返回。但 RenderNode 的显示列表归系统所有：
        // 行视图被 RecyclerView 缓存复用（stable-id 命中 itemView 缓存时不走
        // rebind，invalidateContent 不会被调用）、或 bucket 归零期间本节点脱离
        // 视图显示列表后，录制内容可能已被系统回收——此时 contentDirty 仍是
        // false、尺寸也没变，drawRenderNode 画出的是「空」。bucket > 0 才走本
        // 路径，所以症状只在开启边缘模糊时出现，且集中在滚动方向尾端刚重新
        // 挂接的行上。修复：显示列表失效时强制重录；无法走 RenderNode 时回退
        // 直绘，宁可这一帧没有模糊也绝不画空。
        if (width <= 0 || height <= 0 || !target.isHardwareAccelerated) {
            drawContent(target)
            return
        }
        if (
            contentDirty ||
            recordedWidth != width ||
            recordedHeight != height ||
            !renderNode.hasDisplayList()
        ) {
            renderNode.setPosition(0, 0, width, height)
            val recordingCanvas = renderNode.beginRecording(width, height)
            drawContent(recordingCanvas)
            renderNode.endRecording()
            recordedWidth = width
            recordedHeight = height
            contentDirty = false
        }
        if (!renderNode.hasDisplayList()) {
            // 录制在同帧内又被系统回收的极端情况：首帧回退直绘兜底。
            drawContent(target)
            return
        }
        target.drawRenderNode(renderNode)
    }
}
