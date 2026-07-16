package com.flue.launcher.applist.geometry

data class SafeInsetsPx(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    init {
        require(
            listOf(left, top, right, bottom).all { inset ->
                inset.isFinite() && inset >= 0f
            },
        ) {
            "Safe insets must be finite and non-negative"
        }
    }
}

sealed interface DeviceProfile {
    val safeInsets: SafeInsetsPx

    data class Rectangular(
        override val safeInsets: SafeInsetsPx = SafeInsetsPx(),
    ) : DeviceProfile

    data class Round(
        override val safeInsets: SafeInsetsPx = SafeInsetsPx(),
    ) : DeviceProfile
}
