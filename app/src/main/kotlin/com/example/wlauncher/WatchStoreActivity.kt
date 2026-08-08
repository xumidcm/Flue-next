package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import com.flue.launcher.ui.settings.miuix.MiuixLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import com.flue.launcher.ui.common.FluePullToRefreshState
import com.flue.launcher.ui.common.fluePressScale
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.fluePullToRefresh
import com.flue.launcher.ui.common.chordScaleGridItem
import com.flue.launcher.ui.common.chordScaleItem
import com.flue.launcher.ui.common.chordScaleForPosition
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.roundScrollablePadding
import com.flue.launcher.ui.common.rememberFluePressScale
import com.flue.launcher.ui.common.rememberFluePullToRefreshState
import com.flue.launcher.ui.theme.LauncherTheme
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
import com.flue.launcher.watchface.store.WatchStoreException
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import com.flue.launcher.watchface.store.WatchStoreInstallState
import com.flue.launcher.watchface.store.WatchStoreInstallTransactionException
import com.flue.launcher.watchface.store.WatchStorePagination
import com.flue.launcher.watchface.store.WatchStoreQuery
import com.flue.launcher.watchface.store.WatchStoreRepository
import com.flue.launcher.watchface.store.WatchStoreScreenType
import com.flue.launcher.watchface.store.WatchStoreSort
import com.flue.launcher.watchface.store.WatchStoreUiPreferences
import com.flue.launcher.watchface.store.WatchStoreViewMode
import com.flue.launcher.watchface.store.WatchStoreWatchFace
import com.flue.launcher.watchface.store.WatchStoreWatchFaceDetail
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
import kotlin.math.roundToInt

/**
 * Public Watchmaker community store.  The Activity deliberately owns only the
 * coarse screen state; HTTP, archive validation and bitmap decoding stay in
 * [WatchStoreRepository] (which switches to Dispatchers.IO).
 */
class WatchStoreActivity : FlueComponentActivity() {
    private val launcherViewModel: LauncherViewModel by viewModels()
    private val resumeGeneration = MutableStateFlow(0)

