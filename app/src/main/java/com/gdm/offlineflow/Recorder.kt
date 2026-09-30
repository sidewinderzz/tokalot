package com.gdm.offlineflow

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
        private const val MAX_SAMPLES = SAMPLE_RATE * 300 // 5 minute cap
    }

    val isRecording: Boolean get() = running

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
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(minBuf)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0 && total < MAX_SAMPLES) {
                    chunks.add(buf.copyOf(n))
                    total += n
                } else if (n < 0) break
            }
        }.also { it.start() }
        return true
    }

    /** Stops the mic immediately and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        running = false
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
