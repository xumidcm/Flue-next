package com.flue.launcher.ui.store

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueExpressiveSplitButton
import com.flue.launcher.ui.style.useMaterial3ExpressiveComponents
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.RoundElementRole
import com.flue.launcher.ui.theme.roundAwareCompactShape
import com.flue.launcher.watchface.store.WatchStoreUrlPolicy
import com.flue.launcher.watchface.store.WatchStoreWebPage
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import java.net.URI

/**
 * G5 / C5 — 所有「打开网页版」「在网页版查看」入口共用的一行。
 *
 * 七个入口分散在六个覆层里，各写一份的话图标、字号、边框迟早分叉；而它们的差别只有
 * **标签**与**目标地址**两项，所以只把这两项开放给调用方。
 */
@Composable
internal fun StoreWebLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    FlueOutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = style.roundAwareCompactShape(RoundElementRole.CAPSULE),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Link,
                contentDescription = null,
                tint = style.secondaryTextColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = style.secondaryTextColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * 「打开网页版」+「用系统浏览器打开」两行。
 *
 * 为什么要给系统浏览器留一条路：站点的表盘详情是**弹窗**而不是独立页面，内置 WebView 里
 * 偶尔只出一层灰遮罩、内容出不来（老师实测）。这种时候能一键换成系统浏览器，比困在里面
 * 强得多 —— 代价是外部浏览器没有我们这边的登录 cookie，所以它是第二选项而不是默认。
 */
@Composable
internal fun StoreWebLinks(
    label: String,
    onOpenInApp: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (useMaterial3ExpressiveComponents(LauncherTheme.style.uiStyle)) {
            FlueExpressiveSplitButton(
                primaryOnClick = onOpenInApp,
                secondaryOnClick = onOpenExternal,
                modifier = Modifier.fillMaxWidth(),
                primaryContent = {
                    Text(
                        text = label,
                        color = LauncherTheme.style.secondaryTextColor,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                secondaryContent = {
                    Icon(
                        imageVector = MiuixIcons.Link,
                        contentDescription = stringResource(R.string.watch_store_web_open_external),
                        tint = LauncherTheme.style.secondaryTextColor,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        } else {
            StoreWebLinkRow(label = label, onClick = onOpenInApp)
            StoreWebLinkRow(
                label = stringResource(R.string.watch_store_web_open_external),
                onClick = onOpenExternal,
            )
        }
    }
}

/**
 * 网页入口的 Intent 构造。
 *
 * 返回可空：市场地址被改成畸形值时 [WatchStoreUrlPolicy] 会抛，这里**不把异常抬上去** ——
 * 入口按钮点了没反应比整页崩掉好，而地址本身在 G3 的输入框里已经校验过一次。
 *
 * 全部走 [WatchStoreWebActivity]（C1 宿主），不外跳浏览器：外部浏览器没有登录 cookie，
 * 而这些页面（dashboard / profile）恰恰要登录态。
 */
internal fun storeWebPageIntent(
    context: Context,
    baseUrl: String,
    page: WatchStoreWebPage,
): Intent? = runCatching {
    WatchStoreWebActivity.intent(
        context,
        WatchStoreUrlPolicy.webPage(URI(baseUrl), page).toASCIIString(),
    )
}.getOrNull()

internal fun storeWebWatchFaceIntent(
    context: Context,
    baseUrl: String,
    watchFaceId: String,
): Intent? = runCatching {
    WatchStoreWebActivity.intent(
        context,
        WatchStoreUrlPolicy.webWatchFace(URI(baseUrl), watchFaceId).toASCIIString(),
    )
}.getOrNull()

internal fun storeWebCreatorIntent(
    context: Context,
    baseUrl: String,
    username: String,
): Intent? = runCatching {
    WatchStoreWebActivity.intent(
        context,
        WatchStoreUrlPolicy.webCreator(URI(baseUrl), username).toASCIIString(),
    )
}.getOrNull()
