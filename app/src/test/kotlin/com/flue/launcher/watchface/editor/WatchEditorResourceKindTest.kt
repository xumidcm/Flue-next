package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 资源大类判定。
 *
 * 这条测试盯的是老师报的「表盘编辑器资源管理还是没法预览到图片视频字体缩略图」——
 * 缩略图能不能出，第一步就是这个分类判对没有。
 *
 * 判据刻意用**扩展名**而不是所在目录：老表盘把素材直接堆在包根目录是常见写法
 *（见 `draftRelativeAssetPath`），只看 `images/` 会把这些全归成「其它」、一律没有缩略图。
 */
class WatchEditorResourceKindTest {

    private fun kindOf(path: String): WatchEditorResourceKind =
        WatchEditorResourceEntry(relativePath = path, bytes = 1L, referenced = true).kind

    @Test
    fun `images are recognised in the conventional directory and at the archive root`() {
        assertEquals(WatchEditorResourceKind.IMAGE, kindOf("images/hand.png"))
        assertEquals(WatchEditorResourceKind.IMAGE, kindOf("images/bg.JPG"))
        assertEquals(WatchEditorResourceKind.IMAGE, kindOf("images/anim.webp"))
        // 根目录写法：老表盘常见，不能因为没有 images/ 前缀就丢掉缩略图。
        assertEquals(WatchEditorResourceKind.IMAGE, kindOf("loose.png"))
    }

    @Test
    fun `videos cover the containers the importer accepts`() {
        assertEquals(WatchEditorResourceKind.VIDEO, kindOf("videos/clip.mp4"))
        assertEquals(WatchEditorResourceKind.VIDEO, kindOf("videos/clip.m4v"))
        assertEquals(WatchEditorResourceKind.VIDEO, kindOf("videos/clip.MOV"))
    }

    /** 矢量字体能用 Typeface 加载出字形小样；位图字体是文本描述文件，出不了图。 */
    @Test
    fun `vector fonts and bitmap font descriptors are separate kinds`() {
        assertEquals(WatchEditorResourceKind.FONT, kindOf("fonts/digital.ttf"))
        assertEquals(WatchEditorResourceKind.FONT, kindOf("fonts/digital.otf"))
        assertEquals(WatchEditorResourceKind.BITMAP_FONT, kindOf("fonts_bm/digital.fnt"))
    }

    @Test
    fun `scripts are their own kind`() {
        assertEquals(WatchEditorResourceKind.SCRIPT, kindOf("scripts/script.txt"))
        assertEquals(WatchEditorResourceKind.SCRIPT, kindOf("scripts/helper.lua"))
    }

    /**
     * 受保护表盘的加密孪生图**不算**图片。
     *
     * `.ppng` 是被 WatchmakerProtection 编码过的字节流，BitmapFactory 解不开。归成 IMAGE
     * 只会让每一行都先白跑一次解码再退回兜底图标。
     */
    @Test
    fun `encrypted protected twins are not treated as decodable images`() {
        assertEquals(WatchEditorResourceKind.OTHER, kindOf("images/face.ppng"))
    }

    @Test
    fun `unknown and extensionless files fall back to other`() {
        assertEquals(WatchEditorResourceKind.OTHER, kindOf("data.bin"))
        assertEquals(WatchEditorResourceKind.OTHER, kindOf("README"))
    }
}
