package com.flue.launcher.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flue.launcher.applist.background.AppListBackgroundJson
import com.flue.launcher.data.repository.WidgetRepository
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import com.flue.launcher.watchface.WatchFacePhotoCache
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

data class FlueBackupOptions(
    val settings: Boolean = true,
    val appOrder: Boolean = true,
    val widgetOrder: Boolean = true,
    val sideScreenOrder: Boolean = true,
    val photoWatchFace: Boolean = true,
    val videoWatchFace: Boolean = true,
    /** 已安装的 .watch 表盘文件（WatchmakerWatchFaceStorage 里的表盘）。 */
    val watchFiles: Boolean = true
) {
    val hasAny: Boolean
        get() = settings || appOrder || widgetOrder || sideScreenOrder ||
            photoWatchFace || videoWatchFace || watchFiles

    fun constrainedBy(available: FlueBackupOptions): FlueBackupOptions {
        return FlueBackupOptions(
            settings = settings && available.settings,
            appOrder = appOrder && available.appOrder,
            widgetOrder = widgetOrder && available.widgetOrder,
            sideScreenOrder = sideScreenOrder && available.sideScreenOrder,
            photoWatchFace = photoWatchFace && available.photoWatchFace,
            videoWatchFace = videoWatchFace && available.videoWatchFace,
            watchFiles = watchFiles && available.watchFiles
        )
    }

    /**
     * 勾选的分区，按声明顺序给出稳定 key。UI 侧据此查 strings 拼展示名，
     * 于是「顺序」与「key→名称」各只有一处定义，两边不会写歪。
     */
    fun selectedSectionKeys(): List<String> = buildList {
        if (settings) add(SECTION_SETTINGS)
        if (appOrder) add(SECTION_APP_ORDER)
        if (widgetOrder) add(SECTION_WIDGET_ORDER)
        if (sideScreenOrder) add(SECTION_SIDE_SCREEN_ORDER)
        if (photoWatchFace) add(SECTION_PHOTO_WATCH_FACE)
        if (videoWatchFace) add(SECTION_VIDEO_WATCH_FACE)
        if (watchFiles) add(SECTION_WATCH_FILES)
    }

    companion object {
        const val SECTION_SETTINGS = "settings"
        const val SECTION_APP_ORDER = "appOrder"
        const val SECTION_WIDGET_ORDER = "widgetOrder"
        const val SECTION_SIDE_SCREEN_ORDER = "sideScreenOrder"
        const val SECTION_PHOTO_WATCH_FACE = "photoWatchFace"
        const val SECTION_VIDEO_WATCH_FACE = "videoWatchFace"
        const val SECTION_WATCH_FILES = "watchFiles"
    }
}

data class FlueBackupPreview(
    val appVersion: String,
    val createdAt: String,
    val availableOptions: FlueBackupOptions
)

/** 导出结果摘要，供「导出完成」页显示内容 / 大小 / 时间三行灰字。 */
data class FlueBackupExportSummary(
    /** 实际落盘的分区（就是传给 [FlueBackupManager.exportBackup] 的那份）。 */
    val sections: FlueBackupOptions,
    /**
     * 备份包字节数。在写出流上累计得到，**不**写完再 openFileDescriptor 读
     * statSize —— SAF provider 不保证 statSize 可靠，累计值更准。
     */
    val bytes: Long,
    /** 与包内 manifest 的 createdAt 同源，格式 `yyyy-MM-dd HH:mm:ss`。 */
    val createdAt: String,
)

/** 只做字节计数的透传流，用来在写出过程中得到备份包真实大小。 */
internal class CountingOutputStream(
    private val delegate: java.io.OutputStream,
) : java.io.OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count += 1L
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len.toLong()
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

object FlueBackupManager {
    private const val SCHEMA_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val PHOTO_MEDIA_ENTRY = "watchfaces/photo/media"
    private const val VIDEO_MEDIA_ENTRY = "watchfaces/video/media"
    private const val FONT_ENTRY = "watchfaces/font/current"

