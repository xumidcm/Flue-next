package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueDialog
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.ui.style.FlueLoadingIndicatorUsage
import com.flue.launcher.ui.style.FlueRefreshIndicator
import com.flue.launcher.ui.style.FlueCard
import com.flue.launcher.ui.style.FlueSurface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.extra.WindowListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.Colors as MiuixColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme
import com.flue.launcher.ui.settings.miuix.MiuixIcons as FlueMiuixIcons
import com.flue.launcher.ui.store.StoreOverlayTitle
import com.flue.launcher.ui.store.StoreOverlayTitleBlock
import com.flue.launcher.ui.store.StoreRoundList
import com.flue.launcher.ui.store.StoreAuthorAvatars
import com.flue.launcher.ui.store.storeChordBlock
import com.flue.launcher.ui.store.StoreWebLinkRow
import com.flue.launcher.ui.store.StoreWebLinks
import com.flue.launcher.ui.store.WatchStoreCreatorsOverlay
import com.flue.launcher.ui.store.WatchStoreProfileOverlay
import com.flue.launcher.ui.store.WatchStoreBaseUrlDialog
import com.flue.launcher.ui.store.WatchStoreWebActivity
import com.flue.launcher.ui.store.storeWebPageIntent
import com.flue.launcher.ui.store.storeWebWatchFaceIntent
import com.flue.launcher.ui.store.storeWebCreatorIntent
import com.flue.launcher.ui.store.WatchStoreCollectionsOverlay
import com.flue.launcher.ui.store.WatchStoreFavoritesOverlay
import com.flue.launcher.ui.store.StoreAvatar
import com.flue.launcher.ui.store.threadWatchStoreComments
import com.flue.launcher.ui.store.watchStoreSocialDetailVisibility
import com.flue.launcher.ui.store.favoriteCountAfterToggle
import com.flue.launcher.ui.store.isOwnWatchStoreComment
import com.flue.launcher.ui.store.WatchStoreAccountMenuAction
import com.flue.launcher.ui.store.commentsAfterWatchStoreCommentDeletion
import com.flue.launcher.ui.store.mergeEditedWatchStoreComment
import com.flue.launcher.ui.store.watchStoreAccountMenuActions
import com.flue.launcher.ui.store.watchStoreInstallStateShowsDownloadCount
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import com.flue.launcher.ui.common.FluePullToRefreshState
import com.flue.launcher.ui.common.fluePressScale
import com.flue.launcher.ui.style.flueOverscroll
import com.flue.launcher.ui.style.flueScrollEndHaptic
import com.flue.launcher.ui.common.fluePullToRefresh
import com.flue.launcher.ui.common.chordScaleGridItem
import com.flue.launcher.ui.common.chordScaleItem
import com.flue.launcher.ui.common.chordScaleForPosition
import com.flue.launcher.ui.common.flueExpandEnter
import com.flue.launcher.ui.common.flueShrinkExit
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.roundScrollablePadding
import com.flue.launcher.ui.common.LocalChordScreenCenterY
import com.flue.launcher.ui.common.LocalChordViewportWidth
import com.flue.launcher.ui.common.rememberFluePressScale
import com.flue.launcher.ui.common.rememberFluePullToRefreshState
import com.flue.launcher.ui.drawer.vibrateHaptic
import com.flue.launcher.ui.input.ROTARY_SCROLL_MULTIPLIER
import com.flue.launcher.ui.input.RotaryHapticGate
import com.flue.launcher.ui.input.flueRotaryScrollable
import com.flue.launcher.ui.input.requestFocusAfterFirstFrame
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import com.flue.launcher.ui.theme.LauncherStyle
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.LocalGlobalUiScale
import com.flue.launcher.ui.theme.ProvideDialogUiScale
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.standard.WatchInstallMetadata
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.MyWatchFaceEntry
import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreAuthStore
import com.flue.launcher.watchface.store.WatchStoreCategory
import com.flue.launcher.watchface.store.WatchStoreCompatibilityInspector
import com.flue.launcher.watchface.store.WatchStoreCompatibilityReport
import com.flue.launcher.watchface.store.WatchStoreCredentialStore
import com.flue.launcher.watchface.store.addWatchStoreTag
import com.flue.launcher.watchface.store.beginEditWatchStoreTag
import com.flue.launcher.watchface.store.normalizeWatchStoreTags
import com.flue.launcher.watchface.store.removeWatchStoreTag
import com.flue.launcher.watchface.store.WatchStoreComment
import com.flue.launcher.watchface.store.WatchStoreCollection
import com.flue.launcher.watchface.store.WatchStoreException
import com.flue.launcher.watchface.store.WatchStoreFeatureFlagCache
import com.flue.launcher.watchface.store.WatchStoreFeatureFlags
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import com.flue.launcher.watchface.store.WatchStoreInstallState
import com.flue.launcher.watchface.store.WatchStoreInstallTransactionException
import com.flue.launcher.watchface.store.WatchStorePagination
import com.flue.launcher.watchface.store.WatchStoreQuery
import com.flue.launcher.watchface.store.WatchStoreNotification
import com.flue.launcher.watchface.store.WatchStoreNotificationType
import com.flue.launcher.watchface.store.WatchStoreRepository
import com.flue.launcher.watchface.store.WatchStoreUnreadCounter
import com.flue.launcher.watchface.store.WatchStoreReviewStatus
import com.flue.launcher.watchface.store.WatchStoreScreenType
import com.flue.launcher.watchface.store.WatchStoreSort
import com.flue.launcher.watchface.store.WatchStoreUiPreferences
import com.flue.launcher.watchface.store.WatchStoreViewMode
import com.flue.launcher.watchface.store.WatchStoreWebPage
import com.flue.launcher.watchface.store.WatchStoreWatchFace
import com.flue.launcher.watchface.store.WatchStoreWatchFaceDetail
import com.flue.launcher.watchface.store.WatchStoreRatingSummary
import com.flue.launcher.watchface.store.WatchStoreReportReason
import com.flue.launcher.watchface.store.catalog.WATCHMAKER_COMMUNITY_STORE_ID
import com.flue.launcher.watchface.store.catalog.WatchFaceCatalogDiagnostic
import com.flue.launcher.watchface.store.catalog.WatchFaceCatalogSnapshot
import com.flue.launcher.watchface.store.catalog.WatchFaceInstallSource
import com.flue.launcher.watchface.store.catalog.WatchFaceRecord
import com.flue.launcher.watchface.store.commitMarketInstallCatalog
import com.flue.launcher.watchface.store.installAsRound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Public Watchmaker community store.  The Activity deliberately owns only the
 * coarse screen state; HTTP, archive validation and bitmap decoding stay in
 * [WatchStoreRepository] (which switches to Dispatchers.IO).
 */
class WatchStoreActivity : FlueComponentActivity() {
    private val launcherViewModel: LauncherViewModel by viewModels()
    private val resumeGeneration = MutableStateFlow(0)
    private lateinit var storeUiPreferences: WatchStoreUiPreferences
    private var storeBaseUrl: String? = null

