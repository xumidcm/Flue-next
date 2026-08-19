package com.flue.launcher.ui.style

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * APPLE 档开关 / 滑块共用的「阻尼拖拽」状态机。
 *
 * ## 出处
 *
 * 照 Kyant0 的 `LiquidToggle` / `LiquidSlider`（`DampedDragAnimation`，Apache-2.0）**简化重做**。
 * 弹簧参数、按压放大倍数、速度形变系数、松手时序都按源码取值，因为 iOS 26 那种「按下去
 * 捏一下、跟手拉长、松手先落位再收回」的手感全在这几个数上，改一个就不像了。
 *
 * ## 刻意没带过来的
 *
 * `drawBackdrop` / `blur` / 折射 lens / `Highlight` / `InnerShadow` / backdrop 采样缩放 ——
 * 老师点名「太吃性能」。静止态在源码里本来就是不透明白滑块（`onDrawSurface` 里
 * `alpha = 1 - pressProgress`），所以简化版＝纯白胶囊 + 一点投影，观感损失很小。
 * 另外 `Clock.System.now()`（kotlinx-datetime）不引，时间基准用 Compose 自己的动画时钟；
 * `com.kyant.shapes.Capsule` 换 `CircleShape`（同为「半高圆角」）。
 *
 * ## 为什么把数学抽成纯函数
 *
 * 速度形变的夹取边界、松手时序的「2.5% 邻域」判定、拖过半程的吸附方向，都是**看不见的
 * 规则**：写错了不崩、只是手感不对，而手感在 CI 里没法看。所以这几段全做成纯函数，
 * 由 `FlueDampedDragTest` 钉住（见 `app/src/test/kotlin/.../ui/style/FlueDampedDragTest.kt`）。
 */
object FlueDampedDrag {

    /** 按压时放大到的倍数。iOS 26 的开关按下去滑块会明显涨一圈。 */
    const val PRESSED_SCALE: Float = 1.5f

    /**
     * 松手时序的判定阈值：值动画进入目标的 2.5% 邻域之后，**才**开始收起放大。
     *
     * 顺序反了就是「滑块先缩回原大小、然后才慢慢滑到位」，看着像卡了一下。
     */
    const val SETTLE_FRACTION: Float = 0.025f

    /** 开关的速度归一化除数（源码：`velocity / 50`）。 */
    const val SWITCH_VELOCITY_DIVISOR: Float = 50f

    /** 滑块的速度归一化除数（源码：`velocity / 10`）。滑块行程长，同样手速要更明显的形变。 */
    const val SLIDER_VELOCITY_DIVISOR: Float = 10f

    /**
     * 形变夹取上限：最多拉长 / 压扁 12%。
     *
     * 原来是 20%。老师报「拖动的时候中间那个白块的大小粗细变化有点不流畅」——
     * 幅度越大，同样的速度噪声换算出来的形变跳变就越明显。压到 12% 之后「跟手拉长」还在，
     * 但抖动的可见幅度直接砍掉四成。
     */
    private const val DEFORM_LIMIT: Float = 0.12f

    /**
     * 速度低通系数（指数滑动平均）。
     *
     * 老师报「慢速拖动时白点会抽搐」：`VelocityTracker` 在慢速下输出的是**围绕零抖动的
     * 噪声**，而形变直接读它，于是每个指针事件都把两根弹簧重指到一个新目标，看起来就是
     * 高频抽搐。原版 Kyant 的速度来自拖拽动画自身、本来就是平滑的 —— 我们拿的是原始
     * 触摸速度，所以要自己滤一次。
     */
    const val VELOCITY_SMOOTHING: Float = 0.16f

    /**
     * 形变目标的重指阈值。
     *
     * 差别小于这个值就**不重启弹簧** —— 每事件重启一次会让弹簧永远处在起步段，
     * 反而比不做动画更抖。
     */
    const val DEFORM_EPSILON: Float = 0.02f

    /**
     * 慢速死区：归一化速度绝对值低于此值当零。
     *
     * 慢速拖动本来就不该有拉伸；留一点死区比滤波更直接地解决「几乎不动却在形变」。
     */
    const val VELOCITY_DEADZONE: Float = 0.08f

    /**
     * 松手后收起放大的兜底时限。
     *
     * `release` 原本无条件等「值进入目标 2.5% 邻域」再收放大。可值动画会被外部
     * （上游状态回写、另一次手势、协程取消）打断，一旦它停在邻域外，那个等待就**永远不
     * 返回**，滑块就卡在 1.5 倍 —— 正是老师看到的「开关显示不完全」：放大后的白胶囊
     * 超出轨道、被卡片的 clip 切掉一块。
     *
     * 所以给等待一个上限：正常情况仍是「先落位再收放大」，异常情况最迟 260ms 也一定复位。
     */
    const val SETTLE_TIMEOUT_MS: Long = 260L

