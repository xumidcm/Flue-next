package com.flue.launcher.data.repository

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import com.flue.launcher.data.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppRepository(private val context: Context) {

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val allApps: StateFlow<List<AppInfo>> = _allApps.asStateFlow()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    val iconStore = AppIconStore(context)

    private var customOrder: List<String> = emptyList()
    private var customOrderIndexMap: Map<String, Int> = emptyMap()
    private var hiddenComponents: Set<String> = emptySet()
    private var currentIconSize = 128
    private var iconPackPackage: String? = null
    private var useLegacyCircularIcons = false
    private var refreshGeneration = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val installTimeCache = mutableMapOf<String, Long>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            refreshAsync()
        }
    }

    init {
        iconStore.updateConfig(
            iconSize = currentIconSize,
            iconPackPackage = iconPackPackage,
            useLegacyCircularIcons = useLegacyCircularIcons
        )
        refreshAsync()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
    }

    fun setCustomOrder(order: List<String>) {
        customOrder = order
        customOrderIndexMap = order.withIndex().associate { it.value to it.index }
        reorder()
    }

    fun setHiddenComponents(components: Set<String>) {
        hiddenComponents = components
        applyFilters()
        warmVisibleIcons()
    }

    fun setIconPackPackage(packageName: String?) {
        val normalized = packageName?.takeIf { it.isNotBlank() }
        if (iconPackPackage == normalized) return
        iconPackPackage = normalized
        iconStore.updateConfig(
            iconSize = currentIconSize,
            iconPackPackage = iconPackPackage,
            useLegacyCircularIcons = useLegacyCircularIcons
        )
        warmVisibleIcons()
    }

    fun setLegacyCircularIconsEnabled(enabled: Boolean) {
        if (useLegacyCircularIcons == enabled) return
        useLegacyCircularIcons = enabled
        iconStore.updateConfig(
            iconSize = currentIconSize,
            iconPackPackage = iconPackPackage,
            useLegacyCircularIcons = useLegacyCircularIcons
        )
        warmVisibleIcons()
    }

    fun setIconSize(iconSize: Int) {
        val normalizedSize = iconSize.coerceAtLeast(32)
        if (currentIconSize == normalizedSize) return
        currentIconSize = normalizedSize
        iconStore.updateConfig(
            iconSize = currentIconSize,
            iconPackPackage = iconPackPackage,
            useLegacyCircularIcons = useLegacyCircularIcons
        )
        warmVisibleIcons()
    }

    fun getIcon(componentKey: String, blurred: Boolean): ImageBitmap? {
        return iconStore.get(componentKey, blurred)
    }

    fun observeIcon(componentKey: String, blurred: Boolean): StateFlow<ImageBitmap?> {
        return iconStore.observe(componentKey, blurred)
    }

    fun prefetchIcons(componentKeys: List<String>, blurredKeys: Set<String> = emptySet()) {
        iconStore.prefetch(componentKeys, blurredKeys, currentIconSize)
    }

    private fun reorder() {
        _allApps.value = sortApps(_allApps.value)
        applyFilters()
        warmVisibleIcons()
    }

    fun refresh(iconSize: Int = currentIconSize) {
        val generation = synchronized(this) {
            refreshGeneration += 1
            refreshGeneration
        }
        setIconSize(iconSize)
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
        val myPackage = context.packageName

        val resolveList = resolveInfos
            .filter { ri ->
                !(ri.activityInfo.packageName == myPackage &&
                    ri.activityInfo.name == "com.flue.launcher.LauncherActivity")
            }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }

        val loadedApps = ArrayList<AppInfo>(resolveList.size)
        resolveList.forEach { ri ->
            loadedApps += AppInfo(
                label = ri.loadLabel(pm).toString(),
                packageName = ri.activityInfo.packageName,
                activityName = ri.activityInfo.name
            )
        }

        if (synchronized(this) { generation != refreshGeneration }) return

        val knownPackages = loadedApps.mapTo(linkedSetOf()) { it.packageName }
        synchronized(installTimeCache) {
            installTimeCache.keys.retainAll(knownPackages)
        }

        _allApps.value = sortApps(loadedApps)
        applyFilters()
        warmVisibleIcons()
    }

    fun launchApp(appInfo: AppInfo): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(appInfo.packageName, appInfo.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val options = android.app.ActivityOptions.makeCustomAnimation(
            context,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        return try {
            context.startActivity(intent, options.toBundle())
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
        scope.cancel()
        iconStore.destroy()
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
    }

    private fun refreshAsync(iconSize: Int = currentIconSize) {
        scope.launch {
            refresh(iconSize)
        }
    }

    private fun orderRank(app: AppInfo): Int {
        if (customOrder.isEmpty()) return Int.MAX_VALUE
        return customOrderIndexMap[app.componentKey]
            ?: customOrderIndexMap[app.packageName]
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

    private fun sortApps(apps: List<AppInfo>): List<AppInfo> {
        return if (customOrder.isNotEmpty()) {
            apps.sortedWith(
                compareBy<AppInfo> { orderRank(it) }
                    .thenBy { packageInstallTime(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        } else {
            apps.sortedWith(
                compareBy<AppInfo> { packageInstallTime(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        }
    }

    private fun applyFilters() {
        _apps.value = _allApps.value.filterNot { app ->
            hiddenComponents.contains(app.componentKey) || hiddenComponents.contains(app.packageName)
        }
    }

    private fun warmVisibleIcons() {
        val visibleKeys = _apps.value
            .asSequence()
            .take(24)
            .map(AppInfo::componentKey)
            .toList()
        if (visibleKeys.isNotEmpty()) {
            prefetchIcons(visibleKeys)
        }
    }
}
