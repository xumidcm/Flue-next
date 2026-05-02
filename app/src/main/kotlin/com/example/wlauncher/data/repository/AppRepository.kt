package com.flue.launcher.data.repository

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.R
import com.flue.launcher.data.model.AppInfo
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
import java.util.concurrent.ConcurrentHashMap

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
    }

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val allApps: StateFlow<List<AppInfo>> = _allApps.asStateFlow()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private var customOrder: List<String> = emptyList()
    private var customOrderIndexMap: Map<String, Int> = emptyMap()
    private var hiddenComponents: Set<String> = emptySet()
    private var currentIconSize = 128
    private var iconPackPackage: String? = null
    private var useLegacyCircularIcons = true
    private var useTwoToneIcons = false
    private var refreshGeneration = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMonitor = Any()
    private var refreshJob: Job? = null
    private var packageRefreshJob: Job? = null
    private val installTimeCache = mutableMapOf<String, Long>()
    private val iconLocks = ConcurrentHashMap<String, Any>()
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
        synchronized(iconCache) { iconCache.clear() }
        refreshAsync(nextIconSize)
    }

    private fun reorder() {
        _allApps.value = sortApps(_allApps.value)
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
            val iconCacheKey = "${iconPackPackageSnapshot ?: "sys"}|$componentKey|$iconSize|${if (useLegacyCircularIconsSnapshot) "legacy" else "plain"}|${if (useTwoToneIconsSnapshot) "two_tone" else "normal"}"
            val cachedIcons = getOrCreateIconSet(iconCacheKey) {
                val packedIcon = iconPackMapping?.let { IconPackScanner.loadIconDrawable(context, it, componentKey) }
                val resolvedIconDrawable = packedIcon ?: ri.loadIcon(pm)
                val baseBitmap = if (useLegacyCircularIconsSnapshot && packedIcon == null) {
                    createCircularBitmap(
                        stripEdgeBlackMatte(drawableToBitmap(resolvedIconDrawable, iconSize)),
                        edgeInsetPx = iconSize * 0.005f
                    )
                } else {
                    stripEdgeBlackMatte(
                        resolvedIconDrawable.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                    )
                }
                val sharp = baseBitmap.asImageBitmap()
                val softened = createSoftenedBitmap(baseBitmap).asImageBitmap()
                val twoToneBitmap = if (useTwoToneIconsSnapshot) {
                    createTwoToneBitmap(resolvedIconDrawable, iconSize)
                } else {
                    null
                }
                val twoToneSharp = twoToneBitmap?.asImageBitmap()
                val twoToneSoftened = twoToneBitmap?.let(::createSoftenedBitmap)?.asImageBitmap()
                CachedIconSet(
                    sharp = sharp,
                    softened = softened,
                    twoToneSharp = twoToneSharp,
                    twoToneSoftened = twoToneSoftened
                )
            }
            loadedApps += AppInfo(
                label = ri.loadLabel(pm).toString(),
                packageName = packageName,
                activityName = ri.activityInfo.name,
                cachedIcon = cachedIcons.sharp,
                cachedBlurredIcon = cachedIcons.softened,
                cachedTwoToneIcon = cachedIcons.twoToneSharp,
                cachedTwoToneBlurredIcon = cachedIcons.twoToneSoftened
            )
        }
        createBuiltInSettingsEntry(iconSize, useTwoToneIconsSnapshot)?.let(loadedApps::add)

        if (synchronized(this) { generation != refreshGeneration }) return

        val knownPackages = loadedApps.mapTo(linkedSetOf()) { it.packageName }
        synchronized(installTimeCache) {
            installTimeCache.keys.retainAll(knownPackages)
        }

        _allApps.value = sortApps(loadedApps)
        applyFilters()
    }

    fun launchApp(appInfo: AppInfo): Boolean {
        if (appInfo.isBuiltInSettingsEntry) {
            val settingsIntent = Intent(context, com.flue.launcher.SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val options = android.app.ActivityOptions.makeCustomAnimation(
                context,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            return try {
                context.startActivity(settingsIntent, options.toBundle())
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

    private fun refreshAsync(iconSize: Int = currentIconSize) {
        synchronized(refreshMonitor) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                refresh(iconSize)
            }
        }
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
        return createTintedGrayscaleBitmap(drawableToBitmap(drawable, size))
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
                    .thenBy { if (it.isBuiltInSettingsEntry && it.componentKey !in customOrderIndexMap) 1 else 0 }
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

    private fun createBuiltInSettingsEntry(iconSize: Int, useTwoToneIconsSnapshot: Boolean): AppInfo? {
        val iconDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_settings_launcher) ?: return null
        val iconCacheKey = "builtin_settings|$iconSize|${if (useTwoToneIconsSnapshot) "two_tone" else "normal"}"
        val cachedIcons = getOrCreateIconSet(iconCacheKey) {
            val baseBitmap = stripEdgeBlackMatte(drawableToBitmap(iconDrawable, iconSize))
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
            isBuiltInSettingsEntry = true
        )
    }

    private fun applyFilters() {
        _apps.value = _allApps.value.filterNot { app ->
            hiddenComponents.contains(app.componentKey) || hiddenComponents.contains(app.packageName)
        }
    }
}
