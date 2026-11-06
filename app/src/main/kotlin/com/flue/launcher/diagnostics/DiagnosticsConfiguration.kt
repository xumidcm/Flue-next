package com.flue.launcher.diagnostics

import com.flue.launcher.motion.MotionEasing
import com.flue.launcher.motion.MotionPhaseKey
import com.flue.launcher.viewmodel.LauncherViewModel
import java.util.Locale

/** Read the effective StateFlow values, including active layout/motion profiles, on the UI thread. */
internal fun diagnosticsConfiguration(vm: LauncherViewModel): Map<String, Any> = buildMap {
    put("layout_mode", vm.layoutMode.value.name)
    put("honeycomb_cols", vm.honeycombCols.value)
    put("app_list_scale_percent", vm.appListScalePercent.value)
    put("app_list_fisheye_enabled", vm.appListFisheyeEnabled.value)
    put("app_list_fisheye_range_rows", vm.appListFisheyeRangeRows.value)
    put("app_list_fisheye_strength_percent", vm.appListFisheyeStrengthPercent.value)
    put("app_list_edge_spacing_compression_enabled", vm.appListEdgeSpacingCompressionEnabled.value)
    put("app_list_left_safe_inset_percent", vm.appListLeftSafeInsetPercent.value)
    put("app_list_edge_align_enabled", vm.appListEdgeAlignEnabled.value)
    put("app_list_flow_crescent_bulge_percent", vm.appListFlowCrescentBulgePercent.value)
    put("app_list_flow_crescent_to_right", vm.appListFlowCrescentToRight.value)
    put("blur_enabled", vm.blurEnabled.value)
    put("edge_blur_enabled", vm.edgeBlurEnabled.value)
    put("honeycomb_top_blur", vm.honeycombTopBlur.value)
    put("honeycomb_bottom_blur", vm.honeycombBottomBlur.value)
    put("honeycomb_edge_blur_tenths", (vm.honeycombEdgeBlurRadius.value * 10f).toInt())
    put("animation_override_enabled", vm.animationOverrideEnabled.value)
    put("reduce_dynamic_effects", vm.reduceDynamicEffects.value)
    put("transition_motion", vm.transitionMotion.value.name)
    put("selected_watchface_id", vm.selectedWatchFaceId.value)
    put("show_widget_page", vm.showWidgetPage.value)
    put("widget_count", vm.sideScreenWidgetSlots.value.count { !it.isNullOrBlank() })
    put("widget_glass_highlight_enabled", vm.widgetGlassHighlightEnabled.value)
    put("watchface_text_entry_animation", vm.watchFaceTextEntryAnimation.value)
    put("watchface_text_exit_animation", vm.watchFaceTextExitAnimation.value)
    put("watchface_gravity_parallax_enabled", vm.watchfaceGravityParallaxEnabled.value)
    put("watchface_gravity_backdrop_blur", vm.watchfaceGravityBackdropBlurEnabled.value)
    put("ui_style", vm.uiStyle.value.name)
    put("theme_mode", vm.themeMode.value.name)
    val motion = vm.activeMotionProfile.value
    put("motion_profile_id", motion.id)
    MotionPhaseKey.entries.forEach { key ->
        val prefix = "motion_${key.name.lowercase(Locale.ROOT)}"
        val phase = motion.phase(key)
        put("${prefix}_duration_ms", phase.durationMillis)
        put("${prefix}_handoff_ms", phase.handoffMillis)
        when (val easing = phase.easing) {
            is MotionEasing.CubicBezier -> {
                put("${prefix}_easing", "cubic_bezier")
                put("${prefix}_x1", easing.x1); put("${prefix}_x2", easing.x2)
                put("${prefix}_y1", easing.y1); put("${prefix}_y2", easing.y2)
            }
            is MotionEasing.Spring -> {
                put("${prefix}_easing", "spring")
                put("${prefix}_damping_ratio", easing.dampingRatio)
                put("${prefix}_stiffness", easing.stiffness)
            }
        }
    }
}
