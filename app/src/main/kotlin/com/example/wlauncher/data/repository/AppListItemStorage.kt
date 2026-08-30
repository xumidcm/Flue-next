package com.flue.launcher.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flue.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class StoredAppListShortcut(
    val id: String,
    val label: String,
    val packageName: String,
    val intentUri: String,
    val iconPath: String? = null,
    val pinnedShortcutId: String? = null
) {
    val componentKey: String = "shortcut:$id"
}

data class StoredAppListFolder(
    val id: String,
    val name: String,
    val itemKeys: List<String>
) {
    val componentKey: String = "folder:$id"
}

data class FolderMutationResult(
    val succeeded: Boolean,
    val itemKeys: List<String> = emptyList(),
    val dissolvedFolders: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        val Failure = FolderMutationResult(succeeded = false)
    }
}

class AppListItemStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_list_items", Context.MODE_PRIVATE)
    private val iconDir = File(context.filesDir, "app_list_shortcut_icons").apply { mkdirs() }

    /**
     * 新建文件夹的默认名。
     *
     * 以前这里散着四处写死的 `"文件夹"` 字面量，英日两档的用户建出来的文件夹也叫中文名。
     * 现在统一走资源，三语言各自成立。
     */
    private val defaultFolderName: String
        get() = context.getString(R.string.app_list_folder_default_name)

    /**
     * 取一个没被占用的默认名：`文件夹`、`文件夹1`、`文件夹2`…
     *
     * 必须在持有 [folderMutationLock] 的临界区里调用 —— 判重读的是同一份 `folders`
     * 快照，放到锁外面的话两次快速合成会同时看到「没有文件夹1」而建出两个同名的。
     */
    private fun uniqueDefaultFolderName(existing: List<StoredAppListFolder>): String {
        val base = defaultFolderName
        val taken = existing.mapTo(HashSet()) { it.name }
        if (base !in taken) return base
        var suffix = 1
        while ("$base$suffix" in taken) suffix += 1
        return "$base$suffix"
    }

    @Synchronized
    fun loadShortcuts(): List<StoredAppListShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val label = item.optString("label").takeIf(String::isNotBlank) ?: continue
                    val packageName = item.optString("packageName")
                    val intentUri = item.optString("intentUri").takeIf(String::isNotBlank) ?: continue
                    add(
                        StoredAppListShortcut(
                            id = id,
                            label = label,
                            packageName = packageName,
                            intentUri = intentUri,
                            iconPath = item.optString("iconPath").takeIf(String::isNotBlank),
                            pinnedShortcutId = item.optString("pinnedShortcutId").takeIf(String::isNotBlank)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun loadFolders(): List<StoredAppListFolder> {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val keys = item.optJSONArray("itemKeys") ?: JSONArray()
                    val itemKeys = buildList {
                        for (keyIndex in 0 until keys.length()) {
                            keys.optString(keyIndex).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }.distinct()
                    if (itemKeys.isEmpty()) continue
                    add(
                        StoredAppListFolder(
                            id = id,
                            name = item.optString("name").takeIf(String::isNotBlank) ?: defaultFolderName,
                            itemKeys = itemKeys
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addShortcut(
        label: String,
        packageName: String,
        intentUri: String,
        icon: Bitmap?,
        pinnedShortcutId: String? = null
    ): StoredAppListShortcut {
        val shortcuts = loadShortcuts().toMutableList()
        val normalizedPinnedShortcutId = pinnedShortcutId?.takeIf { it.isNotBlank() }
        val duplicate = shortcuts.firstOrNull { shortcut ->
            if (normalizedPinnedShortcutId != null) {
                shortcut.packageName == packageName && shortcut.pinnedShortcutId == normalizedPinnedShortcutId
            } else {
                shortcut.pinnedShortcutId == null && shortcut.intentUri == intentUri
            }
        }
        if (duplicate != null) return duplicate
        val id = UUID.randomUUID().toString()
        val iconPath = icon?.let { bitmap ->
            val output = File(iconDir, "$id.png")
            runCatching {
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                output.absolutePath
            }.getOrNull()
        }
        val shortcut = StoredAppListShortcut(
            id = id,
            label = label.ifBlank { "快捷方式" },
            packageName = packageName,
            intentUri = intentUri,
            iconPath = iconPath,
            pinnedShortcutId = normalizedPinnedShortcutId
        )
        shortcuts += shortcut
        saveShortcuts(shortcuts)
        return shortcut
    }

    fun removeShortcut(componentKey: String): Boolean {
        return synchronized(folderMutationLock) {
            val id = componentKey.removePrefix("shortcut:")
            val shortcuts = loadShortcuts().toMutableList()
            val removed = shortcuts.removeAll { it.id == id }
            if (removed) {
                saveShortcuts(shortcuts)
                File(iconDir, "$id.png").delete()
                val folders = loadFolders().mapNotNull { folder ->
                    val nextKeys = folder.itemKeys.filterNot { it == componentKey }
                    when {
                        nextKeys.isEmpty() -> null
                        nextKeys == folder.itemKeys -> folder
                        else -> folder.copy(itemKeys = nextKeys)
                    }
                }
                saveFolders(folders)
            }
            removed
        }
    }

    fun createFolder(
        sourceKey: String,
        targetKey: String,
        /**
         * 显式名字。留空表示「用默认名」—— 新建时会取一个没被占用的
         * `文件夹` / `文件夹1` / `文件夹2`…（见 [uniqueDefaultFolderName]）。
         *
         * 以前调用方传的是**被拖到的那个应用的名字**，于是把 A 拖到「音乐」上建出来的
         * 文件夹就叫「音乐」，看着像是把应用改了名。老师要的是固定叫「文件夹」。
         */
        targetName: String = "",
    ): StoredAppListFolder? = synchronized(folderMutationLock) {
        if (sourceKey == targetKey || sourceKey.isBlank() || targetKey.isBlank()) {
            return@synchronized null
        }
        val folders = loadFolders().toMutableList()
        val sourceFolderIndex = folders.indexOfFirst { it.componentKey == sourceKey }
        val targetFolderIndex = folders.indexOfFirst { it.componentKey == targetKey }
        val targetFolder = folders.getOrNull(targetFolderIndex)
        if (targetFolder != null) {
            val sourceKeys = if (sourceFolderIndex >= 0) folders[sourceFolderIndex].itemKeys else listOf(sourceKey)
            folders[targetFolderIndex] = targetFolder.copy(
                itemKeys = (targetFolder.itemKeys + sourceKeys).distinct()
            )
            if (sourceFolderIndex >= 0 && sourceFolderIndex != targetFolderIndex) {
                folders.removeAt(sourceFolderIndex)
            }
            saveFolders(folders)
            return@synchronized folders.firstOrNull { it.componentKey == targetFolder.componentKey }
        }

        if (sourceFolderIndex >= 0) {
            val folder = folders[sourceFolderIndex]
            folders[sourceFolderIndex] = folder.copy(
                itemKeys = (folder.itemKeys + targetKey).distinct()
            )
            saveFolders(folders)
            return@synchronized folders[sourceFolderIndex]
        }

        val folder = StoredAppListFolder(
            id = UUID.randomUUID().toString(),
            name = targetName.ifBlank { uniqueDefaultFolderName(folders) },
            itemKeys = listOf(targetKey, sourceKey).distinct()
        )
        folders += folder
        saveFolders(folders)
        folder
    }

    fun renameFolder(folderKey: String, name: String): Boolean = synchronized(folderMutationLock) {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return@synchronized false
        folders[index] = folders[index].copy(name = name.ifBlank { defaultFolderName })
        saveFolders(folders)
        true
    }

    fun dissolveFolder(folderKey: String): List<String> = synchronized(folderMutationLock) {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return@synchronized emptyList()
        val keys = folders.removeAt(index).itemKeys
        saveFolders(folders)
        keys
    }

    fun moveItemOutOfFolder(
        folderKey: String,
        itemKey: String,
    ): FolderMutationResult = synchronized(folderMutationLock) {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return@synchronized FolderMutationResult.Failure
        val folder = folders[index]
        if (itemKey !in folder.itemKeys) return@synchronized FolderMutationResult.Failure
        val nextKeys = folder.itemKeys.filterNot { it == itemKey }
        if (nextKeys.size <= 1) {
            folders.removeAt(index)
            saveFolders(folders)
            return@synchronized FolderMutationResult(
                succeeded = true,
                itemKeys = nextKeys,
            )
        }
        folders[index] = folder.copy(itemKeys = nextKeys)
        saveFolders(folders)
        FolderMutationResult(succeeded = true, itemKeys = nextKeys)
    }

    fun addItemsToFolder(
        folderKey: String,
        itemKeys: List<String>,
    ): FolderMutationResult = synchronized(folderMutationLock) {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return@synchronized FolderMutationResult.Failure
        val folder = folders[index]
        val additions = itemKeys
            .filter { key -> key != folderKey && key !in folder.itemKeys }
            .distinct()
        if (additions.isEmpty()) {
            return@synchronized FolderMutationResult(
                succeeded = true,
                itemKeys = folder.itemKeys,
            )
        }
        val nextKeys = folder.itemKeys + additions
        val additionSet = additions.toSet()
        val dissolved = linkedMapOf<String, List<String>>()
        val nextFolders = folders.mapIndexedNotNull { folderIndex, candidate ->
            if (folderIndex == index) {
                candidate.copy(itemKeys = nextKeys)
            } else {
                val keptKeys = candidate.itemKeys.filterNot(additionSet::contains)
                when {
                    keptKeys == candidate.itemKeys -> candidate
                    keptKeys.size <= 1 -> {
                        dissolved[candidate.componentKey] = keptKeys
                        null
                    }
                    else -> candidate.copy(itemKeys = keptKeys)
                }
            }
        }
        saveFolders(nextFolders)
        FolderMutationResult(
            succeeded = true,
            itemKeys = nextKeys,
            dissolvedFolders = dissolved,
        )
    }

    fun setFolderItems(
        folderKey: String,
        itemKeys: List<String>,
    ): FolderMutationResult = synchronized(folderMutationLock) {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return@synchronized FolderMutationResult.Failure
        val nextKeys = itemKeys
            .filter { key -> key != folderKey }
            .distinct()
        val selectedKeySet = nextKeys.toSet()
        val dissolved = linkedMapOf<String, List<String>>()
        val nextFolders = folders.mapIndexedNotNull { folderIndex, candidate ->
            if (folderIndex == index) {
                if (nextKeys.size <= 1) null else candidate.copy(itemKeys = nextKeys)
            } else {
                val keptKeys = candidate.itemKeys.filterNot { it in selectedKeySet }
                when {
                    keptKeys == candidate.itemKeys -> candidate
                    keptKeys.size <= 1 -> {
                        dissolved[candidate.componentKey] = keptKeys
                        null
                    }
                    else -> candidate.copy(itemKeys = keptKeys)
                }
            }
        }
        saveFolders(nextFolders)
        FolderMutationResult(
            succeeded = true,
            itemKeys = nextKeys,
            dissolvedFolders = dissolved,
        )
    }

    fun reorderFolderItems(
        folderKey: String,
        orderedKeys: List<String>,
    ): FolderMutationResult = synchronized(folderMutationLock) {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return@synchronized FolderMutationResult.Failure
        val folder = folders[index]
        val existing = folder.itemKeys
        val nextKeys = (
            orderedKeys.filter { it in existing } +
                existing.filterNot { it in orderedKeys }
            ).distinct()
        if (nextKeys == existing) {
            return@synchronized FolderMutationResult(
                succeeded = true,
                itemKeys = existing,
            )
        }
        folders[index] = folder.copy(itemKeys = nextKeys)
        saveFolders(folders)
        FolderMutationResult(succeeded = true, itemKeys = nextKeys)
    }

    fun loadShortcutIcon(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        // 图标文件的原始尺寸不受控，两趟解码按最长边 [MAX_SHORTCUT_ICON_DECODE_SIDE] 采样。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        var width = bounds.outWidth
        var height = bounds.outHeight
        while (width > 0 && height > 0 && (width / 2 >= MAX_SHORTCUT_ICON_DECODE_SIDE || height / 2 >= MAX_SHORTCUT_ICON_DECODE_SIDE)) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return runCatching {
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }.getOrNull()
    }

    private fun saveShortcuts(shortcuts: List<StoredAppListShortcut>) {
        val array = JSONArray()
        shortcuts.forEach { shortcut ->
            array.put(JSONObject().apply {
                put("id", shortcut.id)
                put("label", shortcut.label)
                put("packageName", shortcut.packageName)
                put("intentUri", shortcut.intentUri)
                shortcut.iconPath?.let { put("iconPath", it) }
                shortcut.pinnedShortcutId?.let { put("pinnedShortcutId", it) }
            })
        }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    private fun saveFolders(folders: List<StoredAppListFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("itemKeys", JSONArray(folder.itemKeys))
            })
        }
        // 这里曾经用 commit()：主线程同步阻塞落盘。文件夹的创建 / 拖入 /
        // 拖出全部走这个函数，而它们都发生在手指抬起的那一刻，于是每次建
        // 文件夹放手后都要卡一下磁盘 IO——就是「放手后卡住一段时间才成功
        // 创建」。
        //
        // 改 apply()：内存值同步更新（同进程内紧接着的 loadFolders 立刻能
        // 读到新图，原注释担心的「第二个 repository 实例读到过期图」不会
        // 发生），真正的写盘由框架挪到后台线程。唯一让掉的是「commit 返回
        // 前就落盘」这一点持久性，只在硬杀进程的那一瞬有差别。
        prefs.edit().putString(KEY_FOLDERS, array.toString()).apply()
    }

    private companion object {
        private const val KEY_SHORTCUTS = "shortcuts"
        private const val KEY_FOLDERS = "folders"
        private val folderMutationLock = Any()

        /** 快捷方式图标的解码最长边；显示目标只有几十像素。 */
        private const val MAX_SHORTCUT_ICON_DECODE_SIDE = 256
    }
}
