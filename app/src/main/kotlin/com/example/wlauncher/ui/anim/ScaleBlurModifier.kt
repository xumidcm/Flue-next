package com.flue.launcher.ui.anim

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal sealed interface LayerPivot {
    data object Center : LayerPivot

    data class LaunchIcon(val point: Offset) : LayerPivot
}

internal fun LayerPivot.resolve(): Offset = when (this) {
    LayerPivot.Center -> Offset(0.5f, 0.5f)
    is LayerPivot.LaunchIcon -> point.normalizedOrNull() ?: Offset(0.5f, 0.5f)
}

/**
 * Keeps a launch-origin pivot stable while the surrounding Compose tree swaps
 * between face, drawer, and external-app states. A transient `null` origin is
 * deliberately not interpreted as a request to jump back to the center.
 */
internal class LayerPivotLatch(
    initial: Offset = Offset(0.5f, 0.5f),
) {
    private var retained = initial.normalizedOrNull() ?: Offset(0.5f, 0.5f)

    fun resolve(requested: LayerPivot?): Offset {
        requested?.resolve()?.let { retained = it }
        return retained
    }
}

/**
 * Draw-phase snapshot of the values that are actually installed on a layer.
 *
 * This is deliberately plain mutable state rather than Compose state: the
 * backdrop reads it from its draw modifier and must not recompose the launcher
 * at animation cadence. It also gives the backdrop the spring's real frame,
 * instead of trying to reproduce the same motion with a second clock.
 */
internal class LayerFrameSnapshot {
    var scale: Float = 1f
        private set
    var blur: Float = 0f
        private set
    var alpha: Float = 1f
        private set
    var translationX: Float = 0f
        private set
    var translationY: Float = 0f
        private set

    fun update(
        scale: Float,
        blur: Float,
        alpha: Float,
        translationX: Float,
        translationY: Float,
    ) {
        this.scale = scale
        this.blur = blur
        this.alpha = alpha
        this.translationX = translationX
        this.translationY = translationY
    }

    /**
     * Normalized amount of background that needs edge extension. A zoomed
     * layer still covers the viewport, so blur/alpha alone must not allocate a
     * second recorded layer. Shrink or translation activates the fill, while
     * blur contributes to its visual strength once it is needed.
     */
    fun backdropProgress(): Float {
        val shrink = (1f - scale).coerceAtLeast(0f)
        val movement = max(abs(translationX), abs(translationY))
        if (shrink <= 0.001f && movement <= 0.001f) return 0f
        return max(
            max(shrink / 0.15f, movement / 0.07f),
            blur.coerceAtLeast(0f) / 24f,
        ).coerceIn(0f, 1f)
    }
}

private fun Offset.normalizedOrNull(): Offset? =
    if (x.isFinite() && y.isFinite()) {
        Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    } else {
        null
    }

/**
 * A transparent full-screen layer must not keep an expensive RenderEffect
 * attached. The logical blur animation continues in [Animatable], so the
 * effect can be restored from the correct bucket as soon as the layer becomes
 * visible again without introducing a sharp transition frame.
 */
internal fun shouldRenderLayerBlur(
    sdkInt: Int,
    blurEnabled: Boolean,
    alpha: Float,
    radiusPx: Int,
): Boolean =
    sdkInt >= Build.VERSION_CODES.S &&
        blurEnabled &&
        alpha.isFinite() &&
        alpha > 0.01f &&
        radiusPx > 0

