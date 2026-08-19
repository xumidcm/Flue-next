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
    val translationX: Float = 0f,  // 屏幕宽度比例（-1f = 向左移出, 1f = 向右移出）
    val translationY: Float = 0f  // 屏幕高度比例（-1f = 向上移出, 1f = 向下移出）
)

/**
 * 转场动画风格。**与界面风格无关**，单独一项设置。
 *
 * ## 为什么要拆开
 *
 * 这两套动画原本是按 `uiStyle.isMaterial` 分的：Material 档走「列表横向移出 + 表盘小幅
 * 缩放」，另两档走「列表放大穿过 + 表盘压暗下沉」。可**动画取向与视觉风格是两件事** ——
 * 老师想要 watchOS 的形状配平移动画，或者 HyperOS 的配色配缩放动画，原来都做不到，
 * 只能连着换整套外观。
 *
 * 所以这里把它抽成独立取值：
 *
 * - [SLIDE]（平移）＝原来 Material 那套：列表横向移出、表盘只小幅缩放；
 * - [SCALE]（缩放）＝原来非 Material 那套：列表放大穿过、表盘压暗下沉。
 *
 * 名字按**动画的主导量**取，不按风格取 —— 以后再加风格也不会把这层重新绑回去。
 */
enum class FlueTransitionMotion {
    /** 平移主导：列表横向移出移入，缩放很轻。原 Material 档的做法。 */
    SLIDE,

    /** 缩放主导：列表放大穿过、表盘压暗下沉。原 watchOS / HyperOS 档的做法。 */
    SCALE;

    companion object {
        /** 默认取缩放：三档风格里有两档原本就是这套，多数用户升级后观感不变。 */
        val Default: FlueTransitionMotion = SCALE

        fun fromPreference(raw: String?): FlueTransitionMotion =
            entries.firstOrNull { it.name == raw } ?: Default
    }
}

/**
 * 在两个完整图层状态之间按同一进度插值。
 *
 * 跟手手势不能分别启动 scale/blur/alpha 的弹簧，否则四个量会在抬手前就
 * 彼此错相；应用列表与表盘都通过这个纯函数读取同一个手势时钟。
 */
internal fun lerpLayerAnimValues(
    start: LayerAnimValues,
    end: LayerAnimValues,
    progress: Float,
): LayerAnimValues {
    val p = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    fun lerp(startValue: Float, endValue: Float): Float =
        startValue + (endValue - startValue) * p
    return LayerAnimValues(
        scale = lerp(start.scale, end.scale),
        blur = lerp(start.blur, end.blur),
        alpha = lerp(start.alpha, end.alpha),
        translationX = lerp(start.translationX, end.translationX),
        translationY = lerp(start.translationY, end.translationY),
    )
}

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
 * 只做淡入淡出与模糊，避免收缩动画被抽帧后直接闪现归位。负一屏 / 小组件 /
 * 控制中心 / 通知中心这四档覆层以前不受它管，开关关掉后表盘照样缩——
 * 现在一并门控，「关闭表盘收缩动画」对所有覆层一致生效。
 * [legacyTransition] 开启时（仅非 Material 风格）还原 watchOS 三段式之前的
 * 老动画：表盘 2.5 倍放大冲脸，而不是压暗下沉让位。
 */
