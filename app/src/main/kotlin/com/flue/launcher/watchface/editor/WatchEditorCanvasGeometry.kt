package com.flue.launcher.watchface.editor

import com.flue.launcher.watchface.standard.WatchmakerGeometry
import com.flue.launcher.watchface.standard.WatchmakerLayer
import com.flue.launcher.watchface.standard.resolveWatchLayerParentIndices
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry for the editor canvas: hit testing tapped layers, the
 * selection frame with its resize/rotate handles, and converting pointer
 * deltas into layer-local attribute updates. Everything works in Watchmaker
 * design space (512 x 512 centered on the canvas middle) so the math is
 * identical for every preview size and stays unit-testable on the JVM.
 */

/** A point in Watchmaker design coordinates (origin at canvas center). */
data class WatchEditorPoint(val x: Float, val y: Float)

/**
 * Row-major 2D affine transform: `x' = a*x + c*y + tx`, `y' = b*x + d*y + ty`.
 */
data class WatchEditorAffine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val tx: Float,
    val ty: Float,
) {
    fun apply(x: Float, y: Float): WatchEditorPoint =
        WatchEditorPoint(a * x + c * y + tx, b * x + d * y + ty)

    fun apply(point: WatchEditorPoint): WatchEditorPoint = apply(point.x, point.y)

    /** Applies only the linear part; use for direction/delta vectors. */
    fun applyVector(x: Float, y: Float): WatchEditorPoint =
        WatchEditorPoint(a * x + c * y, b * x + d * y)

    /** Returns `this ∘ inner` (apply [inner] first, then this transform). */
    fun compose(inner: WatchEditorAffine): WatchEditorAffine = WatchEditorAffine(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        tx = a * inner.tx + c * inner.ty + tx,
        ty = b * inner.tx + d * inner.ty + ty,
    )

    fun determinant(): Float = a * d - b * c

    fun inverted(): WatchEditorAffine? {
        val det = determinant()
        if (!det.isFinite() || abs(det) < 1e-6f) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return WatchEditorAffine(
            a = ia,
            b = ib,
            c = ic,
            d = id,
            tx = -(ia * tx + ic * ty),
            ty = -(ib * tx + id * ty),
        )
    }

    /** Approximate uniform scale magnitude, for converting touch slop. */
    fun scaleMagnitude(): Float = sqrt(abs(determinant())).takeIf { it > 1e-6f } ?: 1e-6f

    companion object {
        val IDENTITY = WatchEditorAffine(1f, 0f, 0f, 1f, 0f, 0f)

        fun fromTransform(transform: WatchEditorTransform): WatchEditorAffine {
            val radians = Math.toRadians(transform.rotation.toDouble())
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            // T(x, y) ∘ R(rotation) ∘ S(scaleX, scaleY)
            return WatchEditorAffine(
                a = cosine * transform.scaleX,
                b = sine * transform.scaleX,
                c = -sine * transform.scaleY,
                d = cosine * transform.scaleY,
                tx = transform.x,
                ty = transform.y,
            )
        }
    }
}

/** Axis-aligned content bounds in the layer's own coordinate space. */
data class WatchEditorLayerLocalBox(
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
)

data class WatchEditorLayerGeometry(
    val key: WatchEditorLayerKey,
    val transform: WatchEditorTransform,
    val localBox: WatchEditorLayerLocalBox,
    val parentIndex: Int?,
    val hitTestable: Boolean,
)

/** Reads the same transform attributes the runtime renderer applies. */
fun watchEditorTransformOf(attributes: Map<String, String>): WatchEditorTransform {
    val sharedScale = attributes["anim_scale"]?.takeIf(String::isNotBlank)
        ?: attributes["scale"]?.takeIf(String::isNotBlank)
    fun percent(raw: String?): Float =
        ((raw?.toFloatOrNull() ?: 100f) / 100f).coerceIn(0.05f, 8f)
    return WatchEditorTransform(
        x = attributes["x"]?.toFloatOrNull() ?: 0f,
        y = attributes["y"]?.toFloatOrNull() ?: 0f,
        scaleX = percent(
            attributes["anim_scale_x"]?.takeIf(String::isNotBlank)
                ?: attributes["scale_x"]?.takeIf(String::isNotBlank)
                ?: sharedScale,
        ),
        scaleY = percent(
            attributes["anim_scale_y"]?.takeIf(String::isNotBlank)
                ?: attributes["scale_y"]?.takeIf(String::isNotBlank)
                ?: sharedScale,
        ),
        rotation = attributes["rotation"]?.toFloatOrNull() ?: 0f,
    )
}

