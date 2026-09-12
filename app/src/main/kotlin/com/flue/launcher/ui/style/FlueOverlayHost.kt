package com.flue.launcher.ui.style

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.HapticFeedbackConstants
import android.view.PixelCopy
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.anim.systemBlurEffectForRadiusPx
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.prompt.LegacyPromptCaptureGate
import com.flue.launcher.ui.prompt.PromptBackgroundEffect
import com.flue.launcher.ui.prompt.PromptForegroundMotion
import com.flue.launcher.ui.prompt.PromptTextEffect
import com.flue.launcher.ui.prompt.legacyPromptCaptureSize
import com.flue.launcher.ui.prompt.promptForegroundMotion
import com.flue.launcher.ui.prompt.promptVisualEffectPolicy
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideDialogUiScale
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val PROMPT_CAPTURE_TIMEOUT_MILLIS = 150L
private const val PROMPT_BLUR_PASSES = 3
private const val PROMPT_BLUR_RADIUS = 5

internal class FlueOverlayEntry(
    val id: Any,
    val onDismissRequest: () -> Unit,
    val dismissOnBackPress: Boolean,
    val uiScale: Float,
    val modifier: Modifier,
    val maxHeightFraction: Float,
    val scrollableContent: Boolean,
    val fullScreen: Boolean = false,
    val textReveal: Boolean = true,
    val content: @Composable ColumnScope.() -> Unit,
)

@Stable
internal class FlueOverlayController {
    val entries = mutableStateListOf<FlueOverlayEntry>()
    var chainOpen by mutableStateOf(false)
        private set
    var chainToken by mutableLongStateOf(0L)
        private set

    fun register(entry: FlueOverlayEntry) {
        if (!chainOpen) {
            chainOpen = true
            chainToken += 1L
        }
        entries.removeAll { it.id === entry.id }
        entries += entry
    }

    fun unregister(id: Any) {
        entries.removeAll { it.id === id }
    }

    fun finishEmptyChain() {
        if (entries.isEmpty()) chainOpen = false
    }
}

internal val LocalFlueOverlayController = compositionLocalOf<FlueOverlayController?> { null }
val LocalFlueOverlayActive = compositionLocalOf { false }
val LocalFlueOverlayPresentationProgress = compositionLocalOf { 1f }
internal val LocalFlueOverlayTextEffect = compositionLocalOf { PromptTextEffect.FADE }
internal val LocalFluePromptMotion = compositionLocalOf { promptForegroundMotion(reduceMotion = false) }

@Suppress("UNUSED_PARAMETER")
internal fun shouldRevealOverlayContent(
    fullScreen: Boolean,
    textReveal: Boolean,
    effect: PromptTextEffect,
): Boolean = textReveal && effect == PromptTextEffect.BLUR_RISE

/**
 * Same-window host for transient prompts. It keeps the page and prompt in one
 * Compose tree, which lets API 31+ blur the actual background layer instead of
 * trying to blur across two platform windows.
 */
