package com.flue.launcher.ui.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.util.Base64
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.R
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.ui.common.InternalFileBrowserHost
import com.flue.launcher.ui.common.InternalFileBrowserRequest
import com.flue.launcher.ui.style.FlueDialog
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.store.DEFAULT_WEB_ZOOM_PERCENT
import com.flue.launcher.watchface.store.WatchStoreUiPreferences
import com.flue.launcher.watchface.store.WatchStoreUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

/**
 * C1 — 网页版兜底的 WebView 宿主。
 *
 * ## 为什么是一个独立 Activity 而不是商店里的一层覆层
 *
 * WebView 有自己的 window、输入法、返回栈与生命周期（`onPause` / `onResume` / `destroy`），
 * 塞进商店那棵已经很深的覆层树里，退出时机与 cookie flush 都会跟覆层动画纠缠。独立 Activity
 * 还带来一个实际好处：下载拦截可以用 `setResult` 把表盘 id 交回商店（见 [EXTRA_INSTALL_ID]）。
 *
 * ## 安全基线（加载的是远端页面，逐条都是刻意的）
 *
 * - `javaScriptEnabled = true`：站点要用，但**绝不 `addJavascriptInterface`** —— 不开 JS 桥
 *   就等于不给远端代码任何进入应用的入口。
 * - `allowFileAccess` / `allowContentAccess` / `allowFileAccessFromFileURLs` 全关：页面拿不到
 *   `file://` 与 `content://`，也就读不到应用私有目录。
 * - 导航按站点白名单（[WatchStoreUrlPolicy.isSameWebSite]）限制在**当前配置的市场站点**；
 *   站外链接交给外部浏览器，不在这个带登录 cookie 的 WebView 里打开。
 * - 进来的 `EXTRA_URL` 来自 Intent（别的 App 也能发），一律过
 *   [WatchStoreUrlPolicy.webUrlOrSiteRoot]：不合法就退回站点根，绝不加载传进来的任意地址。
 *
 * ## T-11 —— 白屏 / 黑屏的三道防线
 *
 * 老师报「Android 9 上打开网页版会白屏或黑屏」。真因在上游前端的编译目标（Vite 5 默认
 * es2020，源码里 `?.` / `??` 满天飞，而 Android 9 自带的 WebView 是 Chrome 66~79），
 * 详见 [flueWebViewVersionUsable] 的 KDoc。**不改上游**，客户端这边分三层挡：
 *
 * 1. **进来之前**（`onCreate`）：主版本 < 80 或问不出版本 → 根本不进这个 Activity 的正文，
 *    直接 `ACTION_VIEW` 外跳 + Toast 说明。白屏是「看起来坏了」，外跳只是「换个地方开」。
 * 2. **进来之后**（`onConsoleMessage` / `onReceivedError` / `onReceivedHttpError`）：把
 *    版本号与 console 一起记进 logcat；主框架失败显示带原因的错误页 + 重试，不再是
 *    「转圈一收剩下黑底」。
 * 3. **渲染之后**（[scheduleBlankScreenProbe]）：HTTP 200 但 `#root` 是空的 → 自动外跳。
 *    这类失败没有任何回调，只能主动量一次 DOM。
 */
class WatchStoreWebActivity : FlueComponentActivity() {

    private var webView: WebView? = null
    private var siteBase: URI = URI(DEFAULT_FALLBACK_BASE)
    private var appliedZoomPercent: Int = DEFAULT_WEB_ZOOM_PERCENT

    /**
     * C3b：站外下载走「和从文件导入完全同一条」的安装路径，那条路在 ViewModel 上。
     *
     * `by viewModels()` 与 `setContent` 里的 `viewModel()` 拿到的是**同一个实例**（同一个
     * Activity 的 ViewModelStore、同一个默认 key），所以装完的列表刷新与选中会直接反映到桌面。
     */
    private val launcherViewModel: LauncherViewModel by viewModels()

    /** C4：`<input type="file">` 的回调。**必须**有一条路把它喂掉，否则页面永久卡住。 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var systemPicker: ActivityResultLauncher<Array<String>>

    private var chooserVisible by mutableStateOf(false)
    private var installedPickerVisible by mutableStateOf(false)
    private var internalBrowserRequest by mutableStateOf<InternalFileBrowserRequest?>(null)
    private var installedFaces by mutableStateOf<List<WatchFaceDescriptor>>(emptyList())
    private var busyMessage by mutableStateOf<String?>(null)
    private var pageLoading by mutableStateOf(true)

    /**
     * T-11：主框架加载失败的原因，非 null 时盖一层错误页。
     *
     * 以前失败时只有 `pageLoading = false` —— 转圈一收，屏幕上就只剩 [WebHostScreen] 的
     * 黑底，老师报的「黑屏」就是它。原因文字来自 WebView（`net::ERR_…` / `HTTP 503`），
     * 是技术串但准确；配上「重试」与「用系统浏览器打开」两条出路，总比困在黑屏里好。
     */
    private var pageError by mutableStateOf<String?>(null)

