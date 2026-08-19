package com.flue.launcher.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 副本命名（老师报：复制出来全叫「副本」「副本副本」）。
 */
class FlueUniqueProfileNameTest {

    @Test
    fun `first copy is numbered two`() {
        // 原件算 1，所以第一个副本从 2 开始。
        assertEquals("心流 2", flueUniqueProfileName("心流", listOf("心流")))
    }

    @Test
    fun `copying a copy does not stack suffixes`() {
        // 这正是要修的：以前会变成「心流 副本 副本」。
        assertEquals("心流 3", flueUniqueProfileName("心流 2", listOf("心流", "心流 2")))
        assertEquals("心流 2", flueUniqueProfileName("心流 副本", listOf("心流")))
    }

    @Test
    fun `it skips names already taken`() {
        assertEquals(
            "心流 4",
            flueUniqueProfileName("心流", listOf("心流", "心流 2", "心流 3")),
        )
    }

    @Test
    fun `a blank source name still yields something usable`() {
        assertEquals("档案 2", flueUniqueProfileName("   ", emptyList()))
    }

    @Test
    fun `the result respects the name length cap`() {
        val long = "档".repeat(FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH + 10)
        val name = flueUniqueProfileName(long, emptyList())
        assertEquals(FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH, name.length)
    }

    @Test
    fun `existing names are compared after trimming`() {
        // 存量数据里可能有带空格的名字；不 trim 就会算成「没被占用」而重名。
        assertEquals("心流 3", flueUniqueProfileName("心流", listOf("心流", " 心流 2 ")))
    }
}
