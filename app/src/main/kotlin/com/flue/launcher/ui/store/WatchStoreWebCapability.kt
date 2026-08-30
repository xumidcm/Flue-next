package com.flue.launcher.ui.store

import android.content.Context
import android.webkit.WebView

/**
 * T-11 —— 「这台设备的内置 WebView 能不能打开网页版商店」的判定。
 *
 * ## 为什么阈值是 80，而不是随手挑的一个数
 *
 * 老师报的「Android 9 上白屏或黑屏」的真因在**上游前端的编译目标**，不在客户端配置
 * （客户端那边 JS、domStorage、硬件加速本来就是开的）：
 *
 * - 上游 `frontend/vite.config.js` **没有** `build.target`（那里出现的 `target` 是 dev
 *   proxy 的），`frontend/package.json` **没有** browserslist，**也没有**
 *   `@vitejs/plugin-legacy` → Vite 5 走默认 target `'modules'`，等于 **es2020 / chrome87**；
 * - 而源码里 `?.`（可选链）与 `??`（空值合并）到处都是（`frontend/src/api.js:18`、
 *   `main.jsx:56`、`Dashboard.jsx` 十六处、`Profile.jsx` 九处…），这两个语法
 *   **Chrome 80 才支持**；
 * - Android 9 自带的 WebView 通常是 Chrome 66~79（国产 ROM 还可能是精简版），正好在
 *   支持线以下。
 *
 * 结果是入口 chunk 在**解析期**就 `SyntaxError` —— 不是跑到某一行才抛，是整个文件一行都
 * 不执行、React 根本没挂载。这类失败**没有任何回调**会告诉我们「渲染失败了」：HTTP 是
 * 200，`onPageFinished` 也照常触发，只有屏幕是空的（于是只剩 `WebHostScreen` 的黑底）。
 * 既然事后测不出来，就只能靠版本号在**进去之前**拦。
 *
 * 所以 80 是「`?.` / `??` 可用」的下限，**不是**「站点跑得舒服」的下限。以后上游只要用了
 * 更新的语法，这个常量要跟着抬 —— 比如 `Array.prototype.at` 要 Chrome 92、
 * `structuredClone` 要 98、`Object.hasOwn` 要 93。CSS 侧不用管：上游只用了 CSS 变量 /
 * flex gap / grid minmax，最多排版走形，不会白屏。
 *
 * ## 为什么独立成一个文件
 *
 * T-42（「打开网页版」两个按钮的显隐）要复用同一个判定 —— 按钮不该把人引向一个必定白屏的
 * 页面。所以这里只回答「能不能用」，不碰 UI、不碰 Activity 状态，签名保持通用（既能给
 * 纯字符串版本号，也能给 Context）。
 */

/** 上游前端能跑起来的最低 Chromium 主版本。抬它之前先读本文件顶部那段理由。 */
const val FLUE_WEB_VIEW_MINIMUM_MAJOR_VERSION: Int = 80

/**
 * 从 WebView 的 `versionName` 里取主版本号，取不到就 null。
 *
 * 只认**纯数字的主段**：定制 ROM 上见过 `version-126` 这种带前缀的写法，不自己挡一下的话
 * `substringBefore('.')` 会把整串交给 `toIntOrNull` —— 那样解析失败和「真的取不到版本」
 * 都变成 null，日志里就分不清是哪种了。
 */
fun flueWebViewMajorVersionOrNull(versionName: String?): Int? {
    val major = versionName?.trim()?.substringBefore('.').orEmpty()
    if (major.isEmpty() || !major.all(Char::isDigit)) return null
    return major.toIntOrNull()
}

/**
 * 这个版本号的 WebView 能不能渲染上游网页版。
 *
 * 版本号缺失（null / 空串 / 认不出格式）一律判**不可用**：宁可让人多走一次系统浏览器，
 * 也不要把他丢进一个必定白屏的页面 —— 白屏是「看起来坏了」，外跳只是「换个地方打开」。
 */
fun flueWebViewVersionUsable(versionName: String?): Boolean {
    val major = flueWebViewMajorVersionOrNull(versionName) ?: return false
    return major >= FLUE_WEB_VIEW_MINIMUM_MAJOR_VERSION
}

/**
 * 当前生效的 WebView 实现的版本号，取不到返回 null。
 *
 * `WebView.getCurrentWebViewPackage()` 是 API 26 加进来的静态方法（minSdk 27，不用版本门），
 * 而且**不需要**先 new 出 WebView 就能问 —— 这正是我们要的：拦在构造之前。
 *
 * 两层兜底都是实测出来的：这个方法在部分定制 ROM 上会返回 null，个别机型还会直接抛
 * （provider 没按标准注册）。老师那台机器装的是 AOSP 的 `com.android.webview` 而不是 GMS
 * 那版，所以第二层按已知 provider 包名再查一遍 —— 能问出版本号总比判成「未知」好，
 * 「未知」会把 WebView 其实够新的机器也一起踢去系统浏览器。
 */
fun flueWebViewVersionNameOrNull(context: Context): String? {
    runCatching { WebView.getCurrentWebViewPackage()?.versionName }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    val packageManager = context.packageManager
    for (providerPackage in WEB_VIEW_PROVIDER_PACKAGES) {
        val version = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(providerPackage, 0).versionName
        }.getOrNull()
        if (!version.isNullOrBlank()) return version
    }
    return null
}

/**
 * 这台设备的内置 WebView 能不能用来打开网页版商店。
 *
 * 取不到包信息（部分定制 ROM）也判为不可用 —— 见 [flueWebViewVersionUsable] 的理由。
 */
fun flueWebViewUsable(context: Context): Boolean =
    flueWebViewVersionUsable(flueWebViewVersionNameOrNull(context))

/**
 * 这条 console 消息是不是「入口脚本解析期就挂了」。
 *
 * 版本号 ≥ 80 也不保证万事大吉（精简版 WebView 缺 API、构建换了更新的语法），所以进去之后
 * 还留一条事后诊断：命中它就说明 React 一行都没跑，留在页面上只会是白屏，该换系统浏览器。
 *
 * 只认**未捕获**的那种。站点里被 `try/catch` 住的 `JSON.parse` 失败也会打 `SyntaxError`，
 * 那种页面照常工作，不该因此把人踢走。
 */
fun flueWebConsoleIndicatesFatalSyntaxError(message: String?): Boolean {
    val text = message?.trim().orEmpty()
    if (!text.contains("SyntaxError", ignoreCase = true)) return false
    return text.startsWith("SyntaxError", ignoreCase = true) ||
        text.contains("Uncaught", ignoreCase = true)
}

/**
 * 已知的 WebView provider 包名，按「可能性从高到低」排。
 *
 * `com.android.webview` 排在 Chrome 前面是刻意的：老师那台机器内置的就是 AOSP 这版，
 * 而 Chrome 只在少数把它设成 provider 的 ROM 上才是真正生效的那一个。
 */
private val WEB_VIEW_PROVIDER_PACKAGES = listOf(
    "com.google.android.webview",
    "com.android.webview",
    "com.android.chrome",
)