    /**
     * 按压时滑块的**最低不透明度**。
     *
     * 老师要的是「按住开关或拖动拉杆时中间那个块块变透明，但**不要模糊** —— 单纯透明加上
     * 边缘阴影描边和高光」。所以只降 alpha，不引入任何 blur / backdrop 采样（那正是当初
     * 从 Kyant 版里刻意去掉的、点名太吃性能的部分）。
     *
     * 0.62 是「明显透出底下的轨道颜色，但滑块本身还是白的」这个平衡点；再低就读成灰块了。
     */
    const val PRESSED_ALPHA: Float = 0.62f

    /** 按压时的边缘暗描边不透明度（「边缘阴影描边」）。 */
    const val PRESSED_EDGE_ALPHA: Float = 0.22f

    /** 按压时顶缘高光的不透明度。 */
    const val PRESSED_HIGHLIGHT_ALPHA: Float = 0.55f

    /** 高光带占滑块高度的比例。只覆顶部一小截，才像一条受光的边而不是一层白纱。 */
    const val PRESSED_HIGHLIGHT_FRACTION: Float = 0.42f

    /**
     * 认方向的阈值：手指要先走这么远，才判断这串手势是「横向拖开关」还是「竖向滚列表」。
     *
     * 老师报「AW 开关太敏感，在它上面竖向刷动都会触发」—— 以前落手就消费事件，方向根本
     * 没参与判断。8dp 与 Compose 自己的 touch slop 同一量级：比它小会把手抖当成拖动，
     * 比它大则点按会有黏滞感。
     */
    val DIRECTION_SLOP_DP: Dp = 8.dp

    /** 值动画：临界阻尼、硬弹簧 —— 值要跟手，不能回弹过冲。 */
    fun <T> valueSpring(visibilityThreshold: T): AnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = visibilityThreshold)

    /** 按压进度。 */
    fun pressSpring(): AnimationSpec<Float> =
        spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)

    /** X 与 Y 的阻尼**刻意不同**（0.6 / 0.7）：一致的话就没有「捏一下」的错位感。 */
    fun scaleXSpring(): AnimationSpec<Float> =
        spring(dampingRatio = 0.6f, stiffness = 250f, visibilityThreshold = 0.001f)

    fun scaleYSpring(): AnimationSpec<Float> =
        spring(dampingRatio = 0.7f, stiffness = 250f, visibilityThreshold = 0.001f)

    /** 速度归一化。死区内直接归零 —— 慢速拖动不该有拉伸。 */
    fun normalizedVelocity(velocityPxPerSecond: Float, divisor: Float): Float {
        if (divisor == 0f) return 0f
        val normalized = velocityPxPerSecond / divisor
        return if (abs(normalized) < VELOCITY_DEADZONE) 0f else normalized
    }

    /** 指数滑动平均：新值只占 [VELOCITY_SMOOTHING]，其余沿用上一次。 */
    fun smoothVelocity(previous: Float, incoming: Float): Float =
        previous + (incoming - previous) * VELOCITY_SMOOTHING

    /**
     * 拉伸：往运动方向拉长。
     *
     * 源码是 `scaleX /= 1 - clamp(v * 0.75)`，所以正速度**除以**一个小于 1 的数 → 变长。
     */
    fun stretchScaleX(normalizedVelocity: Float): Float =
        1f / (1f - (normalizedVelocity * 0.75f).coerceIn(-DEFORM_LIMIT, DEFORM_LIMIT))

    /** 挤压：垂直方向反向压扁，配合拉伸做出「体积守恒」的错觉。 */
    fun squashScaleY(normalizedVelocity: Float): Float =
        1f - (normalizedVelocity * 0.25f).coerceIn(-DEFORM_LIMIT, DEFORM_LIMIT)

    /** 值是否已经落进目标邻域。 */
    fun isSettled(current: Float, target: Float): Boolean =
        abs(current - target) <= SETTLE_FRACTION

    /** 拖过半程就吸附到「开」。 */
    fun snapToOn(fraction: Float): Boolean = fraction >= 0.5f

    /**
     * 有刻度时把连续位置量化到最近一格。
     *
     * `steps` 与 `material3.Slider` 同义：**中间**刻度数，所以总档位是 `steps + 1` 段。
     */
    fun quantize(fraction: Float, steps: Int): Float {
        if (steps <= 0) return fraction.coerceIn(0f, 1f)
        val segments = steps + 1
        return (Math.round(fraction.coerceIn(0f, 1f) * segments).toFloat() / segments)
            .coerceIn(0f, 1f)
    }

    /**
     * 拖了没拖 —— tap 与 drag 的区分。
     *
     * **不设 slop**（源码就是无 slop 的检测），只要指针真的动过就算拖；一点没动才当点按切换。
     * 用「有没有产生过位移事件」判断而不是比阈值，是因为设了阈值之后小幅拖动会被吞成 tap，
     * 手感上像「点了一下却跳到反面」。
     */
    fun isDrag(totalDragPx: Float): Boolean = totalDragPx != 0f
}