    /** 当前生效的 WebView 版本号，记进每一条错误日志 —— 诊断白屏时第一个要问的就是它。 */
    private var webViewVersionName: String? = null

    /** 自动外跳只做一次：同一个 SyntaxError 在 console 里会连着报好几条。 */
    private var externalFallbackDone = false

    /** 首屏空白只探一次，见 [scheduleBlankScreenProbe]。 */
    private var blankScreenProbePending = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        val preferences = WatchStoreUiPreferences(this)
        siteBase = intent.getStringExtra(EXTRA_TRUSTED_SITE_BASE)
            ?.let { raw -> runCatching { URI(raw) }.getOrNull() }
            ?: runCatching { URI(preferences.readBaseUrl()) }.getOrDefault(URI(DEFAULT_FALLBACK_BASE))
        val target = WatchStoreUrlPolicy.webUrlOrSiteRoot(siteBase, intent.getStringExtra(EXTRA_URL))
        val loginMode = intent.getBooleanExtra(EXTRA_LOGIN_MODE, false)

        // SAF 的 launcher 必须在 STARTED 之前注册。
        systemPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            deliverFileChooserResult(uri)
        }

        // T-11 —— 进内置 WebView 之前先过两道门：
        //
        // 1. **版本门**（新增）：Android 9 那批机器自带的 WebView 是 Chrome 66~79，上游前端
        //    的入口 chunk 在解析期就 SyntaxError，进去必定白屏，而且事后没有任何回调能告诉
        //    我们「渲染失败了」（见 [flueWebViewVersionUsable] 的 KDoc）。所以必须提前拦。
        // 2. 部分 Wear 机型根本不带 Android System WebView：构造就会抛。
        //
        // 两道门的处置一样 —— 退系统浏览器，并且**说清楚为什么跳走了**：不解释的话老师会
        // 以为「点网页版会莫名跳浏览器」是新 bug。这个 Activity 是版本门的**唯一**落点，
        // 因为 EXTRA_URL 也可能来自别的 App 发来的 Intent，只在按钮那侧拦是拦不住的。
        webViewVersionName = flueWebViewVersionNameOrNull(this)
        val versionUsable = flueWebViewVersionUsable(webViewVersionName)
        val created = if (versionUsable) runCatching { WebView(this) }.getOrNull() else null
        if (created == null) {
            Log.w(
                LOG_TAG,
                "内置 WebView 不可用，改用系统浏览器（version=$webViewVersionName, usable=$versionUsable）",
            )
            val opened = openExternally(target.toASCIIString())
            val version = webViewVersionName
            Toast.makeText(
                this,
                when {
                    // 连系统浏览器都没有：这台设备真的没有任何能开网页的东西。
                    !opened -> getString(R.string.watch_store_web_unavailable)
                    // 问出了版本号但太旧 → 明确说是版本问题。
                    !versionUsable && version != null ->
                        getString(R.string.watch_store_web_view_outdated_fmt, version)
                    // 取不到 provider 包信息，或 WebView 构造直接抛 → 按「没有可用网页组件」说。
                    else -> getString(R.string.watch_store_web_unavailable)
                },
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        webView = created
        appliedZoomPercent = preferences.readWebZoomPercent()
        configure(created, appliedZoomPercent)
        created.loadUrl(target.toASCIIString())

        setContent {
            val vm: LauncherViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShapeOverride by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(
                themeMode = themeMode,
                uiStyle = uiStyle,
                screenShape = screenShapeOverride,
            ) {
              ProvideGlobalUiScale(globalUiScalePercent) {
                WebHostScreen(
                    webView = created,
                    loading = pageLoading,
                    busyMessage = busyMessage,
                    error = pageError,
                    onRetry = {
                        pageError = null
                        pageLoading = true
                        created.reload()
                    },
                    onOpenExternal = {
                        // 当前地址优先：错误可能发生在站内某个子页面上，退回站点根会丢上下文。
                        if (openExternally(created.url ?: target.toASCIIString())) finish()
                    },
                    onBack = { goBackOrFinish() },
                    onImportLogin = if (loginMode) ::captureWebLogin else null,
                )
                if (chooserVisible) {
                    WebUploadChooserDialog(
                        onPickInstalled = {
                            chooserVisible = false
                            installedPickerVisible = true
                            loadInstalledFaces()
                        },
                        onPickInternal = {
                            chooserVisible = false
                            openInternalBrowser()
                        },
                        onPickSystem = {
                            chooserVisible = false
                            runCatching { systemPicker.launch(UPLOAD_MIME_TYPES) }
                                .onFailure { deliverFileChooserResult(null) }
                        },
                        onDismiss = {
                            chooserVisible = false
                            // 取消也要喂回 null，不然页面上那个 file input 之后再点没反应。
                            deliverFileChooserResult(null)
                        },
                    )
                }
                if (installedPickerVisible) {
                    InstalledWatchFacePickerDialog(
                        faces = installedFaces,
                        onSelect = { descriptor ->
                            installedPickerVisible = false
                            exportAndDeliver(descriptor)
                        },
                        onDismiss = {
                            installedPickerVisible = false
                            deliverFileChooserResult(null)
                        },
                    )
                }
                InternalFileBrowserHost(
                    request = internalBrowserRequest,
                    onDismiss = {
                        val hadRequest = internalBrowserRequest != null
                        internalBrowserRequest = null
                        // 关掉浏览器而没选文件也算取消。
                        if (hadRequest && fileChooserCallback != null) deliverFileChooserResult(null)
                    },
                )
              }
            }
        }
    }

    private fun captureWebLogin() {
        val view = webView ?: return
        view.evaluateJavascript(
            "localStorage.getItem('token')",
        ) { raw ->
            runCatching {
                val currentUrl = view.url?.let(::URI)
                    ?: error("网页登录页面地址缺失")
                require(WatchStoreUrlPolicy.isSameWebSite(siteBase, currentUrl)) {
                    "网页登录页面不属于当前市场"
                }
                val token = (JSONTokener(raw).nextValue() as? String).orEmpty().trim()
                require(token.split('.').size == 3) { "网页登录令牌无效" }
                val encodedPayload = token.split('.')[1]
                val decoded = String(
                    Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8,
                )
                val tokenPayload = JSONObject(decoded)
                val exp = tokenPayload.optLong("exp", 0L)
                require(exp > System.currentTimeMillis() / 1_000L) { "网页登录令牌已过期" }
                val username = tokenPayload.optString("username").trim()
                require(username.isNotEmpty()) { "网页登录用户信息缺失" }
                // 成功也要说一声：一 finish 就回到商店，没有提示的话分不清
                // 「导入了」还是「按钮没反应」（失败分支有 Toast，成功一直哑着）。
                Toast.makeText(
                    this,
                    getString(R.string.watch_store_login_web_imported_fmt, username),
                    Toast.LENGTH_SHORT,
                ).show()
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_LOGIN_TOKEN, token)
                        .putExtra(EXTRA_LOGIN_USERNAME, username),
                )
                finish()
            }.onFailure {
                Toast.makeText(this, R.string.watch_store_login_web_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView, zoomPercent: Int) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        // ── T-11：**不要**在这里加 `setLayerType(View.LAYER_TYPE_SOFTWARE)` ────────────
        //
        // 网上治「WebView 黑屏」的偏方基本都是这一句，但它对我们这个病症是反的：软件层会把
        // 整个 WebView 从合成器里摘出来、改成 CPU 逐帧画进一张位图 —— 在 454×454 的手表上
        // 又慢，又**更容易**出现整块不刷新的黑块。而我们的黑屏根本不是合成器的问题，是入口
        // 脚本解析失败导致 React 没渲染（见 [flueWebViewVersionUsable] 的 KDoc），
        // 页面里本来就什么都没有 —— 换渲染路径不会让空页面变出内容。
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 远端页面读不到本地文件：这三项是「不给远端拿本地内容」的硬边界。
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // ── 视口：**不强制宽视口** ─────────────────────────────────────────
            //
            // 一开始按「桌面页面塞进小屏」的思路写了 `useWideViewPort = true` +
            // `loadWithOverviewMode = true` + `setInitialScale`。老师实测：站点里点开一张表盘
            // 是**弹窗**而不是独立页面，而弹窗只出一层灰遮罩、内容看不见。
            //
            // 原因就在这三行：宽视口让**布局视口**比可视视口宽一大截（没有 viewport meta 时
            // 默认 980px），`position: fixed` 的弹窗按布局视口居中 → 落到可视区之外；而遮罩是
            // `inset: 0` 铺满的，所以只有它看得见。站点本身是响应式的，按设备宽度排版就对了，
            // 弹窗自然落回屏幕中间。
            //
            // 可读性交给 `textZoom`（H6 的 25–250%）——它只缩放文字，不动布局视口，
            // 因此不会把 fixed 定位重新算飞。
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = zoomPercent
            // 站点用 `window.open` 打开详情时，不支持多窗口会让它**就地导航**而不是被丢掉；
            // 反之（支持多窗口但没实现 onCreateWindow）才是「点了没反应」。
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = handleNavigation(request.url?.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageLoading = true
                // 新一轮加载：上一轮的错误页要收掉，否则「重试」成功了错误页还盖在上面。
                pageError = null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // The store homepage is a React SPA: clicking a card only mutates local state and
                // opens `.wf-modal-backdrop`, so shouldOverrideUrlLoading never sees it. Intercept
                // that one card gesture before React and use the site's real detail route instead.
                view.evaluateJavascript(WatchStoreWebBridge.installScript(siteBase), null)
                // 转圈要收（不收就永远转），但**不能**顺手把 pageError 清掉 —— 失败时
                // onReceivedError 已经先写好了原因，清掉就又变回黑屏。
                pageLoading = false
                scheduleBlankScreenProbe(view)
            }

            /**
             * T-11：主框架加载失败 → 错误页，不是黑屏。
             *
             * 只认 `isForMainFrame`：站点上子资源失败（埋点、缺图标）很常见，为它整页报错
             * 会把能用的页面也判死。
             */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val description = error.description?.toString()?.takeIf(String::isNotBlank)
                    ?: "error ${error.errorCode}"
                Log.w(
                    LOG_TAG,
                    "主框架加载失败：$description（url=${request.url}, webview=$webViewVersionName）",
                )
                pageLoading = false
                pageError = description
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                val status = "HTTP ${errorResponse.statusCode}"
                Log.w(
                    LOG_TAG,
                    "主框架 HTTP 错误：$status（url=${request.url}, webview=$webViewVersionName）",
                )
                pageLoading = false
                pageError = status
            }
        }
        view.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                // 带 Content-Disposition 的响应不会走 shouldOverrideUrlLoading，两条都要接。
                if (routeWatchFaceDownload(url)) return@DownloadListener
                if (downloadArchiveAndInstall(url, userAgent, contentDisposition, mimeType)) {
                    return@DownloadListener
                }
                Toast.makeText(
                    this,
                    R.string.watch_store_web_download_unsupported,
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // 上一次的回调还挂着（页面连点两次）→ 先把旧的喂掉，不然它永远悬着。
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                chooserVisible = true
                return true
            }

            /**
             * T-11 的诊断口径：白屏时 logcat 里应当能看到
             * `Uncaught SyntaxError: Unexpected token '.'`（或 `'?'`），sourceId 指向
             * `/assets/index-*.js`，同时打印出的 WebView 版本 < 80。
             *
             * 顺带兜住「版本号骗了我们」的情况：主版本 ≥ 80 但入口脚本还是解析失败（精简版
             * WebView、上游换了更新语法），这时候 React 一行都不会跑，留在页面上就是白屏 ——
             * 直接换系统浏览器。
             */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message()
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(
                        LOG_TAG,
                        "web console: $message" +
                            "（${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}," +
                            " webview=$webViewVersionName）",
                    )
                }
                if (flueWebConsoleIndicatesFatalSyntaxError(message)) {
                    fallbackToExternalBrowser("入口脚本解析失败：$message")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    /**
     * 已经进到内置 WebView 才发现渲染不了 → 自动换系统浏览器 + 说明原因。
     *
     * 两条触发路径都属于「版本门放过了但实际跑不起来」的残余情况：入口脚本解析期
     * SyntaxError（[flueWebConsoleIndicatesFatalSyntaxError]）、首屏一直空白
     * （[scheduleBlankScreenProbe]）。
     *
     * 外跳失败时**不**静默返回：退回错误页，至少让人看到发生了什么并能重试。
     */
    private fun fallbackToExternalBrowser(reason: String) {
        if (externalFallbackDone || isFinishing) return
        externalFallbackDone = true
        val url = webView?.url ?: return
        Log.w(LOG_TAG, "改用系统浏览器打开（$reason, webview=$webViewVersionName）")
        if (openExternally(url)) {
            Toast.makeText(this, R.string.watch_store_web_view_render_failed, Toast.LENGTH_LONG).show()
            finish()
        } else {
            pageLoading = false
            pageError = getString(R.string.watch_store_web_unavailable)
        }
    }

    /**
     * 首屏空白探测。
     *
     * 为什么要主动量：这类失败**没有任何回调** —— HTTP 200、`onPageFinished` 正常触发，
     * 只有屏幕是空的。既然事件层测不出来，就在加载完之后隔一会儿去问一次 DOM。
     *
     * 只在**首次**加载后量一次：站内跳转时页面偶尔慢一拍，反复探测会把正在正常浏览的人
     * 莫名踢去系统浏览器 —— 那比白屏更让人困惑。
     */
    private fun scheduleBlankScreenProbe(view: WebView) {
        if (!blankScreenProbePending) return
        blankScreenProbePending = false
        lifecycleScope.launch {
            delay(BLANK_SCREEN_PROBE_DELAY_MS)
            if (externalFallbackDone || isFinishing || pageError != null) return@launch
            view.evaluateJavascript(BLANK_SCREEN_PROBE_SCRIPT) { raw ->
                // evaluateJavascript 回的是 JSON 字面量，字符串结果带引号，得先剥掉。
                if (raw?.trim()?.trim('"') == BLANK_SCREEN_VERDICT_BLANK) {
                    fallbackToExternalBrowser("首屏空白")
                }
            }
        }
    }

    /** 返回 true 表示这条导航我们自己处理了，不交给 WebView。 */
    private fun handleNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (routeWatchFaceDownload(url)) return true
        val candidate = runCatching { URI(url) } .getOrNull() ?: return true
        if (WatchStoreUrlPolicy.isSameWebSite(siteBase, candidate)) return false
        // 站外但一看就是个表盘包（对象存储直链）→ 自己下，别甩给系统浏览器：
        // 浏览器下完只是落进「下载」目录，还要老师手动去导入一遍。
        if (downloadArchiveAndInstall(url, webView?.settings?.userAgentString, null, null)) return true
        // 其余站外链接不在这个带登录 cookie 的 WebView 里开。
        openExternally(url)
        return true
    }

    /**
     * C3 — 表盘下载链接交回商店安装。
     *
     * 这里刻意**不下载、不安装**：商店那边的下载流程带着安装事务日志、目录对账、
     * 失败回滚与进度 UI，复制一份到 WebView 里迟早两边行为分叉。
     */
    private fun routeWatchFaceDownload(url: String?): Boolean {
        val id = WatchStoreUrlPolicy.downloadWatchFaceIdOrNull(siteBase, url) ?: return false
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INSTALL_ID, id))
        finish()
        return true
    }

    /**
     * C3b — 认不出 id 的下载链接自己落地安装。返回 false 表示「这不像表盘包」，交给调用方提示。
     *
     * 站内的 `…/watchfaces/{id}/download` 由 [routeWatchFaceDownload] 交回商店。但站点也可能把
     * 下载指到**对象存储直链**：别的域名、带签名参数、路径里没有 id —— 那种链接
     * [WatchStoreUrlPolicy.downloadWatchFaceIdOrNull] 认不出来，于是以前落到站外规则上被丢给
     * 系统浏览器。老师报的「网页下载会跳转系统浏览器」就是它。
     *
     * 改成自己下：
     * - **带上 WebView 的 cookie** —— 直链多半仍要登录态，不带就是 403；
     * - 落到自己的缓存目录，装完就删；
     * - 安装交给 [LauncherViewModel.importWatchFaceArchive]，也就是「从文件导入 .watch」用的
     *   同一条路：它会嗅探标准包与 JBWatch 包、有大小上限、装完刷新表盘列表并选中。
     *   这里不复制商店那套事务安装 —— 站外包没有市场 id，事务日志无从对账。
     */
    private fun downloadArchiveAndInstall(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Boolean {
        if (!looksLikeWatchFaceArchive(url, contentDisposition, mimeType)) return false
        val fileName = safeFileName(
            runCatching { URLUtil.guessFileName(url, contentDisposition, mimeType) }
                .getOrNull()
                .orEmpty()
                .ifBlank { "watchface.watch" },
        )
        busyMessage = getString(R.string.watch_store_web_download_running)
        lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                runCatching { fetchIntoCache(url, userAgent, fileName) }
            }
            val archive = downloaded.getOrNull()
            val imported = if (archive == null) {
                null
            } else {
                try {
                    launcherViewModel.importWatchFaceArchive(contentUriFor(archive))
                } finally {
                    archive.delete()
                }
            }
            busyMessage = null
            val descriptor = imported?.getOrNull()
            if (descriptor != null) {
                Toast.makeText(
                    this@WatchStoreWebActivity,
                    getString(R.string.watch_store_web_download_installed_fmt, descriptor.displayName),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                val reason = (imported?.exceptionOrNull() ?: downloaded.exceptionOrNull())
                    ?.message
                    ?.takeIf(String::isNotBlank)
                Toast.makeText(
                    this@WatchStoreWebActivity,
                    if (reason == null) {
                        getString(R.string.watch_store_web_download_failed)
                    } else {
                        getString(R.string.watch_store_web_download_failed_fmt, reason)
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        return true
    }

    /**
     * 这条链接看着像不像表盘包。
     *
     * 不能「所有下载都试着装一遍」：站点上还有头像、截图之类的下载，把它们拉下来再让导入器
     * 报错是白花流量。扩展名与 MIME 两条都看，因为对象存储直链常常只有 `Content-Type`
     * （路径是随机 key），而 `URLUtil.guessFileName` 猜不出来时会补个 `.bin`。
     */
    private fun looksLikeWatchFaceArchive(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): Boolean {
        val guessed = runCatching { URLUtil.guessFileName(url, contentDisposition, mimeType) }
            .getOrNull()
            .orEmpty()
            .lowercase(Locale.ROOT)
        if (ARCHIVE_EXTENSIONS.any(guessed::endsWith)) return true
        val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty().lowercase(Locale.ROOT)
        if (ARCHIVE_EXTENSIONS.any(path::endsWith)) return true
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return normalizedMime != null && normalizedMime in ARCHIVE_MIME_TYPES
    }

    /** 只在 IO 线程调用（[WatchStoreUrlPolicy.requirePublicHttps] 会做 DNS 解析）。 */
    private fun fetchIntoCache(url: String, userAgent: String?, fileName: String): File {
        val target = URI(url)
        // 地址来自远端页面：开 socket 之前必须确认它不指向本机/内网，否则页面能拿我们当跳板。
        WatchStoreUrlPolicy.requirePublicHttps(target, label = "web download URL")
        val connection = (URL(target.toASCIIString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            // 签名直链常常仍要登录态：WebView 的 cookie 必须原样带上，不带就是 403。
            // （HttpURLConnection 跟随跳转时不会补 cookie，签名链接本来也不需要。）
            CookieManager.getInstance().getCookie(url)
                ?.takeIf(String::isNotBlank)
                ?.let { setRequestProperty("Cookie", it) }
            userAgent?.takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
            setRequestProperty("Accept", "*/*")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("下载失败：HTTP $status")
            val file = File(downloadCacheDirectory(), fileName)
            file.delete()
            connection.inputStream.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count.toLong()
                        if (copied > MAXIMUM_DOWNLOAD_BYTES) {
                            file.delete()
                            throw IllegalStateException(
                                "表盘包超过 ${MAXIMUM_DOWNLOAD_BYTES / (1024L * 1024L)}MB 限制",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return file
        } finally {
            connection.disconnect()
        }
    }

    private fun openExternally(url: String): Boolean = runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private fun openInternalBrowser() {
        internalBrowserRequest = InternalFileBrowserRequest(
            title = getString(R.string.watch_store_web_upload_internal_title),
            extensions = setOf("watch", "zip"),
            onSelected = { uris ->
                internalBrowserRequest = null
                val picked = uris.firstOrNull()?.path?.let(::File)
                if (picked == null || !picked.isFile) {
                    deliverFileChooserResult(null)
                } else {
                    // 内置选择器给的是 file://，直接喂给页面读不到（也不该让它读得到）。
                    // 拷进自己的缓存目录再包成 FileProvider 的 content://。
                    copyIntoCacheAndDeliver(picked)
                }
            },
        )
    }

    private fun loadInstalledFaces() {
        lifecycleScope.launch {
            val faces = withContext(Dispatchers.IO) {
                runCatching {
                    WatchmakerWatchFaceStorage.scan(this@WatchStoreWebActivity)
                        .filter(WatchFaceDescriptor::isWatchmaker)
                }.getOrDefault(emptyList())
            }
            installedFaces = faces
        }
    }

    private fun exportAndDeliver(descriptor: WatchFaceDescriptor) {
        busyMessage = getString(R.string.watch_store_web_upload_exporting)
        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(uploadCacheDirectory(), "${safeFileName(descriptor.displayName)}.watch")
                    target.delete()
                    WatchEditorSession.open(this@WatchStoreWebActivity, descriptor).use { session ->
                        session.exportWatch(target, protectedExport = session.isProtected)
                    }
                    target
                }.getOrNull()
            }
            busyMessage = null
            if (exported == null || !exported.isFile) {
                Toast.makeText(this@WatchStoreWebActivity, R.string.watch_store_web_upload_export_failed, Toast.LENGTH_LONG).show()
                deliverFileChooserResult(null)
            } else {
                deliverFileChooserResult(contentUriFor(exported))
            }
        }
    }

    private fun copyIntoCacheAndDeliver(source: File) {
        busyMessage = getString(R.string.watch_store_web_upload_exporting)
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(uploadCacheDirectory(), safeFileName(source.name))
                    source.inputStream().buffered().use { input ->
                        target.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    target
                }.getOrNull()
            }
            busyMessage = null
            deliverFileChooserResult(copied?.let(::contentUriFor))
        }
    }

    private fun uploadCacheDirectory(): File =
        File(cacheDir, UPLOAD_CACHE_DIRECTORY).apply { mkdirs() }

    /** 下载与上传分开放：同一个目录里混着两个方向的临时文件，清理时很容易删错。 */
    private fun downloadCacheDirectory(): File =
        File(cacheDir, DOWNLOAD_CACHE_DIRECTORY).apply { mkdirs() }

    private fun contentUriFor(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun deliverFileChooserResult(uri: Uri?) {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(if (uri == null) null else arrayOf(uri))
    }

    private fun goBackOrFinish() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else finish()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // H6 的缩放改完要立刻对当前页生效。
        val desired = WatchStoreUiPreferences(this).readWebZoomPercent()
        if (desired != appliedZoomPercent) {
            appliedZoomPercent = desired
            webView?.let { view ->
                // 只动 textZoom（见 configure 里那段注释：initialScale 会把 fixed 弹窗算飞）。
                view.settings.textZoom = desired
                view.reload()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        // 登录态跨启动保留靠这一句：WebView 的 cookie 是延迟落盘的。
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // 页面还挂着 file input 就退出 → 回调必须喂掉，不然 WebView 内部会留着悬空引用。
        deliverFileChooserResult(null)
        webView?.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL: String = "com.flue.launcher.extra.WEB_URL"

        /** C3：命中表盘下载链接时交回调用方的表盘 id，由商店走既有安装流程。 */
        const val EXTRA_INSTALL_ID: String = "com.flue.launcher.extra.WEB_INSTALL_ID"
        const val EXTRA_LOGIN_MODE: String = "com.flue.launcher.extra.WEB_LOGIN_MODE"
        const val EXTRA_LOGIN_TOKEN: String = "com.flue.launcher.extra.WEB_LOGIN_TOKEN"
        const val EXTRA_LOGIN_USERNAME: String = "com.flue.launcher.extra.WEB_LOGIN_USERNAME"
        const val EXTRA_TRUSTED_SITE_BASE: String = "com.flue.launcher.extra.WEB_TRUSTED_SITE_BASE"

        private const val LOG_TAG = "FlueStoreWeb"

        /**
         * T-11：首屏是否空白。
         *
         * 取 `#root`（Vite + React 的挂载点）而不是 `body`：body 里永远有 `<script>`，
         * 拿它判空会永远判成「有内容」。`#root` 不存在时才退回 body（站点改结构的兜底）。
         *
         * 同时看子元素数与可见文字：React 挂载失败时 `#root` 是彻底空的，而只要渲染出了
         * 任何东西（哪怕是它自己的错误提示）就不该判成空白。
         */
        private const val BLANK_SCREEN_PROBE_SCRIPT =
            "(function(){var r=document.getElementById('root')||document.body;" +
                "if(!r)return 'blank';" +
                "var t=(r.innerText||'').trim();" +
                "return (r.childElementCount===0&&t.length===0)?'blank':'ok';})()"
        private const val BLANK_SCREEN_VERDICT_BLANK = "blank"

        /**
         * 探测前等多久。React 挂载通常在几十毫秒内完成，2.5s 是给慢机型 + 慢网络留的宽余量
         * —— 宁可晚一点判，也不要把只是加载慢的页面判成白屏。
         */
        private const val BLANK_SCREEN_PROBE_DELAY_MS = 2_500L

        private const val UPLOAD_CACHE_DIRECTORY = "web-uploads"
        private const val DOWNLOAD_CACHE_DIRECTORY = "web-downloads"
        private const val DEFAULT_FALLBACK_BASE = "https://jbwatchface.ai-life.xyz/api"
        private val UPLOAD_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")

        /** 与 `LauncherViewModel` 的通用表盘包上限一致，跨手表网络 30s 超时也够拉一个包。 */
        private const val MAXIMUM_DOWNLOAD_BYTES = 500L * 1024L * 1024L
        private const val DOWNLOAD_TIMEOUT_MS = 30_000

        private val ARCHIVE_EXTENSIONS = listOf(".watch", ".jb_watch", ".jbwatch", ".zip")
        private val ARCHIVE_MIME_TYPES = setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
            "binary/octet-stream",
        )

        fun intent(context: Context, url: String): Intent =
            Intent(context, WatchStoreWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_LOGIN_MODE, url.trimEnd('/').endsWith("/login"))
    }
}

@Composable
private fun WebHostScreen(
    webView: WebView,
    loading: Boolean,
    busyMessage: String?,
    error: String?,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onBack: () -> Unit,
    onImportLogin: (() -> Unit)?,
) {
    BackHandler(onBack = onBack)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                FlueLoadingIndicator()
            }
        }
        // T-11：失败时盖错误页。这一层在转圈之后判，因为 onReceivedError 会先把
        // pageLoading 收掉 —— 底下那块 `.background(Color.Black)` 就是老师看到的「黑屏」。
        if (error != null) {
            WebErrorPane(
                reason = error,
                onRetry = onRetry,
                onOpenExternal = onOpenExternal,
            )
        }
        if (busyMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FlueLoadingIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(busyMessage, color = Color.White, fontSize = 13.sp)
                }
            }
        }
        if (onImportLogin != null && error == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp),
            ) {
                WebErrorAction(stringResource(R.string.watch_store_login_web_import), onImportLogin)
            }
        }
    }
}

