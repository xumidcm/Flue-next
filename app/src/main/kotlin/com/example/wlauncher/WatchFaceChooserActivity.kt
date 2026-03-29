package com.flue.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.media.MediaMetadataRetriever
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.ui.home.ClockSnapshot
import com.flue.launcher.ui.home.ClockPalette
import com.flue.launcher.ui.home.FIXED_PREVIEW_CLOCK
import com.flue.launcher.ui.home.rememberClockPaletteForPreview
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import com.flue.launcher.watchface.LunchWatchFaceScanner
import com.flue.launcher.watchface.WatchClockPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

class WatchFaceChooserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                WatchFaceChooserScreen(onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun WatchFaceChooserScreen(
    onDismiss: () -> Unit
) {
    val vm: LauncherViewModel = viewModel()
    val context = LocalContext.current
    val watchFaces by vm.availableWatchFaces.collectAsState()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsState()
    val builtInPhotoPath by vm.builtInPhotoPath.collectAsState()
    val builtInVideoPath by vm.builtInVideoPath.collectAsState()
    val builtInPhotoClockPosition by vm.builtInPhotoClockPosition.collectAsState()
    val builtInVideoClockPosition by vm.builtInVideoClockPosition.collectAsState()
    val builtInPhotoClockSize by vm.builtInPhotoClockSize.collectAsState()
    val builtInVideoClockSize by vm.builtInVideoClockSize.collectAsState()
    val builtInPhotoClockBold by vm.builtInPhotoClockBold.collectAsState()
    val builtInVideoClockBold by vm.builtInVideoClockBold.collectAsState()
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsState()

    LaunchedEffect(watchFaces.isEmpty()) {
        if (watchFaces.isNotEmpty()) return@LaunchedEffect
        vm.refreshWatchFaces()
    }

    var initialSelectionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(watchFaces, selectedWatchFaceId) {
        if (initialSelectionId == null && watchFaces.isNotEmpty()) {
            initialSelectionId = selectedWatchFaceId
        }
    }

    val seedSelectionId = initialSelectionId ?: selectedWatchFaceId
    val targetPage = remember(watchFaces, seedSelectionId) {
        watchFaces.indexOfFirst { it.id == seedSelectionId }
            .takeIf { it >= 0 }
            ?: 0
    }
    val pagerState = rememberPagerState(initialPage = 0) { watchFaces.size.coerceAtLeast(1) }
    var initialAligned by remember(watchFaces, seedSelectionId) { mutableStateOf(false) }

    LaunchedEffect(watchFaces, targetPage) {
        if (watchFaces.isEmpty()) {
            initialAligned = false
            return@LaunchedEffect
        }
        initialAligned = false
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        initialAligned = true
    }

    val displayIndex = if (watchFaces.isEmpty()) {
        -1
    } else if (initialAligned) {
        pagerState.currentPage.coerceIn(0, watchFaces.lastIndex)
    } else {
        targetPage.coerceIn(0, watchFaces.lastIndex)
    }
    val currentDescriptor = watchFaces.getOrNull(displayIndex)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val previewHeight = (maxHeight - 282.dp).coerceIn(170.dp, 420.dp)
        val previewWidth = (maxWidth - 28.dp).coerceAtLeast(170.dp)
        val previewSize = if (previewWidth < previewHeight) previewWidth else previewHeight
        val topSpacing = if (maxHeight < 420.dp) 14.dp else 20.dp
        val bottomSpacing = if (maxHeight < 420.dp) 12.dp else 18.dp

        if (currentDescriptor == null) {
            Text(
                text = "\u6ca1\u6709\u53ef\u7528\u8868\u76d8",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentDescriptor.displayName,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentDescriptor.summary.ifBlank { "\u5df2\u5b89\u88c5\u8868\u76d8" },
                color = WatchColors.TextTertiary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(topSpacing))

            if (!initialAligned) {
                WatchFacePreviewCard(
                    descriptor = currentDescriptor,
                    builtInPhotoPath = builtInPhotoPath,
                    builtInVideoPath = builtInVideoPath,
                    photoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInPhotoClockPosition,
                        clockSizeSp = builtInPhotoClockSize,
                        boldClock = builtInPhotoClockBold
                    ),
                    videoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInVideoClockPosition,
                        clockSizeSp = builtInVideoClockSize,
                        boldClock = builtInVideoClockBold,
                        cropToFill = builtInVideoFillScreen
                    ),
                    clockOverride = FIXED_PREVIEW_CLOCK,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewSize)
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewSize)
                ) { page ->
                    val descriptor = watchFaces.getOrNull(page) ?: return@HorizontalPager
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                    val pageScale = 1f - (pageOffset.coerceIn(0f, 1f) * 0.12f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = 1f - (pageOffset.coerceIn(0f, 1f) * 0.35f)
                            }
                            .scale(pageScale)
                    ) {
                        WatchFacePreviewCard(
                            descriptor = descriptor,
                            builtInPhotoPath = builtInPhotoPath,
                            builtInVideoPath = builtInVideoPath,
                            photoOptions = BuiltInWatchFaceOptions(
                                clockPosition = builtInPhotoClockPosition,
                                clockSizeSp = builtInPhotoClockSize,
                                boldClock = builtInPhotoClockBold
                            ),
                            videoOptions = BuiltInWatchFaceOptions(
                                clockPosition = builtInVideoClockPosition,
                                clockSizeSp = builtInVideoClockSize,
                                boldClock = builtInVideoClockBold,
                                cropToFill = builtInVideoFillScreen
                            ),
                            clockOverride = FIXED_PREVIEW_CLOCK,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(bottomSpacing))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                watchFaces.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == displayIndex.coerceAtLeast(0)) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == displayIndex.coerceAtLeast(0)) WatchColors.ActiveCyan else Color.White.copy(alpha = 0.24f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(bottomSpacing))

            if (!currentDescriptor.isBuiltin && currentDescriptor.packageName != null) {
                Text(
                    text = currentDescriptor.packageName,
                    color = WatchColors.TextTertiary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
            ) {
                if (currentDescriptor.supportsSettings) {
                    ChooserButton("\u8bbe\u7f6e") {
                        if (currentDescriptor.isBuiltin && currentDescriptor.id in setOf(BUILT_IN_PHOTO_WATCHFACE_ID, BUILT_IN_VIDEO_WATCHFACE_ID)) {
                            context.startActivity(
                                Intent(context, InternalWatchFaceConfigActivity::class.java)
                                    .putExtra(EXTRA_INTERNAL_WATCHFACE_ID, currentDescriptor.id)
                            )
                        } else if (!LunchWatchFaceRuntime.openSettings(context, currentDescriptor)) {
                            Toast.makeText(context, "\u6CA1\u6709\u53EF\u7528\u7684\u8868\u76D8\u8BBE\u7F6E", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ChooserButton("\u5b8c\u6210") {
                    vm.selectWatchFace(currentDescriptor.id)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun WatchFacePreviewCard(
    descriptor: LunchWatchFaceDescriptor,
    builtInPhotoPath: String? = null,
    builtInVideoPath: String? = null,
    photoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    videoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    clockOverride: ClockSnapshot? = FIXED_PREVIEW_CLOCK,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.height(400.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val cardSize = if (maxWidth < maxHeight) maxWidth else maxHeight
            val cardModifier = Modifier
                .size(cardSize)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF10141D))

            if (descriptor.isBuiltin) {
                BuiltInChooserPreview(
                    watchFaceId = descriptor.id,
                    photoPath = builtInPhotoPath,
                    videoPath = builtInVideoPath,
                    photoOptions = photoOptions,
                    videoOptions = videoOptions,
                    clockOverride = clockOverride,
                    modifier = cardModifier
                )
            } else {
                WatchFacePreviewDrawable(
                    descriptor = descriptor,
                    modifier = cardModifier
                )
            }
        }
    }
}

@Composable
private fun BuiltInChooserPreview(
    watchFaceId: String,
    photoPath: String?,
    videoPath: String?,
    photoOptions: BuiltInWatchFaceOptions,
    videoOptions: BuiltInWatchFaceOptions,
    clockOverride: ClockSnapshot?,
    modifier: Modifier = Modifier
) {
    val clock = clockOverride ?: FIXED_PREVIEW_CLOCK
    val clockPosition = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> photoOptions.clockPosition
        BUILT_IN_VIDEO_WATCHFACE_ID -> videoOptions.clockPosition
        else -> WatchClockPosition.CENTER
    }
    val clockSizeSp = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> photoOptions.clockSizeSp
        BUILT_IN_VIDEO_WATCHFACE_ID -> videoOptions.clockSizeSp
        else -> 64
    }
    val boldClock = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> photoOptions.boldClock
        BUILT_IN_VIDEO_WATCHFACE_ID -> videoOptions.boldClock
        else -> false
    }
    val palette = rememberClockPaletteForPreview(
        watchFaceId = watchFaceId,
        photoPath = photoPath,
        clockPosition = clockPosition
    )
    val fallbackTitle = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> if (photoPath.isNullOrBlank()) "\u672A\u8BBE\u7F6E\u56FE\u7247" else null
        BUILT_IN_VIDEO_WATCHFACE_ID -> if (videoPath.isNullOrBlank()) "\u672A\u8BBE\u7F6E\u89C6\u9891" else null
        else -> null
    }
    val fallbackSubtitle = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> if (photoPath.isNullOrBlank()) "\u5728\u8868\u76D8\u8BBE\u7F6E\u91CC\u9009\u62E9\u4E00\u5F20\u56FE\u7247" else null
        BUILT_IN_VIDEO_WATCHFACE_ID -> if (videoPath.isNullOrBlank()) "\u5728\u8868\u76D8\u8BBE\u7F6E\u91CC\u9009\u62E9\u4E00\u4E2A\u89C6\u9891" else null
        else -> null
    }
    val previewBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = watchFaceId, key2 = photoPath, key3 = videoPath, key4 = clockPosition, key5 = clockSizeSp, key6 = boldClock, key7 = palette, key8 = fallbackTitle, key9 = fallbackSubtitle) {
        value = withContext(Dispatchers.IO) {
            renderBuiltInChooserBitmap(
                watchFaceId = watchFaceId,
                photoPath = photoPath,
                videoPath = videoPath,
                clock = clock,
                clockPosition = clockPosition,
                clockSizeSp = clockSizeSp,
                boldClock = boldClock,
                palette = palette,
                fallbackTitle = fallbackTitle,
                fallbackSubtitle = fallbackSubtitle
            )
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = previewBitmap
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val fallbackModifier = when (watchFaceId) {
                BUILT_IN_VIDEO_WATCHFACE_ID -> Modifier.background(Color(0xFF05070C))
                BUILT_IN_PHOTO_WATCHFACE_ID -> Modifier.background(Color(0xFF0C1018))
                else -> Modifier.background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF16304A), Color(0xFF0B1322), Color.Black),
                        radius = 900f
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(fallbackModifier)
            )
        }
    }
}

