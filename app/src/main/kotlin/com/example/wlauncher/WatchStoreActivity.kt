package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import com.flue.launcher.ui.common.FluePullToRefreshState
import com.flue.launcher.ui.common.fluePressScale
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.fluePullToRefresh
import com.flue.launcher.ui.common.isFlueScreenRound
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
                                Toast.makeText(this@WatchStoreActivity, "表盘已导出", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(
                                    this@WatchStoreActivity,
                                    error.userFacingMessage(),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                }
            }
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) {
                ProvideGlobalUiScale(uiScale) {
                    WatchStoreScreen(
                        repository = repository,
                        uiPreferences = uiPreferences,
                        reconcileToken = resumeToken,
                        onBack = ::finish,
                        installArchive = { archive ->
                            val installed = launcherViewModel.installWatchStoreArchive(archive)
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
                                Toast.makeText(this@WatchStoreActivity, "找不到已安装表盘", Toast.LENGTH_SHORT).show()
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

@Composable
private fun WatchStoreScreen(
    repository: WatchStoreRepository,
    uiPreferences: WatchStoreUiPreferences,
    reconcileToken: Int,
    onBack: () -> Unit,
    installArchive: suspend (java.io.File) -> Result<InstalledStoreWatchFace>,
    useInstalled: (launcherId: String) -> Unit,
    editInstalled: (launcherId: String) -> Unit,
    exportInstalled: (launcherId: String) -> Unit,
    deleteInstalled: suspend (launcherIds: Collection<String>) -> Result<Unit>,
) {
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
    var categoriesLoading by remember { mutableStateOf(true) }
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
                installedActionError = "旧版表盘清理失败：${cleanupError.userFacingMessage()}"
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
                catalogWarning = buildString {
                    append("有 ")
                    append(restored.scanFailures.size)
                    append(" 个已安装表盘暂时无法读取，已保留安装记录")
                    if (first != null) {
                        append("：")
                        append(first.directoryName.ifBlank { "未知目录" })
                        append(" · ")
                        append(first.message)
                    }
                }
            }
            repairCatalogOnNextRestore = false
        } catch (error: Throwable) {
            error.throwIfCancellation()
            catalogError = error.userFacingMessage()
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
        categoriesLoading = true
        categoriesError = null
        runCatching { repository.getCategories() }
            .onSuccess { categories = it }
            .onFailure {
                it.throwIfCancellation()
                categoriesError = it.userFacingMessage()
            }
        categoriesLoading = false
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
            listError = error.userFacingMessage()
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
                listError = error.userFacingMessage()
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
                detailError = it.userFacingMessage()
            }
        detailLoading = false
    }

    fun download(id: String) {
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
                        val descriptor = installArchive(archive).getOrThrow()
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
                                    installedActionError = "新版已安装，但旧版本清理失败：${cleanupError.userFacingMessage()}"
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
                            installedActionError = "安装失败，事务清理待恢复：${cleanupError.userFacingMessage()}"
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
                        error.userFacingMessage()
                    } else if (pendingCatalogInstalls[id] != null) {
                        "表盘已安装，市场记录恢复失败，请重试"
                    } else {
                        error.userFacingMessage()
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
                    installedActionError = it.userFacingMessage()
                }
        }
    }

    fun removeInstalled(id: String, record: WatchFaceRecord) {
        val launcherId = record.activeLauncherIdOrNull() ?: run {
            installedActionError = "找不到已安装表盘的兼容标识"
            return
        }
        val installedIds = (record.legacyIds + launcherId)
            .filter(installedFacesById::containsKey)
            .ifEmpty { listOf(launcherId) }
        scope.launch {
            val deletion = deleteInstalled(installedIds)
            deletion.onFailure {
                it.throwIfCancellation()
                installedActionError = it.userFacingMessage()
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
                        installedActionError = "表盘已删除，安装索引待恢复：${catalogFailure.userFacingMessage()}"
                        repairCatalogOnNextRestore = true
                        catalogReloadToken++
                    }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.screenBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isScreenRound) 16.dp else 10.dp,
                    vertical = if (isScreenRound) 10.dp else 6.dp,
                ),
        ) {
            StoreHeader(
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
            )
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it.take(200) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = style.rowShape,
                placeholder = { Text("搜索表盘", color = style.secondaryTextColor, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索", tint = style.secondaryTextColor) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空", tint = style.secondaryTextColor)
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
            Spacer(Modifier.height(7.dp))
            FilterBar(
                sort = sort,
                onSortChange = { sort = it },
                screenType = screenType,
                onScreenTypeChange = { screenType = it },
                selectedCategory = selectedCategory,
                categories = categories,
                categoriesLoading = categoriesLoading,
                categoriesError = categoriesError,
                categoryMenuExpanded = categoryMenuExpanded,
                onCategoryMenuExpandedChange = { categoryMenuExpanded = it },
                onCategoryChange = {
                    selectedCategory = it
                    categoryMenuExpanded = false
                },
            )
            Spacer(Modifier.height(4.dp))

            val showEntries = catalogInitialized && catalog.diagnostic == null
            val visibleEntries = if (showEntries) entries else emptyList()
            val currentCatalogError = catalogError
            val currentCatalogWarning = catalogWarning
            val currentListError = listError
            val statusRows = buildList<Pair<String, @Composable () -> Unit>> {
                if (!catalogInitialized) {
                    add("catalog_loading" to { LoadingStateRow("正在恢复安装状态…") })
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
                                "已安装表盘",
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
                            add("list_loading" to { LoadingStateRow("正在加载表盘…") })
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
                        add("loading_more" to { LoadingStateRow("加载更多…") })
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
            val browseContentPadding = PaddingValues(
                top = if (isScreenRound) 14.dp else 4.dp,
                bottom = if (isScreenRound) 48.dp else 20.dp,
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
                // 指示器画在列表下方图层、被内容位移带出来：列表本体由
                // fluePullToRefresh 的 graphicsLayer 一起下移，指示器停在
                // 顶端固定位置，视觉上就是从列表底下被拉出来的。
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
                            .flueElasticNestedScroll(listState)
                            .fluePullToRefresh(pullRefreshState),
                        contentPadding = browseContentPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visibleEntries, key = { it.id }) { item ->
                            val transientState = downloadStates[item.id]
                                ?.collectAsStateWithLifecycle()
                                ?.value
                            WatchFaceListCard(
                                repository = repository,
                                item = item,
                                downloadState = transientState ?: persistentInstallState(item.id),
                                onOpen = { openDetail(item.id) },
                                onDownload = { download(item.id) },
                                onCancelDownload = { cancelDownload(item.id) },
                            )
                        }
                        items(statusRows, key = { it.first }) { row -> row.second() }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .fluePullToRefresh(pullRefreshState),
                        contentPadding = browseContentPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        gridItems(visibleEntries, key = { it.id }) { item ->
                            val transientState = downloadStates[item.id]
                                ?.collectAsStateWithLifecycle()
                                ?.value
                            WatchFaceGridCard(
                                repository = repository,
                                item = item,
                                downloadState = transientState ?: persistentInstallState(item.id),
                                onOpen = { openDetail(item.id) },
                                onDownload = { download(item.id) },
                                onCancelDownload = { cancelDownload(item.id) },
                            )
                        }
                        gridItems(
                            statusRows,
                            key = { it.first },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { row -> row.second() }
                    }
                }
            }
        }

        // 详情做成列表之上的覆层：列表始终保持在组合树里，返回时滚动位置
        // 不会丢；入场/退场用 pageMotionSpec 令牌做 fade + 0.96 scale。
        AnimatedVisibility(
            visible = selectedId != null,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            enter = fadeIn(animationSpec = style.pageMotionSpec) +
                scaleIn(initialScale = 0.96f, animationSpec = style.pageMotionSpec),
            exit = fadeOut(animationSpec = style.pageMotionSpec) +
                scaleOut(targetScale = 0.96f, animationSpec = style.pageMotionSpec),
        ) {
            val overlayId = selectedId ?: lastDetailId
            if (overlayId != null) {
                val transientState = downloadStates[overlayId]
                    ?.collectAsStateWithLifecycle()
                    ?.value
                WatchFaceDetailPage(
                    repository = repository,
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
                    onDownload = { download(overlayId) },
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
                .zIndex(2f),
            enter = fadeIn(animationSpec = style.pageMotionSpec) +
                scaleIn(initialScale = 0.96f, animationSpec = style.pageMotionSpec),
            exit = fadeOut(animationSpec = style.pageMotionSpec) +
                scaleOut(targetScale = 0.96f, animationSpec = style.pageMotionSpec),
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
                .zIndex(3f),
            enter = fadeIn(animationSpec = style.pageMotionSpec) +
                scaleIn(initialScale = 0.96f, animationSpec = style.pageMotionSpec),
            exit = fadeOut(animationSpec = style.pageMotionSpec) +
                scaleOut(targetScale = 0.96f, animationSpec = style.pageMotionSpec),
        ) {
            MyUploadsOverlay(
                repository = repository,
                onDismiss = { myUploadsVisible = false },
            )
        }
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
    val style = LauncherTheme.style
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = style.titleColor)
        }
        Column(Modifier.weight(1f)) {
            Text("表盘市场", color = style.titleColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Watchmaker Community", color = style.secondaryTextColor, fontSize = 10.sp)
        }
        IconButton(onClick = onMyUploads, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = "我的上传",
                tint = style.titleColor,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onUpload, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = "上传表盘",
                tint = style.titleColor,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onToggleViewMode, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (viewMode == WatchStoreViewMode.LIST) {
                    Icons.Default.GridView
                } else {
                    Icons.AutoMirrored.Filled.ViewList
                },
                contentDescription = if (viewMode == WatchStoreViewMode.LIST) "切换为宫格视图" else "切换为列表视图",
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
    categoriesLoading: Boolean,
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
        StorePill("最新", sort == WatchStoreSort.LATEST) { onSortChange(WatchStoreSort.LATEST) }
        StorePill("热门", sort == WatchStoreSort.POPULAR) { onSortChange(WatchStoreSort.POPULAR) }
        StorePill("全部", screenType == null) { onScreenTypeChange(null) }
        StorePill("圆屏", screenType == WatchStoreScreenType.ROUND) { onScreenTypeChange(WatchStoreScreenType.ROUND) }
        StorePill("方屏", screenType == WatchStoreScreenType.SQUARE) { onScreenTypeChange(WatchStoreScreenType.SQUARE) }
        StorePill("矩形", screenType == WatchStoreScreenType.RECTANGULAR) {
            onScreenTypeChange(WatchStoreScreenType.RECTANGULAR)
        }
        Box {
            StorePill(selectedCategory?.name ?: if (categoriesLoading) "分类…" else "分类", selectedCategory != null) {
                onCategoryMenuExpandedChange(true)
            }
            DropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { onCategoryMenuExpandedChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("全部分类") },
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
                        text = { Text("分类加载失败", color = WatchColors.ActiveRed) },
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
    iconPainterRes: Int? = null,
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
        if (iconPainterRes != null) {
            Icon(
                painter = painterResource(iconPainterRes),
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
                            "下载 ${downloadState.bytes * 100 / total}%"
                        } else "下载中"
                    }
                    is WatchStoreInstallState.Validating -> "校验中"
                    is WatchStoreInstallState.Installing -> "安装中"
                    else -> "处理中"
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
                    label = "取消",
                    color = style.secondaryTextColor,
                    fontSize = if (compact) 9.sp else 10.sp,
                    onClick = onCancelDownload,
                )
            }
        }
        is WatchStoreInstallState.Installed -> Text(
            downloadState.record.compatibilityReport?.let { report ->
                if (report.isFullyCompatible) "已安装" else "已安装 · ${report.issueCount} 项兼容性问题"
            } ?: "已安装",
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
                    label = "重试",
                    color = style.accentColor,
                    fontSize = 10.sp,
                    onClick = onDownload,
                    iconPainterRes = R.drawable.ic_flue_refresh_round,
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
                    label = "下载",
                    color = style.accentColor,
                    fontSize = if (compact) 10.sp else 11.sp,
                    onClick = onDownload,
                    iconPainterRes = R.drawable.ic_flue_download_round,
                    iconSize = if (compact) 12.dp else 14.dp,
                )
            }
        }
    }
}

