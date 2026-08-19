package com.flue.launcher.applist.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 应用列表背景配置。
 *
 * 用 Robolectric 是因为 `AppListBackgroundJson` 走 `org.json` —— 项目的
 * `unitTests.isReturnDefaultValues = true` 让 android.jar 里的 `org.json` 桩返回 null，
 * 链式 `put` 直接 NPE。项目里凡是碰 `org.json` 的测试都是这个跑法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class AppListBackgroundConfigTest {

    @Test
    fun `default is the untouched system solid colour`() {
        // 默认必须与改动前逐位相同：这个功能是加选项，不是改默认观感。
        val default = AppListBackgroundConfig.Default
        assertEquals(AppListBackgroundMode.SOLID_SYSTEM, default.mode)
        assertEquals(AppListBackgroundMode.SOLID_SYSTEM, default.effectiveMode)
        assertTrue(default.imagePath == null)
    }

    @Test
    fun `only the two image modes need a bitmap`() {
        assertTrue(AppListBackgroundMode.WATCH_FACE.usesImage)
        assertTrue(AppListBackgroundMode.CUSTOM_IMAGE.usesImage)
        assertTrue(!AppListBackgroundMode.SOLID_SYSTEM.usesImage)
        assertTrue(!AppListBackgroundMode.SOLID_CUSTOM.usesImage)
    }

    @Test
    fun `custom image without a picked file falls back to the system solid colour`() {
        // 渲染侧因此不用各自判「图没了怎么办」，也绝不会出现透明背景或黑闪。
        val config = AppListBackgroundConfig(mode = AppListBackgroundMode.CUSTOM_IMAGE)
        assertEquals(AppListBackgroundMode.SOLID_SYSTEM, config.effectiveMode)
        assertEquals(
            AppListBackgroundMode.CUSTOM_IMAGE,
            config.copy(imagePath = "/data/x.png").effectiveMode,
        )
        // 空白路径与 null 等价。
        assertEquals(
            AppListBackgroundMode.SOLID_SYSTEM,
            config.copy(imagePath = "   ").sanitized().effectiveMode,
        )
    }

    @Test
    fun `sanitize clamps percentages and forces an opaque custom colour`() {
        val sanitized = AppListBackgroundConfig(
            blurPercent = 500,
            scrimPercent = -20,
            customColorArgb = 0x0012AB34,
        ).sanitized()
        assertEquals(100, sanitized.blurPercent)
        assertEquals(0, sanitized.scrimPercent)
        // 半透明的「自定义纯色」会漏出下面的表盘，那不是这一档的语义。
        assertEquals(0xFF.toLong(), (sanitized.customColorArgb ushr 24).toLong())
        assertEquals(0x12AB34, sanitized.customColorArgb and 0xFFFFFF)
        sanitized.requireValid()
    }

    @Test
    fun `sanitize trims the image path and drops blanks`() {
        assertEquals(
            "/data/pic.png",
            AppListBackgroundConfig(imagePath = "  /data/pic.png  ").sanitized().imagePath,
        )
        assertNull(AppListBackgroundConfig(imagePath = "   ").sanitized().imagePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `require rejects an out of range blur`() {
        AppListBackgroundConfig(blurPercent = 101).requireValid()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `require rejects an out of range scrim`() {
        AppListBackgroundConfig(scrimPercent = -1).requireValid()
    }

    @Test
    fun `blur maps zero to no blur and one hundred to the cap`() {
        assertEquals(0f, appListBackgroundBlurRadiusDp(0), 0f)
        assertEquals(APP_LIST_BACKGROUND_MAX_BLUR_DP, appListBackgroundBlurRadiusDp(100), 0f)
        assertEquals(APP_LIST_BACKGROUND_MAX_BLUR_DP / 2f, appListBackgroundBlurRadiusDp(50), 1e-4f)
        // 越界输入被夹住而不是外推。
        assertEquals(0f, appListBackgroundBlurRadiusDp(-40), 0f)
        assertEquals(APP_LIST_BACKGROUND_MAX_BLUR_DP, appListBackgroundBlurRadiusDp(4000), 0f)
    }

    @Test
    fun `scrim maps zero to transparent and one hundred to the cap`() {
        assertEquals(0f, appListBackgroundScrimAlpha(0), 0f)
        assertEquals(APP_LIST_BACKGROUND_MAX_SCRIM_ALPHA, appListBackgroundScrimAlpha(100), 0f)
        // 遮罩上限**不到** 1f：留一点底图可辨，否则跟纯色档没区别。
        assertTrue(APP_LIST_BACKGROUND_MAX_SCRIM_ALPHA < 1f)
    }

    @Test
    fun `scrim direction follows the theme`() {
        assertEquals(0x000000, appListBackgroundScrimRgb(darkMode = true))
        assertEquals(0xFFFFFF, appListBackgroundScrimRgb(darkMode = false))
    }

    @Test
    fun `blur buckets are monotonic and start at zero`() {
        var previous = -1f
        (0..100 step 5).forEach { percent ->
            val bucket = appListBackgroundBlurBucketDp(percent)
            assertTrue("percent $percent went backwards", bucket >= previous)
            previous = bucket
        }
        assertEquals(0f, appListBackgroundBlurBucketDp(0), 0f)
        // 极小的百分比落进「不糊」那一桶，避免为了 0.3dp 去烘焙一整张位图。
        assertEquals(0f, appListBackgroundBlurBucketDp(1), 0f)
        assertTrue(appListBackgroundBlurBucketDp(100) > 0f)
    }

    @Test
    fun `legacy bake radius follows the slider instead of being hardcoded`() {
        // Android 12 以下走预烘焙，原来那条路是写死配方（降采样 /12、3 轮、半径 6）——
        // 不接映射的话那条滑块在旧机上只有开和关两档。
        assertEquals(0, appListBackgroundLegacyBlurRadius(0))
        assertTrue(appListBackgroundLegacyBlurRadius(100) > appListBackgroundLegacyBlurRadius(50))
        assertTrue(appListBackgroundLegacyBlurRadius(50) > appListBackgroundLegacyBlurRadius(20))
        // 半径必须落在 boxBlur 能接受的区间里，不能是 0 或者夸张的大数。
        (1..100).forEach { percent ->
            val radius = appListBackgroundLegacyBlurRadius(percent)
            assertTrue("percent $percent gave radius $radius", radius in 0..8)
        }
    }

    @Test
    fun `legacy bake radius is monotonic and skips imperceptible amounts`() {
        var previous = -1
        (0..100).forEach { percent ->
            val radius = appListBackgroundLegacyBlurRadius(percent)
            assertTrue("percent $percent went backwards", radius >= previous)
            previous = radius
        }
        // 极小的百分比不值得为它跑一次全屏卷积。
        assertEquals(0, appListBackgroundLegacyBlurRadius(1))
    }

    @Test
    fun `json round trips every mode`() {
        AppListBackgroundMode.entries.forEach { mode ->
            val config = AppListBackgroundConfig(
                mode = mode,
                customColorArgb = 0xFF3482FF.toInt(),
                blurPercent = 71,
                scrimPercent = 12,
                imagePath = "/data/user/0/com.flue.launcher/files/app_list_bg.png",
            )
            val decoded = AppListBackgroundJson.decode(AppListBackgroundJson.encode(config))
            assertEquals("mode $mode", config.sanitized(), decoded)
        }
    }

    @Test
    fun `json omits a missing image path rather than writing an empty string`() {
        val encoded = AppListBackgroundJson.encode(AppListBackgroundConfig.Default)
        assertTrue(!encoded.contains("imagePath"))
        assertNull(AppListBackgroundJson.decode(encoded).imagePath)
    }

    @Test
    fun `decode rejects a wrong schema an unknown mode and a non integer`() {
        listOf(
            """{"schemaVersion":99,"mode":"solid_system","customColorArgb":-1,"blurPercent":0,"scrimPercent":0}""",
            """{"schemaVersion":1,"mode":"rainbow","customColorArgb":-1,"blurPercent":0,"scrimPercent":0}""",
            """{"schemaVersion":1,"mode":"solid_system","customColorArgb":-1,"blurPercent":"a lot","scrimPercent":0}""",
            """{"schemaVersion":1,"mode":"solid_system","customColorArgb":-1,"scrimPercent":0}""",
        ).forEach { payload ->
            val failed = runCatching { AppListBackgroundJson.decode(payload) }.isFailure
            assertTrue("should have rejected: $payload", failed)
        }
    }

    @Test
    fun `decodeOrDefault never throws`() {
        assertEquals(AppListBackgroundConfig.Default, AppListBackgroundJson.decodeOrDefault(null))
        assertEquals(AppListBackgroundConfig.Default, AppListBackgroundJson.decodeOrDefault(""))
        assertEquals(
            AppListBackgroundConfig.Default,
            AppListBackgroundJson.decodeOrDefault("not json at all"),
        )
        assertEquals(
            AppListBackgroundConfig.Default,
            AppListBackgroundJson.decodeOrDefault("{\"schemaVersion\":42}"),
        )
    }

    @Test
    fun `decode rejects an oversized document`() {
        val payload = "{\"schemaVersion\":1,\"mode\":\"solid_system\"," +
            "\"customColorArgb\":-1,\"blurPercent\":0,\"scrimPercent\":0,\"pad\":\"" +
            "x".repeat(9_000) + "\"}"
        assertTrue(runCatching { AppListBackgroundJson.decode(payload) }.isFailure)
    }

    @Test
    fun `blur and scrim are independent settings`() {
        // 两条滑块必须各自独立：拖模糊不该动遮罩，反之亦然。
        val base = AppListBackgroundConfig(blurPercent = 10, scrimPercent = 90)
        assertNotEquals(base.blurPercent, base.scrimPercent)
        val moreBlur = base.copy(blurPercent = 80).sanitized()
        assertEquals(90, moreBlur.scrimPercent)
    }
}