@Composable
internal fun Modifier.scaleBlurAlpha(
    targetValues: LayerAnimValues,
    screenWidth: Float = 0f,
    screenHeight: Float = 0f,
    blurEnabled: Boolean = true,
    origin: LayerPivot? = null,
    scaleSpec: FiniteAnimationSpec<Float> = TransitionSpring,
    alphaSpec: FiniteAnimationSpec<Float> = AlphaSpec,
    blurSpec: FiniteAnimationSpec<Float> = AlphaSpec,
    translationSpec: FiniteAnimationSpec<Float> = TransitionSpring,
    /**
     * Invoked once every animatable has physically reached [targetValues] —
     * i.e. when the spring has actually settled, not when a wall-clock guess
     * says it should have. Layer release (z-order demotion, effect restoration)
     * must key off this: releasing on a fixed `delay(durationMillis)` while a
     * spring is still 10% from its target is exactly the "snaps into place on
     * the last frame" artifact.
     */
    onSettled: (() -> Unit)? = null,
    /**
     * External per-frame clock. While [externallyDriven] is true the values
     * returned by this lambda replace the internal springs entirely — the
     * layer is read in the draw phase every frame with zero recomposition.
     * This is how the launch mask, list layer and edge mask share one
     * progress source instead of three unsynchronised springs (the root of
     * the "return shrink jumps straight to the end state" artifact).
     *
     * Contract: at progress 0 the provider must return exactly the resting
     * state of the layer, so the hand-back below is seamless.
     */
    valuesProvider: (() -> LayerAnimValues)? = null,
    externallyDriven: Boolean = false,
    /** Receives the exact draw-frame values without causing recomposition. */
    frameSnapshot: LayerFrameSnapshot? = null,
): Modifier {
    val animScale = remember { Animatable(targetValues.scale) }
    val animAlpha = remember { Animatable(targetValues.alpha) }
    val animBlur = remember { Animatable(targetValues.blur) }
    val animTransX = remember { Animatable(targetValues.translationX) }
    val animTransY = remember { Animatable(targetValues.translationY) }
    val originLatch = remember { LayerPivotLatch() }
    val resolvedOrigin = originLatch.resolve(origin)
    val density = LocalDensity.current
    val latestOnSettled = rememberUpdatedState(onSettled)
    val latestProvider = rememberUpdatedState(valuesProvider)
    val latestDriven = rememberUpdatedState(externallyDriven)
    val wasExternallyDriven = remember { androidx.compose.runtime.mutableStateOf(false) }

    // Specs are part of the key: swapping the motion phase mid-transition used
    // to be silently ignored because only targetValues restarted the effect.
    LaunchedEffect(targetValues, scaleSpec, alphaSpec, blurSpec, translationSpec, externallyDriven) {
        if (externallyDriven) {
            // The external clock owns the frame values; the springs stay parked
            // so the hand-back below starts from the provider's end point.
            wasExternallyDriven.value = true
            return@LaunchedEffect
        }
        if (wasExternallyDriven.value) {
            wasExternallyDriven.value = false
            // Seamless hand-back: seed the springs with the external clock's
            // final frame before resuming normal target pursuit.
            latestProvider.value?.invoke()?.let { seed ->
                animScale.snapTo(seed.scale)
                animAlpha.snapTo(seed.alpha)
                animBlur.snapTo(seed.blur)
                animTransX.snapTo(seed.translationX)
                animTransY.snapTo(seed.translationY)
            }
        }
        val jobs = listOf(
            launch { animScale.animateTo(targetValues.scale, scaleSpec) },
            launch { animAlpha.animateTo(targetValues.alpha, alphaSpec) },
            launch {
                // Keep the logical blur clock running even when rendering is
                // temporarily suppressed by an opaque launch mask. Snapping the
                // Animatable to zero there made the return frame lose its blur and
                // produced a visible sharp flash.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    animBlur.animateTo(targetValues.blur, blurSpec)
                } else {
                    animBlur.snapTo(0f)
                }
            },
            launch { animTransX.animateTo(targetValues.translationX, translationSpec) },
            launch { animTransY.animateTo(targetValues.translationY, translationSpec) },
        )
        jobs.joinAll()
        latestOnSettled.value?.invoke()
    }

    return this
        .graphicsLayer {
            val external = if (latestDriven.value) latestProvider.value?.invoke() else null
            val frameScale = external?.scale ?: animScale.value
            val frameAlpha = external?.alpha ?: animAlpha.value
            val frameBlur = external?.blur ?: animBlur.value
            val frameTranslationX = external?.translationX ?: animTransX.value
            val frameTranslationY = external?.translationY ?: animTransY.value
            frameSnapshot?.update(
                scale = frameScale,
                blur = frameBlur,
                alpha = frameAlpha,
                translationX = frameTranslationX,
                translationY = frameTranslationY,
            )
            scaleX = frameScale
            scaleY = frameScale
            alpha = frameAlpha
            translationX = frameTranslationX * screenWidth
            translationY = frameTranslationY * screenHeight
            transformOrigin = TransformOrigin(resolvedOrigin.x, resolvedOrigin.y)
            val radiusDp = quantizeSystemBlurRadiusDp(frameBlur)
            val radiusPx = with(density) { radiusDp.dp.toPx() }
                .roundToInt()
                .coerceAtLeast(0)
            renderEffect = if (
                shouldRenderLayerBlur(
                    sdkInt = Build.VERSION.SDK_INT,
                    blurEnabled = blurEnabled,
                    alpha = frameAlpha,
                    radiusPx = radiusPx,
                )
            ) {
                systemBlurEffectForRadiusPx(radiusPx)
            } else null
        }
}