private fun renderBuiltInChooserBitmap(
    watchFaceId: String,
    photoPath: String?,
    videoPath: String?,
    clock: ClockSnapshot,
    clockPosition: WatchClockPosition,
    clockSizeSp: Int,
    boldClock: Boolean,
    palette: ClockPalette,
    fallbackTitle: String?,
    fallbackSubtitle: String?
): ImageBitmap? {
    val size = 720
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> {
            val source = photoPath
                ?.takeIf { File(it).exists() }
                ?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            if (source != null) {
                drawBitmapCover(canvas, source)
            } else {
                canvas.drawColor(AndroidColor.parseColor("#0C1018"))
            }
        }
        BUILT_IN_VIDEO_WATCHFACE_ID -> {
            val source = videoPath
                ?.takeIf { File(it).exists() }
                ?.let { path ->
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(path)
                            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }.getOrNull()
                }
            if (source != null) {
                drawBitmapCover(canvas, source)
            } else {
                canvas.drawColor(AndroidColor.parseColor("#05070C"))
            }
        }
        BUILT_IN_WATCHFACE_ID -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    size.toFloat(),
                    size.toFloat(),
                    intArrayOf(
                        AndroidColor.parseColor("#16304A"),
                        AndroidColor.parseColor("#0B1322"),
                        AndroidColor.BLACK
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        }
        else -> {
            canvas.drawColor(AndroidColor.parseColor("#10141D"))
        }
    }

    val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            0f,
            size.toFloat(),
            intArrayOf(
                AndroidColor.argb(28, 0, 0, 0),
                AndroidColor.TRANSPARENT,
                AndroidColor.argb(112, 0, 0, 0)
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), scrimPaint)

    drawChooserClock(
        canvas = canvas,
        clock = clock,
        clockPosition = clockPosition,
        clockSizeSp = clockSizeSp,
        boldClock = boldClock,
        timeColor = palette.timeColor.toArgb(),
        dateColor = palette.dateColor.toArgb(),
        fallbackTitle = fallbackTitle,
        fallbackSubtitle = fallbackSubtitle
    )
    return bitmap.asImageBitmap()
}