@Composable
private fun WatchCompatibilityReportSection(report: WatchStoreCompatibilityReport) {
    val style = LauncherTheme.style
    val issueLines = buildList {
        if (report.unsupportedTags.isNotEmpty()) {
            add("未识别标签：${report.unsupportedTags.sorted().take(4).joinToString(", ")}")
        }
        if (report.unsupportedLayerTypes.isNotEmpty()) {
            add("未知图层：${report.unsupportedLayerTypes.sorted().take(4).joinToString(", ")}")
        }
        if (report.unsupportedExpressions.isNotEmpty()) {
            add("未支持表达式：${report.unsupportedExpressions.sorted().take(2).joinToString("；")}")
        }
        report.previewIssue?.let { issue ->
            add(
                when (issue) {
                    com.flue.launcher.watchface.store.WatchStorePreviewIssue.MISSING -> "预览图：包内缺少 preview.jpg"
                    com.flue.launcher.watchface.store.WatchStorePreviewIssue.EMPTY -> "预览图：文件为空"
                    com.flue.launcher.watchface.store.WatchStorePreviewIssue.UNSUPPORTED_ENCODING -> "预览图：格式无法识别"
                },
            )
        }
        report.inspectionError?.let { add("检查未完成：$it") }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.cardColor.copy(alpha = 0.72f), style.rowShape)
            .border(1.dp, style.outlineColor.copy(alpha = 0.38f), style.rowShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("兼容性报告", color = style.titleColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (issueLines.isEmpty()) {
            Text("此版本的图层、动态标签与预览图均已通过检查", color = style.positiveColor, fontSize = 11.sp)
        } else {
            issueLines.forEach { line ->
                Text(line, color = style.secondaryTextColor, fontSize = 11.sp, lineHeight = 15.sp)
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
                "${item.category.name} · ${screenTypeLabel(item.screenType)}",
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
                        "下载 ${item.downloadCount}",
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
) {
    val style = LauncherTheme.style
    val displayName = (downloadState as? WatchStoreInstallState.Installed)
        ?.record
        ?.resolvedDisplayName(item.name)
        ?: item.name
    val interactionSource = remember(item.id) { MutableInteractionSource() }
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
                        "下载 ${item.downloadCount}",
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
    val style = LauncherTheme.style
    val isScreenRound = isFlueScreenRound()
    val installedRecord = (downloadState as? WatchStoreInstallState.Installed)?.record
    val fallbackName = detail?.name ?: summary?.name ?: localDescriptor?.displayName
    val displayName = installedRecord?.resolvedDisplayName(fallbackName)
        ?: fallbackName
        ?: "已安装表盘"
    var editingName by remember(installedRecord?.logicalId, detail?.id) { mutableStateOf(false) }
    var nameText by remember(installedRecord?.logicalId, displayName) { mutableStateOf(displayName) }
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
        // Opaque background + explicit zIndex: the scroll column below carries
        // flueElasticNestedScroll's graphicsLayer translation, and during the
        // top overscroll bounce it was drawn straight over this header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(style.screenBackground),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = style.titleColor)
            }
            Text("表盘详情", color = style.titleColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        when {
            detail != null || installedRecord != null -> {
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
                    Column(
                        Modifier
                            .fillMaxSize()
                            .flueElasticNestedScroll(detailScrollState)
                            .verticalScroll(detailScrollState)
                            // 圆屏：全宽按钮和文字向内收，避开圆角裁切。
                            .padding(horizontal = if (isScreenRound) 6.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (detail != null) {
                            val previewRatio = when (detail.screenType) {
                                WatchStoreScreenType.RECTANGULAR -> 0.72f
                                else -> 1f
                            }
                            WatchPreview(
                                repository = repository,
                                id = detail.id,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .height(previewMaxHeight)
                                    .aspectRatio(previewRatio, matchHeightConstraintsFirst = true)
                                    .clip(
                                        if (detail.screenType == WatchStoreScreenType.ROUND) {
                                            style.chipShape
                                        } else {
                                            style.cardShape
                                        },
                                    ),
                                targetSize = 512,
                            )
                        } else {
                            LocalInstalledWatchPreview(
                                descriptor = localDescriptor,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .height(previewMaxHeight.coerceIn(140.dp, 260.dp))
                                    .aspectRatio(1f, matchHeightConstraintsFirst = true),
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
                                "${remoteDetail.category.name} · ${screenTypeLabel(remoteDetail.screenType)} · 下载 ${remoteDetail.downloadCount}",
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
                                "作者：${remoteDetail.uploader.username}",
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
                            )
                        }
                        if (error != null) {
                            Text("在线详情暂时不可用：$error", color = style.secondaryTextColor, fontSize = 11.sp)
                            TextButton(onClick = onRetryDetail) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_flue_refresh_round),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = style.accentColor,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("重试在线详情", color = style.accentColor)
                            }
                        }
                        when (downloadState) {
                            is WatchStoreInstallState.Downloading,
                            is WatchStoreInstallState.Validating,
                            is WatchStoreInstallState.Installing -> {
                                val label = when (downloadState) {
                                    is WatchStoreInstallState.Downloading -> {
                                        val total = downloadState.totalBytes
                                        if (total != null && total > 0L) "下载 ${downloadState.bytes * 100 / total}%" else "下载中…"
                                    }
                                    is WatchStoreInstallState.Validating -> "校验中…"
                                    is WatchStoreInstallState.Installing -> "安装中…"
                                    else -> "处理中…"
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
                                        Text("取消", color = style.secondaryTextColor, fontSize = 11.sp)
                                    }
                                }
                            }
                            is WatchStoreInstallState.Installed -> {
                                val record = downloadState.record
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_flue_check_round),
                                        contentDescription = null,
                                        tint = style.positiveColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text("已安装", color = style.positiveColor, fontSize = 12.sp)
                                }
                                 Text(
                                     "名称：${record.resolvedDisplayName(fallbackName) ?: displayName}",
                                     color = style.secondaryTextColor,
                                     fontSize = 11.sp,
                                 )
                                record.compatibilityReport?.let { report ->
                                    WatchCompatibilityReportSection(report)
                                }
                                // 点击后立刻反馈「已应用」并置灰，避免用户反复点。
                                var appliedThisVisit by remember(record.logicalId) { mutableStateOf(false) }
                                val storeContext = LocalContext.current
                                StorePrimaryButton(
                                    label = if (appliedThisVisit) "已应用" else "使用此表盘",
                                    enabled = !appliedThisVisit,
                                    onClick = {
                                        onUse(record)
                                        appliedThisVisit = true
                                        Toast.makeText(storeContext, "已应用该表盘", Toast.LENGTH_SHORT).show()
                                    },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreSecondaryButton(
                                        label = "编辑",
                                        onClick = { onEdit(record) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    StoreSecondaryButton(
                                        label = "导出",
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
                                        label = { Text("自定义名称", color = style.secondaryTextColor) },
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
                                            Text("取消", color = style.secondaryTextColor)
                                        }
                                        TextButton(onClick = { onRename(record, nameText); editingName = false }) {
                                            Text("保存", color = style.accentColor)
                                        }
                                    }
                                } else {
                                    TextButton(
                                        onClick = {
                                            nameText = record.resolvedDisplayName(fallbackName) ?: displayName
                                            editingName = true
                                        },
                                    ) {
                                        Text("重命名", color = style.accentColor)
                                    }
                                }
                                TextButton(onClick = { onDelete(record) }) {
                                    Text("删除已安装表盘", color = WatchColors.ActiveRed)
                                }
                                actionError?.let { Text(it, color = WatchColors.ActiveRed, fontSize = 11.sp) }
                            }
                            is WatchStoreInstallState.Failed -> {
                                Text(downloadState.message, color = WatchColors.ActiveRed, fontSize = 12.sp)
                                StorePrimaryButton(label = "重试", onClick = onDownload)
                            }
                            WatchStoreInstallState.NotInstalled -> StorePrimaryButton(
                                label = "下载并安装",
                                onClick = onDownload,
                                iconPainterRes = R.drawable.ic_flue_download_round,
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
                LoadingStateRow(if (loading) "正在加载表盘详情…" else "正在准备详情…")
            }
        }
    }
}

/**
 * 已上传表盘管理覆层。
 *
 * 从 GET /api/my/watchfaces 加载当前账号上传的全部表盘，支持删除（DELETE）。
 * 未登录时显示提示，无需重定向到登录流。
 */
@Composable
private fun MyUploadsOverlay(
    repository: WatchStoreRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()
    val api = remember { HttpWatchStoreApi() }
    val authStore = remember { WatchStoreAuthStore(context) }
    val session = remember { authStore.read() }

    var entries by remember { mutableStateOf<List<MyWatchFaceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(session != null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<MyWatchFaceEntry?>(null) }
    var deleteInProgress by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

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
            loadError = e.userFacingMessage()
        }
        loading = false
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.screenBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = style.titleColor)
                }
                Column(Modifier.weight(1f)) {
                    Text("我的上传", color = style.titleColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (session != null) {
                        Text(session.username, color = style.secondaryTextColor, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            when {
                session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = style.secondaryTextColor,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("请先登录后查看已上传的表盘", color = style.secondaryTextColor, fontSize = 14.sp)
                    }
                }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MiuixLoadingIndicator(
                        Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = style.accentColor,
                    )
                }
                loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = style.accentColor, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(loadError!!, color = style.bodyColor, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无上传记录", color = style.secondaryTextColor, fontSize = 14.sp)
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        MyUploadEntryRow(
                            entry = entry,
                            repository = repository,
                            onDeleteRequest = { deleteTarget = it },
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!deleteInProgress) { deleteTarget = null; deleteError = null } },
            title = { Text("删除确认", color = style.titleColor) },
            text = {
                Column {
                    Text("确定要删除「${target.name}」？", color = style.bodyColor)
                    Spacer(Modifier.height(4.dp))
                    Text("此操作无法撤销。", color = style.secondaryTextColor, fontSize = 12.sp)
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
                                deleteError = e.userFacingMessage()
                            }
                            deleteInProgress = false
                        }
                    },
                ) {
                    if (deleteInProgress) {
                        MiuixLoadingIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = style.accentColor)
                    } else {
                        Text("删除", color = style.accentColor)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteInProgress,
                    onClick = { deleteTarget = null; deleteError = null },
                ) { Text("取消", color = style.secondaryTextColor) }
            },
        )
    }
}