private fun attributeNumber(attributes: Map<String, String>, vararg names: String): Float? {
    names.forEach { name ->
        attributes[name]?.toFloatOrNull()?.takeIf(Float::isFinite)?.let { return it }
    }
    return null
}

/**
 * Estimates renderer text advance without a real Paint so the JVM tests stay
 * hermetic. Watchmaker expressions such as `{dh}:{dm}` are replaced by a
 * two-digit placeholder before measuring.
 */
fun estimateWatchEditorTextWidth(text: String, textSize: Float): Float {
    // '}' 与字符组内的 '{' 都必须转义：ICU 后端 (SDK 34+) 对裸花括号抛
    // PatternSyntaxException，见 RegexIcuCompatibilityTest。
    val simplified = text.replace(Regex("\\{[^\\{\\}]*\\}"), "88")
    if (simplified.isEmpty()) return 0f
    return simplified.length * textSize * 0.58f
}

private fun anchoredLocalBox(
    width: Float,
    height: Float,
    alignment: String?,
): WatchEditorLayerLocalBox {
    val anchor = alignment.orEmpty().lowercase(Locale.ROOT).ifBlank { "cc" }
    val centerX = when {
        anchor.endsWith('l') -> width / 2f
        anchor.endsWith('r') -> -width / 2f
        else -> 0f
    }
    val centerY = when {
        anchor.startsWith('t') -> height / 2f
        anchor.startsWith('b') -> -height / 2f
        else -> 0f
    }
    return WatchEditorLayerLocalBox(centerX, centerY, width / 2f, height / 2f)
}

/** Layers smaller than this stay tappable on a watch screen. */
private const val MIN_HALF_EXTENT = 8f

/**
 * Approximates each layer type's untransformed content bounds using the same
 * attribute defaults as the runtime renderer. [imageSizeLookup] resolves an
 * image path to its native pixel size (or null when unknown).
 */
fun estimateWatchEditorLayerBox(
    type: String,
    attributes: Map<String, String>,
    imageSizeLookup: (String) -> Pair<Float, Float>? = { null },
): WatchEditorLayerLocalBox {
    val shared = attributeNumber(attributes, "size")
    val box = when (type.lowercase(Locale.ROOT)) {
        "image", "gif", "image_gif", "image_tap", "image_cond", "video", "map", "svg" -> {
            val path = attributes["path"].orEmpty().substringBefore('`').trim()
            val native = path.takeIf(String::isNotBlank)?.let(imageSizeLookup)
            val width = attributeNumber(attributes, "width") ?: shared ?: native?.first ?: 64f
            val height = attributeNumber(attributes, "height") ?: shared ?: native?.second ?: 64f
            anchoredLocalBox(width, height, attributes["alignment"])
        }
        "text" -> {
            val textSize = (attributeNumber(attributes, "text_size") ?: shared ?: 24f)
                .coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE)
            val width = estimateWatchEditorTextWidth(attributes["text"].orEmpty(), textSize)
                .coerceAtLeast(textSize)
            anchoredLocalBox(width, textSize * 1.2f, attributes["alignment"])
        }
        "text_curved", "text_ring" -> {
            val textSize = (attributeNumber(attributes, "text_size") ?: 40f)
                .coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE)
            val radius = attributeNumber(attributes, "radius") ?: 160f
            val half = radius + textSize
            WatchEditorLayerLocalBox(0f, 0f, half, half)
        }
        "shape" -> {
            val width = attributeNumber(attributes, "width") ?: shared ?: 64f
            val height = attributeNumber(attributes, "height") ?: shared ?: width
            WatchEditorLayerLocalBox(0f, 0f, width / 2f, height / 2f)
        }
        "rounded" -> {
            val width = attributeNumber(attributes, "width") ?: shared ?: 64f
            val height = attributeNumber(attributes, "height") ?: shared ?: 64f
            WatchEditorLayerLocalBox(0f, 0f, width / 2f, height / 2f)
        }
        "ring" -> {
            val outer = attributeNumber(attributes, "radius_outer")
                ?: attributeNumber(attributes, "radius")
                ?: 100f
            WatchEditorLayerLocalBox(0f, 0f, outer, outer)
        }
        "progress" -> {
            val width = attributeNumber(attributes, "width") ?: shared ?: 100f
            val height = attributeNumber(attributes, "height") ?: shared ?: 20f
            WatchEditorLayerLocalBox(0f, 0f, width / 2f, height / 2f)
        }
        "markers", "markers_hm" -> {
            val radius = attributeNumber(attributes, "radius") ?: 220f
            val markHeight = attributeNumber(attributes, "m_height") ?: 20f
            val half = radius + markHeight / 2f
            WatchEditorLayerLocalBox(0f, 0f, half, half)
        }
        else -> WatchEditorLayerLocalBox(0f, 0f, 48f, 48f)
    }
    return box.copy(
        halfWidth = box.halfWidth.coerceAtLeast(MIN_HALF_EXTENT),
        halfHeight = box.halfHeight.coerceAtLeast(MIN_HALF_EXTENT),
    )
}