@Composable
fun FlueOverlayHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val controller = remember { FlueOverlayController() }
    val defaultPreferencePair =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) to false
    val preferenceFlow = remember(context) {
        context.dataStore.data
            .map { preferences ->
                (preferences[LauncherViewModel.KEY_BLUR]
                    ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) to
                    (preferences[LauncherViewModel.KEY_REDUCE_DYNAMIC_EFFECTS] ?: false)
            }
            .catch { emit((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) to false) }
    }
    val preferencePair by preferenceFlow
        .collectAsState(initial = defaultPreferencePair)
    val advancedMaterialEnabled = preferencePair.first
    val reduceMotion = preferencePair.second
    val policy = remember(advancedMaterialEnabled, reduceMotion) {
        promptVisualEffectPolicy(
            sdkInt = Build.VERSION.SDK_INT,
            advancedMaterial = advancedMaterialEnabled,
            reduceMotion = reduceMotion,
        )
    }
    val promptMotion = remember(reduceMotion) { promptForegroundMotion(reduceMotion) }
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var legacyBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var foregroundReady by remember { mutableStateOf(false) }
    var capturedChainToken by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var renderedEntry by remember { mutableStateOf<FlueOverlayEntry?>(null) }
    var foregroundVisible by remember { mutableStateOf(false) }
    val backdropProgress = remember { Animatable(0f) }
    val captureGate = remember { LegacyPromptCaptureGate() }
    val chainOpen = controller.chainOpen
    val chainToken = controller.chainToken
    val topEntry = controller.entries.lastOrNull()

    LaunchedEffect(chainToken, chainOpen, policy.background, hostSize) {
        if (!chainOpen || hostSize.width <= 0 || hostSize.height <= 0) return@LaunchedEffect
        foregroundReady = false
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when (policy.background) {
            PromptBackgroundEffect.SYSTEM_RENDER_EFFECT,
            PromptBackgroundEffect.DIM_ONLY,
            -> foregroundReady = true

            PromptBackgroundEffect.PIXEL_COPY -> {
                if (capturedChainToken == chainToken) {
                    foregroundReady = true
                    return@LaunchedEffect
                }
                capturedChainToken = chainToken
                val requestedToken = captureGate.beginChain()
                val activity = context.findActivity() ?: view.context.findActivity()
                val captured = withTimeoutOrNull(PROMPT_CAPTURE_TIMEOUT_MILLIS) {
                    activity?.window?.let { window ->
                        captureAndBlurPrompt(window, hostSize)
                    }
                }
                if (
                    captured != null &&
                    controller.chainOpen &&
                    chainToken == controller.chainToken &&
                    captureGate.shouldAccept(requestedToken, foregroundStarted = foregroundReady)
                ) {
                    val previous = legacyBitmap
                    legacyBitmap = captured
                    captureGate.markCaptured(requestedToken)
                    previous?.retireAfterTwoFrames()
                } else {
                    captured?.retireAfterTwoFrames()
                }
                foregroundReady = true
            }
        }
    }

    LaunchedEffect(chainOpen) {
        if (chainOpen) return@LaunchedEffect
        foregroundReady = false
        capturedChainToken = Long.MIN_VALUE
        captureGate.invalidate()
        val retired = legacyBitmap
        legacyBitmap = null
        retired?.retireAfterTwoFrames()
    }

    LaunchedEffect(topEntry?.id, foregroundReady, chainToken, policy.exitDurationMillis) {
        val next = topEntry
        if (next != null && foregroundReady) {
            renderedEntry = next
            foregroundVisible = true
            return@LaunchedEffect
        }
        if (next == null && chainOpen) {
            // Keep both the frozen background and the last foreground alive until
            // the fade-out completes. A replacement registered during this delay
            // cancels the effect and reuses the same capture chain.
            foregroundVisible = false
            delay(policy.exitDurationMillis.toLong())
            if (controller.entries.isEmpty()) {
                renderedEntry = null
                controller.finishEmptyChain()
            }
        }
    }

    LaunchedEffect(chainOpen, foregroundReady, topEntry?.id, policy) {
        backdropProgress.animateTo(
            targetValue = if (chainOpen && foregroundReady && topEntry != null) 1f else 0f,
            animationSpec = tween(
                if (chainOpen && foregroundReady && topEntry != null) {
                    policy.enterDurationMillis
                } else {
                    policy.exitDurationMillis
                },
            ),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            captureGate.invalidate()
            legacyBitmap?.let(::retireBitmapAfterTwoVsyncs)
        }
    }

    val maxBlurRadiusPx = with(LocalDensity.current) { 26.dp.toPx().roundToInt().coerceAtLeast(1) }
    val backgroundModifier = when {
        chainOpen && policy.background == PromptBackgroundEffect.SYSTEM_RENDER_EFFECT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Modifier.graphicsLayer {
                val radiusPx = (maxBlurRadiusPx * backdropProgress.value).roundToInt()
                renderEffect = if (radiusPx > 0) systemBlurEffectForRadiusPx(radiusPx) else null
            }
        else -> Modifier
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalFlueOverlayController provides controller,
        LocalFlueOverlayActive provides chainOpen,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { hostSize = it },
        ) {
            Box(modifier = Modifier.fillMaxSize().then(backgroundModifier)) {
                content()
            }

            if (chainOpen) {
                Box(
                    Modifier.fillMaxSize(),
                ) {
                    // Block the source page from the moment capture begins. On
                    // API 27-30 this remains transparent until PixelCopy has
                    // sampled the clean page, so the frozen frame is not polluted.
                    FlueOverlayInputBlocker(chainToken)
                    val frozen = legacyBitmap
                    if (foregroundReady && frozen != null && !frozen.isRecycled) {
                        val image = remember(frozen) { frozen.asImageBitmap() }
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = backdropProgress.value },
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                    if (foregroundReady) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = backdropProgress.value }
                                .background(Color.Black.copy(alpha = promptScrimAlpha(LauncherTheme.style.uiStyle))),
                        )
                        renderedEntry?.let { entry ->
                            FlueOverlayForeground(
                                entry = entry,
                                policy = policy,
                                motion = promptMotion,
                                visible = foregroundVisible,
                            )
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = topEntry != null) {
        topEntry?.let { entry ->
            if (entry.dismissOnBackPress) entry.onDismissRequest()
        }
    }
}

