package com.flue.launcher.ui.drawer

internal data class AppListEntryVisuals(
    val backgroundProgress: Float,
    val backgroundFillProgress: Float,
    val iconProgress: Float,
    val surfaceProgress: Float,
    val edgeProgress: Float
)

internal fun appListEntryVisuals(progress: Float): AppListEntryVisuals {
    val p = progress.coerceIn(0f, 1f)
    return AppListEntryVisuals(
        backgroundProgress = 1f,
        backgroundFillProgress = 1f,
        iconProgress = transitionPhase(p, 0f, 0.36f),
        surfaceProgress = transitionPhase(p, 0.18f, 0.56f),
        edgeProgress = transitionPhase(p, 0.34f, 0.68f)
    )
}

private fun transitionPhase(progress: Float, start: Float, end: Float): Float {
    val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    val smoother = t * t * t * (t * (t * 6f - 15f) + 10f)
    val inverse = 1f - smoother
    return 1f - inverse * inverse * inverse
}
