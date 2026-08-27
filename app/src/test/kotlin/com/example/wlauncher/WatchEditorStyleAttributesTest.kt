// 包名是 com.flue.launcher 而目录是 com/example/wlauncher —— 与被测的
// WatchEditorActivity.kt 本身完全一致（这个仓里那批 Activity 的路径和包名从来不一致）。
package com.flue.launcher

import com.flue.launcher.watchface.editor.shape.WATCH_SHAPE_ATTRIBUTE_VALUES
import com.flue.launcher.watchface.editor.shape.watchShapeAttributeValue
import com.flue.launcher.watchface.editor.shape.watchShapeKindOf
import com.flue.launcher.watchface.standard.WatchOutlineMode
import com.flue.launcher.watchface.standard.WatchShadowMode
import com.flue.launcher.watchface.standard.WatchShapeShaderKind
import com.flue.launcher.watchface.standard.watchOutlineMode
import com.flue.launcher.watchface.standard.watchShadowMode
import com.flue.launcher.watchface.standard.watchShapeShaderKindOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形状族属性面板的回归测试。
 *
 * 这一套盯的是「面板与渲染器说的是同一件事」。渲染器早就读 `corner_type` / `shader` /
 * `margin` / `end_style` 这些属性，面板以前一个都不给；现在给了，就必须保证：
 *
 * 1. **写出去的字面值渲染器认得**。规范里的枚举是首字母大写的（`Square` /
 *    `Outline` / `GradientLinear`），Flue 自己读的时候 lowercase，所以写成小写在本机
 *    照样画得出来 —— 但别的渲染器是拿原值去拼 `shape_<小写>.png` 这类查表键的，
 *    写错就整层不显示。这类错只在导出后才暴露，本地跑一遍表盘看不出来。
 * 2. **不承诺渲染器不做的事**。`ring` / `progress` 的描边阴影、`progress` 的 shader
 *    在规范里就不支持（`WM_EFFECTS.md` §7），面板放出来只会让人以为自己写错了。
 * 3. **每个属性都有真标签**。漏一条映射的后果是界面上出现「未知属性」。
 */
class WatchEditorStyleAttributesTest {

    private val styledTypes = listOf("shape", "rounded", "progress", "ring")

    @Test
    fun `every editable attribute has a real label`() {
        val unknown = R.string.watch_editor_attribute_unknown
        val gaps = mutableListOf<String>()
        (styledTypes + listOf("text", "image", "image_gif", "video")).forEach { type ->
            editableAttributesFor(type).forEach { attribute ->
                if (attributeLabelRes(type, attribute) == unknown) gaps += "$type.$attribute"
            }
        }
        assertEquals(emptyList<String>(), gaps)
    }

    /**
     * 同名属性在不同图层上是不同的东西，标签必须跟着分流。
     *
     * `ring` 的 `radius` 是端帽倒角（外半径叫 `radius_outer`），`rounded` 的 `radius`
     * 才是圆角半径；`ring` 的 `angle` 是已填充角度，`progress` 的 `angle` 是填充轴向。
     */
    @Test
    fun `same attribute name gets a different label per layer type`() {
        assertEquals(
            R.string.watch_editor_attribute_cap_radius,
            attributeLabelRes("ring", "radius"),
        )
        assertEquals(
            R.string.watch_editor_attribute_radius,
            attributeLabelRes("rounded", "radius"),
        )
        assertEquals(
            R.string.watch_editor_attribute_ring_angle,
            attributeLabelRes("ring", "angle"),
        )
        assertEquals(
            R.string.watch_editor_attribute_progress_angle,
            attributeLabelRes("progress", "angle"),
        )
        // 大小写不敏感：图层类型串在文档里大小写不统一是常态。
        assertEquals(
            R.string.watch_editor_attribute_cap_radius,
            attributeLabelRes("Ring", "radius"),
        )
    }

    @Test
    fun `shape options keep the specification capitalisation and round trip`() {
        val options = attributeEnumOptions("shape", "shape")
        assertNotNull(options)
        assertEquals(WATCH_SHAPE_ATTRIBUTE_VALUES, options!!.map { it.value })
        options.forEach { option ->
            // 写出去的值必须原样回到同一个字形 —— 这才是「别的渲染器也认得」的判据。
            assertEquals(
                "shape=${option.value} 写回后变了",
                option.value,
                watchShapeAttributeValue(watchShapeKindOf(option.value)),
            )
        }
    }

    @Test
    fun `shader options round trip through the renderer parser`() {
        val options = attributeEnumOptions("shape", "shader")
        assertNotNull(options)
        // 11 种 + 一个「无」。
        assertEquals(WatchShapeShaderKind.entries.size, options!!.size)
        options.forEach { option ->
            val kind = watchShapeShaderKindOf(option.value)
            if (option.value.isEmpty()) {
                assertEquals(WatchShapeShaderKind.NONE, kind)
            } else {
                assertFalse("shader=${option.value} 渲染器不认得", kind == WatchShapeShaderKind.NONE)
            }
        }
    }

