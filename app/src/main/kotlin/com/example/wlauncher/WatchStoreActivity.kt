package com.flue.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.R
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.fluePressScale
import com.flue.launcher.ui.common.rememberFluePressScale
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.standard.WatchInstallMetadata
import com.flue.launcher.watchface.store.WatchStoreCategory
import com.flue.launcher.watchface.store.WatchStoreException
import com.flue.launcher.watchface.store.WatchStorePagination
import com.flue.launcher.watchface.store.WatchStoreQuery
import com.flue.launcher.watchface.store.WatchStoreRepository
import com.flue.launcher.watchface.store.WatchStoreScreenType
import com.flue.launcher.watchface.store.WatchStoreSort
import com.flue.launcher.watchface.store.WatchStoreWatchFace
import com.flue.launcher.watchface.store.WatchStoreWatchFaceDetail
import com.flue.launcher.watchface.store.WatchStoreInstallState
import com.flue.launcher.watchface.store.WatchStoreInstallTransactionException
import com.flue.launcher.watchface.store.commitMarketInstallCatalog
import com.flue.launcher.watchface.store.catalog.WATCHMAKER_COMMUNITY_STORE_ID
import com.flue.launcher.watchface.store.catalog.WatchFaceCatalogDiagnostic
import com.flue.launcher.watchface.store.catalog.WatchFaceCatalogSnapshot
import com.flue.launcher.watchface.store.catalog.WatchFaceInstallSource
import com.flue.launcher.watchface.store.catalog.WatchFaceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Public Watchmaker community store.  The Activity deliberately owns only the
 * coarse screen state; HTTP, archive validation and bitmap decoding stay in
 * [WatchStoreRepository] (which switches to Dispatchers.IO).
 */