    override fun onResume() {
        super.onResume()
        if (::storeUiPreferences.isInitialized) {
            val latest = storeUiPreferences.readBaseUrl()
            if (storeBaseUrl != null && latest != storeBaseUrl) {
                storeBaseUrl = latest
                recreate()
                return
            }
        }
        resumeGeneration.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        storeUiPreferences = WatchStoreUiPreferences(this)
        storeBaseUrl = storeUiPreferences.readBaseUrl()
        val api = HttpWatchStoreApi(storeBaseUrl!!)
        val repository = WatchStoreRepository(this, api = api)
        val uiPreferences = storeUiPreferences
        setContent {
            val themeMode by launcherViewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by launcherViewModel.uiStyle.collectAsStateWithLifecycle()
            val screenShapeOverride by launcherViewModel.screenShapeOverride.collectAsStateWithLifecycle()
            val uiScale by launcherViewModel.globalUiScalePercent.collectAsStateWithLifecycle()
            val rotaryHapticsEnabled by
                launcherViewModel.rotaryHapticsEnabled.collectAsStateWithLifecycle()
            val availableWatchFaces by launcherViewModel.availableWatchFaces.collectAsStateWithLifecycle()
            val resumeToken by resumeGeneration.collectAsStateWithLifecycle()
            val exportScope = rememberCoroutineScope()
            var pendingExportId by remember { mutableStateOf<String?>(null) }
            val exportDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                val launcherId = pendingExportId
                pendingExportId = null
                val descriptor = launcherId?.let { id -> availableWatchFaces.firstOrNull { it.id == id } }
                if (uri != null && descriptor != null) {
                    exportScope.launch {
                        exportInstalledWatchFace(this@WatchStoreActivity, descriptor, uri)
                            .onSuccess {
                                Toast.makeText(
                                    this@WatchStoreActivity,
                                    getString(R.string.watch_store_export_success),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(
                                    this@WatchStoreActivity,
                                    error.userFacingMessage(this@WatchStoreActivity),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                }
            }
            WatchLauncherTheme(
                themeMode = themeMode,
                uiStyle = uiStyle,
                screenShape = screenShapeOverride,
            ) {
                ProvideGlobalUiScale(uiScale) {
                    WatchStoreScreen(
                        repository = repository,
                        api = api,
                        uiPreferences = uiPreferences,
                        reconcileToken = resumeToken,
                        rotaryHapticsEnabled = rotaryHapticsEnabled,
                        onBack = ::finish,
                        installArchive = { archive, installAsRound ->
                            val installed = launcherViewModel.installWatchStoreArchive(archive, installAsRound)
                            val failure = installed.exceptionOrNull()
                            if (failure != null) {
                                Result.failure(failure)
                            } else {
                                val descriptor = installed.getOrThrow()
                                // 只把 XML 解析结果作为兼容诊断保存。市场身份始终在
                                // repository 里由 API id 建立，绝不由 name 属性推断。
                                val compatibilityReport = withContext(Dispatchers.IO) {
                                    WatchStoreCompatibilityInspector.inspect(
                                        rootDirectory = descriptor.sourceDirPath?.let(::File),
                                        previewFile = descriptor.previewFilePath?.let(::File),
                                    )
                                }
                                InstalledStoreWatchFace(
                                    id = descriptor.id,
                                    name = descriptor.displayName,
                                    installedAtEpochMs = descriptor.sourceDirPath
                                        ?.let(::File)
                                        ?.let { directory ->
                                            WatchInstallMetadata.read(directory)
                                                ?: directory.lastModified().takeIf { it > 0L }
                                        }
                                        ?: System.currentTimeMillis(),
                                    compatibilityReport = compatibilityReport,
                                )
                                    .let { Result.success(it) }
                            }
                        },
                        useInstalled = { launcherId -> launcherViewModel.selectWatchFace(launcherId) },
                        editInstalled = { launcherId ->
                            startActivity(
                                Intent(this, WatchEditorActivity::class.java)
                                    .putExtra(WatchEditorActivity.EXTRA_WATCH_FACE_ID, launcherId),
                            )
                        },
                        exportInstalled = { launcherId ->
                            val descriptor = availableWatchFaces.firstOrNull { it.id == launcherId }
                            if (descriptor == null) {
                                Toast.makeText(
                                    this@WatchStoreActivity,
                                    getString(R.string.watch_store_installed_not_found),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                pendingExportId = launcherId
                                exportDocument.launch("${safeStoreExportName(descriptor.displayName)}.watch")
                            }
                        },
                        deleteInstalled = launcherViewModel::deleteImportedWatchFaces,
                        // 地址已经写进偏好了，这里只负责重建 —— onResume 那条
                        // 「base URL 变了就 recreate」的路径会顺带更新 storeBaseUrl。
                        onMarketUrlSaved = { recreate() },
                    )
                }
            }
        }
    }
}

private data class InstalledStoreWatchFace(
    val id: String,
    val name: String,
    val installedAtEpochMs: Long,
    val compatibilityReport: WatchStoreCompatibilityReport,
)

/**
 * The archive is already installed when catalog persistence fails. Keep the
 * descriptor identity so retrying can repair metadata without downloading or
 * importing the same archive again.
 */
private data class PendingMarketCatalogInstall(
    val revisionSha256: String,
    val descriptorId: String,
    val displayName: String,
    val installedAtEpochMs: Long,
    val compatibilityReport: WatchStoreCompatibilityReport,
)

private data class StoreQueryKey(
    val sort: WatchStoreSort,
    val screenType: WatchStoreScreenType?,
    val categorySlug: String?,
    val search: String,
) {
    /** `#运动` 走上游的 tag 参数，其余走 search。拆分规则见 [watchStoreSearchFields]。 */
    val fields: WatchStoreSearchFields get() = watchStoreSearchFields(search)
}

private const val STORE_BROWSE_CONTROLS_KEY = "watch_store_browse_controls"

@Composable
private fun WatchStoreScreen(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    uiPreferences: WatchStoreUiPreferences,
    reconcileToken: Int,
    rotaryHapticsEnabled: Boolean,
    onBack: () -> Unit,
    installArchive: suspend (java.io.File, installAsRound: Boolean) -> Result<InstalledStoreWatchFace>,
    useInstalled: (launcherId: String) -> Unit,
    editInstalled: (launcherId: String) -> Unit,
    exportInstalled: (launcherId: String) -> Unit,
    deleteInstalled: suspend (launcherIds: Collection<String>) -> Result<Unit>,
    /**
     * 自定义市场地址保存后的收尾。输入框现在就地弹在市场里（不再跳设置的工具页），
     * 保存完由 Activity 走 `recreate()` 那条既有路径重建 repository。
     */
    onMarketUrlSaved: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val isScreenRound = isFlueScreenRound()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val rotaryHapticGate = remember { RotaryHapticGate() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocusAfterFirstFrame()
    }
    var viewMode by remember { mutableStateOf(uiPreferences.readViewMode()) }
    var sort by remember { mutableStateOf(WatchStoreSort.LATEST) }
    var screenType by remember { mutableStateOf<WatchStoreScreenType?>(null) }
    var selectedCategory by remember { mutableStateOf<WatchStoreCategory?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var committedSearch by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<WatchStoreCategory>>(emptyList()) }
    var categoriesError by remember { mutableStateOf<String?>(null) }
    var categoriesReloadToken by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<WatchStoreWatchFace>>(emptyList()) }
    var pagination by remember { mutableStateOf<WatchStorePagination?>(null) }
    var listLoading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var marketLoadSucceeded by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var lastDetailId by remember { mutableStateOf<String?>(null) }
    var selectedDetail by remember { mutableStateOf<WatchStoreWatchFaceDetail?>(null) }
    // ── C5/G5：网页版入口 + C3 的下载回流 ─────────────────────────────────────
    //
    // 宿主用 `StartActivityForResult` 起：网页里点「下载」时，宿主认出表盘 id 就把它
    // setResult 回来（自己**不装**），这里再走商店既有的下载/安装流程 —— 那条路带着
    // 安装事务日志、目录对账与失败回滚，复制一份到 WebView 侧迟早两边行为分叉。
    var pendingWebInstallId by remember { mutableStateOf<String?>(null) }
    val webHost = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.getStringExtra(WatchStoreWebActivity.EXTRA_INSTALL_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { id ->
                // 先把详情页打开：既让用户看到下载进度，也顺便把 screenType 取回来。
                selectedId = id
                pendingWebInstallId = id
            }
    }
    val openWebPage: (WatchStoreWebPage) -> Unit = { page ->
        storeWebPageIntent(context, uiPreferences.readBaseUrl(), page)?.let(webHost::launch)
    }
    val openWebWatchFace: (String) -> Unit = { id ->
        storeWebWatchFaceIntent(context, uiPreferences.readBaseUrl(), id)?.let(webHost::launch)
    }
    // 系统浏览器那一路：同一个地址换成 ACTION_VIEW。站点的表盘详情是弹窗，内置 WebView
    // 偶尔只出一层灰遮罩（老师实测），这条是逃生门 —— 但它没有登录 cookie，所以只作第二选项。
    val openExternalUri: (String) -> Unit = { raw ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(raw))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val openExternalPage: (WatchStoreWebPage) -> Unit = { page ->
        storeWebPageIntent(context, uiPreferences.readBaseUrl(), page)
            ?.getStringExtra(WatchStoreWebActivity.EXTRA_URL)
            ?.let(openExternalUri)
    }
    val openExternalWatchFace: (String) -> Unit = { id ->
        storeWebWatchFaceIntent(context, uiPreferences.readBaseUrl(), id)
            ?.getStringExtra(WatchStoreWebActivity.EXTRA_URL)
            ?.let(openExternalUri)
    }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailReloadToken by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf(WatchFaceCatalogSnapshot()) }
    var catalogInitialized by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogWarning by remember { mutableStateOf<String?>(null) }
    var catalogReloadToken by remember { mutableIntStateOf(0) }
    var repairCatalogOnNextRestore by remember { mutableStateOf(false) }
    var installedFaces by remember { mutableStateOf<List<WatchFaceDescriptor>>(emptyList()) }
    var lastLegacyAssociationSignature by remember { mutableStateOf<String?>(null) }
    val installedFacesById = remember(installedFaces) { installedFaces.associateBy { it.id } }
    val legacyAssociationSignature = remember(installedFaces) {
        installedFaces
            .sortedBy(WatchFaceDescriptor::id)
            .joinToString("\u0000") { "${it.id}\u0001${it.displayName}" }
    }
    val installedMarketRecords = remember(catalog.records) {
        catalog.records.mapNotNull { record ->
            val source = record.source as? WatchFaceInstallSource.Market ?: return@mapNotNull null
            if (source.storeId == WATCHMAKER_COMMUNITY_STORE_ID) source.remoteId to record else null
        }.toMap()
    }
    val downloadStates = remember { mutableStateMapOf<String, MutableStateFlow<WatchStoreInstallState>>() }
    val downloadJobs = remember { mutableMapOf<String, Job>() }
    val pendingCatalogInstalls = remember { mutableStateMapOf<String, PendingMarketCatalogInstall>() }
    var installedActionError by remember { mutableStateOf<String?>(null) }
    var uploadVisible by remember { mutableStateOf(false) }
    var myUploadsVisible by remember { mutableStateOf(false) }
    var notificationsVisible by remember { mutableStateOf(false) }
    /** H2：个人资料页。 */
    var profileVisible by remember { mutableStateOf(false) }
    /** H5：热门创作者与单作者页。 */
    var creatorsVisible by remember { mutableStateOf(false) }
    // B3b：我的收藏 / 我的合集。与通知同理只在登录后露入口。
    var favoritesVisible by remember { mutableStateOf(false) }
    var collectionsVisible by remember { mutableStateOf(false) }
    var creatorUsernameToOpen by remember { mutableStateOf<String?>(null) }
    // 铃铛红点：未读数只在本页可见期间拉，且由 WatchStoreUnreadCounter 做 30 秒节流。
    // 手表电量敏感 —— 常驻轮询是不能接受的，红点晚半分钟亮没有代价。
    val notificationApi = api
    val notificationAuthStore = remember { WatchStoreAuthStore(context) }
    val unreadCounter = remember(notificationApi) { WatchStoreUnreadCounter.of(notificationApi) }
    var notificationSession by remember { mutableStateOf(notificationAuthStore.read()) }
    /**
     * 自己的 `user.id`。
     *
     * 评论区判断「这条是不是我发的」只能靠它：用户名是可变的展示名，而且
     * `PUT /my/comments/:id` 的响应里根本没有 user 字段（见 [isOwnWatchStoreComment] 的注释）。
     * 会话里只存了 token 和用户名（`WatchStoreAuthStore`），所以这里按 token 拉一次
     * `/my/me` —— 一次登录只一次往返。拉失败时留 null：那时候只是不显示自己评论上的
     * 编辑 / 删除按钮，绝不会把别人的评论错标成自己的。
     */
    var notificationAccountId by remember { mutableStateOf<String?>(null) }
    /**
     * 未登录时从菜单或详情页动作直接进登录表单。
     *
     * 复用「我的上传」覆层里那套表单（记住用户名/密码、注册切换、去网页版几条路都在里面），
     * 只是让它直接落在表单上、跳过中间那页「登录后查看我的上传」。
     */
    var loginFormRequested by remember { mutableStateOf(false) }
    val requestLogin: () -> Unit = {
        loginFormRequested = true
        myUploadsVisible = true
    }
    var unreadNotificationCount by remember { mutableIntStateOf(0) }
    var featureFlags by remember { mutableStateOf(WatchStoreFeatureFlags()) }
    val featureFlagCache = remember(api) { WatchStoreFeatureFlagCache.of(api) }

    // 功能开关属于市场会话而不是详情页。即使当前没有选中表盘，上传/登录入口
    // 也必须先读 `/config`，否则 registration_enabled=false 会被默认 true 绕过。
    LaunchedEffect(selectedId, featureFlagCache) {
        featureFlags = featureFlagCache.flags()
    }

    // 从「我的上传」返回时可能刚登录 / 刚登出，重新读一次会话。
    //
    // 新增的登录入口复用的就是这一层覆层（`loginFormRequested` 只改它的落地页），所以
    // `myUploadsVisible` 已经把它盖住了，不需要再往 key 里加一个可见性状态。
    LaunchedEffect(myUploadsVisible, uploadVisible) {
        if (!myUploadsVisible && !uploadVisible) {
            loginFormRequested = false
            notificationSession = withContext(Dispatchers.IO) { notificationAuthStore.read() }
        }
    }

    // 自己的 user.id 跟着 token 走：登出立刻清掉，免得下一个账号沿用上一个人的归属判断。
    LaunchedEffect(notificationSession?.token, notificationApi) {
        val token = notificationSession?.token
        if (token == null) {
            notificationAccountId = null
            return@LaunchedEffect
        }
        notificationAccountId = withContext(Dispatchers.IO) {
            runCatching { notificationApi.getAccount(token).id }.getOrNull()
        }?.takeIf(String::isNotBlank)
    }

    LaunchedEffect(notificationSession?.token) {
        val token = notificationSession?.token
        if (token == null) {
            unreadNotificationCount = 0
            unreadCounter.reset()
            return@LaunchedEffect
        }
        // 这个循环随本页一起被取消 —— 离开市场页就不再拉。
        while (true) {
            unreadNotificationCount = runCatching { unreadCounter.count(token) }.getOrDefault(0)
            kotlinx.coroutines.delay(WatchStoreUnreadCounter.DEFAULT_THROTTLE_MS)
        }
    }
    // 自定义市场地址：拉取失败时就地弹输入框，不再把用户丢到设置的工具页。
    var marketUrlDialogVisible by remember { mutableStateOf(false) }
    // 下拉刷新：用「已完成的 reloadToken」与「本次下拉请求的 token」比较来判定
    // 请求是否结束，比直接观察 listLoading 稳——LaunchedEffect 的重启和
    // listLoading 的写入不在同一帧，观察布尔量会在启动缝隙里提前收指示器。
    var listLoadCompletedToken by remember { mutableIntStateOf(-1) }
    var pullRefreshRequestedToken by remember { mutableStateOf<Int?>(null) }

    // Returning from the editor can detach a market install from the durable
    // catalog. Drop only stale terminal Installed snapshots; active downloads
    // remain untouched. Otherwise an old transient state masks the refreshed
    // catalog and keeps showing "Installed" until the Activity is recreated.
    LaunchedEffect(installedMarketRecords) {
        downloadStates.toMap().forEach { (remoteId, state) ->
            if (state.value is WatchStoreInstallState.Installed) {
                val current = installedMarketRecords[remoteId]
                if (current == null) downloadStates.remove(remoteId)
                else state.value = WatchStoreInstallState.Installed(current)
            }
        }
    }
    var pullRefreshHolding by remember { mutableStateOf(false) }
    val pullRefreshing = pullRefreshHolding ||
        pullRefreshRequestedToken?.let { it > listLoadCompletedToken } == true

    /**
     * Opening a detail must reset the load state *synchronously*: the first
     * overlay frame otherwise renders with the previous request's error or
     * with loading=false and flashes "无法加载表盘" before the effect runs.
     */
    fun openDetail(id: String) {
        selectedDetail = null
        detailError = null
        detailLoading = true
        lastDetailId = id
        selectedId = id
        installedActionError = null
    }

    suspend fun cleanObsoleteMarketRevisions(
        snapshot: WatchFaceCatalogSnapshot,
        scannedFaces: List<WatchFaceDescriptor>,
    ) {
        val obsoleteIds = snapshot.obsoleteMarketLauncherIds(scannedFaces.mapTo(HashSet()) { it.id })
        if (obsoleteIds.isEmpty()) return
        deleteInstalled(obsoleteIds)
            .onSuccess {
                installedFaces = installedFaces.filterNot { it.id in obsoleteIds }
            }
            .onFailure { cleanupError ->
                cleanupError.throwIfCancellation()
                installedActionError = context.getString(
                    R.string.watch_store_error_old_revision_cleanup_fmt,
                    cleanupError.userFacingMessage(context),
                )
            }
    }

    // Catalog reconciliation runs silently once the first snapshot is on
    // screen: blanking the browse list during every resume/reinstall pass was
    // the visible "flicker after install" and also clamped the scroll offset
    // of the emptied LazyColumn back to zero.
    LaunchedEffect(repository, reconcileToken, catalogReloadToken) {
        catalogError = null
        try {
            val restored = repository.restoreCatalogFromInstalledFaces(
                repairCorruptCatalog = repairCatalogOnNextRestore,
            )
            installedFaces = restored.installedFaces
            catalog = if (repairCatalogOnNextRestore && restored.snapshot.diagnostic != null) {
                repository.readCatalog()
            } else {
                restored.snapshot
            }
            if (restored.scanComplete) {
                catalogWarning = null
                cleanObsoleteMarketRevisions(catalog, restored.installedFaces)
            } else {
                val first = restored.scanFailures.firstOrNull()
                val warning = context.getString(
                    R.string.watch_store_scan_warning_fmt,
                    restored.scanFailures.size,
                )
                catalogWarning = if (first == null) {
                    warning
                } else {
                    context.getString(
                        R.string.watch_store_scan_warning_detail_fmt,
                        warning,
                        first.directoryName.ifBlank {
                            context.getString(R.string.watch_store_unknown_directory)
                        },
                        first.message,
                    )
                }
            }
            repairCatalogOnNextRestore = false
        } catch (error: Throwable) {
            error.throwIfCancellation()
            catalogError = error.userFacingMessage(context)
        } finally {
            catalogInitialized = true
        }
    }

    LaunchedEffect(
        repository,
        catalogInitialized,
        catalog.diagnostic,
        installedFaces,
        reconcileToken,
        catalogReloadToken,
    ) {
        if (
            !catalogInitialized ||
            catalogError != null ||
            catalog.diagnostic != null ||
            installedFaces.isEmpty() ||
            lastLegacyAssociationSignature == legacyAssociationSignature
        ) {
            return@LaunchedEffect
        }
        // Legacy name association can issue several public-store searches.
        // Run it once for a stable installed set instead of repeating the same
        // network probes on every Activity resume/catalog reconciliation.
        lastLegacyAssociationSignature = legacyAssociationSignature
        try {
            catalog = repository.associateLegacyMarketInstalls(installedFaces)
        } catch (error: Throwable) {
            error.throwIfCancellation()
        }
    }

    // Debounce typing so a tiny watch does not open a request for every key.
    LaunchedEffect(searchText) {
        delay(350)
        committedSearch = searchText.trim().take(200)
    }

    LaunchedEffect(repository, categoriesReloadToken) {
        categoriesError = null
        runCatching { repository.getCategories() }
            .onSuccess { categories = it }
            .onFailure {
                it.throwIfCancellation()
            categoriesError = it.userFacingMessage(context)
            }
    }

    val queryKey = remember(sort, screenType, selectedCategory?.slug, committedSearch) {
        StoreQueryKey(sort, screenType, selectedCategory?.slug, committedSearch)
    }

    // A new filter is a new snapshot.  The old list is cleared before the
    // request starts, preventing stale cards from being mistaken for page 1.
    LaunchedEffect(queryKey, reloadToken) {
        listLoading = true
        loadingMore = false
        listError = null
        marketLoadSucceeded = false
        entries = emptyList()
        pagination = null
        try {
            val firstPage = repository.listWatchFaces(
                WatchStoreQuery(
                    page = 1,
                    limit = 20,
                    sort = queryKey.sort,
                    screenType = queryKey.screenType,
                    categorySlug = queryKey.categorySlug,
                    search = queryKey.fields.search,
                    tag = queryKey.fields.tag,
                ),
            )
            entries = firstPage.data
            pagination = firstPage.pagination
            marketLoadSucceeded = true
        } catch (error: Throwable) {
            error.throwIfCancellation()
            listError = error.userFacingMessage(context)
        } finally {
            listLoading = false
            listLoadCompletedToken = reloadToken
        }
    }

    // 命中缓存时首页可能在几十毫秒内返回，指示器一闪而过反而像没刷新。
    // 触发后至少停 420ms 再允许收起。
    LaunchedEffect(pullRefreshRequestedToken) {
        if (pullRefreshRequestedToken == null) return@LaunchedEffect
        pullRefreshHolding = true
        delay(420)
        pullRefreshHolding = false
    }

    // Paging is driven by the active lazy container's visible index, not by
    // recomposition of every card.  The repository requests off-main.
    LaunchedEffect(viewMode, listState, gridState, queryKey, reloadToken) {
        val prefetchThreshold = if (viewMode == WatchStoreViewMode.GRID) 6 else 3
        snapshotFlow {
            val lastIndex = when (viewMode) {
                WatchStoreViewMode.LIST -> listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                WatchStoreViewMode.GRID -> gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } ?: -1
            lastIndex >= (entries.size - prefetchThreshold).coerceAtLeast(0)
        }.distinctUntilChanged().collect { nearEnd ->
            val current = pagination ?: return@collect
            if (!nearEnd || listLoading || loadingMore || !current.hasNextPage) return@collect
            loadingMore = true
            try {
                val nextPage = repository.listWatchFaces(
                    WatchStoreQuery(
                        page = current.page + 1,
                        limit = current.limit,
                        sort = queryKey.sort,
                        screenType = queryKey.screenType,
                        categorySlug = queryKey.categorySlug,
                        search = queryKey.fields.search,
                        tag = queryKey.fields.tag,
                    ),
                )
                // The API can legally repeat an item across pages. Stable IDs
                // keep the list deterministic and prevent duplicate downloads.
                val known = entries.asSequence().map { it.id }.toHashSet()
                entries = entries + nextPage.data.filter { known.add(it.id) }
                pagination = nextPage.pagination
            } catch (error: Throwable) {
                error.throwIfCancellation()
            listError = error.userFacingMessage(context)
            } finally {
                loadingMore = false
            }
        }
    }

    BackHandler {
        if (selectedId != null) {
            selectedId = null
            installedActionError = null
        } else {
            onBack()
        }
    }

    LaunchedEffect(selectedId, detailReloadToken) {
        val id = selectedId ?: return@LaunchedEffect
        detailLoading = true
        detailError = null
        runCatching { repository.getWatchFace(id) }
            .onSuccess { selectedDetail = it }
            .onFailure {
                it.throwIfCancellation()
            detailError = it.userFacingMessage(context)
            }
        detailLoading = false
    }

    fun download(id: String, screenType: WatchStoreScreenType) {
        val current = downloadStates[id]?.value
        if (current is WatchStoreInstallState.Downloading ||
            current is WatchStoreInstallState.Validating ||
            current is WatchStoreInstallState.Installing
        ) return
        val firstState = (current as? WatchStoreInstallState.Failed)?.retry()
            ?: WatchStoreInstallState.Downloading(bytes = 0L, totalBytes = null, attempt = 1)
        val state = MutableStateFlow<WatchStoreInstallState>(firstState)
        downloadStates[id] = state
        val job = scope.launch {
            var downloadedBytes = 0L
            val pending = pendingCatalogInstalls[id]
            try {
                val record = if (pending != null) {
                    // The archive is already on disk. Only repair the catalog.
                    state.value = WatchStoreInstallState.Validating(firstState.attempt)
                    state.value = WatchStoreInstallState.Installing(firstState.attempt)
                    repository.recordMarketInstallationDetailed(
                        remoteId = id,
                        activeRevisionSha256 = pending.revisionSha256,
                        installedDescriptorId = pending.descriptorId,
                        installedDisplayName = pending.displayName,
                        installedAtEpochMs = pending.installedAtEpochMs,
                        compatibilityReport = pending.compatibilityReport,
                    ).record.also { pendingCatalogInstalls.remove(id) }
                } else {
                    val archive = repository.downloadWatchFace(id) { bytes, totalBytes ->
                        downloadedBytes = bytes
                        state.value = WatchStoreInstallState.Downloading(
                            bytes = bytes,
                            totalBytes = totalBytes,
                            attempt = firstState.attempt,
                        )
                    }
                    try {
                        state.value = WatchStoreInstallState.Validating(firstState.attempt)
                        val revision = repository.computeRevisionSha256(archive)
                        repository.beginMarketInstallTransaction(id, revision)
                        state.value = WatchStoreInstallState.Installing(firstState.attempt)
                        val descriptor = installArchive(
                            archive,
                            screenType.installAsRound(),
                        ).getOrThrow()
                        val descriptorWasInstalled = installedFacesById.containsKey(descriptor.id)
                        val pendingInstall = PendingMarketCatalogInstall(
                            revisionSha256 = revision,
                            descriptorId = descriptor.id,
                            displayName = descriptor.name,
                            installedAtEpochMs = descriptor.installedAtEpochMs,
                            compatibilityReport = descriptor.compatibilityReport,
                        )
                        pendingCatalogInstalls[id] = pendingInstall
                        repository.markMarketInstallPackagePublished(
                            remoteId = id,
                            installedDescriptorId = pendingInstall.descriptorId,
                            installedDisplayName = pendingInstall.displayName,
                            installedAtEpochMs = pendingInstall.installedAtEpochMs,
                        )
                        commitMarketInstallCatalog(
                            wasAlreadyInstalled = descriptorWasInstalled,
                            commit = {
                                repository.recordMarketInstallationDetailed(
                                    remoteId = id,
                                    activeRevisionSha256 = pendingInstall.revisionSha256,
                                    installedDescriptorId = pendingInstall.descriptorId,
                                    installedDisplayName = pendingInstall.displayName,
                                    installedAtEpochMs = pendingInstall.installedAtEpochMs,
                                    compatibilityReport = pendingInstall.compatibilityReport,
                                )
                            },
                            rollback = { deleteInstalled(listOf(pendingInstall.descriptorId)) },
                        ).let { upsert ->
                            pendingCatalogInstalls.remove(id)
                            val obsolete = upsert.supersededLauncherIds.filter(installedFacesById::containsKey)
                            if (obsolete.isNotEmpty()) {
                                deleteInstalled(obsolete).onFailure { cleanupError ->
                                    installedActionError = context.getString(
                                        R.string.watch_store_error_superseded_cleanup_fmt,
                                        cleanupError.userFacingMessage(context),
                                    )
                                }
                            }
                            upsert.record
                        }
                    } finally {
                        withContext(Dispatchers.IO + NonCancellable) { archive.delete() }
                    }
                }
                state.value = WatchStoreInstallState.Installed(record)
                catalog = repository.readCatalog()
                // Refresh the authoritative descriptor scan so the detail page
                // can immediately expose use/edit/delete actions for the new id.
                catalogReloadToken++
            } catch (cancelled: CancellationException) {
                runCatching { repository.discardAbandonedMarketInstallTransaction(id) }
                throw cancelled
            } catch (error: Throwable) {
                if (pendingCatalogInstalls[id] == null) {
                    runCatching { repository.discardAbandonedMarketInstallTransaction(id) }
                        .onFailure { cleanupError ->
                            cleanupError.throwIfCancellation()
                            installedActionError = context.getString(
                                R.string.watch_store_error_transaction_cleanup_fmt,
                                cleanupError.userFacingMessage(context),
                            )
                        }
                }
                if (pendingCatalogInstalls[id] != null) {
                    // Disk install succeeded; retry must repair metadata rather
                    // than download/import a duplicate archive.
                    runCatching {
                        val restored = repository.restoreCatalogFromInstalledFaces()
                        installedFaces = restored.installedFaces
                        catalog = restored.snapshot
                    }.onFailure { restoreError -> restoreError.throwIfCancellation() }
                }
                state.value = WatchStoreInstallState.Failed(
                    message = if (error is WatchStoreInstallTransactionException && error.rollbackCompleted) {
                        pendingCatalogInstalls.remove(id)
                        runCatching { repository.discardMarketInstallTransaction(id) }
                        error.userFacingMessage(context)
                    } else if (pendingCatalogInstalls[id] != null) {
                        context.getString(R.string.watch_store_error_catalog_restore_after_install)
                    } else {
                        error.userFacingMessage(context)
                    },
                    failedAtEpochMs = System.currentTimeMillis(),
                    downloadedBytes = downloadedBytes,
                    attempt = firstState.attempt,
                )
            } finally {
                val runningJob = coroutineContext[Job]
                if (downloadJobs[id] === runningJob) {
                    downloadJobs.remove(id)
                }
            }
        }
        downloadJobs[id] = job
    }

    fun cancelDownload(id: String) {
        downloadJobs.remove(id)?.cancel()
        downloadStates.remove(id)
    }

    // C3：从网页版回来的那个 id，等详情（screenType 在里面）到齐再下载。
    // screenType 决定装成圆屏还是方屏，抢在详情之前下载会按 UNKNOWN 装错。
    // 已经在下/已装好的直接放行，`download` 自己会去重。
    LaunchedEffect(pendingWebInstallId, selectedDetail?.id, entries) {
        val id = pendingWebInstallId ?: return@LaunchedEffect
        val type = selectedDetail?.takeIf { it.id == id }?.screenType
            ?: entries.firstOrNull { it.id == id }?.screenType
            ?: return@LaunchedEffect
        pendingWebInstallId = null
        download(id, type)
    }

    fun persistentInstallState(id: String): WatchStoreInstallState =
        installedMarketRecords[id]
            ?.let(WatchStoreInstallState::Installed)
            ?: WatchStoreInstallState.NotInstalled

    fun renameInstalled(id: String, record: WatchFaceRecord, name: String) {
        scope.launch {
            runCatching { repository.renameInstalledWatchFace(record.logicalId, name) }
                .onSuccess { updated ->
                    catalog = updated
                    updated.findMarketInstall(WATCHMAKER_COMMUNITY_STORE_ID, id)?.let {
                        downloadStates[id]?.value = WatchStoreInstallState.Installed(it)
                    }
                    installedActionError = null
                }
                .onFailure {
                    it.throwIfCancellation()
                    installedActionError = it.userFacingMessage(context)
                }
        }
    }

    fun removeInstalled(id: String, record: WatchFaceRecord) {
        val launcherId = record.activeLauncherIdOrNull() ?: run {
            installedActionError = context.getString(R.string.watch_store_error_installed_identity_missing)
            return
        }
        val installedIds = (record.legacyIds + launcherId)
            .filter(installedFacesById::containsKey)
            .ifEmpty { listOf(launcherId) }
        scope.launch {
            val deletion = deleteInstalled(installedIds)
            deletion.onFailure {
                it.throwIfCancellation()
                installedActionError = it.userFacingMessage(context)
            }.onSuccess {
                installedFaces = installedFaces.filterNot { it.id in installedIds }
                downloadStates.remove(id)
                // The disk scan is authoritative after deletion. Hide the stale
                // market record immediately even if catalog persistence fails.
                catalog = catalog.copy(records = catalog.records.filterNot { it.logicalId == record.logicalId })
                runCatching { repository.removeMarketInstallation(id) }
                    .onSuccess { updated ->
                        catalog = updated
                        installedActionError = null
                    }
                    .onFailure { catalogFailure ->
                        catalogFailure.throwIfCancellation()
                        installedActionError = context.getString(
                            R.string.watch_store_error_catalog_recovery_after_delete_fmt,
                            catalogFailure.userFacingMessage(context),
                        )
                        repairCatalogOnNextRestore = true
                        catalogReloadToken++
                    }
            }
        }
    }

    // Match the settings hierarchy transition: when a child page is pushed,
    // the preserved browse page moves left by 1/6. Placement-phase movement
    // avoids a persistent full-screen graphics layer while retaining both lazy states.
    val browseCovered = selectedId != null || uploadVisible || myUploadsVisible
    val browseHierarchyOffset = animateFloatAsState(
        targetValue = if (browseCovered) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (browseCovered) 240 else 260,
            easing = FastOutSlowInEasing,
        ),
        label = "watch_store_browse_hierarchy_offset",
    )
    var browseRootTopInWindow by remember { mutableFloatStateOf(0f) }
    var browseListTopInWindow by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { browseRootTopInWindow = it.positionInWindow().y }
            .background(style.screenBackground),
    ) {
        val browseDensity = LocalDensity.current
        val browseViewportWidthPx = with(browseDensity) { maxWidth.toPx() }
        val browseViewportHeightPx = with(browseDensity) { maxHeight.toPx() }
        val browseScreenCenterY = browseViewportHeightPx / 2f
        val browseListTopInViewport = { browseListTopInWindow - browseRootTopInWindow }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(
                            x = (-placeable.width / 6f * browseHierarchyOffset.value).roundToInt(),
                            y = 0,
                        )
                    }
                }
                .padding(
                    horizontal = if (isScreenRound) 26.dp else 10.dp,
                    vertical = if (isScreenRound) 10.dp else 6.dp,
                ),
        ) {
            val showEntries = catalogInitialized && catalog.diagnostic == null
            val visibleEntries = if (showEntries) entries else emptyList()
            val currentCatalogError = catalogError
            val currentCatalogWarning = catalogWarning
            val currentListError = listError
            val statusRows = buildList<Pair<String, @Composable () -> Unit>> {
                if (!catalogInitialized) {
                    add(
                        "catalog_loading" to {
                            LoadingStateRow(stringResource(R.string.watch_store_status_restoring_installations))
                        },
                    )
                }
                if (catalogInitialized && currentCatalogError != null) {
                    add(
                        "catalog_error" to {
                            ErrorStateRow(message = currentCatalogError, onRetry = { catalogReloadToken++ })
                        },
                    )
                }
                if (catalogInitialized && currentCatalogError == null && currentCatalogWarning != null) {
                    add(
                        "catalog_scan_warning" to {
                            ErrorStateRow(message = currentCatalogWarning, onRetry = { catalogReloadToken++ })
                        },
                    )
                }
                catalog.diagnostic?.let { diagnostic ->
                    add(
                        "catalog_recovery" to {
                            CatalogRecoveryRow(
                                diagnostic = diagnostic,
                                onRescan = {
                                    repairCatalogOnNextRestore = true
                                    catalogReloadToken++
                                },
                            )
                        },
                    )
                }
                if (catalog.diagnostic == null) {
                    when {
                        listLoading && entries.isEmpty() ->
                            add(
                                "list_loading" to {
                                    LoadingStateRow(stringResource(R.string.watch_store_status_loading_faces))
                                },
                            )
                        currentListError != null && entries.isEmpty() ->
                            add(
                                "list_error" to {
                                    ErrorStateRow(
                                        message = currentListError,
                                        onRetry = { reloadToken++ },
                                        onOpenCustomMarketUrl = { marketUrlDialogVisible = true },
                                    )
                                },
                            )
                        showEntries && !listLoading && !loadingMore && entries.isEmpty() ->
                            add("list_empty" to { EmptyStateRow() })
                    }
                    if (loadingMore) {
                        add(
                            "loading_more" to {
                                LoadingStateRow(stringResource(R.string.watch_store_status_loading_more))
                            },
                        )
                    }
                    if (currentListError != null && entries.isNotEmpty()) {
                        add(
                            "tail_error" to {
                                ErrorStateRow(message = currentListError, onRetry = { reloadToken++ })
                            },
                        )
                    }
                }
            }
            // 圆屏：上下 contentPadding 按弦宽加大，避免首尾卡片被表盘圆角裁掉。
            val roundPadding = roundScrollablePadding(
                viewportHeight = browseViewportHeightPx,
                contentTop = browseListTopInViewport(),
                itemHeight = with(browseDensity) { 104.dp.toPx() },
                minimumPadding = with(browseDensity) { 14.dp.toPx() },
            )
            val browseContentPadding = PaddingValues(
                top = if (isScreenRound) with(browseDensity) { roundPadding.top.toDp() } else 4.dp,
                // 圆屏底部要多留一条：悬浮胶囊压在列表上，不留的话最后一张卡
                // 永远被它盖住一半，滚到底也读不全。
                bottom = if (isScreenRound) {
                    with(browseDensity) { roundPadding.bottom.toDp() } +
                        STORE_FLOATING_ACTIONS_RESERVED_HEIGHT
                } else {
                    20.dp
                },
            )
            val pullRefreshState = rememberFluePullToRefreshState(
                refreshing = pullRefreshing,
                onRefresh = {
                    reloadToken++
                    pullRefreshRequestedToken = reloadToken
                    // 分类是筛选栏的数据源，与列表同批刷新；失败也不阻塞列表。
                    categoriesReloadToken++
                },
                atTop = {
                    if (viewMode == WatchStoreViewMode.LIST) {
                        listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                    } else {
                        gridState.firstVisibleItemIndex == 0 &&
                            gridState.firstVisibleItemScrollOffset == 0
                    }
                },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                // 指示器画在列表下方、被内容位移带出来：列表本体由
                // fluePullToRefresh 在 placement 阶段下移，指示器停在顶端
                // 固定位置，视觉上就是从列表底下被拉出来的。
                PullRefreshIndicator(
                    state = pullRefreshState,
                    refreshing = pullRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                if (viewMode == WatchStoreViewMode.LIST) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { browseListTopInWindow = it.positionInWindow().y }
                            .flueRotaryScrollable(
                                focusRequester = focusRequester,
                                multiplier = ROTARY_SCROLL_MULTIPLIER,
                                onRotaryDelta = { rotaryDelta ->
                                    scope.launch {
                                        val consumed = listState.scrollBy(-rotaryDelta)
                                        if (
                                            rotaryHapticsEnabled &&
                                            abs(consumed) > 0.01f &&
                                            rotaryHapticGate.tryAcquire(SystemClock.elapsedRealtime())
                                        ) {
                                            vibrateHaptic(context)
                                        }
                                    }
                                },
                            ) { pointerDelta ->
                                scope.launch { listState.scrollBy(-pointerDelta) }
                            }
                            .flueOverscroll(listState)
                            .flueScrollEndHaptic()
                            .fluePullToRefresh(pullRefreshState),
                        contentPadding = browseContentPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(STORE_BROWSE_CONTROLS_KEY) {
                            WatchStoreBrowseControls(
                                isScreenRound = isScreenRound,
                                rootTopInWindow = { browseRootTopInWindow },
                                viewportWidth = browseViewportWidthPx,
                                screenCenterY = browseScreenCenterY,
                                searchText = searchText,
                                onSearchTextChange = { searchText = it.take(200) },
                                sort = sort,
                                onSortChange = { sort = it },
                                screenType = screenType,
                                onScreenTypeChange = { screenType = it },
                                selectedCategory = selectedCategory,
                                categories = categories,
                                categoriesError = categoriesError,
                                categoryMenuExpanded = categoryMenuExpanded,
                                onCategoryMenuExpandedChange = { categoryMenuExpanded = it },
                                onCategoryChange = {
                                    selectedCategory = it
                                    categoryMenuExpanded = false
                                },
                                viewMode = viewMode,
                                // 「我的上传 / 上传表盘」看登录态，不再只看网络是否加载成功；
                                // showManagement 退化成「市场可达」这一层含义。
                                showManagement = marketLoadSucceeded,
                                isLoggedIn = notificationSession != null,
                                onLogin = requestLogin,
                                onToggleViewMode = {
                                    val next = if (viewMode == WatchStoreViewMode.LIST) {
                                        WatchStoreViewMode.GRID
                                    } else {
                                        WatchStoreViewMode.LIST
                                    }
                                    viewMode = next
                                    uiPreferences.writeViewMode(next)
                                },
                                onUpload = { uploadVisible = true },
                                onMyUploads = { myUploadsVisible = true },
                                onOpenCustomMarketUrl = { marketUrlDialogVisible = true },
                                onBack = onBack,
                                // 没登录不摆铃铛：通知是账号的东西，未登录时点开只能显示
                                // 一句「请先登录」，那不如不出现。
                                onOpenNotifications = if (notificationSession != null) {
                                    { notificationsVisible = true }
                                } else {
                                    null
                                },
                                hasUnreadNotifications = unreadNotificationCount > 0,
                                onMyFavorites = if (notificationSession != null) {
                                    { favoritesVisible = true }
                                } else {
                                    null
                                },
                                onMyCollections = if (notificationSession != null) {
                                    { collectionsVisible = true }
                                } else {
                                    null
                                },
                                onOpenProfile = if (notificationSession != null) {
                                    { profileVisible = true }
                                } else {
                                    null
                                },
                                onOpenCreators = {
                                    // 从溢出菜单进来的是**列表**：必须清掉上一次
                                    // 从详情页带进来的用户名，否则会直接落到那个人的主页。
                                    creatorUsernameToOpen = null
                                    creatorsVisible = true
                                },
                                onOpenWebStore = { openWebPage(WatchStoreWebPage.HOME) },
                                onOpenWebStoreExternal = { openExternalPage(WatchStoreWebPage.HOME) },
                            )
                        }
                        items(
                            items = visibleEntries,
                            key = { it.id },
                            contentType = { "watch_face_list_card" },
                        ) { item ->
                            val transientState = downloadStates[item.id]
                                ?.collectAsStateWithLifecycle()
                                ?.value
                            WatchFaceListCard(
                                repository = repository,
                                item = item,
                                downloadState = transientState ?: persistentInstallState(item.id),
                                onOpen = { openDetail(item.id) },
                                onDownload = { download(item.id, item.screenType) },
                                onCancelDownload = { cancelDownload(item.id) },
                                modifier = Modifier.chordScaleItem(
                                    listState = listState,
                                    key = item.id,
                                    screenCenterY = browseScreenCenterY,
                                    viewportWidth = browseViewportWidthPx,
                                    enabled = isScreenRound,
                                    listTopInViewport = browseListTopInViewport,
                                    allowTallItem = true,
                                ),
                            )
                        }
                        items(
                            items = statusRows,
                            key = { it.first },
                            contentType = { "watch_store_status" },
                        ) { row -> row.second() }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { browseListTopInWindow = it.positionInWindow().y }
                            .flueRotaryScrollable(
                                focusRequester = focusRequester,
                                multiplier = ROTARY_SCROLL_MULTIPLIER,
                                onRotaryDelta = { rotaryDelta ->
                                    scope.launch {
                                        val consumed = gridState.scrollBy(-rotaryDelta)
                                        if (
                                            rotaryHapticsEnabled &&
                                            abs(consumed) > 0.01f &&
                                            rotaryHapticGate.tryAcquire(SystemClock.elapsedRealtime())
                                        ) {
                                            vibrateHaptic(context)
                                        }
                                    }
                                },
                            ) { pointerDelta ->
                                scope.launch { gridState.scrollBy(-pointerDelta) }
                            }
                            .fluePullToRefresh(pullRefreshState),
                        contentPadding = browseContentPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(
                            key = STORE_BROWSE_CONTROLS_KEY,
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            WatchStoreBrowseControls(
                                isScreenRound = isScreenRound,
                                rootTopInWindow = { browseRootTopInWindow },
                                viewportWidth = browseViewportWidthPx,
                                screenCenterY = browseScreenCenterY,
                                searchText = searchText,
                                onSearchTextChange = { searchText = it.take(200) },
                                sort = sort,
                                onSortChange = { sort = it },
                                screenType = screenType,
                                onScreenTypeChange = { screenType = it },
                                selectedCategory = selectedCategory,
                                categories = categories,
                                categoriesError = categoriesError,
                                categoryMenuExpanded = categoryMenuExpanded,
                                onCategoryMenuExpandedChange = { categoryMenuExpanded = it },
                                onCategoryChange = {
                                    selectedCategory = it
                                    categoryMenuExpanded = false
                                },
                                viewMode = viewMode,
                                // 「我的上传 / 上传表盘」看登录态，不再只看网络是否加载成功；
                                // showManagement 退化成「市场可达」这一层含义。
                                showManagement = marketLoadSucceeded,
                                isLoggedIn = notificationSession != null,
                                onLogin = requestLogin,
                                onToggleViewMode = {
                                    val next = if (viewMode == WatchStoreViewMode.LIST) {
                                        WatchStoreViewMode.GRID
                                    } else {
                                        WatchStoreViewMode.LIST
                                    }
                                    viewMode = next
                                    uiPreferences.writeViewMode(next)
                                },
                                onUpload = { uploadVisible = true },
                                onMyUploads = { myUploadsVisible = true },
                                onOpenCustomMarketUrl = { marketUrlDialogVisible = true },
                                onBack = onBack,
                                // 没登录不摆铃铛：通知是账号的东西，未登录时点开只能显示
                                // 一句「请先登录」，那不如不出现。
                                onOpenNotifications = if (notificationSession != null) {
                                    { notificationsVisible = true }
                                } else {
                                    null
                                },
                                hasUnreadNotifications = unreadNotificationCount > 0,
                                onMyFavorites = if (notificationSession != null) {
                                    { favoritesVisible = true }
                                } else {
                                    null
                                },
                                onMyCollections = if (notificationSession != null) {
                                    { collectionsVisible = true }
                                } else {
                                    null
                                },
                                onOpenProfile = if (notificationSession != null) {
                                    { profileVisible = true }
                                } else {
                                    null
                                },
                                onOpenCreators = {
                                    // 从溢出菜单进来的是**列表**：必须清掉上一次
                                    // 从详情页带进来的用户名，否则会直接落到那个人的主页。
                                    creatorUsernameToOpen = null
                                    creatorsVisible = true
                                },
                                onOpenWebStore = { openWebPage(WatchStoreWebPage.HOME) },
                                onOpenWebStoreExternal = { openExternalPage(WatchStoreWebPage.HOME) },
                            )
                        }
                        gridItems(
                            items = visibleEntries,
                            key = { it.id },
                            contentType = { "watch_face_grid_card" },
                        ) { item ->
                            val transientState = downloadStates[item.id]
                                ?.collectAsStateWithLifecycle()
                                ?.value
                            WatchFaceGridCard(
                                repository = repository,
                                item = item,
                                downloadState = transientState ?: persistentInstallState(item.id),
                                onOpen = { openDetail(item.id) },
                                onDownload = { download(item.id, item.screenType) },
                                onCancelDownload = { cancelDownload(item.id) },
                                modifier = Modifier.chordScaleGridItem(
                                    gridState = gridState,
                                    key = item.id,
                                    screenCenterY = browseScreenCenterY,
                                    viewportWidth = browseViewportWidthPx,
                                    enabled = isScreenRound,
                                    gridTopInViewport = browseListTopInViewport,
                                    allowTallItem = true,
                                ),
                            )
                        }
                        gridItems(
                            items = statusRows,
                            key = { it.first },
                            contentType = { "watch_store_status" },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { row -> row.second() }
                    }
                }
                // 圆屏的动作入口：顶栏那一行左右两端在物理上点不到，
                // 所以整条动作搬到屏幕底部（见 StoreFloatingActions）。
                if (isScreenRound) {
                    StoreFloatingActions(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onBack = onBack,
                        viewMode = viewMode,
                        onToggleViewMode = {
                            val next = if (viewMode == WatchStoreViewMode.LIST) {
                                WatchStoreViewMode.GRID
                            } else {
                                WatchStoreViewMode.LIST
                            }
                            viewMode = next
                            uiPreferences.writeViewMode(next)
                        },
                        onUpload = { uploadVisible = true },
                        onMyUploads = { myUploadsVisible = true },
                        onOpenCustomMarketUrl = { marketUrlDialogVisible = true },
                        showManagement = marketLoadSucceeded,
                        isLoggedIn = notificationSession != null,
                        onLogin = requestLogin,
                        onOpenNotifications = if (notificationSession != null) {
                            { notificationsVisible = true }
                        } else {
                            null
                        },
                        hasUnreadNotifications = unreadNotificationCount > 0,
                        onMyFavorites = if (notificationSession != null) {
                            { favoritesVisible = true }
                        } else {
                            null
                        },
                        onMyCollections = if (notificationSession != null) {
                            { collectionsVisible = true }
                        } else {
                            null
                        },
                        onOpenProfile = if (notificationSession != null) {
                            { profileVisible = true }
                        } else {
                            null
                        },
                        onOpenCreators = {
                            creatorUsernameToOpen = null
                            creatorsVisible = true
                        },
                        onOpenWebStore = { openWebPage(WatchStoreWebPage.HOME) },
                        onOpenWebStoreExternal = { openExternalPage(WatchStoreWebPage.HOME) },
                    )
                }
            }
        }

        // 表盘市场的层级切换统一使用设置页的 MIUIX / HyperOS 推拉动效；
        // 列表仍留在组合树中，因此返回时不会丢失滚动位置。
        AnimatedVisibility(
            visible = selectedId != null,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(1f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            val overlayId = selectedId ?: lastDetailId
            if (overlayId != null) {
                val transientState = downloadStates[overlayId]
                    ?.collectAsStateWithLifecycle()
                    ?.value
                WatchFaceDetailPage(
                    repository = repository,
                    api = api,
                    remoteId = overlayId,
                    detail = selectedDetail,
                    summary = entries.firstOrNull { it.id == overlayId },
                    localDescriptor = (transientState as? WatchStoreInstallState.Installed)?.record
                        ?.activeLauncherIdOrNull()
                        ?.let(installedFacesById::get)
                        ?: installedMarketRecords[overlayId]
                            ?.activeLauncherIdOrNull()
                            ?.let(installedFacesById::get),
                    loading = detailLoading,
                    error = detailError,
                    downloadState = transientState ?: persistentInstallState(overlayId),
                    actionError = installedActionError,
                    onBack = {
                        selectedId = null
                        installedActionError = null
                    },
                    onRetryDetail = {
                        detailError = null
                        detailLoading = true
                        detailReloadToken++
                    },
                    onDownload = {
                        download(
                            overlayId,
                            selectedDetail?.screenType
                                ?: entries.firstOrNull { it.id == overlayId }?.screenType
                                ?: WatchStoreScreenType.UNKNOWN,
                        )
                    },
                    onCancelDownload = { cancelDownload(overlayId) },
                    onUse = { record -> record.activeLauncherIdOrNull()?.let(useInstalled) },
                    onEdit = { record -> record.activeLauncherIdOrNull()?.let(editInstalled) },
                    onRename = { record, name -> renameInstalled(overlayId, record, name) },
                    onDelete = { record -> removeInstalled(overlayId, record) },
                    onExport = { record -> record.activeLauncherIdOrNull()?.let(exportInstalled) },
                    onOpenWeb = { openWebWatchFace(overlayId) },
                    onOpenWebExternal = { openExternalWatchFace(overlayId) },
                    sessionToken = notificationSession?.token,
                    sessionAuthorId = notificationAccountId,
                    featureFlags = featureFlags,
                    onOpenCreator = { username ->
                        creatorUsernameToOpen = username
                        creatorsVisible = true
                    },
                    onRequestLogin = requestLogin,
                )
            }
        }

        // 商店内上传入口：覆层挂在详情之上（zIndex 2），来源支持已安装表盘
        // 与系统文件选择器两路；登录/注册表单与编辑器上传覆层共用同一实现。
        AnimatedVisibility(
            visible = uploadVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(2f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            StoreUploadOverlay(
                repository = repository,
                installedFaces = installedFaces,
                registrationEnabled = featureFlags.registrationEnabled,
                onUploaded = { reloadToken++ },
                onDismiss = { uploadVisible = false },
                // G5/H1：手表上打字费劲，登录与上传都给一条「去网页版做」的路。
                onOpenWeb = { openWebPage(WatchStoreWebPage.DASHBOARD) },
                onOpenWebExternal = { openExternalPage(WatchStoreWebPage.DASHBOARD) },
            )
        }

        // 已上传表盘管理：列出当前账号上传的全部表盘，支持删除。
        AnimatedVisibility(
            visible = myUploadsVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(3f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            MyUploadsOverlay(
                repository = repository,
                installedFaces = installedFaces,
                registrationEnabled = featureFlags.registrationEnabled,
                // 从菜单的「登录 / 注册」或详情页的「去登录」进来时直接落在表单上，
                // 不经过中间那页「登录后查看我的上传」（老师原话：点击直接跳转登录）。
                startInLoginForm = loginFormRequested,
                onDismiss = { myUploadsVisible = false },
                onOpenWeb = { openWebPage(WatchStoreWebPage.DASHBOARD) },
                onOpenWebExternal = { openExternalPage(WatchStoreWebPage.DASHBOARD) },
            )
        }

        AnimatedVisibility(
            visible = notificationsVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(3f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            val token = notificationSession?.token
            if (token != null) {
                NotificationCenterOverlay(
                    api = notificationApi,
                    token = token,
                    onUnreadCountChange = { count ->
                        unreadNotificationCount = count
                        if (count == 0) scope.launch { unreadCounter.markAllRead() }
                    },
                    onTokenExpired = {
                        notificationAuthStore.clear()
                        notificationSession = null
                        unreadNotificationCount = 0
                        notificationsVisible = false
                    },
                    onDismiss = { notificationsVisible = false },
                )
            }
        }

        // 我的收藏 / 我的合集（B3b）。点一条表盘 → 关掉覆层并打开详情：下载与安装
        // 那套机器只有详情页有，不在这两页里再养一份。
        AnimatedVisibility(
            visible = favoritesVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(3f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            val token = notificationSession?.token
            if (token != null) {
                WatchStoreFavoritesOverlay(
                    repository = repository,
                    api = notificationApi,
                    token = token,
                    onOpenWatchFace = { id ->
                        favoritesVisible = false
                        openDetail(id)
                    },
                    onTokenExpired = {
                        notificationAuthStore.clear()
                        notificationSession = null
                        favoritesVisible = false
                    },
                    onDismiss = { favoritesVisible = false },
                )
            }
        }

        AnimatedVisibility(
            visible = profileVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(3f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            val token = notificationSession?.token
            if (token != null) {
                WatchStoreProfileOverlay(
                    repository = repository,
                    api = notificationApi,
                    token = token,
                    onOpenWeb = { openWebPage(WatchStoreWebPage.PROFILE) },
                    onOpenWebExternal = { openExternalPage(WatchStoreWebPage.PROFILE) },
                    onTokenExpired = {
                        notificationAuthStore.clear()
                        notificationSession = null
                        profileVisible = false
                    },
                    onDismiss = { profileVisible = false },
                )
            }
        }

        AnimatedVisibility(
            visible = creatorsVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(3f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            WatchStoreCreatorsOverlay(
                repository = repository,
                api = notificationApi,
                initialUsername = creatorUsernameToOpen,
                onOpenWatchFace = { id ->
                    creatorUsernameToOpen = null
                    creatorsVisible = false
                    openDetail(id)
                },
                onOpenWebList = { openWebPage(WatchStoreWebPage.CREATORS) },
                onOpenWebListExternal = { openExternalPage(WatchStoreWebPage.CREATORS) },
                onOpenWebCreator = { username ->
                    storeWebCreatorIntent(context, uiPreferences.readBaseUrl(), username)
                        ?.let(webHost::launch)
                },
                onOpenWebCreatorExternal = { username ->
                    storeWebCreatorIntent(context, uiPreferences.readBaseUrl(), username)
                        ?.getStringExtra(WatchStoreWebActivity.EXTRA_URL)
                        ?.let(openExternalUri)
                },
                onDismiss = {
                    creatorUsernameToOpen = null
                    creatorsVisible = false
                },
            )
        }

        AnimatedVisibility(
            visible = collectionsVisible,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(3f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            val token = notificationSession?.token
            if (token != null) {
                WatchStoreCollectionsOverlay(
                    repository = repository,
                    api = notificationApi,
                    token = token,
                    onOpenWatchFace = { id ->
                        collectionsVisible = false
                        openDetail(id)
                    },
                    onTokenExpired = {
                        notificationAuthStore.clear()
                        notificationSession = null
                        collectionsVisible = false
                    },
                    onDismiss = { collectionsVisible = false },
                )
            }
        }

        if (marketUrlDialogVisible) {
            WatchStoreBaseUrlDialog(
                preferences = uiPreferences,
                onSaved = {
                    marketUrlDialogVisible = false
                    // repository 是在 onCreate 里按当时的 base URL 建的，所以换地址靠
                    // Activity 重建（onResume 已有同一条路），不在这里重新搭一遍仓库。
                    onMarketUrlSaved()
                },
                onDismiss = { marketUrlDialogVisible = false },
            )
        }
    }
}

@Composable
private fun WatchStoreBrowseControls(
    isScreenRound: Boolean,
    rootTopInWindow: () -> Float,
    viewportWidth: Float,
    screenCenterY: Float,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    sort: WatchStoreSort,
    onSortChange: (WatchStoreSort) -> Unit,
    screenType: WatchStoreScreenType?,
    onScreenTypeChange: (WatchStoreScreenType?) -> Unit,
    selectedCategory: WatchStoreCategory?,
    categories: List<WatchStoreCategory>,
    categoriesError: String?,
    categoryMenuExpanded: Boolean,
    onCategoryMenuExpandedChange: (Boolean) -> Unit,
    onCategoryChange: (WatchStoreCategory?) -> Unit,
    viewMode: WatchStoreViewMode,
    showManagement: Boolean,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onToggleViewMode: () -> Unit,
    onUpload: () -> Unit,
    onMyUploads: () -> Unit,
    onOpenCustomMarketUrl: () -> Unit,
    onBack: () -> Unit,
    onOpenNotifications: (() -> Unit)? = null,
    hasUnreadNotifications: Boolean = false,
    onMyFavorites: (() -> Unit)? = null,
    onMyCollections: (() -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null,
    onOpenCreators: () -> Unit = {},
    onOpenWebStore: () -> Unit = {},
    onOpenWebStoreExternal: () -> Unit = {},
) {
    val style = LauncherTheme.style
    Column(modifier = Modifier.fillMaxWidth()) {
        RoundStoreChordContainer(
            enabled = isScreenRound,
            rootTopInWindow = rootTopInWindow,
            viewportWidth = viewportWidth,
            screenCenterY = screenCenterY,
        ) {
            StoreHeader(
                onBack = onBack,
                viewMode = viewMode,
                onToggleViewMode = onToggleViewMode,
                onUpload = onUpload,
                onMyUploads = onMyUploads,
                onOpenCustomMarketUrl = onOpenCustomMarketUrl,
                showManagement = showManagement,
                isLoggedIn = isLoggedIn,
                onLogin = onLogin,
                onOpenNotifications = onOpenNotifications,
                hasUnreadNotifications = hasUnreadNotifications,
                onMyFavorites = onMyFavorites,
                onMyCollections = onMyCollections,
                onOpenProfile = onOpenProfile,
                onOpenCreators = onOpenCreators,
                onOpenWebStore = onOpenWebStore,
                onOpenWebStoreExternal = onOpenWebStoreExternal,
                // 圆屏：动作全部交给底部悬浮胶囊，顶栏只剩标题。
                compactActions = isScreenRound,
            )
        }
        RoundStoreChordContainer(
            enabled = isScreenRound,
            rootTopInWindow = rootTopInWindow,
            viewportWidth = viewportWidth,
            screenCenterY = screenCenterY,
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = style.rowShape,
                placeholder = {
                    Text(
                        stringResource(R.string.watch_store_search_hint),
                        color = style.secondaryTextColor,
                        fontSize = 13.sp,
                    )
                },
                leadingIcon = {
                    Icon(
                        MiuixIcons.Search,
                        contentDescription = stringResource(R.string.watch_store_search),
                        tint = style.secondaryTextColor,
                    )
                },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchTextChange("") }) {
                            Icon(
                                FlueMiuixIcons.Close,
                                contentDescription = stringResource(R.string.watch_store_clear_search),
                                tint = style.secondaryTextColor,
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = style.accentColor,
                    unfocusedBorderColor = style.outlineColor,
                    focusedContainerColor = style.cardColor,
                    unfocusedContainerColor = style.cardColor,
                    cursorColor = style.accentColor,
                    focusedTextColor = style.bodyColor,
                    unfocusedTextColor = style.bodyColor,
                ),
            )
        }
        Spacer(Modifier.height(7.dp))
        RoundStoreChordContainer(
            enabled = isScreenRound,
            rootTopInWindow = rootTopInWindow,
            viewportWidth = viewportWidth,
            screenCenterY = screenCenterY,
        ) {
            FilterBar(
                sort = sort,
                onSortChange = onSortChange,
                screenType = screenType,
                onScreenTypeChange = onScreenTypeChange,
                selectedCategory = selectedCategory,
                categories = categories,
                categoriesError = categoriesError,
                categoryMenuExpanded = categoryMenuExpanded,
                onCategoryMenuExpandedChange = onCategoryMenuExpandedChange,
                onCategoryChange = onCategoryChange,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RoundStoreChordContainer(
    enabled: Boolean,
    rootTopInWindow: () -> Float,
    viewportWidth: Float,
    screenCenterY: Float,
    content: @Composable () -> Unit,
) {
    var itemCenterYInViewport by remember { mutableFloatStateOf(screenCenterY) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                itemCenterYInViewport = coordinates.positionInWindow().y - rootTopInWindow() +
                    coordinates.size.height / 2f
            }
            .graphicsLayer {
                val scale = if (enabled) {
                    chordScaleForPosition(
                        itemCenterY = itemCenterYInViewport,
                        viewportWidth = viewportWidth,
                        screenCenterY = screenCenterY,
                    )
                } else {
                    1f
                }
                scaleX = scale
                scaleY = scale
            },
    ) {
        content()
    }
}

/**
 * 市场顶栏。右侧固定三个槽位：通知铃铛 / 列表宫格切换 / 三点溢出。
 *
 * 以前是「我的上传 + 上传 + 切换」三个按钮平铺，登录后四个图标挤在窄圆屏上；
 * 现在除了切换之外一律进溢出菜单，往后加项也不会再挤到标题。
 *
 * @param onOpenNotifications 通知中心入口。为 null 时不渲染铃铛 —— 通知列表与未读数
 *   要 `/my/notifications` 那批接口（上游同步那一批），接上之前不摆一个点不动的图标。
 */
@Composable
private fun StoreHeader(
    onBack: () -> Unit,
    viewMode: WatchStoreViewMode,
    onToggleViewMode: () -> Unit,
    onUpload: () -> Unit,
    onMyUploads: () -> Unit,
    onOpenCustomMarketUrl: () -> Unit,
    showManagement: Boolean,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onOpenNotifications: (() -> Unit)? = null,
    hasUnreadNotifications: Boolean = false,
    onMyFavorites: (() -> Unit)? = null,
    onMyCollections: (() -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null,
    onOpenCreators: () -> Unit = {},
    onOpenWebStore: () -> Unit = {},
    onOpenWebStoreExternal: () -> Unit = {},
    compactActions: Boolean = false,
) {
    val style = LauncherTheme.style
    if (compactActions) {
        // 圆屏：顶栏只留标题，四个动作全部搬到屏幕底部的悬浮胶囊
        //（[StoreFloatingActions]）。
        //
        // 根因不是「按钮太小」而是**这一行的两端根本不在屏幕上**：圆屏的可视与
        // 可点区域都是内切圆，顶栏处于圆最窄的那一段，返回（最左）和三点（最右）
        // 落在被切掉的角上 —— 弦缩放能把它们画进圆里，却改不了它们在这一行里
        // 仍然贴着左右两端这件事。所以圆屏下不摆顶栏动作，改摆底部。
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) {
            StoreOverlayTitle(
                title = stringResource(R.string.watch_store_title),
                subtitle = stringResource(R.string.watch_store_community_subtitle),
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                MiuixIcons.Back,
                contentDescription = stringResource(R.string.watch_editor_back),
                tint = style.titleColor,
            )
        }
        Column(Modifier.weight(1f)) {
            // 与二级页共用同一份紧行距标题（见 StoreOverlayTitle 的注释）。
            StoreOverlayTitle(
                title = stringResource(R.string.watch_store_title),
                subtitle = stringResource(R.string.watch_store_community_subtitle),
            )
        }
        if (onOpenNotifications != null) {
            IconButton(onClick = onOpenNotifications, modifier = Modifier.size(40.dp)) {
                Box {
                    Icon(
                        // miuix 官方 extended.* 全表里没有铃铛（已逐个核对），项目自绘的
                        // 这一份本来就是按 MIUIX 规范（24dp 视口 / 1.5dp 线宽 / 圆端点）画的。
                        imageVector = FlueMiuixIcons.Notification,
                        contentDescription = stringResource(R.string.watch_store_notifications),
                        tint = style.titleColor,
                        modifier = Modifier.size(20.dp),
                    )
                    if (hasUnreadNotifications) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(WatchColors.ActiveRed),
                        )
                    }
                }
            }
        }
        IconButton(onClick = onToggleViewMode, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (viewMode == WatchStoreViewMode.LIST) {
                    MiuixIcons.GridView
                } else {
                    MiuixIcons.ListView
                },
                contentDescription = stringResource(
                    if (viewMode == WatchStoreViewMode.LIST) {
                        R.string.watch_store_switch_grid
                    } else {
                        R.string.watch_store_switch_list
                    },
                ),
                tint = style.titleColor,
                modifier = Modifier.size(20.dp),
            )
        }
        StoreOverflowMenu(
            showManagement = showManagement,
            isLoggedIn = isLoggedIn,
            onLogin = onLogin,
            onUpload = onUpload,
            onMyUploads = onMyUploads,
            onOpenCustomMarketUrl = onOpenCustomMarketUrl,
            onMyFavorites = onMyFavorites,
            onMyCollections = onMyCollections,
            onOpenProfile = onOpenProfile,
            onOpenCreators = onOpenCreators,
            onOpenWebStore = onOpenWebStore,
            onOpenWebStoreExternal = onOpenWebStoreExternal,
        )
    }
}

/** 悬浮胶囊离屏幕底边的距离。见 [StoreFloatingActions] 里那段几何推导。 */
private val STORE_FLOATING_ACTIONS_BOTTOM_INSET = 24.dp

/** 圆屏下列表底部要为悬浮胶囊多留出的高度（胶囊高 + 下边距 + 一点呼吸）。 */
private val STORE_FLOATING_ACTIONS_RESERVED_HEIGHT = 80.dp

/**
 * 圆屏下的底部悬浮动作胶囊。
 *
 * ## 为什么不是「把顶栏按钮沿弧线排」
 *
 * 老师给了两个选择。选底部悬浮的理由是几何上只需要满足一个约束，而弧形排布要维护两套：
 * 沿弧线排要给每个按钮各算切向角度与半径，圆屏一套、方屏还得退回直排再一套，
 * 而且按钮本身仍然贴在圆最窄的那一段上。
 *
 * ## 三个按钮不是随便定的
 *
 * 屏幕底部能容纳的宽度由内切圆的弦长决定。半径 r、离底边 y 处的半弦长是
 * `sqrt(r² - (r-y)²)`。取 230dp 圆屏（r=115dp）、胶囊宽 3×40+2×2+12 = 136dp：
 * 半弦 68dp 时 `115-y ≤ sqrt(115²-68²) ≈ 92.7`，即 y ≥ 22.3dp —— 所以
 * [STORE_FLOATING_ACTIONS_BOTTOM_INSET] 取 24dp 刚好把整条胶囊放进圆内。
 *
 * 换成四个按钮，胶囊宽 184dp、半弦 92dp，要求 y ≥ 46dp，胶囊会浮到屏幕中下部，
 * 挡住一整张表盘卡。所以铃铛不在这里单列，而是进溢出菜单（未读红点改画在三点上）。
 */
@Composable
private fun StoreFloatingActions(
    onBack: () -> Unit,
    viewMode: WatchStoreViewMode,
    onToggleViewMode: () -> Unit,
    onUpload: () -> Unit,
    onMyUploads: () -> Unit,
    onOpenCustomMarketUrl: () -> Unit,
    showManagement: Boolean,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenNotifications: (() -> Unit)? = null,
    hasUnreadNotifications: Boolean = false,
    onMyFavorites: (() -> Unit)? = null,
    onMyCollections: (() -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null,
    onOpenCreators: () -> Unit = {},
    onOpenWebStore: () -> Unit = {},
    onOpenWebStoreExternal: () -> Unit = {},
) {
    val style = LauncherTheme.style
    Row(
        modifier = modifier
            .padding(bottom = STORE_FLOATING_ACTIONS_BOTTOM_INSET)
            .clip(CircleShape)
            // 厚玻璃：这条胶囊压着表盘卡片而不是压着根底，薄档会让图标和
            // 底下的封面糊在一起（见 GlassTier 的注释）。
            .background(style.glassThick.fill)
            .border(style.glassThick.borderWidth, style.glassThick.highlight, CircleShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                MiuixIcons.Back,
                contentDescription = stringResource(R.string.watch_editor_back),
                tint = style.titleColor,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onToggleViewMode, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (viewMode == WatchStoreViewMode.LIST) {
                    MiuixIcons.GridView
                } else {
                    MiuixIcons.ListView
                },
                contentDescription = stringResource(
                    if (viewMode == WatchStoreViewMode.LIST) {
                        R.string.watch_store_switch_grid
                    } else {
                        R.string.watch_store_switch_list
                    },
                ),
                tint = style.titleColor,
                modifier = Modifier.size(20.dp),
            )
        }
        StoreOverflowMenu(
            showManagement = showManagement,
            isLoggedIn = isLoggedIn,
            onLogin = onLogin,
            onUpload = onUpload,
            onMyUploads = onMyUploads,
            onOpenCustomMarketUrl = onOpenCustomMarketUrl,
            onMyFavorites = onMyFavorites,
            onMyCollections = onMyCollections,
            onOpenProfile = onOpenProfile,
            onOpenCreators = onOpenCreators,
            onOpenWebStore = onOpenWebStore,
            onOpenWebStoreExternal = onOpenWebStoreExternal,
            // 圆屏下铃铛没有独立槽位，通知改成菜单里的一行，未读红点画在三点上。
            onOpenNotifications = onOpenNotifications,
            hasUnreadNotifications = hasUnreadNotifications,
        )
    }
}

/**
 * 顶栏最右的 miuix 三点溢出菜单。
 *
 * 用 miuix 真组件 [WindowListPopup]（而不是 `ListPopup`）：后者要靠 miuix `Scaffold`
 * 提供 `MiuixPopupUtils` 宿主，本项目明确不引 miuix 的布局骨架；`WindowListPopup`
 * 自己起平台窗口，不需要宿主。
 *
 * 行用 [BasicComponent] 而不是 `DropdownImpl` —— 后者只收纯文本，挂不了图标。
 * 也不用 miuix 的 `ListPopupColumn`：它把列宽硬夹在 200..288dp，窄圆屏上会溢出；
 * 这里自己出一列，宽度跟着屏宽走。
 */
@Composable
private fun StoreOverflowMenu(
    showManagement: Boolean,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onUpload: () -> Unit,
    onMyUploads: () -> Unit,
    onOpenCustomMarketUrl: () -> Unit,
    onMyFavorites: (() -> Unit)?,
    onMyCollections: (() -> Unit)?,
    onOpenProfile: (() -> Unit)?,
    onOpenCreators: () -> Unit,
    onOpenWebStore: () -> Unit,
    onOpenWebStoreExternal: () -> Unit,
    /**
     * 通知中心入口。仅圆屏（底部悬浮胶囊）下非空 —— 那一档没有铃铛的独立槽位，
     * 通知降级成菜单里的一行。方屏顶栏仍有铃铛，这里传 null 免得出现两个入口。
     */
    onOpenNotifications: (() -> Unit)? = null,
    hasUnreadNotifications: Boolean = false,
) {
    val style = LauncherTheme.style
    var expanded by remember { mutableStateOf(false) }
    // 弹层宽度按**屏宽比例**定，而不是跟随最宽那一行。
    //
    // 试过内在测量（`IntrinsicSize.Max`）：宽度确实贴着最长标签，但整块就显小了 ——
    // 老师报「又太小了，改回上面那一版，只要把宽度稍微收窄一点」。所以回到定宽，
    // 比例从 0.66 收到 0.60、上下限从 148..220 收到 140..204。
    //
    // **屏宽要先换算回「布局 dp」**：外层 ProvideGlobalUiScale 把 density 乘过 scale，而
    // `screenWidthDp` 始终是系统原始 dp。直接乘比例的话，全局 UI 大小拉到 150% 时
    // 这块会摊到几乎满屏。
    val uiScale = LocalGlobalUiScale.current.coerceAtLeast(0.01f)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp / uiScale
    val popupWidth = (screenWidth * STORE_OVERFLOW_WIDTH_FRACTION)
        .coerceIn(STORE_OVERFLOW_MIN_WIDTH, STORE_OVERFLOW_MAX_WIDTH)
        // 极窄屏 + 大 UI 倍率下，下限本身就可能超过屏宽，留一点边距兜住。
        .coerceAtMost(screenWidth - 16.dp)
    // 弹层是独立平台窗口，density 在它的根节点被按窗口自己的 DisplayMetrics 重新提供，
    // 外层缩放过的那份到不了里面（与 ProvideDialogUiScale 那条注释同一个坑）。这里把外层
    // 这一份**原样**带进去：popup 若本来就继承了 density，重复提供同一个值是空操作，
    // 不会出现 scale 乘两次。
    val outerDensity = LocalDensity.current
    // WindowListPopup 内部放一个 Spacer 读「它父节点」的坐标当锚点，所以触发按钮
    // 与弹层必须共用这一个 Box —— 锚就是这 40dp 的按钮。
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Box {
                Icon(
                    imageVector = MiuixIcons.More,
                    contentDescription = stringResource(R.string.watch_store_more_options),
                    tint = style.titleColor,
                    modifier = Modifier.size(20.dp),
                )
                // 通知降级进菜单的那一档（圆屏），未读红点跟着搬到三点上，
                // 否则未读状态在整屏上没有任何提示。
                if (hasUnreadNotifications && onOpenNotifications != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(WatchColors.ActiveRed),
                    )
                }
            }
        }
        // MiuixTheme 要包在调用点外面：弹层容器自己的底色与遮罩
        // （ListPopupContent 的 surfaceContainer、ListPopupLayout 的 windowDimming）
        // 是在 WindowListPopup 内部读的，包在 content 里就来不及了。
        MiuixTheme(colors = rememberMiuixPopupColors()) {
            WindowListPopup(
                show = expanded,
                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                onDismissRequest = { expanded = false },
                minWidth = popupWidth,
            ) {
                CompositionLocalProvider(LocalDensity provides outerDensity) {
                    Column(modifier = Modifier.width(popupWidth)) {
                        // 通知排在最前：它是「有新东西」的入口，比管理类的项更急。
                        onOpenNotifications?.let { open ->
                            StoreOverflowItem(
                                icon = FlueMiuixIcons.Notification,
                                label = stringResource(R.string.watch_store_notifications),
                            ) {
                                expanded = false
                                open()
                            }
                        }
                        // 账号相关的那几行按登录态给（见 watchStoreAccountMenuActions）：
                        // 登录后是「我的上传 + 上传表盘」，未登录只给一行「登录 / 注册」。
                        //
                        // 市场没加载成功时管理类的两项不摆 —— 点进去只会再失败一次；
                        // 但「登录 / 注册」照摆，那是这种状态下唯一能自救的入口。
                        watchStoreAccountMenuActions(signedIn = isLoggedIn)
                            .filter {
                                it == WatchStoreAccountMenuAction.LOGIN_OR_REGISTER || showManagement
                            }
                            .forEach { action ->
                                when (action) {
                                    WatchStoreAccountMenuAction.LOGIN_OR_REGISTER -> StoreOverflowItem(
                                        icon = MiuixIcons.Contacts,
                                        label = stringResource(R.string.watch_store_login_or_register),
                                    ) {
                                        expanded = false
                                        onLogin()
                                    }
                                    WatchStoreAccountMenuAction.MY_UPLOADS -> StoreOverflowItem(
                                        icon = MiuixIcons.Folder,
                                        label = stringResource(R.string.watch_store_my_uploads),
                                    ) {
                                        expanded = false
                                        onMyUploads()
                                    }
                                    WatchStoreAccountMenuAction.UPLOAD -> StoreOverflowItem(
                                        icon = MiuixIcons.UploadCloud,
                                        label = stringResource(R.string.watch_store_upload_face),
                                    ) {
                                        expanded = false
                                        onUpload()
                                    }
                                }
                            }
                        // 收藏与合集是账号的东西：未登录时这两项直接不出现（与铃铛同一条
                        // 判断），点开只能看见「请先登录」的入口不如没有。
                        onMyFavorites?.let { open ->
                            StoreOverflowItem(
                                icon = MiuixIcons.Favorites,
                                label = stringResource(R.string.watch_store_my_favorites),
                            ) {
                                expanded = false
                                open()
                            }
                        }
                        onMyCollections?.let { open ->
                            StoreOverflowItem(
                                icon = MiuixIcons.Album,
                                label = stringResource(R.string.watch_store_my_collections),
                            ) {
                                expanded = false
                                open()
                            }
                        }
                        // H2：个人资料同样是账号的东西，未登录不出现。
                        onOpenProfile?.let { open ->
                            StoreOverflowItem(
                                icon = MiuixIcons.Contacts,
                                label = stringResource(R.string.watch_store_profile),
                            ) {
                                expanded = false
                                open()
                            }
                        }
                        StoreOverflowItem(
                            icon = MiuixIcons.Community,
                            label = stringResource(R.string.watch_store_top_creators),
                        ) {
                            expanded = false
                            onOpenCreators()
                        }
                        StoreOverflowItem(
                            icon = MiuixIcons.Store,
                            label = stringResource(R.string.watch_store_web_open_store),
                        ) {
                            expanded = false
                            onOpenWebStore()
                        }
                        StoreOverflowItem(
                            icon = MiuixIcons.Link,
                            label = stringResource(R.string.watch_store_web_open_external),
                        ) {
                            expanded = false
                            onOpenWebStoreExternal()
                        }
                        StoreOverflowItem(
                            icon = MiuixIcons.Tune,
                            label = stringResource(R.string.watch_store_custom_market_url),
                        ) {
                            expanded = false
                            onOpenCustomMarketUrl()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 溢出菜单的一行。
 *
 * **自绘而不是用 miuix 的 `BasicComponent`**：后者的默认排版是手机尺度（16dp 四边内边距 +
 * 16sp 标题），套在手表的小弹层里整体偏大（老师报「弹窗整体元素都偏大」）。自绘之后每个数
 * 都能单独调 —— 现在这一档是「比 miuix 默认收一点、比上一轮那次收得过头的 13.5sp 大回来」。
 *
 * 文字 `softWrap = false` + 单行省略：弹层是定宽的，长标签折行会把行高撑成两倍，
 * 一列里高矮不齐比省略号难看。
 */
@Composable
private fun StoreOverflowItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 商店条目卡片的形状。
 *
 * 直接用 `style.cardShape` 时 MD3 档是 32dp —— Expressive 的大圆角本来是给整屏卡片用的，
 * 套在市场那种「缩略图 + 两行字」的小卡上就圆得发胖（老师报「md3 表盘市场卡片圆角太大」）。
 * 这里按 [STORE_CARD_MAX_RADIUS] 收一道，与 J1 给胶囊设半径上限是同一条思路：
 * **形状语言按元素尺度分档，而不是全局一个值**。另两档（26dp / 18dp）本来就没超，取值不变。
 */
private fun storeCardShape(style: LauncherStyle): Shape =
    if (style.uiStyle == UiStyle.MATERIAL_3) {
        RoundedCornerShape(STORE_CARD_MAX_RADIUS)
    } else {
        style.cardShape
    }

private val STORE_CARD_MAX_RADIUS = 20.dp

/**
 * 圆屏上长表单的上下留白。
 *
 * 取一个固定值而不是「半屏」：表单里全是输入框，键盘一弹半屏留白会把内容推到看不见
 * 的地方。这个量刚好让首尾那一项能拉进弧线宽的区域，不追求正对圆心。
 */
private val STORE_FORM_ROUND_INSET = 40.dp

/** 详情页里直接展示的评论条数，其余进「查看全部评论」二级页。 */
private const val DETAIL_COMMENT_PREVIEW_COUNT = 1

/** 弹层宽度占屏宽的比例。上一版是 0.66，老师说「稍微收窄一点」。 */
private const val STORE_OVERFLOW_WIDTH_FRACTION = 0.60f

/** 弹层宽度下限。比这更窄的话，两三个字的标签会让弹层看着像个气泡。 */
private val STORE_OVERFLOW_MIN_WIDTH = 140.dp

/** 弹层宽度上限。方屏上按比例算会很宽，压住它才不会盖掉半个页面。 */
private val STORE_OVERFLOW_MAX_WIDTH = 204.dp

/**
 * 弹层的 miuix 配色。MaterialTheme 才是真正的明暗权威（见遮罩圆角弹窗那条注释），
 * 所以从它的表面亮度反推，而不是另读一遍系统深色开关——那会忽略应用内的主题覆盖。
 */
@Composable
private fun rememberMiuixPopupColors(): MiuixColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) { if (dark) miuixDarkColorScheme() else miuixLightColorScheme() }
}

@Composable
private fun FilterBar(
    sort: WatchStoreSort,
    onSortChange: (WatchStoreSort) -> Unit,
    screenType: WatchStoreScreenType?,
    onScreenTypeChange: (WatchStoreScreenType?) -> Unit,
    selectedCategory: WatchStoreCategory?,
    categories: List<WatchStoreCategory>,
    categoriesError: String?,
    categoryMenuExpanded: Boolean,
    onCategoryMenuExpandedChange: (Boolean) -> Unit,
    onCategoryChange: (WatchStoreCategory?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StorePill(stringResource(R.string.watch_store_latest), sort == WatchStoreSort.LATEST) {
            onSortChange(WatchStoreSort.LATEST)
        }
        StorePill(stringResource(R.string.watch_store_popular), sort == WatchStoreSort.POPULAR) {
            onSortChange(WatchStoreSort.POPULAR)
        }
        // 上游的 trending 来自日活聚合；聚合表空了它自己退回 latest，客户端不用兜底。
        StorePill(stringResource(R.string.watch_store_trending), sort == WatchStoreSort.TRENDING) {
            onSortChange(WatchStoreSort.TRENDING)
        }
        StorePill(stringResource(R.string.watch_store_all), screenType == null) { onScreenTypeChange(null) }
        StorePill(
            stringResource(R.string.watch_store_filter_round),
            screenType == WatchStoreScreenType.ROUND,
        ) { onScreenTypeChange(WatchStoreScreenType.ROUND) }
        StorePill(
            stringResource(R.string.watch_store_filter_square),
            screenType == WatchStoreScreenType.SQUARE,
        ) { onScreenTypeChange(WatchStoreScreenType.SQUARE) }
        StorePill(
            stringResource(R.string.watch_store_filter_rectangular),
            screenType == WatchStoreScreenType.RECTANGULAR,
        ) {
            onScreenTypeChange(WatchStoreScreenType.RECTANGULAR)
        }
        Box {
            StorePill(
                selectedCategory?.name ?: stringResource(R.string.watch_store_category),
                selectedCategory != null,
            ) {
                onCategoryMenuExpandedChange(true)
            }
            DropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { onCategoryMenuExpandedChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watch_store_all_categories)) },
                    onClick = { onCategoryChange(null) },
                )
                categories.take(40).forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onCategoryChange(category) },
                    )
                }
                if (categoriesError != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.watch_store_category_load_failed),
                                color = WatchColors.ActiveRed,
                            )
                        },
                        onClick = { onCategoryMenuExpandedChange(false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StorePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val style = LauncherTheme.style
    val shape = style.chipShape
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource, pressedScale = 0.94f)
    Text(
        text = label,
        modifier = Modifier
            .fluePressScale(pressScale)
            .clip(shape)
            .background(if (selected) style.accentColor.copy(alpha = 0.22f) else style.cardColor)
            .border(
                1.dp,
                if (selected) style.accentColor.copy(alpha = 0.65f) else style.outlineColor,
                shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
        color = if (selected) style.accentColor else style.secondaryTextColor,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

/** Ripple-free chip-sized action; presses use the shared flue press scale. */
@Composable
private fun StoreMiniAction(
    label: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconSize: androidx.compose.ui.unit.Dp = 13.dp,
) {
    val style = LauncherTheme.style
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource, pressedScale = 0.92f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fluePressScale(pressScale)
            .clip(style.chipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = color,
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            label,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun InstallStateLine(
    downloadState: WatchStoreInstallState,
    compact: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    downloadCountLine: (@Composable () -> Unit)? = null,
) {
    // 状态区固定尺寸槽位：下载前/下载中/已安装/失败四态内容高度各不相同
    // （纯文字比带图标的迷你按钮矮几个 dp），任由内容决定高度会让宫格卡片
    // 在下载完成后向内塌陷。槽位高度取四态中的最大者，卡片尺寸严格恒定。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 20.dp else 22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 「这一态能不能并排放下下载量」由 watchStoreInstallStateShowsDownloadCount 一处说了算。
        // 以前是各分支自己决定，于是「已安装」那一支干脆没碰过下载量 —— 正是老师报的
        // 「已安装的表盘不会显示下载量」。
        val showDownloadCount = watchStoreInstallStateShowsDownloadCount(downloadState)
        InstallStateLineContent(
            downloadState = downloadState,
            compact = compact,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            downloadCountLine = if (showDownloadCount) downloadCountLine else null,
        )
    }
}

@Composable
private fun InstallStateLineContent(
    downloadState: WatchStoreInstallState,
    compact: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    downloadCountLine: (@Composable () -> Unit)?,
) {
    val style = LauncherTheme.style
    when (downloadState) {
        is WatchStoreInstallState.Downloading,
        is WatchStoreInstallState.Validating,
        is WatchStoreInstallState.Installing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 11/14dp 太小：MIUIX 档的转圈是个带较粗描边的细环，直径一压到十来 dp
                // 就只剩一个小点，跟旁边的文字完全不成比例（老师报的「加载圆圈太小」）。
                // 抬到 16/20dp，与 10sp 文字并排时视觉重量才对得上。
                FlueLoadingIndicator(
                    size = if (compact) 16.dp else 20.dp,
                    usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                )
                Spacer(Modifier.width(6.dp))
                val label = when (downloadState) {
                    is WatchStoreInstallState.Downloading -> {
                        val total = downloadState.totalBytes
                        if (total != null && total > 0L) {
                            stringResource(
                                R.string.watch_store_download_progress_fmt,
                                downloadState.bytes * 100 / total,
                            )
                        } else stringResource(R.string.watch_store_status_downloading)
                    }
                    is WatchStoreInstallState.Validating -> stringResource(R.string.watch_store_status_validating)
                    is WatchStoreInstallState.Installing -> stringResource(R.string.watch_store_status_installing)
                    else -> stringResource(R.string.watch_store_status_processing)
                }
                Text(
                    label,
                    color = style.accentColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                StoreMiniAction(
                    label = stringResource(R.string.watch_editor_cancel),
                    color = style.secondaryTextColor,
                    fontSize = if (compact) 9.sp else 10.sp,
                    onClick = onCancelDownload,
                )
            }
        }
        is WatchStoreInstallState.Installed -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.watch_store_installed),
                color = style.positiveColor,
                fontSize = 10.sp,
                maxLines = 1,
            )
            if (downloadCountLine != null) {
                Spacer(Modifier.width(6.dp))
                // 下载量靠右：与「未安装」那一支（下载量在左、按钮在右）保持同一个重心，
                // 一列卡片扫下来数字始终贴着同一侧。
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    downloadCountLine()
                }
            }
        }
        is WatchStoreInstallState.Failed -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    downloadState.message,
                    color = WatchColors.ActiveRed,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StoreMiniAction(
                    label = stringResource(R.string.watch_store_retry),
                    color = style.accentColor,
                    fontSize = 10.sp,
                    onClick = onDownload,
                    icon = MiuixIcons.Refresh,
                    iconSize = if (compact) 11.dp else 13.dp,
                )
            }
        }
        WatchStoreInstallState.NotInstalled -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloadCountLine != null) {
                    Box(Modifier.weight(1f)) { downloadCountLine() }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                StoreMiniAction(
                    label = stringResource(R.string.watch_store_download),
                    color = style.accentColor,
                    fontSize = if (compact) 10.sp else 11.sp,
                    onClick = onDownload,
                    icon = MiuixIcons.Download,
                    iconSize = if (compact) 12.dp else 14.dp,
                )
            }
        }
    }
}

