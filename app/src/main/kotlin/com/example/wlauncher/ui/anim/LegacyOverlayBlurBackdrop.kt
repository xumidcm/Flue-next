package com.flue.launcher.ui.anim

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 通知中心压在负一屏 / 控制中心上时，供它当背景用的预制烘焙模糊帧。
 *
 * 为什么不能靠 [Modifier.flueLegacyBlur]：那个 Modifier 只能模糊「自己录下来的
 * 内容」，而负一屏与控制中心的根底都是 `alpha = 0.62f` 半透明的 —— 底下的表盘
 * 和图标是**直接穿透**过来的，压根不在它录的图层里。于是涂抹只作用在半透明层
 * 自身，穿透过来的像素一点没糊，通知列表后面就是清清楚楚的表盘（老师截图）。
 *
 * 这里改用与表盘同一套思路（见 [rememberLegacyWatchFacePixelCopyFrame]）：在
 * 覆层完全展开、通知还没拉出来的静止时刻，把窗口当前合成像素 PixelCopy 出来，
 * 离线糊一次，之后整段通知转场都复用这张。捕获不进拖动路径，所以不影响跟手。
 *
 * 只服务 Android 12 以下。31+ 有 RenderEffect，由各层自己的 `renderEffect`
 * 实时模糊，质量和功耗都更好。
 */
@Composable
internal fun rememberLegacyOverlayBlurBackdrop(
    /**
     * 捕获闸门。必须在「覆层已完全展开 + 通知尚未开始拉出」时才为 true：
     * 早了会把覆层的入场中间态采进去，晚了会把通知列表自己采进背景形成套娃。
     */
    captureAllowed: Boolean,
    /**
     * 内容签名。换页面 / 换表盘 / 小组件更新都要换它来作废旧帧，
     * 否则通知背后会一直是过期画面。
     */
    visualKey: String,
    advancedMaterialEnabled: Boolean = true,
): ImageBitmap? {
    // O 以下没有 PixelCopy(Window,...)；Android 12+ 仅在高级材质开启时走 RenderEffect。
    if (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        overlayBlurBackend(
            sdkInt = Build.VERSION.SDK_INT,
            advancedMaterialEnabled = advancedMaterialEnabled,
        ) == OverlayBlurBackend.SYSTEM
    ) {
        return null
    }

    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context, view) {
        (context.findHostActivity() ?: view.context.findHostActivity())?.window
    }
    var backdrop by remember { mutableStateOf<LegacyOverlayBlurFrame?>(null) }
    val latestCaptureAllowed = rememberUpdatedState(captureAllowed)

    LaunchedEffect(visualKey, window, captureAllowed) {
        val targetWindow = window ?: return@LaunchedEffect
        if (!captureAllowed) return@LaunchedEffect
        if (backdrop?.visualKey == visualKey) return@LaunchedEffect

        // 让覆层的入场弹簧先落定：捕早了会把滑入中途的画面烘进去。
        delay(OVERLAY_BACKDROP_SETTLE_DELAY_MS)
        coroutineContext.ensureActive()
        if (!latestCaptureAllowed.value) return@LaunchedEffect

        val rootView = view.rootView
        val captured = requestOverlayPixelCopy(
            window = targetWindow,
            width = rootView.width,
            height = rootView.height,
        ) ?: return@LaunchedEffect

        var blurredOwnedByEffect = true
        var blurred: Bitmap? = null
        try {
            coroutineContext.ensureActive()
            if (!latestCaptureAllowed.value) return@LaunchedEffect
            // 盒模糊是纯 CPU 卷积，必须离开主线程 —— 全屏尺寸在低端机上要几十毫秒。
            blurred = withContext(Dispatchers.Default) {
                runCatching { createLegacyPrebuiltBlur(captured) }.getOrNull()
            } ?: return@LaunchedEffect
            coroutineContext.ensureActive()
            if (!latestCaptureAllowed.value) return@LaunchedEffect
            val previous = backdrop
            backdrop = LegacyOverlayBlurFrame(
                visualKey = visualKey,
                bitmap = blurred,
                image = blurred.asImageBitmap(),
            )
            blurredOwnedByEffect = false
            // Compose 的绘制可能还持有上一张 ImageBitmap 包装。留两个 VSYNC
            // 再回收底层 Bitmap，否则偶发画到已回收位图直接崩。
            previous?.bitmap?.let { old ->
                Handler(Looper.getMainLooper()).postDelayed(
                    { recycleOverlayBitmapSafely(old) },
                    OVERLAY_BACKDROP_RECYCLE_DELAY_MS,
                )
            }
        } finally {
            // 原始捕获帧永远不进状态，糊完就没用了。
            recycleOverlayBitmapSafely(captured)
            // 协程在「糊好了」和「装进状态」之间被取消时，这张糊完的图没人认领。
            if (blurredOwnedByEffect) blurred?.let(::recycleOverlayBitmapSafely)
        }
    }

    val latestBackdrop by rememberUpdatedState(backdrop)
    DisposableEffect(Unit) {
        onDispose { latestBackdrop?.bitmap?.let(::recycleOverlayBitmapSafely) }
    }

    return backdrop?.takeIf { it.visualKey == visualKey && !it.bitmap.isRecycled }?.image
}

private data class LegacyOverlayBlurFrame(
    val visualKey: String,
    val bitmap: Bitmap,
    val image: ImageBitmap,
)

/**
 * 覆层弹簧的落定余量。比转场本身（约 260ms）稍长，确保采到的是静止帧。
 */
private const val OVERLAY_BACKDROP_SETTLE_DELAY_MS = 320L
private const val OVERLAY_BACKDROP_RECYCLE_DELAY_MS = 48L

private fun recycleOverlayBitmapSafely(bitmap: Bitmap) {
    synchronized(bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private suspend fun requestOverlayPixelCopy(
    window: Window,
    width: Int,
    height: Int,
): Bitmap? = suspendCancellableCoroutine { continuation ->
    if (width <= 0 || height <= 0) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }
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
                    recycleOverlayBitmapSafely(destination)
                    if (continuation.isActive) continuation.resume(null)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure {
        recycleOverlayBitmapSafely(destination)
        if (continuation.isActive) continuation.resume(null)
    }
}