    private const val WATCH_ARCHIVES_PREFIX = "watchfaces/watch/"

    /**
     * 自定义应用列表背景图在备份包里的条目。
     *
     * 设置里存的是**绝对路径**，只带路径过去没用：换机 / 重装后那个文件不在，配置就变成
     * 一个指向空气的路径（渲染侧会落回纯色，不崩，但老师的背景没了）。所以图跟着走。
     */
    private const val APP_LIST_BACKGROUND_ENTRY = "app_list_background/image"

    /**
     * 还原时背景图落进哪个子目录。与图片 / 视频表盘同一套 `internal_watchfaces/<folder>`
     * 布局，于是 [copyInternalWatchFaceMedia] 的「先清目录再写」语义直接复用 ——
     * 一份背景图只留一张，不会随着反复恢复堆积。
     */
    private const val APP_LIST_BACKGROUND_MEDIA_FOLDER = "app_list_background"

    private val appOrderKey = LauncherViewModel.KEY_APP_ORDER.name
    private val sideScreenShortcutsKey = LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS.name
    private val sideScreenWidgetsKey = LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS.name
    private val hiddenAppsKey = LauncherViewModel.KEY_HIDDEN_APPS.name
    private val photoPathKey = LauncherViewModel.KEY_BUILTIN_PHOTO_PATH.name
    private val videoPathKey = LauncherViewModel.KEY_BUILTIN_VIDEO_PATH.name
    private val fontPathKey = LauncherViewModel.KEY_WATCHFACE_FONT_PATH.name
    private val lastWatchFaceErrorKey = LauncherViewModel.KEY_LAST_WATCHFACE_ERROR.name

    private val photoPreferenceNames = setOf(
        photoPathKey,
        LauncherViewModel.KEY_PHOTO_CLOCK_POSITION.name,
        LauncherViewModel.KEY_PHOTO_CLOCK_SIZE.name,
        LauncherViewModel.KEY_PHOTO_CLOCK_BOLD.name,
        LauncherViewModel.KEY_PHOTO_CLOCK_STYLE.name,
        LauncherViewModel.KEY_PHOTO_MD3E_SHAPE.name,
        LauncherViewModel.KEY_PHOTO_USE_THEME_TEXT_COLOR.name,
        LauncherViewModel.KEY_PHOTO_TEXT_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_AUTO_COLORS.name,
        LauncherViewModel.KEY_PHOTO_MD3E_TEXT_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_FACE_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_HOUR_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_MINUTE_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_SECOND_COLOR.name,
        LauncherViewModel.KEY_PHOTO_SHOW_SECONDS.name,
        LauncherViewModel.KEY_PHOTO_CUSTOM_TEXT.name
    )

    private val videoPreferenceNames = setOf(
        videoPathKey,
        LauncherViewModel.KEY_VIDEO_CLOCK_POSITION.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_SIZE.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_BOLD.name,
        LauncherViewModel.KEY_VIDEO_FILL_SCREEN.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_COLOR_MODE.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_STYLE.name,
        LauncherViewModel.KEY_VIDEO_MD3E_SHAPE.name,
        LauncherViewModel.KEY_VIDEO_USE_THEME_TEXT_COLOR.name,
        LauncherViewModel.KEY_VIDEO_TEXT_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_AUTO_COLORS.name,
        LauncherViewModel.KEY_VIDEO_MD3E_TEXT_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_FACE_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_HOUR_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_MINUTE_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_SECOND_COLOR.name,
        LauncherViewModel.KEY_VIDEO_SHOW_SECONDS.name,
        LauncherViewModel.KEY_VIDEO_CUSTOM_TEXT.name
    )

    private val settingsExcludedNames = setOf(
        appOrderKey,
        sideScreenShortcutsKey,
        sideScreenWidgetsKey,
        lastWatchFaceErrorKey,
        fontPathKey
    ) + photoPreferenceNames + videoPreferenceNames

    fun suggestedFileName(context: Context): String {
        val version = appVersion(context).sanitizeFilePart()
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "Flue${version}_backup_${date}.zip"
    }