@Composable
private fun WatchFaceListCard(
    repository: WatchStoreRepository,
    item: WatchStoreWatchFace,
    downloadState: WatchStoreInstallState,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    val displayName = (downloadState as? WatchStoreInstallState.Installed)
        ?.record
        ?.resolvedDisplayName(item.name)
        ?: item.name
    // 缩略图一律方形：上游封面本来就是 512×512（见 API_USER.md 的 preview 元数据），
    // 以前按 screenType 给长条屏算 0.72 的竖长方形，同一列表里行高忽高忽低。
    // 宫格卡（WatchFaceGridCard）从来就是 1f，两处保持一致。
    val previewShape = if (item.screenType == WatchStoreScreenType.ROUND) style.chipShape else style.rowShape
    val cardShape = storeCardShape(style)
    val interactionSource = remember(item.id) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clip(cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
            )
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WatchPreview(
            repository = repository,
            id = item.id,
            modifier = Modifier
                .width(76.dp)
                .aspectRatio(1f)
                .clip(previewShape),
            // targetSize 是解码目标像素：76dp 在手表 DPI 下最多约 275px，256 够用。
            // 抬到 512 只会让每张封面的位图内存翻四倍，方形化本身不需要它。
            targetSize = 256,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                displayName,
                color = style.titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.category.name} · ${stringResource(screenTypeLabelRes(item.screenType))}",
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.wmProtected) {
                WatchStoreProtectedBadge()
            }
            InstallStateLine(
                downloadState = downloadState,
                compact = false,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                downloadCountLine = {
                    Text(
                        stringResource(R.string.watch_store_download_count_fmt, item.downloadCount),
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/** 宫格卡：预览图占满卡宽，文字压缩为单行。 */
@Composable
private fun WatchFaceGridCard(
    repository: WatchStoreRepository,
    item: WatchStoreWatchFace,
    downloadState: WatchStoreInstallState,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    val cardShape = storeCardShape(style)
    val displayName = (downloadState as? WatchStoreInstallState.Installed)
        ?.record
        ?.resolvedDisplayName(item.name)
        ?: item.name
    val interactionSource = remember(item.id) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clip(cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
            ),
    ) {
        WatchPreview(
            repository = repository,
            id = item.id,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            targetSize = 256,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                displayName,
                color = style.titleColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.wmProtected) {
                WatchStoreProtectedBadge()
            }
            InstallStateLine(
                downloadState = downloadState,
                compact = true,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                downloadCountLine = {
                    Text(
                        stringResource(R.string.watch_store_download_count_fmt, item.downloadCount),
                        color = style.secondaryTextColor,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun WatchFaceDetailPage(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    remoteId: String,
    detail: WatchStoreWatchFaceDetail?,
    summary: WatchStoreWatchFace?,
    localDescriptor: WatchFaceDescriptor?,
    loading: Boolean,
    error: String?,
    downloadState: WatchStoreInstallState,
    actionError: String?,
    onBack: () -> Unit,
    onRetryDetail: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onUse: (WatchFaceRecord) -> Unit,
    onEdit: (WatchFaceRecord) -> Unit,
    onRename: (WatchFaceRecord, String) -> Unit,
    onDelete: (WatchFaceRecord) -> Unit,
    onExport: (WatchFaceRecord) -> Unit,
    /** H4：底部「在网页版查看」。为 null 时不渲染（市场地址算不出网页地址时）。 */
    onOpenWeb: (() -> Unit)? = null,
    /** 同一个地址交给系统浏览器。内置 WebView 打不开站点弹窗时的逃生门。 */
    onOpenWebExternal: (() -> Unit)? = null,
    sessionToken: String? = null,
    /** 自己的 `user.id`。评论的归属判断只认它，见 [isOwnWatchStoreComment]。 */
    sessionAuthorId: String? = null,
    featureFlags: WatchStoreFeatureFlags = WatchStoreFeatureFlags(),
    onOpenCreator: (String) -> Unit = {},
    /** 未登录时点收藏 / 评分 / 举报，弹提示之后由它把人带到登录表单。 */
    onRequestLogin: () -> Unit = {},
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val isScreenRound = isFlueScreenRound()
    val installedRecord = (downloadState as? WatchStoreInstallState.Installed)?.record
    val fallbackName = detail?.name ?: summary?.name ?: localDescriptor?.displayName
    val displayName = installedRecord?.resolvedDisplayName(fallbackName)
        ?: fallbackName
        ?: stringResource(R.string.watch_store_installed_face)
    var editingName by remember(installedRecord?.logicalId, detail?.id) { mutableStateOf(false) }
    var nameText by remember(installedRecord?.logicalId, displayName) { mutableStateOf(displayName) }
    var fullscreenPreview by remember(remoteId, localDescriptor?.id) { mutableStateOf<Bitmap?>(null) }
    // 市场条目的预览身份和比例从点击第一帧起固定。已安装条目以前会先走
    // LocalInstalledWatchPreview，详情接口返回后再换成远端 WatchPreview，造成
    // “小图 -> 加载 -> 正常大图”的三段跳变。
    val remotePreviewId = remoteId
    val remoteScreenType = remember(remoteId) {
        summary?.screenType ?: detail?.screenType ?: WatchStoreScreenType.UNKNOWN
    }
    val detailScrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            // 覆层只需要「可命中」就能挡住底下仍在组合树里的列表；这里
            // 绝不能消费事件——父层在 Main pass 里消费 MOVE 会让内层
            // verticalScroll 在跨过 touch-slop 前被取消，详情页因此滑不动。
            // 与编辑器上传覆层保持同一写法：只观察指针直到全部抬起。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(
                horizontal = if (isScreenRound) 16.dp else 10.dp,
                vertical = if (isScreenRound) 10.dp else 6.dp,
            ),
    ) {
        // Opaque background + explicit zIndex: the scroll column below can move
        // during the top overscroll bounce and must not draw over this header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(style.screenBackground),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    MiuixIcons.Back,
                    contentDescription = stringResource(R.string.watch_editor_back),
                    tint = style.titleColor,
                )
            }
            StoreOverlayTitleBlock(
                title = stringResource(R.string.watch_store_detail_title),
                subtitle = null,
            )
        }
        when {
            detail != null || summary != null || installedRecord != null -> {
                // clipToBounds keeps the elastic-overscroll translation inside
                // the scroll viewport instead of painting over the header row.
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                    // The preview must be sized against the *viewport*, not the
                    // width: width-derived heights (1:1 round, width/0.72
                    // rectangular) always exceeded the visible height on a
                    // watch, so the first screen of the page was nothing but
                    // the preview image, with the name/description/download
                    // button pushed below the fold and reading as "the image
                    // covers the text".
                    val previewMaxHeight = maxHeight * 0.62f
                    val previewMaxWidth = (
                        maxWidth - if (isScreenRound) 12.dp else 0.dp
                    ).coerceAtLeast(1.dp)
                    // 圆屏弦宽坐标系：详情页是 `Column + verticalScroll` 的长表单，
                    // 没有 item 概念，用不了 StoreRoundList，所以坐标在这里量一次、
                    // 逐块用 storeChordBlock 施加（老师报的「二级页面还是没适配圆屏」）。
                    val chordDensity = LocalDensity.current
                    val chordViewportWidth = if (isScreenRound) {
                        with(chordDensity) { maxWidth.toPx() }
                    } else {
                        0f
                    }
                    val chordViewportHeight = with(chordDensity) { maxHeight.toPx() }
                    val chordCenterY = chordViewportHeight / 2f
                    // 首尾要能拉到圆心。没有这段留白，第一张卡最多停在视口顶边 ——
                    // 那正是弧线最窄处（老师报的「拉不到屏幕中间」）。
                    val detailRoundPadding = remember(isScreenRound, chordViewportHeight, chordDensity) {
                        if (!isScreenRound || chordViewportHeight <= 0f) {
                            0.dp to 0.dp
                        } else {
                            val padding = roundScrollablePadding(
                                viewportHeight = chordViewportHeight,
                                contentTop = 0f,
                                itemHeight = with(chordDensity) { 72.dp.toPx() },
                            )
                            with(chordDensity) { padding.top.toDp() to padding.bottom.toDp() }
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .flueOverscroll(detailScrollState)
                            .verticalScroll(detailScrollState)
                            // 圆屏：全宽按钮和文字向内收，避开圆角裁切。
                            .padding(horizontal = if (isScreenRound) 6.dp else 0.dp)
                            .padding(
                                top = detailRoundPadding.first,
                                bottom = detailRoundPadding.second,
                            ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (remotePreviewId.isNotBlank()) {
                            // **一律方形**（H3：预览大图 512 方形）。以前长方屏的条目按 0.72
                            // 画，于是 jbwatch 那些标 rectangular 的表盘详情页是个长方形，
                            // 与列表里的方形缩略图对不上（老师报「还是长方形不是 512×512」）。
                            // 服务端的预览本身就是方形画布，按 0.72 拉只会把它压变形。
                            val previewRatio = 1f
                            val previewWidth = minOf(previewMaxWidth, previewMaxHeight * previewRatio)
                            val previewHeight = previewWidth / previewRatio
                            WatchPreview(
                                repository = repository,
                                id = remotePreviewId,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(previewWidth, previewHeight)
                                    .clip(
                                        if (remoteScreenType == WatchStoreScreenType.ROUND) {
                                            style.chipShape
                                        } else {
                                            style.cardShape
                                        },
                                    ),
                                // 全屏预览允许双击放大，详情头图缓存一份较清晰版本。
                                targetSize = 1024,
                                onOpen = { bitmap -> fullscreenPreview = bitmap },
                            )
                        } else {
                            val localPreviewSize = minOf(
                                previewMaxWidth,
                                previewMaxHeight.coerceIn(140.dp, 260.dp),
                            )
                            LocalInstalledWatchPreview(
                                descriptor = localDescriptor,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(localPreviewSize),
                                onOpen = { bitmap -> fullscreenPreview = bitmap },
                            )
                        }
                        // ── 标题卡 ──────────────────────────────────────────────
                        // 以前名字、meta、受保护徽标、简介是四条平铺的裸文本，靠 8dp
                        // 间距分隔，在 230dp 圆屏上读不出层次。收进一张卡之后「这些是
                        // 同一件事的说明」这层关系是看得出来的。
                        FlueCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .storeChordBlock(chordViewportWidth, chordCenterY),
                            border = null,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                // 名称占满左侧，浏览量贴到它右边、按基线沉到下沿的小灰字。
                                // 以前浏览量夹在下面数据卡的四宫格与动作行之间，那一行只有
                                // 它一条文字，看起来像漏排的边角料。
                                val headerViews = detail?.views ?: summary?.views ?: 0L
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Text(
                                        displayName,
                                        color = style.titleColor,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (headerViews > 0) {
                                        Text(
                                            stringResource(R.string.watch_store_views_fmt, headerViews),
                                            color = style.secondaryTextColor,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                                detail?.let { remoteDetail ->
                                    // 分类 / 屏形 / 受保护徽标 / 标签同一行接着排（老师要的
                                    // 「minimal 方屏后面接着」）。横向滚动而不是换行：230dp
                                    // 圆屏上标签一多就会把简介和下载按钮顶下去。
                                    val headerTags = remoteDetail.tags.ifEmpty { summary?.tags.orEmpty() }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StoreMetaChip(remoteDetail.category.name)
                                        StoreMetaChip(
                                            stringResource(screenTypeLabelRes(remoteDetail.screenType)),
                                        )
                                        if (remoteDetail.wmProtected) WatchStoreProtectedBadge()
                                        headerTags.forEach { tag ->
                                            StoreMetaChip("#$tag")
                                        }
                                    }
                                    if (remoteDetail.description.isNotBlank()) {
                                        // 简介默认只给 3 行 + 「展开」：作者写几百字时，
                                        // 以前会把下载按钮整个挤到屏幕外。
                                        var expanded by remember(remoteId) { mutableStateOf(false) }
                                        Text(
                                            remoteDetail.description,
                                            color = style.bodyColor.copy(alpha = 0.86f),
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.animateContentSize(),
                                        )
                                        if (remoteDetail.description.length > STORE_DESCRIPTION_FOLD_CHARS) {
                                            Text(
                                                stringResource(
                                                    if (expanded) {
                                                        R.string.watch_store_detail_collapse
                                                    } else {
                                                        R.string.watch_store_detail_expand
                                                    },
                                                ),
                                                color = style.accentColor,
                                                fontSize = 11.sp,
                                                modifier = Modifier
                                                    .clip(style.compactShape)
                                                    .clickable { expanded = !expanded }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 社交区里那几张卡也要按弦宽缩放。它在另一个函数里，坐标
                        // 靠 CompositionLocal 下发，不用把两个 Float 一路当参数传。
                        CompositionLocalProvider(
                            LocalChordViewportWidth provides chordViewportWidth,
                            LocalChordScreenCenterY provides chordCenterY,
                        ) {
                            WatchStoreSocialDetailSection(
                                repository = repository,
                                api = api,
                                remoteId = remoteId,
                                detail = detail,
                                summary = summary,
                                sessionToken = sessionToken,
                                sessionAuthorId = sessionAuthorId,
                                featureFlags = featureFlags,
                                onOpenCreator = onOpenCreator,
                                onRequestLogin = onRequestLogin,
                            )
                        }
                        if (error != null) {
                            Text(
                                stringResource(R.string.watch_store_online_detail_unavailable_fmt, error),
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
                            )
                            TextButton(onClick = onRetryDetail) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = style.accentColor,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.watch_store_retry_online_detail),
                                    color = style.accentColor,
                                )
                            }
                        }
                        // ── 主操作卡 ────────────────────────────────────────────
                        // 下载 / 使用 / 编辑 / 导出 / 重命名 / 删除，以及安装进度，
                        // 全部收进一张卡：它们是同一件事的不同分支，以前平铺在页面上时
                        // 与数据、标签、评论混在一起，分不出「哪些是我能对这块表盘做的」。
                        FlueCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .storeChordBlock(chordViewportWidth, chordCenterY),
                            border = null,
                        ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        when (downloadState) {
                            is WatchStoreInstallState.Downloading,
                            is WatchStoreInstallState.Validating,
                            is WatchStoreInstallState.Installing -> {
                                val label = when (downloadState) {
                                    is WatchStoreInstallState.Downloading -> {
                                        val total = downloadState.totalBytes
                                        if (total != null && total > 0L) {
                                            stringResource(
                                                R.string.watch_store_download_progress_fmt,
                                                downloadState.bytes * 100 / total,
                                            )
                                        } else stringResource(R.string.watch_store_status_downloading_ellipsis)
                                    }
                                    is WatchStoreInstallState.Validating ->
                                        stringResource(R.string.watch_store_status_validating_ellipsis)
                                    is WatchStoreInstallState.Installing ->
                                        stringResource(R.string.watch_store_status_installing_ellipsis)
                                    else -> stringResource(R.string.watch_store_status_processing_ellipsis)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FlueLoadingIndicator(
                                        size = 24.dp,
                                        usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(label, color = style.accentColor, fontSize = 12.sp)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = onCancelDownload) {
                                        Text(
                                            stringResource(R.string.watch_editor_cancel),
                                            color = style.secondaryTextColor,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                            is WatchStoreInstallState.Installed -> {
                                val record = downloadState.record
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = MiuixIcons.Ok,
                                        contentDescription = null,
                                        tint = style.positiveColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        stringResource(R.string.watch_store_installed),
                                        color = style.positiveColor,
                                        fontSize = 12.sp,
                                    )
                                }
                                // Global Script 会在 Flue 的受限 Lua 沙箱中运行。
                                // 只提示这一项：其余兼容性字段刻意不展示。
                                if (record.compatibilityReport?.hasGlobalScript == true) {
                                    Text(
                                        stringResource(R.string.watch_store_lua_script_warning),
                                        color = WatchColors.ActiveOrange,
                                        fontSize = 11.sp,
                                    )
                                }
                                 Text(
                                     stringResource(
                                         R.string.watch_store_name_fmt,
                                         record.resolvedDisplayName(fallbackName) ?: displayName,
                                     ),
                                     color = style.secondaryTextColor,
                                     fontSize = 11.sp,
                                 )
                                // 点击后立刻反馈「已应用」并置灰，避免用户反复点。
                                var appliedThisVisit by remember(record.logicalId) { mutableStateOf(false) }
                                StorePrimaryButton(
                                    label = stringResource(
                                        if (appliedThisVisit) {
                                            R.string.watch_store_applied
                                        } else {
                                            R.string.watch_store_use_face
                                        },
                                    ),
                                    enabled = !appliedThisVisit,
                                    onClick = {
                                        onUse(record)
                                        appliedThisVisit = true
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.watch_store_apply_success),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreSecondaryButton(
                                        label = stringResource(R.string.watch_store_edit),
                                        onClick = { onEdit(record) },
                                        icon = MiuixIcons.Edit,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StoreSecondaryButton(
                                        label = stringResource(R.string.watch_store_export),
                                        onClick = { onExport(record) },
                                        icon = MiuixIcons.Share,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // 「重命名 / 删除表盘」与上面三个按钮同一族。
                                // 以前这两个是裸 TextButton，夹在一堆按钮中间「看起来很割裂」
                                // （老师原话）—— 现在都是按钮，都带 miuix 图标。
                                if (editingName) {
                                    OutlinedTextField(
                                        value = nameText,
                                        onValueChange = { nameText = it.take(512) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = style.rowShape,
                                        label = {
                                            Text(
                                                stringResource(R.string.watch_store_custom_name),
                                                color = style.secondaryTextColor,
                                            )
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = style.accentColor,
                                            unfocusedBorderColor = style.outlineColor,
                                            cursorColor = style.accentColor,
                                            focusedTextColor = style.bodyColor,
                                            unfocusedTextColor = style.bodyColor,
                                        ),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StoreSecondaryButton(
                                            label = stringResource(R.string.watch_editor_cancel),
                                            onClick = { editingName = false },
                                            modifier = Modifier.weight(1f),
                                        )
                                        StoreSecondaryButton(
                                            label = stringResource(R.string.watch_editor_save),
                                            onClick = { onRename(record, nameText); editingName = false },
                                            icon = MiuixIcons.Ok,
                                            enabled = nameText.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StoreSecondaryButton(
                                            label = stringResource(R.string.watch_editor_rename),
                                            onClick = {
                                                nameText = record.resolvedDisplayName(fallbackName)
                                                    ?: displayName
                                                editingName = true
                                            },
                                            icon = MiuixIcons.Rename,
                                            modifier = Modifier.weight(1f),
                                        )
                                        StoreDangerButton(
                                            label = stringResource(R.string.watch_store_delete_installed),
                                            onClick = { onDelete(record) },
                                            icon = MiuixIcons.Delete,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                actionError?.let { Text(it, color = WatchColors.ActiveRed, fontSize = 11.sp) }
                            }
                            is WatchStoreInstallState.Failed -> {
                                Text(downloadState.message, color = WatchColors.ActiveRed, fontSize = 12.sp)
                                StorePrimaryButton(
                                    label = stringResource(R.string.watch_store_retry),
                                    onClick = onDownload,
                                )
                            }
                            WatchStoreInstallState.NotInstalled -> StorePrimaryButton(
                                label = stringResource(R.string.watch_store_download_and_install),
                                onClick = onDownload,
                                icon = MiuixIcons.Download,
                            )
                        }
                        }
                        }
                        if (onOpenWeb != null && onOpenWebExternal != null) {
                            Spacer(Modifier.height(6.dp))
                            StoreWebLinks(
                                label = stringResource(R.string.watch_store_web_view_on_site),
                                onOpenInApp = onOpenWeb,
                                onOpenExternal = onOpenWebExternal,
                                // 这两行以前没接弦缩放，于是圆屏上它们既不随弦宽收窄、
                                // 也不跟着页面其余卡片一起缩（老师报的「使用网页版打开
                                // 那些图标不会跟随圆屏缩放」）。
                                modifier = Modifier.storeChordBlock(chordViewportWidth, chordCenterY),
                            )
                        }
                        // 圆屏底部弦宽较窄，多留出滚动余量。
                        Spacer(Modifier.height(if (isScreenRound) 36.dp else 14.dp))
                    }
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorStateRow(message = error, onRetry = onRetryDetail)
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingStateRow(
                    stringResource(
                        if (loading) {
                            R.string.watch_store_status_loading_detail
                        } else {
                            R.string.watch_store_status_preparing_detail
                        },
                    ),
                )
            }
        }
    }
    fullscreenPreview?.let { bitmap ->
        FullscreenWatchPreview(
            bitmap = bitmap,
            onDismiss = { fullscreenPreview = null },
        )
    }
}

/**
 * 简介折叠阈值（字符数）。超过这个长度才出「展开 / 收起」。
 *
 * 三行以内的简介点「展开」什么都不会变，那个按钮只会让人怀疑自己点错了。
 */
private const val STORE_DESCRIPTION_FOLD_CHARS = 70

/**
 * 详情页 meta 胶囊（分类 / 屏形）。
 *
 * 以前这些信息是一整行 `分类 · 屏形 · 下载量` 的灰色小字。改成胶囊之后每一项有自己的
 * 边界，扫一眼就能分出「这是分类」「这是屏形」；下载量搬进下面的数据卡，那里本来就
 * 有一格专门给它，同一个数字不必出现两次。
 */
@Composable
private fun StoreMetaChip(label: String) {
    val style = LauncherTheme.style
    Text(
        text = label,
        color = style.secondaryTextColor,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(style.compactShape)
            .background(style.pressedCardColor)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun WatchStoreProtectedBadge() {    val style = LauncherTheme.style
    Text(
        text = stringResource(R.string.watch_store_protected),
        color = style.accentColor,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(style.compactShape)
            .background(style.accentColor.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun WatchStoreSocialDetailSection(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    remoteId: String,
    detail: WatchStoreWatchFaceDetail?,
    summary: WatchStoreWatchFace?,
    sessionToken: String?,
    sessionAuthorId: String?,
    featureFlags: WatchStoreFeatureFlags,
    onOpenCreator: (String) -> Unit,
    onRequestLogin: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()
    val visibility = remember(featureFlags, sessionToken != null) {
        watchStoreSocialDetailVisibility(featureFlags, sessionToken != null)
    }
    var comments by remember(remoteId) { mutableStateOf<List<WatchStoreComment>>(emptyList()) }
    // 头像是异步补进 StoreAuthorAvatars 的，那是个普通对象、不是 Compose 状态，
    // 所以补完之后要靠这个计数把评论行重组一遍，否则要等下一次别的状态变化才显示。
    var authorAvatarRevision by remember(remoteId) { mutableIntStateOf(0) }
    // 详情页只放一条评论，其余进「查看更多评论」的二级页（老师要的）。
    // 手表屏幕上一条长评论就占掉半屏，全列在详情里等于把下面的卡都挤出屏幕。
    var allCommentsVisible by remember(remoteId) { mutableStateOf(false) }
    var rating by remember(remoteId) { mutableStateOf<WatchStoreRatingSummary?>(null) }
    var favorite by remember(remoteId) { mutableStateOf(false) }
    var favoriteCount by remember(remoteId, detail?.favoriteCount, summary?.favoriteCount) {
        mutableStateOf(detail?.favoriteCount ?: summary?.favoriteCount ?: 0L)
    }
    var loading by remember(remoteId) { mutableStateOf(false) }
    var error by remember(remoteId) { mutableStateOf<String?>(null) }
    var draft by remember(remoteId) { mutableStateOf("") }
    var replyTo by remember(remoteId) { mutableStateOf<String?>(null) }
    var editingCommentId by remember(remoteId) { mutableStateOf<String?>(null) }
    var editingDraft by remember(remoteId) { mutableStateOf("") }
    var submitting by remember(remoteId) { mutableStateOf(false) }
    var reportVisible by remember(remoteId) { mutableStateOf(false) }
    var selectedReportReason by remember(remoteId) { mutableStateOf<WatchStoreReportReason?>(null) }
    var reportMessage by remember(remoteId) { mutableStateOf("") }
    var collectionPickerVisible by remember(remoteId) { mutableStateOf(false) }
    var collectionsLoading by remember(remoteId) { mutableStateOf(false) }
    var collections by remember(remoteId) { mutableStateOf<List<WatchStoreCollection>>(emptyList()) }
    var collectionNameVisible by remember(remoteId) { mutableStateOf(false) }
    var collectionName by remember(remoteId) { mutableStateOf("") }
    var collectionBusy by remember(remoteId) { mutableStateOf(false) }
    /**
     * 「登录后才能使用这个功能」提示。
     *
     * 未登录时收藏 / 加入合集 / 举报 / 评分**照样显示**（见 watchStoreSocialDetailVisibility），
     * 点下去走这里 —— 既不静默吞掉点击，也不发一个必然 401 的请求。
     */
    var loginPromptVisible by remember(remoteId) { mutableStateOf(false) }

    /** 已登录返回 true；未登录时弹「去登录」并返回 false，调用方直接停手。 */
    fun ensureSignedIn(): Boolean {
        if (sessionToken != null) return true
        loginPromptVisible = true
        return false
    }

    BackHandler(enabled = replyTo != null) {
        replyTo = null
        draft = ""
    }

    LaunchedEffect(remoteId, api, sessionToken, featureFlags) {
        loading = featureFlags.socialEnabled
        error = null
        if (!featureFlags.socialEnabled) {
            loading = false
            return@LaunchedEffect
        }
        val failures = mutableListOf<Throwable>()
        withContext(Dispatchers.IO) {
            if (featureFlags.commentsEnabled) {
                runCatching { api.getComments(remoteId) }
                    .onSuccess { loaded ->
                        comments = loaded
                        // 头像另取一次：上游的评论接口只回 username，不回 avatar_url
                        // （见 StoreAuthorAvatars）。按人去重，一个人只打一次。
                        loaded.map { it.authorUsername }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .forEach { author ->
                                if (!StoreAuthorAvatars.isResolved(author)) {
                                    StoreAuthorAvatars.resolve(api, author)
                                }
                            }
                        authorAvatarRevision++
                    }
                    .onFailure(failures::add)
            }
            runCatching { api.getRating(remoteId) }
                .onSuccess { loaded ->
                    var withMine = loaded
                    if (sessionToken != null && featureFlags.reviewsEnabled) {
                        runCatching { api.getMyRating(sessionToken, remoteId) }
                            .onSuccess { mine -> withMine = loaded.copy(mine = mine) }
                    }
                    rating = withMine
                }
                .onFailure(failures::add)
            if (sessionToken != null) {
                runCatching { api.isFavorited(sessionToken, remoteId) }
                    .onSuccess { favorite = it }
                    .onFailure(failures::add)
            }
        }
        if (failures.isNotEmpty()) error = failures.first().userFacingMessage(context)
        loading = false
    }

    fun submitComment() {
        val token = sessionToken ?: return
        val content = draft.trim()
        if (!visibility.allowCommentInput || content.isEmpty() || submitting) return
        submitting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.postComment(token, remoteId, content, replyTo)
                }
            }.onSuccess { posted ->
                comments = comments + posted
                draft = ""
                replyTo = null
            }.onFailure { failure ->
                failure.throwIfCancellation()
                error = failure.userFacingMessage(context)
            }
            submitting = false
        }
    }

    fun beginEdit(comment: WatchStoreComment) {
        if (!visibility.showComments || submitting) return
        editingCommentId = comment.id
        editingDraft = comment.content
        replyTo = null
    }

    fun cancelEdit() {
        editingCommentId = null
        editingDraft = ""
    }

    fun saveEdit() {
        val token = sessionToken ?: return
        val commentId = editingCommentId ?: return
        val content = editingDraft.trim()
        if (content.isEmpty() || submitting) return
        submitting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.editMyComment(token, commentId, content) }
            }.onSuccess { updated ->
                // 只合 content —— PUT 的响应没有 user 也没有 parent_comment_id，整条替换会让
                // 这条回复当场跳到顶级、作者名变空（见 mergeEditedWatchStoreComment）。
                comments = comments.map {
                    if (it.id == updated.id) mergeEditedWatchStoreComment(it, updated) else it
                }
                cancelEdit()
            }.onFailure { failure ->
                failure.throwIfCancellation()
                error = failure.userFacingMessage(context)
            }
            submitting = false
        }
    }

    fun toggleFavorite() {
        if (!visibility.showFavorite || submitting) return
        if (!ensureSignedIn()) return
        val token = sessionToken ?: return
        val before = favorite
        val beforeCount = favoriteCount
        val targetFavorite = !before
        favorite = targetFavorite
        favoriteCount = favoriteCountAfterToggle(beforeCount, before, targetFavorite)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (targetFavorite) api.addFavorite(token, remoteId) else {
                        api.removeFavorite(token, remoteId)
                        false
                    }
                }
            }.onSuccess { serverState ->
                val actualFavorite = if (targetFavorite) serverState else false
                favorite = actualFavorite
                favoriteCount = favoriteCountAfterToggle(beforeCount, before, actualFavorite)
            }.onFailure { failure ->
                failure.throwIfCancellation()
                favorite = before
                favoriteCount = beforeCount
                error = failure.userFacingMessage(context)
            }
        }
    }

    fun setRating(value: Int) {
        if (!visibility.showRating || submitting) return
        if (!ensureSignedIn()) return
        val token = sessionToken ?: return
        // 乐观置数：submitting 期间所有星星都是禁用的，点完到回包这一小段在手表上肉眼可见，
        // 不先把星星点亮会让人以为这一下没点上（那正是 T-08 反馈的现场感受）。
        val before = rating
        rating = (before ?: WatchStoreRatingSummary(average = 0.0, count = 0L)).copy(mine = value)
        submitting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setRating(token, remoteId, value) }
            }.onSuccess {
                // 平均分与总数用服务端的新值，但 mine 必须显式带上 value：POST 的响应把
                // 「我这一票」放在 `rating` 字段里，而 ratingSummary 主要读的是 `mine`
                // （那是 GET /my/watchfaces/:id/rating 的字段名）。解析器虽然两个键都试了，
                // 也不该指望每个部署都回这个字段 —— 否则又变成「退出重进才看到评分」。
                rating = it.copy(mine = value)
            }.onFailure { failure ->
                failure.throwIfCancellation()
                rating = before
                error = failure.userFacingMessage(context)
            }
            submitting = false
        }
    }

    fun submitReport(reason: WatchStoreReportReason, message: String) {
        reportVisible = false
        selectedReportReason = null
        reportMessage = ""
        val token = sessionToken ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.reportWatchFace(token, remoteId, reason, message) }
            }
                .onSuccess {
                    Toast.makeText(context, R.string.watch_store_report_sent, Toast.LENGTH_SHORT).show()
                }
                .onFailure { failure ->
                    failure.throwIfCancellation()
                    error = failure.userFacingMessage(context)
                }
        }
    }

    fun loadCollections() {
        if (!ensureSignedIn()) return
        val token = sessionToken ?: return
        collectionPickerVisible = true
        collectionsLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.listMyCollections(token) } }
                .onSuccess { collections = it }
                .onFailure { failure -> error = failure.userFacingMessage(context) }
            collectionsLoading = false
        }
    }

    fun addToCollection(collection: WatchStoreCollection) {
        val token = sessionToken ?: return
        if (collectionBusy) return
        collectionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.addCollectionItem(token, collection.id, remoteId) }
            }.onSuccess {
                collectionPickerVisible = false
                Toast.makeText(context, R.string.watch_store_collection_added, Toast.LENGTH_SHORT).show()
            }.onFailure { failure -> error = failure.userFacingMessage(context) }
            collectionBusy = false
        }
    }

    fun createCollectionAndAdd() {
        val token = sessionToken ?: return
        val name = collectionName.trim()
        if (name.isEmpty() || collectionBusy) return
        collectionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.createCollection(token, name)
                    val loaded = api.listMyCollections(token)
                    val target = loaded.firstOrNull { it.name == name } ?: loaded.lastOrNull()
                    if (target != null) {
                        api.addCollectionItem(token, target.id, remoteId)
                    }
                    loaded to target
                }
            }.onSuccess { (loaded, target) ->
                collections = loaded
                collectionNameVisible = false
                collectionName = ""
                collectionPickerVisible = false
                if (target != null) {
                    Toast.makeText(context, R.string.watch_store_collection_added, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { failure -> error = failure.userFacingMessage(context) }
            collectionBusy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 创作者卡 ────────────────────────────────────────────────────────
        // 只放「这是谁 + 去他主页」。收藏 / 举报 / 加入合集三个动作以前挤在这一行
        // 右侧，于是「点这一行」到底是去主页还是收藏，看不出来 —— 那三个动作已经
        // 搬到下面的数据卡里。
        detail?.let { face ->
            FlueCard(
            modifier = Modifier.fillMaxWidth().storeChordBlock(),
            border = null,
        ) {
                WatchStoreCreatorIdentity(
                    repository = repository,
                    username = face.uploader.username,
                    avatarUrl = face.uploader.avatarUrl,
                    onClick = { onOpenCreator(face.uploader.username) },
                )
            }
        }

        val downloadCount = detail?.downloadCount ?: summary?.downloadCount ?: 0L
        val layers = detail?.layerCount ?: summary?.layerCount ?: 0
        val commentsCount = detail?.commentCount ?: summary?.commentCount ?: comments.size.toLong()
        val average = rating?.average ?: 0.0
        // ── 数据卡 ──────────────────────────────────────────────────────────
        // 四宫格 + 浏览量 + 标签 + 收藏/合集/举报三个动作。数据与「对这块表盘的
        // 态度」放在一起：收藏和评分本来就是同一类东西。
        FlueCard(
            modifier = Modifier.fillMaxWidth().storeChordBlock(),
            border = null,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WatchStoreStat(value = downloadCount.toString(), label = stringResource(R.string.watch_store_stat_downloads), modifier = Modifier.weight(1f))
                    WatchStoreStat(value = favoriteCount.toString(), label = stringResource(R.string.watch_store_stat_favorites), modifier = Modifier.weight(1f))
                    WatchStoreStat(value = String.format(java.util.Locale.ROOT, "%.1f", average), label = stringResource(R.string.watch_store_stat_rating), modifier = Modifier.weight(1f))
                    WatchStoreStat(value = layers.toString(), label = stringResource(R.string.watch_store_stat_layers), modifier = Modifier.weight(1f))
                }
                // 浏览量已经挪到标题卡里名称的右下角，这里不再重复。
                // 三个动作合成一行居中的纯图标：收藏 / 加入合集 / 举报。
                // 举报本来是卡片外单独一行小字，和这两个隔开之后完全看不出是同一类
                // 操作，而且带文字的按钮一行摆不下、圆屏上更挤（老师报的）。
                //
                // **未登录时这一行照样在**：能不能收藏是这块表盘的属性，不该由「我登录了没」
                // 决定看不看得见；点下去会弹「登录后才能使用这个功能」（见 ensureSignedIn）。
                if (visibility.showFavorite || visibility.showReport) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (visibility.showFavorite) {
                            StoreIconAction(
                                icon = if (favorite) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                                contentDescription = stringResource(
                                    if (favorite) {
                                        R.string.watch_store_favorited
                                    } else {
                                        R.string.watch_store_add_favorite
                                    },
                                ),
                                onClick = ::toggleFavorite,
                                active = favorite,
                                enabled = !submitting,
                            )
                            StoreIconAction(
                                icon = MiuixIcons.Album,
                                contentDescription = stringResource(R.string.watch_store_add_to_collection),
                                onClick = ::loadCollections,
                                enabled = !submitting,
                            )
                        }
                        if (visibility.showReport) {
                            StoreIconAction(
                                icon = MiuixIcons.Blocklist,
                                contentDescription = stringResource(R.string.watch_store_report),
                                onClick = {
                                    // 在**打开表单之前**就拦住：让人挑完理由、写完说明再说
                                    // 「请先登录」比一开始就说更糟。
                                    if (ensureSignedIn()) {
                                        selectedReportReason = null
                                        reportMessage = ""
                                        reportVisible = true
                                    }
                                },
                                enabled = !submitting,
                            )
                        }
                    }
                }
            }
        }

        // 标签已经接到标题卡的「分类 / 屏形」那一行后面，这里不再单独铺一排。

        // ── 评分卡 ──────────────────────────────────────────────────────────
        if (visibility.showRating) {
            FlueCard(
            modifier = Modifier.fillMaxWidth().storeChordBlock(),
            border = null,
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.watch_store_rating),
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    (1..5).forEach { value ->
                        val filled = value <= (rating?.mine ?: 0)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(style.compactShape)
                                .clickable(enabled = !submitting) { setRating(value) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (filled) "★" else "☆",
                                color = if (filled) style.accentColor else style.secondaryTextColor,
                                fontSize = 17.sp,
                            )
                        }
                    }
                }
            }
        }

        // ── 评论卡 ──────────────────────────────────────────────────────────
        // 结构：头部（「评论 (n)」+ 写评论按钮）→ 列表 → 输入框（默认收起）。
        // 以前输入框常驻在列表底下，手表上白占两行；而「写评论」这个动作在哪儿
        // 完全没有提示。
        if (visibility.showComments) {
            var composerOpen by remember(remoteId) { mutableStateOf(false) }
            val threads = remember(comments) { threadWatchStoreComments(comments) }
            // 一份线程渲染逻辑，详情卡与「全部评论」二级页共用 —— 复制两遍必然漂移出
            // 两套交互（回复框在一边能开、另一边不能）。
            val renderThread: @Composable (com.flue.launcher.ui.store.WatchStoreCommentThread) -> Unit =
                { thread ->
                    WatchStoreCommentThreadRow(
                        repository = repository,
                        thread = thread,
                        signedInAuthorId = sessionAuthorId,
                        avatarRevision = authorAvatarRevision,
                        onOpenCreator = onOpenCreator,
                        onReply = { replyTo = it; draft = "" },
                        replyTo = replyTo,
                        replyDraft = draft,
                        onReplyDraftChange = { draft = it.take(2_000) },
                        onSubmitReply = ::submitComment,
                        onCancelReply = { replyTo = null; draft = "" },
                        editingCommentId = editingCommentId,
                        editingDraft = editingDraft,
                        onEdit = ::beginEdit,
                        onEditChange = { editingDraft = it.take(2_000) },
                        onSaveEdit = ::saveEdit,
                        onCancelEdit = ::cancelEdit,
                        onDelete = { id ->
                            val token = sessionToken ?: return@WatchStoreCommentThreadRow
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { api.deleteMyComment(token, id) } }
                                    // 服务端是 ON DELETE CASCADE，整棵子树都没了；本地只砍一层
                                    // 会留下一堆孤儿回复冒充顶级评论。
                                    .onSuccess {
                                        comments = commentsAfterWatchStoreCommentDeletion(comments, id)
                                    }
                                    .onFailure { failure -> error = failure.userFacingMessage(context) }
                            }
                        },
                    )
                }
            FlueCard(
            modifier = Modifier.fillMaxWidth().storeChordBlock(),
            border = null,
        ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.watch_store_comments_count_fmt, commentsCount),
                            color = style.titleColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (visibility.allowCommentInput && replyTo == null) {
                            StoreSecondaryButton(
                                label = stringResource(R.string.watch_store_write_comment),
                                onClick = { composerOpen = !composerOpen },
                                icon = MiuixIcons.Messages,
                            )
                        }
                    }
                    when {
                        loading -> FlueLoadingIndicator(
                            size = 20.dp,
                            usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                        )
                        error != null -> Text(error!!, color = style.secondaryTextColor, fontSize = 11.sp)
                        comments.isEmpty() -> Text(stringResource(R.string.watch_store_no_comments), color = style.secondaryTextColor, fontSize = 11.sp)
                        else -> {
                            // 详情页只留第一条：一条长评论在 230dp 圆屏上就占掉半屏，
                            // 全列出来会把下面的卡整个挤出屏幕。
                            threads.take(DETAIL_COMMENT_PREVIEW_COUNT).forEach { renderThread(it) }
                            if (threads.size > DETAIL_COMMENT_PREVIEW_COUNT) {
                                StoreSecondaryButton(
                                    label = stringResource(
                                        R.string.watch_store_view_all_comments_fmt,
                                        threads.size,
                                    ),
                                    onClick = { allCommentsVisible = true },
                                    icon = MiuixIcons.ChevronForward,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = visibility.allowCommentInput && replyTo == null && composerOpen,
                        enter = flueExpandEnter(),
                        exit = flueShrinkExit(),
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(2_000) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            label = { Text(stringResource(R.string.watch_store_comment_hint)) },
                            shape = style.rowShape,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        submitComment()
                                        composerOpen = false
                                    },
                                    enabled = draft.isNotBlank() && !submitting,
                                ) {
                                    Icon(
                                        MiuixIcons.Ok,
                                        contentDescription = stringResource(R.string.watch_store_send),
                                        tint = style.accentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                    if (sessionToken == null) {
                        // 点这一句直接进登录表单。评论区本来就有这句提示，不必再多弹一个窗 ——
                        // 与三个动作图标弹的「去登录」走的是同一条路（onRequestLogin）。
                        Text(
                            stringResource(R.string.watch_store_sign_in_to_comment),
                            color = style.secondaryTextColor,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(style.compactShape)
                                .clickable(onClick = onRequestLogin)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // ── 全部评论（二级页）─────────────────────────────────────────────
            // 走全屏 Dialog 而不是在详情列里再展开：详情页本身是一条
            // `Column + verticalScroll`，在它里面塞一个可滚动长列表会两层滚动打架。
            // Dialog 有自己的 window，返回键天然只关这一层。
            if (allCommentsVisible) {
                Dialog(
                    onDismissRequest = { allCommentsVisible = false },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    // Dialog 是独立 Window，Compose 会按它自己的 DisplayMetrics 重新提供
                    // LocalDensity，外层 ProvideGlobalUiScale 那份缩放过的到不了这里 ——
                    // 所以评论二级页一直按系统原始 density 排版，「全局 UI 大小」对它无效。
                    ProvideDialogUiScale {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(style.screenBackground),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { allCommentsVisible = false }) {
                                    Icon(
                                        MiuixIcons.Back,
                                        contentDescription = stringResource(R.string.watch_editor_back),
                                        tint = style.titleColor,
                                    )
                                }
                                StoreOverlayTitleBlock(
                                    title = stringResource(
                                        R.string.watch_store_comments_count_fmt,
                                        commentsCount,
                                    ),
                                    subtitle = null,
                                )
                            }
                            // StoreRoundList：圆屏弦宽 + 回弹 + 首尾能拉到圆心，一处给全。
                            StoreRoundList(modifier = Modifier.weight(1f)) {
                                items(threads, key = { it.root.id }, contentType = { "comment_thread" }) { thread ->
                                    renderThread(thread)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 举报入口已并进上面数据卡里那一行图标，这里不再单独摆一行文字。
    }

    if (reportVisible) {
        FlueDialog(onDismissRequest = { reportVisible = false }) {
            Text(
                stringResource(R.string.watch_store_report),
                color = style.titleColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.watch_store_report_reason_label), color = style.secondaryTextColor, fontSize = 11.sp)
            WatchStoreReportReason.entries.forEach { reason ->
                TextButton(onClick = { selectedReportReason = reason }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            when (reason) {
                                WatchStoreReportReason.BROKEN -> R.string.watch_store_report_reason_broken
                                WatchStoreReportReason.COPYRIGHT -> R.string.watch_store_report_reason_copyright
                                WatchStoreReportReason.MALWARE -> R.string.watch_store_report_reason_malware
                                WatchStoreReportReason.WRONG_PREVIEW -> R.string.watch_store_report_reason_wrong_preview
                                WatchStoreReportReason.SPAM -> R.string.watch_store_report_reason_spam
                            },
                        ),
                        color = if (selectedReportReason == reason) style.accentColor else style.bodyColor,
                    )
                }
            }
            OutlinedTextField(
                value = reportMessage,
                onValueChange = { reportMessage = it.take(1_000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(stringResource(R.string.watch_store_report_message_hint)) },
                shape = style.rowShape,
            )
            TextButton(
                onClick = {
                    selectedReportReason?.let { submitReport(it, reportMessage) }
                },
                enabled = selectedReportReason != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.watch_store_send), color = style.accentColor)
            }
            TextButton(onClick = { reportVisible = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.watch_editor_cancel), color = style.secondaryTextColor)
            }
        }
    }

    if (collectionPickerVisible) {
        FlueDialog(onDismissRequest = { collectionPickerVisible = false }) {
            Text(
                stringResource(R.string.watch_store_choose_collection),
                color = style.titleColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            when {
                collectionsLoading -> FlueLoadingIndicator(size = 24.dp, usage = FlueLoadingIndicatorUsage.WATCH_STORE)
                collections.isEmpty() -> Text(
                    stringResource(R.string.watch_store_no_collections_prompt),
                    color = style.secondaryTextColor,
                    fontSize = 12.sp,
                )
                else -> collections.forEach { collection ->
                    TextButton(
                        onClick = { addToCollection(collection) },
                        enabled = !collectionBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(MiuixIcons.Album, contentDescription = null, tint = style.accentColor, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(collection.name, color = style.bodyColor, modifier = Modifier.weight(1f))
                            Text(collection.itemCount.toString(), color = style.secondaryTextColor, fontSize = 10.sp)
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    collectionPickerVisible = false
                    collectionNameVisible = true
                },
                enabled = !collectionBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(MiuixIcons.Album, contentDescription = null, tint = style.accentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.watch_store_create_collection_short), color = style.accentColor)
            }
            TextButton(onClick = { collectionPickerVisible = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.watch_editor_cancel), color = style.secondaryTextColor)
            }
        }
    }

    if (collectionNameVisible) {
        FlueAlertDialog(
            onDismissRequest = { collectionNameVisible = false },
            title = { Text(stringResource(R.string.watch_store_create_collection), color = style.titleColor) },
            text = {
                OutlinedTextField(
                    value = collectionName,
                    onValueChange = { collectionName = it.take(HttpWatchStoreApi.MAX_COLLECTION_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.watch_store_collection_name)) },
                )
            },
            confirmButton = {
                TextButton(onClick = ::createCollectionAndAdd, enabled = collectionName.trim().isNotEmpty() && !collectionBusy) {
                    Text(stringResource(R.string.watch_editor_confirm), color = style.accentColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { collectionNameVisible = false }) {
                    Text(stringResource(R.string.watch_editor_cancel), color = style.secondaryTextColor)
                }
            },
        )
    }

    // 未登录点收藏 / 加入合集 / 举报 / 评分：不静默、也不发必然 401 的请求，
    // 就在这里说清楚，并给一条直达登录表单的路。
    if (loginPromptVisible) {
        FlueAlertDialog(
            onDismissRequest = { loginPromptVisible = false },
            title = stringResource(R.string.watch_store_login_required),
            confirmText = stringResource(R.string.watch_store_login_or_register),
            onConfirm = {
                loginPromptVisible = false
                onRequestLogin()
            },
            dismissText = stringResource(R.string.watch_editor_cancel),
        )
    }
}

@Composable
private fun WatchStoreCreatorIdentity(
    repository: WatchStoreRepository,
    username: String,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.compactShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreAvatar(repository, avatarUrl, username, 32.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(R.string.watch_store_creator_label), color = style.secondaryTextColor, fontSize = 10.sp)
            Text(username, color = style.titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        // 明确的「可进入」提示：整行可点这件事以前只能靠猜。
        Icon(
            MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = style.secondaryTextColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun WatchStoreStat(value: String, label: String, modifier: Modifier = Modifier) {
    val style = LauncherTheme.style
    FlueSurface(
        modifier = modifier,
        shape = style.compactShape,
        color = style.pressedCardColor,
        // 四个数据格自带浅底，再加一圈描边就是老师报的「下载量那里还有一圈白色
        // 描边，很丑」。它们是卡片里的次级色块，不是独立卡片。
        border = null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = style.titleColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = style.secondaryTextColor, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WatchStoreCommentThreadRow(
    repository: WatchStoreRepository,
    thread: com.flue.launcher.ui.store.WatchStoreCommentThread,
    signedInAuthorId: String?,
    avatarRevision: Int,
    onOpenCreator: (String) -> Unit,
    onReply: (String) -> Unit,
    replyTo: String?,
    replyDraft: String,
    onReplyDraftChange: (String) -> Unit,
    onSubmitReply: () -> Unit,
    onCancelReply: () -> Unit,
    editingCommentId: String?,
    editingDraft: String,
    onEdit: (WatchStoreComment) -> Unit,
    onEditChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val style = LauncherTheme.style
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WatchStoreCommentRow(
            repository = repository,
            comment = thread.root,
            isThreadRoot = true,
            replyToUsername = null,
            signedInAuthorId = signedInAuthorId,
            avatarRevision = avatarRevision,
            onOpenCreator = onOpenCreator,
            onReply = onReply,
            editingCommentId = editingCommentId,
            editingDraft = editingDraft,
            onEdit = onEdit,
            onEditChange = onEditChange,
            onSaveEdit = onSaveEdit,
            onCancelEdit = onCancelEdit,
            onDelete = onDelete,
        )
        // 缩进只有一档（22dp）：thread.replies 已经把任意深度摊平了，层级靠每行的
        // 「@某人」标签表达（见 threadWatchStoreComments 的注释）。手表屏宽撑不住多档缩进。
        thread.replies.forEach { reply ->
            Row(Modifier.padding(start = 22.dp)) {
                WatchStoreCommentRow(
                    repository = repository,
                    comment = reply.comment,
                    isThreadRoot = false,
                    replyToUsername = reply.replyToUsername,
                    signedInAuthorId = signedInAuthorId,
                    avatarRevision = avatarRevision,
                    onOpenCreator = onOpenCreator,
                    onReply = onReply,
                    editingCommentId = editingCommentId,
                    editingDraft = editingDraft,
                    onEdit = onEdit,
                    onEditChange = onEditChange,
                    onSaveEdit = onSaveEdit,
                    onCancelEdit = onCancelEdit,
                    onDelete = onDelete,
                )
            }
        }
        AnimatedVisibility(
            visible = replyTo == thread.root.id,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            OutlinedTextField(
                value = replyDraft,
                onValueChange = onReplyDraftChange,
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                minLines = 2,
                label = { Text(stringResource(R.string.watch_store_reply_hint)) },
                shape = style.rowShape,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCancelReply, modifier = Modifier.size(32.dp)) {
                            Icon(
                                FlueMiuixIcons.Close,
                                contentDescription = stringResource(R.string.watch_store_cancel_reply),
                                tint = style.secondaryTextColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = onSubmitReply,
                            enabled = replyDraft.isNotBlank(),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                MiuixIcons.Ok,
                                contentDescription = stringResource(R.string.watch_store_send),
                                tint = style.accentColor,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun WatchStoreCommentRow(
    repository: WatchStoreRepository,
    comment: WatchStoreComment,
    /** 是不是这条 thread 的顶级评论。回复入口只挂在顶级上，见下面那段注释。 */
    isThreadRoot: Boolean,
    /** 这条在回复谁（父评论的作者名）。顶级评论与父评论已消失时给 null。 */
    replyToUsername: String?,
    signedInAuthorId: String?,
    avatarRevision: Int,
    onOpenCreator: (String) -> Unit,
    onReply: (String) -> Unit,
    editingCommentId: String?,
    editingDraft: String,
    onEdit: (WatchStoreComment) -> Unit,
    onEditChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val style = LauncherTheme.style
    FlueSurface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = style.compactShape,
        color = style.pressedCardColor,
        // 评论行不要默认描边：flueCardBorder() 在深色档的 outlineColor 近白，
        // 叠在 pressedCardColor 上就是老师说的那圈白框。外层评论卡已经关过了，
        // 只有这一行漏了。
        border = null,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像：上游的评论接口不回 avatar_url，所以走按用户名解析的缓存
            // （见 StoreAuthorAvatars）。读 avatarRevision 是为了让异步补齐后这一行
            // 能重组 —— 那个缓存是普通对象，不是 Compose 状态。
            val resolvedAvatar = remember(comment.authorUsername, avatarRevision) {
                comment.authorAvatarUrl?.takeIf { it.isNotBlank() }
                    ?: StoreAuthorAvatars.cached(comment.authorUsername)
            }
            StoreAvatar(
                repository = repository,
                avatarUrl = resolvedAvatar,
                name = comment.authorUsername,
                size = 24.dp,
                onClick = { onOpenCreator(comment.authorUsername) },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                comment.authorUsername,
                color = style.titleColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onOpenCreator(comment.authorUsername) },
            )
            Spacer(Modifier.weight(1f))
            if (isOwnWatchStoreComment(comment, signedInAuthorId)) {
                // 图标要对得上语义：以前「编辑」用的是**齿轮**（Settings）、
                // 「回复」用的是**加号**，读者只能靠猜。
                IconButton(onClick = { onEdit(comment) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.watch_store_edit_comment_a11y),
                        tint = style.accentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { onDelete(comment.id) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.watch_store_delete_comment_a11y),
                        tint = WatchColors.ActiveRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (replyToUsername != null) {
            // 「在回复谁」只能靠这个标签：任意深度都摊平到一档缩进之后，层级信息不在版式里了
            // （见 WatchStoreCommentReply）。用 @用户名 这种通用写法，不需要额外的文案。
            Text(
                "@$replyToUsername",
                color = style.accentColor,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editingCommentId == comment.id) {
            OutlinedTextField(
                value = editingDraft,
                onValueChange = onEditChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = style.rowShape,
                trailingIcon = {
                    Row {
                        TextButton(onClick = onCancelEdit) {
                            Text(stringResource(R.string.watch_editor_cancel), color = style.secondaryTextColor, fontSize = 10.sp)
                        }
                        TextButton(onClick = onSaveEdit, enabled = editingDraft.isNotBlank()) {
                            Text(stringResource(R.string.action_save), color = style.accentColor, fontSize = 10.sp)
                        }
                    }
                },
            )
        } else {
            Text(comment.content, color = style.bodyColor, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (isThreadRoot) {
            // 回复做成带文字的入口：一个光秃秃的图标按钮说明不了它是「回复」。
            //
            // 只有顶级评论摆这个入口：thread 里的回复框是挂在 thread.root.id 上的一个，
            // 回复的回复要挂在哪一行还没定（留给 T-38/T-39 的评论区改造）。判据用
            // isThreadRoot 而不是 parentCommentId == null —— parent 被隐藏的孤儿评论也是
            // 一条顶级 thread，它照样该能被回复。
            Text(
                stringResource(R.string.watch_store_reply_a11y),
                color = style.secondaryTextColor,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(style.compactShape)
                    .clickable { onReply(comment.id) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        }
    }
}

/**
 * 已上传表盘管理覆层。
 *
 * 从 GET /api/my/watchfaces 加载当前账号上传的全部表盘，支持编辑（PUT）和删除（DELETE）。
 * 未登录时可在当前覆层完成登录，不会绕到上传流程。
 */
private enum class MyUploadsContentState {
    LOGIN_PROMPT,
    LOGIN_FORM,
    LOADING,
    ERROR,
    EMPTY,
    LIST,
}

/**
 * 通知中心。
 *
 * 上游的通知正是「上传被拦」的唯一主动告知渠道（`WATCHFACE_REJECTED` /
 * `WATCHFACE_SCAN_FAILED`），所以这一页与「我的上传」的状态分组是同一件事的两面。
 *
 * 交互按上游接口的粒度来：点一条 → `POST /my/notifications/:id/read`；右上角 → `read-all`。
 * 本地先乐观置为已读再发请求，失败时回滚 —— 手表上一次往返肉眼可见，等服务端确认才变灰
 * 会让人以为点击没生效。
 */
@Composable
private fun NotificationCenterOverlay(
    api: HttpWatchStoreApi,
    token: String,
    onUnreadCountChange: (Int) -> Unit,
    onTokenExpired: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<WatchStoreNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var markingAll by remember { mutableStateOf(false) }

    fun publishUnread() {
        onUnreadCountChange(items.count { it.unread })
    }

    LaunchedEffect(token) {
        loading = true
        loadError = null
        runCatching { withContext(Dispatchers.IO) { api.listNotifications(token) } }
            .onSuccess { page ->
                items = page.data
                onUnreadCountChange(page.unread)
            }
            .onFailure { error ->
                error.throwIfCancellation()
                if (error is WatchStoreHttpException && error.statusCode == 401) {
                    onTokenExpired()
                    return@LaunchedEffect
                }
                loadError = watchStoreSocialErrorMessage(context, error)
            }
        loading = false
    }

    fun markOneRead(notification: WatchStoreNotification) {
        if (!notification.unread) return
        val previous = items
        // 乐观置灰：readAt 随便给个非 null 值即可，这一页只判空。
        items = items.map { current ->
            if (current.id == notification.id) current.copy(readAt = "local") else current
        }
        publishUnread()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.markNotificationRead(token, notification.id) }
            }.onFailure { error ->
                error.throwIfCancellation()
                items = previous
                publishUnread()
            }
        }
    }

    fun markAllRead() {
        if (markingAll || items.none { it.unread }) return
        markingAll = true
        val previous = items
        items = items.map { current -> if (current.unread) current.copy(readAt = "local") else current }
        onUnreadCountChange(0)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.markAllNotificationsRead(token) }
            }.onFailure { error ->
                error.throwIfCancellation()
                items = previous
                publishUnread()
            }
            markingAll = false
        }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.screenBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isFlueScreenRound()) 16.dp else 10.dp,
                    vertical = if (isFlueScreenRound()) 10.dp else 6.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.watch_editor_back),
                        tint = style.titleColor,
                    )
                }
                StoreOverlayTitleBlock(
                    title = stringResource(R.string.watch_store_notifications),
                    subtitle = null,
                    modifier = Modifier.weight(1f),
                )
                if (items.any { it.unread }) {
                    TextButton(
                        onClick = ::markAllRead,
                        enabled = !markingAll,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(R.string.watch_store_notifications_mark_all_read),
                            color = style.accentColor,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // 页级与行内加载都走统一的中性灰，尺寸由当前风格换算。
                        FlueLoadingIndicator(
                            size = 28.dp,
                            usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                        )
                    }

                    loadError != null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            loadError.orEmpty(),
                            color = style.bodyColor,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }

                    items.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.watch_store_notifications_empty),
                            color = style.secondaryTextColor,
                            fontSize = 14.sp,
                        )
                    }

                    // StoreRoundList：圆屏弦宽适配与避让动画（见 StoreRoundList.kt）。
                    else -> StoreRoundList {
                        items(
                            items = items,
                            key = { it.id },
                            contentType = { "notification_row" },
                        ) { notification ->
                            NotificationRow(
                                notification = notification,
                                onClick = { markOneRead(notification) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一条通知。未读用强调色圆点标记，已读整体降饱和。 */
@Composable
private fun NotificationRow(
    notification: WatchStoreNotification,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    val unread = notification.unread
    val accent = when (notification.type) {
        WatchStoreNotificationType.WATCHFACE_REJECTED,
        WatchStoreNotificationType.WATCHFACE_SCAN_FAILED,
        WatchStoreNotificationType.COMMENT_REMOVED,
        -> WatchColors.ActiveRed

        WatchStoreNotificationType.WATCHFACE_PUBLISHED -> style.accentColor
        else -> style.secondaryTextColor
    }
    FlueSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = unread, onClick = onClick),
        shape = style.rowShape,
        color = style.cardColor,
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp, end = 8.dp)
                .size(7.dp)
                .background(
                    if (unread) accent else Color.Transparent,
                    androidx.compose.foundation.shape.CircleShape,
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                notification.title.ifBlank { notification.type.apiValue },
                color = if (unread) style.bodyColor else style.secondaryTextColor,
                fontSize = 13.sp,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (notification.message.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    notification.message,
                    color = style.secondaryTextColor,
                    fontSize = 11.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (notification.createdAt.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    notification.createdAt,
                    color = style.secondaryTextColor.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        }
    }
}

@Composable
private fun MyUploadsOverlay(
    repository: WatchStoreRepository,
    installedFaces: List<WatchFaceDescriptor>,
    registrationEnabled: Boolean = true,
    /**
     * 直接落在登录表单上（而不是先显示「登录后查看我的上传」那一页）。
     *
     * 未登录时市场菜单里那一行叫「登录 / 注册」—— 点它再让人多点一次「登录」按钮是多余的。
     * 已登录时这个值无意义：`session != null` 的分支根本不看 `loginFormVisible`。
     */
    startInLoginForm: Boolean = false,
    onDismiss: () -> Unit,
    /** G5 / H1：底部「打开网页版」→ `/dashboard`（未登录时的登录表单底下也是这一条）。 */
    onOpenWeb: (() -> Unit)? = null,
    onOpenWebExternal: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()
    val api = remember { HttpWatchStoreApi() }
    val authStore = remember { WatchStoreAuthStore(context) }
    val credentialStore = remember { WatchStoreCredentialStore(context) }
    var session by remember { mutableStateOf(authStore.read()) }

    var entries by remember { mutableStateOf<List<MyWatchFaceEntry>>(emptyList()) }
    var previewGeneration by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(session != null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<MyWatchFaceEntry?>(null) }
    var lastEditTarget by remember { mutableStateOf<MyWatchFaceEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<MyWatchFaceEntry?>(null) }
    var deleteInProgress by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    // 覆层每次显示都是一次新的组合（AnimatedVisibility 关掉时内容离开组合树），
    // 所以这里的初值就是「本次进来要不要直接看到表单」。
    var loginFormVisible by remember { mutableStateOf(startInLoginForm) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberUsername by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var authAction by remember { mutableStateOf<WatchStoreAuthAction?>(null) }
    var authPage by remember { mutableStateOf(WatchStoreAuthPage.LOGIN) }
    var authError by remember { mutableStateOf<String?>(null) }
    val authBusy = authAction != null

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { credentialStore.read() }
        saved.username?.let { username = it }
        saved.password?.let { password = it }
        rememberUsername = saved.rememberUsername
        rememberPassword = saved.rememberPassword
    }

    LaunchedEffect(session?.token) {
        val token = session?.token ?: return@LaunchedEffect
        loading = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { api.listMyWatchFaces(token) }
        }.onSuccess { list ->
            entries = list
        }.onFailure { e ->
            e.throwIfCancellation()
            loadError = e.userFacingMessage(context)
        }
        loading = false
    }

    fun authenticate(register: Boolean) {
        if (authBusy) return
        val cleanUsername = username.trim()
        val inputError = watchStoreAuthInputError(context, cleanUsername, password)
        if (inputError != null) {
            authError = inputError
            return
        }
        authAction = if (register) WatchStoreAuthAction.REGISTER else WatchStoreAuthAction.LOGIN
        authError = null
        scope.launch {
            try {
                runCatching {
                    watchStoreLoginOrRegister(
                        api = api,
                        authStore = authStore,
                        credentialStore = credentialStore,
                        username = cleanUsername,
                        password = password,
                        register = register,
                        rememberUsername = rememberUsername,
                        rememberPassword = rememberPassword,
                    )
                }.onSuccess { stored ->
                    // Avoid a one-frame empty-state flash before the token effect
                    // begins loading the user's uploads.
                    loading = true
                    session = stored
                    password = ""
                    loginFormVisible = false
                    authPage = WatchStoreAuthPage.LOGIN
                }.onFailure { error ->
                    error.throwIfCancellation()
                    authError = watchStoreAuthErrorMessage(context, error, register)
                }
            } finally {
                authAction = null
            }
        }
    }

    BackHandler {
        if (editTarget != null) {
            editTarget = null
        } else if (loginFormVisible && session == null && authPage == WatchStoreAuthPage.REGISTER && !authBusy) {
            authPage = WatchStoreAuthPage.LOGIN
            authError = null
        } else if (loginFormVisible && session == null && !authBusy) {
            loginFormVisible = false
            authError = null
        } else {
            onDismiss()
        }
    }

    val editCovered = editTarget != null
    val contentHierarchyOffset = animateFloatAsState(
        targetValue = if (editCovered) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (editCovered) 240 else 260,
            easing = FastOutSlowInEasing,
        ),
        label = "my_uploads_hierarchy_offset",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            // Give the full-screen overlay a pointer hit target so taps in its
            // empty area cannot reach the market search field underneath.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(
                            x = (-placeable.width / 6f * contentHierarchyOffset.value).roundToInt(),
                            y = 0,
                        )
                    }
                }
                .padding(
                    horizontal = if (isFlueScreenRound()) 16.dp else 10.dp,
                    vertical = if (isFlueScreenRound()) 10.dp else 6.dp,
                ),
        ) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.watch_editor_back),
                        tint = style.titleColor,
                    )
                }
                Column(Modifier.weight(1f)) {
                    // 与首页 / 其它二级页共用紧行距标题，见 StoreOverlayTitle。
                    StoreOverlayTitle(
                        title = stringResource(R.string.watch_store_my_uploads),
                        subtitle = session?.username,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            if (onOpenWeb != null && onOpenWebExternal != null) {
                StoreWebLinks(
                    label = stringResource(R.string.watch_store_web_open),
                    onOpenInApp = onOpenWeb,
                    onOpenExternal = onOpenWebExternal,
                )
                Spacer(Modifier.height(8.dp))
            }

            val contentState = when {
                session == null && !loginFormVisible -> MyUploadsContentState.LOGIN_PROMPT
                session == null -> MyUploadsContentState.LOGIN_FORM
                loading -> MyUploadsContentState.LOADING
                loadError != null -> MyUploadsContentState.ERROR
                entries.isEmpty() -> MyUploadsContentState.EMPTY
                else -> MyUploadsContentState.LIST
            }
            // Keep the active list out of a full-viewport Crossfade layer. The
            // surrounding hierarchy slide already animates page navigation; a
            // persistent alpha layer here made every upload-list frame more costly.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (contentState) {
                    MyUploadsContentState.LOGIN_PROMPT -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 22.dp),
                        ) {
                            Icon(
                                MiuixIcons.Folder,
                                contentDescription = null,
                                tint = style.secondaryTextColor,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.watch_store_login_to_view_uploads),
                                color = style.secondaryTextColor,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            StorePrimaryButton(
                                label = stringResource(R.string.watch_store_login),
                                onClick = {
                                    authPage = WatchStoreAuthPage.LOGIN
                                    loginFormVisible = true
                                },
                            )
                        }
                    }

                    MyUploadsContentState.LOGIN_FORM -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AnimatedVisibility(
                            visible = authError != null,
                            enter = fadeIn(tween(160)),
                            exit = fadeOut(tween(120)),
                        ) {
                            Text(
                                authError.orEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .background(WatchColors.ActiveRed.copy(alpha = 0.16f), style.rowShape)
                                    .padding(10.dp),
                                color = WatchColors.ActiveRed,
                                fontSize = 12.sp,
                            )
                        }
                        WatchStoreLoginSection(
                            username = username,
                            onUsernameChange = { username = it },
                            password = password,
                            onPasswordChange = { password = it },
                            rememberUsername = rememberUsername,
                            rememberPassword = rememberPassword,
                            onRememberChange = { keepUsername, keepPassword ->
                                rememberUsername = keepUsername
                                rememberPassword = keepPassword
                            },
                            authPage = authPage,
                            onAuthPageChange = { page ->
                                authPage = page
                                authError = null
                            },
                            enabled = !authBusy,
                            registrationEnabled = registrationEnabled,
                            loadingAction = authAction,
                            onLogin = { authenticate(register = false) },
                            onRegister = { authenticate(register = true) },
                        )
                    }

                    MyUploadsContentState.LOADING -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlueLoadingIndicator(
                            size = 28.dp,
                            usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                        )
                    }

                    MyUploadsContentState.ERROR -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Icon(
                                MiuixIcons.Info,
                                contentDescription = null,
                                tint = style.accentColor,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                loadError.orEmpty(),
                                color = style.bodyColor,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    MyUploadsContentState.EMPTY -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.watch_store_no_uploads),
                            color = style.secondaryTextColor,
                            fontSize = 14.sp,
                        )
                    }

                    MyUploadsContentState.LIST -> {
                        val (attention, published) = remember(entries) {
                            myUploadStatusGroups(entries)
                        }
                        // StoreRoundList：圆屏弦宽适配与避让动画（见 StoreRoundList.kt）。
                        StoreRoundList {
                            // 只有真的有需要处理的才出小标题：全部正常时多两行标题是噪声。
                            if (attention.isNotEmpty()) {
                                item(key = "group_attention", contentType = "my_upload_group") {
                                    MyUploadGroupHeader(
                                        text = stringResource(R.string.watch_store_uploads_group_attention),
                                        emphasized = true,
                                    )
                                }
                            }
                            items(
                                items = attention,
                                key = { it.id },
                                contentType = { "my_upload_row" },
                            ) { entry ->
                                MyUploadEntryRow(
                                    entry = entry,
                                    repository = repository,
                                    previewGeneration = previewGeneration,
                                    onEditRequest = {
                                        lastEditTarget = it
                                        editTarget = it
                                    },
                                    onDeleteRequest = { deleteTarget = it },
                                )
                            }
                            if (attention.isNotEmpty() && published.isNotEmpty()) {
                                item(key = "group_published", contentType = "my_upload_group") {
                                    MyUploadGroupHeader(
                                        text = stringResource(R.string.watch_store_uploads_group_published),
                                        emphasized = false,
                                    )
                                }
                            }
                            items(
                                items = published,
                                key = { it.id },
                                contentType = { "my_upload_row" },
                            ) { entry ->
                                MyUploadEntryRow(
                                    entry = entry,
                                    repository = repository,
                                    previewGeneration = previewGeneration,
                                    onEditRequest = {
                                        lastEditTarget = it
                                        editTarget = it
                                    },
                                    onDeleteRequest = { deleteTarget = it },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = editTarget != null,
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough()
                .zIndex(5f),
            enter = STORE_LAYER_ENTER,
            exit = STORE_LAYER_EXIT,
        ) {
            val entry = editTarget ?: lastEditTarget
            if (entry != null) {
                MyUploadEditOverlay(
                    repository = repository,
                    installedFaces = installedFaces,
                    entry = entry,
                    token = session?.token.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                    onUpdated = { updated, replacedArchive ->
                        entries = entries.map { current ->
                            if (current.id == updated.id) updated else current
                        }
                        if (replacedArchive) previewGeneration++
                        editTarget = null
                    },
                    onTokenExpired = {
                        authStore.clear()
                        session = null
                        entries = emptyList()
                        editTarget = null
                    },
                    onDismiss = { editTarget = null },
                )
            }
        }
    }

    // 删除确认对话框
    val target = deleteTarget
    if (target != null) {
        FlueAlertDialog(
            onDismissRequest = { if (!deleteInProgress) { deleteTarget = null; deleteError = null } },
            title = {
                Text(stringResource(R.string.watch_store_delete_confirmation), color = style.titleColor)
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.watch_store_delete_upload_prompt_fmt, target.name),
                        color = style.bodyColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.watch_store_irreversible),
                        color = style.secondaryTextColor,
                        fontSize = 12.sp,
                    )
                    if (deleteError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(deleteError!!, color = style.accentColor, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteInProgress,
                    onClick = {
                        val token = session?.token ?: return@TextButton
                        scope.launch {
                            deleteInProgress = true
                            deleteError = null
                            runCatching {
                                withContext(Dispatchers.IO) { api.deleteMyWatchFace(token, target.id) }
                            }.onSuccess {
                                entries = entries.filterNot { it.id == target.id }
                                deleteTarget = null
                                deleteError = null
                            }.onFailure { e ->
                                e.throwIfCancellation()
                                deleteError = e.userFacingMessage(context)
                            }
                            deleteInProgress = false
                        }
                    },
                ) {
                    if (deleteInProgress) {
                        FlueLoadingIndicator(
                            size = 16.dp,
                            usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                        )
                    } else {
                        Text(stringResource(R.string.watch_editor_delete), color = style.accentColor)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteInProgress,
                    onClick = { deleteTarget = null; deleteError = null },
                ) {
                    Text(stringResource(R.string.watch_editor_cancel), color = style.secondaryTextColor)
                }
            },
        )
    }
}