/**
 * 一个可拖拽控件的动画状态：值、按压进度、两个方向的形变。
 *
 * 四条动画各自一根弹簧、各自一个协程 —— 合成一根会让形变跟着值的阻尼走，就没有
 * 「值已经到位、形变还在回弹」的层次感了。
 */
class FlueDampedDragState internal constructor(
    initialFraction: Float,
    private val velocityDivisor: Float,
) {
    /** 归一化的值，0..1。 */
    val fraction = Animatable(initialFraction)

    private val press = Animatable(0f)
    private val stretchX = Animatable(1f)
    private val squashY = Animatable(1f)

    /** 平滑后的归一化速度。形变读它，不读原始触摸速度。 */
    private var smoothedVelocity: Float = 0f

    private val baseScale: Float
        get() = 1f + (FlueDampedDrag.PRESSED_SCALE - 1f) * press.value

    val scaleX: Float get() = baseScale * stretchX.value
    val scaleY: Float get() = baseScale * squashY.value

    /**
     * 按压进度 0..1。绘制侧用它插值透明度、描边与高光。
     *
     * 与 [scaleX] / [scaleY] 分开暴露是因为形变会被速度扰动，而「按下去了没有」是一个
     * 干净的 0→1 —— 视觉材质不该跟着速度抖。
     */
    val pressProgress: Float get() = press.value

    /** 按下：放大。 */
    fun press(scope: CoroutineScope) {
        smoothedVelocity = 0f
        scope.launch { press.animateTo(1f, FlueDampedDrag.pressSpring()) }
    }

    /** 拖动中：值直接跟手（不走弹簧），形变按当前速度走。 */
    fun dragTo(scope: CoroutineScope, target: Float, velocityPxPerSecond: Float) {
        scope.launch { fraction.snapTo(target.coerceIn(0f, 1f)) }
        applyVelocity(scope, velocityPxPerSecond)
    }

    fun applyVelocity(scope: CoroutineScope, velocityPxPerSecond: Float) {
        val raw = FlueDampedDrag.normalizedVelocity(velocityPxPerSecond, velocityDivisor)
        smoothedVelocity = FlueDampedDrag.smoothVelocity(smoothedVelocity, raw)
        val targetX = FlueDampedDrag.stretchScaleX(smoothedVelocity)
        val targetY = FlueDampedDrag.squashScaleY(smoothedVelocity)
        // 目标几乎没变就不重启弹簧：每个指针事件重启一次会让弹簧永远停在起步段，
        // 那正是慢速拖动时看到的抽搐。
        if (abs(targetX - stretchX.targetValue) > FlueDampedDrag.DEFORM_EPSILON) {
            scope.launch { stretchX.animateTo(targetX, FlueDampedDrag.scaleXSpring()) }
        }
        if (abs(targetY - squashY.targetValue) > FlueDampedDrag.DEFORM_EPSILON) {
            scope.launch { squashY.animateTo(targetY, FlueDampedDrag.scaleYSpring()) }
        }
    }

    /**
     * 松手：先让值落位，**等它进了 2.5% 邻域**再收起放大与形变。
     */
    fun release(scope: CoroutineScope, target: Float) {
        scope.launch {
            launch { fraction.animateTo(target, FlueDampedDrag.valueSpring(0.001f)) }
            // 等值落位再收放大 —— 但**必须有上限**：值动画被打断而停在邻域外时，
            // 无限等待会把滑块永久留在 1.5 倍（老师看到的「开关显示不完全」）。
            withTimeoutOrNull(FlueDampedDrag.SETTLE_TIMEOUT_MS) {
                snapshotFlow { fraction.value }.first { FlueDampedDrag.isSettled(it, target) }
            }
            collapse(this)
        }
    }

    /**
     * 手势被取消（父级接走、控件离屏、pointerInput 重建）时的复位。
     *
     * 与 [release] 的区别是**不动值**：值该由调用方决定回哪儿，这里只保证放大与形变
     * 一定收回去，不留一个胖着的滑块。
     */
    fun cancelPress(scope: CoroutineScope) {
        scope.launch { collapse(this) }
    }

    private fun collapse(scope: CoroutineScope) {
        smoothedVelocity = 0f
        scope.launch { press.animateTo(0f, FlueDampedDrag.pressSpring()) }
        scope.launch { stretchX.animateTo(1f, FlueDampedDrag.scaleXSpring()) }
        scope.launch { squashY.animateTo(1f, FlueDampedDrag.scaleYSpring()) }
    }

    /** 外部（比如上游状态变了）直接把值动到位，不碰按压态。 */
    fun animateValue(scope: CoroutineScope, target: Float) {
        scope.launch { fraction.animateTo(target, FlueDampedDrag.valueSpring(0.001f)) }
    }
}

@Composable
internal fun rememberFlueDampedDragState(
    initialFraction: Float,
    velocityDivisor: Float,
): FlueDampedDragState = remember { FlueDampedDragState(initialFraction, velocityDivisor) }
