package com.flue.launcher.iconpack

/**
 * `iconmask` 的极性。
 *
 * 图标包生态里 `iconmask` 有两套相反的约定，而 appfilter 里没有任何字段说明用的是哪套：
 *
 *  * [KEEP_INSIDE]：形状处不透明、周围透明 —— 「不透明的地方保留图标」，配 `DST_IN`。
 *    这是 ADW / Nova 文档写的那一套。
 *  * [KEEP_OUTSIDE]：形状处**透明**、周围不透明 —— mask 标的是「要挖掉的地方」，配
 *    `DST_OUT`。ColorOS 那类包就是这样：`iconmask1.png` 是白色圆角方压在**不透明黑**上。
 *
 * 按错的那套处理，结果不是「形状不对」而是**图标整个消失**：用 `DST_IN` 处理反相 mask
 * 会把图标本体擦干净，只剩周围那圈深色 —— 也就是老师报的「图标包未包含的应用显示为
 * 黑色空壳」。适配过的应用不受影响，因为它们走的是包里现成的图片，压根不进模板合成。
 */
enum class IconPackMaskPolarity {
    KEEP_INSIDE,
    KEEP_OUTSIDE,

    /** 整张不透明或整张透明：给不出形状信息，只能不遮。 */
    UNUSABLE,
}

/**
 * 用「中心 alpha vs 四角 alpha」判极性。
 *
 * 判据只需要这两个采样点：两套约定的差别正好体现在中心与角落的对比上。要求两者差值
 * 超过 [MIN_ALPHA_CONTRAST] 才认，否则当 [IconPackMaskPolarity.UNUSABLE] —— 一张几乎
 * 均匀的图既不能当保留遮罩也不能当挖除遮罩，硬按一套用就是在猜。
 */
fun iconPackMaskPolarity(centerAlpha: Int, cornerAlpha: Int): IconPackMaskPolarity {
    val center = centerAlpha.coerceIn(0, 255)
    val corner = cornerAlpha.coerceIn(0, 255)
    return when {
        center - corner >= MIN_ALPHA_CONTRAST -> IconPackMaskPolarity.KEEP_INSIDE
        corner - center >= MIN_ALPHA_CONTRAST -> IconPackMaskPolarity.KEEP_OUTSIDE
        else -> IconPackMaskPolarity.UNUSABLE
    }
}

private const val MIN_ALPHA_CONTRAST = 64
