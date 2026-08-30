package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3：静态图层烘焙（计划 §五 要求的三项：快 tag 必须不可烘、成段只合并连续层、
 * 成段不跨视频边界）。
 *
 * 判定测试全部按「从严」的方向写：每一条不可烘条件都单独钉一个用例，因为漏判的
 * 代价是图层被烘死冻住（用户可见的 bug），而误判只是少省一点性能。
 */
class WatchStaticFlattenBakeableTest {

    private fun layer(
        type: String,
        attributes: Map<String, String> = emptyMap(),
    ): WatchmakerLayer = WatchmakerLayer(
        id = null,
        type = type,
        parentId = null,
        attributes = attributes,
        children = emptyList(),
    )

    private fun stageChild(layerIndex: Int = 0): WatchLayerRenderItem =
        WatchLayerRenderItem(layerIndex = layerIndex, ancestorChain = null)

    private fun groupChild(layerIndex: Int = 1, groupIndex: Int = 0): WatchLayerRenderItem =
        WatchLayerRenderItem(
            layerIndex = layerIndex,
            ancestorChain = WatchLayerAncestorChain(layerIndex = groupIndex, outer = null),
        )

    private fun bakeable(
        layer: WatchmakerLayer,
        item: WatchLayerRenderItem = stageChild(),
        tags: List<String> = emptyList(),
    ): Boolean = WatchStaticFlatten.isBakeable(layer, item, WatchLayerTagProfile.of(tags))

    @Test
    fun `无 tag 的静态图片可以烘`() {
        assertTrue(bakeable(layer("image", mapOf("path" to "bg.png"))))
    }

    @Test
    fun `只引用 STATIC tag 的图层可以烘`() {
        // ucolor 只有走全量重绘入口才会变，一个 generation 内恒定。
        assertTrue(bakeable(layer("text", mapOf("color" to "{ucolor}")), tags = listOf("ucolor")))
    }

    @Test
    fun `快 tag 图层一律不可烘`() {
        // 计划 §五 第一项。FRAME / SECOND / SENSOR 三个快桶各钉一个。
        for (tag in listOf("dss", "drm", "dtp", "ds", "drh", "sgx")) {
            assertFalse(
                "tag=$tag",
                bakeable(layer("text", mapOf("text" to "{$tag}")), tags = listOf(tag)),
            )
        }
    }

    @Test
    fun `Lua函数驱动的图层不可烘`() {
        assertFalse(
            WatchStaticFlatten.isBakeable(
                layer = layer("image", mapOf("x" to "var_T_x(0, var_TP)")),
                item = stageChild(),
                profile = WatchLayerTagProfile.of(emptyList()),
                hasFaceScriptDependency = true,
            ),
        )
    }

    @Test
    fun `慢 tag 图层可以烘`() {
        // 分钟跳一次由 hasPendingSlowTagInvalidation 触发重烘，不需要禁烘。
        for (tag in listOf("dm", "dh", "dd", "bl", "wt")) {
            assertTrue(
                "tag=$tag",
                bakeable(layer("text", mapOf("text" to "{$tag}")), tags = listOf(tag)),
            )
        }
    }

    @Test
    fun `位图字体文字不可烘焙以避免图集替换时出现空帧`() {
        assertFalse(
            bakeable(
                layer("text", mapOf("font" to "bm:f2", "text" to "{dmz}")),
                tags = listOf("dmz"),
            ),
        )
    }

    @Test
    fun `混入一个快 tag 就整层不可烘`() {
        assertFalse(bakeable(layer("text"), tags = listOf("dh", "dm", "dss")))
    }

    @Test
    fun `未知 tag 落 PUSH 桶仍可烘`() {
        // PUSH 不是快桶：取值真变了才重绘，重烘由同一个信号带上。
        assertTrue(bakeable(layer("text"), tags = listOf("totally_unknown_tag")))
    }

    @Test
    fun `group 子节点不可烘`() {
        // 继承变换要逐层复现，烘掉就丢了 —— 在 Flue 里这条正好是一个判空。
        assertFalse(bakeable(layer("image"), item = groupChild()))
    }

    @Test
    fun `group 自身不可烘`() {
        assertFalse(bakeable(layer("group")))
        assertFalse(bakeable(layer("GROUP")))
    }

    @Test
    fun `视频图层不可烘`() {
        // 视频由 TextureView 承载，根本不在 Canvas 上。
        assertFalse(bakeable(layer("video", mapOf("path" to "clip.mp4"))))
    }

