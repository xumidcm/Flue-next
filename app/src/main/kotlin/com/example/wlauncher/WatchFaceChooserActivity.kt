package com.flue.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.home.BuiltInWatchFacePreview
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_SELECTED_WATCHFACE_ID
import com.flue.launcher.viewmodel.dataStore
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
    val prefs by context.dataStore.data.collectAsState(initial = null)
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

    val persistedSelectedId = prefs?.get(KEY_SELECTED_WATCHFACE_ID) ?: selectedWatchFaceId
    val targetPage = remember(watchFaces, persistedSelectedId) {
        watchFaces.indexOfFirst { it.id == persistedSelectedId }
            .takeIf { it >= 0 }
            ?: 0
    }
    val pagerState = rememberPagerState(initialPage = 0) { watchFaces.size.coerceAtLeast(1) }
    var chooserReady by remember(watchFaces, persistedSelectedId) { mutableStateOf(watchFaces.isEmpty()) }

    LaunchedEffect(watchFaces, persistedSelectedId, targetPage) {
        if (watchFaces.isEmpty()) return@LaunchedEffect
        chooserReady = false
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        chooserReady = pagerState.currentPage == targetPage
    }

    val currentDescriptor = if (watchFaces.isNotEmpty()) {
        watchFaces[pagerState.currentPage.coerceIn(0, watchFaces.lastIndex)]
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!chooserReady) {
            return@Box
        }

        if (currentDescriptor == null) {
            Text(
                text = "\u6CA1\u6709\u53EF\u7528\u8868\u76D8",
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
                text = if (currentDescriptor.summary.isNotBlank()) currentDescriptor.summary else "\u5DF2\u5B89\u88C5\u8868\u76D8",
                color = WatchColors.TextTertiary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

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
                        modifier = Modifier.fillMaxWidth()
                    )
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
                            .size(if (index == pagerState.currentPage.coerceIn(0, (watchFaces.size - 1).coerceAtLeast(0))) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage.coerceIn(0, (watchFaces.size - 1).coerceAtLeast(0))) {
                                    WatchColors.ActiveCyan
                                } else {
                                    Color.White.copy(alpha = 0.24f)
                                }
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
                    ChooserButton("\u8BBE\u7F6E", 1f) {
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
                ChooserButton("\u5B8C\u6210", 1f) {
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
    AndroidView(
        factory = { imageContext ->
            ImageView(imageContext).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { imageView ->
            imageView.setImageDrawable(LunchWatchFaceScanner.loadPreviewDrawable(context, descriptor))
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ChooserButton(
    label: String,
    scale: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.42f)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale.coerceIn(0.3f, 1f) }
            .clip(RoundedCornerShape(16.dp))
            .background(WatchColors.SurfaceGlass)
            .clickable(onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, color = WatchColors.ActiveCyan)
    }
}
