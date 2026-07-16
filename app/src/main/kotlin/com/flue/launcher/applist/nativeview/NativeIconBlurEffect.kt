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
    private var blurRadiusBucketPx = 0
    private var recordedWidth = 0
    private var recordedHeight = 0
    private var contentDirty = true

    override fun setBlurRadiusBucket(radiusPx: Int) {
        if (blurRadiusBucketPx == radiusPx) return
        blurRadiusBucketPx = radiusPx
        renderNode.setRenderEffect(
            radiusPx.takeIf { it > 0 }?.let(NativeSystemBlurEffectCache::get),
        )
    }

    override fun invalidateContent() {
        contentDirty = true
    }

    override fun discardContent() {
        renderNode.discardDisplayList()
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
