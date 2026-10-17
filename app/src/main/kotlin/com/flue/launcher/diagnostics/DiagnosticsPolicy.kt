package com.flue.launcher.diagnostics

const val MAX_RECORDING_DURATION_MS = 5 * 60 * 1_000L

/** elapsedRealtime, rather than sampled foreground time, owns the recording deadline. */
class RecordingWindow(val startMs: Long) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= startMs && nowMs - startMs >= MAX_RECORDING_DURATION_MS
    fun remainingMs(nowMs: Long): Long = (MAX_RECORDING_DURATION_MS - (nowMs - startMs).coerceAtLeast(0L)).coerceAtLeast(0L)
}

data class DiagnosticsConfig(
    val fps: Boolean = false,
    val memory: Boolean = false,
    val threads: Boolean = false,
) {
    val anyEnabled: Boolean get() = fps || memory || threads
}

data class FrameSample(
    val frames: Long = 0,
    val fps: Double = 0.0,
    val averageMs: Double = 0.0,
    val maxMs: Double = 0.0,
    val jankPercent: Double = 0.0,
    val droppedCallbacks: Long = 0,
    val p95Ms: Double = 0.0,
)

/** Fixed-size counters: an idle window produces 0 FPS, without scheduling synthetic frames. */
class FrameIntervalAccumulator {
    private var frames = 0L
    private var totalNanos = 0L
    private var maxNanos = 0L
    private var jank = 0L
    private var dropped = 0L
    private val durations = DurationHistogram()

    @Synchronized
    fun record(durationNanos: Long, budgetNanos: Long, droppedCallbacks: Int) {
        if (durationNanos < 0L) return
        frames++
        totalNanos += durationNanos
        maxNanos = maxOf(maxNanos, durationNanos)
        if (durationNanos > budgetNanos.coerceAtLeast(1L)) jank++
        dropped += droppedCallbacks.coerceAtLeast(0)
        durations.record(durationNanos)
    }

    @Synchronized
    fun take(elapsedMs: Long): FrameSample {
        val sample = FrameSample(
            frames = frames,
            fps = frames * 1_000.0 / elapsedMs.coerceAtLeast(1L),
            averageMs = if (frames == 0L) 0.0 else totalNanos / frames / 1_000_000.0,
            maxMs = maxNanos / 1_000_000.0,
            jankPercent = if (frames == 0L) 0.0 else jank * 100.0 / frames,
            droppedCallbacks = dropped,
            p95Ms = durations.percentile95Ms(),
        )
        frames = 0; totalNanos = 0; maxNanos = 0; jank = 0; dropped = 0
        durations.clear()
        return sample
    }
}

private val DIAGNOSTIC_CONFIG_KEYS = setOf(
    "layout_mode", "honeycomb_cols", "blur_enabled", "edge_blur_enabled",
    "animation_override_enabled", "animation_duration_scale", "music_text_switch_animation",
    "honeycomb_top_blur", "honeycomb_bottom_blur", "honeycomb_edge_blur_tenths",
    "selected_watchface_id", "show_widget_page", "widget_count", "widget_glass_highlight_enabled",
    "watchface_text_entry_animation", "watchface_text_exit_animation", "watchface_gravity_parallax_enabled",
    "watchface_gravity_backdrop_blur", "ui_style", "theme_mode", "aod_enabled", "aod_timeout_seconds",
    "app_list_scale_percent", "app_list_fisheye_enabled", "app_list_fisheye_range_rows",
    "app_list_fisheye_strength_percent", "app_list_edge_spacing_compression_enabled",
    "app_list_left_safe_inset_percent", "app_list_flow_crescent_bulge_percent", "app_list_flow_crescent_to_right",
    "app_list_edge_align_enabled",
    "motion_profile_id", "reduce_dynamic_effects", "transition_motion",
) + listOf("app_list_enter", "app_launch", "app_return", "pull_to_face", "edge_bounce").flatMap { phase ->
    listOf("duration_ms", "handoff_ms", "easing", "x1", "x2", "y1", "y2", "damping_ratio", "stiffness")
        .map { parameter -> "motion_${phase}_$parameter" }
}

/** Deliberately excludes raw preferences, locations, widget payloads, accounts and credentials. */
fun allowlistedDiagnosticConfig(values: Map<String, *>): Map<String, Any> = buildMap {
    DIAGNOSTIC_CONFIG_KEYS.forEach { key ->
        when (val value = values[key]) {
            is String -> put(key, value.take(160))
            is Boolean -> put(key, value)
            is Number -> put(key, value)
        }
    }
}
