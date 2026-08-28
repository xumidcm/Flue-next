package com.flue.launcher.layout

import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.util.FlowCrescentDirection
import com.flue.launcher.util.FlowShapeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 布局档案的区间校验、JSON 往返与内置档案齐备性。
 *
 * 这一套的存在理由是老师报的「四种布局共用一份鱼眼参数」：改成分布局之后，
 * 「每种布局都有一份可回落的默认档」变成了硬约束 —— 少一份，切到那种布局
 * 就没有参数可用。
 *
 * 走 Robolectric 是因为编解码用的是 `org.json`：单测里 android.jar 的 stub 会
 * 让 `JSONObject.put` 返回 null（`unitTests.isReturnDefaultValues = true`），
 * 链式 put 直接 NPE。项目里其他碰 JSON 的测试（`WatchFaceCatalogTest`）同法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class FlueLayoutProfileTest {
    @Test
    fun `every layout mode has a built-in profile bound to itself`() {
        assertEquals(LayoutMode.entries.size, FlueLayoutProfiles.builtIns.size)
        LayoutMode.entries.forEach { mode ->
            val preset = FlueLayoutProfiles.builtInFor(mode)
            assertEquals(mode, preset.layoutMode)
            assertTrue(FlueLayoutProfiles.isBuiltIn(preset.id))
            assertTrue(FlueLayoutProfiles.builtIns.contains(preset))
        }
        // id 不能撞，否则整库编码会因为重复 id 直接抛。
        assertEquals(
            FlueLayoutProfiles.builtIns.size,
            FlueLayoutProfiles.builtIns.map { it.id }.toSet().size,
        )
    }

    @Test
    fun `built-in defaults match the pre-profile flat preference defaults`() {
        // 没动过设置的用户升级上来观感必须一个像素不变。
        val honeycomb = FlueLayoutProfiles.Honeycomb
        assertTrue(honeycomb.fisheyeEnabled)
        assertFalse(honeycomb.topRowExtraFisheyeEnabled)
        assertTrue(honeycomb.edgeSpacingCompressionEnabled)
        assertEquals(4, honeycomb.fisheyeRangeRows)
        assertEquals(100, honeycomb.fisheyeStrengthPercent)
        assertEquals(0, honeycomb.leftSafeInsetPercent)
        assertEquals(3, honeycomb.narrowColumns)
        assertEquals(100, honeycomb.appListScalePercent)
        // 蜂窝 / 网格 / 列表三档除了 id、名字、布局之外逐位相同。
        listOf(FlueLayoutProfiles.Grid, FlueLayoutProfiles.List).forEach { preset ->
            assertEquals(
                honeycomb.copy(id = preset.id, name = preset.name, layoutMode = preset.layoutMode),
                preset,
            )
        }
    }

    @Test
    fun `flow ships with a crescent so it looks like the reference right away`() {
        // 100% = 「峰值正好贴边」那一档，就是参考截图的样子（上限现在是 150%）。
        assertEquals(100, FlueLayoutProfiles.Flow.crescentBulgePercent)
        assertEquals(FlowCrescentDirection.Right, FlueLayoutProfiles.Flow.crescentDirection)
        // 其他布局默认不鼓 —— 月牙是心流专属。
        listOf(
            FlueLayoutProfiles.Honeycomb,
            FlueLayoutProfiles.Grid,
            FlueLayoutProfiles.List,
        ).forEach { preset ->
            assertEquals(0, preset.crescentBulgePercent)
        }
    }

    @Test
    fun `requireValid rejects out of range values`() {
        val base = FlueLayoutProfiles.Honeycomb
        assertThrows { base.copy(fisheyeRangeRows = 0).requireValid() }
        assertThrows { base.copy(fisheyeRangeRows = 9).requireValid() }
        assertThrows { base.copy(fisheyeStrengthPercent = -1).requireValid() }
        assertThrows { base.copy(fisheyeStrengthPercent = 201).requireValid() }
        assertThrows { base.copy(leftSafeInsetPercent = -1).requireValid() }
        assertThrows { base.copy(leftSafeInsetPercent = 51).requireValid() }
        assertThrows { base.copy(crescentBulgePercent = -1).requireValid() }
        assertThrows { base.copy(crescentBulgePercent = 151).requireValid() }
        assertThrows { base.copy(id = " ").requireValid() }
        assertThrows { base.copy(name = "").requireValid() }
        assertThrows { base.copy(schemaVersion = 99).requireValid() }
    }

    @Test
    fun `sanitized clamps instead of throwing so a single bad field cannot void a profile`() {
        val messy = FlueLayoutProfiles.Flow.copy(
            fisheyeRangeRows = 99,
            fisheyeStrengthPercent = -40,
            leftSafeInsetPercent = 500,
            crescentBulgePercent = 900,
            name = "   ",
        ).sanitized()
        assertEquals(FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MAX, messy.fisheyeRangeRows)
        assertEquals(FLUE_LAYOUT_FISHEYE_STRENGTH_MIN, messy.fisheyeStrengthPercent)
        assertEquals(FLUE_LAYOUT_LEFT_SAFE_INSET_MAX, messy.leftSafeInsetPercent)
        assertEquals(FLUE_LAYOUT_CRESCENT_BULGE_MAX, messy.crescentBulgePercent)
        assertEquals("布局", messy.name)
        messy.requireValid()
    }

    @Test
    fun `custom copy gets a fresh id and keeps the layout binding`() {
        val copy = FlueLayoutProfiles.Flow.customCopy(customName = "我的心流")
        assertEquals("我的心流", copy.name)
        assertEquals(LayoutMode.Flow, copy.layoutMode)
        assertFalse(FlueLayoutProfiles.isBuiltIn(copy.id))
        assertTrue(copy.id.startsWith("custom:"))
        assertEquals(FlueLayoutProfiles.Flow.crescentBulgePercent, copy.crescentBulgePercent)
    }

    @Test
    fun `single profile survives a json round trip`() {
        val original = FlueLayoutProfiles.Flow.copy(
            id = "custom:round-trip",
            name = "往返",
            fisheyeRangeRows = 7,
            fisheyeStrengthPercent = 55,
            leftSafeInsetPercent = 15,
            topRowExtraFisheyeEnabled = true,
            edgeSpacingCompressionEnabled = false,
            fisheyeEnabled = false,
            crescentDirection = FlowCrescentDirection.Left,
            crescentBulgePercent = 33,
            narrowColumns = 5,
            appListScalePercent = 137,
            flowShapeCurve = FlowShapeCurve(0.2f, 0.1f, 0.8f, 0.9f),
        ).requireValid()
        val decoded = FlueLayoutProfileJson.decodeProfile(
            FlueLayoutProfileJson.encodeProfile(original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `layout profile fields stay isolated when switching modes`() {
        val grid = FlueLayoutProfiles.Grid.copy(narrowColumns = 5, appListScalePercent = 80)
        val list = FlueLayoutProfiles.List.copy(narrowColumns = 2, appListScalePercent = 145)

        assertEquals(5, grid.narrowColumns)
        assertEquals(80, grid.appListScalePercent)
        assertEquals(2, list.narrowColumns)
        assertEquals(145, list.appListScalePercent)
    }

    @Test
    fun `schema one profile without new fields migrates to defaults`() {
        val legacyJson = JSONObject(FlueLayoutProfileJson.encodeProfile(FlueLayoutProfiles.Grid))
        legacyJson.put("schemaVersion", 1)
        legacyJson.remove("narrowColumns")
        legacyJson.remove("appListScalePercent")
        legacyJson.remove("flowShapeX1")
        legacyJson.remove("flowShapeY1")
        legacyJson.remove("flowShapeX2")
        legacyJson.remove("flowShapeY2")
        val legacy = legacyJson.toString()

        val decoded = FlueLayoutProfileJson.decodeProfile(legacy)

        assertEquals(3, decoded.narrowColumns)
        assertEquals(100, decoded.appListScalePercent)
        assertEquals(FlowShapeCurve.Crescent, decoded.flowShapeCurve)
        assertEquals(FLUE_LAYOUT_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun `library survives a json round trip for every layout mode`() {
        val library = FlueLayoutProfiles.builtIns +
            FlueLayoutProfiles.Grid.customCopy(customName = "网格副本")
        val decoded = FlueLayoutProfileJson.decodeLibrary(
            FlueLayoutProfileJson.encodeLibrary(library),
        )
        assertEquals(library, decoded)
        assertEquals(
            LayoutMode.entries.toSet(),
            decoded.map { it.layoutMode }.toSet(),
        )
    }

    @Test
    fun `decoding rejects broken documents`() {
        assertThrows { FlueLayoutProfileJson.decodeProfile("") }
        assertThrows { FlueLayoutProfileJson.decodeProfile("not json") }
        assertThrows { FlueLayoutProfileJson.decodeLibrary("""{"schemaVersion":1}""") }
        assertThrows {
            FlueLayoutProfileJson.decodeLibrary("""{"schemaVersion":99,"profiles":[]}""")
        }
        // 未知布局名 / 未知朝向不能被静默当成默认值。
        val payload = FlueLayoutProfileJson.encodeProfile(FlueLayoutProfiles.Flow)
        assertThrows {
            FlueLayoutProfileJson.decodeProfile(payload.replace("\"flow\"", "\"spiral\""))
        }
        assertThrows {
            FlueLayoutProfileJson.decodeProfile(payload.replace("\"right\"", "\"sideways\""))
        }
    }

    @Test
    fun `resolve falls back to the built-in of the requested layout`() {
        val library = FlueLayoutProfiles.builtIns
        // 命中。
        assertEquals(
            FlueLayoutProfiles.Flow,
            FlueLayoutProfiles.resolve(FlueLayoutProfiles.FLOW_ID, LayoutMode.Flow, library),
        )
        // id 不存在 → 退回该布局默认档，而不是别的布局那一份。
        assertEquals(
            FlueLayoutProfiles.List,
            FlueLayoutProfiles.resolve("custom:missing", LayoutMode.List, library),
        )
        // id 存在但属于另一种布局 → 同样退回，绝不跨布局串台。
        assertEquals(
            FlueLayoutProfiles.Grid,
            FlueLayoutProfiles.resolve(FlueLayoutProfiles.FLOW_ID, LayoutMode.Grid, library),
        )
        // 空库也要给得出东西。
        assertEquals(
            FlueLayoutProfiles.Honeycomb,
            FlueLayoutProfiles.resolve(null, LayoutMode.Honeycomb, emptyList()),
        )
    }

    @Test
    fun `resolve prefers a surviving custom profile over a cross-layout preset`() {
        // 默认档被外部编辑删掉、只剩一份自定义档时，仍要给出同布局那一份。
        val custom = FlueLayoutProfiles.Flow.customCopy(customName = "仅剩这份")
        val library = listOf(FlueLayoutProfiles.Honeycomb, custom)
        assertEquals(
            custom,
            FlueLayoutProfiles.resolve("custom:gone", LayoutMode.Flow, library),
        )
    }

    @Test
    fun `withMissingBuiltIns tops up only what is missing`() {
        val partial = listOf(
            FlueLayoutProfiles.Honeycomb.copy(fisheyeStrengthPercent = 42),
            FlueLayoutProfiles.Grid,
        )
        val topped = FlueLayoutProfiles.withMissingBuiltIns(partial)
        assertEquals(LayoutMode.entries.size, topped.size)
        // 已有的那份不能被出厂值盖掉 —— 否则用户的调参在补齐时静默丢失。
        assertEquals(
            42,
            topped.first { it.id == FlueLayoutProfiles.HONEYCOMB_ID }.fisheyeStrengthPercent,
        )
        assertNotNull(topped.firstOrNull { it.id == FlueLayoutProfiles.FLOW_ID })
        assertNotNull(topped.firstOrNull { it.id == FlueLayoutProfiles.LIST_ID })
        // 已经齐备时原样返回，不做无谓的拷贝。
        val full = FlueLayoutProfiles.builtIns
        assertTrue(FlueLayoutProfiles.withMissingBuiltIns(full) === full)
    }

    @Test
    fun `only preset ids count as built-in`() {
        assertTrue(FlueLayoutProfiles.isBuiltIn(FlueLayoutProfiles.HONEYCOMB_ID))
        assertTrue(FlueLayoutProfiles.isBuiltIn(FlueLayoutProfiles.FLOW_ID))
        assertFalse(FlueLayoutProfiles.isBuiltIn("custom:whatever"))
        assertFalse(FlueLayoutProfiles.isBuiltIn(""))
    }

    private fun assertThrows(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertNotNull("expected a failure but the call succeeded", thrown)
    }
}