    suspend fun exportBackup(
        context: Context,
        targetUri: Uri,
        options: FlueBackupOptions,
    ): FlueBackupExportSummary {
        require(options.hasAny) { "没有选择要导出的内容" }
        val prefs = context.dataStore.data.first()
        // manifest 与「导出完成」页共用同一个时间源，别另起一套。
        val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val manifest = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersion", appVersion(context))
            .put("createdAt", createdAt)
            .put("sections", options.toJson())

        if (options.settings) {
            manifest.put("settings", prefs.toPreferenceJson(excludeNames = settingsExcludedNames))
        }
        if (options.appOrder) {
            manifest.put("appOrder", prefs[LauncherViewModel.KEY_APP_ORDER].orEmpty().splitCsvJson())
        }
        if (options.widgetOrder) {
            manifest.put("widgetOrder", prefs[LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS].orEmpty().splitWidgetJson())
        }
        if (options.sideScreenOrder) {
            manifest.put("sideScreenShortcuts", prefs[LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS].orEmpty().splitShortcutJson())
        }

        val photoMedia = prefs[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH]
            ?.let(::File)
            ?.takeIf { it.isFile }
        val videoMedia = prefs[LauncherViewModel.KEY_BUILTIN_VIDEO_PATH]
            ?.let(::File)
            ?.takeIf { it.isFile }
        val fontMedia = prefs[LauncherViewModel.KEY_WATCHFACE_FONT_PATH]
            ?.let(::File)
            ?.takeIf { it.isFile }
        // 自定义应用列表背景图。路径藏在 app_list_background 那份 JSON 里，所以要先解出来。
        val appListBackgroundMedia = if (options.settings) {
            AppListBackgroundJson
                .decodeOrDefault(prefs[LauncherViewModel.KEY_APP_LIST_BACKGROUND])
                .imagePath
                ?.let(::File)
                ?.takeIf { it.isFile }
        } else {
            null
        }
        val appListBackgroundEntry = appListBackgroundMedia?.let {
            "$APP_LIST_BACKGROUND_ENTRY.${it.extension.ifBlank { "png" }}"
        }
        if (appListBackgroundEntry != null) {
            manifest.put("appListBackgroundEntry", appListBackgroundEntry)
        }

        if (options.photoWatchFace) {
            manifest.put(
                "photoWatchFace",
                JSONObject()
                    .put("preferences", prefs.toPreferenceJson(includeNames = photoPreferenceNames - photoPathKey))
                    .put("mediaEntry", photoMedia?.let { "$PHOTO_MEDIA_ENTRY.${it.extension.ifBlank { "png" }}" } ?: JSONObject.NULL)
                    .put("fontEntry", fontMedia?.let { "$FONT_ENTRY.${it.extension.ifBlank { "ttf" }}" } ?: JSONObject.NULL)
            )
        }
        if (options.videoWatchFace) {
            manifest.put(
                "videoWatchFace",
                JSONObject()
                    .put("preferences", prefs.toPreferenceJson(includeNames = videoPreferenceNames - videoPathKey))
                    .put("mediaEntry", videoMedia?.let { "$VIDEO_MEDIA_ENTRY.${it.extension.ifBlank { "mp4" }}" } ?: JSONObject.NULL)
                    .put("fontEntry", fontMedia?.let { "$FONT_ENTRY.${it.extension.ifBlank { "ttf" }}" } ?: JSONObject.NULL)
            )
        }

        // 已安装的 .watch 表盘：每个子目录打成内嵌 ZIP，记录目录列表供 import 识别。
        val watchFaceDirs = if (options.watchFiles) {
            runCatching {
                WatchmakerWatchFaceStorage.rootDirectory(context)
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
            }.getOrDefault(emptyList())
        } else emptyList()
        if (options.watchFiles && watchFaceDirs.isNotEmpty()) {
            val entries = JSONArray()
            watchFaceDirs.forEach { dir -> entries.put("$WATCH_ARCHIVES_PREFIX${dir.name}.watch") }
            manifest.put("watchFiles", JSONObject().put("archives", entries))
        }

        val output = context.contentResolver.openOutputStream(targetUri) ?: error("无法写入备份文件")
        val counting = CountingOutputStream(output)
        counting.use { counted ->
            ZipOutputStream(counted.buffered()).use { zip ->
                zip.writeTextEntry(MANIFEST_ENTRY, manifest.toString(2))
                if (options.photoWatchFace && photoMedia != null) {
                    zip.writeFileEntry(manifest.getJSONObject("photoWatchFace").getString("mediaEntry"), photoMedia)
                }
                if (options.videoWatchFace && videoMedia != null) {
                    zip.writeFileEntry(manifest.getJSONObject("videoWatchFace").getString("mediaEntry"), videoMedia)
                }
                if ((options.photoWatchFace || options.videoWatchFace) && fontMedia != null) {
                    val entry = "$FONT_ENTRY.${fontMedia.extension.ifBlank { "ttf" }}"
                    zip.writeFileEntry(entry, fontMedia)
                }
                if (appListBackgroundEntry != null) {
                    zip.writeFileEntry(appListBackgroundEntry, appListBackgroundMedia)
                }
                // 把每个已安装表盘目录打成内嵌 .watch（ZIP），写进备份包。
                if (options.watchFiles) {
                    watchFaceDirs.forEach { faceDir ->
                        val archiveName = "$WATCH_ARCHIVES_PREFIX${faceDir.name}.watch"
                        val watchBytes = ByteArrayOutputStream().also { baos ->
                            writeBackupWatchArchive(faceDir, baos)
                        }.toByteArray()
                        zip.putNextEntry(ZipEntry(archiveName))
                        zip.write(watchBytes)
                        zip.closeEntry()
                    }
                }
            }
        }
        // ZipOutputStream 关闭时才写完中央目录并 flush 到 counting，所以在 use 之后读。
        return FlueBackupExportSummary(
            sections = options,
            bytes = counting.count,
            createdAt = createdAt,
        )
    }

