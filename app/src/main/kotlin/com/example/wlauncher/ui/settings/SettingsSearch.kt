package com.flue.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * 设置搜索索引条目。destinationName 用字符串（[Enum.name]）解耦，
 * SettingsActivity 侧解析成 SettingsDestination；itemKey 对应目标页
 * LazyColumn 的 item key，用于跳转后定位与高亮。
 */
data class SettingsSearchEntry(
    val destinationName: String,
    val itemKey: String?,
    val title: String,
    val description: String = "",
    /** 拼音首字母 / 英文别名等额外命中词。 */
    val keywords: List<String> = emptyList(),
)

/** 大小写不敏感的标题/描述/关键词匹配；标题命中排前。 */
fun searchSettingsEntries(
    query: String,
    entries: List<SettingsSearchEntry>,
): List<SettingsSearchEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val lower = trimmed.lowercase()
    val titleHits = ArrayList<SettingsSearchEntry>()
    val otherHits = ArrayList<SettingsSearchEntry>()
    entries.forEach { entry ->
        when {
            entry.title.lowercase().contains(lower) -> titleHits += entry
            entry.description.lowercase().contains(lower) ||
                entry.keywords.any { it.lowercase().contains(lower) } -> otherHits += entry
        }
    }
    return titleHits + otherHits
}

/**
 * MIUIX 风格胶囊搜索框（各风格通用，取色走令牌）。
 *
 * @param scale 圆屏弦宽缩放。默认 1f = 不缩放（方屏与其他调用点不受影响）。
 *   只走 graphicsLayer，不动 height/padding —— 改布局属性会触发重测量，正是
 *   圆屏适配抽搐的根因。
 */
@Composable
fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索设置",
    scale: Float = 1f,
) {
    val style = LauncherTheme.style
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(style.cardColor)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = style.secondaryTextColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, color = style.secondaryTextColor, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = style.titleColor, fontSize = 13.sp),
                cursorBrush = SolidColor(style.accentColor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "清空",
                tint = style.secondaryTextColor,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}
