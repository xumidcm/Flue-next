package com.flue.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoundScreenActivityContractTest {
    @Test
    fun watchStoreThemeReceivesTheConfiguredScreenShapeOverride() {
        val source = sourceFile("WatchStoreActivity.kt").readText()

        assertTrue(
            "表盘市场必须订阅 screenShapeOverride，否则手动圆屏模式不会传入独立 Activity",
            source.contains(
                "val screenShapeOverride by launcherViewModel.screenShapeOverride.collectAsStateWithLifecycle()",
            ),
        )
        assertTrue(
            "表盘市场主题必须使用 screenShapeOverride，否则圆屏安全区逻辑不会生效",
            Regex(
                """WatchLauncherTheme\([\s\S]*?screenShape\s*=\s*screenShapeOverride,[\s\S]*?\)\s*\{""",
            ).containsMatchIn(source),
        )
    }

    @Test
    fun fullscreenWatchEditorPreviewDoesNotForceACircularMask() {
        val source = sourceFile("WatchEditorActivity.kt").readText()

        assertFalse(
            "全屏表盘画布必须保持完整矩形内容，不能根据圆屏模式强制套 CircleShape",
            source.contains("if (isRound && previewFraction > 0.92f) CircleShape"),
        )
    }

    @Test
    fun watchStoreControlsAreScrollableInBothListAndGridModes() {
        val source = sourceFile("WatchStoreActivity.kt").readText()

        assertTrue(
            "圆屏市场标题、搜索和筛选必须作为列表首项滚入安全弦宽，不能固定在屏幕顶部",
            source.contains(
                "item(STORE_BROWSE_CONTROLS_KEY) {\n" +
                    "                            WatchStoreBrowseControls(",
            ),
        )
        assertTrue(
            "网格模式也必须把市场控件作为全宽首项，否则切换网格后顶部控件仍不可触达",
            Regex(
                """item\(\s*key\s*=\s*STORE_BROWSE_CONTROLS_KEY,\s*span\s*=\s*\{\s*GridItemSpan\(maxLineSpan\)\s*},\s*\)\s*\{\s*WatchStoreBrowseControls\(""",
            ).containsMatchIn(source),
        )
    }

    @Test
    fun watchFaceSettingsCentersTheTallFirstCardInsteadOfAGenericRow() {
        val source = sourceFile("SettingsActivity.kt").readText()

        assertTrue(
            "表盘页首项约 80dp 高，圆屏初始定位必须使用 40dp 半高，标题才会位于圆心上方",
            Regex(
                """SettingsDestination\.WATCH_FACES\s*->\s*SettingsPageScaffold\([\s\S]*?roundFirstActionHalfHeight\s*=\s*40\.dp,""",
            ).containsMatchIn(source),
        )
    }

    private fun sourceFile(name: String): File {
        val mainSource = sequenceOf(
            File("src/main/kotlin/com/example/wlauncher"),
            File("app/src/main/kotlin/com/example/wlauncher"),
            File("../app/src/main/kotlin/com/example/wlauncher"),
        ).firstOrNull(File::isDirectory)
            ?: error("找不到主源码目录，工作目录是 ${File(".").absolutePath}")
        return File(mainSource, name).also {
            check(it.isFile) { "找不到源码文件：${it.absolutePath}" }
        }
    }
}