    override fun onResume() {
        super.onResume()
        resumeGeneration.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        val repository = WatchStoreRepository(this)
        val uiPreferences = WatchStoreUiPreferences(this)
        setContent {
            val themeMode by launcherViewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by launcherViewModel.uiStyle.collectAsStateWithLifecycle()
            val screenShapeOverride by launcherViewModel.screenShapeOverride.collectAsStateWithLifecycle()
            val uiScale by launcherViewModel.globalUiScalePercent.collectAsStateWithLifecycle()
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
                        uiPreferences = uiPreferences,
                        reconcileToken = resumeToken,
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
)

private const val STORE_BROWSE_CONTROLS_KEY = "watch_store_browse_controls"

@Composable
private fun WatchStoreScreen(
    repository: WatchStoreRepository,
    uiPreferences: WatchStoreUiPreferences,
    reconcileToken: Int,
    onBack: () -> Unit,
    installArchive: suspend (java.io.File, installAsRound: Boolean) -> Result<InstalledStoreWatchFace>,
    useInstalled: (launcherId: String) -> Unit,
    editInstalled: (launcherId: String) -> Unit,
    exportInstalled: (launcherId: String) -> Unit,
    deleteInstalled: suspend (launcherIds: Collection<String>) -> Result<Unit>,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val isScreenRound = isFlueScreenRound()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
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
    var reloadToken by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var lastDetailId by remember { mutableStateOf<String?>(null) }
    var selectedDetail by remember { mutableStateOf<WatchStoreWatchFaceDetail?>(null) }
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
    val localMarketRecords = remember(installedMarketRecords) {
        installedMarketRecords.values.sortedByDescending(WatchFaceRecord::installedAtEpochMs)
    }
    val downloadStates = remember { mutableStateMapOf<String, MutableStateFlow<WatchStoreInstallState>>() }
    val downloadJobs = remember { mutableMapOf<String, Job>() }
    val pendingCatalogInstalls = remember { mutableStateMapOf<String, PendingMarketCatalogInstall>() }
    var installedActionError by remember { mutableStateOf<String?>(null) }
    var uploadVisible by remember { mutableStateOf(false) }
    var myUploadsVisible by remember { mutableStateOf(false) }
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
                    search = queryKey.search.takeIf(String::isNotBlank),
                ),
            )
            entries = firstPage.data
            pagination = firstPage.pagination
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
                        search = queryKey.search.takeIf(String::isNotBlank),
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
                if (
                    showEntries &&
                    currentCatalogError == null &&
                    entries.isEmpty() &&
                    currentListError != null &&
                    localMarketRecords.isNotEmpty()
                ) {
                    add(
                        "offline_installed_header" to {
                            Text(
                                stringResource(R.string.watch_store_installed_faces),
                                color = LauncherTheme.style.titleColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            )
                        },
                    )
                    localMarketRecords.forEach { record ->
                        val source = record.source as WatchFaceInstallSource.Market
                        add(
                            "offline_${record.logicalId}" to {
                                LocalInstalledWatchFaceCard(
                                    record = record,
                                    descriptor = record.activeLauncherIdOrNull()?.let(installedFacesById::get),
                                    onOpen = { openDetail(source.remoteId) },
                                )
                            },
                        )
                    }
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
                                    ErrorStateRow(message = currentListError, onRetry = { reloadToken++ })
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
                bottom = if (isScreenRound) with(browseDensity) { roundPadding.bottom.toDp() } else 20.dp,
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
                            .flueElasticNestedScroll(listState)
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
                                onBack = onBack,
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
                                onBack = onBack,
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
                onUploaded = { reloadToken++ },
                onDismiss = { uploadVisible = false },
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
                onDismiss = { myUploadsVisible = false },
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
    onToggleViewMode: () -> Unit,
    onUpload: () -> Unit,
    onMyUploads: () -> Unit,
    onBack: () -> Unit,
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
                                MiuixIcons.Clear,
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

@Composable
private fun StoreHeader(
    onBack: () -> Unit,
    viewMode: WatchStoreViewMode,
    onToggleViewMode: () -> Unit,
    onUpload: () -> Unit,
    onMyUploads: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
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
            Text(
                stringResource(R.string.watch_store_title),
                color = style.titleColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.watch_store_community_subtitle),
                color = style.secondaryTextColor,
                fontSize = 10.sp,
            )
        }
        IconButton(onClick = onMyUploads, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = MiuixIcons.Folder,
                contentDescription = stringResource(R.string.watch_store_my_uploads),
                tint = style.titleColor,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onUpload, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = MiuixIcons.UploadCloud,
                contentDescription = stringResource(R.string.watch_store_upload_face),
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
    }
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
    fallbackLine: (@Composable () -> Unit)? = null,
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
        InstallStateLineContent(
            downloadState = downloadState,
            compact = compact,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            fallbackLine = fallbackLine,
        )
    }
}

