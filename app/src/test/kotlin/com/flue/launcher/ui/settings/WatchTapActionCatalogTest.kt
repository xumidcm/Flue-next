package com.flue.launcher.ui.settings

import com.flue.launcher.watchface.standard.WatchTapAction
import com.flue.launcher.watchface.standard.WatchTapActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTapActionCatalogTest {
    @Test
    fun `every configurable parser action appears in one category`() {
        val catalogKeys = WatchTapActionCatalog.categories
            .flatMap { it.actions }
            .flatMap { it.mappingKeys }

        assertEquals(WatchTapActionParser.CONFIGURABLE_ACTIONS.toSet(), catalogKeys.toSet())
        assertEquals(catalogKeys.size, catalogKeys.distinct().size)
        assertTrue(WatchTapActionCatalog.categories.all { it.actions.isNotEmpty() })
    }

    @Test
    fun `legacy aliases share one human readable action`() {
        val camera = WatchTapActionCatalog.categories
            .flatMap { it.actions }
            .single { it.id == "camera" }
        val weather = WatchTapActionCatalog.categories
            .flatMap { it.actions }
            .single { it.id == "weather" }

        assertEquals(setOf("w_camera", "m_camera"), camera.mappingKeys.toSet())
        assertEquals(setOf("widget_weather", "w_weather", "m_weather"), weather.mappingKeys.toSet())
        assertTrue(
            WatchTapActionCatalog.categories.sumOf { it.actions.size } <
                WatchTapActionParser.CONFIGURABLE_ACTIONS.size,
        )
    }

    /**
     * 编辑器「预设函数」按 [WatchTapActionDefinition.canonicalKey] 往图层里写动作名。
     * 写进去的名字必须是运行时**认得**的：解析器归到 `Unsupported` 的值，点表盘只会弹
     * 一句「动作不可用」—— 而且是运行时才看得出来，编辑时毫无提示。
     */
    @Test
    fun `every editor preset is recognised by the runtime parser`() {
        WatchTapActionCatalog.categories.flatMap { it.actions }.forEach { action ->
            val parsed = WatchTapActionParser.parse(action.canonicalKey)
            assertFalse(
                "preset ${action.id} writes ${action.canonicalKey}, parser: $parsed",
                parsed is WatchTapAction.Unsupported,
            )
            // 还必须是「可配置」的那一类，否则用户在设置页找不到地方给它绑应用。
            assertTrue(action.canonicalKey, WatchTapActionParser.isConfigurable(action.canonicalKey))
        }
    }

    /**
     * 首选键不能挑到 Flue 私有别名上。
     *
     * 编辑器导出的 .watch 还要能被别的 WatchMaker 宿主读懂，而那些宿主只认上游这 26 个名字
     * （`WM_TAP_ACTIONS.md` §3 = 上游 `TapActionHandler.CONFIGURABLE_ACTIONS`）。所以只要一个
     * 动作有上游拼法，[WatchTapActionDefinition.canonicalKey] 就必须是上游那一个 ——
     * 写成 `w_weather`（Flue 别名）而不是 `widget_weather`，表盘在别处就是个哑动作。
     */
    @Test
    fun `canonical key prefers the upstream spelling`() {
        val upstream = setOf(
            "tap_launcher", "widget_weather", "widget_health", "widget_calendar",
            "w_agenda", "w_fit", "w_heart_rate", "w_compass",
            "w_keep", "m_email", "m_calendar", "voice_search",
            "w_contacts", "w_sports", "w_gallery", "w_messaging",
            "w_camera", "w_dialer", "w_music", "w_clock",
            "w_calculator", "w_payment", "w_map", "browser",
            "flashlight", "socialmedia",
        )
        WatchTapActionCatalog.categories.flatMap { it.actions }
            .filter { action -> action.mappingKeys.any { it in upstream } }
            .forEach { action ->
                assertTrue(
                    "${action.id} 有上游拼法，却把 ${action.canonicalKey} 排在第一位",
                    action.canonicalKey in upstream,
                )
            }
    }

    /**
     * 编辑器原来在「点击动作」那一行摆了「日历」「天气」两个写死的按钮，直接写
     * `widget_calendar` / `widget_weather`。它们被整份预设名单替换掉了，所以这两个动作必须
     * 仍然选得到 —— 否则这次改动就是**拿掉了**用户原本一键可得的两个动作。
     */
    @Test
    fun `presets still cover the two buttons they replaced`() {
        val presets = WatchTapActionCatalog.categories.flatMap { it.actions }.map { it.canonicalKey }

        assertTrue(presets.toString(), "widget_calendar" in presets)
        assertTrue(presets.toString(), "widget_weather" in presets)
    }
}
