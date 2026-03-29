package com.flue.launcher

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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_SELECTED_WATCHFACE_ID
import com.flue.launcher.viewmodel.dataStore
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import com.flue.launcher.watchface.LunchWatchFaceScanner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue

class WatchFaceChooserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                WatchFaceChooserScreen(
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun WatchFaceChooserScreen(
    onDismiss: () -> Unit
) {
    val vm: LauncherViewModel = viewModel()
    val prefs by dataStore.data.collectAsState(initial = null)
    val watchFaces by vm.availableWatchFaces.collectAsState()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.refreshWatchFaces()
    }

    val persistedSelectedId = prefs?.get(KEY_SELECTED_WATCHFACE_ID) ?: selectedWatchFaceId
    val pagerState = rememberPagerState(initialPage = 0) { watchFaces.size.coerceAtLeast(1) }

    LaunchedEffect(watchFaces, persistedSelectedId) {
        if (watchFaces.isEmpty()) return@LaunchedEffect
        val targetPage = watchFaces.indexOfFirst { it.id == persistedSelectedId }
            .takeIf { it >= 0 }
            ?: 0
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(watchFaces, pagerState, persistedSelectedId) {
        snapshotFlow { if (watchFaces.isEmpty()) -1 else pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page in watchFaces.indices) {
                    val descriptor = watchFaces[page]
                    if (descriptor.id != persistedSelectedId) {
                        vm.selectWatchFace(descriptor.id)
                    }
                }
            }
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
        if (currentDescriptor == null) {
            Text(
                text = "No watch faces available",
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
                text = if (currentDescriptor.summary.isNotBlank()) currentDescriptor.summary else "Installed watch face",
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
                    .weight(1f, fill = false)
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
                if (currentDescriptor.settingsEntryClassName != null) {
                    Box(modifier = Modifier.weight(1f)) {
                        ChooserButton("Settings", 1f) {
                            if (!LunchWatchFaceRuntime.openSettings(context, currentDescriptor)) {
                                Toast.makeText(context, "No settings page available", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    ChooserButton("Done", 1f, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun WatchFacePreviewCard(
    descriptor: LunchWatchFaceDescriptor,
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
            BuiltInWatchFacePreview()
        } else {
            WatchFacePreviewDrawable(descriptor)
        }
    }
}

@Composable
private fun BuiltInWatchFacePreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0F1B2F), Color(0xFF08101C), Color.Black)
                )
            )
            .padding(28.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "10:08",
                color = Color.White,
                fontSize = 54.sp,
                fontWeight = FontWeight.W200
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Built-in",
                color = WatchColors.ActiveCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
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
            .fillMaxWidth()
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
