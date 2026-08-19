package com.flue.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页滚动位置记忆的策略测试。
 *
 * 这里刻意不碰 Compose：真正决定「回到顶部还是回到原位」的只有三样纯逻辑 ——
 * 深度表、[settingsScrollResetsOnEnter] 的判定、[SettingsScrollMemory] 的账本。
 * [FakeSettingsNavigator] 把 SettingsActivity 里 navigateTo / handleBack /
 * SettingsPageScaffold 三处的接线原样复刻一遍，这样「二级页进三级页再退回来」
 * 这类多步场景才能在单测里跑出来。
 */
class SettingsScrollMemoryTest {

    /**
     * 复刻生产代码的导航接线：
     * - [navigateTo] 对应 SettingsActivity 的同名 lambda（先算 poppingBack 再判是否清位置）；
     * - [back] 对应 handleBack（回退永不清位置）；
     * - [scrollTo] 对应 SettingsPageScaffold 里滚动停稳/页面销毁时报上来的位置。
     */
    private class FakeSettingsNavigator {
        private val memory = SettingsScrollMemory()
        private val backStack = mutableListOf<SettingsDestination>()
        var current: SettingsDestination = SettingsDestination.ROOT
            private set

        fun scrollTo(index: Int, offset: Int) {
            memory.record(current, index, offset)
        }

        fun navigateTo(next: SettingsDestination) {
            if (next == current) return
            val poppingBack = backStack.lastOrNull() == next
            if (settingsScrollResetsOnEnter(current, next, poppingBack)) {
                memory.forget(next)
            }
            if (poppingBack) {
                backStack.removeAt(backStack.lastIndex)
            } else {
                backStack.add(current)
            }
            current = next
        }

        fun back() {
            val previous = backStack.lastOrNull() ?: return
            backStack.removeAt(backStack.lastIndex)
            current = previous
        }

        /** 当前页重新组合时 scaffold 会拿到的初始位置。 */
        fun restoredPosition(): SavedScrollPosition = memory.positionOf(current)
    }

    @Test
    fun `首次进入二级页面落在顶部`() {
        val nav = FakeSettingsNavigator()

        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)

