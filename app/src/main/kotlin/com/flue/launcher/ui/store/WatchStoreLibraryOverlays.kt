package com.flue.launcher.ui.store

import com.flue.launcher.ui.store.WatchStoreBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.WatchPreview
import com.flue.launcher.blockPointerPassThrough
import com.flue.launcher.throwIfCancellation
import com.flue.launcher.ui.common.fluePressScale
import com.flue.launcher.ui.common.rememberFluePressScale
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueCard
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.watchStoreSocialErrorMessage
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreCollection
import com.flue.launcher.watchface.store.WatchStoreCollectionItem
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStoreRepository
import com.flue.launcher.watchface.store.WatchStoreWatchFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Rename

/**
 * 我的收藏 / 我的合集两页（B3b）。
 *
 * 协议层（B1）与仓库层（B2）早就就绪，缺的只是界面。两页放一个文件是因为它们**结构完全
 * 一样**：鉴权 → 拉一次列表 → 四态（转圈 / 报错 / 空 / 列表）→ 行内一个移除动作，
 * 只有数据源与移除语义不同。共用的骨架抽成 [StoreLibraryScaffold] 与 [StoreLibraryFaceRow]。
 *
 * ## 为什么行里不给「下载」按钮
 *
 * 市场列表的卡（`WatchFaceListCard`）带安装状态与下载进度，那要靠宿主持续收集
 * `downloadStates[id]` 这些 StateFlow。把整套下载机器搬进这两页只会出现第二份状态源，
 * 而**点一下就进详情页**本来就是这两页的主要用途 —— 详情页那边下载、使用、删除全都是现成的。
 * 所以这里只做「浏览 + 移除」，下载交给详情页一条路。
 *
 * ## 移除一律乐观 + 失败回滚
 *
 * 手表上一次网络往返肉眼可见。等服务端确认再从列表里抹掉，用户会以为没点中而连点几次
 * （对收藏来说就是「取消 → 又加回来 → 又取消」）。与通知中心的置灰策略同一套。
 */
@Composable
fun WatchStoreFavoritesOverlay(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    token: String,
    onOpenWatchFace: (String) -> Unit,
    onTokenExpired: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pageActive = LocalWatchStorePageActive.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WatchStoreWatchFace>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token, pageActive) {
        if (!pageActive) return@LaunchedEffect
        loading = items.isEmpty()
        loadError = null
        runCatching { withContext(Dispatchers.IO) { api.listMyFavorites(token) } }
            .onSuccess { items = it }
            .onFailure { error ->
                error.throwIfCancellation()
                if (error.isTokenExpired()) {
                    onTokenExpired()
                    return@LaunchedEffect
                }
                loadError = watchStoreSocialErrorMessage(context, error)
            }
        loading = false
    }

    fun removeFavorite(face: WatchStoreWatchFace) {
        val previous = items
        items = items.filterNot { it.id == face.id }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.removeFavorite(token, face.id) }
            }.onFailure { error ->
                error.throwIfCancellation()
                // 回滚：把它放回原来的位置，而不是追加到末尾 —— 列表顺序是服务端给的。
                items = previous
            }
        }
    }

    BackHandler { onDismiss() }

    StoreLibraryScaffold(
        title = stringResource(R.string.watch_store_my_favorites),
        subtitle = null,
        onBack = onDismiss,
        action = null,
        loading = loading,
        loadError = loadError?.takeIf { items.isEmpty() },
        empty = items.isEmpty(),
        emptyText = stringResource(R.string.watch_store_no_favorites),
    ) {
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面（见该文件注释）。
        StoreRoundList(state = listState) {
            if (loadError != null && items.isNotEmpty()) {
                item("refresh_error") {
                    Text(loadError!!, color = LauncherTheme.style.secondaryTextColor, fontSize = 11.sp)
                }
            }
            items(items = items, key = { it.id }, contentType = { "library_face_row" }) { face ->
                StoreLibraryFaceRow(
                    repository = repository,
                    id = face.id,
                    name = face.name,
                    subtitle = stringResource(
                        R.string.watch_store_download_count_fmt,
                        face.downloadCount,
                    ),
                    trailingIcon = MiuixIcons.FavoritesFill,
                    trailingTint = WatchColors.ActiveRed,
                    trailingDescription = stringResource(R.string.watch_store_remove_favorite),
                    onTrailingClick = { removeFavorite(face) },
                    onClick = { onOpenWatchFace(face.id) },
                )
            }
        }
    }
}