    @Test
    fun `带点击热区的图层不可烘`() {
        // 热区是在绘制过程中登记的，烘掉这一层热区就凭空消失。
        assertFalse(bakeable(layer("image", mapOf("tap_action" to "app:com.example"))))
        assertFalse(bakeable(layer("image", mapOf("action" to "open_app"))))
        assertFalse(bakeable(layer("image_tap", mapOf("path" to "btn.png"))))
    }

    @Test
    fun `空白热区属性不算热区`() {
        assertTrue(bakeable(layer("image", mapOf("tap_action" to "   ", "action" to ""))))
    }

    @Test
    fun `陀螺仪视差图层不可烘`() {
        // 低通滤波在传感器停下后还要收敛几十帧，桶模型看不见这段时间来源。
        assertFalse(bakeable(layer("image", mapOf("gyro" to "12"))))
        assertFalse(bakeable(layer("image", mapOf("gyro" to "-8.5"))))
        // 非数值当作启用，与 prepareSource 的 hasGyroParallax 判定一致。
        assertFalse(bakeable(layer("image", mapOf("gyro" to "{dh}"))))
    }

    @Test
    fun `gyro 为零或空白视同关闭`() {
        assertTrue(bakeable(layer("image", mapOf("gyro" to "0"))))
        assertTrue(bakeable(layer("image", mapOf("gyro" to "  "))))
    }

    @Test
    fun `多帧图与 GIF 不可烘`() {
        // 时间来源是渲染器自己的 elapsed，不是 tag。
        assertFalse(bakeable(layer("gif", mapOf("path" to "anim"))))
        assertFalse(bakeable(layer("gallery", mapOf("path" to "shots"))))
        assertFalse(bakeable(layer("image", mapOf("path" to "spin.gif"))))
        assertFalse(bakeable(layer("image", mapOf("path" to "frames`.png"))))
    }

    @Test
    fun `带入场出场动画的文字不可烘`() {
        assertFalse(bakeable(layer("text", mapOf("anim_in" to "fade"))))
        assertFalse(bakeable(layer("text", mapOf("anim_out" to "slide_left"))))
        assertTrue(bakeable(layer("text", mapOf("anim_in" to ""))))
    }
}

class WatchStaticFlattenSegmentTest {

    private fun segments(
        pattern: String,
        videoBoundaries: Set<Int> = emptySet(),
        slowBuckets: List<Set<WatchTagBucket>> = emptyList(),
    ): List<WatchFlattenSegment> = WatchStaticFlatten.planSegments(
        bakeable = pattern.map { it == '1' },
        videoBoundaries = videoBoundaries,
        slowBuckets = slowBuckets,
    )

    private fun ranges(pattern: String, videoBoundaries: Set<Int> = emptySet()) =
        segments(pattern, videoBoundaries).map { it.fromIndex to it.toIndex }

    @Test
    fun `空计划没有段`() {
        assertTrue(segments("").isEmpty())
    }

    @Test
    fun `只合并连续可烘层`() {
        // 计划 §五 第二项。中间夹一个动态层就断开，z 序因此天然保持。
        assertEquals(listOf(0 to 2, 4 to 6), ranges("1110111"))
    }

    @Test
    fun `不足最小层数的区间不烘`() {
        // 一次 drawBitmap 的开销换不回单层，MIN_SEGMENT_LAYERS = 2。
        assertEquals(2, WatchStaticFlatten.MIN_SEGMENT_LAYERS)
        assertTrue(segments("10101").isEmpty())
        assertEquals(listOf(0 to 1), ranges("110"))
    }

    @Test
    fun `全部可烘时成一整段`() {
        assertEquals(listOf(0 to 4), ranges("11111"))
    }

    @Test
    fun `全部不可烘时没有段`() {
        assertTrue(segments("0000").isEmpty())
    }

    @Test
    fun `末尾的可烘区间会收口`() {
        // 循环结束后必须补一次 closeRun，否则尾段丢失。
        assertEquals(listOf(2 to 4), ranges("00111"))
    }

    @Test
    fun `成段不跨视频边界`() {
        // 计划 §五 第三项，也是 §3.4 第 3 点：StageView 已按 video 把 renderPlan
        // 切成多段、每段一个 View 实例，跨界的段会被两个实例各烘一份并互相盖掉。
        assertEquals(listOf(0 to 2, 3 to 5), ranges("111111", videoBoundaries = setOf(3)))
    }

