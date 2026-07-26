package com.flue.launcher.ui.common

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.anim.quantizeSystemBlurRadiusDp
import com.flue.launcher.ui.anim.systemBlurEffectForRadiusPx
import com.flue.launcher.ui.theme.LauncherTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 覆层「表盘下压」的进度源。
 *
 * 三层覆层（负一屏 / 小组件页 / 控制中心 / 通知中心）在滑动时把 0..1 的进度写进
 * [OverlayBackdropController]，主线把同一个 controller 接到表盘层上，由
 * [com.flue.launcher.ui.common.OverlayBackdropHost] 统一渲染「缩小 + 下沉 + 模糊 +
 * 镜像外扩」。覆层自身不再画不透明底，靠 [overlayGlassColor] 的半透明玻璃透出表盘。
 */
enum class OverlayBackdropSource {
    SmartStack,
    WidgetPage,
    ControlCenter,
    Notification,
}

/**
 * 表盘下压进度的唯一持有者。
 *
 * 三条写入通道，优先级 gesture > host > fallback：
 * - [report]：**主线**逐帧下发的真实进度（`sidePageProgress` / `controlCenterProgress` …）。
 *   一旦某个 source 调用过 [report]，该 source 的 fallback 通道自动让位。
 * - [reportGesture] / [endGesture]：**覆层**在手势里 1:1 写入的跟手进度，抬手后清除。
 * - [reportFallback]：覆层在主线尚未接线时的兜底（由 [OverlayBackdropReporter] 自动喂）。
 *
 * [progress] 是 `mutableFloatStateOf`，可以在 `graphicsLayer {}` 的绘制 lambda 里直接读，
 * 不触发重组。
 */
@Stable
class OverlayBackdropController {

    private val hostValues = mutableStateMapOf<OverlayBackdropSource, Float>()
    private val gestureValues = mutableStateMapOf<OverlayBackdropSource, Float>()
    private val fallbackValues = mutableStateMapOf<OverlayBackdropSource, Float>()
    private val hostDrivenSources = mutableStateMapOf<OverlayBackdropSource, Boolean>()

    /** 聚合后的下压进度，0 = 表盘原样，1 = 完全下压。 */
    var progress by mutableFloatStateOf(0f)
        private set

    /** 是否有覆层正在跟手拖拽（主线可以据此关掉表盘的其它动画）。 */
    var gestureActive by mutableStateOf(false)
        private set

    /** 当前贡献最大进度的覆层。 */
    var activeSource by mutableStateOf<OverlayBackdropSource?>(null)
        private set

    /**
     * 表盘层是否已经被 [com.flue.launcher.ui.common.OverlayBackdropHost] 包起来。
     *
     * 没接线时覆层看到的就是一张**清晰**的表盘（Compose 没有背景模糊，覆层自己
     * 糊不了它下面的东西），所以 [overlayGlassSurface] 会自动补一层兜底压暗，
     * 避免退化成「透明得能看清表盘」。主线接上之后这层兜底自动消失。
     */
    var backdropAttached by mutableStateOf(false)
        private set

    internal fun setBackdropAttached(attached: Boolean) {
        backdropAttached = attached
    }

    /** 主线逐帧下发进度。调用一次之后该 source 的兜底通道永久让位。 */
    fun report(source: OverlayBackdropSource, value: Float) {
        hostDrivenSources[source] = true
        val clamped = value.sanitized()
        if (hostValues[source] == clamped) return
        hostValues[source] = clamped
        recompute()
    }

    /** 覆层手势按下时调用，之后每个 `onDrag` 走 [reportGesture]。 */
    fun beginGesture(source: OverlayBackdropSource, value: Float) {
        gestureValues[source] = value.sanitized()
        gestureActive = true
        recompute()
    }

    /** 1:1 写入跟手进度（不要加阻尼，阻尼只在越界时由调用方自己做）。 */
    fun reportGesture(source: OverlayBackdropSource, value: Float) {
        val clamped = value.sanitized()
        gestureActive = true
        if (gestureValues[source] == clamped) return
        gestureValues[source] = clamped
        recompute()
    }

    /** 抬手 / 手势取消，把控制权交回 [report] 或 [reportFallback]。 */
    fun endGesture(source: OverlayBackdropSource) {
        val removed = gestureValues.remove(source) != null
        gestureActive = gestureValues.isNotEmpty()
        if (removed) recompute()
    }

    /** 覆层离开组合时调用，清掉这一路的全部贡献。 */
    fun release(source: OverlayBackdropSource) {
        val changed = (hostValues.remove(source) != null) or
            (gestureValues.remove(source) != null) or
            (fallbackValues.remove(source) != null)
        hostDrivenSources.remove(source)
        gestureActive = gestureValues.isNotEmpty()
        if (changed) recompute()
    }

