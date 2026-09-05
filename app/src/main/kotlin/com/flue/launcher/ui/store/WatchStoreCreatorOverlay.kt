package com.flue.launcher.ui.store

import com.flue.launcher.ui.store.WatchStoreBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.WatchPreview
import com.flue.launcher.throwIfCancellation
import com.flue.launcher.ui.style.FlueCard
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchStoreSocialErrorMessage
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreCreator
import com.flue.launcher.watchface.store.WatchStorePagination
import com.flue.launcher.watchface.store.WatchStoreRepository
import com.flue.launcher.watchface.store.WatchStoreWatchFace
import com.flue.launcher.watchface.store.appendCreatorWatchFacePage
import com.flue.launcher.watchface.store.nextCreatorWatchFacePage
import com.flue.launcher.watchface.store.TOP_CREATORS_PAGE_SIZE
import com.flue.launcher.watchface.store.nextTopCreatorsLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Share

class WatchStoreCreatorsSession {
    val topListState = LazyListState()
    var topCreators by mutableStateOf<List<WatchStoreCreator>>(emptyList())
    var topLoading by mutableStateOf(false)
    var topLoaded by mutableStateOf(false)
    var topLoadingMore by mutableStateOf(false)
    var topRefreshing by mutableStateOf(false)
    var topHasMore by mutableStateOf(true)
    var topError by mutableStateOf<String?>(null)
    private val creatorPages = mutableMapOf<String, WatchStoreCreatorPageSession>()

    fun creatorPage(username: String): WatchStoreCreatorPageSession =
        creatorPages.getOrPut(username) { WatchStoreCreatorPageSession() }
}

class WatchStoreCreatorPageSession {
    val listState = LazyListState()
    var creator by mutableStateOf<WatchStoreCreator?>(null)
    var faces by mutableStateOf<List<WatchStoreWatchFace>>(emptyList())
    var pagination by mutableStateOf<WatchStorePagination?>(null)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadMoreError by mutableStateOf<String?>(null)
}

/** H5：热门创作者列表与单个创作者页共用的覆层。 */
@Composable
fun WatchStoreCreatorsOverlay(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    initialUsername: String? = null,
    session: WatchStoreCreatorsSession = remember { WatchStoreCreatorsSession() },
    onOpenCreator: (String) -> Unit,
    onOpenWatchFace: (String, String) -> Unit,
    onOpenWebList: () -> Unit,
    onOpenWebListExternal: () -> Unit,
    onOpenWebCreator: (String) -> Unit,
    onOpenWebCreatorExternal: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun requestTopCreators(limit: Int): Result<List<WatchStoreCreator>> =
        runCatching { withContext(Dispatchers.IO) { api.getTopCreators(limit) } }

    LaunchedEffect(initialUsername) {
        if (initialUsername != null || session.topLoaded || session.topLoading) return@LaunchedEffect
        session.topLoading = true
        requestTopCreators(TOP_CREATORS_PAGE_SIZE)
            .onSuccess { incoming ->
                session.topCreators = incoming.distinctBy { it.username }
                session.topHasMore = incoming.size >= TOP_CREATORS_PAGE_SIZE
                session.topError = null
                session.topLoaded = true
            }
            .onFailure { failure ->
                failure.throwIfCancellation()
                session.topError = watchStoreSocialErrorMessage(context, failure)
            }
        session.topLoading = false
    }

    fun refreshTopCreators() {
        if (session.topLoading || session.topRefreshing || session.topLoadingMore) return
        session.topRefreshing = true
        scope.launch {
            requestTopCreators(TOP_CREATORS_PAGE_SIZE)
                .onSuccess { incoming ->
                    session.topCreators = incoming.distinctBy { it.username }
                    session.topHasMore = incoming.size >= TOP_CREATORS_PAGE_SIZE
                    session.topError = null
                    session.topLoaded = true
                }
                .onFailure { failure ->
                    failure.throwIfCancellation()
                    if (session.topCreators.isEmpty()) {
                        session.topError = watchStoreSocialErrorMessage(context, failure)
                    }
                }
            session.topRefreshing = false
        }
    }

    fun loadMoreTopCreators() {
        if (session.topLoadingMore || !session.topHasMore || session.topCreators.size < TOP_CREATORS_PAGE_SIZE) return
        session.topLoadingMore = true
        val requestedLimit = nextTopCreatorsLimit(session.topCreators.size)
        scope.launch {
            requestTopCreators(requestedLimit)
                .onSuccess { incoming ->
                    val merged = (session.topCreators + incoming).distinctBy { it.username }
                    session.topHasMore = incoming.size >= requestedLimit && merged.size > session.topCreators.size
                    session.topCreators = merged
                }
                .onFailure(Throwable::throwIfCancellation)
            session.topLoadingMore = false
        }
    }
    // 系统返回键必须与顶栏返回按钮同一判据（见下面 CreatorDetailPage 的 onBack）：
    // The host owns both creator routes; back always returns to the actual parent entry.
    BackHandler { onDismiss() }

    if (initialUsername == null) {
        CreatorListPage(
            repository = repository,
            creators = session.topCreators,
            loading = session.topLoading,
            loadingMore = session.topLoadingMore,
            refreshing = session.topRefreshing,
            error = session.topError,
            listState = session.topListState,
            onRefresh = ::refreshTopCreators,
            onLoadMore = ::loadMoreTopCreators,
            onSelect = { onOpenCreator(it.username) },
            onOpenWeb = onOpenWebList,
            onOpenWebExternal = onOpenWebListExternal,
            onDismiss = onDismiss,
        )
    } else {
        CreatorDetailPage(
            repository = repository,
            api = api,
            username = initialUsername,
            session = session.creatorPage(initialUsername),
            onOpenWatchFace = { id -> onOpenWatchFace(id, initialUsername) },
            onOpenWeb = { onOpenWebCreator(initialUsername) },
            onOpenWebExternal = { onOpenWebCreatorExternal(initialUsername) },
            onBack = onDismiss,
        )
    }
}

