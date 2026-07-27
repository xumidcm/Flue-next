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
 * 表盘层状态映射。
 *
 * [shrinkOnFaceReturn] 关闭后，表盘层在打开应用列表 / 应用时不再放大位移，
 * 只做淡入淡出与模糊，避免收缩动画被抽帧后直接闪现归位。
 * [legacyTransition] 开启时（仅非 Material 风格）还原 watchOS 三段式之前的
 * 老动画：表盘 2.5 倍放大冲脸，而不是压暗下沉让位。
 */
fun faceLayerValues(
    state: ScreenState,
    uiStyle: UiStyle,
    shrinkOnFaceReturn: Boolean = true,
    legacyTransition: Boolean = false,
): LayerAnimValues =
    if (!uiStyle.isMaterial && legacyTransition) {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Stack -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.Notifications -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.Widgets -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.5f)
        }
    } else if (uiStyle.isMaterial) {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 1.22f, blur = 7f, alpha = 0.22f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 1.28f, blur = 9f, alpha = 0.14f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Stack -> LayerAnimValues(scale = 0.93f, blur = 3f, alpha = 0.58f)
            ScreenState.Notifications -> LayerAnimValues(scale = 0.92f, blur = 4f, alpha = 0.52f)
            ScreenState.Widgets -> LayerAnimValues(scale = 0.93f, blur = 3f, alpha = 0.58f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 0.94f, blur = 5f, alpha = 0.48f)
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
            ScreenState.Stack -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.Notifications -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.Widgets -> LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
            ScreenState.ControlCenter -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.5f)
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
    legacyTransition: Boolean = false,
): LayerAnimValues =
    if (!uiStyle.isMaterial && legacyTransition) {
        when (state) {
            // 老动画：列表退回表盘时缩成一个点（0.2），配合表盘 2.5 倍冲脸。
            ScreenState.Face ->
                LayerAnimValues(scale = 0.2f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Apps -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Settings -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.3f)
            ScreenState.App ->
                LayerAnimValues(scale = 4f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnAppReturn)
            else ->
                LayerAnimValues(scale = 0.2f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
        }
    } else if (uiStyle.isMaterial) {
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
            ScreenState.App ->
                LayerAnimValues(scale = 4f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnAppReturn)
            else ->
                LayerAnimValues(scale = 0.2f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
        }
    }

/**
 * 应用启动/返回转场中应用列表层的逐帧值：由 launchMotionProgress 单一进度
 * 驱动（p=0 完全等于 Apps 静止态，p=1 等于 App 态终点）。
 *
 * 不变量：p=0 必须精确回到静止态（scale 1 / blur 0 / alpha 1 / 位移 0），
 * 外部时钟撤走时 ScaleBlurModifier 直接以此为种子接管，端点零跳变。
 *
 * alpha 用 1.8 倍斜率提前淡完：p≈0.55 之后列表已不可见，启动遮罩的
 * 渐显（1.6 倍斜率）在它上面接管画面——两条曲线同源，返回方向反演时
 * 不再出现「遮罩半透明时露出 4 倍巨图标然后闪现归位」。
 */
fun appListLaunchValues(
    progress: Float,
    uiStyle: UiStyle,
    shrinkOnAppReturn: Boolean = true,
): LayerAnimValues {
    val p = progress.coerceIn(0f, 1f)
    val end = appListLayerValues(
        state = ScreenState.App,
        uiStyle = uiStyle,
        shrinkOnAppReturn = shrinkOnAppReturn,
    )
    return LayerAnimValues(
        scale = 1f + (end.scale - 1f) * p,
        // 模糊 2.2 倍斜率抢跑：图标还看得见的前半程（p<0.45）就已接近
        // 满模糊——「打开应用时背景图标要有模糊」（Compose 版行为）。
        blur = end.blur * (p * 2.2f).coerceAtMost(1f),
        alpha = (1f - 1.8f * p).coerceIn(0f, 1f),
        translationX = end.translationX * p,
        translationY = end.translationY * p,
    )
}

/** [appListLaunchValues] 的底色板变体：只跟 alpha，不缩放不位移。 */
fun appListLaunchPlateValues(progress: Float): LayerAnimValues =
    LayerAnimValues(
        scale = 1f,
        blur = 0f,
        alpha = (1f - 1.8f * progress.coerceIn(0f, 1f)).coerceIn(0f, 1f),
    )

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
