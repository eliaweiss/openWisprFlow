package app.openwisprflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @Test
    fun headerDescribesSixteenBitMonoPcm() {
        val header = ByteBuffer.wrap(WavWriter.header(16_000, 32_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", ascii(header, 0))
        assertEquals(36 + 32_000, header.getInt(4))
        assertEquals("WAVE", ascii(header, 8))
        assertEquals("fmt ", ascii(header, 12))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28)) // byte rate
        assertEquals(2, header.getShort(32).toInt()) // block align
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", ascii(header, 36))
        assertEquals(32_000, header.getInt(40))
    }

    @Test
    fun writesSamplesLittleEndianAfterTheHeader() {
        val file = File.createTempFile("take", ".wav")
        try {
            WavWriter(file, 16_000).use { it.write(shortArrayOf(1, -2, 0x1234, 99), 3) }
            val bytes = file.readBytes()
            assertEquals(WavWriter.HEADER_BYTES + 6, bytes.size)
            assertArrayEquals(WavWriter.header(16_000, 6), bytes.copyOfRange(0, WavWriter.HEADER_BYTES))
            assertArrayEquals(
                byteArrayOf(1, 0, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12),
                bytes.copyOfRange(WavWriter.HEADER_BYTES, bytes.size),
            )
        } finally {
            file.delete()
        }
    }

    private fun ascii(buffer: ByteBuffer, at: Int) =
        String(ByteArray(4) { buffer.get(at + it) }, Charsets.US_ASCII)
}
