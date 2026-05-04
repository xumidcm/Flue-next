package com.flue.launcher.ui.drawer

internal fun fisheyeMinScale(strengthPercent: Int): Float {
    val strength = strengthPercent.coerceIn(0, 200) / 100f
    return (1f - 0.42f * strength).coerceIn(0.16f, 1f)
}