        assertEquals(SavedScrollPosition(0, 0), nav.restoredPosition())
    }

    @Test
    fun `二级页进三级页再退回来恢复离开时的位置`() {
        val nav = FakeSettingsNavigator()
        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)
        nav.scrollTo(index = 6, offset = 18)

        nav.navigateTo(SettingsDestination.GESTURE_SETTINGS)
        nav.back()

        assertEquals(SettingsDestination.FEATURE_TOGGLES, nav.current)
        assertEquals(SavedScrollPosition(6, 18), nav.restoredPosition())
    }

    @Test
    fun `三级页首次进入不会捡到父页的位置`() {
        val nav = FakeSettingsNavigator()
        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)
        nav.scrollTo(index = 6, offset = 18)

        nav.navigateTo(SettingsDestination.GESTURE_SETTINGS)

        assertEquals(SavedScrollPosition(0, 0), nav.restoredPosition())
    }

    @Test
    fun `四层嵌套逐层回退各自恢复到离开时的位置`() {
        val nav = FakeSettingsNavigator()
        nav.scrollTo(index = 2, offset = 5)
        nav.navigateTo(SettingsDestination.WATCH_FACES)
        nav.scrollTo(index = 3, offset = 7)
        nav.navigateTo(SettingsDestination.WATCH_FACE_MORE)
        nav.scrollTo(index = 4, offset = 9)
        nav.navigateTo(SettingsDestination.TAP_ACTION_CATEGORIES)
        nav.scrollTo(index = 5, offset = 11)
        nav.navigateTo(SettingsDestination.TAP_ACTION_CATEGORY)

        nav.back()
        assertEquals(SettingsDestination.TAP_ACTION_CATEGORIES, nav.current)
        assertEquals(SavedScrollPosition(5, 11), nav.restoredPosition())

        nav.back()
        assertEquals(SettingsDestination.WATCH_FACE_MORE, nav.current)
        assertEquals(SavedScrollPosition(4, 9), nav.restoredPosition())

        nav.back()
        assertEquals(SettingsDestination.WATCH_FACES, nav.current)
        assertEquals(SavedScrollPosition(3, 7), nav.restoredPosition())

        nav.back()
        assertEquals(SettingsDestination.ROOT, nav.current)
        assertEquals(SavedScrollPosition(2, 5), nav.restoredPosition())
    }

    @Test
    fun `离开时停在页顶回退后仍在页顶`() {
        val nav = FakeSettingsNavigator()
        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)
        nav.scrollTo(index = 6, offset = 18)
        // 用户自己滑回顶部再进子页：回退时不能拿到那个已经作废的 (6, 18)。
        nav.scrollTo(index = 0, offset = 0)

        nav.navigateTo(SettingsDestination.GESTURE_SETTINGS)
        nav.back()

        assertEquals(SavedScrollPosition(0, 0), nav.restoredPosition())
    }

    @Test
    fun `从上一级重新进入同一页回到顶部而不是上次的位置`() {
        val nav = FakeSettingsNavigator()
        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)
        nav.scrollTo(index = 6, offset = 18)
        nav.back()

        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)

        assertEquals(SavedScrollPosition(0, 0), nav.restoredPosition())
    }

    @Test
    fun `同深度页面之间的跳转按往里进处理`() {
        val nav = FakeSettingsNavigator()
        nav.navigateTo(SettingsDestination.APPEARANCE)
        nav.navigateTo(SettingsDestination.DISPLAY_SIZE)
        nav.scrollTo(index = 4, offset = 11)
        nav.back()

        // APPEARANCE 与 DISPLAY_SIZE 深度都是 1，靠 alwaysOpensAtTop 点名才判得出。
        nav.navigateTo(SettingsDestination.DISPLAY_SIZE)

        assertEquals(SavedScrollPosition(0, 0), nav.restoredPosition())
    }

    @Test
    fun `用 navigateTo 走回退那一格也不清位置`() {
        val nav = FakeSettingsNavigator()
        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)
        nav.scrollTo(index = 6, offset = 18)
        nav.navigateTo(SettingsDestination.GESTURE_SETTINGS)

        // 有些行是直接 navigateTo(父页) 回去的，走的不是 handleBack。
        nav.navigateTo(SettingsDestination.FEATURE_TOGGLES)

        assertEquals(SavedScrollPosition(6, 18), nav.restoredPosition())
    }

    @Test
    fun `回退方向永不重置位置`() {
        // 这一条是老师报的那个 bug 的最小形式：退回去的页面绝不能被清。
        assertFalse(
            settingsScrollResetsOnEnter(
                from = SettingsDestination.GESTURE_SETTINGS,
                to = SettingsDestination.FEATURE_TOGGLES,
                poppingBack = true,
            )
        )
        assertFalse(
            settingsScrollResetsOnEnter(
                from = SettingsDestination.TAP_ACTION_CATEGORY,
                to = SettingsDestination.TAP_ACTION_CATEGORIES,
                poppingBack = true,
            )
        )
    }

    @Test
    fun `往里进要清位置`() {
        assertTrue(
            settingsScrollResetsOnEnter(
                from = SettingsDestination.ROOT,
                to = SettingsDestination.PERFORMANCE,
                poppingBack = false,
            )
        )
        assertTrue(
            settingsScrollResetsOnEnter(
                from = SettingsDestination.PERFORMANCE,
                to = SettingsDestination.PERFORMANCE_MOTION,
                poppingBack = false,
            )
        )
    }

    @Test
    fun `账本一律记录不看页面身份`() {
        // 根因回归：以前 updateScroll 对 alwaysOpensAtTop 的页面直接不记，
        // 于是这些页面连「进子页再退回来」都没位置可恢复。
        val memory = SettingsScrollMemory()

        for (page in listOf(
            SettingsDestination.FEATURE_TOGGLES,
            SettingsDestination.DISPLAY_SIZE,
            SettingsDestination.MOTION_PROFILE,
            SettingsDestination.TAP_ACTION_CATEGORY,
        )) {
            assertTrue(page.alwaysOpensAtTop)
            memory.record(page, index = 3, offset = 12)
            assertEquals(SavedScrollPosition(3, 12), memory.positionOf(page))
        }
    }

    @Test
    fun `未记录过的页面回报页顶`() {
        val memory = SettingsScrollMemory()

        assertEquals(SavedScrollPosition(0, 0), memory.positionOf(SettingsDestination.ABOUT))
    }

    @Test
    fun `深度表覆盖表盘动作那条链`() {
        // 这三页以前漏在深度表外（默认 1），从深度 2 的「更多表盘选项」进来时
        // 判不出是往里进，第一次进页面会捡到上一次的位置。
        assertTrue(
            SettingsDestination.TAP_ACTION_CATEGORIES.settingsDepth >
                SettingsDestination.WATCH_FACE_MORE.settingsDepth
        )
        assertTrue(
            SettingsDestination.APP_LIST_BACKGROUND.settingsDepth >
                SettingsDestination.WATCH_FACE_MORE.settingsDepth
        )
        assertTrue(
            SettingsDestination.TAP_ACTION_CATEGORY.settingsDepth >
                SettingsDestination.TAP_ACTION_CATEGORIES.settingsDepth
        )
    }
}
