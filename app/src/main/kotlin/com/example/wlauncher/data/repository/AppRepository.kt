package com.flue.launcher.data.repository

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.AppListItemType
import com.flue.launcher.data.model.BUILTIN_SETTINGS_ENTRY_COMPONENT
import com.flue.launcher.data.model.BUILTIN_SETTINGS_ENTRY_PACKAGE
import com.flue.launcher.iconpack.IconPackMapping
import com.flue.launcher.iconpack.IconPackScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class AppRepository(private val context: Context) {
    private data class CachedIconSet(
        val sharp: ImageBitmap,
        val softened: ImageBitmap,
        val twoToneSharp: ImageBitmap?,
        val twoToneSoftened: ImageBitmap?
    )

    private companion object {
        private const val TWO_TONE_BG = 0xFF1D1D1F.toInt()
        private const val TWO_TONE_FG = 0xFFF5F5F7.toInt()
        private const val BINARY_LUMINANCE_THRESHOLD = 0.8f
        private const val APP_LIST_CACHE_DIR = "app_list_cache"
        private const val APP_LIST_CACHE_ICON_DIR = "icons"
        private const val APP_LIST_CACHE_FILE = "snapshot.json"

        // 版本 4：自适应图标改为自绘抗锯齿裁剪，旧缓存位图带黑色锯齿边，必须整体重建。
        // 版本 5：文件夹合成底板从 0.24 圆角矩形改为整圆，旧缓存里那张方底板
        //         不会自己刷新，必须靠版本号顶掉。
        private const val APP_LIST_CACHE_VERSION = 5

        /** 自适应图标图层与可见区域的比例（108/72）。 */
        private const val ADAPTIVE_ICON_LAYER_SCALE = 108f / 72f

        /**
         * 裁形时向内收缩的比例。列表侧还会用不抗锯齿的 clipPath 复裁一次，
         * 轻微内缩可让抗锯齿边完整落在裁剪路径内部，不被再次切出锯齿。
         */
        private const val ICON_MASK_EDGE_INSET_FRACTION = 0.005f
    }

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val allApps: StateFlow<List<AppInfo>> = _allApps.asStateFlow()

    private val _allSelectableApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val allSelectableApps: StateFlow<List<AppInfo>> = _allSelectableApps.asStateFlow()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private data class CustomOrderSnapshot(
        val order: List<String>,
        val indexByKey: Map<String, Int>,
    )

    private val customOrderMonitor = Any()
    private var customOrderSnapshot = CustomOrderSnapshot(
        order = emptyList(),
        indexByKey = emptyMap(),
    )
    private var hiddenComponents: Set<String> = emptySet()
    private var currentIconSize = 128
    private var iconPackPackage: String? = null
    private var useLegacyCircularIcons = true
    private var useTwoToneIcons = false
    private var refreshGeneration = 0
    @Volatile
    private var freshScanPublished = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMonitor = Any()
    private var refreshJob: Job? = null
    private var packageRefreshJob: Job? = null
    private val installTimeCache = mutableMapOf<String, Long>()
    private val iconLocks = ConcurrentHashMap<String, Any>()
    private val itemStorage = AppListItemStorage(context)
    private var itemCatalog: Map<String, AppInfo> = emptyMap()
    private var folderCatalog: List<StoredAppListFolder> = emptyList()
    private val iconCache = object : LinkedHashMap<String, CachedIconSet>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedIconSet>?): Boolean {
            return size > 160
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            synchronized(refreshMonitor) {
                packageRefreshJob?.cancel()
                packageRefreshJob = scope.launch {
                    kotlinx.coroutines.delay(350)
                    refreshAsync()
                }
            }
        }
    }

    init {
        val cachedSnapshotLoaded = restoreCachedSnapshot()
        refreshAsync(initialDelayMs = if (cachedSnapshotLoaded) 1_400L else 0L)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
    }

    fun setCustomOrder(order: List<String>) {
        val ownedOrder = order.toList()
        synchronized(customOrderMonitor) {
            val snapshot = CustomOrderSnapshot(
                order = ownedOrder,
                indexByKey = ownedOrder.withIndex().associate { it.value to it.index },
            )
            customOrderSnapshot = snapshot
            reorder(snapshot)
        }
    }

    fun setHiddenComponents(components: Set<String>) {
        if (hiddenComponents == components) return
        hiddenComponents = components
        applyFilters()
    }

    fun setIconPackPackage(packageName: String?) {
        setIconRenderingOptions(packageName = packageName)
    }

    fun setLegacyCircularIconsEnabled(enabled: Boolean) {
        setIconRenderingOptions(legacyCircular = enabled)
    }

    fun setTwoToneIconsEnabled(enabled: Boolean) {
        setIconRenderingOptions(twoTone = enabled)
    }

    fun setIconRenderingOptions(
        packageName: String? = iconPackPackage,
        legacyCircular: Boolean = useLegacyCircularIcons,
        twoTone: Boolean = useTwoToneIcons,
        iconSize: Int = currentIconSize
    ) {
        val normalized = packageName?.takeIf { it.isNotBlank() }
        val nextIconSize = iconSize.coerceAtLeast(1)
        val changed = iconPackPackage != normalized ||
            useLegacyCircularIcons != legacyCircular ||
            useTwoToneIcons != twoTone ||
            currentIconSize != nextIconSize
        if (!changed) return
        iconPackPackage = normalized
        useLegacyCircularIcons = legacyCircular
        useTwoToneIcons = twoTone
        currentIconSize = nextIconSize
        refreshAsync(nextIconSize)
    }

    private fun reorder(orderSnapshot: CustomOrderSnapshot) {
        _allSelectableApps.value = sortApps(_allSelectableApps.value, orderSnapshot)
        _allApps.value = sortApps(_allApps.value, orderSnapshot)
        applyFilters()
    }

    fun refresh(iconSize: Int = currentIconSize) {
        val generation = synchronized(this) {
            refreshGeneration += 1
            refreshGeneration
        }
        val previousIconSize = currentIconSize
        currentIconSize = iconSize
        if (previousIconSize != iconSize) {
            synchronized(iconCache) { iconCache.clear() }
        }
        val iconPackPackageSnapshot = iconPackPackage
        val useLegacyCircularIconsSnapshot = useLegacyCircularIcons
        val useTwoToneIconsSnapshot = useTwoToneIcons
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
        val myPackage = context.packageName

        val iconPackMapping: IconPackMapping? = iconPackPackageSnapshot?.let { IconPackScanner.loadMapping(context, it) }

        val resolveList = resolveInfos
            .filter { ri ->
                !(ri.activityInfo.packageName == myPackage &&
                    ri.activityInfo.name in setOf(
                        "com.flue.launcher.LauncherActivity",
                        "com.flue.launcher.SettingsActivity"
                    ))
            }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        val loadedApps = ArrayList<AppInfo>(resolveList.size + 1)

        resolveList.forEach { ri ->
            if (!isCurrentRefresh(generation)) return
            val packageName = ri.activityInfo.packageName
            val componentKey = "$packageName/${ri.activityInfo.name}"
            val plainIconCacheKey = "${iconPackPackageSnapshot ?: "sys"}|plain|$componentKey|$iconSize|${if (useTwoToneIconsSnapshot) "two_tone" else "normal"}"
            val iconCacheKey = if (useLegacyCircularIconsSnapshot) {
                "${iconPackPackageSnapshot ?: "sys"}|$componentKey|$iconSize|legacy|${if (useTwoToneIconsSnapshot) "two_tone" else "normal"}"
            } else {
                plainIconCacheKey
            }
            val cachedIcons = getOrCreateIconSet(iconCacheKey) {
                createIconSet(
                    componentKey = componentKey,
                    iconPackMapping = iconPackMapping,
                    sourceDrawable = ri.loadIcon(pm),
                    iconSize = iconSize,
                    useLegacyCircularIcons = useLegacyCircularIconsSnapshot,
                    useTwoToneIcons = useTwoToneIconsSnapshot
                )
            }
            val plainIcons = if (!useLegacyCircularIconsSnapshot) {
                cachedIcons
            } else {
                getOrCreateIconSet(plainIconCacheKey) {
                    createIconSet(
                        componentKey = componentKey,
                        iconPackMapping = iconPackMapping,
                        sourceDrawable = ri.loadIcon(pm),
                        iconSize = iconSize,
                        useLegacyCircularIcons = false,
                        useTwoToneIcons = useTwoToneIconsSnapshot
                    )
                }
            }
            loadedApps += AppInfo(
                label = ri.loadLabel(pm).toString(),
                packageName = packageName,
                activityName = ri.activityInfo.name,
                cachedIcon = cachedIcons.sharp,
                cachedBlurredIcon = cachedIcons.softened,
                cachedTwoToneIcon = cachedIcons.twoToneSharp,
                cachedTwoToneBlurredIcon = cachedIcons.twoToneSoftened,
                cachedPlainIcon = plainIcons.sharp,
                cachedPlainBlurredIcon = plainIcons.softened,
                cachedPlainTwoToneIcon = plainIcons.twoToneSharp,
                cachedPlainTwoToneBlurredIcon = plainIcons.twoToneSoftened
            )
        }
        createBuiltInSettingsEntry(iconSize, useTwoToneIconsSnapshot)?.let(loadedApps::add)

        val catalogItems = ArrayList<AppInfo>(loadedApps.size + 8)
        catalogItems += loadedApps
        itemStorage.loadShortcuts().forEach { shortcut ->
            if (!isCurrentRefresh(generation)) return
            createShortcutEntry(
                shortcut = shortcut,
                iconPackMapping = iconPackMapping,
                iconSize = iconSize,
                useLegacyCircularIcons = useLegacyCircularIconsSnapshot,
                useTwoToneIcons = useTwoToneIconsSnapshot
            )?.let(catalogItems::add)
        }
        val catalogMap = catalogItems.associateBy { it.componentKey }
        val storedFolders = itemStorage.loadFolders()
        val folderChildKeys = storedFolders.flatMap { folder -> folder.itemKeys }.toSet()
        val folderEntries = storedFolders.mapNotNull { folder ->
            val liveKeys = folder.itemKeys.filter { key -> catalogMap.containsKey(key) }
            if (liveKeys.isEmpty()) {
                null
            } else {
                createFolderEntry(
                    folder = folder.copy(itemKeys = liveKeys),
                    childItems = liveKeys.mapNotNull(catalogMap::get),
                    iconSize = iconSize,
                    useTwoToneIcons = useTwoToneIconsSnapshot
                )
            }
        }
        val topLevelItems = ArrayList<AppInfo>(catalogItems.size + folderEntries.size)
        topLevelItems += catalogItems.filterNot { it.componentKey in folderChildKeys }
        topLevelItems += folderEntries

        if (synchronized(this) { generation != refreshGeneration }) return

        val knownPackages = catalogItems.mapTo(linkedSetOf()) { it.packageName }
        synchronized(installTimeCache) {
            installTimeCache.keys.retainAll(knownPackages)
        }

        freshScanPublished = true
        itemCatalog = catalogMap + folderEntries.associateBy { it.componentKey }
        folderCatalog = storedFolders
        synchronized(customOrderMonitor) {
            val orderSnapshot = customOrderSnapshot
            _allSelectableApps.value = sortApps(catalogItems, orderSnapshot)
            _allApps.value = sortApps(topLevelItems, orderSnapshot)
            applyFilters()
        }
        saveCachedSnapshot(catalogItems)
    }

    /**
     * 只重组文件夹与顶层排序，不碰 PackageManager。
     *
     * 文件夹增删改本质只改了一条归属关系，但以前一律走 [refresh]：那里会
     * 重跑 queryIntentActivities 并对每个应用调 loadLabel(pm)（图标虽有缓存，
     * label 没有），上百个应用就是用户看到的「放手后要等好一会儿文件夹才出来」。
     *
     * 这条路径复用已发布的 [itemCatalog]（AppInfo 里图标和标签都是现成的），
     * 只重建文件夹合成图标 + 重算顶层列表，代价与应用数无关。
     *
     * 前提是已经有过一次完整扫描（[freshScanPublished]）；否则回落全量刷新。
     */
    private fun refreshFolderCompositionOnly(iconSize: Int = currentIconSize) {
        if (!freshScanPublished) {
            refreshAsync(iconSize)
            return
        }
        // 必须占用一个新 generation：refreshJob.cancel() 对 refresh() 是无效的
        // （它是普通函数，体内没有挂起点，协程取消不会打断它），它唯一的退出
        // 依据就是 isCurrentRefresh()。不推进 generation 的话，一个在文件夹
        // 写盘之前就读过 loadFolders() 的全量刷新会在我们发布之后，带着「还
        // 没有这个文件夹」的旧快照覆盖回来——文件夹刚出现又消失。
        synchronized(this) { refreshGeneration += 1 }
        val useTwoToneIconsSnapshot = useTwoToneIcons
        // 只取真实条目：itemCatalog 里也混着上一轮合成的文件夹条目，
        // 拿它们当子项会套娃。
        val catalogItems = itemCatalog.values.filterNot { it.isFolder }
        val catalogMap = catalogItems.associateBy { it.componentKey }
        val storedFolders = itemStorage.loadFolders()
        val folderChildKeys = storedFolders.flatMap { folder -> folder.itemKeys }.toSet()
        val folderEntries = storedFolders.mapNotNull { folder ->
            val liveKeys = folder.itemKeys.filter { key -> catalogMap.containsKey(key) }
            if (liveKeys.isEmpty()) {
                null
            } else {
                createFolderEntry(
                    folder = folder.copy(itemKeys = liveKeys),
                    childItems = liveKeys.mapNotNull(catalogMap::get),
                    iconSize = iconSize,
                    useTwoToneIcons = useTwoToneIconsSnapshot,
                )
            }
        }
        val topLevelItems = ArrayList<AppInfo>(catalogItems.size + folderEntries.size)
        topLevelItems += catalogItems.filterNot { it.componentKey in folderChildKeys }
        topLevelItems += folderEntries

        itemCatalog = catalogMap + folderEntries.associateBy { it.componentKey }
        folderCatalog = storedFolders
        synchronized(customOrderMonitor) {
            val orderSnapshot = customOrderSnapshot
            _allSelectableApps.value = sortApps(catalogItems, orderSnapshot)
            _allApps.value = sortApps(topLevelItems, orderSnapshot)
            applyFilters()
        }
        saveCachedSnapshot(catalogItems)
    }

    /** 文件夹类改动的异步轻量刷新，避免在拖动收尾的主线程上做合成。 */
    private fun refreshFolderCompositionAsync() {
        synchronized(refreshMonitor) {
            refreshJob?.cancel()
            refreshJob = scope.launch { refreshFolderCompositionOnly() }
        }
    }

    fun launchApp(appInfo: AppInfo): Boolean {
        if (appInfo.isFolder) return false
        if (appInfo.isAppListShortcut) {
            val pinnedShortcutId = appInfo.pinnedShortcutId
            if (!pinnedShortcutId.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
                return runCatching {
                    launcherApps.startShortcut(
                        appInfo.packageName,
                        pinnedShortcutId,
                        null,
                        null,
                        android.os.Process.myUserHandle()
                    )
                    true
                }.getOrElse {
                    refreshAsync()
                    false
                }
            }
            val intent = runCatching {
                Intent.parseUri(appInfo.shortcutIntentUri.orEmpty(), Intent.URI_INTENT_SCHEME)
            }.getOrNull() ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                refreshAsync()
                false
            } catch (_: SecurityException) {
                refreshAsync()
                false
            }
        }
        if (appInfo.isBuiltInSettingsEntry) {
            val settingsIntent = Intent(context, com.flue.launcher.SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return try {
                context.startActivity(settingsIntent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        if (appInfo.packageName == context.packageName) return false
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(appInfo.packageName, appInfo.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            refreshAsync()
            false
        } catch (_: SecurityException) {
            refreshAsync()
            false
        }
    }

    fun destroy() {
        synchronized(refreshMonitor) {
            packageRefreshJob?.cancel()
            packageRefreshJob = null
            refreshJob?.cancel()
            refreshJob = null
        }
        scope.cancel()
        synchronized(iconCache) { iconCache.clear() }
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
    }

    fun requestRefresh(iconSize: Int = currentIconSize) {
        refreshAsync(iconSize)
    }

    fun folderItems(folderKey: String): List<AppInfo> {
        val folder = folderCatalog.firstOrNull { it.componentKey == folderKey } ?: return emptyList()
        return folder.itemKeys
            .mapNotNull(itemCatalog::get)
            .filterNot(::isHiddenApp)
    }

    fun folderItemKeys(folderKey: String): List<String> {
        return folderCatalog.firstOrNull { it.componentKey == folderKey }?.itemKeys.orEmpty()
    }

    fun folderMembershipSnapshot(): Map<String, List<String>> {
        return folderCatalog.associate { it.componentKey to it.itemKeys }
    }

    fun itemsForKeys(keys: List<String>): List<AppInfo> {
        return keys.mapNotNull(itemCatalog::get)
    }

    fun itemForKey(key: String): AppInfo? {
        return itemCatalog[key]
    }

    fun addLegacyShortcut(label: String?, intent: Intent?, icon: Bitmap?): Boolean {
        val shortcutIntent = intent ?: return false
        val safeLabel = label?.takeIf { it.isNotBlank() } ?: shortcutIntent.component?.packageName ?: "快捷方式"
        val packageName = shortcutIntent.component?.packageName ?: shortcutIntent.`package`.orEmpty()
        val uri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        itemStorage.addShortcut(
            label = safeLabel,
            packageName = packageName,
            intentUri = uri,
            icon = icon
        )
        refreshAsync()
        return true
    }

    fun addPinnedShortcut(shortcutInfo: ShortcutInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val packageName = shortcutInfo.safePackageName() ?: return false
        val shortcutId = runCatching { shortcutInfo.id }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
        val label = runCatching { shortcutInfo.shortLabel?.toString() }.getOrNull()
            ?: runCatching { shortcutInfo.longLabel?.toString() }.getOrNull()
            ?: shortcutId
        val iconBitmap = runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            launcherApps?.getShortcutIconDrawable(shortcutInfo, context.resources.displayMetrics.densityDpi)
                ?.let { renderIconBitmap(it, currentIconSize) }
        }.getOrNull()
        itemStorage.addShortcut(
            label = label,
            packageName = packageName,
            intentUri = pinnedShortcutUri(packageName, shortcutId),
            icon = iconBitmap,
            pinnedShortcutId = shortcutId
        )
        refreshAsync()
        return true
    }

    private fun ShortcutInfo.safePackageName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching { `package` }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun removeShortcut(componentKey: String): Boolean {
        val removed = itemStorage.removeShortcut(componentKey)
        if (removed) refreshAsync()
        return removed
    }

    fun createFolder(sourceKey: String, targetKey: String): String? {
        val targetName = itemCatalog[targetKey]?.label ?: "文件夹"
        val folder = itemStorage.createFolder(sourceKey, targetKey, targetName) ?: return null
        // 只重组文件夹，不重扫 PackageManager（放手后的长时间卡顿根因）。
        refreshFolderCompositionAsync()
        return folder.componentKey
    }

    fun renameFolder(folderKey: String, name: String): Boolean {
        val renamed = itemStorage.renameFolder(folderKey, name)
        if (renamed) refreshFolderCompositionAsync()
        return renamed
    }

    fun dissolveFolder(folderKey: String): List<String> {
        val keys = itemStorage.dissolveFolder(folderKey)
        if (keys.isNotEmpty()) refreshFolderCompositionAsync()
        return keys
    }

    fun moveItemOutOfFolder(folderKey: String, itemKey: String): FolderMutationResult {
        val result = itemStorage.moveItemOutOfFolder(folderKey, itemKey)
        if (result.succeeded) refreshFolderCompositionAsync()
        return result
    }

    fun addItemsToFolder(folderKey: String, itemKeys: List<String>): FolderMutationResult {
        val result = itemStorage.addItemsToFolder(folderKey, itemKeys)
        if (result.succeeded && result.itemKeys.isNotEmpty()) refreshFolderCompositionAsync()
        return result
    }

    fun setFolderItems(folderKey: String, itemKeys: List<String>): FolderMutationResult {
        val result = itemStorage.setFolderItems(folderKey, itemKeys)
        if (result.succeeded) refreshFolderCompositionAsync()
        return result
    }

    fun reorderFolderItems(folderKey: String, orderedKeys: List<String>): FolderMutationResult {
        val result = itemStorage.reorderFolderItems(folderKey, orderedKeys)
        if (result.succeeded && result.itemKeys.isNotEmpty()) refreshFolderCompositionAsync()
        return result
    }

    fun loadDisplayIconForPackage(
        packageName: String,
        iconSize: Int = currentIconSize,
        preferPlainIcon: Boolean = false
    ): ImageBitmap? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            val launchComponent = pm.getLaunchIntentForPackage(packageName)?.component
            val componentKey = launchComponent?.let { "${it.packageName}/${it.className}" }
                ?: "$packageName/$packageName"
            val sourceDrawable = launchComponent?.let {
                runCatching { pm.getActivityIcon(it) }.getOrNull()
            } ?: pm.getApplicationIcon(packageName)
            val iconPackPackageSnapshot = iconPackPackage
            val useLegacyCircularIconsSnapshot = useLegacyCircularIcons && !preferPlainIcon
            val useTwoToneIconsSnapshot = useTwoToneIcons
            val iconPackMapping = iconPackPackageSnapshot?.let { IconPackScanner.loadMapping(context, it) }
            val normalizedIconSize = iconSize.coerceAtLeast(1)
            val iconCacheKey = "${iconPackPackageSnapshot ?: "sys"}|media|$componentKey|$normalizedIconSize|${if (useLegacyCircularIconsSnapshot) "legacy" else "plain"}|${if (useTwoToneIconsSnapshot) "two_tone" else "normal"}"
            val cachedIcons = getOrCreateIconSet(iconCacheKey) {
                createIconSet(
                    componentKey = componentKey,
                    iconPackMapping = iconPackMapping,
                    sourceDrawable = sourceDrawable,
                    iconSize = normalizedIconSize,
                    useLegacyCircularIcons = useLegacyCircularIconsSnapshot,
                    useTwoToneIcons = useTwoToneIconsSnapshot
                )
            }
            if (useTwoToneIconsSnapshot) cachedIcons.twoToneSharp ?: cachedIcons.sharp else cachedIcons.sharp
        }.getOrNull()
    }

    private fun refreshAsync(iconSize: Int = currentIconSize, initialDelayMs: Long = 0L) {
        synchronized(refreshMonitor) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                if (initialDelayMs > 0L) {
                    kotlinx.coroutines.delay(initialDelayMs)
                }
                refresh(iconSize)
            }
        }
    }

    private fun restoreCachedSnapshot(): Boolean {
        val cacheRoot = appListCacheRoot()
        val snapshotFile = File(cacheRoot, APP_LIST_CACHE_FILE)
        if (!snapshotFile.isFile || freshScanPublished) return false
        val catalogItems = runCatching {
            val root = JSONObject(snapshotFile.readText(Charsets.UTF_8))
            if (root.optInt("version") != APP_LIST_CACHE_VERSION) return@runCatching emptyList<AppInfo>()
            val items = root.optJSONArray("items") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    readCachedSnapshotItem(cacheRoot, item)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        if (catalogItems.isEmpty() || freshScanPublished) return false

        val catalogMap = catalogItems.associateBy { it.componentKey }
        val storedFolders = itemStorage.loadFolders()
        val folderChildKeys = storedFolders.flatMap { folder -> folder.itemKeys }.toSet()
        val folderEntries = storedFolders.mapNotNull { folder ->
            val liveKeys = folder.itemKeys.filter { key -> catalogMap.containsKey(key) }
            if (liveKeys.isEmpty()) {
                null
            } else {
                createFolderEntry(
                    folder = folder.copy(itemKeys = liveKeys),
                    childItems = liveKeys.mapNotNull(catalogMap::get),
                    iconSize = currentIconSize,
                    useTwoToneIcons = useTwoToneIcons
                )
            }
        }
        if (freshScanPublished) return false
        val topLevelItems = ArrayList<AppInfo>(catalogItems.size + folderEntries.size)
        topLevelItems += catalogItems.filterNot { it.componentKey in folderChildKeys }
        topLevelItems += folderEntries

        itemCatalog = catalogMap + folderEntries.associateBy { it.componentKey }
        folderCatalog = storedFolders
        synchronized(customOrderMonitor) {
            val orderSnapshot = customOrderSnapshot
            _allSelectableApps.value = sortApps(catalogItems, orderSnapshot)
            _allApps.value = sortApps(topLevelItems, orderSnapshot)
            applyFilters()
        }
        return true
    }

    private fun saveCachedSnapshot(catalogItems: List<AppInfo>) {
        runCatching {
            val cacheRoot = appListCacheRoot().apply { mkdirs() }
            val iconDir = File(cacheRoot, APP_LIST_CACHE_ICON_DIR).apply { mkdirs() }
            val items = JSONArray()
            catalogItems
                .asSequence()
                .filterNot { it.isFolder }
                .forEach { app ->
                    val key = app.componentKey
                    val stem = snapshotFileStem(key)
                    val icon = writeCachedSnapshotIcon(cacheRoot, iconDir, stem, "icon", app.cachedIcon)
                        ?: return@forEach
                    val item = JSONObject().apply {
                        put("label", app.label)
                        put("packageName", app.packageName)
                        put("activityName", app.activityName)
                        put("itemType", app.itemType.name)
                        put("builtIn", app.isBuiltInSettingsEntry)
                        put("componentKey", key)
                        put("installTime", synchronized(installTimeCache) {
                            installTimeCache[app.packageName] ?: Long.MAX_VALUE
                        })
                        put("icon", icon)
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "blur",
                            app.cachedBlurredIcon
                        )?.let { put("blur", it) }
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "two_tone",
                            app.cachedTwoToneIcon
                        )?.let { put("twoTone", it) }
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "two_tone_blur",
                            app.cachedTwoToneBlurredIcon
                        )?.let { put("twoToneBlur", it) }
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "plain",
                            app.cachedPlainIcon
                        )?.let { put("plain", it) }
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "plain_blur",
                            app.cachedPlainBlurredIcon
                        )?.let { put("plainBlur", it) }
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "plain_two_tone",
                            app.cachedPlainTwoToneIcon
                        )?.let { put("plainTwoTone", it) }
                        writeCachedSnapshotIcon(
                            cacheRoot,
                            iconDir,
                            stem,
                            "plain_two_tone_blur",
                            app.cachedPlainTwoToneBlurredIcon
                        )?.let { put("plainTwoToneBlur", it) }
                        app.shortcutIntentUri?.let { put("shortcutIntentUri", it) }
                        app.pinnedShortcutId?.let { put("pinnedShortcutId", it) }
                    }
                    items.put(item)
                }
            val root = JSONObject().apply {
                put("version", APP_LIST_CACHE_VERSION)
                put("generatedAt", System.currentTimeMillis())
                put("items", items)
            }
            val output = File(cacheRoot, APP_LIST_CACHE_FILE)
            val temp = File(cacheRoot, "$APP_LIST_CACHE_FILE.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(output)) {
                output.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        }
    }

    private fun readCachedSnapshotItem(cacheRoot: File, item: JSONObject): AppInfo? {
        val label = item.optString("label").takeIf(String::isNotBlank) ?: return null
        val packageName = item.optString("packageName")
        val activityName = item.optString("activityName").takeIf(String::isNotBlank) ?: return null
        val itemType = runCatching {
            AppListItemType.valueOf(item.optString("itemType", AppListItemType.APP.name))
        }.getOrDefault(AppListItemType.APP)
        if (itemType == AppListItemType.FOLDER) return null
        val icon = readCachedSnapshotIcon(cacheRoot, item.optString("icon"))
            ?: return null
        val blurredIcon = readCachedSnapshotIcon(cacheRoot, item.optString("blur")) ?: icon
        val installTime = item.optLong("installTime", Long.MAX_VALUE)
        synchronized(installTimeCache) {
            installTimeCache[packageName] = installTime
        }
        return AppInfo(
            label = label,
            packageName = packageName,
            activityName = activityName,
            cachedIcon = icon,
            cachedBlurredIcon = blurredIcon,
            cachedTwoToneIcon = readCachedSnapshotIcon(cacheRoot, item.optString("twoTone")),
            cachedTwoToneBlurredIcon = readCachedSnapshotIcon(cacheRoot, item.optString("twoToneBlur")),
            cachedPlainIcon = readCachedSnapshotIcon(cacheRoot, item.optString("plain")),
            cachedPlainBlurredIcon = readCachedSnapshotIcon(cacheRoot, item.optString("plainBlur")),
            cachedPlainTwoToneIcon = readCachedSnapshotIcon(cacheRoot, item.optString("plainTwoTone")),
            cachedPlainTwoToneBlurredIcon = readCachedSnapshotIcon(cacheRoot, item.optString("plainTwoToneBlur")),
            isBuiltInSettingsEntry = item.optBoolean("builtIn", false),
            itemType = itemType,
            shortcutIntentUri = item.optString("shortcutIntentUri").takeIf(String::isNotBlank),
            pinnedShortcutId = item.optString("pinnedShortcutId").takeIf(String::isNotBlank)
        )
    }

    private fun writeCachedSnapshotIcon(
        cacheRoot: File,
        iconDir: File,
        stem: String,
        role: String,
        icon: ImageBitmap?
    ): String? {
        if (icon == null) return null
        return runCatching {
            val file = File(iconDir, "$stem-$role.png")
            file.outputStream().use { output ->
                icon.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            "$APP_LIST_CACHE_ICON_DIR/${file.name}"
        }.getOrNull()
    }

    private fun readCachedSnapshotIcon(cacheRoot: File, relativePath: String?): ImageBitmap? {
        if (relativePath.isNullOrBlank()) return null
        val file = File(cacheRoot, relativePath)
        if (!file.isFile) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    private fun appListCacheRoot(): File = File(context.filesDir, APP_LIST_CACHE_DIR)

    private fun snapshotFileStem(componentKey: String): String {
        val readable = componentKey.map { char ->
            if (char.isLetterOrDigit()) char else '_'
        }.joinToString("").take(72).ifBlank { "item" }
        return "$readable-${Integer.toHexString(componentKey.hashCode())}"
    }

    private fun isCurrentRefresh(generation: Int): Boolean =
        synchronized(this) { generation == refreshGeneration }

    private fun getOrCreateIconSet(key: String, producer: () -> CachedIconSet): CachedIconSet {
        synchronized(iconCache) { iconCache[key]?.let { return it } }
        val lock = iconLocks.getOrPut(key) { Any() }
        return synchronized(lock) {
            val existing = synchronized(iconCache) { iconCache[key] }
            if (existing != null) {
                existing
            } else {
                try {
                    producer().also { iconSet ->
                        synchronized(iconCache) {
                            iconCache[key] = iconSet
                        }
                    }
                } finally {
                    iconLocks.remove(key)
                }
            }
        }
    }

    private fun createIconSet(
        componentKey: String,
        iconPackMapping: IconPackMapping?,
        sourceDrawable: Drawable,
        iconSize: Int,
        useLegacyCircularIcons: Boolean,
        useTwoToneIcons: Boolean
    ): CachedIconSet {
        val packedIcon = iconPackMapping?.let { IconPackScanner.loadIconDrawable(context, it, componentKey) }
        val fallbackBitmap = if (packedIcon == null && iconPackMapping != null) {
            createIconPackTemplateBitmap(
                mapping = iconPackMapping,
                componentKey = componentKey,
                sourceDrawable = sourceDrawable,
                iconSize = iconSize
            )
        } else {
            null
        }
        val baseBitmap = when {
            packedIcon != null -> renderIconBitmap(packedIcon, iconSize)
            fallbackBitmap != null -> stripEdgeBlackMatte(fallbackBitmap)
            useLegacyCircularIcons -> if (sourceDrawable is AdaptiveIconDrawable) {
                // 自适应图标直接用圆形遮罩自绘，避免“框架渲染成方图再裁圆”的二次采样。
                createAdaptiveIconBitmap(
                    drawable = sourceDrawable,
                    size = iconSize,
                    forceCircularMask = true,
                    edgeInsetPx = iconSize * ICON_MASK_EDGE_INSET_FRACTION
                )
            } else {
                createCircularBitmap(
                    stripEdgeBlackMatte(drawableToBitmap(sourceDrawable, iconSize)),
                    edgeInsetPx = iconSize * ICON_MASK_EDGE_INSET_FRACTION
                )
            }
            else -> renderIconBitmap(sourceDrawable, iconSize)
        }
        val twoToneSource = when {
            packedIcon != null -> packedIcon
            fallbackBitmap != null -> BitmapDrawable(context.resources, fallbackBitmap)
            else -> sourceDrawable
        }
        val sharp = baseBitmap.asImageBitmap()
        val softened = createSoftenedBitmap(baseBitmap).asImageBitmap()
        val twoToneBitmap = if (useTwoToneIcons) {
            createTwoToneBitmap(twoToneSource, iconSize)
        } else {
            null
        }
        return CachedIconSet(
            sharp = sharp,
            softened = softened,
            twoToneSharp = twoToneBitmap?.asImageBitmap(),
            twoToneSoftened = twoToneBitmap?.let(::createSoftenedBitmap)?.asImageBitmap()
        )
    }

    private fun createIconPackTemplateBitmap(
        mapping: IconPackMapping,
        componentKey: String,
        sourceDrawable: Drawable,
        iconSize: Int
    ): Bitmap? {
        val template = IconPackScanner.loadTemplateDrawables(context, mapping, componentKey) ?: return null
        val output = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        template.iconBack?.let { drawDrawable(it, canvas, 0, 0, iconSize, iconSize) }

        val iconLayer = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val iconCanvas = Canvas(iconLayer)
        val scaledSize = (iconSize * template.scale).roundToInt().coerceIn(1, iconSize * 2)
        val inset = ((iconSize - scaledSize) / 2f).roundToInt()
        if (sourceDrawable is AdaptiveIconDrawable) {
            // 先自绘出干净的自适应图标，再交给图标包模板遮罩，防止黑边混进模板合成。
            val adaptiveBitmap = createAdaptiveIconBitmap(sourceDrawable, scaledSize)
            val adaptivePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            iconCanvas.drawBitmap(
                adaptiveBitmap,
                null,
                RectF(
                    inset.toFloat(),
                    inset.toFloat(),
                    (inset + scaledSize).toFloat(),
                    (inset + scaledSize).toFloat()
                ),
                adaptivePaint
            )
        } else {
            drawDrawable(sourceDrawable, iconCanvas, inset, inset, inset + scaledSize, inset + scaledSize)
        }
        template.iconMask?.let { maskDrawable ->
            val maskBitmap = drawableToBitmap(maskDrawable, iconSize)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            iconCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)
            maskPaint.xfermode = null
        }
        canvas.drawBitmap(iconLayer, 0f, 0f, null)
        template.iconUpon?.let { drawDrawable(it, canvas, 0, 0, iconSize, iconSize) }
        return output
    }

    private fun drawDrawable(
        drawable: Drawable,
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        copy.setBounds(left, top, right, bottom)
        copy.draw(canvas)
    }

    private fun createSoftenedBitmap(source: Bitmap): Bitmap {
        val downscaled = Bitmap.createScaledBitmap(
            source,
            (source.width * 0.25f).toInt().coerceAtLeast(1),
            (source.height * 0.25f).toInt().coerceAtLeast(1),
            true
        )
        return Bitmap.createScaledBitmap(downscaled, source.width, source.height, true)
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * 统一入口：自适应图标走自绘抗锯齿裁剪，普通图标沿用位图化 + 黑边清理。
     *
     * 自适应图标绝不能过 [stripEdgeBlackMatte]——深色背景图标的抗锯齿边像素
     * （半透明深色）会被泛洪误删，重新切出锯齿。
     */
    private fun renderIconBitmap(drawable: Drawable, size: Int): Bitmap =
        if (drawable is AdaptiveIconDrawable) {
            createAdaptiveIconBitmap(
                drawable = drawable,
                size = size,
                edgeInsetPx = size * ICON_MASK_EDGE_INSET_FRACTION
            )
        } else {
            stripEdgeBlackMatte(drawableToBitmap(drawable, size))
        }

    /**
     * 自绘渲染自适应图标。
     *
     * 不使用 AdaptiveIconDrawable#draw：框架实现会先把图层位图整体填黑
     * （AOSP AdaptiveIconDrawable.draw 里的 mCanvas.drawColor(BLACK)），再用
     * 按目标尺寸栅格化的 ALPHA_8 遮罩位图裁剪。背景层一旦有透明区域，黑色
     * 底就会漏出，低分辨率遮罩再把它切成黑色锯齿边。
     *
     * 这里改为：把 background/foreground 图层按 108/72 的官方比例放大居中，
     * 画在完全透明的画布上，再用 BitmapShader + 抗锯齿 Paint 的 drawPath /
     * drawCircle 自行裁形。
     */
    private fun createAdaptiveIconBitmap(
        drawable: AdaptiveIconDrawable,
        size: Int,
        forceCircularMask: Boolean = false,
        edgeInsetPx: Float = 0f
    ): Bitmap {
        val layerBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layerBitmap)
        val overdrawPx = size * (ADAPTIVE_ICON_LAYER_SCALE - 1f) / 2f
        val layerStart = (-overdrawPx).roundToInt()
        val layerEnd = size + overdrawPx.roundToInt()
        drawable.background?.let { background ->
            drawDrawable(background, layerCanvas, layerStart, layerStart, layerEnd, layerEnd)
        }
        drawable.foreground?.let { foreground ->
            drawDrawable(foreground, layerCanvas, layerStart, layerStart, layerEnd, layerEnd)
        }

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
        ).apply {
            shader = BitmapShader(layerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (forceCircularMask) {
            val radius = (size / 2f - edgeInsetPx).coerceAtLeast(0f)
            canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        } else {
            canvas.drawPath(adaptiveMaskPath(drawable, size, edgeInsetPx), paint)
        }
        return output
    }

    /**
     * 把设备的自适应图标遮罩路径归一化到 [0, size] 像素空间。
     *
     * 不同 ROM 的 getIconMask() 坐标空间不一致（100x100 视口或已随 bounds
     * 变换），所以用路径自身包围盒做 rect-to-rect 变换，空路径退化为圆形。
     */
    private fun adaptiveMaskPath(
        drawable: AdaptiveIconDrawable,
        size: Int,
        edgeInsetPx: Float
    ): Path {
        val target = RectF(edgeInsetPx, edgeInsetPx, size - edgeInsetPx, size - edgeInsetPx)
        val path = runCatching { Path(drawable.iconMask) }.getOrNull() ?: Path()
        val pathBounds = RectF()
        path.computeBounds(pathBounds, true)
        if (pathBounds.width() > 0f && pathBounds.height() > 0f) {
            val matrix = Matrix()
            matrix.setRectToRect(pathBounds, target, Matrix.ScaleToFit.FILL)
            path.transform(matrix)
            return path
        }
        return Path().apply {
            addCircle(
                size / 2f,
                size / 2f,
                (size / 2f - edgeInsetPx).coerceAtLeast(0f),
                Path.Direction.CW
            )
        }
    }

    private fun stripEdgeBlackMatte(source: Bitmap): Bitmap {
        if (source.width <= 1 || source.height <= 1) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        var cleared = 0

        fun isMattePixel(index: Int): Boolean {
            val pixel = pixels[index]
            val alpha = Color.alpha(pixel)
            if (alpha == 0 || alpha >= 248) return false
            return Color.red(pixel) <= 28 &&
                Color.green(pixel) <= 28 &&
                Color.blue(pixel) <= 28
        }

        fun enqueue(index: Int) {
            if (visited[index] || !isMattePixel(index)) return
            visited[index] = true
            queue[tail++] = index
        }

        for (x in 0 until width) {
            enqueue(x)
            enqueue((height - 1) * width + x)
        }
        for (y in 1 until height - 1) {
            enqueue(y * width)
            enqueue(y * width + width - 1)
        }

        while (head < tail) {
            val index = queue[head++]
            pixels[index] = Color.TRANSPARENT
            cleared += 1
            val x = index % width
            val y = index / width
            if (x > 0) enqueue(index - 1)
            if (x < width - 1) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y < height - 1) enqueue(index + width)
        }

        if (cleared == 0) return source
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun createCircularBitmap(source: Bitmap, edgeInsetPx: Float = 0f): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            isFilterBitmap = true
            isDither = true
        }
        val radius = (minOf(source.width, source.height) / 2f - edgeInsetPx).coerceAtLeast(0f)
        canvas.drawCircle(source.width / 2f, source.height / 2f, radius, paint)
        return output
    }

    private fun createTwoToneBitmap(drawable: Drawable, size: Int): Bitmap {
        val adaptiveMono = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (drawable as? AdaptiveIconDrawable)?.monochrome
        } else {
            null
        }
        if (adaptiveMono != null) {
            return createAdaptiveMonochromeBitmap(adaptiveMono, size)
        }
        return createTintedGrayscaleBitmap(renderIconBitmap(drawable, size))
    }

    private fun createAdaptiveMonochromeBitmap(monochrome: Drawable, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(TWO_TONE_BG)
        val monoDrawable = monochrome.constantState?.newDrawable()?.mutate() ?: monochrome.mutate()
        monoDrawable.colorFilter = PorterDuffColorFilter(TWO_TONE_FG, PorterDuff.Mode.SRC_IN)
        val inset = (size * 0.10f).toInt()
        monoDrawable.setBounds(inset, inset, size - inset, size - inset)
        monoDrawable.draw(canvas)
        return output
    }

    private fun createTintedGrayscaleBitmap(source: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val grayscaleCanvas = Canvas(grayscale)
        val grayscalePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        grayscaleCanvas.drawBitmap(source, 0f, 0f, grayscalePaint)

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(grayscale.width * grayscale.height)
        grayscale.getPixels(pixels, 0, grayscale.width, 0, 0, grayscale.width, grayscale.height)
        val threshold = (255 * BINARY_LUMINANCE_THRESHOLD).toInt()
        var opaqueCount = 0
        var whiteCount = 0
        for (index in pixels.indices) {
            val px = pixels[index]
            val alpha = Color.alpha(px)
            if (alpha == 0) continue
            opaqueCount += 1
            val luminance = ((Color.red(px) + Color.green(px) + Color.blue(px)) / 3f).toInt()
            val color = if (luminance < threshold) TWO_TONE_FG else TWO_TONE_BG
            if (color == TWO_TONE_FG) {
                whiteCount += 1
            }
            pixels[index] = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
        if (opaqueCount > 0 && whiteCount > opaqueCount / 2) {
            for (index in pixels.indices) {
                val px = pixels[index]
                val alpha = Color.alpha(px)
                if (alpha == 0) continue
                val isFg = Color.red(px) == Color.red(TWO_TONE_FG)
                val inverted = if (isFg) TWO_TONE_BG else TWO_TONE_FG
                pixels[index] = Color.argb(alpha, Color.red(inverted), Color.green(inverted), Color.blue(inverted))
            }
        }
        output.setPixels(pixels, 0, grayscale.width, 0, 0, grayscale.width, grayscale.height)
        return output
    }

    private fun orderRank(
        app: AppInfo,
        orderSnapshot: CustomOrderSnapshot,
    ): Int {
        if (orderSnapshot.order.isEmpty()) return Int.MAX_VALUE
        return orderSnapshot.indexByKey[app.componentKey]
            ?: orderSnapshot.indexByKey[app.packageName]
            ?: Int.MAX_VALUE
    }

    private fun packageInstallTime(packageName: String): Long {
        synchronized(installTimeCache) {
            installTimeCache[packageName]?.let { return it }
        }
        val installTime = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).firstInstallTime
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        synchronized(installTimeCache) {
            installTimeCache[packageName] = installTime
        }
        return installTime
    }

    private fun sortApps(
        apps: List<AppInfo>,
        orderSnapshot: CustomOrderSnapshot,
    ): List<AppInfo> {
        return if (orderSnapshot.order.isNotEmpty()) {
            apps.sortedWith(
                compareBy<AppInfo> { orderRank(it, orderSnapshot) }
                    .thenBy {
                        if (
                            it.isBuiltInSettingsEntry &&
                            it.componentKey !in orderSnapshot.indexByKey
                        ) {
                            1
                        } else {
                            0
                        }
                    }
                    .thenBy { packageInstallTime(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        } else {
            apps.sortedWith(
                compareBy<AppInfo> { if (it.isBuiltInSettingsEntry) 1 else 0 }
                    .thenBy { packageInstallTime(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        }
    }

    private fun createShortcutEntry(
        shortcut: StoredAppListShortcut,
        iconPackMapping: IconPackMapping?,
        iconSize: Int,
        useLegacyCircularIcons: Boolean,
        useTwoToneIcons: Boolean
    ): AppInfo? {
        val packageName = shortcut.packageName
        val sourceDrawable = itemStorage.loadShortcutIcon(shortcut.iconPath)?.let { bitmap ->
            BitmapDrawable(context.resources, bitmap)
        } ?: loadPackageFallbackIcon(packageName) ?: return null
        val iconCacheKey = "shortcut|${shortcut.id}|${shortcut.iconPath}|$iconSize|${if (useLegacyCircularIcons) "legacy" else "plain"}|${if (useTwoToneIcons) "two_tone" else "normal"}"
        val cachedIcons = getOrCreateIconSet(iconCacheKey) {
            createIconSet(
                componentKey = shortcut.componentKey,
                iconPackMapping = iconPackMapping,
                sourceDrawable = sourceDrawable,
                iconSize = iconSize,
                useLegacyCircularIcons = useLegacyCircularIcons,
                useTwoToneIcons = useTwoToneIcons
            )
        }
        val plainIconCacheKey = "shortcut|plain|${shortcut.id}|${shortcut.iconPath}|$iconSize|${if (useTwoToneIcons) "two_tone" else "normal"}"
        val plainIcons = if (!useLegacyCircularIcons) {
            cachedIcons
        } else {
            getOrCreateIconSet(plainIconCacheKey) {
                createIconSet(
                    componentKey = shortcut.componentKey,
                    iconPackMapping = iconPackMapping,
                    sourceDrawable = sourceDrawable,
                    iconSize = iconSize,
                    useLegacyCircularIcons = false,
                    useTwoToneIcons = useTwoToneIcons
                )
            }
        }
        return AppInfo(
            label = shortcut.label,
            packageName = packageName,
            activityName = shortcut.id,
            cachedIcon = cachedIcons.sharp,
            cachedBlurredIcon = cachedIcons.softened,
            cachedTwoToneIcon = cachedIcons.twoToneSharp,
            cachedTwoToneBlurredIcon = cachedIcons.twoToneSoftened,
            cachedPlainIcon = plainIcons.sharp,
            cachedPlainBlurredIcon = plainIcons.softened,
            cachedPlainTwoToneIcon = plainIcons.twoToneSharp,
            cachedPlainTwoToneBlurredIcon = plainIcons.twoToneSoftened,
            itemType = AppListItemType.SHORTCUT,
            shortcutIntentUri = shortcut.intentUri,
            pinnedShortcutId = shortcut.pinnedShortcutId
        )
    }

    private fun pinnedShortcutUri(packageName: String, shortcutId: String): String {
        return Intent("com.flue.launcher.action.START_PINNED_SHORTCUT").apply {
            `package` = packageName
            putExtra("android.intent.extra.shortcut.ID", shortcutId)
        }.toUri(Intent.URI_INTENT_SCHEME)
    }

    private fun createFolderEntry(
        folder: StoredAppListFolder,
        childItems: List<AppInfo>,
        iconSize: Int,
        useTwoToneIcons: Boolean
    ): AppInfo {
        val iconCacheKey = "folder|${folder.id}|${folder.itemKeys.joinToString("|")}|$iconSize|${if (useTwoToneIcons) "two_tone" else "normal"}"
        val cachedIcons = getOrCreateIconSet(iconCacheKey) {
            val baseBitmap = createFolderBitmap(childItems, iconSize)
            val twoToneBitmap = if (useTwoToneIcons) createTintedGrayscaleBitmap(baseBitmap) else null
            CachedIconSet(
                sharp = baseBitmap.asImageBitmap(),
                softened = createSoftenedBitmap(baseBitmap).asImageBitmap(),
                twoToneSharp = twoToneBitmap?.asImageBitmap(),
                twoToneSoftened = twoToneBitmap?.let(::createSoftenedBitmap)?.asImageBitmap()
            )
        }
        return AppInfo(
            label = folder.name,
            packageName = BUILTIN_SETTINGS_ENTRY_PACKAGE,
            activityName = folder.id,
            cachedIcon = cachedIcons.sharp,
            cachedBlurredIcon = cachedIcons.softened,
            cachedTwoToneIcon = cachedIcons.twoToneSharp,
            cachedTwoToneBlurredIcon = cachedIcons.twoToneSoftened,
            cachedPlainIcon = cachedIcons.sharp,
            cachedPlainBlurredIcon = cachedIcons.softened,
            cachedPlainTwoToneIcon = cachedIcons.twoToneSharp,
            cachedPlainTwoToneBlurredIcon = cachedIcons.twoToneSoftened,
            itemType = AppListItemType.FOLDER,
            folderItemKeys = folder.itemKeys
        )
    }

    private fun createFolderBitmap(childItems: List<AppInfo>, iconSize: Int): Bitmap {
        val size = iconSize.coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD2C2C2E.toInt() }
        // 底板画圆，不是圆角方块。列表侧的 iconClipPath 现在恒为
        // addCircle（图标全部统一圆形），底板若还是 0.24·size 的圆角矩形，
        // 四角会被裁掉一截、边缘也和旁边的圆图标对不齐——截图里那个文件夹
        // 缩略图明显比周围图标"方"就是它。
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        // 2x2 子图标网格必须落在内切圆里：蜂窝 / Apple Watch 风格会把整张
        // 文件夹图按圆形裁剪，旧的 0.36+0.08（总宽 0.80·size）四角到中心是
        // √2·0.40 = 0.566·size > 0.5·size 半径，四个子图标的外角会被切掉，
        // 看起来就不是个圆文件夹。0.30+0.08（总宽 0.68·size）对角
        // √2·0.34 = 0.481·size，稳稳在圆内；圆角矩形档只是子图标略小。
        val childSize = size * 0.30f
        val gap = size * 0.08f
        val start = (size - childSize * 2f - gap) / 2f
        childItems.take(4).forEachIndexed { index, app ->
            val row = index / 2
            val col = index % 2
            val left = start + col * (childSize + gap)
            val top = start + row * (childSize + gap)
            val childBitmap = (app.cachedPlainIcon ?: app.cachedIcon).asAndroidBitmap()
            canvas.drawBitmap(
                childBitmap,
                null,
                RectF(left, top, left + childSize, top + childSize),
                paint
            )
        }
        return output
    }

    private fun loadPackageFallbackIcon(packageName: String): Drawable? {
        val pm = context.packageManager
        return runCatching {
            if (packageName.isBlank()) {
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            } else {
                val launchComponent = pm.getLaunchIntentForPackage(packageName)?.component
                launchComponent?.let(pm::getActivityIcon) ?: pm.getApplicationIcon(packageName)
            }
        }.getOrNull() ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
    }

    private fun createBuiltInSettingsEntry(iconSize: Int, useTwoToneIconsSnapshot: Boolean): AppInfo? {
        val iconDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_settings_launcher) ?: return null
        val iconCacheKey = "builtin_settings|$iconSize|${if (useTwoToneIconsSnapshot) "two_tone" else "normal"}"
        val cachedIcons = getOrCreateIconSet(iconCacheKey) {
            // 与普通应用同路：自适应图标强制圆形遮罩。以前这里直接走
            // renderIconBitmap（用设备系统遮罩），全列表只有设置入口被
            // ROM 的圆角矩形遮罩裁成方的。
            val baseBitmap = if (iconDrawable is AdaptiveIconDrawable) {
                createAdaptiveIconBitmap(
                    drawable = iconDrawable,
                    size = iconSize,
                    forceCircularMask = true,
                    edgeInsetPx = iconSize * ICON_MASK_EDGE_INSET_FRACTION,
                )
            } else {
                renderIconBitmap(iconDrawable, iconSize)
            }
            val sharp = baseBitmap.asImageBitmap()
            val softened = createSoftenedBitmap(baseBitmap).asImageBitmap()
            val twoToneBitmap = if (useTwoToneIconsSnapshot) createTwoToneBitmap(iconDrawable, iconSize) else null
            CachedIconSet(
                sharp = sharp,
                softened = softened,
                twoToneSharp = twoToneBitmap?.asImageBitmap(),
                twoToneSoftened = twoToneBitmap?.let(::createSoftenedBitmap)?.asImageBitmap()
            )
        }
        return AppInfo(
            label = context.getString(R.string.settings_title),
            packageName = BUILTIN_SETTINGS_ENTRY_PACKAGE,
            activityName = BUILTIN_SETTINGS_ENTRY_COMPONENT.substringAfter('/'),
            cachedIcon = cachedIcons.sharp,
            cachedBlurredIcon = cachedIcons.softened,
            cachedTwoToneIcon = cachedIcons.twoToneSharp,
            cachedTwoToneBlurredIcon = cachedIcons.twoToneSoftened,
            cachedPlainIcon = cachedIcons.sharp,
            cachedPlainBlurredIcon = cachedIcons.softened,
            cachedPlainTwoToneIcon = cachedIcons.twoToneSharp,
            cachedPlainTwoToneBlurredIcon = cachedIcons.twoToneSoftened,
            isBuiltInSettingsEntry = true
        )
    }

    private fun applyFilters() {
        _apps.value = _allApps.value.mapNotNull(::visibleAppListItem)
    }

    private fun visibleAppListItem(app: AppInfo): AppInfo? {
        if (!app.isFolder) return app.takeUnless(::isHiddenApp)
        val visibleChildren = app.folderItemKeys.mapNotNull(itemCatalog::get).filterNot(::isHiddenApp)
        if (visibleChildren.isEmpty()) return null
        if (visibleChildren.map { it.componentKey } == app.folderItemKeys) return app
        return createFolderEntry(
            folder = StoredAppListFolder(
                id = app.activityName,
                name = app.label,
                itemKeys = visibleChildren.map { it.componentKey }
            ),
            childItems = visibleChildren,
            iconSize = currentIconSize,
            useTwoToneIcons = useTwoToneIcons
        )
    }

    private fun isHiddenApp(app: AppInfo): Boolean {
        if (app.isFolder) return false
        return hiddenComponents.contains(app.componentKey) || hiddenComponents.contains(app.packageName)
    }
}