@Composable
private fun FlueOverlayForeground(
    entry: FlueOverlayEntry,
    policy: com.flue.launcher.ui.prompt.PromptVisualEffectPolicy,
    motion: PromptForegroundMotion,
    visible: Boolean,
) {
    val round = isFlueScreenRound()
    val contentScrollState = rememberScrollState()
    val fullScreenScrollState = rememberScrollState()
    val progress = remember(entry.id) { Animatable(0f) }
    LaunchedEffect(entry.id, policy, visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                if (visible) policy.enterDurationMillis else policy.exitDurationMillis,
            ),
        )
    }
    val revealWholeContent = shouldRevealOverlayContent(
        fullScreen = entry.fullScreen,
        textReveal = entry.textReveal,
        effect = policy.text,
    ) && motion.blurWholeForeground
    val revealDensity = LocalDensity.current
    val animatedModifier = Modifier.graphicsLayer {
        alpha = progress.value
        translationY = 0f
        renderEffect = null
        if (revealWholeContent) {
            translationY = with(revealDensity) { (10.dp * (1f - progress.value)).toPx() }
            val radiusPx = with(revealDensity) { (8.dp * (1f - progress.value)).toPx() }.roundToInt()
            renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radiusPx > 0) {
                systemBlurEffectForRadiusPx(radiusPx)
            } else {
                null
            }
        }
    }
    ProvideDialogUiScale(scaleOverride = entry.uiScale) {
        if (entry.fullScreen) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFlueOverlayPresentationProgress provides progress.value,
                LocalFlueOverlayTextEffect provides if (revealWholeContent) PromptTextEffect.FADE else if (entry.textReveal) policy.text else PromptTextEffect.FADE,
                LocalFluePromptMotion provides motion,
            ) {
                Column(
                    modifier = entry.modifier
                        .fillMaxSize()
                        .then(
                            if (entry.scrollableContent) Modifier.verticalScroll(fullScreenScrollState)
                            else Modifier,
                        )
                        .then(
                            if (entry.scrollableContent && motion.edgeRubberBand) Modifier.flueElasticNestedScroll(fullScreenScrollState)
                            else Modifier,
                        )
                        .then(animatedModifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = entry.content,
                )
            }
            return@ProvideDialogUiScale
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(
                    horizontal = if (round) 40.dp else 24.dp,
                    vertical = if (round) 32.dp else 20.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFlueOverlayPresentationProgress provides progress.value,
                LocalFlueOverlayTextEffect provides if (entry.textReveal) policy.text else PromptTextEffect.FADE,
                LocalFluePromptMotion provides motion,
            ) {
                Column(
                    modifier = entry.modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * entry.maxHeightFraction)
                        .then(
                            if (entry.scrollableContent) {
                                Modifier.verticalScroll(contentScrollState)
                            } else {
                                Modifier
                            },
                        )
                        .then(animatedModifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = entry.content,
                )
            }
        }
    }
}

/** Applies the advanced-material reveal only to short text groups, never controls. */
@Composable
internal fun Modifier.fluePromptTextReveal(): Modifier {
    val progress = LocalFlueOverlayPresentationProgress.current
    val effect = LocalFlueOverlayTextEffect.current
    val density = LocalDensity.current
    return this.graphicsLayer {
        if (effect == PromptTextEffect.BLUR_RISE) {
            translationY = with(density) { (10.dp * (1f - progress)).toPx() }
            val radiusPx = with(density) { (8.dp * (1f - progress)).toPx() }.roundToInt()
            renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radiusPx > 0) {
                systemBlurEffectForRadiusPx(radiusPx)
            } else {
                null
            }
        }
    }
}

