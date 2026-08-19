package com.flue.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import java.util.Locale

/**
 * 表盘编辑器「点击动作 → 预设函数」选择器：WatchMaker 的可配置命名动作
 * （`WM_TAP_ACTIONS.md` §3，`w_music` / `w_dialer` 这一族）。
 *
 * 为什么要有它：编辑器原来只摆了「日历」「天气」两个写死的按钮（`widget_calendar` /
 * `widget_weather`），剩下 20 多个命名动作只能**手打进输入框**。而动作名打错一个字母，
 * [com.flue.launcher.watchface.standard.WatchTapActionParser] 直接归到 `Unsupported`
 * —— 表盘上点下去只弹一句「动作不可用」，编辑时完全看不出是拼错了。给全名单就没这问题。
 *
 * 名单**复用 [WatchTapActionCatalog]**，也就是「设置 → 表盘点击动作」用的同一份。这一点是
 * 刻意的：编辑器写进图层的动作名，必须正好是用户在设置页能绑定应用的那个键，两边各存一份
 * 名单迟早会漂 —— 一漂就是「表盘写了 A、映射存在 B」，点下去静默无反应，最难查的那种。
 */
@Composable
internal fun WatchTapActionPresetDialog(
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    var query by remember { mutableStateOf("") }
    // 图层现有的动作名。别名也算命中（表盘作者可能写的是 `m_music`），所以比较的是
    // 整个 mappingKeys 而不只是首选键 —— 否则明明选过的那一行不显示「当前」。
    val selected = remember(current) { current?.trim()?.lowercase(Locale.ROOT).orEmpty() }
    // 过滤同时看本地化标题和原始动作名：知道自己要 `w_music` 的人会直接打函数名，
    // 只知道「音乐」的人打中文，两条路都得通。
    val groups = remember(query) {
        val keyword = query.trim()
        WatchTapActionCatalog.categories.mapNotNull { category ->
            val hits = category.actions.filter { action ->
                keyword.isEmpty() ||
                    context.getString(action.titleRes).contains(keyword, ignoreCase = true) ||
                    action.mappingKeys.any { it.contains(keyword, ignoreCase = true) }
            }
            if (hits.isEmpty()) null else category to hits
        }
    }

    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.watch_editor_tap_action_preset),
                color = style.titleColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                // 说清楚「选了之后还要去哪儿绑应用」：命名动作只是个名字，打开哪个应用
                // 由设置页的映射决定，不写这句用户会以为选完就该能跳转。
                Text(
                    stringResource(R.string.watch_editor_tap_action_preset_sub),
                    color = style.secondaryTextColor,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(stringResource(R.string.watch_editor_tap_action_preset_search))
                    },
                    singleLine = true,
                    shape = style.rowShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    // 固定高度，和应用选择器同一个理由：弹窗卡片自己会滚，列表再按内容
                    // 长高就会撞上无限高度约束（FlueDialog 里嵌 verticalScroll 崩过一次）。
                    modifier = Modifier.height(250.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { (category, actions) ->
                        item(key = "category_${category.id}") {
                            Text(
                                stringResource(category.titleRes),
                                color = style.secondaryTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                        items(actions, key = { "preset_${it.id}" }) { action ->
                            WatchTapActionPresetRow(
                                action = action,
                                selected = action.mappingKeys.any { it == selected },
                                onClick = { onPick(action.canonicalKey) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.watch_editor_cancel),
                    color = style.secondaryTextColor,
                )
            }
        },
    )
}

@Composable
private fun WatchTapActionPresetRow(
    action: WatchTapActionDefinition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(action.titleRes),
                color = if (selected) style.accentColor else style.bodyColor,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 真正会写进表盘 XML 的那个字符串。刻意不本地化、不大写化：它是标识符，
            // 不是文案 —— 而且给「照着别的表盘抄动作名」的人一个对照。
            Text(
                action.canonicalKey,
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
