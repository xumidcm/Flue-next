package com.flue.launcher.watchface.editor

import com.flue.launcher.watchface.standard.WatchmakerGeometry

/**
 * 表盘设计画布尺寸。
 *
 * WatchMaker 原生授权空间是 512×512 的正方形（见 [WatchmakerGeometry]），
 * 所有图层坐标都以画布中心为原点。这里把「画布尺寸」提升为每块表盘自己的
 * 声明值，落在 watch.xml 根节点的 width / height 属性上：
 * 缺省时回落 512×512，与历史表盘完全兼容。
 */
data class WatchCanvasSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width in MIN_EDGE..MAX_EDGE) { "画布宽度超出 $MIN_EDGE..$MAX_EDGE" }
        require(height in MIN_EDGE..MAX_EDGE) { "画布高度超出 $MIN_EDGE..$MAX_EDGE" }
    }

    val isSquare: Boolean get() = width == height

    /** 宽 / 高。预览按这个比例等比缩放，不再锁死 1:1。 */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /**
     * 设计空间宽度：基准边恒为 [WatchmakerGeometry.DESIGN_SIZE]，只按宽高比
     * 拉长另一边。
     *
     * 关键语义是「只跟随宽高比、不跟随绝对像素」：声明 466×466 和 360×360 的
     * 比例都是 1.0，设计空间仍是 512×512 —— 第三方方形表盘的图层坐标本来按
     * 512 中心系写，行为零变化。只有真正非方的声明才拉长长边，而那些表盘在
     * 旧实现里本来就被 letterbox 压成方形、超出部分被 clipRect 裁掉。
     */
    val designExtentWidth: Float
        get() = if (aspectRatio >= 1f) {
            WatchmakerGeometry.DESIGN_SIZE * aspectRatio
        } else {
            WatchmakerGeometry.DESIGN_SIZE
        }

    /** 设计空间高度，语义同 [designExtentWidth]。 */
    val designExtentHeight: Float
        get() = if (aspectRatio >= 1f) {
            WatchmakerGeometry.DESIGN_SIZE
        } else {
            WatchmakerGeometry.DESIGN_SIZE / aspectRatio
        }

    val label: String get() = "$width × $height"

    companion object {
        const val MIN_EDGE = 64
        const val MAX_EDGE = 2048

        /** WatchMaker 原生画布，也是缺省值。 */
        val DEFAULT = WatchCanvasSize(
            WatchmakerGeometry.DESIGN_SIZE.toInt(),
            WatchmakerGeometry.DESIGN_SIZE.toInt(),
        )

        /** 越界值钳回合法区间，而不是抛异常——解析外部表盘时要能容错。 */
        fun clamped(width: Int, height: Int): WatchCanvasSize = WatchCanvasSize(
            width = width.coerceIn(MIN_EDGE, MAX_EDGE),
            height = height.coerceIn(MIN_EDGE, MAX_EDGE),
        )

        /**
         * 从根属性里读画布尺寸。width / height 缺失或不是正整数时回落
         * [DEFAULT]，所以老表盘（根节点没有这两个属性）行为不变。
         */
        fun fromRootAttributes(attributes: Map<String, String>): WatchCanvasSize {
            val width = attributes.valueIgnoringCase("width")?.toCanvasEdge()
            val height = attributes.valueIgnoringCase("height")?.toCanvasEdge()
            if (width == null && height == null) return DEFAULT
            // 只声明了一边时按正方形补齐，避免另一边落回 512 造成怪比例。
            val resolvedWidth = width ?: height ?: DEFAULT.width
            val resolvedHeight = height ?: width ?: DEFAULT.height
            return clamped(resolvedWidth, resolvedHeight)
        }

        private fun Map<String, String>.valueIgnoringCase(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /** 允许 "360"、"360px"、"360.0" 这类写法。 */
        private fun String.toCanvasEdge(): Int? {
            val cleaned = trim().removeSuffix("px").trim()
            val value = cleaned.toIntOrNull()
                ?: cleaned.toFloatOrNull()?.takeIf { it.isFinite() }?.toInt()
                ?: return null
            return value.takeIf { it > 0 }
        }
    }
}

/**
 * 画布尺寸预设。
 *
 * [FollowScreen] 是默认项：新建表盘时直接取设备屏幕的物理像素，做出来的
 * 表盘和当前设备 1:1 对应。[Preset] 是表盘市场上的常见规格。
 */
sealed interface WatchCanvasPreset {
    val label: String

    /** 跟随屏幕物理像素。尺寸在调用点用真实 DisplayMetrics 填。 */
    data object FollowScreen : WatchCanvasPreset {
        override val label: String get() = "跟随屏幕尺寸"
    }

    data class Preset(
        val size: WatchCanvasSize,
        val note: String,
    ) : WatchCanvasPreset {
        override val label: String get() = size.label
    }

    data object Custom : WatchCanvasPreset {
        override val label: String get() = "自定义"
    }

    companion object {
        /**
         * 表盘市场常见规格。圆形规格放前面（国内表盘市场以圆屏为主），
         * 方屏 / 长方屏在后，最后是 WatchMaker 原生 512。
         */
        val MARKET_PRESETS: List<Preset> = listOf(
            Preset(WatchCanvasSize(466, 466), "圆屏 AMOLED 常见"),
            Preset(WatchCanvasSize(454, 454), "大圆屏"),
            Preset(WatchCanvasSize(410, 410), "中圆屏"),
            Preset(WatchCanvasSize(360, 360), "Wear OS 圆屏"),
            Preset(WatchCanvasSize(240, 240), "小方屏"),
            Preset(WatchCanvasSize(320, 385), "方屏竖向"),
            Preset(WatchCanvasSize(368, 448), "长方屏"),
            Preset(WatchCanvasSize(512, 512), "WatchMaker 原生"),
        )
    }
}
