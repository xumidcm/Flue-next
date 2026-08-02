package com.flue.launcher.watchface.store

/**
 * HTTP Range 分段的一段：[start, endInclusive] 闭区间。
 *
 * `index` 只是分段编号，方便日志和线程命名；计算没用它。
 */
internal data class SegmentRange(
    val index: Int,
    val start: Long,
    val endInclusive: Long,
)
