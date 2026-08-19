package com.flue.launcher.ui.common

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flue.launcher.R
import com.flue.launcher.ui.theme.ProvideDialogUiScale
import com.flue.launcher.watchface.editor.WatchEditorCropRect
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import com.flue.launcher.watchface.editor.constrainWatchEditorCropRect
import com.flue.launcher.watchface.editor.mapWatchEditorCropToSource
import com.flue.launcher.watchface.editor.moveWatchEditorCropRect
import com.flue.launcher.watchface.editor.resizeWatchEditorCropRect
import com.flue.launcher.watchface.editor.resolveWatchEditorCropHandle
import com.flue.launcher.watchface.editor.scaleWatchEditorCropRect
import com.flue.launcher.watchface.editor.watchEditorFitImageRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 固定宽高比的图片裁剪覆层。
 *
 * ## 为什么锁死宽高比、只留平移 + 缩放
 *
 * 两个调用点的产物都会被**按固定比例**再摆一次：应用列表背景铺满全屏（center-crop），
 * 商店头像塞进一个圆里（1:1）。放开自由裁剪，用户挑出一个 4:3 的框，最后还是要被拉/切成
 * 目标比例 —— 等于让他选一个必然失真的结果。所以裁剪框锁成目标比例，手势只保留
 * 「挪位置」和「取多大一块」这两件真正有意义的自由度。单指拖动边缘/角落使用编辑器已有的
 * 八方向把手几何，双指仍然负责缩放，框内单指负责平移。
 *
 * 解码 / 区域裁剪 / 编码全部复用 [WatchEditorImageCropper]，几何复用
 * `WatchEditorImageCropModels` 那套已经有单测的纯函数 —— 不引裁剪库，也不重写一遍。
 *
 * ## 为什么用 Dialog 托管
 *
 * 调用点常常在发一串 `AlertDialog` 的位置，**不是**一个全屏 Box 的子节点。一个裸
 * `Column(fillMaxSize)` 放进去会被排进宿主页自己的布局流里，而不是压在它上面。
 * 全屏 Dialog 才能保证「盖住整屏 + 在最上层」，这也是 `InternalFileBrowser` 的做法。
 *
 * @param aspectRatio 裁剪框的宽/高。传 null 用**屏幕比例**（背景图那一路）。
 */
