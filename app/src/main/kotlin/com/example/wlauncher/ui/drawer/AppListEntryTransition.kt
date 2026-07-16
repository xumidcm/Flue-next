package com.flue.launcher.ui.drawer

internal data class AppListEntryVisuals(
    val backgroundProgress: Float,
    val backgroundFillProgress: Float,
    val iconProgress: Float,
    val surfaceProgress: Float,
    val edgeProgress: Float,
    val verticalOffsetFraction: Float,
)

internal enum class AppListEntryMotion {
    BETA_12_CLASSIC,
    FLUE_NEXT,
}

internal fun appListEntryMotionForStyle(appleWatchStyle: Boolean): AppListEntryMotion =
    if (appleWatchStyle) {
        AppListEntryMotion.BETA_12_CLASSIC
    } else {
        AppListEntryMotion.FLUE_NEXT
    }

internal fun appListEntryVisuals(
    progress: Float,
    motion: AppListEntryMotion = AppListEntryMotion.BETA_12_CLASSIC,
): AppListEntryVisuals {
    val normalized = progress.coerceIn(0f, 1f)
    if (motion == AppListEntryMotion.BETA_12_CLASSIC) {
        return AppListEntryVisuals(
            backgroundProgress = 1f,
            backgroundFillProgress = 1f,
            iconProgress = 1f,
            surfaceProgress = 1f,
            edgeProgress = 1f,
            verticalOffsetFraction = 0f,
        )
    }
    return AppListEntryVisuals(
        backgroundProgress = normalized,
        backgroundFillProgress = normalized,
        iconProgress = normalized,
        surfaceProgress = normalized,
        edgeProgress = normalized,
        verticalOffsetFraction = (1f - normalized) * 0.08f,
    )
}