@Composable
private fun InstallStateLineContent(
    downloadState: WatchStoreInstallState,
    compact: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    fallbackLine: (@Composable () -> Unit)?,
) {
    val style = LauncherTheme.style
    when (downloadState) {
        is WatchStoreInstallState.Downloading,
        is WatchStoreInstallState.Validating,
        is WatchStoreInstallState.Installing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixLoadingIndicator(
                    Modifier.size(if (compact) 11.dp else 14.dp),
                    strokeWidth = 2.dp,
                    color = style.accentColor,
                )
                Spacer(Modifier.width(5.dp))
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
        is WatchStoreInstallState.Installed -> Text(
            stringResource(R.string.watch_store_installed),
            color = style.positiveColor,
            fontSize = 10.sp,
            maxLines = 1,
        )
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
                if (fallbackLine != null) {
                    Box(Modifier.weight(1f)) { fallbackLine() }
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
    val previewRatio = when (item.screenType) {
        WatchStoreScreenType.ROUND, WatchStoreScreenType.SQUARE, WatchStoreScreenType.UNKNOWN -> 1f
        WatchStoreScreenType.RECTANGULAR -> 0.72f
    }
    val previewShape = if (item.screenType == WatchStoreScreenType.ROUND) style.chipShape else style.rowShape
    val interactionSource = remember(item.id) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clip(style.cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.cardShape)
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
                .aspectRatio(previewRatio)
                .clip(previewShape),
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
            InstallStateLine(
                downloadState = downloadState,
                compact = false,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                fallbackLine = {
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
            .clip(style.cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.cardShape)
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
            InstallStateLine(
                downloadState = downloadState,
                compact = true,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                fallbackLine = {
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
            Text(
                stringResource(R.string.watch_store_detail_title),
                color = style.titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
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
                    Column(
                        Modifier
                            .fillMaxSize()
                            .flueElasticNestedScroll(detailScrollState)
                            .verticalScroll(detailScrollState)
                            // 圆屏：全宽按钮和文字向内收，避开圆角裁切。
                            .padding(horizontal = if (isScreenRound) 6.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (remotePreviewId.isNotBlank()) {
                            val previewRatio = when (remoteScreenType) {
                                WatchStoreScreenType.RECTANGULAR -> 0.72f
                                else -> 1f
                            }
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
                        Text(
                            displayName,
                            color = style.titleColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        detail?.let { remoteDetail ->
                            Text(
                                stringResource(
                                    R.string.watch_store_detail_meta_fmt,
                                    remoteDetail.category.name,
                                    stringResource(screenTypeLabelRes(remoteDetail.screenType)),
                                    remoteDetail.downloadCount,
                                ),
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
                            )
                            if (remoteDetail.description.isNotBlank()) {
                                Text(
                                    remoteDetail.description,
                                    color = style.bodyColor.copy(alpha = 0.86f),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                            Text(
                                stringResource(
                                    R.string.watch_store_author_fmt,
                                    remoteDetail.uploader.username,
                                ),
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixLoadingIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = style.accentColor,
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
                                // Flue 不执行 Lua，带 Global Script 的表盘装上后脚本不生效。
                                // 只提示这一项：其余兼容性字段刻意不展示。报告随 catalog
                                // 持久化，所以进程重启后回到这一页仍然看得到。
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
                                        modifier = Modifier.weight(1f),
                                    )
                                    StoreSecondaryButton(
                                        label = stringResource(R.string.watch_store_export),
                                        onClick = { onExport(record) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
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
                                        TextButton(onClick = { editingName = false }) {
                                            Text(
                                                stringResource(R.string.watch_editor_cancel),
                                                color = style.secondaryTextColor,
                                            )
                                        }
                                        TextButton(onClick = { onRename(record, nameText); editingName = false }) {
                                            Text(
                                                stringResource(R.string.watch_editor_save),
                                                color = style.accentColor,
                                            )
                                        }
                                    }
                                } else {
                                    TextButton(
                                        onClick = {
                                            nameText = record.resolvedDisplayName(fallbackName) ?: displayName
                                            editingName = true
                                        },
                                    ) {
                                        Text(
                                            stringResource(R.string.watch_editor_rename),
                                            color = style.accentColor,
                                        )
                                    }
                                }
                                TextButton(onClick = { onDelete(record) }) {
                                    Text(
                                        stringResource(R.string.watch_store_delete_installed),
                                        color = WatchColors.ActiveRed,
                                    )
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

@Composable
private fun MyUploadsOverlay(
    repository: WatchStoreRepository,
    installedFaces: List<WatchFaceDescriptor>,
    onDismiss: () -> Unit,
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
    var loginFormVisible by remember { mutableStateOf(false) }
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
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    Text(
                        stringResource(R.string.watch_store_my_uploads),
                        color = style.titleColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    session?.username?.let { username ->
                        Text(username, color = style.secondaryTextColor, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

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
                            loadingAction = authAction,
                            onLogin = { authenticate(register = false) },
                            onRegister = { authenticate(register = true) },
                        )
                    }

                    MyUploadsContentState.LOADING -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        MiuixLoadingIndicator(
                            Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = style.accentColor,
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

                    MyUploadsContentState.LIST -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = entries,
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
        AlertDialog(
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
                        MiuixLoadingIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = style.accentColor)
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
                MiuixLoadingIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = style.accentColor,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                color = style.bodyColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                                    session.exportWatch(archive)
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
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isScreenRound) 6.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saving) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiuixLoadingIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = style.accentColor,
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
                    MiuixLoadingIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = style.accentColor,
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
    onUploaded: () -> Unit,
    onDismiss: () -> Unit,
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
    var author by remember { mutableStateOf("") }
    var authorEdited by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
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
            if (name.isBlank()) {
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
            Text(
                stringResource(R.string.watch_store_upload_to_store),
                modifier = Modifier.weight(1f),
                color = style.titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Spacer(Modifier.size(40.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isScreenRound) 6.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            busy?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiuixLoadingIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = style.accentColor)
                    Text(
                        message + (uploadPercent?.let { " $it%" }.orEmpty()),
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
                                            if (name.isBlank()) name = descriptor.displayName.take(100)
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
                            onValueChange = { name = it.take(100) },
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
                            onValueChange = { description = it.take(2000) },
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
                                    if (busy == null) screenType = candidate
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
                                MiuixLoadingIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = style.accentColor,
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
                        StorePrimaryButton(
                            label = stringResource(R.string.watch_store_upload_this_face),
                            onClick = { upload() },
                            enabled = busy == null &&
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
private fun StorePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 42.dp),
        shape = style.rowShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = style.accentColor,
            contentColor = accentContentColor(style.accentColor),
            disabledContainerColor = style.pressedCardColor,
            disabledContentColor = style.secondaryTextColor,
        ),
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

@Composable
private fun StoreSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = style.rowShape,
        border = BorderStroke(1.dp, style.outlineColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = style.accentColor),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Filled-accent buttons need readable content on both bright and deep accents. */
private fun accentContentColor(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

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
    val interactionSource = remember(record.logicalId) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clip(style.cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.cardShape)
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
            .clip(style.cardShape)
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
private fun WatchPreview(
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
private fun WatchPreviewContent(
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
                MiuixLoadingIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = style.accentColor,
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

/**
 * 表盘市场层级覆层的进/退场动效，与设置页 [SettingsActivity] 的层级转场同源。
 *
 * fade 不是装饰：只做位移时覆层是一块 100% 不透明的板子，从 1/4 屏处直接盖上
 * 来，底层页面在它后面全程满不透明地并存 —— 观感就是「元素叠加」。设置页那边
 * 曾经为省合成开销删掉 fade，正是老师点名的那个退化。四处覆层共用同一份常量，
 * 避免以后改一处漏三处。
 */
private val STORE_LAYER_ENTER =
    slideInHorizontally(
        animationSpec = tween(260, easing = FastOutSlowInEasing),
    ) { fullWidth -> fullWidth / 4 } + fadeIn(tween(200))

private val STORE_LAYER_EXIT =
    slideOutHorizontally(
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    ) { fullWidth -> fullWidth / 4 } + fadeOut(tween(140))

/**
 * 让仍在进/退场的全屏覆层保持命中，避免其滑动后露出的区域点到底层列表。
 * 不消费事件，覆层自己的滚动、输入框和按钮仍可正常接收同一指针流。
 */
private fun Modifier.blockPointerPassThrough(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** 预览图三态：Crossfade 的比较键，位图内容本身仍从闭包读取。 */
private enum class WatchPreviewState { LOADING, FAILED, READY }

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
    val style = LauncherTheme.style
    val progress = state.progress
    if (progress <= 0.01f && !refreshing) return
    Box(
        modifier = modifier
            .padding(top = 6.dp)
            .size(30.dp)
            .graphicsLayer {
                // 指示器停在顶端，靠 alpha/scale 跟随进度出现，不额外位移：
                // 列表本体已经被 fluePullToRefresh 下移，两者叠加会跑太快。
                alpha = if (refreshing) 1f else progress
                val scale = 0.7f + 0.3f * progress
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(style.cardColor),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            MiuixLoadingIndicator(
                Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = style.accentColor,
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                tint = style.accentColor,
                modifier = Modifier
                    .size(17.dp)
                    .graphicsLayer { rotationZ = progress * 270f },
            )
        }
    }
}

@Composable
private fun LoadingStateRow(label: String) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiuixLoadingIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = style.accentColor)
        Text(label, color = style.secondaryTextColor, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorStateRow(message: String, onRetry: () -> Unit) {
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
private fun screenTypeLabelRes(type: WatchStoreScreenType): Int = when (type) {
    WatchStoreScreenType.ROUND -> R.string.watch_store_screen_round_short
    WatchStoreScreenType.SQUARE -> R.string.watch_store_screen_square_short
    WatchStoreScreenType.RECTANGULAR -> R.string.watch_store_screen_rectangular_short
    WatchStoreScreenType.UNKNOWN -> R.string.watch_store_screen_unknown
}

private fun Throwable.userFacingMessage(context: Context): String = when (this) {
    is WatchStoreException -> message
        ?: context.getString(R.string.watch_store_error_request_failed)
    else -> message?.takeIf { it.isNotBlank() }?.take(120)
        ?: context.getString(R.string.watch_store_error_network_or_validation)
}

private fun Throwable.throwIfCancellation() {
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
                session.exportWatch(temporary)
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
