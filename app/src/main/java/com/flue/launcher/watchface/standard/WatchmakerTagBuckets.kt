package com.flue.launcher.watchface.standard

import java.util.Locale

/**
 * 阶段 2：tag 分桶 + 推模型（`plan-Claude/watchface-precompile-and-canvas.md` §三）。
 *
 * 对照 `wf_engine.lua`：clox 把每个 tag 归到一个更新频率桶里，tag 重算后**值真的
 * 变了**才把绑定它的图层推成脏。Flue 是 Canvas 直绘的拉模型，没法只重画一个图层
 * —— 所以这里落的不是「部分重绘」，而是**判断这一拍到底要不要重绘**：
 *
 * - 快桶（[WatchTagBucket.FRAME]/[WatchTagBucket.SECOND]/[WatchTagBucket.SENSOR]）
 *   照旧每拍重绘，行为与改动前逐帧一致。
 * - 只依赖慢桶的表盘，1s 一拍先把 tag 全部重算一遍，**一个都没变就不 invalidate**。
 *   一张最快依赖到分钟的表盘，改动前每分钟要走 60 遍全部图层，改动后只走 1 遍。
 *
 * 分桶的第二个用途是给阶段 3 的可烘判定当输入：命中快桶的图层一律不可烘。
 *
 * ⚠️ 桶只影响「要不要重绘」与「能不能烘」，**不影响正确性**：慢桶那条路仍然逐拍
 * 比对真实取值，分错桶最多是白重绘一次或少省一点性能，不会让图层冻住。
 */
enum class WatchTagBucket {
    /** 毫秒级：`dss` `drss` `drms` `drm` `dtp`、以及 `c_*` 计数器。 */
    FRAME,
    SECOND,
    MINUTE,
    HOUR,
    DAY,

    /** 外部事件驱动：电量、天气、步数、心率、通知、Wi-Fi。 */
    PUSH,

    /** 陀螺仪。⚠️ 见 §2.3：低通滤波在传感器停下后还要收敛几十帧。 */
    SENSOR,

    /** 一个 generation 内恒定，只有全量重绘入口能改（主题色等）。 */
    STATIC,
    ;

    /**
     * 需要 33ms 拍子、且**永远不可烘**的桶。
     *
     * [SECOND] 也算快：它每秒必变，压根没有「值没变可以跳过」的余地。
     */
    val isFast: Boolean
        get() = this == FRAME || this == SECOND || this == SENSOR
}

/**
 * 单个 tag 名 → 桶。
 *
 * 归类依据是 [WatchmakerTagResolver.resolve] **真正读了哪个日历字段**，不是 tag
 * 名字看起来像什么。计划 §2.1 的表格里有三处与实现不符，这里以实现为准并标注：
 * `drm`（读了 `MILLISECOND`，是每帧量）、`drh`（读了 `SECOND`）、
 * `dtp`（读了 `MILLISECOND`）。按名字归类会把它们放进 MINUTE/HOUR/DAY，
 * 表针就会一秒一跳。
 */
object WatchTagBuckets {

    private val FRAME_TAGS = setOf(
        // 毫秒直读。
        "dss", "dssz", "dsps",
        // 平滑表针：分数部分来自毫秒。
        "drss", "drms", "drm",
        // 一日进度：分母 86400000。
        "dtp",
    )

    private val SECOND_TAGS = setOf(
        "ds", "dsz", "drs",
        // {drh} = (时 + 分/60 + 秒/3600) * 30，秒级平滑。
        "drh",
    )

    private val MINUTE_TAGS = setOf("dm", "dmz", "drm0", "drmh24")

    private val HOUR_TAGS = setOf(
        "dh", "dhz", "dh11", "dh11z", "dh12", "dh12z",
        "dh23", "dh24", "dh23z", "dh24z",
        "da", "dampm", "drh0", "drh24",
    )

    private val DAY_TAGS = setOf(
        "dd", "ddz", "ddw0", "ddw", "ddw1", "ddw2", "ddww",
        "dn", "dnn", "dnnn", "dnnnn", "dy", "dyy", "dyyyy",
        // {dw} 周序号、{ddim} 本月天数、{wmp} 月相序号：都是纯日期函数，
        // 一天最多变一次。{wmp} 虽然名字像天气，实现是本地天文算法（见
        // WatchmakerTagResolver.moonPhaseIndex），不随天气推送变化。
        "dw", "ddim", "wmp",
        // 时区缩写会随夏令时切换而变，跟着日期桶走。
        "dz", "dzz",
    )

    private val SENSOR_TAGS = setOf("sgx", "sgy", "sgz")

    private val STATIC_TAGS = setOf("ucolor")

    /**
     * `{ddw1_3}` 之类「N 天后的星期名」家族，与 [WatchTagBucket.DAY] 同频。
     *
     * 与 `WatchmakerTagResolver.futureWeekdayTagParts` 的正则保持一致；那边是
     * 解析取值，这里只判归桶，分歧的后果仅是多比对一次（落 PUSH），不影响正确性。
     */
    private val FUTURE_WEEKDAY_PATTERN = Regex("^(?:ddw|ddww|ddw1|ddw2)_\\d+$")

    /**
     * 归桶。未知 tag 一律进 [WatchTagBucket.PUSH]（**不是** STATIC）：
     * 桶从严，宁可每拍都比对一次取值，也不能把没认出来的 tag 当成恒定值烘死。
     */
    fun of(rawTag: String): WatchTagBucket {
        val tag = normalize(rawTag)
        if (tag.isEmpty()) return WatchTagBucket.STATIC
        // 载入相对的计数器动画，时间来源是渲染器自己的 elapsed。
        if (isWatchCounterTag(tag)) return WatchTagBucket.FRAME
        // {utc_dm} / {dmutc} 与 {dm} 的更新频率相同，只差时区。
        val base = stripUtc(tag)
        return when {
            base in FRAME_TAGS -> WatchTagBucket.FRAME
            base in SECOND_TAGS -> WatchTagBucket.SECOND
            base in MINUTE_TAGS -> WatchTagBucket.MINUTE
            base in HOUR_TAGS -> WatchTagBucket.HOUR
            base in DAY_TAGS -> WatchTagBucket.DAY
            FUTURE_WEEKDAY_PATTERN.matches(base) -> WatchTagBucket.DAY
            base in SENSOR_TAGS -> WatchTagBucket.SENSOR
            base in STATIC_TAGS -> WatchTagBucket.STATIC
            else -> WatchTagBucket.PUSH
        }
    }

    fun normalize(rawTag: String): String =
        rawTag.trim().trim('{', '}').trim().lowercase(Locale.ROOT)

    private fun stripUtc(tag: String): String {
        // WM_TAGS.md 的规范写法，与 WatchmakerTagResolver.utcBaseTag 对齐。
        when (tag) {
            "dhutc12" -> return "dh"
            "dhutc12z" -> return "dhz"
            "dhutc24" -> return "dh23"
            "dhutc24z" -> return "dh23z"
        }
        return when {
            tag.startsWith("utc_") -> tag.removePrefix("utc_")
            tag.startsWith("utc") -> tag.removePrefix("utc")
            tag.endsWith("_utc") -> tag.removeSuffix("_utc")
            tag.endsWith("utc") -> tag.removeSuffix("utc")
            else -> tag
        }
    }
}