class WatchStoreActivity : ComponentActivity() {
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
        val isScreenRound = resources.configuration.isScreenRound
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
                        reconcileToken = resumeToken,
                        isScreenRound = isScreenRound,
                        onBack = ::finish,
                        installArchive = { archive ->
                            launcherViewModel.installWatchStoreArchive(archive).map { descriptor ->
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
                                )
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
                        openDisplaySettings = {
                            startActivity(
                                Intent(this, SettingsActivity::class.java)
                                    .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES),
                            )
                        },
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
    reconcileToken: Int,
    isScreenRound: Boolean,
    onBack: () -> Unit,
    installArchive: suspend (java.io.File) -> Result<InstalledStoreWatchFace>,
    useInstalled: (launcherId: String) -> Unit,
    editInstalled: (launcherId: String) -> Unit,
    exportInstalled: (launcherId: String) -> Unit,
    deleteInstalled: suspend (launcherIds: Collection<String>) -> Result<Unit>,
    openDisplaySettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sort by remember { mutableStateOf(WatchStoreSort.LATEST) }
    var screenType by remember { mutableStateOf<WatchStoreScreenType?>(null) }
    var selectedCategory by remember { mutableStateOf<WatchStoreCategory?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var committedSearch by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<WatchStoreCategory>>(emptyList()) }
    var categoriesLoading by remember { mutableStateOf(true) }
    var categoriesError by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WatchStoreWatchFace>>(emptyList()) }
    var pagination by remember { mutableStateOf<WatchStorePagination?>(null) }
    var listLoading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf(WatchFaceCatalogSnapshot()) }
    var catalogReady by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogWarning by remember { mutableStateOf<String?>(null) }
    var catalogReloadToken by remember { mutableIntStateOf(0) }
    var repairCatalogOnNextRestore by remember { mutableStateOf(false) }
    var installedFaces by remember { mutableStateOf<List<LunchWatchFaceDescriptor>>(emptyList()) }
    var lastLegacyAssociationSignature by remember { mutableStateOf<String?>(null) }
    val installedFacesById = remember(installedFaces) { installedFaces.associateBy { it.id } }
    val legacyAssociationSignature = remember(installedFaces) {
        installedFaces
            .sortedBy(LunchWatchFaceDescriptor::id)
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

    suspend fun cleanObsoleteMarketRevisions(
        snapshot: WatchFaceCatalogSnapshot,
        scannedFaces: List<LunchWatchFaceDescriptor>,
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

    LaunchedEffect(repository, reconcileToken, catalogReloadToken) {
        catalogReady = false
        catalogError = null
        catalogWarning = null
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
            catalogReady = true
        }
    }

    LaunchedEffect(
        repository,
        catalogReady,
        catalog.diagnostic,
        installedFaces,
        reconcileToken,
        catalogReloadToken,
    ) {
        if (
            !catalogReady ||
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

    LaunchedEffect(repository) {
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
        }
    }

    // Paging is driven by the lazy list's visible index, not by recomposition
    // of every card.  The repository performs the actual request off-main.
    LaunchedEffect(listState, queryKey, reloadToken) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val lastIndex = visible.lastOrNull()?.index ?: -1
            lastIndex >= (entries.size - 3).coerceAtLeast(0)
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

    var selectedDetail by remember { mutableStateOf<WatchStoreWatchFaceDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailReloadToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedId, detailReloadToken) {
        val id = selectedId ?: run {
            selectedDetail = null
            return@LaunchedEffect
        }
        detailLoading = true
        detailError = null
        selectedDetail = null
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

    val selectedRemoteId = selectedId
    if (selectedRemoteId != null) {
        val transientState = downloadStates[selectedRemoteId]
            ?.collectAsStateWithLifecycle()
            ?.value
        WatchFaceDetailPage(
            repository = repository,
            detail = selectedDetail,
            summary = entries.firstOrNull { it.id == selectedRemoteId },
            localDescriptor = (transientState as? WatchStoreInstallState.Installed)?.record
                ?.activeLauncherIdOrNull()
                ?.let(installedFacesById::get)
                ?: installedMarketRecords[selectedRemoteId]
                    ?.activeLauncherIdOrNull()
                    ?.let(installedFacesById::get),
            loading = detailLoading,
            error = detailError,
            downloadState = transientState ?: persistentInstallState(selectedRemoteId),
            actionError = installedActionError,
            isScreenRound = isScreenRound,
            onBack = {
                selectedId = null
                installedActionError = null
            },
            onRetryDetail = { detailReloadToken++ },
            onDownload = { download(selectedRemoteId) },
            onCancelDownload = { cancelDownload(selectedRemoteId) },
            onUse = { record -> record.activeLauncherIdOrNull()?.let(useInstalled) },
            onEdit = { record -> record.activeLauncherIdOrNull()?.let(editInstalled) },
            onRename = { record, name -> renameInstalled(selectedRemoteId, record, name) },
            onDelete = { record -> removeInstalled(selectedRemoteId, record) },
            onExport = { record -> record.activeLauncherIdOrNull()?.let(exportInstalled) },
            onDisplaySettings = openDisplaySettings,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background)
            .padding(
                horizontal = if (isScreenRound) 16.dp else 10.dp,
                vertical = if (isScreenRound) 10.dp else 6.dp,
            ),
    ) {
        StoreHeader(onBack = onBack)
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it.take(200) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索表盘", color = WatchColors.TextTertiary, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索", tint = WatchColors.TextSecondary) },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { searchText = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空", tint = WatchColors.TextSecondary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WatchColors.ActiveCyan,
                unfocusedBorderColor = WatchColors.SurfaceGlass,
                focusedContainerColor = WatchColors.SurfaceGlass,
                unfocusedContainerColor = WatchColors.SurfaceGlass,
                cursorColor = WatchColors.ActiveCyan,
                focusedTextColor = WatchColors.OnBackground,
                unfocusedTextColor = WatchColors.OnBackground,
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .flueElasticNestedScroll(listState),
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    if (catalogReady && catalogError == null && catalog.diagnostic == null) entries else emptyList(),
                    key = { it.id },
                ) { item ->
                    val transientState = downloadStates[item.id]
                        ?.collectAsStateWithLifecycle()
                        ?.value
                    WatchFaceCard(
                        repository = repository,
                        item = item,
                        downloadState = transientState ?: persistentInstallState(item.id),
                        onOpen = { selectedId = item.id },
                        onDownload = { download(item.id) },
                        onCancelDownload = { cancelDownload(item.id) },
                    )
                }
                if (!catalogReady) item { LoadingRow("正在恢复安装状态…") }
                if (catalogReady && catalogError != null) {
                    item { ErrorRow(message = catalogError!!, onRetry = { catalogReloadToken++ }) }
                }
                if (catalogReady && catalogError == null && catalogWarning != null) {
                    item("catalog_scan_warning") {
                        ErrorRow(message = catalogWarning!!, onRetry = { catalogReloadToken++ })
                    }
                }
                catalog.diagnostic?.let { diagnostic ->
                    item("catalog_recovery") {
                        CatalogRecoveryRow(
                            diagnostic = diagnostic,
                            onRescan = {
                                repairCatalogOnNextRestore = true
                                catalogReloadToken++
                            },
                        )
                    }
                }
                if (
                    catalogReady &&
                    catalogError == null &&
                    catalog.diagnostic == null &&
                    entries.isEmpty() &&
                    listError != null &&
                    localMarketRecords.isNotEmpty()
                ) {
                    item("offline_installed_header") {
                        Text(
                            "已安装表盘",
                            color = WatchColors.OnBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                    items(localMarketRecords, key = { "offline_${it.logicalId}" }) { record ->
                        val source = record.source as WatchFaceInstallSource.Market
                        LocalInstalledWatchFaceCard(
                            record = record,
                            descriptor = record.activeLauncherIdOrNull()?.let(installedFacesById::get),
                            onOpen = { selectedId = source.remoteId },
                        )
                    }
                }
                if (catalog.diagnostic == null && listLoading && entries.isEmpty()) {
                    item { LoadingRow("正在加载表盘…") }
                } else if (catalog.diagnostic == null && listError != null && entries.isEmpty()) {
                    item {
                        ErrorRow(
                            message = listError!!,
                            onRetry = { reloadToken++ },
                        )
                    }
                } else if (catalog.diagnostic == null && !listLoading && !loadingMore && entries.isEmpty()) {
                    item { EmptyRow() }
                }
                if (catalog.diagnostic == null && loadingMore) item { LoadingRow("加载更多…") }
                if (catalog.diagnostic == null && listError != null && entries.isNotEmpty()) {
                    item {
                        ErrorRow(
                            message = listError!!,
                            onRetry = { reloadToken++ },
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun StoreHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = WatchColors.OnBackground)
        }
        Column(Modifier.weight(1f)) {
            Text("表盘市场", color = WatchColors.OnBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Watchmaker Community", color = WatchColors.TextTertiary, fontSize = 10.sp)
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
    val shape = RoundedCornerShape(50)
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource, pressedScale = 0.94f)
    Text(
        text = label,
        modifier = Modifier
            .fluePressScale(pressScale)
            .clip(shape)
            .background(if (selected) WatchColors.ActiveCyan.copy(alpha = 0.22f) else WatchColors.SurfaceGlass)
            .border(1.dp, if (selected) WatchColors.ActiveCyan.copy(alpha = 0.65f) else Color.Transparent, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) WatchColors.ActiveCyan else WatchColors.TextSecondary,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

@Composable
private fun WatchFaceCard(
    repository: WatchStoreRepository,
    item: WatchStoreWatchFace,
    downloadState: WatchStoreInstallState,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val displayName = (downloadState as? WatchStoreInstallState.Installed)
        ?.record
        ?.resolvedDisplayName(item.name)
        ?: item.name
    val previewRatio = when (item.screenType) {
        WatchStoreScreenType.ROUND, WatchStoreScreenType.SQUARE, WatchStoreScreenType.UNKNOWN -> 1f
        WatchStoreScreenType.RECTANGULAR -> 0.72f
    }
    val previewShape = if (item.screenType == WatchStoreScreenType.ROUND) CircleShape else RoundedCornerShape(12.dp)
    val interactionSource = remember(item.id) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WatchColors.SurfaceGlass),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WatchPreview(
                repository = repository,
                id = item.id,
                modifier = Modifier.width(78.dp).aspectRatio(previewRatio).clip(previewShape),
                targetSize = 256,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayName, color = WatchColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.category.name} · ${screenTypeLabel(item.screenType)}",
                    color = WatchColors.TextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("下载 ${item.downloadCount}", color = WatchColors.TextTertiary, fontSize = 10.sp)
                when (downloadState) {
                    is WatchStoreInstallState.Downloading,
                    is WatchStoreInstallState.Validating,
                    is WatchStoreInstallState.Installing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = WatchColors.ActiveCyan)
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
                            Text(label, color = WatchColors.ActiveCyan, fontSize = 10.sp)
                            Spacer(Modifier.width(3.dp))
                            TextButton(
                                onClick = onCancelDownload,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp),
                            ) {
                                Text("取消", color = WatchColors.TextSecondary, fontSize = 9.sp)
                            }
                        }
                    }
                    is WatchStoreInstallState.Installed -> Text("已安装", color = WatchColors.ActiveGreen, fontSize = 10.sp)
                    is WatchStoreInstallState.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(downloadState.message, color = WatchColors.ActiveRed, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDownload, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_flue_refresh_round),
                                    contentDescription = "重试",
                                    modifier = Modifier.size(14.dp),
                                    tint = WatchColors.ActiveCyan,
                                )
                                Text("重试", color = WatchColors.ActiveCyan, fontSize = 10.sp)
                            }
                        }
                    }
                    WatchStoreInstallState.NotInstalled -> {
                        TextButton(
                            onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_flue_download_round),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = WatchColors.ActiveCyan,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("下载", color = WatchColors.ActiveCyan, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchFaceDetailPage(
    repository: WatchStoreRepository,
    detail: WatchStoreWatchFaceDetail?,
    summary: WatchStoreWatchFace?,
    localDescriptor: LunchWatchFaceDescriptor?,
    loading: Boolean,
    error: String?,
    downloadState: WatchStoreInstallState,
    actionError: String?,
    isScreenRound: Boolean,
    onBack: () -> Unit,
    onRetryDetail: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onUse: (WatchFaceRecord) -> Unit,
    onEdit: (WatchFaceRecord) -> Unit,
    onRename: (WatchFaceRecord, String) -> Unit,
    onDelete: (WatchFaceRecord) -> Unit,
    onExport: (WatchFaceRecord) -> Unit,
    onDisplaySettings: () -> Unit,
) {
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
            .background(WatchColors.Background)
            .padding(
                horizontal = if (isScreenRound) 16.dp else 10.dp,
                vertical = if (isScreenRound) 10.dp else 6.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = WatchColors.OnBackground)
            }
            Text("表盘详情", color = WatchColors.OnBackground, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        when {
            loading && installedRecord == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WatchColors.ActiveCyan)
            }
            error != null && installedRecord == null -> ErrorRow(message = error, onRetry = onRetryDetail)
            detail != null || installedRecord != null -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .flueElasticNestedScroll(detailScrollState)
                        .verticalScroll(detailScrollState),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (detail != null) {
                        WatchPreview(
                            repository = repository,
                            id = detail.id,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).aspectRatio(
                                when (detail.screenType) {
                                    WatchStoreScreenType.RECTANGULAR -> 0.72f
                                    else -> 1f
                                },
                            ).clip(if (detail.screenType == WatchStoreScreenType.ROUND) CircleShape else RoundedCornerShape(18.dp)),
                            targetSize = 512,
                        )
                    } else {
                        LocalInstalledWatchPreview(
                            descriptor = localDescriptor,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp).aspectRatio(1f),
                        )
                    }
                    Text(displayName, color = WatchColors.OnBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    detail?.let { remoteDetail ->
                        Text(
                            "${remoteDetail.category.name} · ${screenTypeLabel(remoteDetail.screenType)} · 下载 ${remoteDetail.downloadCount}",
                            color = WatchColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                        if (remoteDetail.description.isNotBlank()) {
                            Text(remoteDetail.description, color = WatchColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Text("作者：${remoteDetail.uploader.username}", color = WatchColors.TextTertiary, fontSize = 11.sp)
                    }
                    if (error != null) {
                        Text("在线详情暂时不可用：$error", color = WatchColors.TextSecondary, fontSize = 11.sp)
                        TextButton(onClick = onRetryDetail) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_flue_refresh_round),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            Spacer(Modifier.width(4.dp))
                            Text("重试在线详情")
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
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WatchColors.ActiveCyan)
                                Spacer(Modifier.width(7.dp))
                                Text(label, color = WatchColors.ActiveCyan, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onCancelDownload) {
                                    Text("取消", color = WatchColors.TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                        is WatchStoreInstallState.Installed -> {
                            val record = downloadState.record
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_flue_check_round),
                                    contentDescription = null,
                                    tint = WatchColors.ActiveGreen,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("已安装", color = WatchColors.ActiveGreen, fontSize = 12.sp)
                            }
                            Text("名称：${record.resolvedDisplayName(fallbackName) ?: displayName}", color = WatchColors.TextSecondary, fontSize = 12.sp)
                            Button(onClick = { onUse(record) }, modifier = Modifier.fillMaxWidth()) { Text("使用此表盘") }
                            Button(onClick = { onEdit(record) }, modifier = Modifier.fillMaxWidth()) { Text("编辑") }
                            Button(onClick = { onExport(record) }, modifier = Modifier.fillMaxWidth()) { Text("导出") }
                            Button(onClick = onDisplaySettings, modifier = Modifier.fillMaxWidth()) { Text("显示设置") }
                            if (editingName) {
                                OutlinedTextField(
                                    value = nameText,
                                    onValueChange = { nameText = it.take(512) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("自定义名称") },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editingName = false }) { Text("取消") }
                                    TextButton(onClick = { onRename(record, nameText); editingName = false }) { Text("保存") }
                                }
                            } else {
                                TextButton(onClick = { nameText = record.resolvedDisplayName(fallbackName) ?: displayName; editingName = true }) {
                                    Text("重命名")
                                }
                            }
                            TextButton(onClick = { onDelete(record) }) {
                                Text("删除已安装表盘", color = WatchColors.ActiveRed)
                            }
                            actionError?.let { Text(it, color = WatchColors.ActiveRed, fontSize = 11.sp) }
                        }
                        is WatchStoreInstallState.Failed -> {
                            Text(downloadState.message, color = WatchColors.ActiveRed, fontSize = 12.sp)
                            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                        }
                        WatchStoreInstallState.NotInstalled -> Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WatchColors.ActiveCyan, contentColor = Color.Black),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_flue_download_round),
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("下载并安装")
                        }
                    }
                }
            }
            else -> ErrorRow(message = "无法读取表盘详情", onRetry = onRetryDetail)
        }
    }
}