    fun reset() {
        hostValues.clear()
        gestureValues.clear()
        fallbackValues.clear()
        hostDrivenSources.clear()
        gestureActive = false
        recompute()
    }

    fun isHostDriven(source: OverlayBackdropSource): Boolean = hostDrivenSources[source] == true

    internal fun reportFallback(source: OverlayBackdropSource, value: Float) {
        if (isHostDriven(source)) return
        val clamped = value.sanitized()
        if (fallbackValues[source] == clamped) return
        fallbackValues[source] = clamped
        recompute()
    }

    private fun recompute() {
        var best = 0f
        var bestSource: OverlayBackdropSource? = null
        for (source in OverlayBackdropSource.entries) {
            val value = gestureValues[source]
                ?: hostValues[source]
                ?: fallbackValues[source]
                ?: continue
            if (value > best) {
                best = value
                bestSource = source
            }
        }
        progress = best
        activeSource = bestSource
    }

    private fun Float.sanitized(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
}

val LocalOverlayBackdrop = staticCompositionLocalOf { OverlayBackdropController() }

@Composable
fun rememberOverlayBackdropController(): OverlayBackdropController =
    remember { OverlayBackdropController() }

/**
 * 覆层的兜底进度上报：主线还没接 [OverlayBackdropController.report] 时，进入 / 离开各跑
 * 一次 [OverlaySettleSpec]，保证「表盘下压」在只改覆层的情况下也能看见。主线一旦调用过
 * [OverlayBackdropController.report]，这里自动闭嘴。
 */
@Composable
fun OverlayBackdropReporter(
    source: OverlayBackdropSource,
    visible: Boolean = true,
    controller: OverlayBackdropController = LocalOverlayBackdrop.current,
) {
    val fallback = remember(controller, source) { Animatable(0f) }
    LaunchedEffect(controller, source, visible) {
        fallback.animateTo(if (visible) 1f else 0f, OverlaySettleSpec)
    }
    val value = fallback.value
    SideEffect { controller.reportFallback(source, value) }
    DisposableEffect(controller, source) {
        onDispose { controller.release(source) }
    }
}

object OverlayBackdropDefaults {
    /** 满进度时表盘缩到的比例。 */
    const val MinScale = 0.84f

    /** 满进度时表盘下沉的高度占比（正数 = 向下）。 */
    const val SinkFraction = 0.055f

    /** 满进度时表盘的模糊半径（dp，下发前经 [quantizeSystemBlurRadiusDp] 分桶）。 */
    const val MaxBlurDp = 18f

    /** 压暗层最大不透明度。**必须是独立的黑色 scrim**，不能压在镜像层上。 */
    const val MaxScrimAlpha = 0.38f

    /**
     * 镜像层的外扩倍率。**必须显著大于 1**：blur 会把图层自身边缘采样成半透明，
     * 镜像层不外扩就会在裁剪边缘露出底色 —— 这正是「黑边」的成因。
     */
    const val MirrorOverscan = 1.62f