/**
 * 我的合集：一层列表 + 一层合集内容。
 *
 * 两层用同一个覆层而不是各起一个：合集内容页除了标题与数据源，和外层一模一样，而**返回键
 * 要先退回外层再退出**这条行为只有放在同一个组件里才自然（分成两个覆层就得在宿主那边
 * 再记一份「当前在第几层」）。
 *
 * 重命名 / 删除挂在**长按**上：手表上每行再摆一个三点按钮会把名字挤没，而长按调出操作
 * 已经是这个项目的既有约定（导入表盘、选背景图都是长按走另一条路）。
 */
@Composable
fun WatchStoreCollectionsOverlay(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    token: String,
    onOpenWatchFace: (String) -> Unit,
    onTokenExpired: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pageActive = LocalWatchStorePageActive.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var collections by remember { mutableStateOf<List<WatchStoreCollection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    /** 打开的合集。非空即显示合集内容那一层。 */
    var openCollection by remember { mutableStateOf<WatchStoreCollection?>(null) }
    /** 长按弹出的操作目标。 */
    var actionTarget by remember { mutableStateOf<WatchStoreCollection?>(null) }
    /** 非空即显示命名弹窗；`id` 为空表示新建。 */
    var namingTarget by remember { mutableStateOf<CollectionNaming?>(null) }
    var deleteTarget by remember { mutableStateOf<WatchStoreCollection?>(null) }

    LaunchedEffect(token, reloadToken, pageActive) {
        if (!pageActive) return@LaunchedEffect
        loading = collections.isEmpty()
        loadError = null
        runCatching { withContext(Dispatchers.IO) { api.listMyCollections(token) } }
            .onSuccess { collections = it }
            .onFailure { error ->
                error.throwIfCancellation()
                if (error.isTokenExpired()) {
                    onTokenExpired()
                    return@LaunchedEffect
                }
                loadError = watchStoreSocialErrorMessage(context, error)
            }
        loading = false
    }

    BackHandler {
        when {
            openCollection != null -> openCollection = null
            else -> onDismiss()
        }
    }

    StoreLibraryScaffold(
        title = stringResource(R.string.watch_store_my_collections),
        subtitle = null,
        onBack = onDismiss,
        action = {
            IconButton(
                onClick = { namingTarget = CollectionNaming(id = null, name = "") },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    MiuixIcons.AddFolder,
                    contentDescription = stringResource(R.string.watch_store_create_collection),
                    tint = LauncherTheme.style.accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        loading = loading,
        loadError = loadError?.takeIf { collections.isEmpty() },
        empty = collections.isEmpty(),
        emptyText = stringResource(R.string.watch_store_no_collections),
    ) {
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面（见该文件注释）。
        StoreRoundList(state = listState) {
            if (loadError != null && collections.isNotEmpty()) {
                item("refresh_error") {
                    Text(loadError!!, color = LauncherTheme.style.secondaryTextColor, fontSize = 11.sp)
                }
            }
            items(
                items = collections,
                key = { it.id },
                contentType = { "collection_row" },
            ) { collection ->
                StoreCollectionRow(
                    collection = collection,
                    onClick = { openCollection = collection },
                    onLongPress = { actionTarget = collection },
                )
            }
        }
    }

    // 合集内容层。盖在外层之上、吃掉穿透，与宿主里那些覆层同一套。
    val opened = openCollection
    if (opened != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blockPointerPassThrough(),
        ) {
            StoreCollectionItemsPage(
                repository = repository,
                api = api,
                token = token,
                collection = opened,
                onOpenWatchFace = onOpenWatchFace,
                onTokenExpired = onTokenExpired,
                onItemCountChange = { count ->
                    collections = collections.map {
                        if (it.id == opened.id) it.copy(itemCount = count) else it
                    }
                    openCollection = openCollection?.copy(itemCount = count)
                },
                onBack = { openCollection = null },
            )
        }
    }

    // 长按操作单：重命名 / 删除。
    val target = actionTarget
    if (target != null && pageActive) {
        FlueAlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.name, color = LauncherTheme.style.titleColor) },
            text = {
                Column {
                    StoreLibraryActionRow(
                        icon = MiuixIcons.Rename,
                        label = stringResource(R.string.watch_store_rename_collection),
                        tint = LauncherTheme.style.bodyColor,
                    ) {
                        actionTarget = null
                        namingTarget = CollectionNaming(id = target.id, name = target.name)
                    }
                    StoreLibraryActionRow(
                        icon = MiuixIcons.Delete,
                        label = stringResource(R.string.watch_store_delete_collection),
                        tint = WatchColors.ActiveRed,
                    ) {
                        actionTarget = null
                        deleteTarget = target
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(
                        stringResource(R.string.watch_editor_cancel),
                        color = LauncherTheme.style.secondaryTextColor,
                    )
                }
            },
        )
    }

    // 新建 / 重命名共用一个命名弹窗：两条路的输入校验与长度上限完全相同。
    val naming = namingTarget
    if (naming != null && pageActive) {
        StoreCollectionNameDialog(
            initialName = naming.name,
            isRename = naming.id != null,
            onConfirm = { name ->
                namingTarget = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (naming.id == null) {
                                api.createCollection(token, name)
                            } else {
                                api.renameCollection(token, naming.id, name)
                            }
                        }
                    }.onSuccess {
                        // 上游这两个端点都只回 204，拿不到新的合集对象 —— 重拉一次列表
                        // 比在本地猜一个 id 靠谱。
                        reloadToken++
                    }.onFailure { error ->
                        error.throwIfCancellation()
                        if (error.isTokenExpired()) {
                            onTokenExpired()
                        } else {
                            loadError = watchStoreSocialErrorMessage(context, error)
                        }
                    }
                }
            },
            onDismiss = { namingTarget = null },
        )
    }

    val pendingDelete = deleteTarget
    if (pendingDelete != null && pageActive) {
        FlueAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    stringResource(R.string.watch_store_delete_collection),
                    color = LauncherTheme.style.titleColor,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.watch_store_delete_collection_prompt_fmt,
                        pendingDelete.name,
                    ),
                    color = LauncherTheme.style.bodyColor,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        val previous = collections
                        collections = collections.filterNot { it.id == pendingDelete.id }
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    api.deleteCollection(token, pendingDelete.id)
                                }
                            }.onFailure { error ->
                                error.throwIfCancellation()
                                collections = previous
                                loadError = watchStoreSocialErrorMessage(context, error)
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.watch_editor_delete),
                        color = WatchColors.ActiveRed,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(
                        stringResource(R.string.watch_editor_cancel),
                        color = LauncherTheme.style.secondaryTextColor,
                    )
                }
            },
        )
    }
}

