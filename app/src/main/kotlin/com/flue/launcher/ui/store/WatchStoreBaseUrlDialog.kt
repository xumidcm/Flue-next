package com.flue.launcher.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.flue.launcher.ui.style.FlueAlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.watchface.store.WatchStoreUiPreferences

/**
 * 自定义表盘市场地址弹窗。
 *
 * 设置页与表盘市场共用这一份：市场拉取失败时「自定义市场 URL」要**就地**弹这个框，
 * 而不是把用户丢到整个工具页去自己找（以前是 startActivity 跳 SETTINGS_DESTINATION_TOOLS）。
 *
 * 校验不在这里重写 —— [WatchStoreUiPreferences.writeBaseUrl] 内部已经过
 * `WatchStoreUrlPolicy.normalizeBaseUrl`，非法地址直接抛异常。
 *
 * @param onSaved 保存成功后各自的收尾：设置页清封面缓存 + Toast，市场侧 recreate() 重建仓库。
 */
@Composable
fun WatchStoreBaseUrlDialog(
    preferences: WatchStoreUiPreferences,
    onSaved: (savedUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(preferences.readBaseUrl()) }
    var allowLocalNetwork by remember { mutableStateOf(preferences.readAllowLocalNetwork()) }
    var error by remember { mutableStateOf<String?>(null) }
    val isHttp = draft.trim().startsWith("http://", ignoreCase = true)
    val invalidMessage = stringResource(R.string.settings_watch_store_market_url_invalid)

    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_watch_store_market_url_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    label = { Text(stringResource(R.string.settings_watch_store_market_url)) },
                    placeholder = {
                        Text(stringResource(R.string.settings_watch_store_market_url_hint))
                    },
                )
                error?.let { Text(it, color = WatchColors.ActiveRed) }
                if (isHttp) {
                    Text(
                        stringResource(R.string.settings_watch_store_market_url_http_warning),
                        color = WatchColors.ActiveRed,
                        fontSize = 12.sp,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowLocalNetwork,
                        onCheckedChange = { allowLocalNetwork = it },
                    )
                    Text(
                        stringResource(R.string.settings_watch_store_market_url_allow_local),
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { preferences.writeBaseUrl(draft, allowLocalNetwork) }
                    .onSuccess(onSaved)
                    .onFailure { error = invalidMessage }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        // 「恢复默认」是第三个动作，走 extraAction 独占一行胶囊 —— 以前和「取消」包在同一个
        // Row 里塞进 dismissButton，两个选项就挤进了同一枚胶囊（老师报的问题）。
        extraAction = {
            TextButton(onClick = {
                preferences.resetBaseUrl()
                draft = preferences.readBaseUrl()
                error = null
            }) { Text(stringResource(R.string.settings_watch_store_market_url_reset)) }
        },
    )
}
