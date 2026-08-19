package com.flue.launcher.ui.settings

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用选择器：**表盘编辑器与「表盘点击动作」共用的唯一一份**。
 *
 * 以前两处各有一份实现：编辑器那份没有图标、用的是 MaterialTheme 的颜色（在 miuix /
 * Apple 档下明显跳脸），点击动作那份有图标但每次打开都把所有图标重解一遍。合并之后
 * 观感与性能只需要维护一处。
 *
 * 性能：名单走 [InstalledLaunchableAppCache]（进程级 + TTL），图标**按可见行懒取**并常驻
 * 缓存（见该对象的注释）—— 首屏只解看得见的那几个，第二次打开是纯命中。
 *
 * @param subtitle 副标题。点击动作那边传的是表盘 XML 里的原始动作名（`w_music · m_music`），
 *   编辑器那边传用途说明。
 * @param selected 当前已选组件，用于在列表里标出「当前」。
 * @param clearLabel 非 null 时出「清除」按钮（点击动作用它解除映射）。
 */
@Composable
internal fun WatchAppPickerDialog(
    title: String,
    subtitle: String?,
    selected: ComponentName?,
    onPick: (InstalledLaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledLaunchableApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { InstalledLaunchableAppCache.load(context) }
    }
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, color = style.titleColor, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(7.dp))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.watch_editor_search_app_hint)) },
                    singleLine = true,
                    shape = style.rowShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    // 固定高度：弹窗卡片本身可滚，这里再让列表按内容长高会撑出无限
                    // 高度约束（FlueDialog 里嵌 verticalScroll 崩过一次，同一个坑）。
                    modifier = Modifier.height(250.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { "${it.packageName}/${it.activityClass}" }) { app ->
                        WatchAppPickerRow(
                            app = app,
                            selected = app.component == selected,
                            onClick = { onPick(app) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (clearLabel != null && onClear != null) {
                TextButton(onClick = onClear) {
                    Text(clearLabel, color = style.secondaryTextColor)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.watch_editor_cancel),
                        color = style.secondaryTextColor,
                    )
                }
            }
        },
        dismissButton = if (clearLabel != null && onClear != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(android.R.string.cancel),
                        color = style.secondaryTextColor,
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun WatchAppPickerRow(
    app: InstalledLaunchableApp,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    // 懒取图标：只有这一行真的被组合（= 进了视口）才解码，命中缓存时首帧就有图。
    val icon by produceState(
        initialValue = InstalledLaunchableAppCache.cachedIcon(app.component),
        key1 = app.component,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                InstalledLaunchableAppCache.icon(context, app.component)
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val current = icon
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(style.compactShape),
            )
        } else {
            // 占位保持行高与左边距稳定：图标晚到时列表不会整体抖一下。
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(style.compactShape)
                    .background(style.pressedCardColor),
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                color = if (selected) style.accentColor else style.bodyColor,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 包名跟着出：同名应用（多用户/多渠道）只有包名能区分。
            Text(
                app.packageName,
                color = style.secondaryTextColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.settings_tap_actions_current),
                color = style.accentColor,
                fontSize = 10.sp,
            )
        }
    }
}
