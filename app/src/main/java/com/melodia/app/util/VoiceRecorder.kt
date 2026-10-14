package com.melodia.app.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records the microphone straight to a 16-bit mono WAV file, a format the
 * engine can read as a voice reference.
 */
class VoiceRecorder {
    private val sampleRate = 44100
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    var level: Float = 0f
        private set

    val isRecording: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(file: File, maxSeconds: Int = 30, onStopped: (File) -> Unit) {
        if (running.get()) return
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, sampleRate / 5 * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("The microphone is not available.")
        }
        running.set(true)
        worker = thread(name = "voice-recorder") {
            val maxBytes = sampleRate.toLong() * 2 * maxSeconds
            var written = 0L
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(44))
                val buffer = ShortArray(bufferSize / 2)
                val bytes = ByteArray(buffer.size * 2)
                record.startRecording()
                try {
                    while (running.get() && written < maxBytes) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n <= 0) continue
                        var peak = 0
                        for (i in 0 until n) {
                            val s = buffer[i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            val a = if (s < 0) -s else s
                            if (a > peak) peak = a
                        }
                        level = peak / 32768f
                        out.write(bytes, 0, n * 2)
                        written += n * 2
                    }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
                out.seek(0)
                out.write(wavHeader(written.toInt()))
            }
            running.set(false)
            level = 0f
            onStopped(file)
        }
    }

    fun stop() {
        running.set(false)
    }

    private fun wavHeader(dataBytes: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        return header.array()
    }
}