    @Test
    fun `边界切出的碎片不足最小层数就丢掉`() {
        assertEquals(listOf(1 to 4), ranges("11111", videoBoundaries = setOf(1)))
    }

    @Test
    fun `下标 0 的边界不影响首段`() {
        assertEquals(listOf(0 to 3), ranges("1111", videoBoundaries = setOf(0)))
    }

    @Test
    fun `段数上限之外的区间不再烘`() {
        // 段数失控意味着位图内存失控，宁可少烘。
        val pattern = buildString {
            repeat(WatchStaticFlatten.MAX_SEGMENTS + 4) { append("110") }
        }
        assertEquals(WatchStaticFlatten.MAX_SEGMENTS, segments(pattern).size)
    }

    @Test
    fun `段的层数与命中判定`() {
        val segment = segments("0111").single()
        assertEquals(3, segment.layerCount)
        assertFalse(0 in segment)
        assertTrue(1 in segment)
        assertTrue(3 in segment)
        assertFalse(4 in segment)
    }
}

/**
 * 段慢桶并集 —— 按桶重烘的判据。
 *
 * ⚠️ 这一组守的是一个真踩过的坑：调用点漏传 `slowBuckets`，所有段的集合都是空集，
 * 而空集跟任何变化集合都没有交集 —— 按桶回收于是一段都匹配不上，分钟跳变后烘焙
 * 段的像素**永久冻住**。比卡顿严重得多，所以单独一组钉住。
 */
class WatchStaticFlattenSlowBucketTest {

    private fun segments(
        pattern: String,
        slowBuckets: List<Set<WatchTagBucket>>,
    ): List<WatchFlattenSegment> = WatchStaticFlatten.planSegments(
        bakeable = pattern.map { it == '1' },
        slowBuckets = slowBuckets,
    )

    @Test
    fun `段的慢桶是段内各层之并`() {
        val segment = segments(
            pattern = "111",
            slowBuckets = listOf(
                setOf(WatchTagBucket.MINUTE),
                emptySet(),
                setOf(WatchTagBucket.PUSH),
            ),
        ).single()
        assertEquals(setOf(WatchTagBucket.MINUTE, WatchTagBucket.PUSH), segment.slowBuckets)
    }

    @Test
    fun `段外图层的慢桶不算进来`() {
        // 分钟层在段外（下标 3），这一段就不该被分钟跳变触发重烘。
        val segment = segments(
            pattern = "1110",
            slowBuckets = listOf(
                setOf(WatchTagBucket.HOUR),
                emptySet(),
                emptySet(),
                setOf(WatchTagBucket.MINUTE),
            ),
        ).single()
        assertEquals(setOf(WatchTagBucket.HOUR), segment.slowBuckets)
    }

    @Test
    fun `多段各自聚合互不串味`() {
        val result = segments(
            pattern = "11011",
            slowBuckets = listOf(
                setOf(WatchTagBucket.MINUTE),
                setOf(WatchTagBucket.MINUTE),
                setOf(WatchTagBucket.DAY),
                setOf(WatchTagBucket.PUSH),
                setOf(WatchTagBucket.PUSH),
            ),
        )
        assertEquals(2, result.size)
        assertEquals(setOf(WatchTagBucket.MINUTE), result[0].slowBuckets)
        assertEquals(setOf(WatchTagBucket.PUSH), result[1].slowBuckets)
    }

    @Test
    fun `全常量段的慢桶为空集`() {
        // 空集是**正确**结果：这一段在整个 generation 内恒定，任何 tag 变化都不该
        // 让它重烘。它只能由全量失效入口（压暗、主题）整批回收 —— 这也正是全量
        // 必须有独立信号、不能编码成「所有桶都变了」的原因。
        val segment = segments("11", slowBuckets = listOf(emptySet(), emptySet())).single()
        assertTrue(segment.slowBuckets.isEmpty())
    }

    @Test
    fun `不传慢桶时退化成空集`() {
        // 调用方漏传就是这个下场，这里把「漏传 = 空集」显式钉住，配合上面那条
        // 说明：空集的段只能靠全量回收，绝不会被按桶路径碰到。
        val segment = WatchStaticFlatten.planSegments(bakeable = listOf(true, true)).single()
        assertTrue(segment.slowBuckets.isEmpty())
    }
}