    @Test
    fun `outline and shadow options round trip through the renderer parser`() {
        val outline = attributeEnumOptions("shape", "outline")!!
        assertEquals(listOf("", "Outline", "Glow"), outline.map { it.value })
        assertEquals(WatchOutlineMode.NONE, watchOutlineMode(""))
        assertEquals(WatchOutlineMode.OUTLINE, watchOutlineMode("Outline"))
        assertEquals(WatchOutlineMode.GLOW, watchOutlineMode("Glow"))

        val shadow = attributeEnumOptions("shape", "shadow")!!
        assertEquals(listOf("", "Drop", "Flat"), shadow.map { it.value })
        assertEquals(WatchShadowMode.NONE, watchShadowMode(""))
        assertEquals(WatchShadowMode.DROP, watchShadowMode("Drop"))
        assertEquals(WatchShadowMode.FLAT, watchShadowMode("Flat"))
    }

    /** `end_style` 在两种图层上取值不同，所以取值表必须按图层类型分流。 */
    @Test
    fun `end style options differ between ring and progress`() {
        val ring = attributeEnumOptions("ring", "end_style")!!.map { it.value }
        assertEquals(listOf("", "butt", "square"), ring)
        val progress = attributeEnumOptions("progress", "end_style")!!.map { it.value }
        assertEquals(listOf("", "round", "flat"), progress)
    }

    @Test
    fun `corner type offers all seventeen values with spec column names`() {
        val options = attributeEnumOptions("rounded", "corner_type")!!
        assertEquals((0..16).map(Int::toString), options.map { it.value })
        // 文案从渲染器那张唯一的圆角表反推，不另抄一份 —— 抄第二遍就会漂。
        assertEquals("0 · —", cornerTypeChipLabel(0))
        assertEquals("1 · TL TR BL BR", cornerTypeChipLabel(1))
        assertEquals("6 · TL TR", cornerTypeChipLabel(6))
        assertEquals("15 · BR", cornerTypeChipLabel(15))
        // 16 与 0 同义，规范里就是这么定的。
        assertEquals("16 · —", cornerTypeChipLabel(16))
    }

    @Test
    fun `non enum attributes have no option table`() {
        assertNull(attributeEnumOptions("progress", "pct_complete"))
        assertNull(attributeEnumOptions("shape", "width"))
        assertNull(attributeEnumOptions("shape", "color"))
    }

    /**
     * 描边 / 阴影只挂在渲染器真的读它们的类型上。
     *
     * `resolveOutline` / `resolveShadow` 的调用点只有 text / 位图文字 / shape / rounded
     * 四处，与 `WM_EFFECTS.md` §7 的支持矩阵一致。
     */
    @Test
    fun `outline shadow and shader are only offered where the renderer reads them`() {
        listOf("shape", "rounded").forEach { type ->
            val attributes = editableAttributesFor(type)
            assertTrue("$type 应有描边", "outline" in attributes)
            assertTrue("$type 应有阴影", "shadow" in attributes)
            assertTrue("$type 应有着色器", "shader" in attributes)
            assertTrue("$type 应有 u_5", "u_5" in attributes)
        }
        listOf("progress", "ring").forEach { type ->
            val attributes = editableAttributesFor(type)
            assertFalse("$type 不该有描边（渲染器不读）", "outline" in attributes)
            assertFalse("$type 不该有阴影（渲染器不读）", "shadow" in attributes)
            // progress 按规范就不支持 shader；ring 规范支持但 Flue 渲染器还没做。
            assertFalse("$type 不该有着色器", "shader" in attributes)
        }
    }

    /** 进度条那几个「找不到开关」的属性必须都在面板上。 */
    @Test
    fun `progress exposes the attributes people cannot find`() {
        val attributes = editableAttributesFor("progress")
        assertTrue(
            attributes.containsAll(
                listOf(
                    "margin",
                    "corner_type",
                    "radius",
                    "end_style",
                    "angle",
                    "color2",
                    "color3",
                    "color4",
                    "outside_opacity",
                ),
            ),
        )
        // margin 要排在 radius 之前：底槽开关就在这一格，得让人先看到。
        assertTrue(attributes.indexOf("margin") < attributes.indexOf("radius"))
    }

    /** `ring` 的外半径是 `radius_outer`，别再自造 `width`。 */
    @Test
    fun `ring exposes spec attribute names`() {
        val attributes = editableAttributesFor("ring")
        assertTrue(
            attributes.containsAll(
                listOf("radius_outer", "radius_inner", "angle_total", "angle", "is_clockwise"),
            ),
        )
    }