private fun isLayerHitTestable(attributes: Map<String, String>): Boolean {
    val display = attributes["display"]?.trim()?.lowercase(Locale.ROOT) ?: return true
    return display !in setOf("false", "no", "n", "0")
}

/**
 * Builds geometry (own transform, content box, parent link) for every editor
 * layer. [transformOverrides] lets an in-flight canvas gesture replace the
 * attribute-derived transform without waiting for a DOM commit.
 */
fun buildWatchEditorLayerGeometries(
    layers: List<WatchEditorLayer>,
    imageSizeLookup: (String) -> Pair<Float, Float>? = { null },
    transformOverrides: Map<WatchEditorLayerKey, WatchEditorTransform> = emptyMap(),
): List<WatchEditorLayerGeometry> {
    if (layers.isEmpty()) return emptyList()
    val parentIndices = resolveWatchLayerParentIndices(
        layers.map { layer ->
            WatchmakerLayer(
                id = layer.id,
                type = layer.type,
                parentId = layer.parentId,
                attributes = layer.attributes,
                children = emptyList(),
            )
        },
    )
    return layers.mapIndexed { index, layer ->
        WatchEditorLayerGeometry(
            key = layer.key,
            transform = transformOverrides[layer.key] ?: watchEditorTransformOf(layer.attributes),
            localBox = estimateWatchEditorLayerBox(layer.type, layer.attributes, imageSizeLookup),
            parentIndex = parentIndices[index],
            hitTestable = isLayerHitTestable(layer.attributes),
        )
    }
}

/** Computes layer-local -> design-space matrices, composing ancestor chains. */
fun buildWatchEditorWorldMatrices(
    geometries: List<WatchEditorLayerGeometry>,
): List<WatchEditorAffine> {
    val cache = arrayOfNulls<WatchEditorAffine>(geometries.size)
    fun worldOf(index: Int, depth: Int): WatchEditorAffine {
        cache[index]?.let { return it }
        val geometry = geometries[index]
        val local = WatchEditorAffine.fromTransform(geometry.transform)
        val parent = geometry.parentIndex
        val world = if (parent == null || parent !in geometries.indices || depth > geometries.size) {
            local
        } else {
            worldOf(parent, depth + 1).compose(local)
        }
        cache[index] = world
        return world
    }
    return List(geometries.size) { worldOf(it, 0) }
}

/**
 * Returns the index of the topmost hit-testable layer under the design-space
 * point, or null. Later list entries draw on top, so iteration is reversed.
 * [slopDesign] expands each layer's bounds by a touch slop in design units.
 */
fun hitTestWatchEditorLayers(
    geometries: List<WatchEditorLayerGeometry>,
    worldMatrices: List<WatchEditorAffine>,
    x: Float,
    y: Float,
    slopDesign: Float = 0f,
): Int? {
    for (index in geometries.indices.reversed()) {
        if (hitTestWatchEditorLayer(geometries, worldMatrices, index, x, y, slopDesign)) {
            return index
        }
    }
    return null
}

/**
 * Whether the design-space point falls inside one specific layer's bounds.
 *
 * 拆出来供「已选中元素独占拖动」用：选中态下先只测选中的那一层，命中就直接
 * 拖它，不再被压在它上面的图层抢走（老师指定：只有单击才切换选择）。
 */
