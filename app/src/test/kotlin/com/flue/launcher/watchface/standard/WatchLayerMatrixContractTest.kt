package com.flue.launcher.watchface.standard

import android.graphics.Matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 锁住图层变换的折叠顺序。
 *
 * 视频图层走 [android.view.TextureView.setTransform]，只吃 Matrix；其余图层走
 * Canvas 的 `concat`。两条路径由同一个 `composeLayerMatrix` 驱动，所以这里要钉住
 * 的是**折叠顺序本身** —— translate → rotate → scale → skew，以及祖先组「外先内
 * 后」。顺序一旦反了，视频会相对静态图层偏一点点，而这种偏差在真机上极难定位。
 *
 * 刻意不拿 `canvas.matrix` 当基准：Robolectric 的 Canvas 不累积变换，读回来恒为
 * 单位矩阵，拿它比对等于什么都没验。这里改成与手推的乘积逐元素比对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchLayerMatrixContractTest {

    private fun values(matrix: Matrix): FloatArray =
        FloatArray(9).also { matrix.getValues(it) }

    /** 行主序 3×3 相乘，与 [Matrix] 的内部布局一致。 */
    private fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += left[row * 3 + k] * right[k * 3 + column]
                }
                out[row * 3 + column] = sum
            }
        }
        return out
    }

    private fun translation(x: Float, y: Float) =
        floatArrayOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f)

    private fun rotation(degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    private fun scaling(sx: Float, sy: Float) =
        floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f)

    private fun skewing(kx: Float, ky: Float) =
        floatArrayOf(1f, kx, 0f, ky, 1f, 0f, 0f, 0f, 1f)

    /**
     * 单个图层：translate → rotate → scale → skew 依次 pre-concat，
     * 等价于同顺序的矩阵右乘。
     */
    @Test
    fun layerTransformFoldsInAuthoredOrder() {
        val x = 37.5f
        val y = -12.25f
        val rotationDegrees = 33f
        val scaleX = 1.75f
        val scaleY = 0.4f
        val skewX = tan(Math.toRadians(11.0)).toFloat()
        val skewY = tan(Math.toRadians(-7.0)).toFloat()

        val composed = Matrix().apply {
            preTranslate(x, y)
            preRotate(rotationDegrees)
            preScale(scaleX, scaleY)
            preSkew(skewX, skewY)
        }

        var expected = translation(x, y)
        expected = multiply(expected, rotation(rotationDegrees))
        expected = multiply(expected, scaling(scaleX, scaleY))
        expected = multiply(expected, skewing(skewX, skewY))

        assertArrayEquals(expected, values(composed), 1e-4f)
    }

    /**
     * skew 的符号：cocos 在 **y 轴向上** 的空间里做 `u' = u + tan(sx)·v`、
     * `v' = v + tan(sy)·u`；Flue 的 Canvas 是 y 轴向下。
     *
     * 这里不复读实现里的表达式（那样等于自证），而是**独立**走一遍换算 ——
     * 把点翻进 y-up、套 cocos 公式、再翻回来 —— 拿它当基准去比对
     * `preSkew(-tan(sx), -tan(sy))`。两个分量少取一次负，就会变成沿另一根轴
     * 镜像，也就是老师报的「数字倾斜方向相反」。
     */
    @Test
    fun skewNegatesBothComponentsWhenCrossingIntoCanvasYDown() {
        val skewXDegrees = 18.0
        val skewYDegrees = -9.0
        val tanX = tan(Math.toRadians(skewXDegrees)).toFloat()
        val tanY = tan(Math.toRadians(skewYDegrees)).toFloat()

        // 实现侧：两个分量都取负。
        val actual = floatArrayOf(24f, -37f, -15f, 61f)
        Matrix().apply { preSkew(-tanX, -tanY) }.mapPoints(actual)

        // 基准侧：y-down → y-up → cocos skew → y-down。
        val expected = floatArrayOf(24f, -37f, -15f, 61f)
        for (i in expected.indices step 2) {
            val x = expected[i]
            val v = -expected[i + 1]                 // 翻进 cocos 的 y-up
            expected[i] = x + tanX * v
            expected[i + 1] = -(v + tanY * x)        // 算完再翻回 Canvas
        }

        assertArrayEquals(expected, actual, 1e-4f)
    }

    /**
     * gyro 视差的符号。clox `applyPos` 自带的注释就是判据（原文）：
     * `UL (screen coords, y-down): x += sgy*gyro, y += sgx*(-gyro)`。
     *
     * 它自己在 y-up 空间里两个分量都写成加号，是因为 `baseY = Y_SIGN * y` 已经
     * 翻过一次。Flue 直接在 y-down 里排版，所以只有 y 分量取负 —— x 分量本来
     * 就是对的，别顺手一起翻。
     */
    @Test
    fun gyroParallaxNegatesOnlyTheYComponentInCanvasSpace() {
        val baseX = 120f
        val baseY = -64f
        val gyroscopeX = 0.35f
        val gyroscopeY = -0.8f
        val gyroScale = 12f

        val actual = floatArrayOf(0f, 0f)
        Matrix().apply {
            preTranslate(baseX + gyroscopeY * gyroScale, baseY - gyroscopeX * gyroScale)
        }.mapPoints(actual)

        // 基准侧：在 cocos 的 y-up 里按 clox 原式摆位，再翻回 Canvas。
        val cocosX = baseX + gyroscopeY * gyroScale
        val cocosY = -baseY + gyroscopeX * gyroScale     // baseY 先翻成 y-up
        assertArrayEquals(floatArrayOf(cocosX, -cocosY), actual, 1e-4f)
    }

    /**
     * 祖先组链：外层组的变换必须先作用、内层后作用，图层自身最后。
     * 顺序反了整组元素会绕错误的锚点旋转。
     */
    @Test
    fun ancestorChainAppliesOutermostFirst() {
        val outer = Matrix().apply {
            preTranslate(100f, 20f)
            preRotate(15f)
        }
        val inner = Matrix().apply {
            preTranslate(-30f, 45f)
            preScale(2f, 2f)
        }
        val own = Matrix().apply {
            preTranslate(5f, 5f)
            preRotate(-8f)
        }
        val composed = Matrix().apply {
            preConcat(outer)
            preConcat(inner)
            preConcat(own)
        }

        var expected = multiply(translation(100f, 20f), rotation(15f))
        expected = multiply(expected, multiply(translation(-30f, 45f), scaling(2f, 2f)))
        expected = multiply(expected, multiply(translation(5f, 5f), rotation(-8f)))

        assertArrayEquals(expected, values(composed), 1e-4f)
    }

    /**
     * 画布基变换是视频图层矩阵的最外层：等比缩放 + 居中原点。
     * 非方 View 上必须取短边比例并居中留边，与 Canvas 路径同源。
     */
    @Test
    fun canvasBaseTransformCentersDesignSpace() {
        val transform = watchCanvasTransform(
            viewWidth = 300f,
            viewHeight = 400f,
            forceFill = false,
            designWidth = 512f,
            designHeight = 512f,
        )

        val composed = Matrix().apply {
            preTranslate(transform.left, transform.top)
            preScale(transform.scaleX, transform.scaleY)
            preTranslate(256f, 256f)
        }

        var expected = translation(transform.left, transform.top)
        expected = multiply(expected, scaling(transform.scaleX, transform.scaleY))
        expected = multiply(expected, translation(256f, 256f))

        assertArrayEquals(expected, values(composed), 1e-4f)

        // 设计空间原点应落在 View 的几何中心：300×400 的 View 上，
        // 512² 的画布按短边缩放，垂直方向留白。
        val mapped = floatArrayOf(0f, 0f)
        composed.mapPoints(mapped)
        assertArrayEquals(floatArrayOf(150f, 200f), mapped, 1e-3f)
    }
}
