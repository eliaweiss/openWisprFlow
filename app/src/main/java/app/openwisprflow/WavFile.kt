package app.openwisprflow

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit mono PCM to a WAV file. The header is written last, once the length is known. */
class WavWriter(private val file: File, private val sampleRate: Int) : Closeable {

    private val out = BufferedOutputStream(FileOutputStream(file)).apply { write(ByteArray(HEADER_BYTES)) }
    private var buffer: ByteBuffer = ByteBuffer.allocate(0)
    var dataBytes = 0L
        private set

    fun write(samples: ShortArray, count: Int) {
        if (buffer.capacity() < count * 2) {
            buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        buffer.clear()
        for (i in 0 until count) buffer.putShort(samples[i])
        out.write(buffer.array(), 0, count * 2)
        dataBytes += count * 2
    }

    override fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { it.write(header(sampleRate, dataBytes)) }
    }

    companion object {
        const val HEADER_BYTES = 44

        /** Canonical 44-byte RIFF header for 16-bit mono PCM. */
        fun header(sampleRate: Int, dataBytes: Long): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.toInt())
            }.array()
        }
    }
}