private fun drawBitmapCover(canvas: Canvas, source: Bitmap) {
    val scale = maxOf(
        canvas.width / source.width.toFloat(),
        canvas.height / source.height.toFloat()
    )
    val scaledWidth = source.width * scale
    val scaledHeight = source.height * scale
    val left = (canvas.width - scaledWidth) / 2f
    val top = (canvas.height - scaledHeight) / 2f
    val dst = RectF(left, top, left + scaledWidth, top + scaledHeight)
    canvas.drawBitmap(source, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}

private fun drawChooserClock(
    canvas: Canvas,
    clock: ClockSnapshot,
    clockPosition: WatchClockPosition,
    clockSizeSp: Int,
    boldClock: Boolean,
    timeColor: Int,
    dateColor: Int,
    fallbackTitle: String?,
    fallbackSubtitle: String?
) {
    val width = canvas.width.toFloat()
    val height = canvas.height.toFloat()
    val horizontalPadding = width * 0.1f
    val verticalPadding = height * 0.12f
    val textAlign = when (clockPosition) {
        WatchClockPosition.TOP_LEFT,
        WatchClockPosition.BOTTOM_LEFT,
        WatchClockPosition.LEFT_CENTER -> Paint.Align.LEFT
        WatchClockPosition.TOP_RIGHT,
        WatchClockPosition.BOTTOM_RIGHT,
        WatchClockPosition.RIGHT_CENTER -> Paint.Align.RIGHT
        WatchClockPosition.CENTER -> Paint.Align.CENTER
    }
    val anchorX = when (clockPosition) {
        WatchClockPosition.TOP_LEFT,
        WatchClockPosition.BOTTOM_LEFT,
        WatchClockPosition.LEFT_CENTER -> horizontalPadding
        WatchClockPosition.TOP_RIGHT,
        WatchClockPosition.BOTTOM_RIGHT,
        WatchClockPosition.RIGHT_CENTER -> width - horizontalPadding
        WatchClockPosition.CENTER -> width / 2f
    }
    val anchorY = when (clockPosition) {
        WatchClockPosition.TOP_LEFT,
        WatchClockPosition.TOP_RIGHT -> verticalPadding + clockSizeSp * 2.7f
        WatchClockPosition.LEFT_CENTER,
        WatchClockPosition.CENTER,
        WatchClockPosition.RIGHT_CENTER -> height * 0.46f
        WatchClockPosition.BOTTOM_LEFT,
        WatchClockPosition.BOTTOM_RIGHT -> height - verticalPadding - clockSizeSp * 1.1f
    }
    val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = timeColor
        textAlign = textAlign
        textSize = clockSizeSp * 5.2f
        typeface = if (boldClock) Typeface.DEFAULT_BOLD else Typeface.create(Typeface.DEFAULT, 300, false)
        isSubpixelText = true
    }
    val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dateColor
        textAlign = textAlign
        textSize = clockSizeSp * 1.25f
        typeface = Typeface.DEFAULT_BOLD
        alpha = 220
    }
    canvas.drawText(clock.time, anchorX, anchorY, timePaint)
    canvas.drawText(clock.date, anchorX, anchorY + clockSizeSp * 1.7f, datePaint)

    fallbackTitle?.let { title ->
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = timeColor
            textAlign = textAlign
            textSize = clockSizeSp * 1.45f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(title, anchorX, anchorY + clockSizeSp * 3.7f, titlePaint)
        fallbackSubtitle?.let { subtitle ->
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = timeColor
                textAlign = textAlign
                textSize = clockSizeSp * 0.92f
                alpha = 196
            }
            canvas.drawText(subtitle, anchorX, anchorY + clockSizeSp * 5.05f, subtitlePaint)
        }
    }
}

@Composable
private fun WatchFacePreviewDrawable(
    descriptor: LunchWatchFaceDescriptor,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = descriptor.stableKey) {
        value = withContext(Dispatchers.Default) {
            LunchWatchFaceScanner.loadPreviewDrawable(context, descriptor)
                ?.toBitmap(420, 420)
                ?.asImageBitmap()
        }
    }
    val bitmap = previewBitmap
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF151922)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = descriptor.displayName,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ChooserButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.42f)
            .clip(RoundedCornerShape(16.dp))
            .background(WatchColors.SurfaceGlass)
            .clickable(onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, color = WatchColors.ActiveCyan)
    }
}
