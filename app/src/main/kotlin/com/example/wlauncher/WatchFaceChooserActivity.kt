package com.flue.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.ui.home.BuiltInWatchFacePreview
import com.flue.launcher.ui.home.ClockSnapshot
import com.flue.launcher.ui.home.FIXED_PREVIEW_CLOCK
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import com.flue.launcher.watchface.LunchWatchFaceScanner
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
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsState()

    LaunchedEffect(Unit) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (currentDescriptor == null) {
            Text(
                text = "\u6ca1\u6709\u53ef\u7528\u8868\u76d8",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 24.dp),
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
            Spacer(modifier = Modifier.height(20.dp))

            if (!initialAligned) {
                WatchFacePreviewCard(
                    descriptor = currentDescriptor,
                    builtInPhotoPath = builtInPhotoPath,
                    builtInVideoPath = builtInVideoPath,
                    photoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInPhotoClockPosition,
                        clockSizeSp = builtInPhotoClockSize
                    ),
                    videoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInVideoClockPosition,
                        clockSizeSp = builtInVideoClockSize,
                        cropToFill = builtInVideoFillScreen
                    ),
                    clockOverride = FIXED_PREVIEW_CLOCK,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 26.dp),
                    pageSpacing = 18.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
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
                                clockSizeSp = builtInPhotoClockSize
                            ),
                            videoOptions = BuiltInWatchFaceOptions(
                                clockPosition = builtInVideoClockPosition,
                                clockSizeSp = builtInVideoClockSize,
                                cropToFill = builtInVideoFillScreen
                            ),
                            clockOverride = FIXED_PREVIEW_CLOCK,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

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

            Spacer(modifier = Modifier.height(18.dp))

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
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF10141D))
            .height(400.dp),
        contentAlignment = Alignment.Center
    ) {
        if (descriptor.isBuiltin) {
            BuiltInWatchFacePreview(
                watchFaceId = descriptor.id,
                photoPath = builtInPhotoPath,
                videoPath = builtInVideoPath,
                photoOptions = photoOptions,
                videoOptions = videoOptions,
                clockOverride = clockOverride,
                showClock = true,
                playVideo = true,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            WatchFacePreviewDrawable(descriptor)
        }
    }
}

@Composable
private fun WatchFacePreviewDrawable(descriptor: LunchWatchFaceDescriptor) {
    val context = LocalContext.current
    val previewBitmap = remember(descriptor.stableKey) {
        LunchWatchFaceScanner.loadPreviewDrawable(context, descriptor)
            ?.toBitmap(720, 720)
            ?.asImageBitmap()
    }
    if (previewBitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = previewBitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
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
