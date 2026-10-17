package com.flue.launcher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「应用列表贴边对齐」开关的源码契约。
 *
 * 照 [WatchFaceStatusPreferenceContractTest] 的写法：这个项目没有 DataStore 集成测试，
 * 持久化的正确性靠「读源码断言」守着。漏掉任何一环都会静默出错 ——
 * 比如只写内存不落盘，或者只写 setter 忘了复位那两处。
 */
class AppListEdgeAlignPreferenceContractTest {
    @Test
    fun `view model persists restores and resets the edge align switch`() {
        val source = source("com/example/wlauncher/viewmodel/LauncherViewModel.kt")

        assertTrue(source.contains("booleanPreferencesKey(\"app_list_edge_align_enabled\")"))
        assertTrue(source.contains("private val _appListEdgeAlignEnabled = MutableStateFlow(false)"))
        assertTrue(source.contains("prefs[KEY_APP_LIST_EDGE_ALIGN] ?: false"))
        assertTrue(source.contains("it[KEY_APP_LIST_EDGE_ALIGN] = enabled"))
        // 复位两处：只改内存会让「重置后 UI 还显示旧值，下次落盘又写回去」。
        assertTrue(source.contains("_appListEdgeAlignEnabled.value = false"))
        assertTrue(source.contains("it[KEY_APP_LIST_EDGE_ALIGN] = false"))
    }

    @Test
    fun `settings exposes the edge align switch with a search entry`() {
        val settings = source("com/example/wlauncher/SettingsActivity.kt")

        assertTrue(settings.contains("settings_app_list_edge_align"))
        assertTrue(settings.contains("appListEdgeAlignEnabled"))
        assertTrue(settings.contains("setAppListEdgeAlignEnabled"))
        // 搜索索引的 itemKey 必须与设置页里 row("…") 的 key 逐字一致，否则跳转静默失效。
        assertTrue(settings.contains("SettingsSearchEntry(\"DISPLAY_SIZE\", \"app_list_edge_align\""))
        assertTrue(settings.contains("row(\"app_list_edge_align\")"))
    }

    /** 开关必须一路从 Compose 传到原生配置，中间断一环就是「设置里能点、列表没反应」。 */
    @Test
    fun `the switch reaches the native app list config`() {
        val activity = source("com/example/wlauncher/LauncherActivity.kt")
        assertTrue(activity.contains("edgeAlignEnabled = appListEdgeAlignEnabled"))

        val drawer = source("com/flue/launcher/ui/drawer/NativeListDrawerScreen.kt")
        assertTrue(drawer.contains("edgeAlignEnabled: Boolean = false"))
        assertTrue(drawer.contains("edgeAlignEnabled = request.edgeAlignEnabled"))
        assertTrue(drawer.contains("edgeAlignPaddingPx = edgeAlignPaddingPx"))

        val config = source("com/flue/launcher/applist/nativeview/NativeAppListConfig.kt")
        assertTrue(config.contains("val edgeAlignEnabled: Boolean = false"))
        assertTrue(config.contains("val edgeAlignPaddingPx: Float = 0f"))
        assertTrue(config.contains("edgeAlignPaddingPx = edgeAlignPaddingPx.nonNegativeFiniteMetric()"))
    }

    /**
     * 四个空间几何都必须走同一个边界函数。
     *
     * 各自的 `-lastY to -firstY` 曾经是散落四份的同构代码，正是「首行居中」那条
     * 约定的四个副本；新开关要想五种布局一致，就必须收敛到一个算式。
     */
    @Test
    fun `every spatial geometry routes its scroll bounds through the shared policy`() {
        listOf(
            "NativeDenseGridGeometry.kt",
            "NativeHoneycombGeometry.kt",
            "NativeRingGeometry.kt",
            "NativeFlowGeometry.kt",
        ).forEach { file ->
            val geometry = source("com/flue/launcher/applist/nativeview/$file")
            assertTrue(
                "$file 必须参与贴边对齐（要么用共享边界函数，要么用 edgePaddingPx 定位）",
                geometry.contains("nativeAppListScrollBounds") ||
                    geometry.contains("edgePaddingPx"),
            )
            assertTrue("$file 必须接受 centerAligned 开关", geometry.contains("centerAligned"))
        }
    }

    private fun source(relative: String): String {
        val root = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        return File(root, relative).readText()
    }
}
