package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap

/** Owns only the submitted Bitmap reference and never copies or recycles it. */
internal class NativeAppListIconRenderOwner {
    private var bitmap: Bitmap? = null

    fun bind(bitmap: Bitmap?) {
        this.bitmap = bitmap
    }

    fun bitmapForDraw(): Bitmap? {
        val candidate = bitmap ?: return null
        return candidate.takeIf {
            !it.isRecycled && it.width > 0 && it.height > 0
        }
    }

    fun clear() {
        bitmap = null
    }
}
