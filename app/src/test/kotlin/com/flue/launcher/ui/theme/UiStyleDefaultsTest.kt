package com.flue.launcher.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStyleDefaultsTest {
    /**
     * 没存过、或者存了个认不出来的值，都落回 watchOS 档。
     *
     * 这里断言的是 [DEFAULT_UI_STYLE] 本身而不是写死某一档：默认档是产品决定
     * （老师要求启动默认用 watchOS 风格），会再变；而「解析不出来必须兜回默认」
     * 这条不变。写死枚举的话，下次换默认档又要来改一遍这条测试。
     */
    @Test
    fun `missing and invalid preferences fall back to the default style`() {
        assertEquals(UiStyle.APPLE_WATCH, DEFAULT_UI_STYLE)
        assertEquals(DEFAULT_UI_STYLE, uiStyleFromPreference(null))
        assertEquals(DEFAULT_UI_STYLE, uiStyleFromPreference("not-a-style"))
    }

    @Test
    fun `valid saved style is preserved`() {
        assertEquals(UiStyle.APPLE_WATCH, uiStyleFromPreference(UiStyle.APPLE_WATCH.name))
        assertEquals(UiStyle.MATERIAL_3, uiStyleFromPreference(UiStyle.MATERIAL_3.name))
        assertEquals(UiStyle.MIUIX, uiStyleFromPreference(UiStyle.MIUIX.name))
    }

    @Test
    fun `every style caps its capsule radius and the three caps differ`() {
        // 三档都必须有上限：没有上限的那一档会让被内容撑高的行拉成长药丸，
        // 而且弧深会随行高一路涨（老师报的「带滑竿的胶囊撑到边缘」）。
        val caps = listOf(
            appleWatchLauncherStyle(darkMode = true).capsuleMaxRadius,
            materialLauncherStyle(darkMode = true).capsuleMaxRadius,
            miuixLauncherStyle(darkMode = true).capsuleMaxRadius,
        )
        caps.forEachIndexed { index, cap ->
            assertNotNull("style #$index has no capsule radius cap", cap)
        }
        // 互不相同：三套的形状语言各自成立，防「三套抄成一套」。
        assertEquals(3, caps.toSet().size)
        // APPLE 的上限必须严格大于 56dp 标准行的半高（28dp），否则标准单行就不是真胶囊了。
        assertTrue(appleWatchLauncherStyle(darkMode = true).capsuleMaxRadius!! > 28.dp)
    }

    @Test
    fun `dark and light variants share the same capsule cap`() {
        // 上限是形状语言，不随深浅色变。
        listOf(
            ::appleWatchLauncherStyle,
            ::materialLauncherStyle,
            ::miuixLauncherStyle,
        ).forEach { factory ->
            assertEquals(
                factory(true).capsuleMaxRadius,
                factory(false).capsuleMaxRadius,
            )
        }
    }
}
