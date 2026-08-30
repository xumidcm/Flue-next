package com.flue.launcher.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreWebCapabilityTest {
    @Test
    fun `webview requires chrome major version 80 or newer`() {
        assertFalse(flueWebViewVersionUsable("79.0.3945.136"))
        assertTrue(flueWebViewVersionUsable("80.0.3987.99"))
        assertTrue(flueWebViewVersionUsable("126.0.6478.122"))
    }

    @Test
    fun `unknown or malformed webview versions are rejected`() {
        assertFalse(flueWebViewVersionUsable(null))
        assertFalse(flueWebViewVersionUsable(""))
        assertFalse(flueWebViewVersionUsable("version-126"))
        assertNull(flueWebViewMajorVersionOrNull("version-126"))
        assertEquals(126, flueWebViewMajorVersionOrNull("126.0.1"))
    }

    /**
     * 阈值本身也钉住：80 是「`?.` / `??` 可用」的下限，抬它要连着改 KDoc 里的理由，
     * 不该有人顺手调个数就过。
     */
    @Test
    fun `threshold is the optional chaining support line`() {
        assertEquals(80, FLUE_WEB_VIEW_MINIMUM_MAJOR_VERSION)
        // 主版本号带后缀（部分 ROM 会加架构名）与纯主版本号都要认。
        assertEquals(80, flueWebViewMajorVersionOrNull("80.0.3987.99-arm64"))
        assertEquals(66, flueWebViewMajorVersionOrNull("66"))
        assertFalse(flueWebViewVersionUsable("66"))
        // 老 AOSP WebView 的 "4.0" 这种也要判不可用，不能被短版本号骗过去。
        assertFalse(flueWebViewVersionUsable("4.0"))
    }

    /**
     * 只有**未捕获**的解析期 SyntaxError 才该把人踢去系统浏览器：站点里被 try/catch 住的
     * JSON.parse 失败也会打同一个词，那种页面照常工作。
     */
    @Test
    fun `only uncaught syntax errors count as fatal`() {
        assertTrue(
            flueWebConsoleIndicatesFatalSyntaxError(
                "Uncaught SyntaxError: Unexpected token '.'",
            ),
        )
        assertTrue(flueWebConsoleIndicatesFatalSyntaxError("SyntaxError: Unexpected token '?'"))
        assertFalse(
            flueWebConsoleIndicatesFatalSyntaxError(
                "handled SyntaxError while parsing cached payload",
            ),
        )
        assertFalse(flueWebConsoleIndicatesFatalSyntaxError("Failed to load resource: 404"))
        assertFalse(flueWebConsoleIndicatesFatalSyntaxError(null))
        assertFalse(flueWebConsoleIndicatesFatalSyntaxError(""))
    }
}
