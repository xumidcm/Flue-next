package com.flue.launcher.ui.store

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.throwIfCancellation
import com.flue.launcher.ui.theme.LauncherStyle
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchface.store.WatchStoreRepository

internal fun storeAvatarCacheKey(avatarUrl: String?, avatarVersion: Long): String =
    "${avatarUrl?.trim().orEmpty()}#$avatarVersion"

internal fun storeAvatarBitmapStateKey(avatarUrl: String?, targetPx: Int): String =
    "${avatarUrl?.trim().orEmpty()}@$targetPx"

/**
 * 圆形头像，取不到图就退回首字母（H2 / H3 / H5 共用）。
 *
 * ## 为什么失败态是首字母而不是一个通用小人图标
 *
 * 头像取不到有三种常见原因：用户没设过、地址落在站外被 [WatchStoreRepository.loadSiteImage]
 * 拒了、网络不通。三种情况下**这一行仍然要能认出是谁**——评论区一整列一模一样的小人图标
 * 等于没有信息。首字母 + 稳定底色至少能让同一个人在不同行看起来一致。
 *
 * 底色由名字取模挑一档，同一个名字永远同一个颜色（不随机、不跟进入顺序变）。
 */
@Composable
internal fun StoreAvatar(
    repository: WatchStoreRepository,
    avatarUrl: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    avatarVersion: Long = 0L,
) {
    val style = LauncherTheme.style
    val density = LocalDensity.current
    // 解码目标按实际显示尺寸取整到像素，再夹到缓存约定的头像档位：一屏十几个头像，
    // 每个都按原图解码会把内存 LRU 里的封面全挤出去。
    val targetPx = remember(size, density) {
        with(density) { size.toPx() }
            .toInt()
            .coerceIn(24, WatchStoreRepository.AVATAR_TARGET_SIZE)
    }
    val trimmedUrl = avatarUrl?.trim().orEmpty()
    val cacheKey = storeAvatarCacheKey(trimmedUrl, avatarVersion)
    // Version changes must re-fetch the image, but must not clear the old bitmap
    // first. This keeps the cached avatar visible until the replacement arrives.
    var bitmap by remember(storeAvatarBitmapStateKey(trimmedUrl, targetPx)) {
        mutableStateOf<Bitmap?>(repository.peekSiteImage(trimmedUrl, targetPx))
    }

    LaunchedEffect(repository, cacheKey, targetPx) {
        if (trimmedUrl.isEmpty()) return@LaunchedEffect
        runCatching { repository.loadSiteImage(trimmedUrl, targetPx) }
            .onSuccess { bitmap = it }
            .onFailure { error ->
                error.throwIfCancellation()
                // 失败不报错：这一格退回首字母就行，不值得在评论列表里摆一堆「重试」。
                bitmap = null
            }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val initial = remember(name) { storeAvatarInitial(name) }
    val fallbackTint = remember(name, style) { storeAvatarTint(name, style) }
    val clickable = onClick != null || onLongClick != null

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (image != null) style.pressedCardColor else fallbackTint)
            .then(
                if (clickable) {
                    Modifier.combinedClickable(
                        onLongClick = onLongClick,
                        onClick = onClick ?: {},
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop 而不是 Fit：上游不保证头像是方的，Fit 会在圆里留两条空边。
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = initial,
                color = style.titleColor,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 首字母。上游用户名字符集是 `[a-zA-Z0-9_]`，所以按 `take(1)` 取不会切坏代理对；
 * 万一名字是空的（服务端字段缺失），给一个中性的 `?` 而不是空白圆。
 */
internal fun storeAvatarInitial(name: String): String =
    name.trim().take(1).uppercase().ifBlank { "?" }

/** 名字 → 固定底色。同一个人在任何一页都是同一个颜色。 */
private fun storeAvatarTint(
    name: String,
    style: LauncherStyle,
): androidx.compose.ui.graphics.Color {
    val palette = listOf(
        style.accentColor,
        style.titleColor,
        style.secondaryTextColor,
    )
    val index = (name.trim().hashCode().toLong() and 0xFFFFFFFFL) % palette.size
    return palette[index.toInt()].copy(alpha = 0.24f)
}
