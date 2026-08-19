package com.flue.launcher.applist.nativeview

/**
 * 按压 / folder hover 高亮描什么形状。
 *
 * 高亮的作用是「告诉用户按到的是哪一块」，所以它必须描**这一格里真实存在的那个实体**。
 * 格子里有底板时实体是底板，没有底板时实体只有图标自己。描错了就会出现两种难看的情况：
 *
 *  * 该描图标却描了整格 —— 在一片空白上盖一块圆角方（老师报的「网格底板关掉之后，
 *    按压高亮仍是整格圆角矩形」）；
 *  * 该描整格却描了图标 —— 有底板的行按下去只亮一小块，像没按中。
 */
enum class NativePressOverlayShape {
    /** 描整格圆角方，和行底板同形。 */
    ROW_ROUNDED_RECT,

    /**
     * 描图标自己的裁剪路径。
     *
     * 具体是圆还是圆角方由 `effectiveIconShape()` 决定 —— 蜂窝 / 心流 / 星环在那里
     * 已经被强制成圆（格子只比圆图标略宽，方角会顶到邻格），所以这里不必再判一次形状。
     */
    ICON_SHAPE,
}

/**
 * @param rowBackgroundVisible 行底板是否真的会被画出来。判据是底板颜色的 alpha 非 0 ——
 *   和 `NativeAppListRowView.onDraw` 决定要不要画底板用的是**同一个**依据。不另加布尔
 *   字段的原因是那样就有两个真相来源：开关说「关了」而 alpha 还非 0（或反过来）时，
 *   底板和它的高亮就会对不上。
 */
fun nativePressOverlayShape(
    presentation: NativeAppListPresentation,
    iconShape: NativeAppListIconShape,
    rowBackgroundVisible: Boolean,
): NativePressOverlayShape = when (presentation) {
    // 这三种的格子只比圆图标略宽，整格高亮会糊满一格、盖住格间空隙。
    NativeAppListPresentation.HONEYCOMB,
    NativeAppListPresentation.FLOW,
    NativeAppListPresentation.RING -> NativePressOverlayShape.ICON_SHAPE

    // 密网格的格子明显比图标大，有底板时整格高亮才跟底板对齐；底板被关掉之后
    // 格子里就只剩那个圆图标，此时整格高亮没有对应的实体可描。
    NativeAppListPresentation.DENSE_GRID -> if (rowBackgroundVisible) {
        NativePressOverlayShape.ROW_ROUNDED_RECT
    } else {
        NativePressOverlayShape.ICON_SHAPE
    }

    // 线性布局整行都是可点区域：圆图标那档（watchOS 风格）高亮图标圆盘，
    // 方图标那档高亮整行，与各自的观感一致。
    NativeAppListPresentation.LINEAR -> if (iconShape == NativeAppListIconShape.CIRCLE) {
        NativePressOverlayShape.ICON_SHAPE
    } else {
        NativePressOverlayShape.ROW_ROUNDED_RECT
    }
}
