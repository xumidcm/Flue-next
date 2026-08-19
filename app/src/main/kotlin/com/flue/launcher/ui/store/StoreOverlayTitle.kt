package com.flue.launcher.ui.store

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * 市场页顶栏的「大标题 + 小副标题」。**首页与所有二级页共用这一份**。
 *
 * ## 老师报的问题
 *
 * 「我的」「上传」「个人主页」这些二级页，大字与小字之间的空隙明显比市场首页大。
 *
 * ## 根因
 *
 * 默认行盒是字号的 1.2–1.4 倍，两行之间因此空出一条。首页早就显式给了 `lineHeight`
 * 并关掉字体自带的上下内边距（`includeFontPadding = false`），二级页那四处头部各写
 * 了一遍 `Text`、都没带这两项，于是只有首页是紧的。
 *
 * 抽成一份之后「间距」这件事只有一个出处，往后加页面不会再漏。
 *
 * **不要**改用负的 `spacedBy` 去硬拉：那会连带把字形上下裁掉。
 */
@Composable
internal fun ColumnScope.StoreOverlayTitle(
    title: String,
    subtitle: String?,
) {
    val style = LauncherTheme.style
    Text(
        title,
        color = style.titleColor,
        fontSize = 17.sp,
        lineHeight = 18.sp,
        style = STORE_HEADER_TIGHT_LINE_STYLE,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
        Text(
            subtitle,
            color = style.secondaryTextColor,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            style = STORE_HEADER_TIGHT_LINE_STYLE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 独立成一个 `Column` 的版本，供不在 `ColumnScope` 里的调用点用
 * （顶栏通常是 `Row { … Column(weight(1f)) { 标题 } … }`）。
 */
@Composable
internal fun StoreOverlayTitleBlock(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) { StoreOverlayTitle(title, subtitle) }
}

/** 标题两行共用：关掉字体自带的上下内边距，只留显式 lineHeight。 */
internal val STORE_HEADER_TIGHT_LINE_STYLE = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
