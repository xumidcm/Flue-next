package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** H2 — 头像本地约束。纯函数，不需要 Robolectric。 */
class WatchStoreAvatarRulesTest {

    @Test
    fun `long edge is capped at 512 keeping aspect ratio`() {
        val size = WatchStoreAvatarRules.fitWithinMaxEdge(4000, 3000)
        assertEquals(512, size.width)
        assertEquals(384, size.height)
    }

    @Test
    fun `portrait sources cap on height`() {
        val size = WatchStoreAvatarRules.fitWithinMaxEdge(600, 1200)
        assertEquals(256, size.width)
        assertEquals(512, size.height)
    }

    @Test
    fun `sources smaller than the cap are not upscaled`() {
        val size = WatchStoreAvatarRules.fitWithinMaxEdge(300, 200)
        assertEquals(300, size.width)
        assertEquals(200, size.height)
    }

    /** 极端细长图缩放取整后不能出现 0 —— `createScaledBitmap(0, …)` 会直接抛。 */
    @Test
    fun `extremely elongated sources keep at least one pixel per edge`() {
        val size = WatchStoreAvatarRules.fitWithinMaxEdge(20_000, 3)
        assertEquals(512, size.width)
        assertTrue(size.height >= 1)
    }

    @Test
    fun `non positive dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreAvatarRules.fitWithinMaxEdge(0, 100)
        }
    }

    /** 一次降质：阶梯正好两档，且是降序。 */
    @Test
    fun `quality ladder retries exactly once at a lower quality`() {
        assertEquals(2, WatchStoreAvatarRules.QUALITY_LADDER.size)
        val (first, second) = WatchStoreAvatarRules.QUALITY_LADDER
        assertTrue(first > second)
        assertTrue(WatchStoreAvatarRules.QUALITY_LADDER.all { it in 1..100 })
    }

    @Test
    fun `size limit matches the upstream two megabyte cap`() {
        assertEquals(2L * 1024L * 1024L, WatchStoreAvatarRules.MAX_BYTES)
        assertTrue(WatchStoreAvatarRules.withinSizeLimit(WatchStoreAvatarRules.MAX_BYTES))
        assertFalse(WatchStoreAvatarRules.withinSizeLimit(WatchStoreAvatarRules.MAX_BYTES + 1))
    }

    /** 编码失败会给出 0 字节文件，那种不能当成「通过」传上去。 */
    @Test
    fun `empty payloads are not uploadable`() {
        assertFalse(WatchStoreAvatarRules.withinSizeLimit(0))
    }
}