@Composable
private fun MyUploadEntryRow(
    entry: MyWatchFaceEntry,
    repository: WatchStoreRepository,
    onDeleteRequest: (MyWatchFaceEntry) -> Unit,
) {
    val style = LauncherTheme.style
    val previewSizePx = 56
    var bitmap by remember(entry.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.id) {
        runCatching {
            val bmp = withContext(Dispatchers.IO) { repository.loadPreview(entry.id, previewSizePx, previewSizePx) }
            bitmap = bmp.asImageBitmap()
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
            } else {
                Icon(
                    Icons.Default.Inventory2,
                    contentDescription = null,
                    tint = style.secondaryTextColor,
                    modifier = Modifier.size(24.dp),
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
                buildString {
                    append(entry.category.name)
                    append(" · ")
                    append(screenTypeLabel(entry.screenType))
                    append(" · ↓")
                    append(entry.downloadCount)
                },
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onDeleteRequest(entry) }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = style.secondaryTextColor,
                modifier = Modifier.size(18.dp),
            )
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
    var formError by remember { mutableStateOf<String?>(null) }
    var uploadPercent by remember { mutableStateOf<Int?>(null) }
    var selectedDescriptorId by remember { mutableStateOf<String?>(null) }
    var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
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
            val display = queryStoreUploadDisplayName(context, uri) ?: "本地表盘"
            pickedFileName = display
            if (name.isBlank()) {
                name = display.removeSuffix(".watch").ifBlank { "Flue Watch" }.take(100)
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
                categoriesError = watchStoreUploadErrorMessage(error)
            }
    }

    fun authenticate(register: Boolean) {
        if (busy != null) return
        val cleanUsername = username.trim()
        val inputError = watchStoreAuthInputError(cleanUsername, password)
        if (inputError != null) {
            formError = inputError
            return
        }
        busy = if (register) "正在注册" else "正在登录"
        formError = null
        scope.launch {
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
            }.onFailure { error ->
                if (error is CancellationException) throw error
                formError = watchStoreAuthErrorMessage(error, register)
            }
            busy = null
        }
    }

    fun upload() {
        if (busy != null) return
        val activeAuth = auth ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            formError = "请填写表盘名称"
            return
        }
        if (description.isBlank()) {
            formError = "请填写表盘描述"
            return
        }
        val categoryId = selectedCategoryId
        if (categoryId == null) {
            formError = "请选择一个分类"
            return
        }
        val descriptor = selectedDescriptor
        val fileUri = pickedFileUri
        if (descriptor == null && fileUri == null) {
            formError = "请先选择要上传的表盘"
            return
        }
        busy = "正在准备表盘包"
        formError = null
        uploadPercent = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("flue_store_upload_", ".watch", context.cacheDir)
                    try {
                        if (descriptor != null) {
                            WatchEditorSession.open(context, descriptor).use { session ->
                                session.exportWatch(archive)
                            }
                        } else {
                            copyPickedWatchArchive(context, requireNotNull(fileUri), archive)
                        }
                        if (archive.length() > HttpWatchStoreApi.MAX_UPLOAD_BYTES) {
                            throw WatchStorePayloadTooLargeException(HttpWatchStoreApi.MAX_UPLOAD_BYTES)
                        }
                        busy = "正在上传"
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
                Toast.makeText(context, "已上传到表盘商店", Toast.LENGTH_SHORT).show()
                onUploaded()
                onDismiss()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is WatchStoreHttpException && error.statusCode == 401) {
                    // 服务器判定令牌失效比本地过期时间更权威：清掉并回到登录页。
                    authStore.clear()
                    auth = null
                    formError = "登录已过期，请重新登录后再上传"
                } else {
                    formError = watchStoreUploadErrorMessage(error)
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
                Icon(Icons.Default.Clear, contentDescription = "关闭上传", tint = style.titleColor)
            }
            Text(
                "上传到商店",
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
                    "正在检查登录状态…",
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
                    enabled = busy == null,
                    onLogin = { authenticate(register = false) },
                    onRegister = { authenticate(register = true) },
                )
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已登录：${activeAuth.username}",
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
                        ) { Text("退出登录", color = style.accentColor) }
                    }
                    Text("上传来源", color = style.titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (sourceChosen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedDescriptor?.displayName ?: pickedFileName ?: "已选择表盘",
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
                            ) { Text("重新选择", color = style.accentColor) }
                        }
                    } else {
                        StoreSecondaryButton(
                            label = "选择本地 .watch 文件",
                            onClick = { if (busy == null) pickWatchFile.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (installedWatchmakerFaces.isEmpty()) {
                            Text(
                                "还没有可上传的已安装 .watch 表盘",
                                color = style.secondaryTextColor,
                                fontSize = 11.sp,
                            )
                        } else {
                            Text("或从已安装表盘选择：", color = style.secondaryTextColor, fontSize = 11.sp)
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
                            label = { Text("表盘名称（必填）", color = style.secondaryTextColor) },
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
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it.take(2000) },
                            label = { Text("描述（必填）", color = style.secondaryTextColor) },
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
                        Text("屏幕形状", color = style.titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            STORE_UPLOAD_SCREEN_TYPE_OPTIONS.forEach { (candidate, label) ->
                                StorePill(label, screenType == candidate) {
                                    if (busy == null) screenType = candidate
                                }
                            }
                        }
                        Text("分类", color = style.titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        when {
                            categoriesError != null -> {
                                Text(
                                    categoriesError.orEmpty(),
                                    color = WatchColors.ActiveRed,
                                    fontSize = 12.sp,
                                )
                                StoreSecondaryButton(
                                    label = "重新加载分类",
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
                                Text("正在加载分类…", color = style.secondaryTextColor, fontSize = 12.sp)
                            }
                            categories.orEmpty().isEmpty() -> Text(
                                "商店暂时没有可用分类，稍后再试。",
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
                                            painter = painterResource(R.drawable.ic_flue_check_round),
                                            contentDescription = null,
                                            tint = style.accentColor,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        StorePrimaryButton(
                            label = "上传此表盘",
                            onClick = { upload() },
                            enabled = busy == null &&
                                name.isNotBlank() &&
                                description.isNotBlank() &&
                                selectedCategoryId != null,
                        )
                        Text(
                            "已安装表盘会先导出为 .watch 再上传（上限 50MB）；" +
                                "预览图由商店从表盘包中自动生成。",
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
    context: android.content.Context,
    uri: Uri,
    destination: File,
) {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("无法读取所选文件")
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
    require(destination.length() > 0L) { "所选文件是空的" }
    destination.inputStream().use { stream ->
        val header = ByteArray(2)
        val read = stream.read(header)
        require(read == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            "所选文件不是有效的 .watch 表盘包"
        }
    }
}

private val STORE_UPLOAD_SCREEN_TYPE_OPTIONS = listOf(
    WatchStoreScreenType.ROUND to "圆形",
    WatchStoreScreenType.SQUARE to "方形",
    WatchStoreScreenType.RECTANGULAR to "长方形",
)

@Composable
private fun StorePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconPainterRes: Int? = null,
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
        if (iconPainterRes != null) {
            Icon(
                painter = painterResource(iconPainterRes),
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
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = WatchColors.ActiveRed,
            modifier = Modifier.size(26.dp),
        )
        Text("安装目录需要恢复", color = WatchColors.ActiveRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            diagnostic.message,
            color = style.secondaryTextColor,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        diagnostic.preservedCopyPath?.let { path ->
            Text("损坏副本已保留：${File(path).name}", color = style.secondaryTextColor, fontSize = 10.sp)
        }
        TextButton(onClick = onRescan) {
            Icon(
                painter = painterResource(R.drawable.ic_flue_refresh_round),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = style.accentColor,
            )
            Spacer(Modifier.width(4.dp))
            Text("重新扫描并恢复", color = style.accentColor)
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
            record.resolvedDisplayName(descriptor?.displayName) ?: "已安装表盘",
            color = style.titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text("已安装 · 可离线管理", color = style.positiveColor, fontSize = 11.sp)
    }
}

@Composable
private fun LocalInstalledWatchPreview(
    descriptor: WatchFaceDescriptor?,
    modifier: Modifier,
) {
    val style = LauncherTheme.style
    Box(
        modifier = modifier.clip(style.cardShape).background(style.cardColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            descriptor?.displayName ?: "本地表盘",
            color = style.secondaryTextColor,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(18.dp),
        )
    }
}

@Composable
private fun WatchPreview(
    repository: WatchStoreRepository,
    id: String,
    modifier: Modifier,
    targetSize: Int,
) {
    val style = LauncherTheme.style
    var retryToken by remember(repository, id, targetSize) { mutableIntStateOf(0) }
    var bitmap by remember(repository, id, targetSize) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(repository, id, targetSize) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, id, targetSize, retryToken) {
        bitmap = null
        loadError = null
        try {
            bitmap = repository.loadPreview(id, targetSize, targetSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.userFacingMessage()
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
    Box(modifier.background(style.pressedCardColor), contentAlignment = Alignment.Center) {
        // 220ms 交叉淡入：图片就绪后从加载态柔和过渡进来，替代原先的硬跳。
        Crossfade(
            targetState = previewState,
            animationSpec = tween(durationMillis = 220),
            label = "watchPreviewFade",
        ) { state ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    WatchPreviewState.READY -> image?.let { readyImage ->
                        Image(
                            readyImage,
                            contentDescription = "表盘预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    WatchPreviewState.FAILED -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        // 灰色占位图标：失败时不留白，一眼可辨预览没加载出来。
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = "预览加载失败",
                            tint = style.secondaryTextColor,
                            modifier = Modifier.size(18.dp),
                        )
                        StoreMiniAction(
                            label = "重试",
                            color = style.accentColor,
                            fontSize = 10.sp,
                            onClick = { retryToken++ },
                            iconPainterRes = R.drawable.ic_flue_refresh_round,
                        )
                    }
                    WatchPreviewState.LOADING -> MiuixLoadingIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = style.accentColor,
                    )
                }
            }
        }
    }
}

/** 预览图三态：Crossfade 的比较键，位图内容本身仍从闭包读取。 */
private enum class WatchPreviewState { LOADING, FAILED, READY }

/**
 * 下拉刷新指示器。外观走 style token，三套 UiStyle 各自成立。
 *
 * 未越阈值时画一个跟随进度收拢的箭头刷新图标，越过阈值或正在刷新时换成
 * MiuixLoadingIndicator，与商店里其它加载态同源。
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
        if (refreshing || state.willRefresh) {
            MiuixLoadingIndicator(
                Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = style.accentColor,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_flue_refresh_round),
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
            imageVector = Icons.Default.Warning,
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
                painter = painterResource(R.drawable.ic_flue_refresh_round),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = style.accentColor,
            )
            Spacer(Modifier.width(4.dp))
            Text("重试", color = style.accentColor)
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
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = style.secondaryTextColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            "没有找到表盘，换个关键词或筛选条件试试",
            color = style.secondaryTextColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun screenTypeLabel(type: WatchStoreScreenType): String = when (type) {
    WatchStoreScreenType.ROUND -> "圆屏"
    WatchStoreScreenType.SQUARE -> "方屏"
    WatchStoreScreenType.RECTANGULAR -> "矩形"
    WatchStoreScreenType.UNKNOWN -> "未知"
}

private fun Throwable.userFacingMessage(): String = when (this) {
    is WatchStoreException -> message ?: "表盘市场请求失败"
    else -> message?.takeIf { it.isNotBlank() }?.take(120) ?: "网络或表盘包校验失败"
}

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

private suspend fun exportInstalledWatchFace(
    context: android.content.Context,
    descriptor: WatchFaceDescriptor,
    destination: Uri,
): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        require(descriptor.isWatchmaker) { "只有标准 .watch 表盘可以导出" }
        val temporary = File.createTempFile("flue_watch_export_", ".watch", context.cacheDir)
        try {
            WatchEditorSession.open(context, descriptor).use { session ->
                session.exportWatch(temporary)
            }
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                temporary.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: error("无法写入导出位置")
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
