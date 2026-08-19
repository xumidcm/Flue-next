package com.flue.launcher.watchface.store

/**
 * H2 — 头像上传前的本地约束（纯函数，可单测）。
 *
 * 上游 `my/profile/avatar` 按 **magic bytes** 判类型（PNG / JPEG / WebP），限 **2MB**。
 * 客户端这一侧只做两件能在本地做对的事：
 *
 * 1. **降分辨率**：头像最大展示尺寸是列表里的一个小圆，传一张 4000×3000 上去只是浪费流量与
 *    服务端磁盘。按最长边 [MAX_EDGE] 重采样。
 * 2. **控体积**：编码完超过 2MB 就降一档质量重编一次（[QUALITY_LADDER]），仍超才报错 ——
 *    直接把「超 2MB」当选图错误弹给用户是不合理的，512 见方的图降一档质量几乎必定进得去。
 *
 * **类型不在本地猜**：上游看 magic bytes，客户端按扩展名/MIME 猜错会拦下合法文件
 * （改过后缀的 PNG、非 `image/` MIME 的 provider）。所以这里只管尺寸与体积。
 */
object WatchStoreAvatarRules {

    /** 重采样后的最长边。头像最大用在详情页作者行，512 已经绰绰有余。 */
    const val MAX_EDGE: Int = 512

    /** 上游硬限制。与 `HttpWatchStoreApi` 的预检是同一个数。 */
    const val MAX_BYTES: Long = 2L * 1024L * 1024L

    /**
     * 质量阶梯：先按第一档编码，超限降到第二档再编一次。
     *
     * 只两档是刻意的 —— 每一档都要重新编码一遍位图，手表上多试几次的收益远不如
     * 「第一档就够小」。90 对 512 见方的 WebP 通常是 60–120 KB，离 2MB 差两个数量级；
     * 第二档留给极端情况（噪点大图 + 无损通道）。
     */
    val QUALITY_LADDER: List<Int> = listOf(90, 70)

    /**
     * 按最长边 [maxEdge] 等比缩放后的像素尺寸。
     *
     * **不放大**：源图比 512 小就原样返回。放大只会让文件变大、清晰度一点不涨。
     * 结果每边至少 1px（极端细长图取整后不能变成 0）。
     */
    fun fitWithinMaxEdge(width: Int, height: Int, maxEdge: Int = MAX_EDGE): AvatarPixelSize {
        require(width > 0 && height > 0) { "头像源尺寸必须为正" }
        require(maxEdge > 0) { "最长边必须为正" }
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return AvatarPixelSize(width, height)
        val scale = maxEdge.toDouble() / longest
        return AvatarPixelSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1),
        )
    }

    /** 编码结果能不能上传。0 字节也算不合格（编码失败会给出空文件）。 */
    fun withinSizeLimit(byteCount: Long): Boolean = byteCount in 1..MAX_BYTES
}

/** [WatchStoreAvatarRules.fitWithinMaxEdge] 的结果。 */
data class AvatarPixelSize(val width: Int, val height: Int)
