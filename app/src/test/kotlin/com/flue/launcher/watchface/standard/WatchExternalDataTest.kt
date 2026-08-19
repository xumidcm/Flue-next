package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 —— clox External Data API 的写入校验。
 *
 * 这一层是**任何已安装 App 都能触达**的入口（上游的 API 就是无权限的），所以这些断言不是
 * 「顺手测一下」，而是攻击面本身的规格：越界静默忽略、长度封顶、控制字符剔掉、时间戳由本机盖。
 */
class WatchExternalDataTest {

    @Test
    fun `a well formed write is accepted`() {
        val write = WatchExternalData.sanitizeWrite(1, "24.5", "outdoor_temp", nowMs = 1_000L)
        assertNotNull(write)
        assertEquals(1, write!!.slot)
        assertEquals("24.5", write.slotData.data)
        assertEquals("outdoor_temp", write.slotData.key)
        assertEquals(1_000L, write.slotData.timestampMs)
    }

    @Test
    fun `slot numbers outside one to ten are silently ignored`() {
        // 上游明写「非法槽位号静默忽略」，不是报错。
        assertNull(WatchExternalData.sanitizeWrite(0, "x", null, 0L))
        assertNull(WatchExternalData.sanitizeWrite(11, "x", null, 0L))
        assertNull(WatchExternalData.sanitizeWrite(-1, "x", null, 0L))
        assertNotNull(WatchExternalData.sanitizeWrite(10, "x", null, 0L))
    }

    @Test
    fun `a missing data extra is ignored but an empty string is a valid write`() {
        assertNull(WatchExternalData.sanitizeWrite(1, null, "k", 0L))
        // 空串是合法写入（等于「清空这个槽位」），不能和缺参数混为一谈。
        val cleared = WatchExternalData.sanitizeWrite(1, "", "k", 0L)
        assertNotNull(cleared)
        assertEquals("", cleared!!.slotData.data)
    }

    @Test
    fun `data and key are capped`() {
        val write = WatchExternalData.sanitizeWrite(
            slot = 3,
            data = "a".repeat(WatchExternalData.MAX_DATA_LENGTH * 2),
            key = "b".repeat(WatchExternalData.MAX_KEY_LENGTH * 2),
            nowMs = 0L,
        )
        assertEquals(WatchExternalData.MAX_DATA_LENGTH, write!!.slotData.data.length)
        assertEquals(WatchExternalData.MAX_KEY_LENGTH, write.slotData.key.length)
    }

    @Test
    fun `control characters are stripped but newlines and tabs survive`() {
        // 载荷用字符码拼，不写转义序列：源文件里埋裸控制字符经不起编辑器与
        // CRLF 转换，写成 \u0000 又容易在生成脚本里被二次转义。
        val nul = 0.toChar()
        val bell = 7.toChar()
        val del = 127.toChar()
        val newline = 10.toChar()
        val tab = 9.toChar()
        val write = WatchExternalData.sanitizeWrite(
            slot = 2,
            data = "a${nul}b${bell}c${del}d${newline}e${tab}f",
            key = null,
            nowMs = 0L,
        )
        assertEquals("abcd${newline}e${tab}f", write!!.slotData.data)
    }

    @Test
    fun `json payloads pass through untouched`() {
        // 上游文档把 JSON 明确当作传结构化数据的方式，所以花括号绝不能被过滤掉。
        val payload = """{"temp":24.5,"unit":"C"}"""
        val write = WatchExternalData.sanitizeWrite(4, payload, null, 0L)
        assertEquals(payload, write!!.slotData.data)
    }

    @Test
    fun `a braced tag in the payload stays a literal string`() {
        // 槽位值只作为字符串值交给 Lua，不会被当成 {tag} 二次展开 —— 这里钉住
        // 「校验层不做任何展开或改写」。
        val write = WatchExternalData.sanitizeWrite(5, "{dh}:{dm}", null, 0L)
        assertEquals("{dh}:{dm}", write!!.slotData.data)
    }

    @Test
    fun `the key defaults to an empty label`() {
        val write = WatchExternalData.sanitizeWrite(6, "v", null, 0L)
        assertEquals("", write!!.slotData.key)
    }

    @Test
    fun `a never written slot reads as the documented empty triple`() {
        assertEquals("", WatchExternalData.EMPTY_SLOT.data)
        assertEquals("", WatchExternalData.EMPTY_SLOT.key)
        assertEquals(0L, WatchExternalData.EMPTY_SLOT.timestampMs)
    }

    @Test
    fun `negative clocks never land in a slot timestamp`() {
        val write = WatchExternalData.sanitizeWrite(7, "v", null, nowMs = -5L)
        assertTrue(write!!.slotData.timestampMs >= 0L)
    }

    @Test
    fun `slot state is one indexed and updates only the addressed slot`() {
        val state = WatchExternalDataSlotState(
            initialSlots = listOf(
                WatchExternalDataSlot(data = "old-1", key = "first", timestampMs = 10L),
                WatchExternalDataSlot(data = "old-2", key = "second", timestampMs = 20L),
            ),
        )
        val replacement = WatchExternalDataSlot(data = "new-2", key = "changed", timestampMs = 30L)

        assertEquals(0L, state.version)
        state.write(WatchExternalDataWrite(slot = 2, slotData = replacement))

        assertEquals(1L, state.version)
        assertEquals("old-1", state.slot(1).data)
        assertEquals(replacement, state.slot(2))
        assertEquals(WatchExternalData.EMPTY_SLOT, state.slot(3))
        assertEquals(WatchExternalData.EMPTY_SLOT, state.slot(0))
        assertEquals(WatchExternalData.EMPTY_SLOT, state.slot(11))
    }

    @Test
    fun `slot listeners receive writes until removed`() {
        val state = WatchExternalDataSlotState(emptyList())
        val observed = mutableListOf<Int>()
        val listener: (Int) -> Unit = observed::add
        state.addListener(listener)

        state.write(
            WatchExternalDataWrite(
                slot = 4,
                slotData = WatchExternalDataSlot(data = "first"),
            ),
        )
        state.removeListener(listener)
        state.write(
            WatchExternalDataWrite(
                slot = 5,
                slotData = WatchExternalDataSlot(data = "second"),
            ),
        )

        assertEquals(listOf(4), observed)
    }
}