    /**
     * 三个输入槽位互斥。
     *
     * 一个属性只有一个「下拉」位置：枚举面板、取色器、表达式插入面板三者只能占一个。
     * 重叠的后果是其中一个入口永远点不到。
     */
    @Test
    fun `picker slots are mutually exclusive`() {
        assertTrue((COLOR_ATTRIBUTES intersect ENUM_ATTRIBUTE_NAMES).isEmpty())
        assertTrue((WATCH_EXPRESSION_ATTRIBUTES intersect COLOR_ATTRIBUTES).isEmpty())
        assertTrue((WATCH_EXPRESSION_ATTRIBUTES intersect ENUM_ATTRIBUTE_NAMES).isEmpty())
    }

    /**
     * 每个枚举属性都必须真的有取值表，否则它的下拉箭头点开是空的。
     *
     * [ENUM_ATTRIBUTE_NAMES] 与 [attributeEnumOptions] 是两份独立数据，这条盯它们不脱节。
     */
    @Test
    fun `every enum attribute name has an option table on some layer type`() {
        val gaps = ENUM_ATTRIBUTE_NAMES.filter { name ->
            styledTypes.plus("text").all { type -> attributeEnumOptions(type, name) == null }
        }
        assertEquals(emptyList<String>(), gaps)
    }

    /**
     * 最常被写成表达式的两个属性不能被锁上数字键盘。
     *
     * `pct_complete="{hp}"`、`angle="{dh}*30"` 是这份格式里最普通的写法，数字键盘
     * 打不出花括号。
     */
    @Test
    fun `the two most tag driven attributes keep a text keyboard`() {
        assertFalse("pct_complete" in NUMERIC_ATTRIBUTES)
        assertFalse("angle" in NUMERIC_ATTRIBUTES)
        assertTrue("pct_complete" in WATCH_EXPRESSION_ATTRIBUTES)
        assertTrue("angle" in WATCH_EXPRESSION_ATTRIBUTES)
        // 纯几何的那些反过来：给数字键盘。
        assertTrue("width" in NUMERIC_ATTRIBUTES)
        assertTrue("corner_type" in NUMERIC_ATTRIBUTES)
        // u_1 / u_2 在两种渐变里是十六进制颜色，不能锁数字键盘。
        assertFalse("u_1" in NUMERIC_ATTRIBUTES)
        assertFalse("u_2" in NUMERIC_ATTRIBUTES)
        assertTrue("u_3" in NUMERIC_ATTRIBUTES)
    }

    /**
     * 选着色器要把 `u_*` 一起铺上，切回「无」要把它们清掉。
     *
     * 不铺缺省值的话选了 `GradientLinear` 画面毫无变化，用户会以为着色器没生效；
     * 不清掉的话残留的 uniform 会被下一个着色器重新解释成别的意思。
     */
    @Test
    fun `choosing a shader seeds its uniforms and clearing it wipes them`() {
        val linear = watchShaderCompanionAttributes("GradientLinear")
        assertEquals(setOf("u_1", "u_2", "u_3", "u_4", "u_5"), linear.keys)
        assertEquals("ffffff", linear["u_1"])
        assertEquals("000000", linear["u_2"])
        // GradientLinear 只用到 u_1..u_4，第五个必须显式清空而不是留着。
        assertNull(linear["u_5"])

        val cleared = watchShaderCompanionAttributes("")
        assertEquals(setOf("u_1", "u_2", "u_3", "u_4", "u_5"), cleared.keys)
        assertTrue(cleared.values.all { it == null })
    }

    /**
     * 新插进来的进度条要长得像进度条。
     *
     * `margin` 是底槽与边框的总开关（规范：**只有 margin > 0 才画 color3 轨道与
     * color4 边框**）。以前默认不写 margin，插出来是一条没有底槽的裸色块。
     */
    @Test
    fun `a fresh progress bar has a visible track`() {
        val progress = defaultAttributes("progress")
        val margin = progress["margin"]?.toFloatOrNull()
        assertNotNull("progress 必须写 margin，否则没有底槽", margin)
        assertTrue("margin 必须大于 0 才画底槽", margin!! > 0f)
        assertTrue("有 margin 就要有底槽色", !progress["color3"].isNullOrBlank())
        assertTrue("有 margin 就要有边框色", !progress["color4"].isNullOrBlank())
        // radius 要显式写出来，别留空靠规范缺省 50：留空时面板那一格是空的，
        // 生效值却是 50，用户改不了自己看不见的数。写出来的值还得在钳制范围内
        // （渲染器把它夹到 min(w, h) / 2），否则写进去的数和画出来的形状不一致。
        val radius = progress["radius"]?.toFloatOrNull()
        val height = progress["height"]?.toFloatOrNull()
        assertNotNull("progress 必须显式写 radius", radius)
        assertNotNull(height)
        assertTrue("radius 必须大于 0", radius!! > 0f)
        assertTrue("radius 不能超过钳制上限", radius <= height!! / 2f)
        assertEquals("1", progress["corner_type"])
    }
}
