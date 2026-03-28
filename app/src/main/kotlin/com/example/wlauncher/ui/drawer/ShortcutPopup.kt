package com.flue.launcher.ui.drawer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.data.model.AppInfo
import kotlinx.coroutines.delay

@Composable
fun AppShortcutOverlay(
    app: AppInfo,
    blurEnabled: Boolean = true,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var showing by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) { showing = true }

    fun animateDismiss() {
        dismissing = true
        showing = false
    }

    LaunchedEffect(dismissing) {
        if (dismissing) {
            delay(250)
            onDismiss()
        }
    }

    val animAlpha by animateFloatAsState(
        targetValue = if (showing && !dismissing) 1f else 0f,
        animationSpec = tween(200),
        label = "overlay_alpha"
    )
    val animScale by animateFloatAsState(
        targetValue = if (showing && !dismissing) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "overlay_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = animAlpha }
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(indication = null, interactionSource = dismissInteraction) { animateDismiss() },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelWidth = (maxWidth * 0.86f).coerceIn(220.dp, 360.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        scaleX = animScale
                        scaleY = animScale
                    }
                    .clickable(indication = null, interactionSource = blockInteraction) { }
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2C2C2E))
                ) {
                    ShortcutMenuItem("应用信息") {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${app.packageName}")
                        })
                        onDismiss()
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF48484A)))
                    ShortcutMenuItem("卸载", Color(0xFFFF453A)) {
                        val packageUri = Uri.parse("package:${app.packageName}")
                        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                            data = packageUri
                            putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        }
                        val fallbackIntent = Intent(Intent.ACTION_DELETE).apply {
                            data = packageUri
                        }
                        try {
                            context.startActivity(uninstallIntent)
                        } catch (_: Exception) {
                            context.startActivity(fallbackIntent)
                        }
                        onDismiss()
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    bitmap = app.cachedIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ShortcutMenuItem(text: String, color: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.W500)
    }
}

fun vibrateHaptic(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    } catch (_: Exception) {
    }
}
