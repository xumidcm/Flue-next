package com.flue.launcher.watchface

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import dalvik.system.DexClassLoader
import org.xmlpull.v1.XmlPullParser

object LunchWatchFaceScanner {
    fun builtInDescriptor(): LunchWatchFaceDescriptor = LunchWatchFaceDescriptor(
        id = BUILT_IN_WATCHFACE_ID,
        type = LunchWatchFaceType.BUILTIN,
        displayName = "Flue Default"
    )

    fun scanInstalled(context: Context): List<LunchWatchFaceDescriptor> {
        val pm = context.packageManager
        val intent = Intent(WATCHFACE_ACTION)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return resolveInfos.mapNotNull { info ->
            runCatching {
                val packageName = info.activityInfo.packageName
                val packageContext = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
                val resources = packageContext.resources
                val configId = resources.getIdentifier("watchface_config", "xml", packageName)
                if (configId == 0) return@runCatching null
                val parser = resources.getXml(configId)
                var eventType = parser.eventType
                var displayName = info.loadLabel(pm)?.toString().orEmpty()
                var watchFaceClass = ""
                var settingsClass = ""
                var previewResId = 0
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        previewResId = parser.getAttributeResourceValue(null, "preview", 0)
                        displayName = parser.getAttributeValue(null, "name") ?: displayName
                        watchFaceClass = parser.getAttributeValue(null, "watchface").orEmpty()
                        settingsClass = parser.getAttributeValue(null, "settings_activity").orEmpty()
                        break
                    }
                    eventType = parser.next()
                }
                if (watchFaceClass.isBlank()) return@runCatching null

                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
                val apkPath = appInfo.sourceDir
                val dexLoader = createDexClassLoader(context, apkPath)
                val buildConfigClassName = findBuildConfigClassName(watchFaceClass)
                val watchFaceName = readStaticString(dexLoader, buildConfigClassName, "WATCHFACE_NAME")
                    ?: readStaticString(dexLoader, buildConfigClassName, "DISPLAY_NAME")
                    ?: packageName
                val author = readStaticString(dexLoader, buildConfigClassName, "AUTHOR")
                val previewAssetPath = if (hasAssetPreview(packageContext)) "preview.png" else null
                LunchWatchFaceDescriptor(
                    id = packageName,
                    type = LunchWatchFaceType.EXTERNAL,
                    displayName = displayName.ifBlank { packageName },
                    packageName = packageName,
                    watchFaceClassName = watchFaceClass,
                    settingsEntryClassName = settingsClass.ifBlank { null },
                    previewResId = previewResId,
                    previewAssetPath = previewAssetPath,
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong(),
                    author = author,
                    sourceApkPath = apkPath,
                    watchFaceName = watchFaceName,
                    buildConfigClassName = buildConfigClassName
                )
            }.getOrNull()
        }.sortedBy { it.displayName.lowercase() }
    }

    fun loadPreviewDrawable(context: Context, descriptor: LunchWatchFaceDescriptor): Drawable? {
        if (descriptor.isBuiltin) return null
        val packageName = descriptor.packageName ?: return null
        return runCatching {
            val packageContext = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
            when {
                descriptor.previewResId != 0 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        packageContext.getDrawable(descriptor.previewResId)
                    } else {
                        @Suppress("DEPRECATION")
                        packageContext.resources.getDrawable(descriptor.previewResId)
                    }
                }
                descriptor.previewAssetPath != null -> packageContext.assets.open(descriptor.previewAssetPath).use {
                    Drawable.createFromStream(it, descriptor.previewAssetPath)
                }
                else -> packageContext.applicationInfo.loadIcon(context.packageManager)
            }
        }.getOrNull() ?: context.packageManager.defaultActivityIcon
    }

    fun createDexClassLoader(context: Context, apkPath: String): DexClassLoader {
        return DexClassLoader(apkPath, context.codeCacheDir.absolutePath, null, context.classLoader)
    }

    private fun hasAssetPreview(context: Context): Boolean {
        return runCatching { context.assets.open("preview.png").close(); true }.getOrDefault(false)
    }

    private fun findBuildConfigClassName(watchFaceClass: String): String {
        val packageName = watchFaceClass.substringBeforeLast('.', missingDelimiterValue = watchFaceClass)
        return "$packageName.BuildConfig"
    }

    private fun readStaticString(loader: ClassLoader, className: String, fieldName: String): String? {
        return runCatching {
            val clazz = loader.loadClass(className)
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(null)?.toString()
        }.getOrNull()
    }
}
