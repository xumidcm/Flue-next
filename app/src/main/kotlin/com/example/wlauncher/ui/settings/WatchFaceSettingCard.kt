package com.flue.launcher.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.R
import com.flue.launcher.ui.home.BuiltInWatchFacePreview
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.RoundElementRole
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.roundAwareCompactShape
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceDescriptors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings

private val BUILT_IN_WATCHFACE_IDS = setOf(
    BUILT_IN_WATCHFACE_ID,
    BUILT_IN_PHOTO_WATCHFACE_ID,
    BUILT_IN_VIDEO_WATCHFACE_ID,
)

@Composable
fun WatchFaceSettingCard(
    descriptor: WatchFaceDescriptor,
    selected: Boolean,
    scale: Float,
    uiStyle: UiStyle = UiStyle.APPLE_WATCH,
    builtInPhotoPath: String? = null,
    builtInVideoPath: String? = null,
    photoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    videoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    onSelect: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /**
     * 表盘刷新令牌。编辑保存会原地覆盖 preview.jpg —— 路径和 stableKey 都
     * 不变，只有文件内容变了。令牌自增时重新 stat 一次文件，缩略图才会跟着
     * 换；否则列表里永远是原作者那张预览图。
     */
    refreshToken: Int = 0,
) {
    val cardInteraction = remember { MutableInteractionSource() }
    val cardPressed by cardInteraction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (cardPressed) 0.965f else 1f,
        animationSpec = spring(stiffness = 780f, dampingRatio = 0.72f),
        label = "watchface_card_press_scale"
    )
    val launcherStyle = LauncherTheme.style
    val cardShape = launcherStyle.roundAwareCompactShape(RoundElementRole.CAPSULE)
    val cardPadding = if (isFlueScreenRound()) 20.dp else 14.dp
    // 表盘管理属于 Activity 的本地化界面。内置项不能沿用由
    // Application/ViewModel 缓存的 displayName，否则应用内切换语言后仍会显示旧语言。
    val localizedDisplayName = when (descriptor.id) {
        BUILT_IN_WATCHFACE_ID -> stringResource(R.string.watchface_builtin_default_name)
        BUILT_IN_PHOTO_WATCHFACE_ID -> stringResource(R.string.watchface_builtin_photo_name)
        BUILT_IN_VIDEO_WATCHFACE_ID -> stringResource(R.string.watchface_builtin_video_name)
        else -> descriptor.displayName
    }
    val localizedSummary = if (descriptor.id in BUILT_IN_WATCHFACE_IDS) {
        stringResource(R.string.watchface_builtin_summary)
    } else {
        descriptor.summary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressScale
                scaleY = scale * pressScale
                alpha = scale.coerceIn(0.3f, 1f)
            }
            .clip(cardShape)
            .background(if (selected) launcherStyle.selectedCardColor else launcherStyle.cardColor)
            .clickable(interactionSource = cardInteraction, indication = null, onClick = onSelect)
            .padding(horizontal = cardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (descriptor.isBuiltin) {
            BuiltInWatchFacePreview(
                watchFaceId = descriptor.id,
                photoPath = builtInPhotoPath,
                videoPath = builtInVideoPath,
                uiStyle = uiStyle,
                photoOptions = photoOptions,
                videoOptions = videoOptions,
                showClock = false,
                playVideo = false,
                showBottomFade = false,
                modifier = Modifier
                    .size(52.dp)
                    .clip(launcherStyle.compactShape)
            )
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            // 编辑保存会原地覆盖 preview.jpg：路径和 stableKey 都不变，只有
            // 文件本身变了。单用 stableKey 做 key 会让 produceState 不重跑，
            // 列表里永远显示原作者那张预览图（老师报的问题）。把文件指纹
            // 一起算进 key，和 externalWatchFacePaletteCacheKey 同一口径。
            val previewFingerprint = remember(
                descriptor.stableKey,
                descriptor.previewFilePath,
                refreshToken,
            ) {
                val previewFile = descriptor.previewFilePath?.let { java.io.File(it) }
                if (previewFile != null && previewFile.isFile) {
                    "${previewFile.lastModified()}|${previewFile.length()}"
                } else {
                    ""
                }
            }
            val previewBitmap by produceState<ImageBitmap?>(
                initialValue = null,
                key1 = descriptor.stableKey,
                key2 = previewFingerprint,
            ) {
                value = withContext(Dispatchers.Default) {
                    WatchFaceDescriptors.loadPreviewDrawable(context, descriptor)
                        ?.toBitmap(120, 120)
                        ?.asImageBitmap()
                }
            }
            val bitmap = previewBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(launcherStyle.compactShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(launcherStyle.compactShape)
                        .background(Color.White.copy(alpha = 0.08f))
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(modifier = Modifier.padding(end = 56.dp)) {
                Text(localizedDisplayName, fontSize = 14.sp, color = launcherStyle.titleColor)
                Spacer(modifier = Modifier.height(2.dp))
                if (localizedSummary.isNotBlank()) {
                    Text(
                        localizedSummary,
                        fontSize = 11.sp,
                        color = launcherStyle.secondaryTextColor
                    )
                }
                val meta = buildString {
                    if (descriptor.author != null) {
                        append(descriptor.author)
                    }
                    if (descriptor.versionCode > 0) {
                        if (isNotEmpty()) append("  ·  ")
                        append("v")
                        append(descriptor.versionCode)
                    }
                }
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(meta, fontSize = 11.sp, color = launcherStyle.secondaryTextColor)
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(launcherStyle.topBarChipColor)
                            .clickable(onClick = onDelete)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(MiuixIcons.Delete, contentDescription = null, tint = Color(0xFFFF6B6B))
                    }
                } else if (onOpenSettings != null && descriptor.supportsSettings) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(launcherStyle.topBarChipColor)
                            .clickable(onClick = onOpenSettings)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(MiuixIcons.Settings, contentDescription = null, tint = launcherStyle.accentColor)
                    }
                }
                if (selected) {
                    Icon(MiuixIcons.Ok, contentDescription = null, tint = launcherStyle.accentColor)
                }
            }
        }
    }
}