fun faceLayerValues(
    state: ScreenState,
    motion: FlueTransitionMotion,
    shrinkOnFaceReturn: Boolean = true,
    legacyTransition: Boolean = false,
): LayerAnimValues =
    // 「还原老动画」是缩放家族的一个变体（2.5 倍冲脸），所以只在缩放档下生效；
    // 平移档开着它没有意义，也不该悄悄改成另一套。
    if (motion == FlueTransitionMotion.SCALE && legacyTransition) {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 2.5f, blur = 15f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Stack ->
                LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Notifications ->
                LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Widgets ->
                LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.ControlCenter ->
                LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.5f)
                    .shrinkGated(shrinkOnFaceReturn)
        }
    } else if (motion == FlueTransitionMotion.SLIDE) {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 1.22f, blur = 7f, alpha = 0.22f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 1.28f, blur = 9f, alpha = 0.14f).shrinkGated(shrinkOnFaceReturn)
            ScreenState.Stack ->
                LayerAnimValues(scale = 0.93f, blur = 3f, alpha = 0.58f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Notifications ->
                LayerAnimValues(scale = 0.92f, blur = 4f, alpha = 0.52f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Widgets ->
                LayerAnimValues(scale = 0.93f, blur = 3f, alpha = 0.58f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.ControlCenter ->
                LayerAnimValues(scale = 0.94f, blur = 5f, alpha = 0.48f)
                    .shrinkGated(shrinkOnFaceReturn)
        }
    } else {
        when (state) {
            ScreenState.Face -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            // 返回表盘这一程改成与平移档同一套取值（老师指定）。
            //
            // 为什么连去程一起变了：这张表是**按状态**给目标值的，不按方向 —— 同一个
            // `Apps` 值既是「离开表盘去列表」的终点，也是「从列表返回表盘」的起点，
            // 弹簧只是反着跑同一段。所以「只改返回那一程」在这套结构下做不到，改端点
            // 是唯一不产生跳变的做法（起点被静止态钉死，见下面 App 那条的注释）。
            //
            // 旧取值 `scale = 0.90 / blur = 14 / alpha = 0` 的问题正是老师要去掉的那种
            // 观感：表盘从**完全不可见**且重度模糊里长出来。平移档那套（1.22 / 7 / 0.22）
            // 是「从略大一点收回原位」，全程留着 22% 可见度，所以读起来是表盘一直在
            // 那儿、只是退后了一点。
            //
            // 刻意只换这两条：Stack / Notifications / Widgets / ControlCenter 不是
            // 「返回表盘」这一程（那是侧向覆层），应用列表层那张表也一个字没动 ——
            // 老师说了「其他的动画不要改」。
            ScreenState.Apps, ScreenState.Settings ->
                LayerAnimValues(scale = 1.22f, blur = 7f, alpha = 0.22f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.App ->
                LayerAnimValues(scale = 1.28f, blur = 9f, alpha = 0.14f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Stack ->
                LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Notifications ->
                LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.Widgets ->
                LayerAnimValues(scale = 0.85f, blur = 5f, alpha = 0.3f)
                    .shrinkGated(shrinkOnFaceReturn)
            ScreenState.ControlCenter ->
                LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.5f)
                    .shrinkGated(shrinkOnFaceReturn)
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
    motion: FlueTransitionMotion,
    shrinkOnAppReturn: Boolean = true,
    shrinkOnFaceReturn: Boolean = true,
    legacyTransition: Boolean = false,
): LayerAnimValues =
    if (motion == FlueTransitionMotion.SCALE && legacyTransition) {
        when (state) {
            // 老动画返回表盘时不再收缩。以前是缩成一个点（0.2）配合表盘 2.5 倍
            // 冲脸，但列表缩点这一段 spring 往往还没走完就被表盘盖住，看起来是
            // 「闪一下归位」而不是收进去 —— 老师要的是去掉这个收缩，只留淡出
            // 与模糊；表盘那侧的 2.5 倍冲脸是老动画的招牌，保持不动。
            //
            // scale 恒为 1 且无位移，shrinkGated 对它已无事可做，所以不再套。
            ScreenState.Face ->
                LayerAnimValues(scale = 1f, blur = 10f, alpha = 0f)
            ScreenState.Apps -> LayerAnimValues(scale = 1f, blur = 0f, alpha = 1f)
            ScreenState.Settings -> LayerAnimValues(scale = 0.9f, blur = 8f, alpha = 0.3f)
            ScreenState.App ->
                LayerAnimValues(scale = 4f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnAppReturn)
            else ->
                LayerAnimValues(scale = 0.2f, blur = 10f, alpha = 0f).shrinkGated(shrinkOnFaceReturn)
        }
    } else if (motion == FlueTransitionMotion.SLIDE) {
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
    motion: FlueTransitionMotion,
    shrinkOnAppReturn: Boolean = true,
): LayerAnimValues {
    val p = progress.coerceIn(0f, 1f)
    val end = appListLayerValues(
        state = ScreenState.App,
        motion = motion,
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
