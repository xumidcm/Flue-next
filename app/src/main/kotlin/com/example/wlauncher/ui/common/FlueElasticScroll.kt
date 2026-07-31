package com.flue.launcher.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.motion.asFloatAnimationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One shared nested-scroll contract for every vertically scrolling Flue page.
 *
 * The unconsumed delta is transferred into the spring in the same MOVE frame
 * (rather than requiring a second pull after the list stops).  The constants
 * intentionally mirror CloX: pull 0.18 and fling absorb 0.008.
 */
@Stable
internal class FlueElasticScrollState internal constructor(
    private val density: Float,
    private val scope: CoroutineScope,
    private val atTopProvider: () -> Boolean,
    private val atBottomProvider: () -> Boolean,
    private val viewportHeightProvider: () -> Int,
    private val motionProfileProvider: () -> FlueMotionProfile,
) {
    private val offset = FlueElasticOffsetController(scope)
    private var viewportHeightOverride: Int? = null

    val offsetPx: Float
        get() = offset.value

    val maximumOffsetPx: Float
        get() = maximumOffsetPx(motionProfileProvider())

    fun reset() {
        offset.reset()
    }

    fun setViewportHeight(heightPx: Int) {
        viewportHeightOverride = heightPx.coerceAtLeast(0)
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            val motionProfile = motionProfileProvider()
            if (!motionProfile.bounceEnabled || motionProfile.edgeStrength <= 0) {
                if (offset.value != 0f) offset.reset()
                return Offset.Zero
            }
            val current = offset.value
            val recovering = when {
                current > 0f && available.y < 0f -> maxOf(0f, current + available.y)
                current < 0f && available.y > 0f -> minOf(0f, current + available.y)
                else -> current
            }
            if (recovering == current) return Offset.Zero
            offset.setImmediate(recovering)
            return Offset(0f, recovering - current)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val motionProfile = motionProfileProvider()
            if (!motionProfile.bounceEnabled ||
                motionProfile.edgeStrength <= 0 ||
                source != NestedScrollSource.UserInput ||
                available.y == 0f
            ) {
                return Offset.Zero
            }
            val ownsTop = available.y > 0f && atTop()
            val ownsBottom = available.y < 0f && atBottom()
            if (!ownsTop && !ownsBottom) return Offset.Zero
            val maximum = maximumOffsetPx(motionProfile)
            val next = (offset.value + available.y * PULL_FACTOR)
                .coerceIn(-maximum, maximum)
            offset.setImmediate(next)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val motionProfile = motionProfileProvider()
            if (!motionProfile.bounceEnabled || motionProfile.edgeStrength <= 0) {
                if (offset.value != 0f) offset.reset()
                return Velocity.Zero
            }
            if (abs(offset.value) <= 0.01f) {
                return Velocity.Zero
            }
            offset.settle(motionProfile.edgeBounce.asFloatAnimationSpec())
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val motionProfile = motionProfileProvider()
            if (!motionProfile.bounceEnabled || motionProfile.edgeStrength <= 0) {
                if (offset.value != 0f) offset.reset()
                return Velocity.Zero
            }
            val topImpact = available.y > 0f && atTop()
            val bottomImpact = available.y < 0f && atBottom()
            if (!topImpact && !bottomImpact && abs(offset.value) <= 0.01f) {
                return Velocity.Zero
            }
            val direction = when {
                topImpact -> 1f
                bottomImpact -> -1f
                offset.value >= 0f -> 1f
                else -> -1f
            }
            val kick = min(abs(available.y) * ABSORB_VELOCITY_FACTOR, MAX_KICK_DP * density)
            val maximum = maximumOffsetPx(motionProfile)
            offset.setImmediate(
                (offset.value + direction * kick).coerceIn(-maximum, maximum),
            )
            offset.settle(motionProfile.edgeBounce.asFloatAnimationSpec())
            return available
        }
    }

    private fun atTop(): Boolean = atTopProvider()

    private fun atBottom(): Boolean = atBottomProvider()

    private fun maximumOffsetPx(motionProfile: FlueMotionProfile): Float =
        flueElasticMaximumOffsetPx(
            viewportPx = viewportHeightPx(),
            density = density,
            edgeStrength = motionProfile.edgeStrength,
        )

    private fun viewportHeightPx(): Int {
        return (viewportHeightOverride ?: viewportHeightProvider()).coerceAtLeast(0)
    }

    private companion object {
        const val PULL_FACTOR = 0.18f
        const val ABSORB_VELOCITY_FACTOR = 0.008f
        const val MAX_KICK_DP = 40f
    }
}

