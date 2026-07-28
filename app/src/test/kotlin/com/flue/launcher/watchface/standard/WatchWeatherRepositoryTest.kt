package com.flue.launcher.watchface.standard

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchWeatherRepositoryTest {
    @Test
    fun boundedUtf8ReaderAcceptsAResponseAtTheExactByteLimit() {
        val expected = "上海 21℃"
        val bytes = expected.toByteArray(Charsets.UTF_8)

        assertEquals(
            expected,
            readBoundedWeatherUtf8(ByteArrayInputStream(bytes), bytes.size),
        )
    }

    @Test
    fun boundedUtf8ReaderRejectsOversizeWithoutDrainingTheResponse() {
        val input = TrackingChunkedInputStream(
            payload = ByteArray(64 * 1024) { 'x'.code.toByte() },
            chunkSize = 3,
        )

        assertNull(readBoundedWeatherUtf8(input, maxBytes = 5))
        assertEquals(6, input.bytesRead)
        assertTrue(input.bytesRead < input.payloadSize)
    }

    @Test
    fun boundedUtf8ReaderRejectsInvalidLimitsWithoutReading() {
        val input = TrackingChunkedInputStream("weather".toByteArray(), chunkSize = 2)

        assertNull(readBoundedWeatherUtf8(input, maxBytes = 0))
        assertEquals(0, input.bytesRead)
    }

    private class TrackingChunkedInputStream(
        private val payload: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        var bytesRead: Int = 0
            private set

        val payloadSize: Int get() = payload.size

        override fun read(): Int {
            if (bytesRead >= payload.size) return -1
            return payload[bytesRead++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= payload.size) return -1
            val count = minOf(chunkSize, length, payload.size - bytesRead)
            payload.copyInto(buffer, destinationOffset = offset, startIndex = bytesRead, endIndex = bytesRead + count)
            bytesRead += count
            return count
        }
    }
}
