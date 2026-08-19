package com.flue.launcher

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchEditorThumbnailPolicyTest {
    @Test
    fun failedVideoFrameDecodeStillProducesAnOpaqueBlackThumbnail() {
        val bitmap = watchEditorBlackVideoThumbnail(9)
        assertEquals(9, bitmap.width)
        assertEquals(9, bitmap.height)
        assertEquals(Color.BLACK, bitmap.getPixel(4, 4))
    }
}
