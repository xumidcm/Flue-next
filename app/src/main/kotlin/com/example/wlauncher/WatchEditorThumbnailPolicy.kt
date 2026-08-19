package com.flue.launcher

import android.graphics.Bitmap
import android.graphics.Color

internal fun watchEditorBlackVideoThumbnail(sizePx: Int): Bitmap =
    Bitmap.createBitmap(
        sizePx.coerceAtLeast(1),
        sizePx.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    ).apply {
        eraseColor(Color.BLACK)
    }
