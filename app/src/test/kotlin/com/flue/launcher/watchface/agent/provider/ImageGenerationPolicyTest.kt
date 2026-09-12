package com.flue.launcher.watchface.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGenerationPolicyTest {
    @Test
    fun `watch face policy picks landscape size and transparent background for assets`() {
        val options = ImageGenerationPolicy.forWatchFace(width = 390, height = 450, transparentAsset = true)
        assertEquals("1024x1536", options.size)
        assertEquals("transparent", options.background)
        assertNull(options.quality)
    }

    @Test
    fun `unknown image intent omits optional provider parameters`() {
        val options = ImageGenerationPolicy.forWatchFace(width = 512, height = 512, transparentAsset = false)
        assertNull(options.size)
        assertNull(options.background)
        assertNull(options.quality)
    }
}
