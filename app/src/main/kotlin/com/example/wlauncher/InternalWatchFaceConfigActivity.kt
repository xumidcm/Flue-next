package com.flue.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.home.BuiltInWatchFacePreview
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.InternalWatchFaceStorage

const val EXTRA_INTERNAL_WATCHFACE_ID = "internal_watchface_id"

class InternalWatchFaceConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val watchFaceId = intent.getStringExtra(EXTRA_INTERNAL_WATCHFACE_ID) ?: BUILT_IN_PHOTO_WATCHFACE_ID
        setContent {
            WatchLauncherTheme {
                InternalWatchFaceConfigScreen(
                    watchFaceId = watchFaceId,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun InternalWatchFaceConfigScreen(
    watchFaceId: String,
    onBack: () -> Unit
) {
    val vm: LauncherViewModel = viewModel()
    val photoPath by vm.builtInPhotoPath.collectAsState()
    val videoPath by vm.builtInVideoPath.collectAsState()
    val isPhoto = watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID
    val currentPath = if (isPhoto) photoPath else videoPath

    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            handlePickedMedia(
                viewModel = vm,
                watchFaceId = watchFaceId,
                sourceUri = uri,
                onMessage = { message ->
                    Toast.makeText(vm.getApplication(), message, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isPhoto) "\u56FE\u7247\u8868\u76D8\u8BBE\u7F6E" else "\u89C6\u9891\u8868\u76D8\u8BBE\u7F6E",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (isPhoto) "\u9009\u62E9\u4E00\u5F20\u56FE\u7247\u4F5C\u4E3A\u8868\u76D8\u80CC\u666F" else "\u9009\u62E9\u4E00\u4E2A\u89C6\u9891\u4F5C\u4E3A\u8868\u76D8\u80CC\u666F",
            color = WatchColors.TextTertiary,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(22.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF10141D)),
            contentAlignment = Alignment.Center
        ) {
            BuiltInWatchFacePreview(
                watchFaceId = watchFaceId,
                photoPath = photoPath,
                videoPath = videoPath,
                showClock = true,
                playVideo = true,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        ActionButton(
            text = if (currentPath.isNullOrBlank()) {
                if (isPhoto) "\u9009\u62E9\u56FE\u7247" else "\u9009\u62E9\u89C6\u9891"
            } else {
                if (isPhoto) "\u66F4\u6362\u56FE\u7247" else "\u66F4\u6362\u89C6\u9891"
            }
        ) {
            picker.launch(arrayOf(if (isPhoto) "image/*" else "video/*"))
        }

        Spacer(modifier = Modifier.height(10.dp))

        ActionButton(
            text = if (isPhoto) "\u6E05\u9664\u56FE\u7247" else "\u6E05\u9664\u89C6\u9891",
            enabled = !currentPath.isNullOrBlank()
        ) {
            if (isPhoto) vm.setBuiltInPhotoPath(null) else vm.setBuiltInVideoPath(null)
        }

        Spacer(modifier = Modifier.height(10.dp))

        ActionButton(text = "\u8FD4\u56DE", onClick = onBack)
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) WatchColors.SurfaceGlass else Color.White.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) WatchColors.ActiveCyan else WatchColors.TextTertiary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun handlePickedMedia(
    viewModel: LauncherViewModel,
    watchFaceId: String,
    sourceUri: Uri,
    onMessage: (String) -> Unit
) {
    val context = viewModel.getApplication<Application>()
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            sourceUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
    val savedPath = if (watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID) {
        InternalWatchFaceStorage.copyPhoto(context, sourceUri)
    } else {
        InternalWatchFaceStorage.copyVideo(context, sourceUri)
    }

    if (savedPath.isNullOrBlank()) {
        onMessage("\u4FDD\u5B58\u5A92\u4F53\u5931\u8D25")
        return
    }

    if (watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID) {
        viewModel.setBuiltInPhotoPath(savedPath)
    } else {
        viewModel.setBuiltInVideoPath(savedPath)
    }
    onMessage("\u8868\u76D8\u5A92\u4F53\u5DF2\u66F4\u65B0")
}
