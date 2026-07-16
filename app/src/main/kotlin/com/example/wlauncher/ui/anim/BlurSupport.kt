package com.flue.launcher.ui.anim

import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.skydoves.cloudy.cloudy
import kotlin.math.roundToInt
import android.graphics.RenderEffect as AndroidRenderEffect

internal enum class PlatformBlurBackend {
    NONE,
    SYSTEM_RENDER_EFFECT,
    LEGACY_CAPTURE,
}

internal fun selectPlatformBlurBackend(
    sdkInt: Int,
    enabled: Boolean,
    allowLegacyCaptureBlur: Boolean,
): PlatformBlurBackend = when {
    !enabled -> PlatformBlurBackend.NONE
    sdkInt >= Build.VERSION_CODES.S -> PlatformBlurBackend.SYSTEM_RENDER_EFFECT
    allowLegacyCaptureBlur -> PlatformBlurBackend.LEGACY_CAPTURE
    else -> PlatformBlurBackend.NONE
}

private val SystemBlurRadiusBucketsDp = floatArrayOf(
    0f,
    0.5f,
    1f,
    1.5f,
    2f,
    2.5f,
    3f,
    3.5f,
    4f,
    4.5f,
    5f,
    6f,
    8f,
    10f,
    12f,
    16f,
)

internal fun quantizeSystemBlurRadiusDp(radiusDp: Float): Float {
    if (!radiusDp.isFinite() || radiusDp < 0.5f) return 0f
    var selected = 0f
    for (bucket in SystemBlurRadiusBucketsDp) {
        if (bucket > radiusDp) break
        selected = bucket
    }
    return selected
}

private object SystemBlurEffectCache {
    private val cache = LruCache<Int, ComposeRenderEffect>(32)

    @RequiresApi(Build.VERSION_CODES.S)
    fun get(radiusPx: Int): ComposeRenderEffect = synchronized(cache) {
        cache.get(radiusPx) ?: AndroidRenderEffect.createBlurEffect(
            radiusPx.toFloat(),
            radiusPx.toFloat(),
            Shader.TileMode.CLAMP,
        ).asComposeRenderEffect().also { cache.put(radiusPx, it) }
    }
}

@Composable
fun Modifier.platformBlur(
    blurRadiusDp: Float,
    enabled: Boolean,
    allowLegacyCaptureBlur: Boolean = false,
): Modifier = composed {
    val density = LocalDensity.current
    val radiusDp = quantizeSystemBlurRadiusDp(blurRadiusDp)
    when (
        selectPlatformBlurBackend(
            sdkInt = Build.VERSION.SDK_INT,
            enabled = enabled && radiusDp >= 0.5f,
            allowLegacyCaptureBlur = allowLegacyCaptureBlur,
        )
    ) {
        PlatformBlurBackend.NONE -> this
        PlatformBlurBackend.SYSTEM_RENDER_EFFECT -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                this
            } else {
                val radiusPx = with(density) { radiusDp.dp.toPx() }
                    .roundToInt()
                    .coerceAtLeast(1)
                val blurEffect = remember(radiusPx) {
                    SystemBlurEffectCache.get(radiusPx)
                }
                this.graphicsLayer {
                    renderEffect = blurEffect
                }
            }
        }

        PlatformBlurBackend.LEGACY_CAPTURE -> {
            this.cloudy(radius = radiusDp.roundToInt().coerceAtLeast(1))
        }
    }
}
