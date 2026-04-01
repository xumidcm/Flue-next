package com.flue.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import kotlinx.coroutines.launch

const val EXTRA_FILE_MANAGER_MODE = "file_manager_mode"
const val EXTRA_FILE_MANAGER_RESULT_URI = "file_manager_result_uri"
const val FILE_MANAGER_MODE_IMAGE = "image"
const val FILE_MANAGER_MODE_VIDEO = "video"

class BuiltInFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_FILE_MANAGER_MODE)
            ?.takeIf { it == FILE_MANAGER_MODE_IMAGE || it == FILE_MANAGER_MODE_VIDEO }
            ?: FILE_MANAGER_MODE_IMAGE
        val requiredPermission = storagePermissionForMode(mode)
        val hasPermission = requiredPermission == null || ContextCompat.checkSelfPermission(
            this,
            requiredPermission
        ) == PackageManager.PERMISSION_GRANTED
        val permissionState = mutableStateOf(hasPermission)
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionState.value = granted
        }

        setContent {
            WatchLauncherTheme {
                BuiltInFileManagerScreen(
                    mode = mode,
                    hasPermission = permissionState.value,
                    onRequestPermission = {
                        if (requiredPermission != null) {
                            permissionLauncher.launch(requiredPermission)
                        }
                    },
                    onPick = { uri ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_FILE_MANAGER_RESULT_URI, uri.toString()))
                        finish()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

private fun storagePermissionForMode(mode: String): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return if (mode == FILE_MANAGER_MODE_VIDEO) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_MEDIA_IMAGES
    }
    return Manifest.permission.READ_EXTERNAL_STORAGE
}

@Composable
private fun BuiltInFileManagerScreen(
    mode: String,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPick: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val mediaList by produceState<List<MediaItem>?>(initialValue = null, mode, hasPermission) {
        value = if (hasPermission) queryMedia(context, mode) else emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .focusable()
            .onRotaryScrollEvent {
                scope.launch { listState.scrollBy(-it.verticalScrollPixels * 1.15f) }
                true
            }
    ) {
        Text(
            text = if (mode == FILE_MANAGER_MODE_VIDEO) "内置文件管理器（视频）" else "内置文件管理器（图片）",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (!hasPermission) {
            Text(
                text = "需要存储权限才能读取媒体文件",
                color = WatchColors.TextTertiary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            FileManagerActionButton(text = "申请权限", onClick = onRequestPermission)
            Spacer(modifier = Modifier.height(8.dp))
            FileManagerActionButton(text = "返回", onClick = onBack)
            return
        }

        if (mediaList == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = WatchColors.ActiveCyan)
            }
        } else if (mediaList!!.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (mode == FILE_MANAGER_MODE_VIDEO) "未发现可用视频" else "未发现可用图片",
                    color = WatchColors.TextTertiary,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mediaList!!, key = { it.uri.toString() }) { item ->
                    val subTitle = remember(item) { "${item.displayName}\n${item.path}" }
                    val scale = itemFisheye(listState, item.uri.toString())
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = scale.coerceIn(0.6f, 1f)
                            }
                            .background(WatchColors.SurfaceGlass, RoundedCornerShape(12.dp))
                            .clickable { onPick(item.uri) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(text = item.displayName, color = Color.White, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = subTitle, color = WatchColors.TextTertiary, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        FileManagerActionButton(text = "返回", onClick = onBack)
    }
}

@Composable
private fun itemFisheye(state: LazyListState, key: Any): Float {
    val info = state.layoutInfo.visibleItemsInfo.find { it.key == key } ?: return 0.9f
    val center = state.layoutInfo.viewportEndOffset / 2f
    val itemCenter = info.offset + info.size / 2f
    val normalized = ((itemCenter - center) / center).coerceIn(-1f, 1f)
    return (1f - kotlin.math.abs(normalized) * 0.13f).coerceIn(0.86f, 1f)
}

private data class MediaItem(
    val uri: Uri,
    val displayName: String,
    val path: String
)

private fun queryMedia(context: Context, mode: String): List<MediaItem> {
    val resolver = context.contentResolver
    val baseUri = if (mode == FILE_MANAGER_MODE_VIDEO) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.MediaColumns.RELATIVE_PATH
    } else {
        MediaStore.MediaColumns.DATA
    }
    val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    val items = mutableListOf<MediaItem>()

    fun collectItems(projection: Array<String>) {
        resolver.query(baseUri, projection, null, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndex(pathColumn)
            while (cursor.moveToNext() && items.size < 300) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).orEmpty()
                val path = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                items += MediaItem(
                    uri = ContentUris.withAppendedId(baseUri, id),
                    displayName = name.ifBlank { "未命名文件" },
                    path = path.ifBlank { "未知路径" }
                )
            }
        }
    }

    try {
        collectItems(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                pathColumn
            )
        )
    } catch (_: SQLiteException) {
        items.clear()
        collectItems(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME))
    } catch (_: IllegalArgumentException) {
        items.clear()
        collectItems(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME))
    }

    return items
}

@Composable
private fun FileManagerActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) WatchColors.SurfaceGlass else Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = if (enabled) WatchColors.ActiveCyan else WatchColors.TextTertiary,
            fontSize = 14.sp
        )
    }
}
