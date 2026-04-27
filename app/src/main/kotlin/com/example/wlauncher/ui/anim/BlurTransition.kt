package com.flue.launcher.ui.anim

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.flue.launcher.ui.navigation.ScreenState

/**
 * 每一层在不同状态下的 scale / blur / opacity 目标值。
 */
data class LayerAnimValues(
    val scale: Float = 1f,
    val blur: Float = 0f,    // dp
    val alpha: Float = 1f,
    val translationY: Float = 0f  // 屏幕高度比例（-1f = 向上移出, 1f = 向下移出）
)

/**
 * 表盘层状态映射。
 */
fun faceLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
    ScreenState.Apps, ScreenState.Settings -> LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f)
    ScreenState.App -> LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f)
    ScreenState.Stack -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
    ScreenState.Notifications -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
    ScreenState.ControlCenter -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.5f)
}

/**
 * 应用列表层状态映射。
 */
fun appListLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.Face -> LayerAnimValues(scale = 0.2f, blur = 10f, alpha = 0f)
    ScreenState.Apps -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
    ScreenState.Settings -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.3f)
    ScreenState.App -> LayerAnimValues(scale = 4f, blur = 10f, alpha = 0f)
    else -> LayerAnimValues(scale = 0.2f, blur = 10f, alpha = 0f)
}

/**
 * 应用视图层状态映射。
 */
fun appViewLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.App -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
    ScreenState.Apps -> LayerAnimValues(scale = 0f, blur = 0f, alpha = 0f)
    else -> LayerAnimValues(scale = 0f, blur = 0f, alpha = 0f)
}

/**
 * 侧屏层状态映射。
 */
fun stackLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.Stack -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f, translationY = 0f)
    ScreenState.Apps -> LayerAnimValues(scale = 1.5f, blur = 12f, alpha = 0f, translationY = -0.5f)
    else -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 0f, translationY = 1f)
}

/**
 * 通知层状态映射。
 */
fun notificationLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.Notifications -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f, translationY = 0f)
    else -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 0f, translationY = 1f)
}

/**
 * 控制中心层状态映射。
 */
fun controlCenterLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.ControlCenter -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f, translationY = 0f)
    else -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 0f, translationY = 1f)
}

// 轻微回弹效果，接近 cubic-bezier(0.32, 1.2, 0.4, 1) 的观感
val TransitionSpring = spring<Float>(
    dampingRatio = 0.75f,
    stiffness = Spring.StiffnessMediumLow
)

val AlphaSpec = tween<Float>(durationMillis = 400)
