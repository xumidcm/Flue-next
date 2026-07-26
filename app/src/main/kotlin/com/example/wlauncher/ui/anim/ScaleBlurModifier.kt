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
     * When non-null this layer gives up its own animation clock and reads these
     * values every frame instead.
     *
     * A launch/return puts three things on screen at once — the launch backdrop
     * (its own `Animatable`), this layer (its own springs) and the edge mask
     * (a third set of springs) — on three different z-levels. Nothing made them
     * agree, and the backdrop is removed outright the frame its progress hits
     * ~0, so whatever the layer underneath happened to be doing became visible
     * mid-flight: leave early and you watch the list still shrinking, leave
     * late and it has already settled. That is the long-standing "jumps
     * straight to the finished state" artifact, and no amount of spec tuning
     * can fix it because the defect is structural.
     *
     * Handing every participant the *same* progress source removes the class of
     * bug. Read inside the `graphicsLayer` draw lambda, so driving it per frame
     * costs no recomposition. The internal animatables keep tracking
     * [targetValues] underneath, so when the driver is withdrawn they are
     * already at the resting values and the hand-off is exact — provided the
     * driver's value at rest equals those resting values.
     */
    valuesProvider: (() -> LayerAnimValues)? = null,
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
    val latestValuesProvider = rememberUpdatedState(valuesProvider)

    // Specs are part of the key: swapping the motion phase mid-transition used
    // to be silently ignored because only targetValues restarted the effect.
    LaunchedEffect(targetValues, scaleSpec, alphaSpec, blurSpec, translationSpec) {
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
            // 外部时钟优先：驱动存在时这一层完全跟随它，内部 spring 仍在
            // 后台收敛到 targetValues，驱动撤走时正好接得上。
            val driven = latestValuesProvider.value?.invoke()
            val scaleValue = driven?.scale ?: animScale.value
            val alphaValue = driven?.alpha ?: animAlpha.value
            val blurValue = driven?.blur ?: animBlur.value
            scaleX = scaleValue
            scaleY = scaleValue
            alpha = alphaValue
            translationX = (driven?.translationX ?: animTransX.value) * screenWidth
            translationY = (driven?.translationY ?: animTransY.value) * screenHeight
            transformOrigin = TransformOrigin(resolvedOrigin.x, resolvedOrigin.y)
            val radiusDp = quantizeSystemBlurRadiusDp(blurValue)
            val radiusPx = with(density) { radiusDp.dp.toPx() }
                .roundToInt()
                .coerceAtLeast(0)
            renderEffect = if (
                shouldRenderLayerBlur(
                    sdkInt = Build.VERSION.SDK_INT,
                    blurEnabled = blurEnabled,
                    alpha = alphaValue,
                    radiusPx = radiusPx,
                )
            ) {
                systemBlurEffectForRadiusPx(radiusPx)
            } else null
        }
}
