package app.openwisprflow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.math.abs

/** One finished recording. */
data class Take(val file: File, val durationMs: Long, val peak: Int)

/**
 * Records 16 kHz mono PCM from the phone microphone into a WAV file on a dedicated thread
 * (HLD section 4, Audio). This object is the only owner of the AudioRecord: the capture thread
 * reads it, and only [stop] releases it, after that thread has exited.
 */
class AudioCapture(
    private val file: File,
    private val maxDurationMs: Long,
    private val onMaxDuration: () -> Unit,
    /** Receives a copy of each 20 ms frame as little-endian PCM bytes, on the capture thread. */
    private val onFrame: ((ByteArray) -> Unit)? = null,
) {

    private val recorder: AudioRecord
    private val writer: WavWriter
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var samples = 0L
    @Volatile private var peak = 0

    init {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(minBytes > 0) { "AudioRecord.getMinBufferSize returned $minBytes" }
        recorder = buildRecorder(maxOf(minBytes * 2, 6400))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord did not initialize")
        }
        writer = WavWriter(file, SAMPLE_RATE)
    }

    // RECORD_AUDIO is checked by the caller before a capture is created.
    @SuppressLint("MissingPermission")
    private fun buildRecorder(bufferBytes: Int) = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .setEncoding(ENCODING)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes)
        .build()

    fun start() {
        recorder.startRecording()
        running = true
        thread = Thread(::captureLoop, "openWisprFlow-capture").apply { start() }
    }

    private fun captureLoop() {
        val frame = ShortArray(FRAME_SAMPLES)
        val maxSamples = maxDurationMs * SAMPLE_RATE / 1000
        var limitReported = false
        while (running) {
            val read = recorder.read(frame, 0, frame.size)
            if (read <= 0) continue
            writer.write(frame, read)
            onFrame?.let { send ->
                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    bytes[2 * i] = frame[i].toInt().toByte()
                    bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte()
                }
                send(bytes)
            }
            for (i in 0 until read) {
                val level = abs(frame[i].toInt())
                if (level > peak) peak = level
            }
            samples += read
            if (samples >= maxSamples && !limitReported) {
                limitReported = true
                onMaxDuration()
            }
        }
    }

    /** Stops the thread, releases the microphone and closes the file. Safe to call once. */
    fun stop(): Take {
        running = false
        thread?.join(STOP_JOIN_MS)
        try {
            recorder.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        recorder.release()
        writer.close()
        return Take(file, samples * 1000 / SAMPLE_RATE, peak)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** 20 ms frames. */
        private const val FRAME_SAMPLES = 320
        private const val STOP_JOIN_MS = 500L
    }
}
