package com.flue.launcher.applist.background

/**
 * 应用列表背景配置。
 *
 * 老师要的四档：
 * 1. [AppListBackgroundMode.SOLID_SYSTEM] —— 纯色，黑 / 白跟随系统深浅色（**默认，与改动前逐位相同**）
 * 2. [AppListBackgroundMode.SOLID_CUSTOM] —— 自定义取一个颜色
 * 3. [AppListBackgroundMode.WATCH_FACE] —— 跟随表盘，可调模糊 + 可调遮罩
 * 4. [AppListBackgroundMode.CUSTOM_IMAGE] —— 自定义图片（长按拉起内置文件选择器 → 裁剪），
 *    同样可调模糊 + 遮罩
 *
 * 全部字段是纯数据，映射与夹取都是纯函数（见 `AppListBackgroundConfigTest`）。渲染侧
 * （`AppListBackgroundLayer`）只读这些值，不自己再定第二套默认值。
 */
enum class AppListBackgroundMode {
    SOLID_SYSTEM,
    SOLID_CUSTOM,
    WATCH_FACE,
    CUSTOM_IMAGE,
    ;

    /** 这一档要不要底图（也就是要不要解码 / 模糊 / 遮罩）。 */
    val usesImage: Boolean get() = this == WATCH_FACE || this == CUSTOM_IMAGE
}

/** JSON 结构版本。加字段时 +1，并在 `AppListBackgroundJson` 里写好迁移。 */
const val APP_LIST_BACKGROUND_SCHEMA_VERSION: Int = 1

/** 不透明黑。默认自定义色取它，而不是取 0（0 是全透明黑，会让列表背景直接消失）。 */
const val APP_LIST_BACKGROUND_DEFAULT_COLOR_ARGB: Int = 0xFF000000.toInt()

data class AppListBackgroundConfig(
    val schemaVersion: Int = APP_LIST_BACKGROUND_SCHEMA_VERSION,
    val mode: AppListBackgroundMode = AppListBackgroundMode.SOLID_SYSTEM,
    val customColorArgb: Int = APP_LIST_BACKGROUND_DEFAULT_COLOR_ARGB,
    /** 模糊程度 0..100。仅 [AppListBackgroundMode.usesImage] 的两档有意义。 */
    val blurPercent: Int = DEFAULT_BLUR_PERCENT,
    /** 遮罩强度 0..100。深色主题压黑、浅色主题压白。 */
    val scrimPercent: Int = DEFAULT_SCRIM_PERCENT,
    /** 自定义图片的落盘路径。`null` = 还没选图。 */
    val imagePath: String? = null,
) {
    /**
     * 这份配置**实际**会走哪一档。
     *
     * [AppListBackgroundMode.CUSTOM_IMAGE] 但还没选图（或图被删了）时落回
     * [AppListBackgroundMode.SOLID_SYSTEM] —— 渲染侧就不用各自判一遍「图没了怎么办」，
     * 也绝不会出现透明背景或黑闪。
     */
    val effectiveMode: AppListBackgroundMode
        get() = if (mode == AppListBackgroundMode.CUSTOM_IMAGE && imagePath.isNullOrBlank()) {
            AppListBackgroundMode.SOLID_SYSTEM
        } else {
            mode
        }

    /** 夹取到合法区间而**不抛异常**。读盘与导入都先过这一道。 */
    fun sanitized(): AppListBackgroundConfig = copy(
        schemaVersion = APP_LIST_BACKGROUND_SCHEMA_VERSION,
        blurPercent = blurPercent.coerceIn(MIN_PERCENT, MAX_PERCENT),
        scrimPercent = scrimPercent.coerceIn(MIN_PERCENT, MAX_PERCENT),
        // 透明的自定义底色会让列表背景漏出下面的表盘，那不是「自定义纯色」的语义。
        customColorArgb = customColorArgb or ALPHA_OPAQUE_MASK,
        imagePath = imagePath?.trim()?.takeIf(String::isNotEmpty),
    )

    /** 写盘 / 编码前的自检。夹取交给 [sanitized]，这里只负责「不合法就别偷偷写进去」。 */
    fun requireValid(): AppListBackgroundConfig = apply {
        require(schemaVersion == APP_LIST_BACKGROUND_SCHEMA_VERSION) {
            "Unsupported app list background schema: $schemaVersion"
        }
        require(blurPercent in MIN_PERCENT..MAX_PERCENT) { "blurPercent out of range" }
        require(scrimPercent in MIN_PERCENT..MAX_PERCENT) { "scrimPercent out of range" }
        require(imagePath == null || imagePath.isNotBlank()) { "imagePath must not be blank" }
    }

    companion object {
        const val MIN_PERCENT: Int = 0
        const val MAX_PERCENT: Int = 100

        /**
         * 默认模糊 55%：足够让表盘退成色块、不与图标抢注意力，又还看得出是哪张表盘。
         * 0% 也是合法的（老师要的「可调」包含关掉）。
         */
        const val DEFAULT_BLUR_PERCENT: Int = 55

        /** 默认遮罩 35%：图标文字对比度够，底图仍可辨。 */
        const val DEFAULT_SCRIM_PERCENT: Int = 35

        private const val ALPHA_OPAQUE_MASK: Int = 0xFF shl 24

        val Default: AppListBackgroundConfig = AppListBackgroundConfig()
    }
}

