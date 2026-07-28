package com.flue.launcher.watchface.editor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 表盘编辑器的拖动辅助吸附。
 *
 * 三类吸附（老师指定）：
 *  1. 旋转吸附：接近 0/90/180/270 时锁到整数角
 *  2. 中心吸附：x 或 y 接近表盘中心（0）时锁到 0
 *  3. 等距吸附：与其他元素在同一轴上对齐时锁到该坐标
 *
 * ## 为什么需要「脱离」语义
 *
 * 第一版只做了「进入阈值就把值锁到吸附点」，结果手指继续拖、只要还没走出
 * 阈值就一直被锁回同一个点 —— 观感就是元素粘住拖不动（老师反馈）。
 *
 * 正确做法是带 hysteresis：吸附是一个**状态**，进入用小阈值 [ENTER_THRESHOLD]，
 * 退出用更大的 [EXIT_THRESHOLD]。已经吸住时，手指必须拖出 EXIT 才解锁；
 * 解锁后要再次靠近到 ENTER 以内才会重新吸住。这样既有磁性又不会卡死。
 *
 * 阈值单位是设计坐标（512 基准的中心坐标系），与视图像素无关。
 */
object WatchEditorSnapping {

    /** 旋转吸附阈值（度）。 */
    const val ROTATION_THRESHOLD_DEG = 3f

    /** 位置吸附「进入」阈值（设计单位）。512 画布下 2.5 ≈ 0.5% 边长。 */
    const val ENTER_THRESHOLD = 2.5f

    /** 位置吸附「退出」阈值。必须明显大于 ENTER，否则会在边界抖动。 */
    const val EXIT_THRESHOLD = 9f

    private val ROTATION_TARGETS = floatArrayOf(0f, 90f, 180f, 270f, 360f)

    /**
     * 把 [rotation] 吸附到最近的整数直角。返回吸附后的角度；
     * 不在任何阈值内时原样返回。
     */
    fun snapRotation(rotation: Float): Float {
        val normalized = ((rotation % 360f) + 360f) % 360f
        val target = ROTATION_TARGETS.minByOrNull { abs(normalized - it) } ?: return rotation
        if (abs(normalized - target) > ROTATION_THRESHOLD_DEG) return rotation
        val snappedNormalized = if (target >= 360f) 0f else target
        val turns = ((rotation - normalized) / 360f).roundToInt()
        return snappedNormalized + turns * 360f
    }

    /**
     * 收集单轴上所有候选吸附点：中心（0）、各 peer 坐标、相邻 peer 的中点。
     */
    private fun candidates(peers: FloatArray): FloatArray {
        if (peers.isEmpty()) return floatArrayOf(0f)
        val list = ArrayList<Float>(peers.size * 2 + 1)
        list.add(0f)
        peers.forEach(list::add)
        val sorted = peers.sorted()
        for (i in 0 until sorted.size - 1) {
            list.add((sorted[i] + sorted[i + 1]) / 2f)
        }
        return list.toFloatArray()
    }

    /**
     * 带 hysteresis 的单轴吸附。
     *
     * @param value    手指当前对应的原始坐标（未吸附）
     * @param peers    同轴上其他元素的坐标
     * @param lockedAt 上一帧已吸住的点；null 表示当前未吸附
     * @return         吸附结果：新坐标 + 新的吸附状态（供下一帧传回）
     */
    fun snapAxis(
        value: Float,
        peers: FloatArray,
        lockedAt: Float?,
    ): AxisSnap {
        // 已吸住：只有拖出 EXIT_THRESHOLD 才解锁，否则维持吸附点。
        if (lockedAt != null) {
            return if (abs(value - lockedAt) <= EXIT_THRESHOLD) {
                AxisSnap(lockedAt, lockedAt)
            } else {
                // 刚脱离就返回真实坐标，不立刻吸到别处，避免连续跳点。
                AxisSnap(value, null)
            }
        }
        // 未吸住：靠近任何候选点到 ENTER_THRESHOLD 以内就吸上。
        val nearest = candidates(peers)
            .filter { abs(value - it) <= ENTER_THRESHOLD }
            .minByOrNull { abs(value - it) }
        return if (nearest != null) AxisSnap(nearest, nearest) else AxisSnap(value, null)
    }

    /** 单轴吸附结果。[lockedAt] 需要由调用方在手势期间保存并回传。 */
    data class AxisSnap(
        val value: Float,
        val lockedAt: Float?,
    )

    /** 一次手势内的吸附状态。调用方在 onBeginTransform 时新建，拖动期间复用。 */
    class GestureState {
        var lockedX: Float? = null
        var lockedY: Float? = null

        fun reset() {
            lockedX = null
            lockedY = null
        }
    }

    /**
     * 对一次拖动结果做双轴吸附，并就地更新 [state]。
     *
     * @param snapRotationToo 双指手势禁用旋转时传 false
     */
    fun snapTransform(
        x: Float,
        y: Float,
        rotation: Float,
        peerXs: FloatArray = FloatArray(0),
        peerYs: FloatArray = FloatArray(0),
        state: GestureState,
        snapRotationToo: Boolean = true,
    ): SnapResult {
        val sx = snapAxis(x, peerXs, state.lockedX)
        val sy = snapAxis(y, peerYs, state.lockedY)
        state.lockedX = sx.lockedAt
        state.lockedY = sy.lockedAt
        return SnapResult(
            x = sx.value,
            y = sy.value,
            rotation = if (snapRotationToo) snapRotation(rotation) else rotation,
            snappedX = sx.lockedAt != null,
            snappedY = sy.lockedAt != null,
        )
    }

    data class SnapResult(
        val x: Float,
        val y: Float,
        val rotation: Float,
        /** 供 UI 画对齐参考线用（当前未接，留给后续）。 */
        val snappedX: Boolean = false,
        val snappedY: Boolean = false,
    )
}
