package com.flue.launcher.layout

import com.flue.launcher.ui.navigation.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扁平鱼眼偏好 → 布局档案的迁移。
 *
 * 这是升级路径上最容易静默丢配置的一步：改动前六个键是全局的，改动后按布局分开存。
 * 迁移必须把老师现有的那一份原样写进**每一种**布局，而不是让谁退回出厂值。
 *
 * Robolectric 只为最后那条「迁移结果能直接当档案库落盘」用（`org.json` 的 stub
 * 在纯 JVM 单测里会返回 null），其余用例都是纯函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class FlueLayoutProfileMigrationTest {
    @Test
    fun `no stored flat preferences keeps every factory default`() {
        assertEquals(FlueLayoutProfiles.builtIns, migratedLayoutProfiles())
    }

    @Test
    fun `stored flat preferences land on every layout`() {
        val migrated = migratedLayoutProfiles(
            fisheyeEnabled = false,
            topRowExtraFisheyeEnabled = true,
            edgeSpacingCompressionEnabled = false,
            fisheyeRangeRows = 7,
            fisheyeStrengthPercent = 35,
            leftSafeInsetPercent = 20,
            narrowColumns = 4,
            appListScalePercent = 125,
        )
        assertEquals(LayoutMode.entries.size, migrated.size)
        migrated.forEach { profile ->
            assertFalse(profile.fisheyeEnabled)
            assertTrue(profile.topRowExtraFisheyeEnabled)
            assertFalse(profile.edgeSpacingCompressionEnabled)
            assertEquals(7, profile.fisheyeRangeRows)
            assertEquals(35, profile.fisheyeStrengthPercent)
            assertEquals(20, profile.leftSafeInsetPercent)
            assertEquals(4, profile.narrowColumns)
            assertEquals(125, profile.appListScalePercent)
        }
        // 布局绑定与 id 不能被迁移改掉，否则 resolve 找不到档案。
        assertEquals(
            FlueLayoutProfiles.builtIns.map { it.id to it.layoutMode },
            migrated.map { it.id to it.layoutMode },
        )
    }

    @Test
    fun `null means never written and must not be read as false or zero`() {
        // 只写过「关掉鱼眼」这一个键：其余五项必须保持出厂值。
        val migrated = migratedLayoutProfiles(fisheyeEnabled = false)
        migrated.forEach { profile ->
            val preset = FlueLayoutProfiles.builtInFor(profile.layoutMode)
            assertFalse(profile.fisheyeEnabled)
            assertEquals(preset.topRowExtraFisheyeEnabled, profile.topRowExtraFisheyeEnabled)
            assertEquals(
                preset.edgeSpacingCompressionEnabled,
                profile.edgeSpacingCompressionEnabled,
            )
            assertEquals(preset.fisheyeRangeRows, profile.fisheyeRangeRows)
            assertEquals(preset.fisheyeStrengthPercent, profile.fisheyeStrengthPercent)
            assertEquals(preset.leftSafeInsetPercent, profile.leftSafeInsetPercent)
        }
    }

    @Test
    fun `crescent settings are untouched because old versions had no flow layout`() {
        val migrated = migratedLayoutProfiles(fisheyeStrengthPercent = 10)
        migrated.forEach { profile ->
            val preset = FlueLayoutProfiles.builtInFor(profile.layoutMode)
            assertEquals(preset.crescentBulgePercent, profile.crescentBulgePercent)
            assertEquals(preset.crescentDirection, profile.crescentDirection)
        }
        // 心流仍然带着出厂那道弧。
        assertEquals(
            FlueLayoutProfiles.Flow.crescentBulgePercent,
            migrated.first { it.layoutMode == LayoutMode.Flow }.crescentBulgePercent,
        )
    }

    @Test
    fun `values written by a corrupted store are clamped rather than rejected`() {
        // DataStore 里存的是裸 Int，理论上可以是任何值（外部编辑 / 旧版 bug）。
        // 迁移要留住尽可能多的设置，所以夹取而不是整份作废。
        val migrated = migratedLayoutProfiles(
            fisheyeRangeRows = 1_000,
            fisheyeStrengthPercent = -5,
            leftSafeInsetPercent = 80,
        )
        migrated.forEach { profile ->
            assertEquals(FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MAX, profile.fisheyeRangeRows)
            assertEquals(FLUE_LAYOUT_FISHEYE_STRENGTH_MIN, profile.fisheyeStrengthPercent)
            assertEquals(FLUE_LAYOUT_LEFT_SAFE_INSET_MAX, profile.leftSafeInsetPercent)
            profile.requireValid()
        }
    }

    @Test
    fun `migration output can be persisted as a library right away`() {
        val migrated = migratedLayoutProfiles(fisheyeRangeRows = 6)
        val decoded = FlueLayoutProfileJson.decodeLibrary(
            FlueLayoutProfileJson.encodeLibrary(migrated),
        )
        assertEquals(migrated, decoded)
    }
}
