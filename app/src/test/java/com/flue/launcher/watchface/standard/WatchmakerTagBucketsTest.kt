package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 2：tag 分桶 + 推模型。
 *
 * 分桶的正确性判据不是"名字像什么"，而是 [WatchmakerTagResolver.resolve] 真正读了
 * 哪个日历字段 —— 所以这里对每个易错 tag 都钉死到具体的桶。
 */
class WatchmakerTagBucketsTest {

    @Test
    fun `毫秒驱动的平滑表针归入 FRAME 桶`() {
        // {drm} 读 MILLISECOND 而不是 MINUTE：按名字归类会让分针一秒一跳。
        for (tag in listOf("dss", "dssz", "dsps", "drss", "drms", "drm", "dtp")) {
            assertEquals("tag=$tag", WatchTagBucket.FRAME, WatchTagBuckets.of(tag))
        }
    }

    @Test
    fun `秒级 tag 归入 SECOND 桶`() {
        // {drh} 是 (时 + 分/60 + 秒/3600) * 30，秒级平滑，不是 HOUR。
        for (tag in listOf("ds", "dsz", "drs", "drh")) {
            assertEquals("tag=$tag", WatchTagBucket.SECOND, WatchTagBuckets.of(tag))
        }
    }

    @Test
    fun `整分整点整日 tag 归入对应慢桶`() {
        assertEquals(WatchTagBucket.MINUTE, WatchTagBuckets.of("dm"))
        assertEquals(WatchTagBucket.MINUTE, WatchTagBuckets.of("drm0"))
        assertEquals(WatchTagBucket.MINUTE, WatchTagBuckets.of("drmh24"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("dh"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("dh12"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("da"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("drh0"))
        assertEquals(WatchTagBucket.DAY, WatchTagBuckets.of("dd"))
        assertEquals(WatchTagBucket.DAY, WatchTagBuckets.of("ddww"))
        assertEquals(WatchTagBucket.DAY, WatchTagBuckets.of("dyyyy"))
    }

    @Test
    fun `传感器与外部事件分别归入 SENSOR 和 PUSH 桶`() {
        for (tag in listOf("sgx", "sgy", "sgz")) {
            assertEquals("tag=$tag", WatchTagBucket.SENSOR, WatchTagBuckets.of(tag))
        }
        for (tag in listOf("bl", "batt", "bc", "ssc", "shr", "pws", "nbc", "wt", "wf3dt")) {
            assertEquals("tag=$tag", WatchTagBucket.PUSH, WatchTagBuckets.of(tag))
        }
    }

    @Test
    fun `未知 tag 保守归入 PUSH 而不是 STATIC`() {
        // 桶从严：认不出来的 tag 宁可每拍比对，也不能当成恒定值烘死。
        assertEquals(WatchTagBucket.PUSH, WatchTagBuckets.of("totally_unknown_tag"))
        assertEquals(WatchTagBucket.STATIC, WatchTagBuckets.of("ucolor"))
    }

    @Test
    fun `UTC 变体与基础 tag 同桶`() {
        assertEquals(WatchTagBucket.MINUTE, WatchTagBuckets.of("utc_dm"))
        assertEquals(WatchTagBucket.MINUTE, WatchTagBuckets.of("dm_utc"))
        assertEquals(WatchTagBucket.MINUTE, WatchTagBuckets.of("dmutc"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("dhutc12"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("dhutc24"))
        assertEquals(WatchTagBucket.FRAME, WatchTagBuckets.of("utc_dss"))
    }

    @Test
    fun `计数器 tag 归入 FRAME 桶`() {
        // 时间来源是渲染器自己的 elapsed，不是日历。
        assertEquals(WatchTagBucket.FRAME, WatchTagBuckets.of("c_elapsed"))
    }

    @Test
    fun `归一化去掉花括号与大小写`() {
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("{DH}"))
        assertEquals(WatchTagBucket.HOUR, WatchTagBuckets.of("  { dh }  "))
        assertEquals("dh", WatchTagBuckets.normalize("{ DH }"))
    }

    @Test
    fun `快桶判定只覆盖 FRAME SECOND SENSOR`() {
        assertTrue(WatchTagBucket.FRAME.isFast)
        assertTrue(WatchTagBucket.SECOND.isFast)
        assertTrue(WatchTagBucket.SENSOR.isFast)
        assertFalse(WatchTagBucket.MINUTE.isFast)
        assertFalse(WatchTagBucket.HOUR.isFast)
        assertFalse(WatchTagBucket.DAY.isFast)
        assertFalse(WatchTagBucket.PUSH.isFast)
        assertFalse(WatchTagBucket.STATIC.isFast)
    }
}

class WatchLayerTagProfileTest {

    @Test
    fun `无 tag 的图层在 generation 内恒定`() {
        val profile = WatchLayerTagProfile.of(emptyList())
        assertTrue(profile.isConstantWithinGeneration)
        assertFalse(profile.hasFastDependency)
    }

    @Test
    fun `只引用 ucolor 的图层仍算恒定`() {
        val profile = WatchLayerTagProfile.of(listOf("ucolor"))
        assertTrue(profile.isConstantWithinGeneration)
        assertFalse(profile.hasFastDependency)
    }

    @Test
    fun `混入一个快 tag 就整体变成快依赖`() {
        val profile = WatchLayerTagProfile.of(listOf("dh", "dm", "dss"))
        assertTrue(profile.hasFastDependency)
        assertFalse(profile.isConstantWithinGeneration)
    }

    @Test
    fun `慢桶集合剔除快桶与 STATIC`() {
        val profile = WatchLayerTagProfile.of(listOf("dss", "dm", "dd", "ucolor", "bl"))
        assertEquals(
            setOf(
                WatchTagBucket.MINUTE,
                WatchTagBucket.DAY,
                WatchTagBucket.PUSH,
            ),
            profile.slowBuckets,
        )
    }

    @Test
    fun `重复与大小写不同的 tag 归一为一个`() {
        val profile = WatchLayerTagProfile.of(listOf("{DH}", "dh", " dh "))
        assertEquals(setOf("dh"), profile.tags)
    }
}

class WatchTagChangeTrackerTest {

    @Test
    fun `慢桶表盘取值没变就不重绘`() {
        val tracker = WatchTagChangeTracker(listOf("dh", "dm"))
        assertFalse(tracker.requiresRedrawEveryTick)
        val values = mutableMapOf("dh" to "10", "dm" to "30")
        // 首拍必须画：缓存是空的，没有可比对的基准。
        assertTrue(tracker.poll { values[it] })
        assertFalse(tracker.poll { values[it] })
        assertFalse(tracker.poll { values[it] })
        values["dm"] = "31"
        assertTrue(tracker.poll { values[it] })
        assertFalse(tracker.poll { values[it] })
    }

    @Test
    fun `poll 必须重算完所有 tag 才返回`() {
        // wf_engine.lua:122 的坑：中途发现变化就 break，会让后半截 tag 的缓存
        // 停在上一拍，下一拍它们凭空"变了"一次。
        val tracker = WatchTagChangeTracker(listOf("dh", "dm", "dd"))
        val values = mutableMapOf("dh" to "10", "dm" to "30", "dd" to "5")
        assertTrue(tracker.poll { values[it] })
        // 同一拍里第一个和最后一个同时变。
        values["dh"] = "11"
        values["dd"] = "6"
        val visited = mutableListOf<String>()
        assertTrue(
            tracker.poll { tag ->
                visited += tag
                values[tag]
            },
        )
        assertEquals(listOf("dh", "dm", "dd"), visited)
        // 若上一拍提前 break，这一拍 dd 会被误判为"刚变化"。
        assertFalse(tracker.poll { values[it] })
    }

    @Test
    fun `快桶表盘每拍都要重绘`() {
        val tracker = WatchTagChangeTracker(listOf("dh", "dss"))
        assertTrue(tracker.requiresRedrawEveryTick)
        val values = mapOf("dh" to "10", "dss" to "123")
        assertTrue(tracker.poll { values[it] })
        assertTrue(tracker.poll { values[it] })
    }

    @Test
    fun `外部快依赖可以强制每拍重绘`() {
        // GIF / anim_in / anim_out 的时间来源不是 tag，桶模型看不见它们。
        val tracker = WatchTagChangeTracker(listOf("dh"), forceFastTicks = true)
        assertTrue(tracker.requiresRedrawEveryTick)
        val values = mapOf("dh" to "10")
        assertTrue(tracker.poll { values[it] })
        assertTrue(tracker.poll { values[it] })
    }

    @Test
    fun `快桶表盘仍然维护慢桶缓存`() {
        // 否则日后走全量入口回到慢路径时，会凭空误报一次变化。
        val tracker = WatchTagChangeTracker(listOf("dh", "dss"), forceFastTicks = true)
        val values = mutableMapOf("dh" to "10", "dss" to "1")
        assertTrue(tracker.poll { values[it] })
        assertTrue(tracker.poll { values[it] })
        assertEquals(1, tracker.watchedTagCount)
    }

    @Test
    fun `纯静态表盘只画首帧`() {
        val tracker = WatchTagChangeTracker(listOf("ucolor"))
        assertFalse(tracker.requiresRedrawEveryTick)
        assertEquals(0, tracker.watchedTagCount)
        assertTrue(tracker.poll { "#ffffff" })
        assertFalse(tracker.poll { "#ffffff" })
        assertFalse(tracker.poll { "#ffffff" })
    }

    @Test
    fun `完全没有 tag 的表盘只画首帧`() {
        val tracker = WatchTagChangeTracker(emptyList())
        assertTrue(tracker.poll { null })
        assertFalse(tracker.poll { null })
    }

    @Test
    fun `全量重绘入口强制下一拍重绘`() {
        val tracker = WatchTagChangeTracker(listOf("dh"))
        val values = mapOf("dh" to "10")
        assertTrue(tracker.poll { values[it] })
        assertFalse(tracker.poll { values[it] })
        tracker.invalidateAll()
        assertTrue(tracker.poll { values[it] })
        assertFalse(tracker.poll { values[it] })
    }

    @Test
    fun `静态表盘走全量重绘入口后也会再画一帧`() {
        val tracker = WatchTagChangeTracker(emptyList())
        assertTrue(tracker.poll { null })
        assertFalse(tracker.poll { null })
        tracker.invalidateAll()
        assertTrue(tracker.poll { null })
    }

    @Test
    fun `取值解析抛异常不会中断整轮比对`() {
        val tracker = WatchTagChangeTracker(listOf("dh", "dm"))
        val visited = mutableListOf<String>()
        tracker.poll { tag ->
            visited += tag
            if (tag == "dh") error("boom") else "30"
        }
        assertEquals(listOf("dh", "dm"), visited)
        // 抛异常视同不可用；稳定不可用不应被当成持续变化。
        assertFalse(
            tracker.poll { tag -> if (tag == "dh") error("boom") else "30" },
        )
    }

    @Test
    fun `不可用转为可用会触发重绘`() {
        val tracker = WatchTagChangeTracker(listOf("bl"))
        var battery: String? = null
        assertTrue(tracker.poll { battery })
        assertFalse(tracker.poll { battery })
        battery = "80"
        assertTrue(tracker.poll { battery })
        assertFalse(tracker.poll { battery })
    }
}
