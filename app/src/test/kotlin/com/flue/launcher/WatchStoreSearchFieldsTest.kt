package com.flue.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索框文本 → 上游的 `search` / `tag` 两个参数（B3）。
 *
 * 手表屏幕上不值得再摆一个「搜索模式」开关，所以用 `#` 前缀区分。这条映射同时也是详情页
 * 点标签跳搜索时填进去的形式。
 */
class WatchStoreSearchFieldsTest {

    @Test
    fun `plain text searches names`() {
        val fields = watchStoreSearchFields("  猫 clock  ")
        assertEquals("猫 clock", fields.search)
        assertNull(fields.tag)
    }

    @Test
    fun `a hash prefix switches to the tag parameter`() {
        val fields = watchStoreSearchFields("#运动")
        assertNull(fields.search)
        assertEquals("运动", fields.tag)
        // `# 运动` 也算 —— 用户会顺手打空格。
        assertEquals("运动", watchStoreSearchFields("# 运动").tag)
    }

    @Test
    fun `an empty or oversized tag falls back to a plain search`() {
        // 只打了一个 `#`：不能把输入吃成「什么都没搜」。
        assertEquals("#", watchStoreSearchFields("#").search)
        assertNull(watchStoreSearchFields("#").tag)

        // 上游 tag 限 50 字符，超了会 400 —— 退回普通搜索，别白吃一个错误。
        val long = "#" + "a".repeat(51)
        assertEquals(long, watchStoreSearchFields(long).search)
        assertNull(watchStoreSearchFields(long).tag)

        // 正好 50 仍然是标签。
        assertEquals("a".repeat(50), watchStoreSearchFields("#" + "a".repeat(50)).tag)
    }

    @Test
    fun `blank input asks for no filter at all`() {
        val fields = watchStoreSearchFields("   ")
        assertNull(fields.search)
        assertNull(fields.tag)
    }
}
