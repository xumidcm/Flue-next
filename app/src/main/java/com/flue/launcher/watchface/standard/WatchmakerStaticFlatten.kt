package com.flue.launcher.watchface.standard

import java.util.Locale

/**
 * 阶段 3：静态图层烘焙（`plan-Claude/watchface-precompile-and-canvas.md` §三·阶段 3）。
 *
 * 对照 `wf_render.lua:3196` `flattenStatics()`：把**连续**的、每帧都不变的图层合成
 * 到一张离屏位图上，绘制时用一次 `drawBitmap` 代替走完整路径的 N 层。老师看到
 * clox「导入后剩 100 多层」就是这一步的结果。
 *
 * Flue 用 Bitmap + 同步 Canvas 代替 clox 的 RenderTexture，省掉了 GL 那套
 * end-of-frame flush 的安全契约（`wf_render.lua:3155`），但要自己管位图生命周期。
 */
internal object WatchStaticFlatten {

    /** 少于这个层数不值得烘：一次 drawBitmap 的开销换不回来（对齐 `FLATTEN_MIN`）。 */
    const val MIN_SEGMENT_LAYERS = 2

    /**
     * 一次烘焙的最大段数。段数失控意味着位图内存失控，宁可少烘。
     */
    const val MAX_SEGMENTS = 16

    /**
     * 判定单个图层能否进烘焙段。**从严**：不确定就返回 false。
     *
     * 漏判（把动态层当静态烘掉）的代价是图层永久冻住，用户可见的 bug；
     * 误判（把静态层当动态）只是少省一点性能。两者不对等，所以全部往严的一侧倒。
     */
    fun isBakeable(
        layer: WatchmakerLayer,
        item: WatchLayerRenderItem,
        profile: WatchLayerTagProfile,
        hasFaceScriptDependency: Boolean = false,
    ): Boolean {
        // 1. 不是舞台直接子节点 —— group 的继承变换要逐层复现，烘掉就丢了。
        //    clox 的第一条不可烘条件在 Flue 这里正好是一个判空。
        if (item.ancestorChain != null) return false
        // 2. group 本身不进 renderPlan，这里再挡一次以防调用方直接喂 layers。
        if (layer.isGroup) return false
        val type = layer.type.trim().lowercase(Locale.ROOT)
        val attrs = layer.attributes
        // 3. 视频图层由 TextureView 承载，根本不在 Canvas 上。
        if (type == "video") return false
        // Script globals/functions can mutate on every on_millisecond or tap.
        // They are not ordinary {tag} dependencies, so the tag profile cannot
        // see them; baking such a layer would freeze Nexus-style particles.
        if (hasFaceScriptDependency) return false
        // 4. 点击热区是**在绘制过程中**登记的（drawRenderItem → recordTapTarget），
        //    烘掉这一层就再也不会登记，热区凭空消失。
        if (!attrs["tap_action"].isNullOrBlank() || !attrs["action"].isNullOrBlank()) return false
        if (type == "image_tap") return false
        // 5. 依赖快 tag（FRAME/SECOND/SENSOR 桶）。
        if (profile.hasFastDependency) return false
        // 6. 入场/出场动画与多帧图 / GIF / gallery：时间来源是渲染器自己的 elapsed，
        //    桶模型看不见它们（计划 §2.3 第 2 点）。
        if (watchLayerRequiresFrameTicks(type, attrs)) return false
        // 7. 陀螺仪视差：低通滤波在传感器停止后还要收敛几十帧（计划 §2.3 第 1 点）。
        if (hasGyroParallax(attrs)) return false
        return true
    }

    private fun hasGyroParallax(attributes: Map<String, String>): Boolean {
        val raw = attributes["gyro"]?.trim()?.takeIf(String::isNotEmpty) ?: return false
        // 与 prepareSource 里的 hasGyroParallax 判定保持一致：非数值当作启用。
        return raw.toFloatOrNull()?.let { it != 0f } ?: true
    }

    /**
     * 把 renderPlan 切成「烘焙段」与「逐层绘制」的交替序列。
     *
     * 只合并**连续**的可烘图层（`wf_render.lua:3212`），中间夹一个动态层就断开 ——
     * z 序因此天然保持，不需要额外排序。
     *
     * @param bakeable 下标与 [WatchLayerRenderItem] 在 renderPlan 里的位置一一对应。
     * @param videoBoundaries 视频分段的起始下标集合。⚠️ 见 §3.4 第 3 点：
     *   `WatchmakerWatchFaceStageView` 已按 video 图层把 renderPlan 切成多段，每段
     *   一个 View 实例。烘焙段跨越边界会让两个实例各烘一份重叠内容并互相盖掉。
     */
    fun planSegments(
        bakeable: List<Boolean>,
        videoBoundaries: Set<Int> = emptySet(),
        slowBuckets: List<Set<WatchTagBucket>> = emptyList(),
    ): List<WatchFlattenSegment> {
        if (bakeable.isEmpty()) return emptyList()
        val segments = ArrayList<WatchFlattenSegment>()
        var runStart = -1

        fun closeRun(endExclusive: Int) {
            if (runStart < 0) return
            val length = endExclusive - runStart
            if (length >= MIN_SEGMENT_LAYERS && segments.size < MAX_SEGMENTS) {
                // 段的慢桶 = 段内各层慢桶之并（对照 `wf_render.lua:3192` 的
                // `runSlowDeps(run, fast)`：那里也是**逐段**绑定 requestBake）。
                // 空集合意味着这一段在整个 generation 内恒定，任何 tag 变化都
                // 不该让它重烘。
                val buckets = LinkedHashSet<WatchTagBucket>()
                for (index in runStart until endExclusive) {
                    slowBuckets.getOrNull(index)?.let(buckets::addAll)
                }
                segments += WatchFlattenSegment(runStart, endExclusive - 1, buckets)
            }
            runStart = -1
        }

        for (index in bakeable.indices) {
            // 边界处必须先收口，段不能跨越视频分段。
            if (index in videoBoundaries) closeRun(index)
            if (bakeable[index]) {
                if (runStart < 0) runStart = index
            } else {
                closeRun(index)
            }
        }
        closeRun(bakeable.size)
        return segments
    }
}

/**
 * 一个连续可烘区间，闭区间，下标对齐 renderPlan。
 *
 * [slowBuckets] 是段内各层慢依赖桶的并集，**重烘的判据就是它** —— 只有变化的桶
 * 命中这个集合，这一段才需要重建。312 有 16 段，一次分钟跳变通常只碰一两段；
 * 原来用一个全局布尔，任何慢桶变化都把 16 段整批回收，正是老师报的卡顿来源。
 */
internal data class WatchFlattenSegment(
    val fromIndex: Int,
    val toIndex: Int,
    val slowBuckets: Set<WatchTagBucket> = emptySet(),
) {
    val layerCount: Int
        get() = toIndex - fromIndex + 1

    operator fun contains(planIndex: Int): Boolean =
        planIndex in fromIndex..toIndex
}