/** 分组小标题。「需要处理」用强调色，「已上架」用次要色 —— 前者是要用户动手的。 */
@Composable
private fun MyUploadGroupHeader(text: String, emphasized: Boolean) {
    val style = LauncherTheme.style
    Text(
        text = text,
        color = if (emphasized) WatchColors.ActiveRed else style.secondaryTextColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** 审核状态徽标。颜色分三档：驳回红、待审强调色、其余次要色。 */
@Composable
private fun MyUploadStatusBadge(label: String, status: WatchStoreReviewStatus) {
    val style = LauncherTheme.style
    val tint = when (status) {
        WatchStoreReviewStatus.REJECTED, WatchStoreReviewStatus.UNKNOWN -> WatchColors.ActiveRed
        WatchStoreReviewStatus.PENDING -> style.accentColor
        else -> style.secondaryTextColor
    }
    Text(
        text = label,
        color = tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .background(tint.copy(alpha = 0.16f), style.rowShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MyUploadEntryRow(
    entry: MyWatchFaceEntry,
    repository: WatchStoreRepository,
    previewGeneration: Int,
    onEditRequest: (MyWatchFaceEntry) -> Unit,
    onDeleteRequest: (MyWatchFaceEntry) -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val screenType = stringResource(screenTypeLabelRes(entry.screenType))
    val previewSizePx = 56
    var bitmap by remember(entry.id, previewGeneration) {
        mutableStateOf(repository.peekPreview(entry.id, previewSizePx, previewSizePx)?.asImageBitmap())
    }
    var previewFailed by remember(entry.id, previewGeneration) { mutableStateOf(false) }
    LaunchedEffect(entry.id, previewGeneration) {
        if (bitmap != null) return@LaunchedEffect
        previewFailed = false
        runCatching { repository.loadPreview(entry.id, previewSizePx, previewSizePx) }
            .onSuccess { bitmap = it.asImageBitmap() }
            .onFailure { error ->
                error.throwIfCancellation()
                previewFailed = true
            }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.cardColor, style.rowShape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(previewSizePx.dp)
                .background(style.outlineColor.copy(alpha = 0.15f), style.rowShape)
                .clip(style.rowShape),
            contentAlignment = Alignment.Center,
        ) {
            val img = bitmap
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (previewFailed) {
                Icon(
                    MiuixIcons.Folder,
                    contentDescription = null,
                    tint = style.secondaryTextColor,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                FlueLoadingIndicator(
                    size = 20.dp,
                    usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    color = style.bodyColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val statusLabel = watchStoreReviewStatusLabel(context, entry.status)
                if (statusLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    MyUploadStatusBadge(label = statusLabel, status = entry.status)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(
                    R.string.watch_store_upload_entry_meta_fmt,
                    entry.category.name,
                    screenType,
                    entry.downloadCount,
                ),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 驳回原因是唯一能告诉用户「为什么表盘一直不出现」的东西，所以给足行数。
            val reason = entry.rejectionReason?.takeIf(String::isNotBlank)
            if (reason != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.watch_store_rejection_reason_fmt, reason),
                    color = WatchColors.ActiveRed,
                    fontSize = 11.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val hint = when (entry.status) {
                    WatchStoreReviewStatus.PENDING ->
                        stringResource(R.string.watch_store_status_pending_hint)
                    WatchStoreReviewStatus.DELISTED ->
                        stringResource(R.string.watch_store_status_delisted_hint)
                    else -> null
                }
                if (hint != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        hint,
                        color = style.secondaryTextColor,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TextButton(
            onClick = { onEditRequest(entry) },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Text(
                stringResource(R.string.watch_store_edit),
                color = style.accentColor,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = { onDeleteRequest(entry) }, modifier = Modifier.size(36.dp)) {
            Icon(
                MiuixIcons.Delete,
                contentDescription = stringResource(R.string.watch_editor_delete),
                tint = style.secondaryTextColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 编辑当前账号已经上传的表盘。服务端的 PUT 允许仅更新元数据；选择了新的
 * `.watch` 文件时，服务端会同时替换归档和从中重新生成的封面。
 */
@Composable
private fun MyUploadEditOverlay(
    repository: WatchStoreRepository,
    installedFaces: List<WatchFaceDescriptor>,
    entry: MyWatchFaceEntry,
    token: String,
    modifier: Modifier = Modifier,
    onUpdated: (MyWatchFaceEntry, replacedArchive: Boolean) -> Unit,
    onTokenExpired: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val style = LauncherTheme.style
    val isScreenRound = isFlueScreenRound()
    val api = remember { HttpWatchStoreApi() }
    var name by remember(entry.id) { mutableStateOf(entry.name) }
    var description by remember(entry.id) { mutableStateOf(entry.description) }
    var screenType by remember(entry.id) { mutableStateOf(entry.screenType) }
    var selectedCategoryId by remember(entry.id) { mutableStateOf(entry.category.id) }
    var categories by remember { mutableStateOf<List<WatchStoreCategory>?>(null) }
    var categoriesError by remember { mutableStateOf<String?>(null) }
    var categoriesRetryToken by remember { mutableIntStateOf(0) }
    var pickedFileUri by remember(entry.id) { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember(entry.id) { mutableStateOf<String?>(null) }
    var selectedDescriptorId by remember(entry.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var updatePercent by remember { mutableStateOf<Int?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = style.accentColor,
        unfocusedBorderColor = style.outlineColor,
        cursorColor = style.accentColor,
        focusedTextColor = style.bodyColor,
        unfocusedTextColor = style.bodyColor,
    )
    val installedWatchmakerFaces = remember(installedFaces) {
        installedFaces.filter { it.isWatchmaker && it.sourceDirPath != null }
    }
    val selectedDescriptor = selectedDescriptorId
        ?.let { id -> installedWatchmakerFaces.firstOrNull { it.id == id } }

    val pickReplacementFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedFileUri = uri
            selectedDescriptorId = null
            pickedFileName = queryStoreUploadDisplayName(context, uri)
                ?: context.getString(R.string.watch_store_local_face)
        }
    }

    LaunchedEffect(entry.id, categoriesRetryToken) {
        categoriesError = null
        runCatching { repository.getCategories() }
            .onSuccess { loaded ->
                categories = loaded
                if (loaded.none { it.id == selectedCategoryId }) {
                    selectedCategoryId = loaded.firstOrNull()?.id.orEmpty()
                }
            }
            .onFailure { error ->
                error.throwIfCancellation()
                categoriesError = watchStoreUploadErrorMessage(context, error)
            }
    }

    fun update() {
        if (saving) return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            formError = context.getString(R.string.watch_store_error_name_required)
            return
        }
        val trimmedDescription = description.trim()
        if (trimmedDescription.isEmpty()) {
            formError = context.getString(R.string.watch_store_error_description_required)
            return
        }
        val categoryId = selectedCategoryId.trim()
        if (categoryId.isEmpty()) {
            formError = context.getString(R.string.watch_store_error_category_required)
            return
        }
        if (token.isBlank()) {
            onTokenExpired()
            return
        }

        val replacementUri = pickedFileUri
        val replacementDescriptor = selectedDescriptor
        val selectedCategory = categories.orEmpty().firstOrNull { it.id == categoryId } ?: entry.category
        formError = null
        updatePercent = null
        saving = true
        scope.launch {
            try {
                val replacedArchive = withContext(Dispatchers.IO) {
                    var archive: File? = null
                    try {
                        if (replacementUri != null || replacementDescriptor != null) {
                            archive = File.createTempFile(
                                "flue_store_update_",
                                ".watch",
                                context.cacheDir,
                            )
                            if (replacementDescriptor != null) {
                                WatchEditorSession.open(context, replacementDescriptor).use { session ->
                                    session.exportWatch(archive, protectedExport = session.isProtected)
                                }
                            } else {
                                copyPickedWatchArchive(context, requireNotNull(replacementUri), archive)
                            }
                        }
                        api.updateMyWatchFace(
                            token = token,
                            id = entry.id,
                            name = trimmedName,
                            description = trimmedDescription,
                            screenType = screenType,
                            categoryId = categoryId,
                            replacementFile = archive,
                        ) { sent, total ->
                            val percent = ((sent * 100L) / total.coerceAtLeast(1L))
                                .toInt()
                                .coerceIn(0, 100)
                            if (updatePercent != percent) updatePercent = percent
                        }
                        archive != null
                    } finally {
                        archive?.delete()
                    }
                }
                if (replacedArchive) repository.invalidatePreview(entry.id)
                Toast.makeText(
                    context,
                    context.getString(R.string.watch_store_update_success),
                    Toast.LENGTH_SHORT,
                ).show()
                onUpdated(
                    entry.copy(
                        name = trimmedName,
                        description = trimmedDescription,
                        screenType = screenType,
                        category = selectedCategory,
                    ),
                    replacedArchive,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is WatchStoreHttpException && error.statusCode == 401) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.watch_store_error_auth_expired_update),
                        Toast.LENGTH_LONG,
                    ).show()
                    onTokenExpired()
                } else {
                    formError = watchStoreUploadErrorMessage(context, error)
                }
            } finally {
                saving = false
                updatePercent = null
            }
        }
    }

    // 保存中不允许返回，避免协程随组合一起取消而留下不确定的远端状态。
    BackHandler { if (!saving) onDismiss() }

    Column(
        modifier = modifier
            .background(style.screenBackground)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(
                horizontal = if (isScreenRound) 16.dp else 10.dp,
                vertical = if (isScreenRound) 10.dp else 6.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (!saving) onDismiss() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    MiuixIcons.Back,
                    contentDescription = stringResource(R.string.watch_editor_back),
                    tint = style.titleColor,
                )
            }
            Text(
                stringResource(R.string.watch_store_edit_upload),
                modifier = Modifier.weight(1f),
                color = style.titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
            )
            Spacer(Modifier.size(40.dp))
        }
        // 长表单：加橡皮筋回弹 + 圆屏首尾留白。这两页以前连回弹都没有，拉到底是硬停，
        // 圆屏上最后一个输入框也顶不到屏幕中间（老师报的）。
        // 表单**不做**弦宽缩放：把输入框逐个横向压扁只会更难点。
        val editScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .flueOverscroll(editScrollState)
                .verticalScroll(editScrollState)
                .padding(horizontal = if (isScreenRound) 6.dp else 0.dp)
                .padding(vertical = if (isScreenRound) STORE_FORM_ROUND_INSET else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saving) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlueLoadingIndicator(
                        size = 18.dp,
                        usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                    )
                    Text(
                        stringResource(R.string.watch_store_status_updating) +
                            (updatePercent?.let { " $it%" }.orEmpty()),
                        color = style.bodyColor,
                        fontSize = 12.sp,
                    )
                }
            }
            formError?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WatchColors.ActiveRed.copy(alpha = 0.16f), style.rowShape)
                        .padding(10.dp),
                    color = WatchColors.ActiveRed,
                    fontSize = 12.sp,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text(stringResource(R.string.watch_store_watch_name_required)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(2000) },
                label = { Text(stringResource(R.string.watch_store_description_required)) },
                minLines = 2,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
            )
            Text(
                stringResource(R.string.watch_store_replace_uploaded_file_optional),
                color = style.titleColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val replacementName = selectedDescriptor?.displayName ?: pickedFileName
            replacementName?.let { fileName ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.watch_store_replace_selected_file_fmt, fileName),
                        modifier = Modifier.weight(1f),
                        color = style.bodyColor,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            pickedFileUri = null
                            pickedFileName = null
                            selectedDescriptorId = null
                        },
                    ) {
                        Text(stringResource(R.string.watch_editor_cancel), color = style.accentColor)
                    }
                }
            } ?: Text(
                stringResource(R.string.watch_store_keep_uploaded_file),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
            )
            StoreSecondaryButton(
                label = stringResource(R.string.watch_store_select_replacement_file),
                onClick = { if (!saving) pickReplacementFile.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (installedWatchmakerFaces.isEmpty()) {
                Text(
                    stringResource(R.string.watch_store_no_uploadable_installed),
                    color = style.secondaryTextColor,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    stringResource(R.string.watch_store_or_select_installed),
                    color = style.secondaryTextColor,
                    fontSize = 11.sp,
                )
                installedWatchmakerFaces.forEach { descriptor ->
                    val selected = descriptor.id == selectedDescriptorId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(style.rowShape)
                            .background(if (selected) style.selectedCardColor else style.cardColor)
                            .border(1.dp, style.outlineColor, style.rowShape)
                            .clickable(enabled = !saving) {
                                selectedDescriptorId = descriptor.id
                                pickedFileUri = null
                                pickedFileName = null
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            descriptor.displayName,
                            modifier = Modifier.weight(1f),
                            color = style.bodyColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = null,
                                tint = style.accentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.watch_store_screen_shape),
                color = style.titleColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                STORE_UPLOAD_SCREEN_TYPE_OPTIONS.forEach { (candidate, labelRes) ->
                    StorePill(stringResource(labelRes), screenType == candidate) {
                        if (!saving) screenType = candidate
                    }
                }
            }
            Text(
                stringResource(R.string.watch_store_category),
                color = style.titleColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                categoriesError != null -> {
                    Text(
                        categoriesError.orEmpty(),
                        color = WatchColors.ActiveRed,
                        fontSize = 12.sp,
                    )
                    StoreSecondaryButton(
                        label = stringResource(R.string.watch_store_reload_categories),
                        onClick = { if (!saving) categoriesRetryToken++ },
                    )
                }
                categories == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlueLoadingIndicator(
                        size = 16.dp,
                        usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                    )
                    Text(
                        stringResource(R.string.watch_store_status_loading_categories),
                        color = style.secondaryTextColor,
                        fontSize = 12.sp,
                    )
                }
                categories.orEmpty().isEmpty() -> Text(
                    stringResource(R.string.watch_store_no_categories),
                    color = style.secondaryTextColor,
                    fontSize = 12.sp,
                )
                else -> categories.orEmpty().forEach { category ->
                    val selected = category.id == selectedCategoryId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(style.rowShape)
                            .background(if (selected) style.selectedCardColor else style.cardColor)
                            .clickable(enabled = !saving) { selectedCategoryId = category.id }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            category.name,
                            modifier = Modifier.weight(1f),
                            color = style.bodyColor,
                            fontSize = 13.sp,
                        )
                        if (selected) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = null,
                                tint = style.accentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            StorePrimaryButton(
                label = stringResource(R.string.watch_store_update_upload),
                onClick = ::update,
                enabled = !saving &&
                    name.isNotBlank() &&
                    description.isNotBlank() &&
                    selectedCategoryId.isNotBlank(),
            )
            Spacer(Modifier.height(if (isScreenRound) 36.dp else 14.dp))
        }
    }
}

/**
 * 商店内上传覆层。与编辑器上传覆层同构：登录/注册 → 选择来源 → 元数据 →
 * 上传；登录表单、凭据记忆与错误映射复用 WatchStoreUploadCommon 里的共享
 * 实现。来源有两路：a) 已安装 .watch 表盘（经 WatchEditorSession 导出）；
 * b) 系统文件选择器选择的本地 .watch 文件。上传中禁用返回与关闭。
 */
@Composable
private fun StoreUploadOverlay(
    repository: WatchStoreRepository,
    installedFaces: List<WatchFaceDescriptor>,
    registrationEnabled: Boolean = true,
    onUploaded: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * G5 / H1：底部「打开网页版」→ `/dashboard`。
     *
     * 登录 / 注册表单也在这个覆层里，所以这一项对**未登录**状态同样有效 —— 手表上打字费劲，
     * 那正是老师要的兜底（在网页上登录，回来 cookie 还在）。
     */
    onOpenWeb: (() -> Unit)? = null,
    onOpenWebExternal: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isScreenRound = isFlueScreenRound()
    val style = LauncherTheme.style
    val authStore = remember { WatchStoreAuthStore(context) }
    val credentialStore = remember { WatchStoreCredentialStore(context) }
    val api = remember { HttpWatchStoreApi() }

    var auth by remember { mutableStateOf<WatchStoreAuthSession?>(null) }
    var authChecked by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberUsername by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var authAction by remember { mutableStateOf<WatchStoreAuthAction?>(null) }
    var authPage by remember { mutableStateOf(WatchStoreAuthPage.LOGIN) }
    var formError by remember { mutableStateOf<String?>(null) }
    var uploadPercent by remember { mutableStateOf<Int?>(null) }
    var selectedDescriptorId by remember { mutableStateOf<String?>(null) }
    var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf("") }
    var authorEdited by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var descriptionEdited by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var uploadTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagsEdited by remember { mutableStateOf(false) }
    var screenTypeEdited by remember { mutableStateOf(false) }
    var uploadProtected by remember { mutableStateOf(false) }
    var parsingMetadata by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<WatchStoreCategory>?>(null) }
    var categoriesError by remember { mutableStateOf<String?>(null) }
    var categoriesRetryToken by remember { mutableIntStateOf(0) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var screenType by remember {
        mutableStateOf(if (isScreenRound) WatchStoreScreenType.ROUND else WatchStoreScreenType.SQUARE)
    }

    val installedWatchmakerFaces = remember(installedFaces) {
        installedFaces.filter { it.isWatchmaker && it.sourceDirPath != null }
    }
    val selectedDescriptor = selectedDescriptorId
        ?.let { id -> installedWatchmakerFaces.firstOrNull { it.id == id } }
    val sourceChosen = selectedDescriptor != null || pickedFileUri != null

    val pickWatchFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedFileUri = uri
            selectedDescriptorId = null
            val display = queryStoreUploadDisplayName(context, uri)
                ?: context.getString(R.string.watch_store_local_face)
            pickedFileName = display
            if (!nameEdited) {
                name = display.removeSuffix(".watch")
                    .ifBlank { context.getString(R.string.watch_editor_default_watch_name) }
                    .take(100)
            }
        }
    }

    LaunchedEffect(Unit) {
        // 令牌与已记住的凭据都在 IO 上读；打开覆层时自动填充登录表单。
        val (storedAuth, savedCredentials) = withContext(Dispatchers.IO) {
            authStore.read() to credentialStore.read()
        }
        auth = storedAuth
        savedCredentials.username?.let { username = it }
        savedCredentials.password?.let { password = it }
        rememberUsername = savedCredentials.rememberUsername
        rememberPassword = savedCredentials.rememberPassword
        if (!authorEdited) {
            author = storedAuth?.username.orEmpty().ifBlank {
                savedCredentials.username.orEmpty()
            }
        }
        authChecked = true
    }

    LaunchedEffect(auth, categoriesRetryToken) {
        if (auth == null || categories != null) return@LaunchedEffect
        categoriesError = null
        runCatching { repository.getCategories() }
            .onSuccess { list ->
                categories = list
                if (selectedCategoryId == null) selectedCategoryId = list.firstOrNull()?.id
            }
            .onFailure { error ->
                error.throwIfCancellation()
                categoriesError = watchStoreUploadErrorMessage(context, error)
            }
    }

    LaunchedEffect(auth, selectedDescriptorId, pickedFileUri) {
        val activeAuth = auth
        val descriptor = selectedDescriptor
        val fileUri = pickedFileUri
        if (activeAuth == null || (descriptor == null && fileUri == null)) {
            parsingMetadata = false
            return@LaunchedEffect
        }
        parsingMetadata = true
        runCatching {
            withContext(Dispatchers.IO) {
                val archive = File.createTempFile("flue_store_parse_", ".watch", context.cacheDir)
                try {
                    if (descriptor != null) {
                        WatchEditorSession.open(context, descriptor).use { session ->
                            session.exportWatch(archive, protectedExport = session.isProtected)
                        }
                    } else {
                        copyPickedWatchArchive(context, requireNotNull(fileUri), archive)
                    }
                    api.parseWatchFace(activeAuth.token, archive)
                } finally {
                    archive.delete()
                }
            }
        }.onSuccess { parsed ->
            if (!nameEdited && parsed.title.isNotBlank()) name = parsed.title.take(100)
            if (!descriptionEdited && parsed.description.isNotBlank()) {
                description = parsed.description.take(2000)
            }
            if (!tagsEdited && parsed.tags.isNotEmpty()) {
                uploadTags = normalizeWatchStoreTags(parsed.tags)
            }
            if (!authorEdited && parsed.author.isNotBlank()) author = parsed.author.take(80)
            if (!screenTypeEdited && parsed.screenType != WatchStoreScreenType.UNKNOWN) {
                screenType = parsed.screenType
            }
            uploadProtected = parsed.wmProtected
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // Parsing is a convenience prefill. A transient parse failure must not prevent the
            // existing upload flow; the create request will still run the mandatory server parse.
        }
        parsingMetadata = false
    }

    fun authenticate(register: Boolean) {
        if (busy != null) return
        val cleanUsername = username.trim()
        val inputError = watchStoreAuthInputError(context, cleanUsername, password)
        if (inputError != null) {
            formError = inputError
            return
        }
        busy = context.getString(
            if (register) R.string.watch_store_status_registering
            else R.string.watch_store_status_logging_in,
        )
        authAction = if (register) WatchStoreAuthAction.REGISTER else WatchStoreAuthAction.LOGIN
        formError = null
        scope.launch {
            try {
                runCatching {
                    watchStoreLoginOrRegister(
                        api = api,
                        authStore = authStore,
                        credentialStore = credentialStore,
                        username = cleanUsername,
                        password = password,
                        register = register,
                        rememberUsername = rememberUsername,
                        rememberPassword = rememberPassword,
                    )
                }.onSuccess { stored ->
                    password = ""
                    auth = stored
                    if (!authorEdited) author = stored.username
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    formError = watchStoreAuthErrorMessage(context, error, register)
                }
            } finally {
                busy = null
                authAction = null
            }
        }
    }

    fun upload() {
        if (busy != null) return
        val activeAuth = auth ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            formError = context.getString(R.string.watch_store_error_name_required)
            return
        }
        if (description.isBlank()) {
            formError = context.getString(R.string.watch_store_error_description_required)
            return
        }
        val categoryId = selectedCategoryId
        if (categoryId == null) {
            formError = context.getString(R.string.watch_store_error_category_required)
            return
        }
        val descriptor = selectedDescriptor
        val fileUri = pickedFileUri
        if (descriptor == null && fileUri == null) {
            formError = context.getString(R.string.watch_store_error_source_required)
            return
        }
        val effectiveAuthor = author.trim().ifBlank { activeAuth.username }.ifBlank { "flue" }
        busy = context.getString(R.string.watch_store_status_exporting_package)
        formError = null
        uploadPercent = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("flue_store_upload_", ".watch", context.cacheDir)
                    try {
                        if (descriptor != null) {
                            WatchEditorSession.open(context, descriptor).use { session ->
                                session.exportWatch(
                                    archive,
                                    rootAttributeOverrides = mapOf(
                                        "designer_name" to effectiveAuthor,
                                        "author" to effectiveAuthor,
                                    ),
                                    protectedExport = uploadProtected,
                                )
                            }
                        } else {
                            copyPickedWatchArchive(context, requireNotNull(fileUri), archive)
                        }
                        if (archive.length() > HttpWatchStoreApi.MAX_UPLOAD_BYTES) {
                            throw WatchStorePayloadTooLargeException(HttpWatchStoreApi.MAX_UPLOAD_BYTES)
                        }
                        busy = context.getString(R.string.watch_store_status_uploading)
                        api.uploadWatchFace(
                            token = activeAuth.token,
                            file = archive,
                            name = trimmedName,
                            description = description.trim(),
                            screenType = screenType,
                            categoryId = categoryId,
                            tags = uploadTags,
                        ) { sent, total ->
                            val percent = ((sent * 100L) / total.coerceAtLeast(1L))
                                .toInt()
                                .coerceIn(0, 100)
                            if (uploadPercent != percent) uploadPercent = percent
                        }
                    } finally {
                        archive.delete()
                    }
                }
            }.onSuccess {
                Toast.makeText(
                    context,
                    context.getString(R.string.watch_store_upload_success),
                    Toast.LENGTH_SHORT,
                ).show()
                onUploaded()
                onDismiss()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is WatchStoreHttpException && error.statusCode == 401) {
                    // 服务器判定令牌失效比本地过期时间更权威：清掉并回到登录页。
                    authStore.clear()
                    auth = null
                    formError = context.getString(R.string.watch_store_error_auth_expired_upload)
                } else {
                    formError = watchStoreUploadErrorMessage(context, error)
                }
            }
            busy = null
            uploadPercent = null
        }
    }

    // 上传中禁止返回键关闭，避免协程随组合一起被取消而中断传输。
    BackHandler { if (busy == null) onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            // 与详情覆层一致：只挡命中、不消费，覆层内部的 verticalScroll
            // 才能正常跨过 touch-slop（消费会掐死自己的滚动）。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(
                horizontal = if (isScreenRound) 16.dp else 10.dp,
                vertical = if (isScreenRound) 10.dp else 6.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (busy == null) onDismiss() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    MiuixIcons.Back,
                    contentDescription = stringResource(R.string.watch_store_close_upload),
                    tint = style.titleColor,
                )
            }
            StoreOverlayTitleBlock(
                title = stringResource(R.string.watch_store_upload_to_store),
                subtitle = null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(40.dp))
        }
        // 同上：上传表单也要回弹与圆屏首尾留白。
        val uploadScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .flueOverscroll(uploadScrollState)
                .verticalScroll(uploadScrollState)
                .padding(horizontal = if (isScreenRound) 6.dp else 0.dp)
                .padding(vertical = if (isScreenRound) STORE_FORM_ROUND_INSET else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            busy?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlueLoadingIndicator(
                        size = 18.dp,
                        usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                    )
                    Text(
                        message + (uploadPercent?.let { " $it%" }.orEmpty()),
                        color = style.bodyColor,
                        fontSize = 12.sp,
                    )
                }
            }
            if (parsingMetadata) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlueLoadingIndicator(
                        size = 16.dp,
                        usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                    )
                    Text(
                        stringResource(R.string.watch_store_status_parsing_metadata),
                        color = style.secondaryTextColor,
                        fontSize = 12.sp,
                    )
                }
            }
            formError?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WatchColors.ActiveRed.copy(alpha = 0.16f), style.rowShape)
                        .padding(10.dp),
                    color = WatchColors.ActiveRed,
                    fontSize = 12.sp,
                )
            }
            val activeAuth = auth
            when {
                !authChecked -> Text(
                    stringResource(R.string.watch_store_status_checking_login),
                    color = style.secondaryTextColor,
                    fontSize = 12.sp,
                )
                activeAuth == null -> WatchStoreLoginSection(
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    rememberUsername = rememberUsername,
                    rememberPassword = rememberPassword,
                    onRememberChange = { keepUsername, keepPassword ->
                        rememberUsername = keepUsername
                        rememberPassword = keepPassword
                    },
                    authPage = authPage,
                    onAuthPageChange = { page ->
                        authPage = page
                        formError = null
                    },
                    enabled = busy == null,
                    registrationEnabled = registrationEnabled,
                    loadingAction = authAction,
                    onLogin = { authenticate(register = false) },
                    onRegister = { authenticate(register = true) },
                )
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.watch_store_logged_in_fmt, activeAuth.username),
                            modifier = Modifier.weight(1f),
                            color = style.secondaryTextColor,
                            fontSize = 12.sp,
                        )
                        TextButton(
                            onClick = {
                                if (busy == null) {
                                    authStore.clear()
                                    auth = null
                                    // 登出同步清理已记住的账号/密码（含 Keystore 密文）。
                                    rememberUsername = false
                                    rememberPassword = false
                                    scope.launch(Dispatchers.IO) { credentialStore.clear() }
                                }
                            },
                        ) { Text(stringResource(R.string.watch_store_logout), color = style.accentColor) }
                    }
                    Text(
                        stringResource(R.string.watch_store_upload_source),
                        color = style.titleColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (sourceChosen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedDescriptor?.displayName
                                    ?: pickedFileName
                                    ?: stringResource(R.string.watch_store_selected_face),
                                modifier = Modifier.weight(1f),
                                color = style.bodyColor,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = {
                                    if (busy == null) {
                                        selectedDescriptorId = null
                                        pickedFileUri = null
                                        pickedFileName = null
                                    }
                                },
                            ) { Text(stringResource(R.string.watch_store_select_again), color = style.accentColor) }
                        }
                    } else {
                        StoreSecondaryButton(
                            label = stringResource(R.string.watch_store_select_local_file),
                            onClick = { if (busy == null) pickWatchFile.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (installedWatchmakerFaces.isEmpty()) {
                            Text(
                                stringResource(R.string.watch_store_no_uploadable_installed),
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
                            )
                        } else {
                            Text(
                                stringResource(R.string.watch_store_or_select_installed),
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
                            )
                            installedWatchmakerFaces.forEach { descriptor ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(style.rowShape)
                                        .background(style.cardColor)
                                        .border(1.dp, style.outlineColor, style.rowShape)
                                        .clickable(enabled = busy == null) {
                                            selectedDescriptorId = descriptor.id
                                            pickedFileUri = null
                                            pickedFileName = null
                                            if (!nameEdited) name = descriptor.displayName.take(100)
                                            if (!authorEdited && author.isBlank()) author = activeAuth.username
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        descriptor.displayName,
                                        modifier = Modifier.weight(1f),
                                        color = style.bodyColor,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (sourceChosen) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it.take(100)
                                nameEdited = true
                            },
                            label = {
                                Text(
                                    stringResource(R.string.watch_store_watch_name_required),
                                    color = style.secondaryTextColor,
                                )
                            },
                            singleLine = true,
                            enabled = busy == null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = style.accentColor,
                                unfocusedBorderColor = style.outlineColor,
                                cursorColor = style.accentColor,
                                focusedTextColor = style.bodyColor,
                                unfocusedTextColor = style.bodyColor,
                            ),
                        )
                        if (selectedDescriptor != null) {
                            OutlinedTextField(
                                value = author,
                                onValueChange = {
                                    author = it.take(80)
                                    authorEdited = true
                                },
                                label = { Text(stringResource(R.string.watch_editor_author)) },
                                placeholder = {
                                    Text(stringResource(R.string.watch_store_author_account_hint))
                                },
                                singleLine = true,
                                enabled = busy == null,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = style.accentColor,
                                    unfocusedBorderColor = style.outlineColor,
                                    cursorColor = style.accentColor,
                                    focusedTextColor = style.bodyColor,
                                    unfocusedTextColor = style.bodyColor,
                                ),
                            )
                        }
                        OutlinedTextField(
                            value = description,
                            onValueChange = {
                                description = it.take(2000)
                                descriptionEdited = true
                            },
                            label = {
                                Text(
                                    stringResource(R.string.watch_store_description_required),
                                    color = style.secondaryTextColor,
                                )
                            },
                            minLines = 2,
                            enabled = busy == null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = style.accentColor,
                                unfocusedBorderColor = style.outlineColor,
                                cursorColor = style.accentColor,
                                focusedTextColor = style.bodyColor,
                                unfocusedTextColor = style.bodyColor,
                            ),
                        )
                        WatchStoreTagEditor(
                            tags = uploadTags,
                            input = tagInput,
                            enabled = busy == null,
                            onInputChange = { next ->
                                tagInput = next.take(50)
                                tagsEdited = true
                            },
                            onAdd = {
                                uploadTags = addWatchStoreTag(uploadTags, tagInput)
                                tagInput = ""
                                tagsEdited = true
                            },
                            onEdit = { index ->
                                val edit = beginEditWatchStoreTag(uploadTags, index)
                                uploadTags = edit.tags
                                tagInput = edit.editingText
                                tagsEdited = true
                            },
                            onRemove = { index ->
                                uploadTags = removeWatchStoreTag(uploadTags, index)
                                tagsEdited = true
                            },
                        )
                        Text(
                            stringResource(R.string.watch_store_screen_shape),
                            color = style.titleColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            STORE_UPLOAD_SCREEN_TYPE_OPTIONS.forEach { (candidate, labelRes) ->
                                StorePill(stringResource(labelRes), screenType == candidate) {
                                    if (busy == null) {
                                        screenType = candidate
                                        screenTypeEdited = true
                                    }
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.watch_store_category),
                            color = style.titleColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        when {
                            categoriesError != null -> {
                                Text(
                                    categoriesError.orEmpty(),
                                    color = WatchColors.ActiveRed,
                                    fontSize = 12.sp,
                                )
                                StoreSecondaryButton(
                                    label = stringResource(R.string.watch_store_reload_categories),
                                    onClick = { if (busy == null) categoriesRetryToken++ },
                                )
                            }
                            categories == null -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FlueLoadingIndicator(
                                    size = 16.dp,
                                    usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                                )
                                Text(
                                    stringResource(R.string.watch_store_status_loading_categories),
                                    color = style.secondaryTextColor,
                                    fontSize = 12.sp,
                                )
                            }
                            categories.orEmpty().isEmpty() -> Text(
                                stringResource(R.string.watch_store_no_categories),
                                color = style.secondaryTextColor,
                                fontSize = 12.sp,
                            )
                            else -> categories.orEmpty().forEach { category ->
                                val selected = category.id == selectedCategoryId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(style.rowShape)
                                        .background(if (selected) style.selectedCardColor else style.cardColor)
                                        .clickable(enabled = busy == null) {
                                            selectedCategoryId = category.id
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        category.name,
                                        modifier = Modifier.weight(1f),
                                        color = style.bodyColor,
                                        fontSize = 13.sp,
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = MiuixIcons.Ok,
                                            contentDescription = null,
                                            tint = style.accentColor,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(style.rowShape)
                                .clickable(enabled = busy == null && selectedDescriptor != null) {
                                    uploadProtected = !uploadProtected
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = uploadProtected,
                                onCheckedChange = { uploadProtected = it },
                                enabled = busy == null && selectedDescriptor != null,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.watch_store_upload_protected),
                                    color = style.bodyColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(R.string.watch_store_upload_protected_sub),
                                    color = style.secondaryTextColor,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        StorePrimaryButton(
                            label = stringResource(R.string.watch_store_upload_this_face),
                            onClick = { upload() },
                            enabled = busy == null && !parsingMetadata &&
                                name.isNotBlank() &&
                                description.isNotBlank() &&
                                selectedCategoryId != null,
                        )
                        Text(
                            stringResource(R.string.watch_store_upload_installed_note),
                            color = style.secondaryTextColor,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            if (onOpenWeb != null && onOpenWebExternal != null) {
                StoreWebLinks(
                    label = stringResource(R.string.watch_store_web_open),
                    onOpenInApp = onOpenWeb,
                    onOpenExternal = onOpenWebExternal,
                )
            }
            Spacer(Modifier.height(if (isScreenRound) 36.dp else 14.dp))
        }
    }
}

/** SAF 只给 Uri；标题栏与默认名称需要 DISPLAY_NAME，查询失败时返回 null。 */
private fun queryStoreUploadDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf(String::isNotBlank)
                } else {
                    null
                }
            }
    }.getOrNull()

