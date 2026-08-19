// 包名是 com.flue.launcher 而目录是 com/example/wlauncher —— 与被测的
// WatchEditorActivity.kt 本身完全一致（这个仓里那批 Activity 的路径和包名从来不一致）。
// 测试跟着被测文件放，比跟着包名放更好找。
package com.flue.launcher

import com.flue.launcher.watchface.editor.WatchFormatVersion
import com.flue.launcher.watchface.store.WatchStoreUploadRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器「加图层」按钮插出来的图层，必须一插进来就满足商店的必填属性。
 *
 * 这条测试的由来：默认属性表原先给 `ring` 写 `radius` / `width`、给 `progress` 写
 * `radius` / `width` / `value` —— 那不是 WM 1.0 的属性名，我们自己的渲染器读的都是
 * 规范名（`radius_outer` / `pct_complete`），商店的 `LAYER_REQUIRED` 也按规范名查。
 * 于是随手插一个环就带着四条 missing required attribute 上传，被自动转待审。
 *
 * 属性名这种错在本地跑一遍表盘是**看不出来的**：渲染器取不到值就吃兜底默认，画面
 * 照样有东西。只有对着规范逐条比才发现，所以必须有这条测试盯着。
 */
class WatchEditorDefaultAttributesTest {

    /**
     * 选素材之前天然为空的属性。
     *
     * `image` / `image_gif` / `video` 的 `path` 要等用户挑文件；这里**不能**编一个假值
     * 去骗过必填检查，也不能顺手补 width / height —— `WatchEditorSession`
     * 靠「没有尺寸」判断该按素材比例自动适配，补了默认值导入的图就被钉死了。
     * 这类「图层还没做完」由上传前的预检提示，不由默认属性表遮掉。
     */
    private val pendingUntilResourcePicked = mapOf(
        "image" to setOf("path", "width", "height"),
        "image_gif" to setOf("path", "width", "height"),
        "video" to setOf("path"),
    )

    @Test
    fun `every insertable layer type satisfies the store required attributes`() {
        val types = WatchFormatVersion.entries
            .flatMap { insertableLayerTypes(it) }
            .map { it.first }
            .distinct()
        assertTrue("可插入类型列表读不到了", types.isNotEmpty())

        val gaps = mutableListOf<String>()
        types.forEach { type ->
            val required = WatchStoreUploadRules.requiredAttributes[type].orEmpty()
            val defaults = defaultAttributes(type)
            val excused = pendingUntilResourcePicked[type].orEmpty()
            required.forEach { attribute ->
                if (attribute in excused) return@forEach
                val value = defaults[attribute]
                // 空字符串在上游判据里等于缺失（`undefined || === ''`）。
                if (value.isNullOrBlank()) gaps += "$type 缺 $attribute"
            }
        }
        assertEquals(emptyList<String>(), gaps)
    }

    @Test
    fun `ring and progress use the spec attribute names not our own invention`() {
        val ring = defaultAttributes("ring")
        // 规范名在（§7.3）。
        assertTrue(ring.keys.containsAll(setOf("radius_outer", "radius_inner", "angle_total", "angle")))
        // 旧的自造名不能再出现：留着会让渲染器与商店各读一半。
        assertTrue("ring 不该再写 width", "width" !in ring)

        val progress = defaultAttributes("progress")
        assertTrue(progress.keys.containsAll(setOf("width", "height", "pct_complete")))
        assertTrue("progress 不该再写 value", "value" !in progress)
    }

    @Test
    fun `a fresh ring is visible instead of an empty track`() {
        val ring = defaultAttributes("ring")
        // angle 是**已填充角度**：给 0 会插出一个看不见的空环，用户会以为没插上。
        assertEquals(ring["angle_total"], ring["angle"])
    }

    @Test
    fun `text carries a font because an empty one counts as missing`() {
        val font = defaultAttributes("text")["font"]
        assertTrue("text 必须带 font", !font.isNullOrBlank())
    }

    /**
     * 颜色必须是规范的 `hex RRGGBB`：小写、6 位、不带 `#`。
     *
     * 之前这里写的是 Android 风格的 `#FFFFFFFF` / `#FF4A90E2`。本地看不出问题 ——
     * Flue 的 `normalizeWatchColorLiteral` 会剥掉 `#` 并接受 8 位；官方
     * WatchMaker / CloX 不会，那边解析失败就吃兜底色。同一份表盘在 Flue 里对、
     * 换个渲染器就变色，是最难被作者自己发现的一类错。
     */
    @Test
    fun `every default color is a bare lowercase six digit hex`() {
        val offenders = mutableListOf<String>()
        WatchFormatVersion.entries
            .flatMap { insertableLayerTypes(it) }
            .map { it.first }
            .distinct()
            .forEach { type ->
                defaultAttributes(type)
                    .filterKeys { it == "color" || it.startsWith("color") }
                    .forEach { (name, value) ->
                        if (!Regex("^[0-9a-f]{6}$").matches(value)) offenders += "$type.$name=$value"
                    }
            }
        assertEquals(emptyList<String>(), offenders)
    }

    /** 没选素材时 `path` 要整条缺席，而不是留一个空串。 */
    @Test
    fun `an unpicked resource path is absent rather than empty`() {
        listOf("image", "image_gif", "video").forEach { type ->
            assertTrue("$type 不该带 path", "path" !in defaultAttributes(type))
        }
    }

    /**
     * `shape` 的值是首字母大写的枚举，2.0 还要额外写遮罩 `path`。
     *
     * 1.0 §7.11 的合法值是 `Square` / `Circle` / `Triangle` / `Star` / `Pentagon` /
     * `Hexagon` / `Heart`，官方渲染器拿这个名字去查内置遮罩 `shape_<小写>.png`。
     * 这里以前写小写 `circle`：Flue 自己会 lowercase 所以照样画得出来，别的渲染器
     * 按原样查表查不到，图层直接不显示 —— 又是一个只在导出后才暴露的错。
     * 2.0 §11 规定 shape 图层同时写 `path="shape_<小写名>.png"`。
     */
    @Test
    fun `shape uses the capitalised enum and only format two writes the mask path`() {
        val v1 = defaultAttributes("shape", WatchFormatVersion.V1)
        assertEquals("Circle", v1["shape"])
        assertTrue("1.0 不该写遮罩 path", "path" !in v1)

        val v2 = defaultAttributes("shape", WatchFormatVersion.V2)
        assertEquals("Circle", v2["shape"])
        assertEquals("shape_circle.png", v2["path"])
    }
}
