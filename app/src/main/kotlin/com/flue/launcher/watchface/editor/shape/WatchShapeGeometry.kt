package com.flue.launcher.watchface.editor.shape

import android.graphics.Path
import android.graphics.RectF
import java.util.Locale

/**
 * WatchMaker `shape` / `rounded` 图层的几何真源。
 *
 * ## 为什么单独一个文件
 *
 * 同一套形状要喂三处：渲染器（`WatchmakerWatchFaceView.drawShape` 的本体 / 描边 / 阴影
 * 三条通道）、编辑器的命中框（`WatchEditorCanvasGeometry`）、以及专业模式属性面板的
 * 取值范围钳制。以前只有渲染器里那份 `when`，而且只认 circle / triangle，其余四种
 * （Star / Pentagon / Hexagon / Heart）**全部落到 `drawRect` 画成矩形**。
 *
 * ## 顶点从哪来
 *
 * `WM_FORMAT_1.0.md:383-389` 只规定了 7 个合法枚举值，没给顶点坐标 —— 规范里没有，
 * clox 的 `wf_render.lua` 也只画矩形和圆。**目前唯一有全部 7 种实现的是 wftools 的
 * 预览器**（`ui/editor/components/WmPreview.kt:1797-1836`），所以顶点按它对齐，
 * 归一化到 0..1 记在 [SHAPE_POLYGONS] / [buildHeartPath] 里。
 *
 * ## 坐标系
 *
 * 渲染器在画图层时 canvas 已经吃过该图层的矩阵，原点就是图层中心，所以这里生成的路径
 * 一律**以原点为中心**（`-w/2..w/2` × `-h/2..h/2`），而不是 wftools 那样的 `0..w`。
 * 阴影通道需要偏移拷贝，所以留了 [dx] / [dy] 入参 —— 直接加进顶点坐标，省掉每份拷贝
 * 一次 save/restore，也让偏移天然是图层局部的（会跟着图层旋转）。
 */
enum class WatchShapeKind {
    SQUARE,
    CIRCLE,
    TRIANGLE,
    PENTAGON,
    HEXAGON,
    STAR,
    HEART,
}

/**
 * 把 `shape` 属性值解析成枚举。
 *
 * 用 `contains` 而不是相等：老表盘里写过 `Square/Rectangle`、`Circle (Ellipse)`
 * 这类带后缀的值，渲染器原来那份 `when` 也是 `contains`，行为要保持一致。
 * 顺序有讲究 —— `rectangle` 必须在 `angle`(triangle) 之前判掉，否则
 * "Square/Rectangle" 会被 `triangle` 的子串匹配抢走。
 */
fun watchShapeKindOf(raw: String?): WatchShapeKind {
    val value = raw.orEmpty().trim().lowercase(Locale.ROOT)
    return when {
        value.isEmpty() -> WatchShapeKind.SQUARE
        value.contains("circle") || value.contains("ellipse") || value.contains("oval") ->
            WatchShapeKind.CIRCLE
        value.contains("pentagon") -> WatchShapeKind.PENTAGON
        value.contains("hexagon") -> WatchShapeKind.HEXAGON
        value.contains("heart") -> WatchShapeKind.HEART
        value.contains("star") -> WatchShapeKind.STAR
        value.contains("triangle") -> WatchShapeKind.TRIANGLE
        else -> WatchShapeKind.SQUARE
    }
}

/** 规范里 `shape` 的 7 个合法值，**大小写照抄规范**（见 [watchShapeAttributeValue]）。 */
val WATCH_SHAPE_ATTRIBUTE_VALUES: List<String> = listOf(
    "Square",
    "Circle",
    "Triangle",
    "Pentagon",
    "Hexagon",
    "Star",
    "Heart",
)

/**
 * 枚举 → 写进 watch.xml 的字面值。
 *
 * **必须保持规范的首字母大写**：Flue 自己解析时会 lowercase，写小写也画得出来，但
 * 别的渲染器按 `shape_<原值>.png` 之类查表就查不到，整层不显示。
 * 同一个坑在 `WatchEditorActivity.kt:6322-6326` 的注释里记过一次。
 */
fun watchShapeAttributeValue(kind: WatchShapeKind): String = when (kind) {
    WatchShapeKind.SQUARE -> "Square"
    WatchShapeKind.CIRCLE -> "Circle"
    WatchShapeKind.TRIANGLE -> "Triangle"
    WatchShapeKind.PENTAGON -> "Pentagon"
    WatchShapeKind.HEXAGON -> "Hexagon"
    WatchShapeKind.STAR -> "Star"
    WatchShapeKind.HEART -> "Heart"
}

