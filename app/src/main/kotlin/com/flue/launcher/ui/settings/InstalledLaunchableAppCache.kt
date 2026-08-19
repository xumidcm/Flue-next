package com.flue.launcher.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Lightweight process cache shared by every watch app picker. */
internal data class InstalledLaunchableApp(
    val label: String,
    val packageName: String,
    val activityClass: String,
) {
    val component: ComponentName get() = ComponentName(packageName, activityClass)
}

/**
 * 已安装可启动应用的进程级缓存：**元数据一份、图标一份**。
 *
 * ## 为什么图标也要缓存
 *
 * 名单本身早就有 TTL 缓存了，但图标以前是每次打开选择器现查
 * （`getActivityIcon` + `toBitmap`，逐个应用一次），于是「第二次打开还是一样慢」——
 * 慢的从来不是那份名单，是一两百次图标解码。图标按组件缓存之后，第二次打开是纯命中。
 *
 * ## 为什么图标要按需取而不是随名单一起批量解码
 *
 * 手表上一屏只放得下五六行。批量解码等于为了看六个图标先解一百个，首次打开反而更慢；
 * 按需取让首屏只付六次解码的代价，滚动到哪解到哪，且解过的永远不再解。
 *
 * ## 缓存不随主题/风格失效
 *
 * 这里存的是应用自己的图标位图，与 Flue 的风格无关，所以不需要跟着风格切换失效。
 * 应用装卸由 [invalidate] 统一清（名单与图标一起清 —— 图标留着会指向已卸载的组件）。
 */
internal object InstalledLaunchableAppCache {
    private data class Snapshot(
        val loadedAtMs: Long,
        val apps: List<InstalledLaunchableApp>,
    )

    private const val TTL_MS = 5L * 60L * 1_000L

    /** 图标解码边长（px）。选择器行里最大显示 36dp，72px 在高密度屏上也够清晰。 */
    const val ICON_DECODE_PX = 72

    /**
     * 图标缓存上限（条）。
     *
     * 72×72 ARGB_8888 ≈ 20KB，256 条约 5MB —— 手表上装到 256 个可启动应用已属极端，
     * 真到了上限也只是最早的那些要重解一次，不会无界增长。
     */
    private const val MAX_ICON_ENTRIES = 256

    private val lock = Any()
    private val iconLock = Any()

    @Volatile
    private var snapshot: Snapshot? = null

    /** access-order LRU：满了淘汰最久没被用到的那条。 */
    private val iconCache = object : LinkedHashMap<ComponentName, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ComponentName, ImageBitmap>?,
        ): Boolean = size > MAX_ICON_ENTRIES
    }

    /** Call from a background dispatcher; PackageManager label queries can be slow. */
    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): List<InstalledLaunchableApp> {
        snapshot?.takeIf { nowMs - it.loadedAtMs in 0 until TTL_MS }?.let { return it.apps }
        synchronized(lock) {
            snapshot?.takeIf { nowMs - it.loadedAtMs in 0 until TTL_MS }?.let { return it.apps }
            val manager = context.applicationContext.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded = manager.queryIntentActivities(launcher, 0)
                .mapNotNull { info ->
                    val packageName = info.activityInfo?.packageName.orEmpty()
                    val activity = info.activityInfo?.name.orEmpty()
                    if (packageName.isBlank() || activity.isBlank()) return@mapNotNull null
                    InstalledLaunchableApp(
                        label = info.loadLabel(manager).toString(),
                        packageName = packageName,
                        activityClass = activity,
                    )
                }
                .distinctBy(InstalledLaunchableApp::component)
                .sortedBy { it.label.lowercase() }
            snapshot = Snapshot(loadedAtMs = nowMs, apps = loaded)
            return loaded
        }
    }

    /** 已经在缓存里的图标。主线程可调 —— 纯查表，不解码。 */
    fun cachedIcon(component: ComponentName): ImageBitmap? =
        synchronized(iconLock) { iconCache[component] }

    /**
     * 取图标，缓存没有就解码一次并存下。
     *
     * **必须在后台调度器上调**：`getActivityIcon` 会读 APK 资源，图标是 vector 时还要
     * 现场栅格化。取不到（应用刚被卸载、资源损坏）返回 null，调用方退回占位符。
     */
    fun icon(context: Context, component: ComponentName): ImageBitmap? {
        cachedIcon(component)?.let { return it }
        val decoded = runCatching {
            context.applicationContext.packageManager
                .getActivityIcon(component)
                .toBitmap(ICON_DECODE_PX, ICON_DECODE_PX)
                .asImageBitmap()
        }.getOrNull() ?: return null
        synchronized(iconLock) {
            // 并发解码同一个组件时保留先到的那份，避免同一图标在缓存里换来换去。
            iconCache[component]?.let { return it }
            iconCache[component] = decoded
        }
        return decoded
    }

    fun invalidate() {
        synchronized(lock) { snapshot = null }
        synchronized(iconLock) { iconCache.clear() }
    }
}