/**
 * 把 SAF 选中的文件复制进 cache 作为上传源：边复制边限制在 50MB 内，
 * 并校验 zip（PK）文件头，避免把任意文件当作 .watch 传给服务器。
 */
private fun copyPickedWatchArchive(
    context: Context,
    uri: Uri,
    destination: File,
) {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException(context.getString(R.string.watch_store_error_read_selected_file))
    input.use { stream ->
        destination.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > HttpWatchStoreApi.MAX_UPLOAD_BYTES) {
                    throw WatchStorePayloadTooLargeException(HttpWatchStoreApi.MAX_UPLOAD_BYTES)
                }
                output.write(buffer, 0, count)
            }
        }
    }
    require(destination.length() > 0L) {
        context.getString(R.string.watch_store_error_empty_selected_file)
    }
    destination.inputStream().use { stream ->
        val header = ByteArray(2)
        val read = stream.read(header)
        require(read == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            context.getString(R.string.watch_store_error_invalid_selected_archive)
        }
    }
}

private val STORE_UPLOAD_SCREEN_TYPE_OPTIONS = listOf(
    WatchStoreScreenType.ROUND to R.string.watch_store_screen_round,
    WatchStoreScreenType.SQUARE to R.string.watch_store_screen_square,
    WatchStoreScreenType.RECTANGULAR to R.string.watch_store_screen_rectangular,
)