/**
 * 归一化顶点表（x, y ∈ 0..1，y 向下）。圆形与心形不是多边形，不在表里。
 *
 * 顶点与 wftools 预览器逐个对齐，包括 Star 那几个不对称的坐标
 * （0.3 / 0.35 与 0.65 / 0.7）—— 那不是笔误，是它那颗星的实际形状，
 * 改成对称会让同一张表盘在两边长得不一样。
 */
private val SHAPE_POLYGONS: Map<WatchShapeKind, FloatArray> = mapOf(
    WatchShapeKind.SQUARE to floatArrayOf(
        0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f,
    ),
    WatchShapeKind.TRIANGLE to floatArrayOf(
        0.5f, 0f, 1f, 1f, 0f, 1f,
    ),
    WatchShapeKind.PENTAGON to floatArrayOf(
        0.5f, 0f, 1f, 0.4f, 0.8f, 1f, 0.2f, 1f, 0f, 0.4f,
    ),
    WatchShapeKind.HEXAGON to floatArrayOf(
        0.5f, 0f, 1f, 0.25f, 1f, 0.75f, 0.5f, 1f, 0f, 0.75f, 0f, 0.25f,
    ),
    WatchShapeKind.STAR to floatArrayOf(
        0.5f, 0f,
        0.65f, 0.35f,
        1f, 0.35f,
        0.7f, 0.6f,
        0.85f, 1f,
        0.5f, 0.75f,
        0.15f, 1f,
        0.3f, 0.6f,
        0f, 0.35f,
        0.35f, 0.35f,
    ),
)

/** 该形状能不能直接用 `drawOval` / `drawRect` 快路径画（省一次 Path 构建）。 */
fun watchShapeUsesFastPath(kind: WatchShapeKind): Boolean =
    kind == WatchShapeKind.CIRCLE || kind == WatchShapeKind.SQUARE

/**
 * 把形状写进 [into]（会先 `reset()`），以原点为中心、尺寸 [width] × [height]。
 *
 * @param dx 阴影拷贝的横向偏移，直接加进顶点。
 * @param dy 阴影拷贝的纵向偏移。
 * @return [into] 本身，方便链式调用。
 */
fun buildWatchShapePath(
    kind: WatchShapeKind,
    width: Float,
    height: Float,
    into: Path,
    dx: Float = 0f,
    dy: Float = 0f,
): Path {
    into.reset()
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val left = dx - w / 2f
    val top = dy - h / 2f
    when (kind) {
        WatchShapeKind.CIRCLE -> into.addOval(left, top, left + w, top + h, Path.Direction.CW)
        WatchShapeKind.HEART -> buildHeartPath(left, top, w, h, into)
        else -> {
            val points = SHAPE_POLYGONS[kind] ?: SHAPE_POLYGONS.getValue(WatchShapeKind.SQUARE)
            var index = 0
            while (index < points.size) {
                val x = left + points[index] * w
                val y = top + points[index + 1] * h
                if (index == 0) into.moveTo(x, y) else into.lineTo(x, y)
                index += 2
            }
            into.close()
        }
    }
    return into
}

/**
 * 心形：四段三次贝塞尔，控制点照抄 wftools（`WmPreview.kt:1828-1833`）。
 *
 * 它那份最后一段没有显式 `close()` —— 终点正好回到起点，闭合是隐式的。这里补上
 * `close()`，因为描边通道用 STROKE 画时不闭合会在顶部留一道缺口。
 */
private fun buildHeartPath(left: Float, top: Float, w: Float, h: Float, into: Path) {
    fun x(f: Float) = left + f * w
    fun y(f: Float) = top + f * h
    into.moveTo(x(0.5f), y(0.2f))
    into.cubicTo(x(0.35f), y(0f), x(0f), y(0.06f), x(0.035f), y(0.4f))
    into.cubicTo(x(0.07f), y(0.66f), x(0.43f), y(0.83f), x(0.5f), y(1f))
    into.cubicTo(x(0.57f), y(0.83f), x(0.93f), y(0.66f), x(0.965f), y(0.4f))
    into.cubicTo(x(1f), y(0.06f), x(0.65f), y(0f), x(0.5f), y(0.2f))
    into.close()
}

/** `corner_type` 的合法取值（规范 §7.12 的 0..16）。 */
val WATCH_CORNER_TYPE_VALUES: IntRange = 0..16

/**
 * `corner_type` → 圆角半径数组，顺序是 Android [Path.addRoundRect] 要的
 * `[TL,TL, TR,TR, BR,BR, BL,BL]`。
 *
 * 映射表来自 `WM_FORMAT_1.0.md:405-425`，与 clox 的 `CORNERS`
 * （`wf_render.lua:176-181`，那张表是 `{TL,TR,BL,BR}` 顺序）逐位一致。
 * 16 和 0 都是「四角全直」—— 规范里就是这么定的，不是笔误。
 *
 * 未知值按 1（四角全圆）处理，与 clox 的 `CORNERS[...] or CORNERS[1]` 同样兜底。
 */
