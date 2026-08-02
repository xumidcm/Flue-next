package com.flue.launcher.watchface.standard

/**
 * 一个 renderPlan 项（图层）的 tag 依赖摘要。
 *
 * 由阶段 1 的 [WatchExpressionCompileReport.tagDependencies] 汇总而来，同时是
 * 阶段 3 可烘判定的输入之一。⚠️ 它只覆盖 **tag 驱动**的依赖：入场出场动画、
 * GIF 帧序列、陀螺仪视差的时间来源都不是 tag（见计划 §2.3），必须由调用方
 * 用 [watchLayerRequiresFrameTicks] 之类的谓词单独并进来。
 */
data class WatchLayerTagProfile(
    val tags: Set<String> = emptySet(),
    val buckets: Set<WatchTagBucket> = emptySet(),
) {
    /** 命中快桶 → 每拍都要重绘，且永远不可烘。 */
    val hasFastDependency: Boolean
        get() = buckets.any { it.isFast }

    /** 一个 generation 内恒定：没有 tag，或全部落在 STATIC。 */
    val isConstantWithinGeneration: Boolean
        get() = buckets.isEmpty() || buckets.all { it == WatchTagBucket.STATIC }

    /** 重烘触发用的慢依赖：去掉快桶与 STATIC 之后剩下的桶。 */
    val slowBuckets: Set<WatchTagBucket>
        get() = buckets.filterNotTo(LinkedHashSet()) { it.isFast || it == WatchTagBucket.STATIC }

    companion object {
        val EMPTY = WatchLayerTagProfile()

        fun of(tags: Collection<String>): WatchLayerTagProfile {
            if (tags.isEmpty()) return EMPTY
            val normalized = LinkedHashSet<String>(tags.size)
            val buckets = LinkedHashSet<WatchTagBucket>()
            for (tag in tags) {
                val name = WatchTagBuckets.normalize(tag)
                if (name.isEmpty()) continue
                normalized += name
                buckets += WatchTagBuckets.of(name)
            }
            if (normalized.isEmpty()) return EMPTY
            return WatchLayerTagProfile(tags = normalized, buckets = buckets)
        }
    }
}

/**
 * tag 取值变化追踪器 —— 阶段 2 推模型在 Flue 拉模型里的落点。
 *
 * clox 的 `wf_engine.lua` 能把脏标记推给单个图层；Flue 是 Canvas 直绘，一次
 * `invalidate()` 就是整张重画，所以这里回答的问题降级成一个布尔值：
 * **这一拍到底有没有必要重绘**。
 *
 * 只依赖慢桶的表盘因此从「每分钟 60 次全量重绘」降到「每分钟 1 次」。带快桶的
 * 表盘走 [requiresRedrawEveryTick]，行为与改动前逐帧一致。
 *
 * ⚠️ **`wf_engine.lua:122` 的坑照抄**：[poll] 必须把所有 tag 重算完再返回，
 * 中途发现变化**不能提前 break**。提前退出会让后半截 tag 的缓存值停留在上一拍，
 * 下一拍再比对时它们凭空「变了」一次 —— clox 踩过的「闹钟小时更新了分钟没更新」
 * 就是这个。
 */
class WatchTagChangeTracker(
    tags: Collection<String>,
    /** 计划 §2.3 的两个非 tag 快依赖（陀螺仪 / 入场动画 / GIF）由外部并进来。 */
    private val forceFastTicks: Boolean = false,
) {
    /** 逐拍比对的 tag。快桶 tag 不进这里：它们每拍必变，比对纯属浪费。 */
    private val watchedTags: Array<String>

    /** 与 [watchedTags] 同序的上一拍取值。`null` 表示解析失败或不可用。 */
    private val lastValues: Array<String?>

    private val bucketsPresent: Set<WatchTagBucket>

    /** 首拍（以及每次 [invalidateAll] 之后）必须重绘一次，不能拿空缓存去比。 */
    private var pristine: Boolean = true

    init {
        val slow = LinkedHashSet<String>()
        val buckets = LinkedHashSet<WatchTagBucket>()
        for (tag in tags) {
            val name = WatchTagBuckets.normalize(tag)
            if (name.isEmpty()) continue
            val bucket = WatchTagBuckets.of(name)
            buckets += bucket
            if (!bucket.isFast && bucket != WatchTagBucket.STATIC) slow += name
        }
        watchedTags = slow.toTypedArray()
        lastValues = arrayOfNulls(watchedTags.size)
        bucketsPresent = buckets
    }

    /** 命中快桶（或被外部标记为快依赖）→ 每拍无条件重绘。 */
    val requiresRedrawEveryTick: Boolean
        get() = forceFastTicks || bucketsPresent.any { it.isFast }

    /** 这张表盘出现过的桶，供诊断与阶段 3 使用。 */
    val buckets: Set<WatchTagBucket>
        get() = bucketsPresent

    /** 参与逐拍比对的 tag 数量（诊断用）。 */
    val watchedTagCount: Int
        get() = watchedTags.size

    /**
     * 最近一次 [poll] 里慢桶 tag 是否真的变了。
     *
     * 与 [poll] 的返回值**不是**一回事：快桶表盘每拍都要重绘（返回 true），但它的
     * 慢桶 tag 可能几分钟才动一次。阶段 3 的烘焙位图只关心后者 —— 段内图层按定义
     * 不含快依赖，秒针跳动不该让整段背景重烘。
     */
    var slowTagsChangedOnLastPoll: Boolean = true
        private set

    /**
     * 重算全部慢桶 tag 并比对。返回 true 表示这一拍需要重绘。
     *
     * @param resolve 取 tag 当前的渲染值；抛异常视同 `null`（不可用），不会中断整轮。
     */
    fun poll(resolve: (String) -> String?): Boolean {
        var changed = false
        for (index in watchedTags.indices) {
            val value = runCatching { resolve(watchedTags[index]) }.getOrNull()
            // 不能 break：见类注释里 wf_engine.lua:122 的坑。
            if (value != lastValues[index]) changed = true
            lastValues[index] = value
        }
        // 首拍（以及 invalidateAll 之后）一律当作"变了"：缓存是空的，没有基准可比。
        val firstPoll = pristine
        pristine = false
        slowTagsChangedOnLastPoll = changed || firstPoll
        return requiresRedrawEveryTick || changed || firstPoll
    }

    /**
     * 全量重绘入口（对照 `wf_engine.lua:337` `repaint`）。
     *
     * 压暗切换、主题变化、编辑器改属性、表盘切换都要走这里。**漏推比慢更糟** ——
     * 图层会永久卡住不更新，所以这个兜底不是可选项。
     */
    fun invalidateAll() {
        pristine = true
        slowTagsChangedOnLastPoll = true
        lastValues.fill(null)
    }
}
