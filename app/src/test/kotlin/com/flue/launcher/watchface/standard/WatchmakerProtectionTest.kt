package com.flue.launcher.watchface.standard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class WatchmakerProtectionTest {
    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    @Test
    fun protectedXmlRoundTripsWithWatchmakerAlphabetSwap() {
        val source = "<Watch name=\"Protected\" path=\"images/face.png\"/>"
            .toByteArray(StandardCharsets.UTF_8)

        val encoded = WatchmakerProtection.encodeXml(source)

        assertFalse(encoded.contentEquals(source))
        val decoded = WatchmakerProtection.openDecodedXml(ByteArrayInputStream(encoded)).use {
            it.readBytes()
        }
        assertArrayEquals(source, decoded)
        assertTrue(encoded.any { it == 'D'.code.toByte() || it == 'g'.code.toByte() })
    }

    @Test
    fun protectedImageRoundTripsWithRepeatingWatchmakerKey() {
        val source = pngHeader + byteArrayOf(1, 2, 3, 4, 5, 6)

        val encoded = WatchmakerProtection.protectAssetBytes(source)

        assertFalse(encoded.contentEquals(source))
        assertArrayEquals(source, WatchmakerProtection.unprotectAssetBytes(encoded))
    }
}
