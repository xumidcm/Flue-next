package com.flue.launcher.ui.anim

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.isMaterial

/**
 * 每一层在不同状态下的 scale / blur / opacity 目标值。
 */
data class LayerAnimValues(
    val scale: Float = 1f,
    val blur: Float = 0f,    // dp
    val alpha: Float = 1f,
    val translationX: Float = 0f,  // 屏幕宽度比例（-1f = 向左移出, 1f = 向右移出）
    val translationY: Float = 0f  // 屏幕高度比例（-1f = 向上移出, 1f = 向下移出）
)

/**
 * 去掉缩放与位移行程，只保留 alpha / blur 过渡。
 */
private fun LayerAnimValues.withoutShrink(): LayerAnimValues =
    copy(scale = 1f, translationX = 0f, translationY = 0f)

private fun LayerAnimValues.shrinkGated(enabled: Boolean): LayerAnimValues =
    if (enabled) this else withoutShrink()

/**
 * beta1.5 之前的表盘层曲线：打开应用列表时表盘直接放大冲脸。
 * 由「还原旧转场」开关驱动，保留给习惯老手感的用户。
 */
private fun legacyFaceLayerValues(state: ScreenState, uiStyle: UiStyle): LayerAnimValues =
    if (uiStyle.isMaterial) {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 1.22f, blur = 7f, alpha = 0.22f)
            ScreenState.App -> LayerAnimValues(scale = 1.28f, blur = 9f, alpha = 0.14f)
            ScreenState.Stack -> LayerAnimValues(scale = 0.93f, blur = 3f, alpha = 0.58f)
            ScreenState.Notifications -> LayerAnimValues(scale = 0.92f, blur = 4f, alpha = 0.52f)
            ScreenState.Widgets -> LayerAnimValues(scale = 0.93f, blur = 3f, alpha = 0.58f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 0.94f, blur = 5f, alpha = 0.48f)
        }
    } else {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f)
            ScreenState.App -> LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f)
            ScreenState.Stack -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.Notifications -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.Widgets -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.5f)
        }
    }

/**
 * 表盘层状态映射。
 *
 * [shrinkOnFaceReturn] 关闭后，表盘层在打开应用列表 / 应用时不再放大位移，
 * 只做淡入淡出与模糊，避免收缩动画被抽帧后直接闪现归位。
 */
fun faceLayerValues(
    state: ScreenState,
    uiStyle: UiStyle,
    shrinkOnFaceReturn: Boolean = true,
    legacy: Boolean = false,
): LayerAnimValues = if (legacy) {
    legacyFaceLayerValues(state, uiStyle).shrinkGated(
        shrinkOnFaceReturn ||
            state == ScreenState.Face ||
            state == ScreenState.Stack ||
            state == ScreenState.Notifications ||
            state == ScreenState.Widgets ||
            state == ScreenState.ControlCenter
    )
} else {
    if (uiStyle.isMaterial) {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 1.22f, blur = 7f, alpha = 0.22f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 1.28f, blur = 9f, alpha = 0.14f).shrinkGated(shrinkOnFaceReturn)
            // 侧屏四态的「下压」现在统一由 OverlayBackdropController 一条链驱动
            // （缩放 + 下沉 + 模糊 + 压暗都在那里）。这里再叠一份 scale 就是两个
            // 来源打架，观感是「负一屏打开时表盘先缩一次、覆层再缩一次」。这一层
            // 只保留模糊与淡出。legacy 分支（:41-55）保持原样。
            ScreenState.Stack -> LayerAnimValues(scale = 1f, blur = 3f, alpha = 0.58f)
            ScreenState.Notifications -> LayerAnimValues(scale = 1f, blur = 4f, alpha = 0.52f)
            ScreenState.Widgets -> LayerAnimValues(scale = 1f, blur = 3f, alpha = 0.58f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 1f, blur = 5f, alpha = 0.48f)
        }
    } else {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            // watchOS 三段式的 A 段：表盘「向下压暗退后」（缩小 + 下沉 + 模糊），
            // 而不是旧版的 2.5 倍放大冲脸。B 段的渐黑由 LauncherActivity 里的
            // 独立遮罩层承担，所以这里 alpha 不用一路打到 0 也能全黑。
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 0.90f, blur = 14f, alpha = 0f, translationY = 0.07f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 0.88f, blur = 15f, alpha = 0f, translationY = 0.07f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Stack -> LayerAnimValues(scale = 1f, blur = 5f, alpha = 0.3f)
            ScreenState.Notifications -> LayerAnimValues(scale = 1f, blur = 5f, alpha = 0.3f)
            ScreenState.Widgets -> LayerAnimValues(scale = 1f, blur = 5f, alpha = 0.3f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 1f, blur = 8f, alpha = 0.5f)
        }
    }
}