/** 命名弹窗的目标：`id == null` 是新建。 */
private data class CollectionNaming(val id: String?, val name: String)

/** 一个合集的内容页。 */
@Composable
private fun StoreCollectionItemsPage(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    token: String,
    collection: WatchStoreCollection,
    onOpenWatchFace: (String) -> Unit,
    onTokenExpired: () -> Unit,
    onItemCountChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val pageActive = LocalWatchStorePageActive.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(collection.id) {
        mutableStateOf<List<WatchStoreCollectionItem>>(emptyList())
    }
    var loading by remember(collection.id) { mutableStateOf(true) }
    var loadError by remember(collection.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(token, collection.id, pageActive) {
        if (!pageActive) return@LaunchedEffect
        loading = items.isEmpty()
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { api.listCollectionItems(token, collection.id) }
        }.onSuccess {
            items = it
            onItemCountChange(it.size)
        }.onFailure { error ->
            error.throwIfCancellation()
            if (error.isTokenExpired()) {
                onTokenExpired()
                return@LaunchedEffect
            }
            loadError = watchStoreSocialErrorMessage(context, error)
        }
        loading = false
    }

    StoreLibraryScaffold(
        title = collection.name,
        subtitle = stringResource(R.string.watch_store_collection_count_fmt, items.size),
        onBack = onBack,
        action = null,
        loading = loading,
        loadError = loadError?.takeIf { items.isEmpty() },
        empty = items.isEmpty(),
        emptyText = stringResource(R.string.watch_store_collection_empty),
    ) {
        // StoreRoundList 而不是 LazyColumn：圆屏弦宽适配与避让动画都在它里面（见该文件注释）。
        StoreRoundList(state = listState) {
            if (loadError != null && items.isNotEmpty()) {
                item("refresh_error") {
                    Text(loadError!!, color = LauncherTheme.style.secondaryTextColor, fontSize = 11.sp)
                }
            }
            items(items = items, key = { it.id }, contentType = { "library_face_row" }) { entry ->
                StoreLibraryFaceRow(
                    repository = repository,
                    id = entry.id,
                    name = entry.name,
                    // 合集条目上游只回 id / name / preview_url，没有下载量与分类可显示。
                    subtitle = null,
                    trailingIcon = MiuixIcons.Delete,
                    trailingTint = LauncherTheme.style.secondaryTextColor,
                    trailingDescription = stringResource(
                        R.string.watch_store_remove_from_collection,
                    ),
                    onTrailingClick = {
                        val previous = items
                        items = items.filterNot { it.id == entry.id }
                        onItemCountChange(items.size)
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    api.removeCollectionItem(token, collection.id, entry.id)
                                }
                            }.onFailure { error ->
                                error.throwIfCancellation()
                                items = previous
                                onItemCountChange(previous.size)
                            }
                        }
                    },
                    onClick = { onOpenWatchFace(entry.id) },
                )
            }
        }
    }
}