/**
 * T-11 的错误页：原因 + 两条出路。
 *
 * 文字与按钮全部写死成白色（而不是走 [LauncherTheme] 的 titleColor）：这一层盖在 WebView
 * 的黑底上，浅色主题下的深色前景在这里等于看不见。
 *
 * 标题只有原因串（`net::ERR_INTERNET_DISCONNECTED` / `HTTP 503` 这类）—— 技术味重，但
 * 准确，而且两个按钮已经把「接下来能做什么」说清楚了。要加一句本地化的「网页版加载失败」
 * 标题需要新字符串，那个由主代理统一加。
 */
@Composable
private fun WebErrorPane(
    reason: String,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = reason,
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
            Spacer(Modifier.height(14.dp))
            WebErrorAction(stringResource(R.string.watch_store_retry), onRetry)
            Spacer(Modifier.height(6.dp))
            WebErrorAction(stringResource(R.string.watch_store_web_open_external), onOpenExternal)
        }
    }
}

@Composable
private fun WebErrorAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** C4 的三选项。取消由 [FlueDialog] 的点空白与返回键统一走 `onDismiss`。 */
@Composable
private fun WebUploadChooserDialog(
    onPickInstalled: () -> Unit,
    onPickInternal: () -> Unit,
    onPickSystem: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlueDialog(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.watch_store_web_upload_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        WebChooserRow(stringResource(R.string.watch_store_web_upload_installed), onPickInstalled)
        WebChooserRow(stringResource(R.string.watch_store_web_upload_internal), onPickInternal)
        WebChooserRow(stringResource(R.string.watch_store_web_upload_system), onPickSystem)
    }
}