    suspend fun readPreview(context: Context, sourceUri: Uri): FlueBackupPreview {
        return previewFromManifest(readManifest(context, sourceUri))
    }

    suspend fun importBackup(
        context: Context,
        sourceUri: Uri,
        options: FlueBackupOptions,
        availableAppKeys: Set<String>,
        widgetRepository: WidgetRepository
    ) {
        require(options.hasAny) { "没有选择要导入的内容" }
        val tempRoot = File(context.cacheDir, "flue_backup_import_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            extractBackup(context, sourceUri, tempRoot)
            val manifest = JSONObject(File(tempRoot, MANIFEST_ENTRY).readText(Charsets.UTF_8))
            val available = previewFromManifest(manifest).availableOptions
            val selected = options.constrainedBy(available)
            val validWidgetKeys = widgetRepository.getAllWidgets().map { it.widgetKey }.toSet()

            context.dataStore.edit { prefs ->
                if (selected.settings) {
                    prefs.applyPreferenceJson(
                        manifest.optJSONObject("settings"),
                        availableAppKeys = availableAppKeys,
                        validWidgetKeys = validWidgetKeys,
                        excludeNames = settingsExcludedNames
                    )
                    restoreAppListBackgroundImage(context, tempRoot, prefs, manifest)
                }
                if (selected.appOrder) {
                    val appOrder = manifest.optJSONArray("appOrder")
                        .toStringList()
                        .filter(availableAppKeys::contains)
                    if (appOrder.isEmpty()) prefs.remove(LauncherViewModel.KEY_APP_ORDER) else prefs[LauncherViewModel.KEY_APP_ORDER] = appOrder.joinToString(",")
                }
                if (selected.widgetOrder) {
                    val widgets = manifest.optJSONArray("widgetOrder")
                        .toStringList()
                        .filter { raw -> raw.widgetKeyPart() in validWidgetKeys }
                    if (widgets.isEmpty()) prefs.remove(LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS) else prefs[LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS] = widgets.joinToString("|")
                }
                if (selected.sideScreenOrder) {
                    val shortcuts = manifest.optJSONArray("sideScreenShortcuts")
                        .toNullableStringList()
                        .map { value -> value?.takeIf(availableAppKeys::contains) }
                    val serializedShortcuts = serializeShortcuts(shortcuts)
                    if (serializedShortcuts.isEmpty()) prefs.remove(LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS) else prefs[LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS] = serializedShortcuts
                }
                if (selected.photoWatchFace) {
                    restoreMediaWatchFace(
                        context = context,
                        tempRoot = tempRoot,
                        prefs = prefs,
                        manifest = manifest.optJSONObject("photoWatchFace"),
                        mediaKey = LauncherViewModel.KEY_BUILTIN_PHOTO_PATH,
                        mediaFolder = "photo"
                    )
                }
                if (selected.videoWatchFace) {
                    restoreMediaWatchFace(
                        context = context,
                        tempRoot = tempRoot,
                        prefs = prefs,
                        manifest = manifest.optJSONObject("videoWatchFace"),
                        mediaKey = LauncherViewModel.KEY_BUILTIN_VIDEO_PATH,
                        mediaFolder = "video"
                    )
                }
            }

            // 历史非 .watch 格式条目不再恢复，直接随 tempRoot 一起丢弃
            // （静默跳过，不报错）。

            // 还原 .watch 表盘：提取内嵌 .watch 文件并调 importArchiveFile 安装。
            if (selected.watchFiles) {
                val archivesObj = manifest.optJSONObject("watchFiles")
                val archives = archivesObj?.optJSONArray("archives")
                if (archives != null) {
                    repeat(archives.length()) { i ->
                        val entry = archives.optString(i)
                        if (entry.isNotBlank()) {
                            val archiveFile = File(tempRoot, entry)
                            if (archiveFile.isFile) {
                                val sanitizedArchive = File(tempRoot, "sanitized-watch-$i.watch")
                                runCatching {
                                    sanitizeBackupWatchArchive(archiveFile, sanitizedArchive)
                                    WatchmakerWatchFaceStorage.importArchiveFile(context, sanitizedArchive)
                                }
                                // 单个表盘失败不中断整体恢复
                            }
                        }
                    }
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * 还原自定义应用列表背景图。
     *
     * 必须在 `applyPreferenceJson` **之后**跑：那一步刚把备份里的 `app_list_background`
     * JSON（含来源机的绝对路径）写进 prefs，这里再把图落到本机 filesDir 并把路径改成本机的。
     * 顺序反了就会留下一个指向来源机的路径。
     *
     * 备份包里没图（旧版备份、或当时就没选图）时**什么都不做** —— 让配置自己按
     * `AppListBackgroundConfig.effectiveMode` 落回纯色，而不是在这里再判一遍。
     */
    private fun restoreAppListBackgroundImage(
        context: Context,
        tempRoot: File,
        prefs: MutablePreferences,
        manifest: JSONObject,
    ) {
        val entry = manifest.optString("appListBackgroundEntry")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: return
        val source = File(tempRoot, entry).takeIf { it.isFile } ?: return
        val restoredPath = runCatching {
            copyInternalWatchFaceMedia(context, source, APP_LIST_BACKGROUND_MEDIA_FOLDER)
        }.getOrNull() ?: return
        val config = AppListBackgroundJson
            .decodeOrDefault(prefs[LauncherViewModel.KEY_APP_LIST_BACKGROUND])
            .copy(imagePath = restoredPath)
        prefs[LauncherViewModel.KEY_APP_LIST_BACKGROUND] =
            runCatching { AppListBackgroundJson.encode(config) }.getOrNull() ?: return
    }

    private fun restoreMediaWatchFace(
        context: Context,
        tempRoot: File,
        prefs: MutablePreferences,
        manifest: JSONObject?,
        mediaKey: Preferences.Key<String>,
        mediaFolder: String
    ) {
        if (manifest == null) return
        prefs.applyPreferenceJson(manifest.optJSONObject("preferences"))
        manifest.optString("mediaEntry")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { entry ->
                val source = File(tempRoot, entry)
                if (source.isFile) {
                    prefs[mediaKey] = copyInternalWatchFaceMedia(context, source, mediaFolder)
                    WatchFacePhotoCache.clear()
                }
            }
        manifest.optString("fontEntry")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { entry ->
                val source = File(tempRoot, entry)
                if (source.isFile) {
                    prefs[LauncherViewModel.KEY_WATCHFACE_FONT_PATH] =
                        copyInternalWatchFaceMedia(context, source, "font")
                }
            }
    }


    private fun copyInternalWatchFaceMedia(context: Context, source: File, folder: String): String {
        val targetRoot = File(context.filesDir, "internal_watchfaces/$folder").apply { mkdirs() }
        targetRoot.listFiles()?.forEach { existing -> if (existing.isFile) existing.delete() }
        val extension = source.extension.ifBlank {
            when (folder) {
                "photo", APP_LIST_BACKGROUND_MEDIA_FOLDER -> "png"
                "video" -> "mp4"
                else -> "ttf"
            }
        }.sanitizeFilePart()
        val target = File(targetRoot, "current_${System.currentTimeMillis()}.$extension")
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    private fun readManifest(context: Context, sourceUri: Uri): JSONObject {
        context.contentResolver.openInputStream(sourceUri)?.use { rawInput ->
            ZipInputStream(rawInput.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                        return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
        }
        error("不是有效的 Flue 备份")
    }

    private fun previewFromManifest(manifest: JSONObject): FlueBackupPreview {
        return FlueBackupPreview(
            appVersion = manifest.optString("appVersion", "unknown"),
            createdAt = manifest.optString("createdAt", ""),
            availableOptions = FlueBackupOptions(
                settings = manifest.has("settings"),
                appOrder = manifest.has("appOrder"),
                widgetOrder = manifest.has("widgetOrder"),
                sideScreenOrder = manifest.has("sideScreenShortcuts"),
                photoWatchFace = manifest.has("photoWatchFace"),
                videoWatchFace = manifest.has("videoWatchFace"),
                watchFiles = manifest.has("watchFiles")
            )
        )
    }

    private fun extractBackup(context: Context, sourceUri: Uri, targetRoot: File) {
        val canonicalRoot = targetRoot.canonicalFile
        context.contentResolver.openInputStream(sourceUri)?.use { rawInput ->
            ZipInputStream(rawInput.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val target = File(targetRoot, entry.name).canonicalFile
                    val insideRoot = target.path == canonicalRoot.path ||
                        target.path.startsWith(canonicalRoot.path + File.separator)
                    require(insideRoot) { "备份路径越界: ${entry.name}" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("无法读取备份文件")
    }

    private fun Preferences.toPreferenceJson(
        includeNames: Set<String>? = null,
        excludeNames: Set<String> = emptySet()
    ): JSONObject {
        val output = JSONObject()
        asMap().forEach { (key, value) ->
            val name = key.name
            if (includeNames != null && name !in includeNames) return@forEach
            if (name in excludeNames) return@forEach
            val entry = when (value) {
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is String -> JSONObject().put("type", "string").put("value", value)
                else -> null
            }
            if (entry != null) output.put(name, entry)
        }
        return output
    }

    private fun MutablePreferences.applyPreferenceJson(
        json: JSONObject?,
        availableAppKeys: Set<String> = emptySet(),
        validWidgetKeys: Set<String> = emptySet(),
        excludeNames: Set<String> = emptySet()
    ) {
        if (json == null) return
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name in excludeNames) continue
            val entry = json.optJSONObject(name) ?: continue
            val type = entry.optString("type")
            val value = entry.opt("value") ?: continue
            runCatching {
                when (type) {
                    "boolean" -> this[booleanPreferencesKey(name)] = entry.optBoolean("value")
                    "int" -> this[intPreferencesKey(name)] = entry.optInt("value")
                    "string" -> {
                        val sanitized = when (name) {
                            appOrderKey -> value.toString().split(',').filter(availableAppKeys::contains).joinToString(",")
                            hiddenAppsKey -> value.toString().split(',').filter(availableAppKeys::contains).joinToString(",")
                            sideScreenWidgetsKey -> value.toString().split('|', ',').filter { it.widgetKeyPart() in validWidgetKeys }.joinToString("|")
                            sideScreenShortcutsKey -> serializeShortcuts(
                                value.toString().split('|').map { token ->
                                    token.takeIf { it.isNotBlank() }?.takeIf(availableAppKeys::contains)
                                }
                            )
                            else -> value.toString()
                        }
                        if (sanitized.isBlank() && name in setOf(appOrderKey, hiddenAppsKey, sideScreenWidgetsKey, sideScreenShortcutsKey)) {
                            remove(stringPreferencesKey(name))
                        } else {
                            this[stringPreferencesKey(name)] = sanitized
                        }
                    }
                }
            }
        }
    }

    private fun FlueBackupOptions.toJson(): JSONObject {
        return JSONObject()
            .put("settings", settings)
            .put("appOrder", appOrder)
            .put("widgetOrder", widgetOrder)
            .put("sideScreenOrder", sideScreenOrder)
            .put("photoWatchFace", photoWatchFace)
            .put("videoWatchFace", videoWatchFace)
    }

    private fun String.splitCsvJson(): JSONArray {
        val array = JSONArray()
        split(',').map(String::trim).filter(String::isNotBlank).forEach(array::put)
        return array
    }

    private fun String.splitWidgetJson(): JSONArray {
        val array = JSONArray()
        split(if (contains('|')) '|' else ',').map(String::trim).filter(String::isNotBlank).forEach(array::put)
        return array
    }

    private fun String.splitShortcutJson(): JSONArray {
        val array = JSONArray()
        val parts = split('|')
        repeat(12) { index ->
            val value = parts.getOrNull(index)?.takeIf { it.isNotBlank() }
            array.put(value ?: JSONObject.NULL)
        }
        return array
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
            }
        }
    }

    private fun JSONArray?.toNullableStringList(): List<String?> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            if (isNull(index)) null else optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun serializeShortcuts(shortcuts: List<String?>): String {
        val normalized = List(12) { index -> shortcuts.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "" }
        return if (normalized.all(String::isEmpty)) "" else normalized.joinToString("|")
    }

    private fun String.widgetKeyPart(): String {
        val separator = indexOf(':')
        return if (separator > 0) substring(separator + 1) else this
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(this) }
        closeEntry()
    }

    private fun ZipOutputStream.writeDirectory(dir: File, baseEntry: String) {
        dir.walkTopDown().forEach { file ->
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            val entryName = if (relative.isBlank()) baseEntry else baseEntry + relative
            if (file.isDirectory) {
                putNextEntry(ZipEntry(entryName.trimEnd('/') + "/"))
                closeEntry()
            } else {
                writeFileEntry(entryName, file)
            }
        }
    }

    private fun appVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun String.sanitizeFilePart(): String {
        return replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    }
}

internal fun writeBackupWatchArchive(faceDirectory: File, output: OutputStream) {
    ZipOutputStream(output).use { inner ->
        faceDirectory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(faceDirectory).invariantSeparatorsPath
                if (isFlueInstallMetadataPath(relative)) return@forEach
                inner.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(inner) }
                inner.closeEntry()
            }
    }
}

internal fun sanitizeBackupWatchArchive(source: File, target: File) {
    ZipInputStream(source.inputStream().buffered()).use { input ->
        ZipOutputStream(target.outputStream().buffered()).use { output ->
            generateSequence { input.nextEntry }.forEach { entry ->
                if (!isFlueInstallMetadataPath(entry.name)) {
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) input.copyTo(output)
                    output.closeEntry()
                }
                input.closeEntry()
            }
        }
    }
}

private fun isFlueInstallMetadataPath(path: String): Boolean =
    path.replace('\\', '/').trimStart('/').substringBefore('/').equals(".flue", ignoreCase = true)
