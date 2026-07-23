package com.flue.launcher

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.store.WatchStoreCategory
import com.flue.launcher.watchface.store.WatchStoreException
import com.flue.launcher.watchface.store.WatchStorePagination
import com.flue.launcher.watchface.store.WatchStoreQuery
import com.flue.launcher.watchface.store.WatchStoreRepository
import com.flue.launcher.watchface.store.WatchStoreScreenType
import com.flue.launcher.watchface.store.WatchStoreSort
import com.flue.launcher.watchface.store.WatchStoreWatchFace
import com.flue.launcher.watchface.store.WatchStoreWatchFaceDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Public Watchmaker community store.  The Activity deliberately owns only the
 * coarse screen state; HTTP, archive validation and bitmap decoding stay in
 * [WatchStoreRepository] (which switches to Dispatchers.IO).
 */
class WatchStoreActivity : ComponentActivity() {
    private val launcherViewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        val repository = WatchStoreRepository(this)
        val isScreenRound = resources.configuration.isScreenRound
        setContent {
            val themeMode by launcherViewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by launcherViewModel.uiStyle.collectAsStateWithLifecycle()
            val uiScale by launcherViewModel.globalUiScalePercent.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) {
                ProvideGlobalUiScale(uiScale) {
                    WatchStoreScreen(
                        repository = repository,
                        isScreenRound = isScreenRound,
                        onBack = ::finish,
                        installArchive = { archive ->
                            launcherViewModel.installWatchStoreArchive(archive).map { descriptor ->
                                InstalledStoreWatchFace(
                                    id = descriptor.id,
                                    name = descriptor.displayName,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private sealed interface StoreDownloadState {
    data object Downloading : StoreDownloadState
    data class Installed(val name: String) : StoreDownloadState
    data class Failed(val message: String) : StoreDownloadState
}

private data class InstalledStoreWatchFace(
    val id: String,
    val name: String,
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
    isScreenRound: Boolean,
    onBack: () -> Unit,
    installArchive: suspend (java.io.File) -> Result<InstalledStoreWatchFace>,
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
    val downloadStates = remember { mutableStateMapOf<String, StoreDownloadState>() }

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

    BackHandler(onBack = onBack)

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
        if (downloadStates[id] is StoreDownloadState.Downloading) return
        downloadStates[id] = StoreDownloadState.Downloading
        scope.launch {
            try {
                val archive = repository.downloadWatchFace(id)
                val installed = try {
                    installArchive(archive).getOrThrow()
                } finally {
                    withContext(Dispatchers.IO + NonCancellable) { archive.delete() }
                }
                downloadStates[id] = StoreDownloadState.Installed(installed.name)
            } catch (error: Throwable) {
                error.throwIfCancellation()
                downloadStates[id] = StoreDownloadState.Failed(error.userFacingMessage())
            }
        }
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { item ->
                    WatchFaceCard(
                        repository = repository,
                        item = item,
                        downloadState = downloadStates[item.id],
                        onOpen = { selectedId = item.id },
                        onDownload = { download(item.id) },
                    )
                }
                if (listLoading && entries.isEmpty()) {
                    item { LoadingRow("正在加载表盘…") }
                } else if (listError != null && entries.isEmpty()) {
                    item {
                        ErrorRow(
                            message = listError!!,
                            onRetry = { reloadToken++ },
                        )
                    }
                } else if (!listLoading && !loadingMore && entries.isEmpty()) {
                    item { EmptyRow() }
                }
                if (loadingMore) item { LoadingRow("加载更多…") }
                if (listError != null && entries.isNotEmpty()) {
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

    if (selectedId != null) {
        WatchFaceDetailDialog(
            repository = repository,
            detail = selectedDetail,
            loading = detailLoading,
            error = detailError,
            downloadState = downloadStates[selectedId!!],
            onDismiss = { selectedId = null },
            onRetry = { detailReloadToken++ },
            onDownload = { download(selectedId!!) },
        )
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
    Text(
        text = label,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) WatchColors.ActiveCyan.copy(alpha = 0.22f) else WatchColors.SurfaceGlass)
            .border(1.dp, if (selected) WatchColors.ActiveCyan.copy(alpha = 0.65f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
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
    downloadState: StoreDownloadState?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val previewRatio = when (item.screenType) {
        WatchStoreScreenType.ROUND, WatchStoreScreenType.SQUARE, WatchStoreScreenType.UNKNOWN -> 1f
        WatchStoreScreenType.RECTANGULAR -> 0.72f
    }
    val previewShape = if (item.screenType == WatchStoreScreenType.ROUND) CircleShape else RoundedCornerShape(12.dp)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
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
                Text(item.name, color = WatchColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.category.name} · ${screenTypeLabel(item.screenType)}",
                    color = WatchColors.TextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("下载 ${item.downloadCount}", color = WatchColors.TextTertiary, fontSize = 10.sp)
                when (downloadState) {
                    StoreDownloadState.Downloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = WatchColors.ActiveCyan)
                            Spacer(Modifier.width(5.dp))
                            Text("安装中", color = WatchColors.ActiveCyan, fontSize = 10.sp)
                        }
                    }
                    is StoreDownloadState.Installed -> Text("已安装", color = WatchColors.ActiveGreen, fontSize = 10.sp)
                    is StoreDownloadState.Failed -> Text(downloadState.message, color = WatchColors.ActiveRed, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    null -> {
                        TextButton(
                            onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(15.dp), tint = WatchColors.ActiveCyan)
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
private fun WatchFaceDetailDialog(
    repository: WatchStoreRepository,
    detail: WatchStoreWatchFaceDetail?,
    loading: Boolean,
    error: String?,
    downloadState: StoreDownloadState?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp),
            shape = RoundedCornerShape(22.dp),
            color = WatchColors.Background,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.fillMaxHeight().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("表盘详情", color = WatchColors.OnBackground, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Clear, contentDescription = "关闭", tint = WatchColors.TextSecondary)
                    }
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = WatchColors.ActiveCyan)
                    }
                    error != null -> ErrorRow(message = error, onRetry = onRetry)
                    detail != null -> {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            WatchPreview(
                                repository = repository,
                                id = detail.id,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(
                                    when (detail.screenType) {
                                        WatchStoreScreenType.RECTANGULAR -> 0.72f
                                        else -> 1f
                                    },
                                ).clip(if (detail.screenType == WatchStoreScreenType.ROUND) CircleShape else RoundedCornerShape(18.dp)),
                                targetSize = 512,
                            )
                            Text(detail.name, color = WatchColors.OnBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${detail.category.name} · ${screenTypeLabel(detail.screenType)} · 下载 ${detail.downloadCount}",
                                color = WatchColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                            if (detail.description.isNotBlank()) {
                                Text(detail.description, color = WatchColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            Text("作者：${detail.uploader.username}", color = WatchColors.TextTertiary, fontSize = 11.sp)
                            when (downloadState) {
                                StoreDownloadState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WatchColors.ActiveCyan)
                                    Spacer(Modifier.width(7.dp))
                                    Text("下载并安装中…", color = WatchColors.ActiveCyan, fontSize = 12.sp)
                                }
                                is StoreDownloadState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = WatchColors.ActiveGreen, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("已安装：${downloadState.name}", color = WatchColors.ActiveGreen, fontSize = 12.sp)
                                }
                                is StoreDownloadState.Failed -> Text(downloadState.message, color = WatchColors.ActiveRed, fontSize = 12.sp)
                                null -> Button(
                                    onClick = onDownload,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = WatchColors.ActiveCyan, contentColor = Color.Black),
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    Spacer(Modifier.width(7.dp))
                                    Text("下载并安装")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchPreview(
    repository: WatchStoreRepository,
    id: String,
    modifier: Modifier,
    targetSize: Int,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = repository, key2 = id, key3 = targetSize) {
        value = try {
            repository.loadPreview(id, targetSize, targetSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(modifier.background(WatchColors.SurfaceGlass), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = "表盘预览", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = WatchColors.ActiveCyan)
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
