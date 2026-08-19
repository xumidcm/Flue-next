package com.flue.launcher.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.R
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchface.standard.WatchTapActionMappings

@Composable
internal fun WatchTapActionBindingCard(
    action: WatchTapActionDefinition,
    revision: Int,
    scale: Float,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val mappings = remember(context) { WatchTapActionMappings.of(context) }
    val component = remember(action.mappingKeys, revision) {
        action.mappingKeys.firstNotNullOfOrNull(mappings::component)
    }
    val app = remember(component, revision) {
        component?.let { current ->
            runCatching {
                val manager = context.packageManager
                val info = manager.getApplicationInfo(current.packageName, 0)
                val label = manager.getApplicationLabel(info).toString()
                val icon = manager.getApplicationIcon(info).toBitmap(72, 72).asImageBitmap()
                label to icon
            }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(style.cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(action.titleRes),
                color = style.titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // 表盘 XML 里写的原始动作名（`w_music · m_music`）。
            // 这一行是给**对着表盘找映射**的人看的：表盘作者写的是这些名字，
            // 而上面那行是本地化标题，两者对不上时根本不知道该配哪一项。
            // 刻意不本地化、不大写化 —— 它是标识符，不是文案。
            Text(
                action.mappingKeys.joinToString(" · "),
                color = style.secondaryTextColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app?.first?.let { stringResource(R.string.settings_tap_actions_bound_fmt, it) }
                    ?: stringResource(action.descriptionRes),
                color = if (app == null) style.secondaryTextColor else style.accentColor,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (app?.second != null) {
            Image(
                bitmap = app.second,
                contentDescription = app.first,
                modifier = Modifier.size(36.dp).clip(style.compactShape),
            )
        } else {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.settings_tap_actions_pick_app),
                    tint = style.secondaryTextColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun WatchTapActionAppPickerDialog(
    action: WatchTapActionDefinition,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val mappings = remember(context) { WatchTapActionMappings.of(context) }
    val current = remember(action.mappingKeys) {
        action.mappingKeys.firstNotNullOfOrNull(mappings::component)
    }
    // 界面本体是与表盘编辑器**共用**的那一份（名单缓存 + 图标懒取都在里面）；
    // 这里只负责把选择结果写进映射表 —— 一个动作的多个别名一起写，
    // 这样表盘写 w_music 还是 m_music 都能命中。
    WatchAppPickerDialog(
        title = stringResource(action.titleRes),
        // 副标题给原始动作名：表盘作者写在 XML 里的是这些键，本地化标题对不上。
        subtitle = action.mappingKeys.joinToString(" · "),
        selected = current,
        onPick = { app ->
            action.mappingKeys.forEach { key ->
                mappings.write(key, app.packageName, app.activityClass)
            }
            onChanged()
            onDismiss()
        },
        onDismiss = onDismiss,
        clearLabel = stringResource(R.string.settings_tap_actions_clear),
        onClear = {
            action.mappingKeys.forEach(mappings::clear)
            onChanged()
            onDismiss()
        },
    )
}