@Composable
private fun FlueOverlayInputBlocker(chainToken: Long) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(chainToken) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    )
}

private fun promptScrimAlpha(uiStyle: UiStyle): Float = when (uiStyle) {
    UiStyle.APPLE_WATCH -> 0.70f
    UiStyle.MIUIX -> 0.62f
    UiStyle.MATERIAL_3 -> 0.64f
}

private suspend fun captureAndBlurPrompt(window: Window, sourceSize: IntSize): Bitmap? {
    val captureSize = legacyPromptCaptureSize(sourceSize.width, sourceSize.height)
    val bitmap = requestPromptPixelCopy(window, captureSize.width, captureSize.height) ?: return null
    return try {
        withContext(Dispatchers.Default) {
            blurPromptBitmapInPlace(bitmap)
            bitmap
        }
    } catch (error: Throwable) {
        if (!bitmap.isRecycled) bitmap.recycle()
        if (error is kotlinx.coroutines.CancellationException) throw error
        null
    }
}

private suspend fun requestPromptPixelCopy(window: Window, width: Int, height: Int): Bitmap? =
    withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { continuation ->
            val destination = runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            if (destination == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            runCatching {
                PixelCopy.request(
                    window,
                    destination,
                    { result ->
                        if (result == PixelCopy.SUCCESS && continuation.isActive) {
                            continuation.resume(destination)
                        } else {
                            if (!destination.isRecycled) destination.recycle()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                    PromptPixelCopyThread.handler,
                )
            }.onFailure {
                if (!destination.isRecycled) destination.recycle()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

private suspend fun blurPromptBitmapInPlace(bitmap: Bitmap) {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    val scratch = IntArray(pixels.size)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(PROMPT_BLUR_PASSES) {
        coroutineContext.ensureActive()
        boxBlurHorizontal(pixels, scratch, width, height, PROMPT_BLUR_RADIUS)
        boxBlurVertical(scratch, pixels, width, height, PROMPT_BLUR_RADIUS)
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}

private fun boxBlurHorizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
    val diameter = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (sample in -radius..radius) {
            val color = input[row + sample.coerceIn(0, width - 1)]
            alpha += color ushr 24
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        for (x in 0 until width) {
            output[row + x] =
                ((alpha / diameter) shl 24) or ((red / diameter) shl 16) or
                    ((green / diameter) shl 8) or (blue / diameter)
            val outgoing = input[row + (x - radius).coerceIn(0, width - 1)]
            val incoming = input[row + (x + radius + 1).coerceIn(0, width - 1)]
            alpha += (incoming ushr 24) - (outgoing ushr 24)
            red += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
            green += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun boxBlurVertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
    val diameter = radius * 2 + 1
    for (x in 0 until width) {
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (sample in -radius..radius) {
            val color = input[sample.coerceIn(0, height - 1) * width + x]
            alpha += color ushr 24
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        for (y in 0 until height) {
            output[y * width + x] =
                ((alpha / diameter) shl 24) or ((red / diameter) shl 16) or
                    ((green / diameter) shl 8) or (blue / diameter)
            val outgoing = input[(y - radius).coerceIn(0, height - 1) * width + x]
            val incoming = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            alpha += (incoming ushr 24) - (outgoing ushr 24)
            red += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
            green += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private suspend fun Bitmap.retireAfterTwoFrames() {
    withFrameNanos { }
    withFrameNanos { }
    if (!isRecycled) recycle()
}

private fun retireBitmapAfterTwoVsyncs(bitmap: Bitmap) {
    Handler(android.os.Looper.getMainLooper()).postDelayed(
        { if (!bitmap.isRecycled) bitmap.recycle() },
        48L,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private object PromptPixelCopyThread {
    private val thread = HandlerThread("flue-prompt-pixel-copy").apply { start() }
    val handler = Handler(thread.looper)
}
