package com.flue.launcher.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.motion.asFloatAnimationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 下拉刷新的手势与位移状态。
 *
 * 不用 material3 的 PullToRefreshBox：那套指示器自带 MD3 的配色与形状，和
 * Flue 的三套 UiStyle 对不上，而且它会自己接管顶端 overscroll，与
 * [FlueElasticScrollState] 抢同一份 available.y。这里只做位移账本，指示器
 * 外观交给调用方用 style token 画。
 *
 * 关键分工：本连接只吃「顶端向下」的未消费位移，其余方向一律放回去，
 * 底端回弹仍由 [FlueElasticScrollState] 负责，两者可叠加在同一个列表上。
 */
@Stable
internal class FluePullToRefreshState internal constructor(
    private val scope: CoroutineScope,
    private val atTopProvider: () -> Boolean,
    private val thresholdPx: Float,
    private val onRefresh: () -> Unit,
    private val motionProfileProvider: () -> FlueMotionProfile,
) {
    private val offsetState = mutableFloatStateOf(0f)
    private val settleAnimation = Animatable(0f)
    private var settleJob: Job? = null
    private var generation = 0L
    private var armed = false

    /** 外部驱动的刷新中标志：请求真正结束前指示器保持停靠。 */
    var refreshing: Boolean by mutableStateOf(false)
        internal set

    /** 当前下拉位移（px），已含阻尼，永不为负。 */
    val offsetPx: Float
        get() = offsetState.floatValue

    /** 0..1 的下拉进度，用于指示器的透明度与旋转。 */
    val progress: Float
        get() = if (thresholdPx <= 0f) 0f else (offsetPx / thresholdPx).coerceIn(0f, 1f)

    /** 越过阈值：松手就会触发刷新。 */
    val willRefresh: Boolean
        get() = offsetPx >= thresholdPx

    private val maximumPx: Float
        get() = thresholdPx * MAX_OVERSHOOT

    internal fun onRefreshFinished() {
        if (!refreshing) return
        refreshing = false
        settleTo(0f)
    }

    private fun setImmediate(value: Float) {
        generation++
        settleJob?.cancel()
        settleJob = null
        offsetState.floatValue = value
    }

    private fun settleTo(target: Float) {
        val start = offsetPx
        if (abs(start - target) <= 0.01f) {
            setImmediate(target)
            return
        }
        val token = ++generation
        settleJob?.cancel()
        val spec = motionProfileProvider().edgeBounce.asFloatAnimationSpec()
        settleJob = scope.launch {
            try {
                settleAnimation.snapTo(start)
                settleAnimation.animateTo(targetValue = target, animationSpec = spec) {
                    if (token == generation) offsetState.floatValue = value
                }
            } finally {
                if (token == generation) {
                    offsetState.floatValue = target
                    settleJob = null
                }
            }
        }
    }

    /** 松手判定：越过阈值就停在阈值处并发起刷新，否则收回。 */
    private fun release() {
        if (!armed) return
        armed = false
        if (refreshing) {
            settleTo(thresholdPx)
            return
        }
        if (willRefresh) {
            refreshing = true
            settleTo(thresholdPx)
            onRefresh()
        } else {
            settleTo(0f)
        }
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            // 向上滑动时先把已有的下拉量还回去，之后才轮到列表滚动。
            // 刷新中停靠的那段位移不参与回收，否则指示器会被滑走。
            val floor = if (refreshing) thresholdPx else 0f
            val current = offsetPx
            if (available.y >= 0f || current <= floor) return Offset.Zero
            val next = (current + available.y).coerceAtLeast(floor)
            if (next == current) return Offset.Zero
            setImmediate(next)
            return Offset(0f, next - current)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y <= 0f || !atTopProvider()) return Offset.Zero
            val current = offsetPx
            val next = (current + available.y * PULL_FACTOR).coerceAtMost(maximumPx)
            if (next == current) return Offset.Zero
            armed = true
            setImmediate(next)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!armed) return Velocity.Zero
            val consumed = offsetPx > 0f
            release()
            // 吃掉这次 fling，避免松手瞬间列表再借着速度滚一段。
            return if (consumed) available else Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            release()
            return Velocity.Zero
        }
    }

    private companion object {
        /** 与 FlueElasticScrollState 同源的阻尼系数，手感一致。 */
        const val PULL_FACTOR = 0.42f

        /** 允许拉过阈值的倍数，给「已经够了」一点余量反馈。 */
        const val MAX_OVERSHOOT = 1.7f
    }
}

@Composable
internal fun rememberFluePullToRefreshState(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    atTop: () -> Boolean,
    threshold: Dp = FLUE_PULL_REFRESH_THRESHOLD,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
): FluePullToRefreshState {
    val scope = rememberCoroutineScope()
    val latestMotionProfile = rememberUpdatedState(motionProfile)
    val latestOnRefresh = rememberUpdatedState(onRefresh)
    val latestAtTop = rememberUpdatedState(atTop)
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    val state = remember(scope, thresholdPx) {
        FluePullToRefreshState(
            scope = scope,
            atTopProvider = { latestAtTop.value() },
            thresholdPx = thresholdPx,
            onRefresh = { latestOnRefresh.value() },
            motionProfileProvider = { latestMotionProfile.value },
        )
    }
    // 请求结束由外部 refreshing 标志宣告：收到 false 才把指示器收回去。
    // 直接把外部值写进 state 会在下拉那一帧被覆盖，所以只处理下降沿。
    if (!refreshing && state.refreshing) state.onRefreshFinished()
    return state
}

internal val FLUE_PULL_REFRESH_THRESHOLD: Dp = 56.dp

/** 让内容跟着下拉走；指示器由调用方叠在上面。 */
internal fun Modifier.fluePullToRefresh(state: FluePullToRefreshState): Modifier =
    nestedScroll(state.connection)
        .graphicsLayer { translationY = state.offsetPx }
