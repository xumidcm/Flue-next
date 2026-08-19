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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.flue.launcher.R
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

/** [validateSettingsSearchIndex] 找出来的一条问题。 */
data class SettingsSearchIndexProblem(val entryTitle: String, val reason: String)

/**
 * 索引自检。
 *
 * 存在理由：`SettingsActivity` 侧解析 `destinationName` 时外面裹着 `runCatching`，
 * **写错的枚举名和不存在的 itemKey 都会静默失效**（点进去只落在页顶，没有任何报错）。
 * 这类错误历史上出现过好几个（`advanced_material` / `edge_blur` / `ui_style` /
 * `screen_shape` / `watchface_date_locale` / `watchmaker_force_fill`），只能靠自动化守。
 *
 * 纯函数：调用方把「合法枚举名」与「该页真实存在的 item key」传进来。
 *
 * 同一行有**多个标题不同的别名条目是允许的** —— 设置项被搬到子页面之后，只能靠指向那个
 * 入口行的别名让原来的名字仍然搜得到。
 *
 * @param knownItemKeys 每个 destination 下真实存在的 item key。**留空的 destination 不检查
 *   itemKey** —— 页面里有些行是 `items(list)` 动态生成的（图标包、语言、动效档案），
 *   把它们全枚举进测试只会让测试变成第二份页面清单。
 */
fun validateSettingsSearchIndex(
    entries: List<SettingsSearchEntry>,
    validDestinationNames: Set<String>,
    knownItemKeys: Map<String, Set<String>> = emptyMap(),
): List<SettingsSearchIndexProblem> {
    val problems = ArrayList<SettingsSearchIndexProblem>()
    // 身份带上标题：同一行**允许**有多个别名条目（「强制铺满」这一项搬进画布调节页之后
    // 就只能靠指向那个入口行的别名被搜到）。要防的是标题也一样的复制粘贴条目。
    val seen = HashSet<Triple<String, String?, String>>()
    entries.forEach { entry ->
        if (entry.destinationName !in validDestinationNames) {
            problems += SettingsSearchIndexProblem(
                entry.title,
                "destination \"${entry.destinationName}\" is not a SettingsDestination",
            )
        }
        if (entry.title.isBlank()) {
            problems += SettingsSearchIndexProblem(
                entry.itemKey ?: entry.destinationName,
                "title is blank",
            )
        }
        if (!seen.add(Triple(entry.destinationName, entry.itemKey, entry.title))) {
            problems += SettingsSearchIndexProblem(
                entry.title,
                "duplicate entry ${entry.destinationName}/${entry.itemKey}",
            )
        }
        val known = knownItemKeys[entry.destinationName]
        if (entry.itemKey != null && known != null && known.isNotEmpty() && entry.itemKey !in known) {
            problems += SettingsSearchIndexProblem(
                entry.title,
                "itemKey \"${entry.itemKey}\" does not exist on ${entry.destinationName}",
            )
        }
    }
    return problems
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
    placeholder: String = stringResource(R.string.settings_search_placeholder),
    scale: Float = 1f,
) {
    val style = LauncherTheme.style
    // 输入文本与占位文本必须共用同一份行盒度量。以前占位是 Text（会合并
    // LocalTextStyle：字族 / 字距 / 行高全在）、输入是裸 TextStyle(color, fontSize)
    // （这些全丢），两套度量算出两条基线 —— 于是闪烁的光标和「搜索设置」四个字
    // 对不齐。includeFontPadding=false 再去掉字体自带的上下不对称留白。
    val fieldTextStyle = LocalTextStyle.current.merge(
        TextStyle(
            fontSize = 13.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
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
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = fieldTextStyle.copy(color = style.titleColor),
            cursorBrush = SolidColor(style.accentColor),
            modifier = Modifier.weight(1f),
            // 占位画在 BasicTextField 自己的装饰层里、与 innerTextField 同框，
            // 位置由它决定而不是外面并排一个 Text 去猜。
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = fieldTextStyle,
                            color = style.secondaryTextColor,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.action_clear),
                tint = style.secondaryTextColor,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}
