package com.flue.launcher.ui.drawer

internal data class AppListEntryVisuals(
    val backgroundProgress: Float,
    val iconProgress: Float,
    val surfaceProgress: Float,
    val edgeProgress: Float
)

internal fun appListEntryVisuals(progress: Float): AppListEntryVisuals {
    val p = progress.coerceIn(0f, 1f)
    return AppListEntryVisuals(
        backgroundProgress = transitionPhase(p, 0f, 0.66f),
        iconProgress = transitionPhase(p, 0.06f, 0.48f),
        surfaceProgress = transitionPhase(p, 0.36f, 0.72f),
        edgeProgress = transitionPhase(p, 0.68f, 1f)
    )
}

private fun transitionPhase(progress: Float, start: Float, end: Float): Float {
    val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
