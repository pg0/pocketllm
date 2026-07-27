package com.redcoralstudios.pocketllm.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records mono 16 kHz PCM to a WAV file for the model's audio encoder.
 *
 * The audio goes to the model as audio, not as a transcript: Gemma 4's mmproj
 * carries an audio encoder, so tone and delivery survive that a
 * speech-to-text step would discard.
 *
 * 16 kHz mono matches what the encoder wants, so nothing has to be resampled.
 * Recording is capped at [MAX_SECONDS] because the model only attends to a
 * short window.
 */
class AudioRecorder {

    private val recording = AtomicBoolean(false)

    @Volatile
    private var recorder: AudioRecord? = null

    val isRecording: Boolean get() = recording.get()

    /**
     * Records until [stop] is called or the cap is reached.
     *
     * Requires RECORD_AUDIO; the caller is responsible for the permission.
     *
     * @return the finished WAV file, or null if recording failed or was empty.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(context: Context, onLevel: (Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            if (!recording.compareAndSet(false, true)) return@withContext null

            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) {
                recording.set(false)
                return@withContext null
            }
            val bufferSize = maxOf(minBuffer, SAMPLE_RATE) // ~1 s of headroom

            val target = File(ImagePrep.cacheDir(context), "audio_${System.currentTimeMillis()}.wav")

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize,
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord init failed", e)
                recording.set(false)
                return@withContext null
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                recording.set(false)
                return@withContext null
            }

            recorder = record
            var totalBytes = 0L

            try {
                RandomAccessFile(target, "rw").use { out ->
                    out.setLength(0)
                    out.write(ByteArray(WAV_HEADER_BYTES)) // placeholder, patched below

                    record.startRecording()
                    val buffer = ByteArray(bufferSize)
                    val maxBytes = MAX_SECONDS * SAMPLE_RATE * BYTES_PER_SAMPLE

                    // Voice activity detection. The threshold is derived from the
                    // room rather than hardcoded, because a fixed cutoff either
                    // never triggers in a cafe or cuts off quiet speech at a desk.
                    var noiseFloor = -1f
                    var noiseSamples = 0
                    var speechSeen = false
                    var silenceBytes = 0L

                    while (recording.get() && totalBytes < maxBytes) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        totalBytes += read

                        val level = rmsLevel(buffer, read)
                        onLevel(level)

                        // Calibrate on the opening moments, before anyone speaks.
                        if (noiseSamples * bufferSize < CALIBRATION_BYTES) {
                            noiseFloor = if (noiseFloor < 0) level else (noiseFloor * 0.7f + level * 0.3f)
                            noiseSamples++
                            continue
                        }

                        val threshold = maxOf(MIN_SPEECH_LEVEL, noiseFloor * NOISE_MULTIPLIER)

                        if (level > threshold) {
                            speechSeen = true
                            silenceBytes = 0
                        } else {
                            silenceBytes += read
                        }

                        if (speechSeen) {
                            // Trailing pause after real speech means the user finished.
                            if (silenceBytes > TRAILING_SILENCE_BYTES) {
                                Log.i(TAG, "auto-stopped on trailing silence")
                                break
                            }
                        } else if (totalBytes > NO_SPEECH_GIVEUP_BYTES) {
                            // Mic opened but nobody spoke: don't leave it running.
                            Log.i(TAG, "auto-stopped, no speech detected")
                            break
                        }
                    }

                    writeWavHeader(out, totalBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "recording failed", e)
                target.delete()
                return@withContext null
            } finally {
                runCatching { record.stop() }
                record.release()
                recorder = null
                recording.set(false)
            }

            if (totalBytes < MIN_USEFUL_BYTES) {
                target.delete()
                return@withContext null
            }
            target
        }

    fun stop() {
        recording.set(false)
    }

    /**
     * Normalised 0..1 RMS, for the level meter and for voice detection.
     *
     * RMS rather than peak: a single click or a table bump spikes the peak and
     * would read as speech, while RMS tracks sustained energy.
     */
    private fun rmsLevel(buffer: ByteArray, length: Int): Float {
        val shorts = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var count = 0
        while (shorts.hasRemaining()) {
            val v = shorts.get().toDouble()
            sum += v * v
            count++
        }
        if (count == 0) return 0f
        return (kotlin.math.sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /** Standard 44-byte canonical WAV header, patched once the size is known. */
    private fun writeWavHeader(out: RandomAccessFile, dataBytes: Long) {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE

        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                                  // PCM subchunk size
        header.putShort(1)                                 // PCM format
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort()) // block align
        header.putShort((BYTES_PER_SAMPLE * 8).toShort())        // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())

        out.seek(0)
        out.write(header.array())
    }

    private companion object {
        const val TAG = "PocketLLM-audio"
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val WAV_HEADER_BYTES = 44
        const val MAX_SECONDS = 30
        const val MIN_USEFUL_BYTES = 8_000L // ~0.25 s

        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE

        /** Opening window used to measure the room before anyone speaks. */
        const val CALIBRATION_BYTES = BYTES_PER_SECOND / 4          // 250 ms

        /** Speech must exceed the room noise by this factor. */
        const val NOISE_MULTIPLIER = 3.0f

        /** Floor for a silent room, where noiseFloor * multiplier is ~0. */
        const val MIN_SPEECH_LEVEL = 0.015f

        /** Pause after speech that ends the recording. */
        const val TRAILING_SILENCE_BYTES = (BYTES_PER_SECOND * 1.2f).toLong()

        /** Mic opened but nothing said: give up rather than record the room. */
        const val NO_SPEECH_GIVEUP_BYTES = (BYTES_PER_SECOND * 4L)
    }
}