/**
 * Owns the synchronous value used by placement. Animatable's
 * suspend-only snapTo was previously launched from every MOVE callback, so
 * multiple callbacks observed the same stale value and consumed deltas that
 * were never rendered.  Direct gesture updates now publish immediately;
 * Animatable is reserved for the interruptible settle phase.
 */
@Stable
internal class FlueElasticOffsetController(
    private val scope: CoroutineScope,
) {
    private val offsetState = mutableFloatStateOf(0f)
    private val settleAnimation = Animatable(0f)
    private var settleJob: Job? = null
    private var generation = 0L

    val value: Float
        get() = offsetState.floatValue

    fun setImmediate(value: Float) {
        generation++
        settleJob?.cancel()
        settleJob = null
        offsetState.floatValue = value
    }

    fun reset() {
        setImmediate(0f)
    }

    fun settle(animationSpec: AnimationSpec<Float>) {
        val start = value
        if (abs(start) <= 0.01f) {
            reset()
            return
        }
        val token = ++generation
        settleJob?.cancel()
        settleJob = scope.launch {
            try {
                settleAnimation.snapTo(start)
                settleAnimation.animateTo(
                    targetValue = 0f,
                    animationSpec = animationSpec,
                ) {
                    if (token == generation) {
                        offsetState.floatValue = value
                    }
                }
            } finally {
                if (token == generation) {
                    offsetState.floatValue = 0f
                    settleJob = null
                }
            }
        }
    }
}

/**
 * Pure geometry helpers kept separate so boundary behavior can be tested
 * without constructing a Compose LazyList.
 */
internal fun flueElasticMaximumOffsetPx(
    viewportPx: Int,
    density: Float,
    edgeStrength: Int,
): Float {
    if (viewportPx <= 0 || density <= 0f || edgeStrength <= 0) return 0f
    val strength = (edgeStrength / 5f).coerceIn(0f, 2f)
    return min(48f * density, viewportPx * 0.14f) * strength
}

@Composable
internal fun rememberFlueElasticScrollState(
    listState: LazyListState,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
): FlueElasticScrollState {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val latestMotionProfile = rememberUpdatedState(motionProfile)
    return remember(listState, density, scope) {
        FlueElasticScrollState(
            density = density,
            scope = scope,
            atTopProvider = {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            },
            atBottomProvider = {
                val layout = listState.layoutInfo
                if (layout.totalItemsCount == 0) {
                    true
                } else {
                    val last = layout.visibleItemsInfo.lastOrNull()
                    last != null &&
                        last.index >= layout.totalItemsCount - 1 &&
                        last.offset + last.size <= layout.viewportEndOffset
                }
            },
            viewportHeightProvider = {
                val layout = listState.layoutInfo
                (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
            },
            motionProfileProvider = { latestMotionProfile.value },
        )
    }
}

@Composable
internal fun Modifier.flueElasticNestedScroll(
    listState: LazyListState,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
): Modifier {
    val state = rememberFlueElasticScrollState(listState, motionProfile)
    return nestedScroll(state.connection)
        // Placement-phase offset keeps the same elastic motion without turning the
        // entire scrolling viewport into a persistent full-screen graphics layer.
        // The latter is especially costly on 1200x1920 Wear OS displays because
        // every LazyColumn invalidation has to redraw through that layer.
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, state.offsetPx.roundToInt())
            }
        }
}

@Composable
internal fun rememberFlueElasticScrollState(
    scrollState: ScrollState,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
): FlueElasticScrollState {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val latestMotionProfile = rememberUpdatedState(motionProfile)
    return remember(scrollState, density, scope) {
        FlueElasticScrollState(
            density = density,
            scope = scope,
            atTopProvider = { scrollState.value <= 0 },
            atBottomProvider = { flueScrollStateAtBottom(scrollState.value, scrollState.maxValue) },
            viewportHeightProvider = { 0 },
            motionProfileProvider = { latestMotionProfile.value },
        )
    }
}

/** A non-scrollable page owns both edges so either drag direction still feels responsive. */
internal fun flueScrollStateAtBottom(value: Int, maxValue: Int): Boolean =
    maxValue <= 0 || value >= maxValue

@Composable
internal fun Modifier.flueElasticNestedScroll(
    scrollState: ScrollState,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
): Modifier {
    val state = rememberFlueElasticScrollState(scrollState, motionProfile)
    return onSizeChanged { viewportHeight ->
        // ScrollState does not expose its viewport. Capture it at the same
        // layout boundary so the spring cap remains proportional on watches.
        state.setViewportHeight(viewportHeight.height)
    }
        .nestedScroll(state.connection)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, state.offsetPx.roundToInt())
            }
        }
}