@Composable
private fun CatalogRecoveryRow(
    diagnostic: WatchFaceCatalogDiagnostic,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("安装目录需要恢复", color = WatchColors.ActiveRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            diagnostic.message,
            color = WatchColors.TextSecondary,
            fontSize = 11.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        diagnostic.preservedCopyPath?.let { path ->
            Text("损坏副本已保留：${File(path).name}", color = WatchColors.TextTertiary, fontSize = 10.sp)
        }
        TextButton(onClick = onRescan) {
            Icon(
                painter = painterResource(R.drawable.ic_flue_refresh_round),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("重新扫描并恢复")
        }
    }
}

@Composable
private fun LocalInstalledWatchFaceCard(
    record: WatchFaceRecord,
    descriptor: LunchWatchFaceDescriptor?,
    onOpen: () -> Unit,
) {
    val interactionSource = remember(record.logicalId) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WatchColors.SurfaceGlass),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                record.resolvedDisplayName(descriptor?.displayName) ?: "已安装表盘",
                color = WatchColors.OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("已安装 · 可离线管理", color = WatchColors.ActiveGreen, fontSize = 10.sp)
        }
    }
}

@Composable
private fun LocalInstalledWatchPreview(
    descriptor: LunchWatchFaceDescriptor?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(WatchColors.SurfaceGlass),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            descriptor?.displayName ?: "本地表盘",
            color = WatchColors.TextSecondary,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
    Box(modifier.background(WatchColors.SurfaceGlass), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = "表盘预览", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (loadError != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                Text("预览加载失败", color = WatchColors.TextTertiary, fontSize = 10.sp)
                TextButton(
                    onClick = { retryToken++ },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_flue_refresh_round),
                        contentDescription = "重试预览",
                        modifier = Modifier.size(14.dp),
                        tint = WatchColors.ActiveCyan,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("重试", color = WatchColors.ActiveCyan, fontSize = 10.sp)
                }
            }
        } else {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = WatchColors.TextTertiary)
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WatchColors.ActiveCyan)
        Spacer(Modifier.width(8.dp))
        Text(label, color = WatchColors.TextTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(message, color = WatchColors.ActiveRed, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onRetry) {
            Icon(
                painter = painterResource(R.drawable.ic_flue_refresh_round),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = WatchColors.ActiveCyan,
            )
            Spacer(Modifier.width(4.dp))
            Text("重试", color = WatchColors.ActiveCyan)
        }
    }
}

@Composable
private fun EmptyRow() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("没有找到表盘", color = WatchColors.TextSecondary, fontSize = 14.sp)
        Text("换个关键词或筛选条件试试", color = WatchColors.TextTertiary, fontSize = 11.sp)
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
    descriptor: LunchWatchFaceDescriptor,
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
