package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `features` 重算的判据。
 *
 * 这个字段是市场和启动器唯一的「这块表盘用了什么」索引。编辑器不重算它，导出的包
 * 就带着上一位作者的特性表 —— 加了天气搜不到、删干净心率还挂着心率标签。规范
 * （`WM_STUDIO_2.0_EDITOR.md` §2.3、`WM_FORMAT_2.0.md` §2.2）明确它每次导出都覆盖。
 */
class WatchFeatureDetectorTest {

    @Test
    fun `tags inside expressions still count`() {
        // 指针角度写在表达式里，不是整个花括号一个标签。
        val features = WatchFeatureDetector.detect("""<Layer type="image" rotation="{drss*6}"/>""")
        assertEquals(setOf(WatchFeature.ANALOG), features)
    }

    @Test
    fun `minute and second tags are not a feature`() {
        // {dm}/{ds} 只是时间零件，不是官方特性表里的一项；标成 Date 会让日期筛选变噪音。
        assertEquals(
            emptySet<WatchFeature>(),
            WatchFeatureDetector.detect("""<Layer type="text" text="{dm}:{ds}"/>"""),
        )
    }

    /** 最容易抄错的一组：分钟的个位是 {dmo}，月份才是 {dn}。 */
    @Test
    fun `minute digit tags are never mistaken for a date`() {
        assertEquals(
            emptySet<WatchFeature>(),
            WatchFeatureDetector.detect("""<Layer text="{dmo}{dmt}{dmat}"/>"""),
        )
        assertEquals(
            setOf(WatchFeature.DATE),
            WatchFeatureDetector.detect("""<Layer text="{dn} {dnnn} {ddw_1}"/>"""),
        )
    }

    @Test
    fun `twelve hour is dropped when both clock modes appear`() {
        val features = WatchFeatureDetector.detect("""<Layer text="{dh}"/><Layer text="{dh23}"/>""")
        assertTrue(WatchFeature.TIME_24 in features)
        assertTrue("同时出现时必须丢掉 12", WatchFeature.TIME_12 !in features)
    }

    @Test
    fun `moon phase does not fall into the weather bucket`() {
        assertEquals(setOf(WatchFeature.MOON), WatchFeatureDetector.detect("""<Layer text="{wmp}"/>"""))
        assertEquals(setOf(WatchFeature.WEATHER), WatchFeatureDetector.detect("""<Layer text="{wt}{wf2hh}"/>"""))
    }

    @Test
    fun `compass and health share the s prefix without colliding`() {
        assertEquals(setOf(WatchFeature.COMPASS), WatchFeatureDetector.detect("""<Layer text="{scb}"/>"""))
        assertEquals(setOf(WatchFeature.HEALTH), WatchFeatureDetector.detect("""<Layer text="{scal}"/>"""))
        assertEquals(setOf(WatchFeature.HEART_RATE), WatchFeatureDetector.detect("""<Layer text="{shr}"/>"""))
    }

    @Test
    fun `structural constructs are detected without tags`() {
        val features = WatchFeatureDetector.detect(
            """<Layer type="image_gif" tap_action="w_music" gyro="12"/><Sound path="a.mp3"/>""",
        )
        assertEquals(
            setOf(WatchFeature.GIF, WatchFeature.TAP, WatchFeature.GYRO, WatchFeature.SOUND),
            features,
        )
    }

    /** `gyro="0"` 是「关闭」，不能算用到陀螺仪。 */
    @Test
    fun `a disabled gyro attribute is not a gyro feature`() {
        assertEquals(emptySet<WatchFeature>(), WatchFeatureDetector.detect("""<Layer gyro="0"/>"""))
        assertEquals(emptySet<WatchFeature>(), WatchFeatureDetector.detect("""<Layer gyro="0.0"/>"""))
    }

    @Test
    fun `formatting follows the version specific vocabulary`() {
        val features = setOf(WatchFeature.TIME_24, WatchFeature.ANALOG, WatchFeature.WEATHER, WatchFeature.GIF)
        // 2.0：短代码，字母序。
        assertEquals("24,an,gif,w", WatchFeatureDetector.format(features, WatchFormatVersion.V2))
        // 1.0：人类可读名，同样字母序。
        assertEquals(
            "24h Time,Analog,GIF,Weather",
            WatchFeatureDetector.format(features, WatchFormatVersion.V1),
        )
    }
}