@Composable
internal fun StorePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FlueButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 42.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 详情页底部那排「对这块表盘的态度」动作：收藏 / 加入合集 / 举报。
 *
 * ## 为什么是纯图标
 *
 * 三个动作以前是带文字的轮廓按钮，一行摆不下就换行，圆屏上更挤；举报还被单独降级成
 * 卡片外的一行小字，于是「这三件事是同一类操作」这个关系完全看不出来。改成同一行居中
 * 的纯图标之后，它们在视觉上是一组，也不再和上面的主操作（安装 / 编辑）抢宽度。
 *
 * ## 选中态为什么用强调色
 *
 * [active] 为真时图标与底色都走 accent（老师要的「点击之后变蓝」）。收藏是**有持久状态**
 * 的，所以 accent 一直亮着就是「已收藏」；加入合集和举报是一次性动作，没有可亮的状态，
 * 那就靠按下瞬间的 accent 给出反馈 —— 手表上没有 hover，按压是唯一的即时确认。
 */
@Composable
private fun StoreIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlighted = active || pressed
    val tint = when {
        !enabled -> style.secondaryTextColor.copy(alpha = 0.4f)
        highlighted -> style.accentColor
        else -> style.secondaryTextColor
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(style.chipShape)
            .background(
                if (highlighted) style.accentColor.copy(alpha = 0.14f) else style.pressedCardColor,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
internal fun StoreSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FlueOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 38.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 破坏性动作按钮（删除表盘）。
 *
 * 与 [StoreSecondaryButton] 同一族的轮廓按钮，只把描边与文字换成红色 —— 以前这两个动作
 * 是裸 `TextButton`，和上面三个按钮放在一起「看起来很割裂」（老师原话）。做成按钮而不是
 * 纯文字之后，「这是一个可点的动作」这件事不用靠颜色去猜。
 */
@Composable
internal fun StoreDangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FlueOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 38.dp),
        borderColor = WatchColors.ActiveRed.copy(alpha = 0.55f),
        contentColor = WatchColors.ActiveRed,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WatchStoreTagEditor(
    tags: List<String>,
    input: String,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.watch_store_tags),
            color = style.titleColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.watch_store_tag_input)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = style.accentColor,
                    unfocusedBorderColor = style.outlineColor,
                    cursorColor = style.accentColor,
                    focusedTextColor = style.bodyColor,
                    unfocusedTextColor = style.bodyColor,
                ),
            )
            // 加标签是个按钮，不是一句话（老师报的）。
            // 原来只有 68dp 宽的描边 + 一个「添加」二字：在描边很淡的样式里它和
            // 旁边输入框的边框糊在一起，看着像提示文字。换成实心按钮 + 图标，
            // 任何样式下都一眼看出是可点的。
            FlueButton(
                onClick = onAdd,
                enabled = enabled && input.isNotBlank(),
                modifier = Modifier.heightIn(min = 38.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.watch_store_add_tag),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEachIndexed { index, tag ->
                    Row(
                        modifier = Modifier
                            .clip(style.chipShape)
                            .background(style.cardColor)
                            .border(1.dp, style.outlineColor, style.chipShape)
                            .clickable(enabled = enabled) { onEdit(index) }
                            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "#$tag",
                            color = style.bodyColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { onRemove(index) },
                            enabled = enabled,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = stringResource(
                                    R.string.watch_store_delete_tag_fmt,
                                    tag,
                                ),
                                tint = style.secondaryTextColor,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRecoveryRow(
    diagnostic: WatchFaceCatalogDiagnostic,
    onRescan: () -> Unit,
) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Info,
            contentDescription = null,
            tint = WatchColors.ActiveRed,
            modifier = Modifier.size(26.dp),
        )
        Text(
            stringResource(R.string.watch_store_recovery_required),
            color = WatchColors.ActiveRed,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            diagnostic.message,
            color = style.secondaryTextColor,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        diagnostic.preservedCopyPath?.let { path ->
            Text(
                stringResource(R.string.watch_store_preserved_copy_fmt, File(path).name),
                color = style.secondaryTextColor,
                fontSize = 10.sp,
            )
        }
        TextButton(onClick = onRescan) {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = style.accentColor,
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.watch_store_rescan_and_recover), color = style.accentColor)
        }
    }
}

