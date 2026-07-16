package com.flue.launcher.applist.nativeview

enum class NativeBlurTier {
    OFF,
    LITE,
    SYSTEM,
}

internal fun selectNativeBlurTier(
    sdkInt: Int,
    blurEnabled: Boolean,
    edgeBlurEnabled: Boolean,
    suppressHeavyEffects: Boolean,
): NativeBlurTier {
    if (!blurEnabled || !edgeBlurEnabled || suppressHeavyEffects) {
        return NativeBlurTier.OFF
    }
    return if (sdkInt >= 31) NativeBlurTier.SYSTEM else NativeBlurTier.LITE
}
