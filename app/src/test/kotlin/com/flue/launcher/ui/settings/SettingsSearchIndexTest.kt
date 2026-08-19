package com.flue.launcher.ui.settings

import com.flue.launcher.SettingsDestination
import com.flue.launcher.buildSettingsSearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 设置搜索索引。
 *
 * 这份测试是「索引静默失效」的**唯一**自动化防线：`SettingsActivity` 解析
 * `destinationName` 时外面裹着 `runCatching`，写错的枚举名与不存在的 itemKey 都不会报错，
 * 只会让搜索结果落在页顶。历史上已经出现过一批这样的死锚点。
 *
 * 走 Robolectric 是因为索引要 `Context.getString`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class SettingsSearchIndexTest {

    private val index by lazy {
        buildSettingsSearchIndex(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `every entry points at a real destination with a non blank title and no duplicates`() {
        val problems = validateSettingsSearchIndex(
            entries = index,
            validDestinationNames = SettingsDestination.entries.mapTo(mutableSetOf()) { it.name },
        )
        assertEquals(
            problems.joinToString("\n") { "${it.entryTitle}: ${it.reason}" },
            emptyList<SettingsSearchIndexProblem>(),
            problems,
        )
    }

    @Test
    fun `descriptions and keywords are present so pinyin search works`() {
        // 每条至少要有一样可搜的东西（描述或关键词）。只有标题的条目意味着
        // 用拼音首字母或英文别名搜不到它。
        val bare = index.filter { it.description.isBlank() && it.keywords.isEmpty() }
        assertTrue(
            "entries with neither description nor keywords: ${bare.map { it.title }}",
            bare.isEmpty(),
        )
    }

    @Test
    fun `the pages users are most likely to search are all covered`() {
        // 抽查一批必须能搜到的锚点。这不是全量清单 —— 全量对齐靠上面那条
        // destination 校验加人工逐页核对，这里只钉住「历史上漏过 / 这一轮刚补的」。
        val targets = index.mapTo(mutableSetOf()) { it.destinationName to it.itemKey }
        listOf(
            "LANGUAGE" to null,
            "BACKUP_EXPORT" to null,
            "BACKUP_IMPORT" to null,
            "APP_LIST_BACKGROUND" to null,
            "APP_LIST_BACKGROUND" to "app_list_background_blur",
            "APP_LIST_BACKGROUND" to "app_list_background_scrim",
            "APPEARANCE" to "layout_flow",
            "APPEARANCE" to "layout_grid",
            "APPEARANCE" to "ui_style_miuix",
            "DISPLAY_SIZE" to "app_list_flow_crescent_bulge",
            "DISPLAY_SIZE" to "app_list_grid_icon_plate",
            "DISPLAY_SIZE" to "layout_profile_header",
            "ICON_PACKS" to "icon_pack_default",
            "MOTION_PROFILE" to "motion_export",
            "WATCH_FACE_MORE" to "app_list_background_entry",
            "WATCH_FACE_MORE" to "watchface_canvas_entry",
            "WATCH_FACE_MORE" to "tap_actions",
        ).forEach { target ->
            assertTrue("missing search entry for $target", target in targets)
        }
    }

    @Test
    fun `no entry still points at the retired force fill row`() {
        // 「强制铺满」搬进 CanvasAdjustActivity 之后，设置页里那个 item key 就没了。
        // 旧索引指着它，点进去只能落在页顶。
        assertTrue(index.none { it.itemKey == "watchmaker_force_fill" })
    }

    @Test
    fun `searching a title beats searching a keyword`() {
        val entries = listOf(
            SettingsSearchEntry("TOOLS", "a", "keyword only", keywords = listOf("mohu")),
            SettingsSearchEntry("TOOLS", "b", "模糊程度"),
        )
        val hits = searchSettingsEntries("模糊", entries)
        assertEquals(1, hits.size)
        assertEquals("b", hits.first().itemKey)
    }

    @Test
    fun `validator catches a bad destination a blank title and a duplicate`() {
        val problems = validateSettingsSearchIndex(
            entries = listOf(
                SettingsSearchEntry("NOT_A_PAGE", null, "bad destination"),
                SettingsSearchEntry("TOOLS", "x", "   "),
                SettingsSearchEntry("TOOLS", "y", "first"),
                SettingsSearchEntry("TOOLS", "y", "first"),
                SettingsSearchEntry("TOOLS", "ghost", "missing key"),
            ),
            validDestinationNames = setOf("TOOLS"),
            knownItemKeys = mapOf("TOOLS" to setOf("x", "y")),
        )
        assertEquals(4, problems.size)
    }

    @Test
    fun `validator allows differently named aliases for the same row`() {
        // 设置项被搬到子页面之后，原来的名字只能靠指向入口行的别名继续被搜到。
        val problems = validateSettingsSearchIndex(
            entries = listOf(
                SettingsSearchEntry("TOOLS", "y", "画布调节"),
                SettingsSearchEntry("TOOLS", "y", "强制铺满"),
            ),
            validDestinationNames = setOf("TOOLS"),
            knownItemKeys = mapOf("TOOLS" to setOf("y")),
        )
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `validator skips itemKey checks for pages with no declared keys`() {
        // 动态生成行的页面（图标包 / 语言 / 动效档案）不该被强迫维护第二份清单。
        val problems = validateSettingsSearchIndex(
            entries = listOf(SettingsSearchEntry("ICON_PACKS", "iconpack_com.foo", "Foo")),
            validDestinationNames = setOf("ICON_PACKS"),
            knownItemKeys = emptyMap(),
        )
        assertTrue(problems.isEmpty())
    }
}