/**
 * 模糊半径上限（dp）。
 *
 * 28dp 对齐 `BlurSupport` 的量化桶顶档（20dp）再留一档余量 —— 全屏背景的模糊要够重，
 * 不然「跟随表盘」看起来只是一张糊了一点的截图。
 */
const val APP_LIST_BACKGROUND_MAX_BLUR_DP: Float = 28f

/** 遮罩不透明度上限。不给到 1f：留一点底图可辨，否则「跟随表盘」与纯色档没有区别。 */
const val APP_LIST_BACKGROUND_MAX_SCRIM_ALPHA: Float = 0.85f

/** 百分比 → 模糊半径（dp）。线性映射，0 就是完全不糊。 */
fun appListBackgroundBlurRadiusDp(percent: Int): Float {
    val clamped = percent.coerceIn(
        AppListBackgroundConfig.MIN_PERCENT,
        AppListBackgroundConfig.MAX_PERCENT,
    )
    return APP_LIST_BACKGROUND_MAX_BLUR_DP * clamped / 100f
}

/** 百分比 → 遮罩不透明度。 */
fun appListBackgroundScrimAlpha(percent: Int): Float {
    val clamped = percent.coerceIn(
        AppListBackgroundConfig.MIN_PERCENT,
        AppListBackgroundConfig.MAX_PERCENT,
    )
    return APP_LIST_BACKGROUND_MAX_SCRIM_ALPHA * clamped / 100f
}

/**
 * 遮罩颜色（ARGB，不含 alpha —— alpha 由 [appListBackgroundScrimAlpha] 给）。
 *
 * 老师定的方向：**深色主题压黑、浅色主题压白**。这一层必须独立画在底图上面、图标下面，
 * 不能混进背景色里 —— 混进去纯色档也会跟着变暗。
 */
fun appListBackgroundScrimRgb(darkMode: Boolean): Int =
    if (darkMode) 0x000000 else 0xFFFFFF

/**
 * 模糊半径的量化桶（dp）。
 *
 * 30 及以下要**预烘焙**（盒模糊算一次、缓存起来），桶越少缓存命中率越高；31+ 的
 * `RenderEffect` 也按同一套桶取缓存好的 effect。两条路径共用一套桶，滑块拖过去时
 * 两边的观感台阶才一致。
 */
fun appListBackgroundBlurBucketDp(percent: Int): Float {
    val radius = appListBackgroundBlurRadiusDp(percent)
    if (radius < 1f) return 0f
    var selected = 0f
    for (bucket in APP_LIST_BACKGROUND_BLUR_BUCKETS_DP) {
        if (bucket > radius) break
        selected = bucket
    }
    return selected
}

private val APP_LIST_BACKGROUND_BLUR_BUCKETS_DP = floatArrayOf(
    0f, 2f, 4f, 6f, 9f, 12f, 16f, 20f, 24f, 28f,
)

/**
 * 30 及以下预烘焙用的盒模糊半径（作用在 1/12 降采样图上）。
 *
 * 老师要的是「模糊程度可以调」，而预烘焙那条路原本是**写死配方**（降采样 /12、3 轮、
 * 半径 6）—— 不接这个映射的话，Android 12 以下那条滑块只有开和关两档。
 *
 * 返回 0 表示「不用烘焙」（百分比太小，糊不出可见差别，白花一次全屏卷积）。
 * 1..8 在 /12 降采样下折算回原图约等于 24..192px 的扩散，覆盖得住 0..28dp 那条量程。
 */
fun appListBackgroundLegacyBlurRadius(percent: Int): Int {
    val bucket = appListBackgroundBlurBucketDp(percent)
    if (bucket <= 0f) return 0
    val fraction = (bucket / APP_LIST_BACKGROUND_MAX_BLUR_DP).coerceIn(0f, 1f)
    return (1 + (fraction * (LEGACY_BLUR_MAX_RADIUS - 1))).toInt()
        .coerceIn(1, LEGACY_BLUR_MAX_RADIUS)
}

private const val LEGACY_BLUR_MAX_RADIUS = 8