@Composable
internal fun FlueImageCropOverlay(
    uri: Uri,
    title: String,
    hint: String,
    aspectRatio: Float?,
    onCancel: () -> Unit,
    onConfirm: (WatchEditorSourceRect) -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            // 返回键交给下面的 BackHandler：裁剪页要能被返回键取消，但不能被点外面误关。
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            // 全屏 Dialog 自己接管 insets；默认 true 时窗口会被系统下移，
            // 顶部让出的一条不被背景覆盖。
            decorFitsSystemWindows = false,
        ),
    ) {
        // Dialog 是独立 Window，density 要在这里重新缩放一次（见 ProvideDialogUiScale）。
        ProvideDialogUiScale {
            FlueImageCropContent(
                uri = uri,
                title = title,
                hint = hint,
                aspectRatio = aspectRatio,
                onCancel = onCancel,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun FlueImageCropContent(
    uri: Uri,
    title: String,
    hint: String,
    aspectRatio: Float?,
    onCancel: () -> Unit,
    onConfirm: (WatchEditorSourceRect) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var preview by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sourceSize by remember(uri) { mutableStateOf<IntSize?>(null) }
    var decodeError by remember(uri) { mutableStateOf(false) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    var cropRect by remember(uri) { mutableStateOf<WatchEditorCropRect?>(null) }

    // 目标宽高比。裁剪框锁死它，渲染侧再摆一次就不会又切掉用户选中的内容。
    val ratio = aspectRatio
        ?: remember(configuration.screenWidthDp, configuration.screenHeightDp) {
            val width = configuration.screenWidthDp.toFloat()
            val height = configuration.screenHeightDp.toFloat()
            if (width > 0f && height > 0f) width / height else 1f
        }

    LaunchedEffect(uri) {
        runCatching {
            withContext(Dispatchers.IO) {
                val bounds = WatchEditorImageCropper.readBounds(context.contentResolver, uri)
                val bitmap = WatchEditorImageCropper.decodePreview(context.contentResolver, uri)
                bounds to bitmap
            }
        }.onSuccess { (bounds, bitmap) ->
            sourceSize = IntSize(bounds.width, bounds.height)
            preview = bitmap
        }.onFailure { decodeError = true }
    }
    // 预览位图故意交给 GC：还有一帧在引用它的 ImageBitmap 时 recycle 会直接崩渲染节点
    // （编辑器的裁剪预览同样是这个理由）。
    BackHandler(onBack = onCancel)

    val bitmap = preview
    val fit = if (bitmap != null && areaSize.width > 0 && areaSize.height > 0) {
        watchEditorFitImageRect(
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            areaSize.width.toFloat(),
            areaSize.height.toFloat(),
        )
    } else {
        null
    }
    val minCropSizePx = with(LocalDensity.current) { 48.dp.toPx() }
    if (fit != null && cropRect == null) {
        // 初始取「按目标比例塞进整张图」的最大框：绝大多数情况下用户直接确定就是对的。
        cropRect = constrainWatchEditorCropRect(fit, fit, minCropSizePx, ratio)
    }

    val latestCrop = rememberUpdatedState(cropRect)
    val latestFit = rememberUpdatedState(fit)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // decorFitsSystemWindows = false 之后系统不再给窗口让位，自己让。
            // 圆屏上不让位会把底部两个按钮切掉一半。
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            // 吞掉所有触摸，下面的宿主页点不到（与编辑器裁剪覆层同一写法）。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            hint,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(8.dp))
                .onSizeChanged { areaSize = it },
        ) {
            when {
                decodeError -> Text(
                    stringResource(R.string.image_crop_failed),
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                bitmap == null -> Text(
                    stringResource(R.string.internal_file_browser_loading),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(ratio) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val initialRect = latestCrop.value
                                    val activeHandle = initialRect?.let {
                                        resolveWatchEditorCropHandle(
                                            rect = it,
                                            x = down.position.x,
                                            y = down.position.y,
                                            touchRadius = 28f,
                                        )
                                    }
                                    var pastTouchSlop = false
                                    val touchSlop = viewConfiguration.touchSlop
                                    var slopAccumulator = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.any { it.isConsumed }) break
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        if (!pastTouchSlop) {
                                            slopAccumulator += kotlin.math.abs(
                                                event.calculateCentroidSize(useCurrent = true) -
                                                    event.calculateCentroidSize(useCurrent = false),
                                            ) + pan.getDistance()
                                            if (slopAccumulator > touchSlop) pastTouchSlop = true
                                        }
                                        if (!pastTouchSlop) continue
                                        val bounds = latestFit.value ?: continue
                                        var rect = latestCrop.value ?: continue
                                        if (activeHandle != null) {
                                            // 把手拖动只改变裁剪框，不跟随图片平移；几何层会负责
                                            // 固定对角/边、保持比例、夹回原图边界。
                                            rect = resizeWatchEditorCropRect(
                                                rect = rect,
                                                handle = activeHandle,
                                                deltaX = pan.x,
                                                deltaY = pan.y,
                                                bounds = bounds,
                                                minSize = minCropSizePx,
                                                ratio = ratio,
                                            )
                                        } else if (zoom != 1f) {
                                            // 双指缩放：取多大一块。1/zoom —— 手指张开是「看得更近」，
                                            // 也就是裁剪框变小。
                                            rect = scaleWatchEditorCropRect(
                                                rect = rect,
                                                factor = 1f / zoom,
                                                bounds = bounds,
                                                minSize = minCropSizePx,
                                                ratio = ratio,
                                            )
                                        }
                                        if (pan.x != 0f || pan.y != 0f) {
                                            rect = moveWatchEditorCropRect(
                                                rect = rect,
                                                deltaX = pan.x,
                                                deltaY = pan.y,
                                                bounds = bounds,
                                            )
                                        }
                                        cropRect = rect
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    ) {
                        val rect = latestCrop.value ?: return@Canvas
                        // 框外压暗：四条矩形而不是一个 Path，绘制期零分配。
                        val dim = Color.Black.copy(alpha = 0.58f)
                        drawRect(dim, size = Size(size.width, rect.top))
                        drawRect(
                            dim,
                            topLeft = Offset(0f, rect.bottom),
                            size = Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f)),
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(0f, rect.top),
                            size = Size(rect.left, rect.height),
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(rect.right, rect.top),
                            size = Size((size.width - rect.right).coerceAtLeast(0f), rect.height),
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(rect.width, rect.height),
                            style = Stroke(width = 2f),
                        )
                        // 八个小把手只做视觉提示，实际命中半径放大到 28px，圆屏上也容易拖到。
                        val handleRadius = 4f
                        val handlePoints = listOf(
                            Offset(rect.left, rect.top),
                            Offset(rect.centerX, rect.top),
                            Offset(rect.right, rect.top),
                            Offset(rect.right, rect.centerY),
                            Offset(rect.right, rect.bottom),
                            Offset(rect.centerX, rect.bottom),
                            Offset(rect.left, rect.bottom),
                            Offset(rect.left, rect.centerY),
                        )
                        handlePoints.forEach { point ->
                            drawCircle(Color.White, handleRadius, point)
                            drawCircle(Color.Black.copy(alpha = 0.55f), handleRadius, point, style = Stroke(1f))
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.watch_editor_cancel))
            }
            TextButton(
                enabled = cropRect != null && fit != null && sourceSize != null,
                onClick = {
                    val rect = cropRect ?: return@TextButton
                    val bounds = fit ?: return@TextButton
                    val source = sourceSize ?: return@TextButton
                    onConfirm(
                        mapWatchEditorCropToSource(
                            crop = rect,
                            fit = bounds,
                            sourceWidth = source.width,
                            sourceHeight = source.height,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.watch_editor_use))
            }
        }
    }
}
