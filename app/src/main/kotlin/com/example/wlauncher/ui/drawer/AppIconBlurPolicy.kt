package com.flue.launcher.ui.drawer

import android.os.Build

internal fun usePreRenderedIconBlur(
    apiLevel: Int = Build.VERSION.SDK_INT,
    blurEnabled: Boolean,
    edgeBlurEnabled: Boolean,
    blurRadius: Float,
): Boolean =
    apiLevel < Build.VERSION_CODES.S &&
        blurEnabled &&
        edgeBlurEnabled &&
        blurRadius > 0.5f
