package com.tokalot.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records 16 kHz mono PCM (what Whisper wants) only between start() and stop(). */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<ShortArray>()
    private var total = 0

    companion object {
        const val SAMPLE_RATE = 16000
        private const val VOICE_RMS = 0.015f // above this counts as "someone is talking"
        private const val SPEECH_RMS = 0.008f // lower bar for "was anything said at all" (quiet mics)
        private const val MAX_SAMPLES = SAMPLE_RATE * 300 // 5 minute cap
    }

    val isRecording: Boolean get() = running

    /** Loudness of the latest audio chunk (RMS, 0..1), for the animated button. */
    @Volatile var level = 0f
        private set

    /** When speech was last heard, for auto-stop, and where it ended in the audio. */
    @Volatile var lastVoiceAt = 0L
        private set
    @Volatile var lastVoiceSample = 0
        private set

    /** How much of the recording had sound in it, in samples. */
    @Volatile var speechSamples = 0
        private set

    /** Caller must already hold RECORD_AUDIO. Returns false if the mic couldn't be opened. */
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
        } catch (e: SecurityException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        chunks.clear()
        total = 0
        lastVoiceAt = System.currentTimeMillis()
        lastVoiceSample = 0
        speechSamples = 0
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(minBuf)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    level = kotlin.math.sqrt(sum / n).toFloat()
                    if (level > SPEECH_RMS) speechSamples += n
                    if (level > VOICE_RMS) {
                        lastVoiceAt = System.currentTimeMillis()
                        lastVoiceSample = total + n
                    }
                    if (total < MAX_SAMPLES) {
                        chunks.add(buf.copyOf(n))
                        total += n
                    }
                } else if (n < 0) break
            }
        }.also { it.start() }
        return true
    }

    /** Stops the mic immediately and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        running = false
        level = 0f
        thread?.join(500)
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
        val out = FloatArray(total)
        var i = 0
        for (c in chunks) for (s in c) out[i++] = s / 32768f
        chunks.clear()
        total = 0
        return out
    }
}
