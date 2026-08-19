package com.flue.launcher.ui.store

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.File

/**
 * C6 — 清理网页版数据（含登录状态）。
 *
 * 与设置里原有的「清理表盘市场封面缓存」是**两件不同的事**，决策 7 明确分成两项：
 * 封面缓存只影响图片，这一项影响的是**网页版的登录态**。所以文案要说明会登出网页版，
 * 而应用内的表盘市场账号（另一套凭据，存在 `WatchStoreAuthStore` 里）不受影响。
 *
 * 刻意**不删** `app_webview` 那个 profile 目录：进程里可能还挂着 WebView 实例，
 * 直接删它的 profile 会让 Chromium 在下次访问时崩。cookie + WebStorage + 缓存目录
 * 已经覆盖「登录态与本地数据」，这是既有效又不会拖崩渲染器的那一档。
 */
internal fun clearFlueWebViewData(context: Context) {
    runCatching {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }
    runCatching { WebStorage.getInstance().deleteAllData() }
    // WebView 的 HTTP 缓存落在 cacheDir 下，名字随版本变过（WebView / org.chromium…），
    // 所以按前缀扫而不是写死一个。
    runCatching {
        context.cacheDir.listFiles()
            ?.filter { it.isDirectory && WEBVIEW_CACHE_PREFIXES.any(it.name::startsWith) }
            ?.forEach(File::deleteRecursively)
    }
}

private val WEBVIEW_CACHE_PREFIXES = listOf("WebView", "webview", "org.chromium")
