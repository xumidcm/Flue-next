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
    if (config.effectiveBlurTier() == NativeBlurTier.SYSTEM) {
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
        if (width <= 0 || height <= 0) return
        if (contentDirty || recordedWidth != width || recordedHeight != height) {
            renderNode.setPosition(0, 0, width, height)
            val recordingCanvas = renderNode.beginRecording(width, height)
            drawContent(recordingCanvas)
            renderNode.endRecording()
            recordedWidth = width
            recordedHeight = height
            contentDirty = false
        }
        target.drawRenderNode(renderNode)
    }
}
