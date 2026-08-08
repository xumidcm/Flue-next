package com.flue.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRoundInitialPositionTest {
    @Test
    fun `圆屏按目标卡片半高把卡片中心放到屏幕中心`() {
        assertEquals(
            -140,
            settingsCenteredItemScrollOffset(
                screenCenterY = 180f,
                itemHalfHeight = 40f,
            ),
        )
    }

    @Test
    fun `较高的表盘卡片会比普通设置行默认位置更靠上`() {
        val regularRowOffset = settingsCenteredItemScrollOffset(
            screenCenterY = 180f,
            itemHalfHeight = 28f,
        )
        val watchFaceCardOffset = settingsCenteredItemScrollOffset(
            screenCenterY = 180f,
            itemHalfHeight = 40f,
        )

        assertTrue(watchFaceCardOffset > regularRowOffset)
    }

    @Test
    fun `圆屏首次进入定位到首个可操作项`() {
        val position = settingsInitialPosition(
            isRound = true,
            savedIndex = 0,
            savedOffset = 0,
            firstActionIndex = 2,
        )

        assertEquals(2, position.index)
        assertEquals(0, position.offset)
        assertTrue(position.centerOnScreen)
    }

    @Test
    fun `方屏首次进入保持标题在列表顶部`() {
        val position = settingsInitialPosition(
            isRound = false,
            savedIndex = 0,
            savedOffset = 0,
            firstActionIndex = 2,
        )

        assertEquals(0, position.index)
        assertEquals(0, position.offset)
        assertFalse(position.centerOnScreen)
    }

    @Test
    fun `已保存滚动位置不被圆屏默认位置覆盖`() {
        val position = settingsInitialPosition(
            isRound = true,
            savedIndex = 5,
            savedOffset = 27,
            firstActionIndex = 2,
        )

        assertEquals(5, position.index)
        assertEquals(27, position.offset)
        assertFalse(position.centerOnScreen)
    }
}
