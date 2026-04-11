package com.flue.launcher.data.repository

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.BuildConfig
import com.flue.launcher.iconpack.IconPackMapping
import com.flue.launcher.iconpack.IconPackScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppIconStoreStats(
    val sharpCacheHits: Int = 0,
    val sharpCacheMisses: Int = 0,
    val blurCacheHits: Int = 0,
    val blurCacheMisses: Int = 0,
    val blurredIconGenerations: Int = 0
)

class AppIconStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packageManager = context.packageManager
    private val lock = Any()

    private var currentIconSize = 128
    private var iconPackPackage: String? = null
    private var useLegacyCircularIcons = false
    private var configRevision = 0L
    private var iconPackMapping: IconPackMapping? = null
    private var iconPackContext: Context? = null

    private val sharpBitmapCache = BitmapByteLruCache(maxBytes = 10 * 1024 * 1024)
    private val blurredBitmapCache = BitmapByteLruCache(maxBytes = 8 * 1024 * 1024)
    private val composeCache = object : LinkedHashMap<IconRequestKey, ImageBitmap>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IconRequestKey, ImageBitmap>?): Boolean {
            return size > 96
        }
    }
    private val inFlight = mutableSetOf<IconRequestKey>()

    private val _iconVersion = MutableStateFlow(0L)
    val iconVersion: StateFlow<Long> = _iconVersion.asStateFlow()

    private val _debugStats = MutableStateFlow(AppIconStoreStats())
    val debugStats: StateFlow<AppIconStoreStats> = _debugStats.asStateFlow()

    fun updateConfig(
        iconSize: Int = currentIconSize,
        iconPackPackage: String? = this.iconPackPackage,
        useLegacyCircularIcons: Boolean = this.useLegacyCircularIcons
    ) {
        val normalizedPack = iconPackPackage?.takeIf { it.isNotBlank() }
        val changed = synchronized(lock) {
            val sameSize = currentIconSize == iconSize
            val samePack = this.iconPackPackage == normalizedPack
            val sameLegacy = this.useLegacyCircularIcons == useLegacyCircularIcons
            if (sameSize && samePack && sameLegacy) {
                false
            } else {
                currentIconSize = iconSize
                this.iconPackPackage = normalizedPack
                this.useLegacyCircularIcons = useLegacyCircularIcons
                if (!samePack) {
                    iconPackMapping = normalizedPack?.let { IconPackScanner.loadMapping(context, it) }
                    iconPackContext = normalizedPack?.let {
                        runCatching {
                            context.createPackageContext(
                                it,
                                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                            )
                        }.getOrNull()
                    }
                }
                configRevision += 1L
                true
            }
        }
        if (changed) {
            invalidateAll()
        }
    }

    fun invalidateAll() {
        synchronized(lock) {
            sharpBitmapCache.clear()
            blurredBitmapCache.clear()
            composeCache.clear()
            inFlight.clear()
        }
        bumpVersion()
    }

    fun get(componentKey: String, blurred: Boolean): ImageBitmap? {
        val requestKey = IconRequestKey(componentKey = componentKey, blurred = blurred)
        synchronized(lock) {
            composeCache[requestKey]?.let {
                recordCacheHit(blurred)
                return it
            }
        }

        val bitmap = synchronized(lock) {
            if (blurred) blurredBitmapCache.get(componentKey) else sharpBitmapCache.get(componentKey)
        }
        if (bitmap != null) {
            recordCacheHit(blurred)
            return bitmap.toComposeImage(requestKey)
        }

        recordCacheMiss(blurred)
        prefetch(
            componentKeys = listOf(componentKey),
            blurredKeys = if (blurred) setOf(componentKey) else emptySet(),
            iconSize = currentIconSize
        )
        if (blurred) {
            return get(componentKey, blurred = false)
        }
        return null
    }

    fun prefetch(componentKeys: List<String>, blurredKeys: Set<String>, iconSize: Int) {
        if (componentKeys.isEmpty()) return
        updateConfig(iconSize = iconSize)
        val distinctKeys = componentKeys
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (distinctKeys.isEmpty()) return

        scope.launch {
            var changed = false
            distinctKeys.forEach { componentKey ->
                changed = ensureIcon(componentKey, blurred = false) || changed
                if (blurredKeys.contains(componentKey)) {
                    changed = ensureIcon(componentKey, blurred = true) || changed
                }
            }
            if (changed) {
                bumpVersion()
            }
        }
    }

    fun destroy() {
        scope.cancel()
        synchronized(lock) {
            sharpBitmapCache.clear()
            blurredBitmapCache.clear()
            composeCache.clear()
            inFlight.clear()
        }
    }

    private fun ensureIcon(componentKey: String, blurred: Boolean): Boolean {
        val requestKey = IconRequestKey(componentKey = componentKey, blurred = blurred)
        val snapshot = synchronized(lock) {
            if (composeCache.containsKey(requestKey)) {
                recordCacheHit(blurred)
                return false
            }
            if (blurred) {
                blurredBitmapCache.get(componentKey)?.let {
                    recordCacheHit(true)
                    composeCache[requestKey] = it.asImageBitmap()
                    return true
                }
            } else {
                sharpBitmapCache.get(componentKey)?.let {
                    recordCacheHit(false)
                    composeCache[requestKey] = it.asImageBitmap()
                    return true
                }
            }
            if (!inFlight.add(requestKey)) return false
            IconConfigSnapshot(
                revision = configRevision,
                iconSize = currentIconSize,
                useLegacyCircularIcons = useLegacyCircularIcons,
                iconPackMapping = iconPackMapping,
                iconPackContext = iconPackContext
            )
        }

        return try {
            val sharpBitmap = ensureSharpBitmap(componentKey, snapshot) ?: return false
            if (!blurred) {
                synchronized(lock) {
                    if (configRevision != snapshot.revision) return false
                    composeCache[requestKey] = sharpBitmap.asImageBitmap()
                }
                true
            } else {
                val blurredBitmap = synchronized(lock) {
                    blurredBitmapCache.get(componentKey)
                } ?: createSoftenedBitmap(sharpBitmap).also { softened ->
                    synchronized(lock) {
                        if (configRevision != snapshot.revision) return false
                        blurredBitmapCache.put(componentKey, softened)
                    }
                    recordBlurredGeneration()
                }
                synchronized(lock) {
                    if (configRevision != snapshot.revision) return false
                    composeCache[requestKey] = blurredBitmap.asImageBitmap()
                }
                true
            }
        } finally {
            synchronized(lock) {
                inFlight.remove(requestKey)
            }
        }
    }

    private fun ensureSharpBitmap(componentKey: String, snapshot: IconConfigSnapshot): Bitmap? {
        synchronized(lock) {
            sharpBitmapCache.get(componentKey)?.let {
                return it
            }
        }

        val drawable = resolveIconDrawable(componentKey, snapshot) ?: return null
        val createdBitmap = if (snapshot.useLegacyCircularIcons && snapshot.iconPackMapping == null) {
            createCircularBitmap(
                drawableToBitmap(drawable, snapshot.iconSize),
                edgeInsetPx = snapshot.iconSize * 0.015f
            )
        } else {
            drawable.toBitmap(snapshot.iconSize, snapshot.iconSize, Bitmap.Config.ARGB_8888)
        }

        synchronized(lock) {
            if (configRevision != snapshot.revision) return null
            sharpBitmapCache.put(componentKey, createdBitmap)
        }
        return createdBitmap
    }

    private fun resolveIconDrawable(componentKey: String, snapshot: IconConfigSnapshot): Drawable? {
        val packed = resolvePackedIconDrawable(componentKey, snapshot)
        if (packed != null) return packed

        val packageName = componentKey.substringBefore('/', missingDelimiterValue = componentKey)
        val activityName = componentKey.substringAfter('/', missingDelimiterValue = "")
        if (packageName.isBlank() || activityName.isBlank()) return null
        val componentName = ComponentName(packageName, activityName)

        return runCatching {
            packageManager.getActivityIcon(componentName)
        }.getOrElse {
            runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
    }

    private fun resolvePackedIconDrawable(componentKey: String, snapshot: IconConfigSnapshot): Drawable? {
        val mapping = snapshot.iconPackMapping ?: return null
        val packContext = snapshot.iconPackContext ?: return null
        val drawableName = mapping.componentToDrawable[componentKey] ?: return null
        val resources = packContext.resources
        val resId = resources.getIdentifier(drawableName, "drawable", mapping.descriptor.packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(drawableName, "mipmap", mapping.descriptor.packageName)
        if (resId == 0) return null
        return runCatching { resources.getDrawable(resId, packContext.theme) }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
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

    private fun Bitmap.toComposeImage(requestKey: IconRequestKey): ImageBitmap {
        val image = asImageBitmap()
        synchronized(lock) {
            composeCache[requestKey] = image
        }
        return image
    }

    private fun bumpVersion() {
        _iconVersion.value = _iconVersion.value + 1L
    }

    private fun recordCacheHit(blurred: Boolean) {
        if (!BuildConfig.DEBUG) return
        _debugStats.value = _debugStats.value.let { current ->
            if (blurred) {
                current.copy(blurCacheHits = current.blurCacheHits + 1)
            } else {
                current.copy(sharpCacheHits = current.sharpCacheHits + 1)
            }
        }
    }

    private fun recordCacheMiss(blurred: Boolean) {
        if (!BuildConfig.DEBUG) return
        _debugStats.value = _debugStats.value.let { current ->
            if (blurred) {
                current.copy(blurCacheMisses = current.blurCacheMisses + 1)
            } else {
                current.copy(sharpCacheMisses = current.sharpCacheMisses + 1)
            }
        }
    }

    private fun recordBlurredGeneration() {
        if (!BuildConfig.DEBUG) return
        _debugStats.value = _debugStats.value.copy(
            blurredIconGenerations = _debugStats.value.blurredIconGenerations + 1
        )
    }

    private data class IconRequestKey(
        val componentKey: String,
        val blurred: Boolean
    )

    private data class IconConfigSnapshot(
        val revision: Long,
        val iconSize: Int,
        val useLegacyCircularIcons: Boolean,
        val iconPackMapping: IconPackMapping?,
        val iconPackContext: Context?
    )

    private class BitmapByteLruCache(private val maxBytes: Int) {
        private val entries = LinkedHashMap<String, Bitmap>(0, 0.75f, true)
        private var totalBytes = 0

        fun get(key: String): Bitmap? = entries[key]

        fun put(key: String, bitmap: Bitmap) {
            entries.put(key, bitmap)?.let { previous ->
                totalBytes -= previous.allocationByteCount
            }
            totalBytes += bitmap.allocationByteCount
            trimToSize()
        }

        fun clear() {
            entries.clear()
            totalBytes = 0
        }

        private fun trimToSize() {
            while (totalBytes > maxBytes && entries.isNotEmpty()) {
                val iterator = entries.entries.iterator()
                val eldest = iterator.next()
                totalBytes -= eldest.value.allocationByteCount
                iterator.remove()
            }
        }
    }
}