fun hitTestWatchEditorLayer(
    geometries: List<WatchEditorLayerGeometry>,
    worldMatrices: List<WatchEditorAffine>,
    index: Int,
    x: Float,
    y: Float,
    slopDesign: Float = 0f,
): Boolean {
    val geometry = geometries.getOrNull(index) ?: return false
    if (!geometry.hitTestable) return false
    val matrix = worldMatrices.getOrNull(index) ?: return false
    val inverse = matrix.inverted() ?: return false
    val local = inverse.apply(x, y)
    val slopLocal = slopDesign / matrix.scaleMagnitude()
    val box = geometry.localBox
    return abs(local.x - box.centerX) <= box.halfWidth + slopLocal &&
        abs(local.y - box.centerY) <= box.halfHeight + slopLocal
}

/** The selection frame: content box corners mapped into design space. */
data class WatchEditorSelectionBox(
    val center: WatchEditorPoint,
    val topLeft: WatchEditorPoint,
    val topRight: WatchEditorPoint,
    val bottomLeft: WatchEditorPoint,
    val bottomRight: WatchEditorPoint,
) {
    val topMid: WatchEditorPoint get() = midpoint(topLeft, topRight)
    val bottomMid: WatchEditorPoint get() = midpoint(bottomLeft, bottomRight)
    val leftMid: WatchEditorPoint get() = midpoint(topLeft, bottomLeft)
    val rightMid: WatchEditorPoint get() = midpoint(topRight, bottomRight)

    /** Rotation handle anchored above the top edge along its outward normal. */
    fun rotationHandle(offsetDesign: Float): WatchEditorPoint {
        val top = topMid
        val dx = top.x - center.x
        val dy = top.y - center.y
        val length = hypot(dx, dy)
        if (length < 1e-4f) return WatchEditorPoint(top.x, top.y - offsetDesign)
        return WatchEditorPoint(
            top.x + dx / length * offsetDesign,
            top.y + dy / length * offsetDesign,
        )
    }

    private fun midpoint(a: WatchEditorPoint, b: WatchEditorPoint): WatchEditorPoint =
        WatchEditorPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}

fun watchEditorSelectionBox(
    geometry: WatchEditorLayerGeometry,
    worldMatrix: WatchEditorAffine,
): WatchEditorSelectionBox {
    val box = geometry.localBox
    return WatchEditorSelectionBox(
        center = worldMatrix.apply(box.centerX, box.centerY),
        topLeft = worldMatrix.apply(box.centerX - box.halfWidth, box.centerY - box.halfHeight),
        topRight = worldMatrix.apply(box.centerX + box.halfWidth, box.centerY - box.halfHeight),
        bottomLeft = worldMatrix.apply(box.centerX - box.halfWidth, box.centerY + box.halfHeight),
        bottomRight = worldMatrix.apply(box.centerX + box.halfWidth, box.centerY + box.halfHeight),
    )
}

enum class WatchEditorHandle {
    ROTATE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
}

fun watchEditorHandlePosition(
    box: WatchEditorSelectionBox,
    handle: WatchEditorHandle,
    rotationOffsetDesign: Float,
): WatchEditorPoint = when (handle) {
    WatchEditorHandle.ROTATE -> box.rotationHandle(rotationOffsetDesign)
    WatchEditorHandle.TOP_LEFT -> box.topLeft
    WatchEditorHandle.TOP_RIGHT -> box.topRight
    WatchEditorHandle.BOTTOM_LEFT -> box.bottomLeft
    WatchEditorHandle.BOTTOM_RIGHT -> box.bottomRight
    WatchEditorHandle.LEFT -> box.leftMid
    WatchEditorHandle.TOP -> box.topMid
    WatchEditorHandle.RIGHT -> box.rightMid
    WatchEditorHandle.BOTTOM -> box.bottomMid
}

/**
 * Returns the closest handle within [touchRadiusDesign] of the pointer, or
 * null. The rotation handle wins ties because it sits outside the frame.
 */
