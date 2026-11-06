@file:Suppress("DEPRECATION")

package com.flue.launcher.applist.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import eightbitlab.com.blurview.BlurAlgorithm

/**
 * Strict legacy kernel: unlike BlurView's RenderScriptBlur, failures propagate
 * to the host so it can restore the live list and stop this transition's work.
 * Constructed only by the API 27–30 host's lazy algorithm factory.
 */
internal class NativeAppListRenderScriptBlur(context: Context) : BlurAlgorithm {
    private val renderScript = RenderScript.create(context.applicationContext)
    private val script = try {
        ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
    } catch (failure: Throwable) {
        renderScript.destroy()
        throw failure
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var input: Allocation? = null
    private var output: Allocation? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var destroyed = false

    fun prepare(bitmap: Bitmap) {
        check(!destroyed)
        if (input == null || bitmapWidth != bitmap.width || bitmapHeight != bitmap.height) {
            input?.destroy()
            input = null
            output?.destroy()
            output = null
            input = Allocation.createFromBitmap(renderScript, bitmap)
            output = Allocation.createTyped(renderScript, input!!.type)
            bitmapWidth = bitmap.width
            bitmapHeight = bitmap.height
        }
    }

    override fun blur(bitmap: Bitmap, blurRadius: Float): Bitmap {
        check(!destroyed && input != null && bitmapWidth == bitmap.width && bitmapHeight == bitmap.height)
        input!!.copyFrom(bitmap)
        script.setRadius(blurRadius)
        script.setInput(input)
        script.forEach(output)
        output!!.copyTo(bitmap)
        return bitmap
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { input?.destroy() }
        input = null
        runCatching { output?.destroy() }
        output = null
        runCatching { script.destroy() }
        runCatching { renderScript.destroy() }
    }

    override fun canModifyBitmap() = true
    override fun getSupportedBitmapConfig() = Bitmap.Config.ARGB_8888
    override fun render(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }
}