@Composable
private fun LocalInstalledWatchFaceCard(
    record: WatchFaceRecord,
    descriptor: WatchFaceDescriptor?,
    onOpen: () -> Unit,
) {
    val style = LauncherTheme.style
    val cardShape = storeCardShape(style)
    val interactionSource = remember(record.logicalId) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clip(cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            record.resolvedDisplayName(descriptor?.displayName)
                ?: stringResource(R.string.watch_store_installed_face),
            color = style.titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.watch_store_installed_offline_management),
            color = style.positiveColor,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LocalInstalledWatchPreview(
    descriptor: WatchFaceDescriptor?,
    modifier: Modifier,
    onOpen: ((Bitmap) -> Unit)? = null,
) {
    val style = LauncherTheme.style
    val cardShape = storeCardShape(style)
    val previewPath = descriptor?.previewFilePath
    var bitmap by remember(previewPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(previewPath) {
        bitmap = withContext(Dispatchers.IO) {
            previewPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier = modifier
            .clip(cardShape)
            .background(style.cardColor)
            .then(
                if (bitmap != null && onOpen != null) {
                    Modifier.clickable { bitmap?.let(onOpen) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.watch_store_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                descriptor?.displayName ?: stringResource(R.string.watch_store_local_face),
                color = style.secondaryTextColor,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}

@Composable
internal fun WatchPreview(
    repository: WatchStoreRepository,
    id: String,
    modifier: Modifier,
    targetSize: Int,
    onOpen: ((Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    var retryToken by remember(repository, id, targetSize) { mutableIntStateOf(0) }
    var bitmap by remember(repository, id, targetSize) {
        mutableStateOf(repository.peekPreview(id, targetSize, targetSize))
    }
    var loadError by remember(repository, id, targetSize) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, id, targetSize, retryToken) {
        if (bitmap != null) return@LaunchedEffect
        loadError = null
        try {
            bitmap = repository.loadPreview(id, targetSize, targetSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.userFacingMessage(context)
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    // 三态互斥：加载中转圈 / 失败灰色占位 / 就绪显示图片。占位尺寸完全由
    // 调用方 modifier（height/aspectRatio）决定，态切换只发生在 Box 内部，
    // 绝不改变外框大小——宫格卡片的尺寸因此保持恒定，不会二次塌陷。
    val previewState = when {
        image != null -> WatchPreviewState.READY
        loadError != null -> WatchPreviewState.FAILED
        else -> WatchPreviewState.LOADING
    }
    Box(
        modifier
            .background(style.pressedCardColor)
            .then(
                if (bitmap != null && onOpen != null) {
                    Modifier.clickable { bitmap?.let(onOpen) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Keep the original loading spinner and polished image fade. A synchronous
        // memory-cache seed above avoids replaying this state for covers already seen.
        Crossfade(
            targetState = previewState,
            animationSpec = tween(durationMillis = 220),
            label = "watchPreviewFade",
        ) { state ->
            WatchPreviewContent(
                state = state,
                image = image,
                style = style,
                onRetry = { retryToken++ },
            )
        }
    }
}

@Composable
internal fun WatchPreviewContent(
    state: WatchPreviewState,
    image: ImageBitmap?,
    style: com.flue.launcher.ui.theme.LauncherStyle,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            WatchPreviewState.READY -> image?.let { readyImage ->
                Image(
                    readyImage,
                    contentDescription = stringResource(R.string.watch_store_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            WatchPreviewState.FAILED -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Image,
                    contentDescription = stringResource(R.string.watch_store_preview_load_failed),
                    tint = style.secondaryTextColor,
                    modifier = Modifier.size(18.dp),
                )
                StoreMiniAction(
                    label = stringResource(R.string.watch_store_retry),
                    color = style.accentColor,
                    fontSize = 10.sp,
                    onClick = onRetry,
                    icon = MiuixIcons.Refresh,
                )
            }

            WatchPreviewState.LOADING -> {
                FlueLoadingIndicator(
                    size = 22.dp,
                    usage = FlueLoadingIndicatorUsage.WATCH_STORE,
                )
            }
        }
    }
}

@Composable
private fun FullscreenWatchPreview(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var translation by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 同上：Dialog 独立 Window 会重置 LocalDensity，全屏预览也要重新挂缩放。
        ProvideDialogUiScale {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .onSizeChanged { viewport = it }
                    .pointerInput(bitmap, viewport) {
                        detectTapGestures(
                            onDoubleTap = { position ->
                                if (scale > 1.01f) {
                                    scale = 1f
                                    translation = Offset.Zero
                                } else {
                                    val targetScale = 2.5f
                                    val maxX = viewport.width * (targetScale - 1f) / 2f
                                    val maxY = viewport.height * (targetScale - 1f) / 2f
                                    translation = Offset(
                                        x = ((viewport.width / 2f - position.x) * (targetScale - 1f))
                                            .coerceIn(-maxX, maxX),
                                        y = ((viewport.height / 2f - position.y) * (targetScale - 1f))
                                            .coerceIn(-maxY, maxY),
                                    )
                                    scale = targetScale
                                }
                            },
                        )
                    }
                    .pointerInput(bitmap, viewport) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = viewport.width * (nextScale - 1f) / 2f
                            val maxY = viewport.height * (nextScale - 1f) / 2f
                            translation = if (nextScale <= 1.001f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    x = (translation.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (translation.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            scale = nextScale
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.watch_store_preview),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = translation.x
                            translationY = translation.y
                        },
                    contentScale = ContentScale.Fit,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.watch_editor_back),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 表盘市场层级覆层的进/退场动效，与设置页 [SettingsActivity] 的层级转场同源。
 *
 * fade 不是装饰：只做位移时覆层是一块 100% 不透明的板子，从 1/4 屏处直接盖上
 * 来，底层页面在它后面全程满不透明地并存 —— 观感就是「元素叠加」。设置页那边
 * 曾经为省合成开销删掉 fade，正是老师点名的那个退化。四处覆层共用同一份常量，
 * 避免以后改一处漏三处。
 */
internal val STORE_LAYER_ENTER =
    slideInHorizontally(
        animationSpec = tween(260, easing = FastOutSlowInEasing),
    ) { fullWidth -> fullWidth / 4 } + fadeIn(tween(200))

internal val STORE_LAYER_EXIT =
    slideOutHorizontally(
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    ) { fullWidth -> fullWidth / 4 } + fadeOut(tween(140))

/**
 * 让仍在进/退场的全屏覆层保持命中，避免其滑动后露出的区域点到底层列表。
 * 不消费事件，覆层自己的滚动、输入框和按钮仍可正常接收同一指针流。
 */
internal fun Modifier.blockPointerPassThrough(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** 预览图三态：Crossfade 的比较键，位图内容本身仍从闭包读取。 */
internal enum class WatchPreviewState { LOADING, FAILED, READY }

/**
 * 下拉刷新指示器。外观走 style token，三套 UiStyle 各自成立。
 *
 * 松手前始终保留原刷新图标；只有 onRefresh 真正触发后才切换为加载态。
 */
@Composable
private fun PullRefreshIndicator(
    state: FluePullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress
    if (progress <= 0.01f && !refreshing) return
    Box(
        modifier = modifier
            .padding(top = 6.dp)
            .graphicsLayer {
                // 指示器停在顶端，靠 alpha/scale 跟随进度出现，不额外位移：
                // 列表本体已经被 fluePullToRefresh 下移，两者叠加会跑太快。
                alpha = if (refreshing) 1f else progress
                val scale = 0.7f + 0.3f * progress
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        // 外观三档各一份（MIUIX 圆盘 / MD3 surface 容器 / Apple 无容器细弧）；手势与位移
        // 仍归 FluePullToRefreshState —— 那份分工本来就是对的，A4 只换外观。
        FlueRefreshIndicator(progress = progress, refreshing = refreshing)
    }
}
@Composable
internal fun LoadingStateRow(label: String) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlueLoadingIndicator(
            size = 22.dp,
            usage = FlueLoadingIndicatorUsage.WATCH_STORE,
        )
        Text(label, color = style.secondaryTextColor, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorStateRow(
    message: String,
    onRetry: () -> Unit,
    onOpenCustomMarketUrl: (() -> Unit)? = null,
) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Info,
            contentDescription = null,
            tint = WatchColors.ActiveRed,
            modifier = Modifier.size(24.dp),
        )
        Text(
            message,
            color = style.secondaryTextColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = style.accentColor,
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.watch_store_retry), color = style.accentColor)
        }
        onOpenCustomMarketUrl?.let { openUrl ->
            TextButton(onClick = openUrl) {
                Text(
                    stringResource(R.string.watch_store_custom_market_url),
                    color = style.accentColor,
                )
            }
        }
    }
}

@Composable
private fun EmptyStateRow() {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Search,
            contentDescription = null,
            tint = style.secondaryTextColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            stringResource(R.string.watch_store_empty_search),
            color = style.secondaryTextColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@StringRes
internal fun screenTypeLabelRes(type: WatchStoreScreenType): Int = when (type) {
    WatchStoreScreenType.ROUND -> R.string.watch_store_screen_round_short
    WatchStoreScreenType.SQUARE -> R.string.watch_store_screen_square_short
    WatchStoreScreenType.RECTANGULAR -> R.string.watch_store_screen_rectangular_short
    WatchStoreScreenType.UNKNOWN -> R.string.watch_store_screen_unknown
}

internal fun Throwable.userFacingMessage(context: Context): String = when (this) {
    is WatchStoreException -> message
        ?: context.getString(R.string.watch_store_error_request_failed)
    else -> message?.takeIf { it.isNotBlank() }?.take(120)
        ?: context.getString(R.string.watch_store_error_network_or_validation)
}

internal fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

private suspend fun exportInstalledWatchFace(
    context: Context,
    descriptor: WatchFaceDescriptor,
    destination: Uri,
): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        require(descriptor.isWatchmaker) {
            context.getString(R.string.watch_store_error_export_standard_only)
        }
        val temporary = File.createTempFile("flue_watch_export_", ".watch", context.cacheDir)
        try {
            WatchEditorSession.open(context, descriptor).use { session ->
                session.exportWatch(temporary, protectedExport = session.isProtected)
            }
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                temporary.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: error(context.getString(R.string.watch_store_error_write_export))
        } finally {
            temporary.delete()
        }
    }
}

private fun safeStoreExportName(displayName: String): String = displayName
    .trim()
    .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
    .take(80)
    .ifBlank { "Flue-Watch" }
