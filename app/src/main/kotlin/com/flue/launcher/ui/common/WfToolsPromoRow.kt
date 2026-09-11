package com.flue.launcher.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.flue.launcher.R
import com.flue.launcher.ui.theme.LauncherTheme

/** WFTools 仓库地址。站外仓库，明确走系统浏览器而不是内置 WebView。 */
const val WF_TOOLS_URL = "https://gitlab.com/ailife8881/wftools/-/releases"
const val WATCH_DEFINITION_URL = "https://gitlab.com/ailife8881/watchmaker-definition"

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
            text = wfToolsPromoText(),
            color = style.secondaryTextColor,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { openExternalUrl(context, WATCH_DEFINITION_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.wf_tools_watch_document),
                color = style.accentColor,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 把文案里的 `WFTools` 一词染成蓝色（老师原话「把 wftools 字体改为蓝色」）。
 *
 * 用固定的系统蓝而不是 `style.accentColor`：强调色是用户可改的，改成橙色以后
 * 「蓝色」这条要求就不成立了。两档各取对应亮度的蓝，深色档上浅色档下都够对比。
 *
 * 三种语言的文案里这个词都是拉丁字母原样出现，所以按子串定位是安全的；
 * 万一以后译文改了写法，`indexOf` 返回 -1 就整句退回次级文字色，不会崩。
 */
@Composable
private fun wfToolsPromoText(): AnnotatedString {
    val raw = stringResource(R.string.wf_tools_promo)
    val brand = "WFTools"
    val start = raw.indexOf(brand, ignoreCase = true)
    val linkBlue = if (isSystemInDarkTheme()) Color(0xFF0A84FF) else Color(0xFF007AFF)
    return remember(raw, start, linkBlue) {
        buildAnnotatedString {
            append(raw)
            if (start >= 0) {
                addStyle(
                    SpanStyle(color = linkBlue, fontWeight = FontWeight.SemiBold),
                    start,
                    start + brand.length,
                )
            }
        }
    }
}

/**
 * 用系统默认浏览器打开 WFTools 仓库。
 *
 * 手表常常没装浏览器，所以 `resolveActivity` 为空时给提示而不是让 startActivity 抛。
 * FLAG_ACTIVITY_NEW_TASK 是必须的：调用方可能是没有任务栈的设置页。
 */
private fun openWfTools(context: android.content.Context) {
    openExternalUrl(context, WF_TOOLS_URL)
}

private fun openExternalUrl(context: android.content.Context, url: String) {
    // GitLab 的仓库与文档页对内置 WebView 来说又宽又重，没有登录态要带，
    // 一律交给系统浏览器（手表上没有浏览器时给提示而不是让 startActivity 抛）。
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val handled = intent.resolveActivity(context.packageManager) != null &&
        runCatching { context.startActivity(intent) }.isSuccess
    if (!handled) {
        Toast.makeText(
            context,
            context.getString(R.string.wf_tools_no_browser_fmt, url),
            Toast.LENGTH_LONG,
        ).show()
    }
}