fun resolveWatchEditorHandleHit(
    box: WatchEditorSelectionBox,
    x: Float,
    y: Float,
    touchRadiusDesign: Float,
    rotationOffsetDesign: Float,
): WatchEditorHandle? {
    var best: WatchEditorHandle? = null
    var bestDistance = Float.MAX_VALUE
    WatchEditorHandle.entries.forEach { handle ->
        val position = watchEditorHandlePosition(box, handle, rotationOffsetDesign)
        val distance = hypot(x - position.x, y - position.y)
        if (distance <= touchRadiusDesign && distance < bestDistance) {
            best = handle
            bestDistance = distance
        }
    }
    return best
}

/** Snaps to the nearest multiple of [stepDegrees] when within [toleranceDegrees]. */
fun snapWatchEditorRotation(
    rotation: Float,
    stepDegrees: Float = 45f,
    toleranceDegrees: Float = 3f,
): Float {
    if (stepDegrees <= 0f || !rotation.isFinite()) return rotation
    val nearest = (rotation / stepDegrees).roundToInt() * stepDegrees
    return if (abs(rotation - nearest) <= toleranceDegrees) nearest else rotation
}

private fun angleDegrees(from: WatchEditorPoint, to: WatchEditorPoint): Float =
    Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()

private const val HANDLE_EPSILON = 1e-3f

/**
 * Applies a handle drag to the transform captured when the gesture began.
 * All points are in design space; [box] is the selection frame at gesture
 * start. Corners scale proportionally, edges scale one axis, and the rotate
 * handle spins around the frame center with 45-degree snapping.
 */
fun applyWatchEditorHandleDrag(
    handle: WatchEditorHandle,
    box: WatchEditorSelectionBox,
    startTransform: WatchEditorTransform,
    startX: Float,
    startY: Float,
    currentX: Float,
    currentY: Float,
): WatchEditorTransform {
    val center = box.center
    return when (handle) {
        WatchEditorHandle.ROTATE -> {
            val startAngle = angleDegrees(center, WatchEditorPoint(startX, startY))
            val currentAngle = angleDegrees(center, WatchEditorPoint(currentX, currentY))
            startTransform.copy(
                rotation = snapWatchEditorRotation(
                    startTransform.rotation + currentAngle - startAngle,
                ),
            ).sanitized()
        }
        WatchEditorHandle.TOP_LEFT,
        WatchEditorHandle.TOP_RIGHT,
        WatchEditorHandle.BOTTOM_LEFT,
        WatchEditorHandle.BOTTOM_RIGHT,
        -> {
            val startDistance = hypot(startX - center.x, startY - center.y)
            val currentDistance = hypot(currentX - center.x, currentY - center.y)
            val factor = if (startDistance < HANDLE_EPSILON) 1f else currentDistance / startDistance
            startTransform.copy(
                scaleX = startTransform.scaleX * factor,
                scaleY = startTransform.scaleY * factor,
            ).sanitized()
        }
        WatchEditorHandle.LEFT, WatchEditorHandle.RIGHT -> {
            val factor = axisScaleFactor(
                axisFrom = box.topLeft,
                axisTo = box.topRight,
                center = center,
                startX = startX,
                startY = startY,
                currentX = currentX,
                currentY = currentY,
            )
            startTransform.copy(scaleX = startTransform.scaleX * factor).sanitized()
        }
        WatchEditorHandle.TOP, WatchEditorHandle.BOTTOM -> {
            val factor = axisScaleFactor(
                axisFrom = box.topLeft,
                axisTo = box.bottomLeft,
                center = center,
                startX = startX,
                startY = startY,
                currentX = currentX,
                currentY = currentY,
            )
            startTransform.copy(scaleY = startTransform.scaleY * factor).sanitized()
        }
    }
}

private fun axisScaleFactor(
    axisFrom: WatchEditorPoint,
    axisTo: WatchEditorPoint,
    center: WatchEditorPoint,
    startX: Float,
    startY: Float,
    currentX: Float,
    currentY: Float,
): Float {
    val axisX = axisTo.x - axisFrom.x
    val axisY = axisTo.y - axisFrom.y
    val length = hypot(axisX, axisY)
    if (length < HANDLE_EPSILON) return 1f
    val unitX = axisX / length
    val unitY = axisY / length
    val startProjection = abs((startX - center.x) * unitX + (startY - center.y) * unitY)
    val currentProjection = abs((currentX - center.x) * unitX + (currentY - center.y) * unitY)
    return if (startProjection < HANDLE_EPSILON) 1f else currentProjection / startProjection
}

