package com.flue.launcher.watchface.standard

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `shader` 通道的回归测试。
 *
 * Flue 以前完全没有 shader —— 全库 grep `"shader"` 零命中，所有带渐变的 `shape` /
 * `rounded` 图层都画成纯色。这套测试盯三件事：
 *
 * 1. 11 个字面值都能解析（大小写不敏感），未知值退回 NONE 而不是抛。
 * 2. 每种着色器都真的产出了 Paint 效果（shader 或 colorFilter 至少一个非空）——
 *    少一个分支就意味着那种着色器静默失效。
 * 3. `u_*` 缺省值与 wftools 的 `wmShaderDefaults` 逐位一致：选中着色器时要把这些值
 *    写进 XML，否则面板全是「未设置」、渲染器把空值当 0，用户看到一块纯黑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchShapeShadersTest {

    @Test
    fun `every specification literal parses`() {
        assertEquals(WatchShapeShaderKind.GRADIENT_LINEAR, watchShapeShaderKindOf("GradientLinear"))
        assertEquals(WatchShapeShaderKind.GRADIENT_RADIAL, watchShapeShaderKindOf("GradientRadial"))
        assertEquals(WatchShapeShaderKind.RAINBOW, watchShapeShaderKindOf("Rainbow"))
        assertEquals(WatchShapeShaderKind.RGB, watchShapeShaderKindOf("RGB"))
        assertEquals(WatchShapeShaderKind.HSV, watchShapeShaderKindOf("HSV"))
        assertEquals(WatchShapeShaderKind.SEGMENT, watchShapeShaderKindOf("Segment"))
        assertEquals(WatchShapeShaderKind.SEGMENT_BETWEEN, watchShapeShaderKindOf("SegmentBetween"))
        assertEquals(WatchShapeShaderKind.RADAR, watchShapeShaderKindOf("Radar"))
        assertEquals(WatchShapeShaderKind.PROGRESS, watchShapeShaderKindOf("Progress"))
        assertEquals(WatchShapeShaderKind.PROGRESS_BETWEEN, watchShapeShaderKindOf("ProgressBetween"))
        // 表盘里大小写不统一是常态。
        assertEquals(WatchShapeShaderKind.RAINBOW, watchShapeShaderKindOf("rainbow"))
        // 未知与缺省都退回 NONE，绝不抛 —— 一条坏属性不能让整块表盘加载不了。
        assertEquals(WatchShapeShaderKind.NONE, watchShapeShaderKindOf("Bloom"))
        assertEquals(WatchShapeShaderKind.NONE, watchShapeShaderKindOf(null))
        assertEquals(WatchShapeShaderKind.NONE, watchShapeShaderKindOf(""))
    }

    @Test
    fun `attribute values round trip`() {
        WatchShapeShaderKind.entries.forEach { kind ->
            val literal = watchShapeShaderAttributeValue(kind)
            assertEquals(
                "writing $kind back must preserve the spec's capitalisation",
                kind,
                watchShapeShaderKindOf(literal),
            )
        }
    }

    @Test
    fun `no shader produces no paint effect`() {
        assertNull(effectFor(WatchShapeShaderKind.NONE))
    }

    @Test
    fun `every shader produces a usable paint effect`() {
        WatchShapeShaderKind.entries
            .filter { it != WatchShapeShaderKind.NONE }
            .forEach { kind ->
                val effect = effectFor(kind)
                assertNotNull("$kind produced no effect at all", effect)
                val usable = effect!!.shader != null || effect.colorFilter != null
                assertTrue("$kind produced an empty effect", usable)
            }
    }

    @Test
    fun `colour matrix shaders use a filter and gradients use a shader`() {
        // RGB / HSV 是逐像素颜色变换 → ColorFilter；其余是几何渐变 → Shader。
        listOf(WatchShapeShaderKind.RGB, WatchShapeShaderKind.HSV).forEach { kind ->
            val effect = effectFor(kind)!!
            assertNotNull("$kind must use a colour filter", effect.colorFilter)
            assertNull("$kind must not need a shader", effect.shader)
        }
        listOf(
            WatchShapeShaderKind.GRADIENT_LINEAR,
            WatchShapeShaderKind.GRADIENT_RADIAL,
            WatchShapeShaderKind.RAINBOW,
            WatchShapeShaderKind.SEGMENT,
            WatchShapeShaderKind.SEGMENT_BETWEEN,
            WatchShapeShaderKind.RADAR,
            WatchShapeShaderKind.PROGRESS,
            WatchShapeShaderKind.PROGRESS_BETWEEN,
        ).forEach { kind ->
            assertNotNull("$kind must use a shader", effectFor(kind)!!.shader)
        }
    }

    @Test
    fun `degenerate boxes produce no effect instead of throwing`() {
        // 宽或高为 0 的图层（作者写了 width="0"）不能让渐变构造抛异常。
        assertNull(effectFor(WatchShapeShaderKind.GRADIENT_LINEAR, width = 0f))
        assertNull(effectFor(WatchShapeShaderKind.RADAR, height = 0f))
    }

    @Test
    fun `reversed ranges do not throw`() {
        // SegmentBetween 的起止角写反、ProgressBetween 的起止百分比写反都很常见。
        // Skia 对乱序 stop 会直接抛，所以这两条必须被内部单调修正吃掉。
        val segment = buildWatchShapeShaderEffect(
            kind = WatchShapeShaderKind.SEGMENT_BETWEEN,
            width = 100f,
            height = 100f,
            baseColor = Color.WHITE,
            number = { key, fallback ->
                when (key) {
                    "u_1" -> 300f
                    "u_2" -> 20f
                    else -> fallback
                }
            },
            color = { _, fallback -> fallback },
        )
        assertNotNull(segment?.shader)
        val progress = buildWatchShapeShaderEffect(
            kind = WatchShapeShaderKind.PROGRESS_BETWEEN,
            width = 100f,
            height = 100f,
            baseColor = Color.WHITE,
            number = { key, fallback ->
                when (key) {
                    "u_1" -> 90f
                    "u_2" -> 10f
                    else -> fallback
                }
            },
            color = { _, fallback -> fallback },
        )
        assertNotNull(progress?.shader)
    }

    @Test
    fun `uniform defaults match the upstream editor`() {
        // 与 wftools 的 wmShaderDefaults 逐位一致（见该文件注释）。
        assertEquals(
            listOf("u_1" to "ffffff", "u_2" to "000000", "u_3" to "0", "u_4" to "100"),
            watchShapeShaderDefaults(WatchShapeShaderKind.GRADIENT_LINEAR),
        )
        assertEquals(
            listOf("u_1" to "ffffff", "u_2" to "000000", "u_3" to "100"),
            watchShapeShaderDefaults(WatchShapeShaderKind.GRADIENT_RADIAL),
        )
        assertEquals(listOf("u_1" to "0"), watchShapeShaderDefaults(WatchShapeShaderKind.RAINBOW))
        assertEquals(
            listOf("u_1" to "90", "u_2" to "100", "u_3" to "20"),
            watchShapeShaderDefaults(WatchShapeShaderKind.SEGMENT),
        )
        assertEquals(
            listOf("u_1" to "0", "u_2" to "50", "u_3" to "0", "u_4" to "100", "u_5" to "15"),
            watchShapeShaderDefaults(WatchShapeShaderKind.PROGRESS_BETWEEN),
        )
        assertTrue(watchShapeShaderDefaults(WatchShapeShaderKind.NONE).isEmpty())
    }

    @Test
    fun `defaults only ever seed u_1 through u_5`() {
        WatchShapeShaderKind.entries.forEach { kind ->
            watchShapeShaderDefaults(kind).forEach { (key, value) ->
                assertTrue("$kind seeds an unexpected key $key", key in ALLOWED_UNIFORMS)
                assertTrue("$kind seeds a blank value for $key", value.isNotBlank())
            }
        }
    }

    private fun effectFor(
        kind: WatchShapeShaderKind,
        width: Float = 120f,
        height: Float = 80f,
    ): WatchShapePaintEffect? {
        // 求值器照抄渲染器的语义：拿不到属性就用调用方给的缺省值。
        val defaults = watchShapeShaderDefaults(kind).toMap()
        return buildWatchShapeShaderEffect(
            kind = kind,
            width = width,
            height = height,
            baseColor = Color.WHITE,
            number = { key, fallback -> defaults[key]?.toFloatOrNull() ?: fallback },
            color = { key, fallback ->
                defaults[key]?.let { hex ->
                    runCatching { Color.parseColor("#$hex") }.getOrDefault(fallback)
                } ?: fallback
            },
        )
    }

    private companion object {
        val ALLOWED_UNIFORMS = setOf("u_1", "u_2", "u_3", "u_4", "u_5")
    }
}
