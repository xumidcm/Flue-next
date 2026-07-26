package com.flue.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * 设置页顶部搜索框。
 *
 * 单行、无边框、圆角胶囊，配色全部走 [LauncherTheme.style]，所以三种 UI 风格
 * 下都自动对味；图标用 MIUIX 线性图标（放大镜 / 叉号这两个形状本来就没有
 * 风格差异，不必再按风格分支）。
 */
@Composable
fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索设置",
) {
    val style = LauncherTheme.style
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(style.bubbleShape)
            .background(style.cardColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Search,
            contentDescription = null,
            tint = style.secondaryTextColor,
            modifier = Modifier.size(18.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    color = style.secondaryTextColor,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = style.titleColor, fontSize = 14.sp),
                cursorBrush = SolidColor(style.accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = MiuixIcons.Close,
                contentDescription = null,
                tint = style.secondaryTextColor,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}