/**
 * Converts a design-space pointer delta into the coordinate frame the layer's
 * `x`/`y` attributes live in (its parent chain), so dragging a child of a
 * rotated or scaled group still follows the finger.
 */
fun watchEditorDragDeltaInParentFrame(
    geometries: List<WatchEditorLayerGeometry>,
    worldMatrices: List<WatchEditorAffine>,
    layerIndex: Int,
    deltaX: Float,
    deltaY: Float,
): WatchEditorPoint {
    val parent = geometries.getOrNull(layerIndex)?.parentIndex
        ?: return WatchEditorPoint(deltaX, deltaY)
    val parentInverse = worldMatrices.getOrNull(parent)?.inverted()
        ?: return WatchEditorPoint(deltaX, deltaY)
    return parentInverse.applyVector(deltaX, deltaY)
}

/**
 * 视图像素 ↔ 设计坐标的双轴映射。
 *
 * 和渲染器 `watchCanvasTransform` 用同一套契约：**等比** scale 在 FIT 时取
 * `min(viewW/designW, viewH/designH)`，在 FILL 时取 `max(...)`；画布始终居中。所以这里
 * 以视口中心为基准反投，而不是「除以宽度」——后者只在视口比例恰好等于设计比例
 * 时才成立，而编辑器预览的高度是弹簧动画驱动的，动画中途两者必然不相等。用
 * 中心反投在动画每一帧都和渲染结果对齐。
 *
 * [designWidth] / [designHeight] 缺省是原生方形画布，方形表盘行为与改动前一致。
 *
 * 编辑器的设备预览固定使用居中 FILL，与画布调节的“铺满模式”一致；[fill] 让
 * 选择框、命中测试和渲染器使用同一缩放规则。
 */
data class WatchEditorCanvasMapping(
    val viewWidthPx: Float,
    val viewHeightPx: Float,
    val designWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    val designHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
    val fill: Boolean = false,
) {
    /** 等比缩放因子（视图像素 / 设计单位）；退化输入回落 1 避免除零。 */
    val scale: Float
        get() {
            if (viewWidthPx <= 0f || viewHeightPx <= 0f) return 1f
            if (designWidth <= 0f || designHeight <= 0f) return 1f
            val widthScale = viewWidthPx / designWidth
            val heightScale = viewHeightPx / designHeight
            val candidate = if (fill) maxOf(widthScale, heightScale) else minOf(widthScale, heightScale)
            return if (candidate > 0f && candidate.isFinite()) candidate else 1f
        }

    fun designX(pxX: Float): Float = (pxX - viewWidthPx / 2f) / scale

    fun designY(pxY: Float): Float = (pxY - viewHeightPx / 2f) / scale

    fun viewX(designX: Float): Float = designX * scale + viewWidthPx / 2f

    fun viewY(designY: Float): Float = designY * scale + viewHeightPx / 2f

    /** 视图像素长度（触摸容差、手柄半径）换算成设计单位。 */
    fun designLength(lengthPx: Float): Float = lengthPx / scale
}

/**
 * Converts view-pixel coordinates into centered design-space coordinates.
 *
 * 单轴旧签名，等价于方形画布 + 方形视口的 [WatchEditorCanvasMapping]。保留它让
 * 既有调用点和单元测试不必全改。
 */
fun watchEditorDesignFromViewPx(px: Float, viewSizePx: Float): Float {
    if (viewSizePx <= 0f) return 0f
    return px / viewSizePx * WatchmakerGeometry.DESIGN_SIZE - WatchmakerGeometry.DESIGN_SIZE / 2f
}

/** Converts centered design-space coordinates into view pixels. */
fun watchEditorViewPxFromDesign(design: Float, viewSizePx: Float): Float =
    (design + WatchmakerGeometry.DESIGN_SIZE / 2f) / WatchmakerGeometry.DESIGN_SIZE * viewSizePx

/** Converts a view-pixel length (touch slop, handle radius) to design units. */
fun watchEditorDesignLengthFromViewPx(lengthPx: Float, viewSizePx: Float): Float {
    if (viewSizePx <= 0f) return lengthPx
    return lengthPx / viewSizePx * WatchmakerGeometry.DESIGN_SIZE
}