    /** 镜像层在表盘模糊之上再叠的固定模糊量，保证外圈完全糊掉、看不出是同一张图。 */
    const val MirrorBlurBoostDp = 12f
}

/** smoothstep，保证 0/1 两端导数为 0，跟手时不会在起手瞬间抖一下。 */
fun overlayBackdropEase(progress: Float): Float {
    if (!progress.isFinite()) return 0f
    val p = progress.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

fun overlayBackdropScale(progress: Float): Float =
    1f + (OverlayBackdropDefaults.MinScale - 1f) * overlayBackdropEase(progress)

fun overlayBackdropSinkPx(progress: Float, heightPx: Float): Float =
    overlayBackdropEase(progress) * OverlayBackdropDefaults.SinkFraction * heightPx

fun overlayBackdropBlurDp(progress: Float): Float =
    overlayBackdropEase(progress) * OverlayBackdropDefaults.MaxBlurDp

fun overlayBackdropScrimAlpha(progress: Float): Float =
    overlayBackdropEase(progress) * OverlayBackdropDefaults.MaxScrimAlpha

fun overlayMirrorBlurDp(progress: Float): Float =
    OverlayBackdropDefaults.MirrorBlurBoostDp + overlayBackdropBlurDp(progress)

/**
 * 把「缩小 + 下沉 + 模糊」一次性写进 `graphicsLayer` 的绘制 lambda。
 * 三个量全部是 [progress] 的纯函数 —— 不允许再各跑各的 Animatable。
 */
fun GraphicsLayerScope.applyOverlayBackdropPress(
    progress: Float,
    blurEnabled: Boolean = true,
) {
    val scale = overlayBackdropScale(progress)
    scaleX = scale
    scaleY = scale
    translationY = overlayBackdropSinkPx(progress, size.height)
    renderEffect = if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radiusPx = quantizeSystemBlurRadiusDp(overlayBackdropBlurDp(progress)).dp
            .toPx()
            .roundToInt()
        if (radiusPx > 0) systemBlurEffectForRadiusPx(radiusPx) else null
    } else {
        null
    }
}

/**
 * 表盘下压的最小接法：直接挂在表盘层上（没有镜像外扩，只有缩放 / 下沉 / 模糊）。
 * 想要镜像外扩填充请改用 [OverlayBackdropHost]。
 */
fun Modifier.overlayBackdropPress(
    controller: OverlayBackdropController,
    blurEnabled: Boolean = true,
): Modifier = this.graphicsLayer {
    applyOverlayBackdropPress(controller.progress, blurEnabled)
}

// ---------------------------------------------------------------------------
// 跟手手势：1:1 拖拽 + 带初速度的收尾
// ---------------------------------------------------------------------------

/** 超过这个速度（进度单位 / 秒）直接按方向定向，不再看位置。 */
const val OVERLAY_FLING_PROGRESS_VELOCITY = 2.2f

/** 抬手时用速度外推的时间窗（秒）。 */
const val OVERLAY_VELOCITY_HORIZON_SECONDS = 0.18f

/** 收尾弹簧：全项目覆层统一这一档，禁止再写固定 tween。 */
val OverlaySettleSpec: SpringSpec<Float> = spring(dampingRatio = 0.86f, stiffness = 380f)

/** `projected = progress + velocity * 0.18f`。[velocity] 单位是「进度 / 秒」。 */
fun projectOverlayProgress(
    progress: Float,
    velocity: Float,
    horizonSeconds: Float = OVERLAY_VELOCITY_HORIZON_SECONDS,
): Float {
    if (!progress.isFinite()) return 0f
    val v = if (velocity.isFinite()) velocity else 0f
    return (progress + v * horizonSeconds).coerceIn(0f, 1f)
}

/** 抬手定向：速度够大按方向走，否则看外推位置过没过半。 */
fun overlayReleaseTarget(progress: Float, velocity: Float): Float {
    val v = if (velocity.isFinite()) velocity else 0f
    if (abs(v) > OVERLAY_FLING_PROGRESS_VELOCITY) return if (v > 0f) 1f else 0f
    return if (projectOverlayProgress(progress, v) > 0.5f) 1f else 0f
}

/** 把像素速度换算成进度速度；[rangePx] 是该手势跑满 0→1 所需的位移。 */
fun overlayVelocityToProgress(velocityPxPerSecond: Float, rangePx: Float): Float {
    if (rangePx <= 0f || !velocityPxPerSecond.isFinite()) return 0f
    return velocityPxPerSecond / rangePx
}

// ---------------------------------------------------------------------------
// 覆层表面：半透明玻璃 + 圆屏适配
// ---------------------------------------------------------------------------

/** 覆层根背景。**不是不透明底** —— 下面的表盘必须透出来。 */
@Composable
@ReadOnlyComposable
fun overlayGlassColor(): Color {
    val background = LauncherTheme.style.screenBackground
    return if (background.luminance() < 0.45f) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.34f)
    }
}

/** 编辑态用的更实一点的玻璃（仅用于指示「可拖拽」）。 */
@Composable
@ReadOnlyComposable
fun overlayGlassEditColor(): Color {
    val background = LauncherTheme.style.screenBackground
    return if (background.luminance() < 0.45f) {
        Color.White.copy(alpha = 0.06f)
    } else {
        Color.Black.copy(alpha = 0.05f)
    }
}

/** 覆层根裁剪形状：圆屏走整圆，方屏走 panelShape。 */
@Composable
@ReadOnlyComposable
fun overlayClipShape(): Shape =
    if (isFlueScreenRound()) CircleShape else LauncherTheme.style.panelShape

/**
 * 主线未接 [OverlayBackdropHost] 时，覆层自己补的压暗量。
 *
 * 目标观感是「表盘的模糊版本」；模糊只能由表盘层自己做，覆层能做的只有压暗。
 * 接上 host 之后这一层归零，压暗改由 [OverlayBackdropDefaults.MaxScrimAlpha] 提供。
 */
private const val OVERLAY_UNATTACHED_SCRIM_ALPHA = 0.46f

/**
 * 覆层根表面：半透明玻璃 + 兜底压暗。
 *
 * 三层覆层的根 `Modifier` 一律走这个，禁止再写 `.background(screenBackground)`。
 */
@Composable
fun Modifier.overlayGlassSurface(
    controller: OverlayBackdropController = LocalOverlayBackdrop.current,
): Modifier {
    val glass = overlayGlassColor()
    val scrimBase = LauncherTheme.style.screenBackground
    return this.drawBehind {
        if (!controller.backdropAttached) {
            drawRect(color = scrimBase, alpha = OVERLAY_UNATTACHED_SCRIM_ALPHA)
        }
        drawRect(color = glass)
    }
}
