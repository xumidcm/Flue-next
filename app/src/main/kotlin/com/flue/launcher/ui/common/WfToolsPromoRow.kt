package com.flue.launcher.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.flue.launcher.R
import com.flue.launcher.ui.theme.LauncherTheme

/** WFTools 仓库地址。站外仓库，明确走系统浏览器而不是内置 WebView。 */
const val WF_TOOLS_URL = "https://gitlab.com/ailife8881/wftools"

/**
 * 表盘编辑 / 表盘设置页最底部的一行灰色小字，指向 WFTools。
 *
 * 颜色取 [LauncherTheme] 的次级文字令牌而不是写死灰色 —— 三套风格的次级色不同，
 * 写死会在其中两套里读成「禁用」。
 *
 * @param scale 圆屏弦宽缩放，只走 graphicsLayer（改布局属性会触发重测量）。
 */
@Composable
fun WfToolsPromoRow(
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clickable { openWfTools(context) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.wf_tools_promo),
            color = style.secondaryTextColor,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 用系统默认浏览器打开 WFTools 仓库。
 *
 * 手表常常没装浏览器，所以 `resolveActivity` 为空时给提示而不是让 startActivity 抛。
 * FLAG_ACTIVITY_NEW_TASK 是必须的：调用方可能是没有任务栈的设置页。
 */
private fun openWfTools(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WF_TOOLS_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val handled = intent.resolveActivity(context.packageManager) != null &&
        runCatching { context.startActivity(intent) }.isSuccess
    if (!handled) {
        Toast.makeText(
            context,
            context.getString(R.string.wf_tools_no_browser_fmt, WF_TOOLS_URL),
            Toast.LENGTH_LONG,
        ).show()
    }
}
