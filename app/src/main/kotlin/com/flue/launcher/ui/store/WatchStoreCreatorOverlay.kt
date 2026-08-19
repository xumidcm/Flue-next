package com.flue.launcher.ui.store

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Share

/** H5：热门创作者列表与单个创作者页共用的覆层。 */
@Composable
fun WatchStoreCreatorsOverlay(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    initialUsername: String? = null,
    onOpenWatchFace: (String) -> Unit,
    onOpenWebList: () -> Unit,
    onOpenWebListExternal: () -> Unit,
    onOpenWebCreator: (String) -> Unit,
    onOpenWebCreatorExternal: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedUsername by remember(initialUsername) { mutableStateOf(initialUsername) }
    // 系统返回键必须与顶栏返回按钮同一判据（见下面 CreatorDetailPage 的 onBack）：
    // 从表盘详情点进来时（initialUsername != null）创作者页是**子路由**，返回该回到
    // 详情页；只有从「优秀创作者」列表点进来时，返回才是回那张列表。
    // 以前这里只看 selectedUsername，于是从详情进来按返回会落到一张本来没打开过的
    // 「优秀创作者」列表上（老师报的问题）。
    BackHandler {
        if (selectedUsername != null && initialUsername == null) {
            selectedUsername = null
        } else {
            onDismiss()
        }
    }

    if (selectedUsername == null) {
        CreatorListPage(
            repository = repository,
            api = api,
            onSelect = { selectedUsername = it.username },
            onOpenWeb = onOpenWebList,
            onOpenWebExternal = onOpenWebListExternal,
            onDismiss = onDismiss,
        )
    } else {
        CreatorDetailPage(
            repository = repository,
            api = api,
            username = selectedUsername!!,
            onOpenWatchFace = onOpenWatchFace,
            onOpenWeb = { onOpenWebCreator(selectedUsername!!) },
            onOpenWebExternal = { onOpenWebCreatorExternal(selectedUsername!!) },
            // When opened from a watch-face detail, the creator page is a child
            // route. Back must reveal that detail instead of the top-creators list.
            onBack = {
                if (initialUsername != null) onDismiss() else selectedUsername = null
            },
        )
    }
}

@Composable
private fun CreatorListPage(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    onSelect: (WatchStoreCreator) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenWebExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var creators by remember { mutableStateOf<List<WatchStoreCreator>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { api.getTopCreators() } }
            .onSuccess { creators = it }
            .onFailure { failure ->
                failure.throwIfCancellation()
                error = watchStoreSocialErrorMessage(context, failure)
            }
        loading = false
    }
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
        loadError = error,
    ) {
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面。
        StoreRoundList {
            if (creators.isEmpty()) {
                item("empty") {
                    CreatorMessage(stringResource(R.string.watch_store_no_creators))
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
    onOpenWatchFace: (String) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenWebExternal: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creator by remember(username) { mutableStateOf<WatchStoreCreator?>(null) }
    var faces by remember(username) { mutableStateOf<List<WatchStoreWatchFace>>(emptyList()) }
    var pagination by remember(username) { mutableStateOf<WatchStorePagination?>(null) }
    var loading by remember(username) { mutableStateOf(true) }
    var loadingMore by remember(username) { mutableStateOf(false) }
    var error by remember(username) { mutableStateOf<String?>(null) }
    var loadMoreError by remember(username) { mutableStateOf<String?>(null) }
    LaunchedEffect(username) {
        runCatching {
            withContext(Dispatchers.IO) {
                api.getCreator(username) to api.listCreatorWatchFaces(username)
            }
        }.onSuccess { (loadedCreator, loadedPage) ->
            creator = loadedCreator
            faces = loadedPage.data
            pagination = loadedPage.pagination
        }.onFailure { failure ->
            failure.throwIfCancellation()
            error = watchStoreSocialErrorMessage(context, failure)
        }
        loading = false
    }

    fun loadMore() {
        val page = nextCreatorWatchFacePage(pagination) ?: return
        if (loadingMore) return
        loadingMore = true
        loadMoreError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.listCreatorWatchFaces(username = username, page = page)
                }
            }.onSuccess { loadedPage ->
                faces = appendCreatorWatchFacePage(faces, loadedPage.data)
                pagination = loadedPage.pagination
            }.onFailure { failure ->
                failure.throwIfCancellation()
                loadMoreError = watchStoreSocialErrorMessage(context, failure)
            }
            loadingMore = false
        }
    }

    StoreLibraryScaffold(
        title = creator?.username ?: username,
        subtitle = null,
        onBack = onBack,
        action = {
            CreatorWebActions(
                onOpenWeb = onOpenWeb,
                onOpenWebExternal = onOpenWebExternal,
            )
        },
        loading = loading,
        loadError = error,
    ) {
        val nextPage = nextCreatorWatchFacePage(pagination)
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面。
        StoreRoundList {
            creator?.let { loadedCreator ->
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
            items(faces, key = { it.id }) { face ->
                CreatorFaceRow(
                    repository = repository,
                    face = face,
                    onClick = { onOpenWatchFace(face.id) },
                )
            }
            if (faces.isEmpty()) {
                item("empty") {
                    CreatorMessage(stringResource(R.string.watch_store_no_creator_faces))
                }
            }
            if (nextPage != null) {
                item("load_more_$nextPage") {
                    // This footer is only composed as it approaches the viewport, so reaching the
                    // end requests the next segment without exposing page-style controls.
                    LaunchedEffect(nextPage) { loadMore() }
                    if (loadMoreError == null) {
                        CreatorMessage(stringResource(R.string.watch_store_status_loading_more))
                    } else {
                        CreatorMessage(loadMoreError!!)
                        FlueOutlinedButton(
                            onClick = ::loadMore,
                            enabled = !loadingMore,
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
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(style.accentColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rank.toString(),
                color = style.screenBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(8.dp))
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