/**
 * 应用列表层状态映射。
 *
 * [shrinkOnAppReturn] 控制「从应用返回应用列表」这一档的整体缩放，
 * [shrinkOnFaceReturn] 控制「返回表盘」这一档（含兜底档）的整体收缩。
 * 关闭后只保留 alpha / blur 过渡。
 */
fun appListLayerValues(
    state: ScreenState,
    uiStyle: UiStyle,
    shrinkOnAppReturn: Boolean = true,
    shrinkOnFaceReturn: Boolean = true,
): LayerAnimValues =
    if (uiStyle.isMaterial) {
        when (state) {
            ScreenState.Face ->
                LayerAnimValues(scale = 0.88f, blur = 8f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Apps -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Settings -> LayerAnimValues(scale = 0.96f, blur = 4f, alpha = 0.42f)
            ScreenState.App ->
                LayerAnimValues(scale = 1f, blur = 4f, alpha = 0f, translationX = -1f)
                    .shrinkGated(shrinkOnAppReturn)
            else ->
                LayerAnimValues(scale = 0.88f, blur = 8f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
        }
    } else {
        when (state) {
            // 0.2 的旧目标让列表退场时缩成一个点，spring 收尾差一点没走完就
            // 被遮住，观感就是「闪现归位」。watchOS 的退场是 0.86 的小幅收缩
            // + 淡出（图标飞入的逆过程）。
            ScreenState.Face ->
                LayerAnimValues(scale = 0.86f, blur = 8f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Apps -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Settings -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.3f)
            // 4 倍猛缩是「返回时突然收缩」的来源。启动/返回期间实际生效的是
            // appListLaunchValues（与遮罩共用时钟），这里只是驱动撤走后的兜底
            // 目标，必须与它的 p=1 端一致，否则交接仍会跳。
            ScreenState.App ->
                LayerAnimValues(scale = 1.12f, blur = 14f, alpha = 0f).shrinkGated(shrinkOnAppReturn)
            else ->
                LayerAnimValues(scale = 0.86f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
        }
    }

/**
 * 启动 / 返回期间应用列表层的视觉，由启动遮罩的同一条进度驱动。
 *
 * [progress] 0 = 应用列表静止态，1 = 应用已接管全屏。
 *
 * **p = 0 时必须精确等于 `appListLayerValues(ScreenState.Apps)` 的静止值**
 * （scale 1 / alpha 1 / blur 0），否则驱动撤走的那一帧仍会跳 —— 这正是这套
 * 机制要消灭的东西，改这里的数值时务必守住这个不变量。
 *
 * 曲线本身走 watchOS 的「让位」而不是旧的 4 倍猛缩：列表只轻微前推 +
 * 模糊淡出，把画面交给遮罩，返回时原路淡回来。
 */
fun appListLaunchValues(progress: Float): LayerAnimValues {
    val p = progress.coerceIn(0f, 1f)
    return LayerAnimValues(
        scale = 1f + 0.12f * p,
        blur = 14f * p,
        // 遮罩在 p≈0.5 前就已铺满，列表提前淡完可以省掉一整段昂贵的模糊合成。
        alpha = (1f - p * 1.8f).coerceIn(0f, 1f),
    )
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

fun widgetLayerValues(state: ScreenState): LayerAnimValues = when (state) {
    ScreenState.Widgets -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f, translationY = 0f)
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