/**
 * 商店内页共用的骨架：顶栏 + 四态内容。
 *
 * 四态用 `when` 而不是各写一遍 `if`：漏一个分支就会同时出现「转圈」与「空列表」两块，
 * 那是这类页面最常见的观感 bug。
 *
 * 个人资料页（H2）与创作者页（H5）也用它 —— 那两页没有「空」这一态，所以
 * [empty] / [emptyText] 给了默认值。
 */
@Composable
internal fun StoreLibraryScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)?,
    loading: Boolean,
    loadError: String?,
    empty: Boolean = false,
    emptyText: String = "",
    content: @Composable () -> Unit,
) {
    val style = LauncherTheme.style
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
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
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.watch_editor_back),
                        tint = style.titleColor,
                    )
                }
                Column(Modifier.weight(1f)) {
                    // 与市场首页共用同一份紧行距标题（见 StoreOverlayTitle 的注释）：
                    // 以前这里各写一遍 Text，大字与小字之间比首页宽一条。
                    StoreOverlayTitle(title = title, subtitle = subtitle)
                }
                action?.invoke()
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .padding(horizontal = if (isFlueScreenRound()) 2.dp else 0.dp),
            ) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FlueLoadingIndicator(size = 28.dp)
                    }

                    loadError != null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            loadError,
                            color = style.bodyColor,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }

                    empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyText, color = style.secondaryTextColor, fontSize = 14.sp)
                    }

                    else -> content()
                }
            }
        }
    }
}

/** 一行表盘：方形缩略图 + 名字 + 副标题 + 一个尾部动作。 */
@Composable
private fun StoreLibraryFaceRow(
    repository: WatchStoreRepository,
    id: String,
    name: String,
    subtitle: String?,
    trailingIcon: ImageVector,
    trailingTint: Color,
    trailingDescription: String,
    onTrailingClick: () -> Unit,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    val interactionSource = remember(id) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    FlueCard(
        modifier = Modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        border = null,
    ) {
        Row(
            modifier = Modifier.padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        WatchPreview(
            repository = repository,
            id = id,
            modifier = Modifier.width(56.dp).aspectRatio(1f).clip(style.rowShape),
            // 56dp 的槽位，256 的解码目标足够；与市场列表卡同一口径。
            targetSize = 256,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                color = style.titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = style.secondaryTextColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onTrailingClick, modifier = Modifier.size(36.dp)) {
            Icon(
                trailingIcon,
                contentDescription = trailingDescription,
                tint = trailingTint,
                modifier = Modifier.size(18.dp),
            )
        }
        }
    }
}

/** 一行合集：图标 + 名字 + 表盘数。 */
@Composable
private fun StoreCollectionRow(
    collection: WatchStoreCollection,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val style = LauncherTheme.style
    val interactionSource = remember(collection.id) { MutableInteractionSource() }
    val pressScale = rememberFluePressScale(interactionSource)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fluePressScale(pressScale)
            .clip(style.cardShape)
            .background(style.cardColor)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClick = onLongPress,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MiuixIcons.Album,
            contentDescription = null,
            tint = style.accentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                collection.name,
                color = style.titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.watch_store_collection_count_fmt, collection.itemCount),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
            )
        }
    }
}

/** 长按操作单里的一行。 */
@Composable
private fun StoreLibraryActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LauncherTheme.style.rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = tint, fontSize = 14.sp)
    }
}

/** 合集命名弹窗（新建与重命名共用）。 */
@Composable
private fun StoreCollectionNameDialog(
    initialName: String,
    isRename: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val style = LauncherTheme.style
    var draft by remember { mutableStateOf(initialName) }
    val trimmed = draft.trim()
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isRename) {
                        R.string.watch_store_rename_collection
                    } else {
                        R.string.watch_store_create_collection
                    },
                ),
                color = style.titleColor,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft,
                    // 就地截断到上游上限：不截就只剩 `require` 抛异常一条路，
                    // 用户会看到一句「合集名称需要 1-100 个字符」而不知道超了多少。
                    onValueChange = { draft = it.take(HttpWatchStoreApi.MAX_COLLECTION_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.watch_store_collection_name)) },
                )
                if (trimmed.isEmpty()) {
                    Text(
                        stringResource(R.string.watch_store_collection_name_empty),
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = { onConfirm(trimmed) },
            ) {
                Text(
                    stringResource(R.string.watch_editor_confirm),
                    color = if (trimmed.isEmpty()) style.secondaryTextColor else style.accentColor,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.watch_editor_cancel),
                    color = style.secondaryTextColor,
                )
            }
        },
    )
}

/**
 * 401 = 令牌过期。上游其余错误码都由 [watchStoreSocialErrorMessage] 翻译成人话，
 * 只有这一个要把用户踢回登录态，所以单独判。
 */
internal fun Throwable.isTokenExpired(): Boolean =
    this is WatchStoreHttpException && statusCode == 401