@Composable
private fun CreatorListPage(
    repository: WatchStoreRepository,
    creators: List<WatchStoreCreator>,
    loading: Boolean,
    loadingMore: Boolean,
    refreshing: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (WatchStoreCreator) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenWebExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    StoreLibraryScaffold(
        title = stringResource(R.string.watch_store_top_creators),
        subtitle = null,
        onBack = onDismiss,
        action = {
            CreatorWebActions(
                onOpenWeb = onOpenWeb,
                onOpenWebExternal = onOpenWebExternal,
            )
        },
        loading = loading,
        loadError = null,
    ) {
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面。
        StoreRoundList(
            state = listState,
            refreshing = refreshing,
            onRefresh = onRefresh,
            onNearEnd = onLoadMore,
        ) {
            if (creators.isEmpty()) {
                item("empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CreatorMessage(error ?: stringResource(R.string.watch_store_no_creators))
                        if (error != null) {
                            FlueOutlinedButton(
                                onClick = onRefresh,
                                enabled = !refreshing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.watch_store_retry))
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(creators, key = { _, creator -> creator.username }) { index, creator ->
                    CreatorRow(
                        repository = repository,
                        creator = creator,
                        rank = index + 1,
                        onClick = { onSelect(creator) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatorDetailPage(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    username: String,
    session: WatchStoreCreatorPageSession,
    onOpenWatchFace: (String) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenWebExternal: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(username, session) {
        if (session.loaded || session.loading) return@LaunchedEffect
        session.loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                api.getCreator(username) to api.listCreatorWatchFaces(username)
            }
        }.onSuccess { (loadedCreator, loadedPage) ->
            session.creator = loadedCreator
            session.faces = loadedPage.data
            session.pagination = loadedPage.pagination
            session.error = null
            session.loaded = true
        }.onFailure { failure ->
            failure.throwIfCancellation()
            session.error = watchStoreSocialErrorMessage(context, failure)
        }
        session.loading = false
    }

    fun loadMore() {
        val page = nextCreatorWatchFacePage(session.pagination) ?: return
        if (session.loadingMore) return
        session.loadingMore = true
        session.loadMoreError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.listCreatorWatchFaces(username = username, page = page)
                }
            }.onSuccess { loadedPage ->
                session.faces = appendCreatorWatchFacePage(session.faces, loadedPage.data)
                session.pagination = loadedPage.pagination
            }.onFailure { failure ->
                failure.throwIfCancellation()
                session.loadMoreError = watchStoreSocialErrorMessage(context, failure)
            }
            session.loadingMore = false
        }
    }

    StoreLibraryScaffold(
        title = session.creator?.username ?: username,
        subtitle = null,
        onBack = onBack,
        action = {
            CreatorWebActions(
                onOpenWeb = onOpenWeb,
                onOpenWebExternal = onOpenWebExternal,
            )
        },
        loading = session.loading || (!session.loaded && session.error == null),
        loadError = session.error,
    ) {
        val nextPage = nextCreatorWatchFacePage(session.pagination)
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面。
        StoreRoundList(state = session.listState) {
            session.creator?.let { loadedCreator ->
                item("creator_summary") { CreatorSummary(repository, loadedCreator) }
                item("creator_faces_title") {
                    Text(
                        stringResource(R.string.watch_store_creator_watchfaces),
                        color = LauncherTheme.style.titleColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            items(session.faces, key = { it.id }) { face ->
                CreatorFaceRow(
                    repository = repository,
                    face = face,
                    onClick = { onOpenWatchFace(face.id) },
                )
            }
            if (session.faces.isEmpty()) {
                item("empty") {
                    CreatorMessage(stringResource(R.string.watch_store_no_creator_faces))
                }
            }
            if (nextPage != null) {
                item("load_more_$nextPage") {
                    // This footer is only composed as it approaches the viewport, so reaching the
                    // end requests the next segment without exposing page-style controls.
                    LaunchedEffect(nextPage) { loadMore() }
                    if (session.loadMoreError == null) {
                        CreatorMessage(stringResource(R.string.watch_store_status_loading_more))
                    } else {
                        CreatorMessage(session.loadMoreError!!)
                        FlueOutlinedButton(
                            onClick = ::loadMore,
                            enabled = !session.loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.watch_store_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorRow(
    repository: WatchStoreRepository,
    creator: WatchStoreCreator,
    rank: Int,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    FlueCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        // 创作者信息卡不描边：深色档下那圈细白线是噪音（老师报的）。
        border = null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Text(
            rank.toString(),
            color = style.secondaryTextColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp),
        )
        Spacer(Modifier.width(4.dp))
        // The avatar itself is loaded by the shared store cache and falls back
        // to a stable initial when the server omits or rejects the image.
        StoreAvatar(
            repository = repository,
            avatarUrl = creator.avatarUrl,
            name = creator.username,
            size = 42.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(creator.username, color = style.titleColor, fontWeight = FontWeight.SemiBold)
            if (creator.description.isNotBlank()) {
                Text(
                    creator.description,
                    color = style.secondaryTextColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(
                    R.string.watch_store_creator_stats_fmt,
                    creator.watchFaceCount,
                    creator.totalDownloads,
                ),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}

@Composable
private fun CreatorSummary(
    repository: WatchStoreRepository,
    creator: WatchStoreCreator,
) {
    val style = LauncherTheme.style
    FlueCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        border = null,
    ) {
        Column(
            Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoreAvatar(
                repository = repository,
                avatarUrl = creator.avatarUrl,
                name = creator.username,
                size = 64.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    creator.username,
                    color = style.titleColor,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (creator.description.isNotBlank()) {
                    Text(
                        creator.description,
                        color = style.secondaryTextColor,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreatorStat(
                value = creator.totalDownloads.toString(),
                label = stringResource(R.string.watch_store_creator_downloads),
                modifier = Modifier.weight(1f),
            )
            CreatorStat(
                value = creator.watchFaceCount.toString(),
                label = stringResource(R.string.watch_store_creator_watchfaces),
                modifier = Modifier.weight(1f),
            )
        }
        }
    }
}

@Composable
private fun CreatorStat(value: String, label: String, modifier: Modifier = Modifier) {
    val style = LauncherTheme.style
    Column(
        modifier = modifier
            .clip(style.compactShape)
            .background(style.screenBackground.copy(alpha = 0.62f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = style.accentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = style.secondaryTextColor, fontSize = 10.sp)
    }
}

@Composable
private fun CreatorWebActions(onOpenWeb: () -> Unit, onOpenWebExternal: () -> Unit) {
    val style = LauncherTheme.style
    Row {
        IconButton(onClick = onOpenWeb, modifier = Modifier.size(38.dp)) {
            Icon(
                MiuixIcons.Link,
                contentDescription = stringResource(R.string.watch_store_web_open),
                tint = style.accentColor,
                modifier = Modifier.size(19.dp),
            )
        }
        IconButton(onClick = onOpenWebExternal, modifier = Modifier.size(38.dp)) {
            Icon(
                MiuixIcons.Share,
                contentDescription = stringResource(R.string.watch_store_web_open_external),
                tint = style.secondaryTextColor,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun CreatorFaceRow(
    repository: WatchStoreRepository,
    face: WatchStoreWatchFace,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.cardShape)
            .background(style.cardColor)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WatchPreview(
            repository = repository,
            id = face.id,
            modifier = Modifier.size(60.dp).clip(style.rowShape),
            targetSize = 96,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                face.name,
                color = style.titleColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.watch_store_download_count_fmt, face.downloadCount),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CreatorMessage(message: String) {
    Text(
        message,
        color = LauncherTheme.style.secondaryTextColor,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
    )
}