fun watchShapeCornerRadii(cornerType: Int, radius: Float): FloatArray {
    val r = radius.coerceAtLeast(0f)
    // 规范表按 TL / TR / BL / BR 列出，这里也照这个顺序写，读代码时能直接对上文档。
    val topLeft: Boolean
    val topRight: Boolean
    val bottomLeft: Boolean
    val bottomRight: Boolean
    when (cornerType) {
        0, 16 -> { topLeft = false; topRight = false; bottomLeft = false; bottomRight = false }
        2 -> { topLeft = true; topRight = true; bottomLeft = true; bottomRight = false }
        3 -> { topLeft = true; topRight = true; bottomLeft = false; bottomRight = true }
        4 -> { topLeft = true; topRight = false; bottomLeft = true; bottomRight = true }
        5 -> { topLeft = false; topRight = true; bottomLeft = true; bottomRight = true }
        6 -> { topLeft = true; topRight = true; bottomLeft = false; bottomRight = false }
        7 -> { topLeft = false; topRight = false; bottomLeft = true; bottomRight = true }
        8 -> { topLeft = true; topRight = false; bottomLeft = true; bottomRight = false }
        9 -> { topLeft = false; topRight = true; bottomLeft = false; bottomRight = true }
        10 -> { topLeft = true; topRight = false; bottomLeft = false; bottomRight = true }
        11 -> { topLeft = false; topRight = true; bottomLeft = true; bottomRight = false }
        12 -> { topLeft = true; topRight = false; bottomLeft = false; bottomRight = false }
        13 -> { topLeft = false; topRight = true; bottomLeft = false; bottomRight = false }
        14 -> { topLeft = false; topRight = false; bottomLeft = true; bottomRight = false }
        15 -> { topLeft = false; topRight = false; bottomLeft = false; bottomRight = true }
        else -> { topLeft = true; topRight = true; bottomLeft = true; bottomRight = true }
    }
    val tl = if (topLeft) r else 0f
    val tr = if (topRight) r else 0f
    val bl = if (bottomLeft) r else 0f
    val br = if (bottomRight) r else 0f
    return floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
}

/** [cornerType] 是否让**任何**一个角变圆。全直时调用方可以走 `drawRect` 快路径。 */
fun watchShapeHasRoundedCorner(cornerType: Int, radius: Float): Boolean =
    radius > 0f && watchShapeCornerRadii(cornerType, radius).any { it > 0f }

/**
 * 圆角矩形的半径钳制：规范说「clamped to half the smaller dimension」
 * （`WM_FORMAT_1.0.md:220`），clox 写的是 `min(radius, w/2, h/2)`
 * （`wf_render.lua:1477`）。两处一致。
 */
fun watchShapeClampCornerRadius(radius: Float, width: Float, height: Float): Float =
    radius.coerceAtLeast(0f)
        .coerceAtMost(width.coerceAtLeast(0f) / 2f)
        .coerceAtMost(height.coerceAtLeast(0f) / 2f)

/**
 * 把带 `corner_type` 的圆角矩形写进 [into]（会先 `reset()`），以原点为中心。
 *
 * 半径在这里**不再**钳制 —— 调用方应先过 [watchShapeClampCornerRadius]，
 * 因为 progress / chart 的可用盒子比图层盒子小（要扣掉 margin / bar 宽度），
 * 钳制基准不是同一个 w/h。
 */
fun buildWatchRoundedRectPath(
    width: Float,
    height: Float,
    radius: Float,
    cornerType: Int,
    into: Path,
    dx: Float = 0f,
    dy: Float = 0f,
): Path {
    into.reset()
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val rect = RectF(dx - w / 2f, dy - h / 2f, dx + w / 2f, dy + h / 2f)
    into.addRoundRect(rect, watchShapeCornerRadii(cornerType, radius), Path.Direction.CW)
    return into
}

/**
 * 任意矩形（不以原点为中心）的圆角版本 —— chart 的柱子用它，柱子是
 * `[cx-bw/2, top] .. [cx+bw/2, baseline]`，不在原点上。
 */
fun buildWatchRoundedRectPathIn(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    cornerType: Int,
    into: Path,
): Path {
    into.reset()
    val rect = RectF(
        minOf(left, right),
        minOf(top, bottom),
        maxOf(left, right),
        maxOf(top, bottom),
    )
    into.addRoundRect(rect, watchShapeCornerRadii(cornerType, radius), Path.Direction.CW)
    return into
}
