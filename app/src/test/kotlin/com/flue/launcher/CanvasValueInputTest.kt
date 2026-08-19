package com.flue.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 画布调节的手输框校验。
 *
 * 老师报的是「输入 8 但滑块只到 5，确定按钮就灰掉」——所以这里刻意**不**看
 * 滑块量程，只挡真正会让变换矩阵退化的值。真正的上下限在渲染侧收敛一次
 * （`WatchmakerRuntimeContracts` 的 `sanitizedCanvasZoom` / `sanitizedCanvasOffset`）。
 */
class CanvasValueInputTest {
    private fun parse(raw: String): Float = requireNotNull(canvasInputValueOrNull(raw)) {
        "expected \"$raw\" to parse"
    }

    @Test
    fun `ordinary numbers parse`() {
        assertEquals(1.2f, parse("1.2"), 0f)
        assertEquals(0f, parse("0"), 0f)
        assertEquals(-200f, parse("-200"), 0f)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(3f, parse("  3  "), 0f)
        assertEquals(-1.5f, parse("\t-1.5\n"), 0f)
    }

    @Test
    fun `values beyond the slider range are accepted`() {
        // 滑块量程止于 5 / ±400，手输不受它约束。
        assertEquals(8f, parse("8"), 0f)
        assertEquals(19.5f, parse("19.5"), 0f)
        assertEquals(100_000f, parse("100000"), 0f)
        assertEquals(-100_000f, parse("-100000"), 0f)
    }

    @Test
    fun `non finite and unparseable input is rejected`() {
        assertNull(canvasInputValueOrNull("NaN"))
        assertNull(canvasInputValueOrNull("Infinity"))
        assertNull(canvasInputValueOrNull("-Infinity"))
        // 1e40 溢出 Float 变成 +Inf，同样要拒。
        assertNull(canvasInputValueOrNull("1e40"))
        assertNull(canvasInputValueOrNull(""))
        assertNull(canvasInputValueOrNull("   "))
        assertNull(canvasInputValueOrNull("abc"))
        assertNull(canvasInputValueOrNull("1.2.3"))
    }
}