@Composable
private fun InstalledWatchFacePickerDialog(
    faces: List<WatchFaceDescriptor>,
    onSelect: (WatchFaceDescriptor) -> Unit,
    onDismiss: () -> Unit,
) {
    FlueDialog(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.watch_store_web_upload_installed),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        if (faces.isEmpty()) {
            Text(
                text = stringResource(R.string.watch_store_web_upload_no_installed),
                color = LauncherTheme.style.secondaryTextColor,
                fontSize = 13.sp,
            )
            return@FlueDialog
        }
        // **不能再套一层 verticalScroll**：FlueDialog 的卡片本身就是可滚动的 Column，
        // 嵌两层滚动容器会让内层拿到无限高约束 —— 直接抛
        // 「Vertically scrollable component was measured with an infinity maximum height」。
        // 老师点「从已安装表盘选择」时的崩溃就是这个。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            faces.forEach { descriptor ->
                WebChooserRow(descriptor.displayName) { onSelect(descriptor) }
            }
        }
    }
}

@Composable
private fun WebChooserRow(label: String, onClick: () -> Unit) {
    val style = LauncherTheme.style
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        androidx.compose.material3.TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                color = style.titleColor,
                fontSize = 14.sp,
                maxLines = 2,
            )
        }
    }
}

/** 供调用方使用的 `WatchStoreWebActivity` 启动器。 */
internal fun Context.openWatchStoreWeb(url: URI) {
    startActivity(WatchStoreWebActivity.intent(this, url.toASCIIString()))
}

/**
 * 缓存文件名清洗。
 *
 * 表盘显示名与用户挑的文件名都会进这里，它们可能带路径分隔符（`../` / `\`）。这是**路径逃逸
 * 的防线**，不只是好看：拼进缓存目录之前必须确认它不能跳出去。
 */
private fun safeFileName(raw: String): String = raw
    .trim()
    .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
    .replace("..", "_")
    .take(80)
    .ifBlank { "flue-upload" }
