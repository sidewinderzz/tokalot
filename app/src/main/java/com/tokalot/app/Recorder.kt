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
    @Volatile private var total = 0

    companion object {
        const val SAMPLE_RATE = 16000
        private const val VOICE_RMS = 0.015f // the most a sound ever has to reach to count as "someone is talking"
        private const val VOICE_MIN = 0.004f // and the least, on a very quiet microphone
        private const val SPEECH_RMS = 0.008f // lower bar for "was anything said at all" (quiet mics)
        private const val MAX_SAMPLES = SAMPLE_RATE * 300 // 5 minute cap
    }

    val isRecording: Boolean get() = running

    /** Called once (on the recording thread) if the microphone stops delivering audio mid-recording. */
    @Volatile var onLost: (() -> Unit)? = null

    /** Called once (on the recording thread) when the 5 minute cap is reached. */
    @Volatile var onFull: (() -> Unit)? = null

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

    private var floor = 0.002f // running estimate of the room's background level

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
        floor = 0.002f
        record = rec
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            // The mic is busy or was taken away between creating and starting.
            rec.release()
            record = null
            return false
        }
        running = true
        thread = Thread {
            val buf = ShortArray(minBuf)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    level = kotlin.math.sqrt(sum / n).toFloat()
                    if (level > SPEECH_RMS) speechSamples += n
                    // The bar for "talking" follows the room: three times the background level, which drops at once
                    // in a quiet moment and creeps up slowly. A fixed bar read soft speech on a quiet mic as
                    // silence, and auto-stop then ended the recording 30 s in, mid-sentence.
                    floor = if (level < floor) level else minOf(level, floor * 1.003f + 0.00001f)
                    if (level > (floor * 3).coerceIn(VOICE_MIN, VOICE_RMS)) {
                        lastVoiceAt = System.currentTimeMillis()
                        lastVoiceSample = total + n
                    }
                    if (total < MAX_SAMPLES) {
                        synchronized(chunks) {
                            chunks.add(buf.copyOf(n))
                            total += n
                        }
                        if (total >= MAX_SAMPLES) onFull?.invoke()
                    }
                } else if (n < 0) {
                    level = 0f
                    if (running) onLost?.invoke()
                    break
                }
            }
        }.also { it.start() }
        return true
    }

    /** How much has been recorded so far, in samples. */
    val count: Int get() = total

    /** A copy of part of the recording so far, as floats in [-1, 1], while it carries on. */
    fun snapshot(from: Int, to: Int): FloatArray = synchronized(chunks) {
        val end = minOf(to, total)
        if (from < 0 || from >= end) return FloatArray(0)
        val out = FloatArray(end - from)
        var pos = 0 // index of the current chunk's first sample
        for (c in chunks) {
            if (pos >= end) break
            if (pos + c.size > from) {
                for (i in maxOf(from, pos) until minOf(end, pos + c.size)) out[i - from] = c[i - pos] / 32768f
            }
            pos += c.size
        }
        out
    }

    /** Stops the mic immediately and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray = stopMic().audio().samples

    /**
     * Stops the mic immediately and hands over what was recorded, still as 16-bit chunks. Turning a long
     * recording into floats (up to ~19 MB) is left to [Taken.audio], which can run on any thread.
     */
    fun stopMic(): Taken {
        running = false
        level = 0f
        thread?.join(500)
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
        return synchronized(chunks) {
            val taken = Taken(ArrayList(chunks), total)
            chunks.clear()
            total = 0
            taken
        }
    }

    /** The audio as floats in [-1, 1], and its loudest sample. */
    class Audio(val samples: FloatArray, val peak: Float)

    /** A finished recording. */
    class Taken(private val chunks: List<ShortArray>, val size: Int) {
        /** The first [keep] samples as floats, with their peak, in one pass. */
        fun audio(keep: Int = size): Audio {
            val n = keep.coerceIn(0, size)
            val out = FloatArray(n)
            var peak = 0f
            var i = 0
            outer@ for (c in chunks) for (s in c) {
                if (i >= n) break@outer
                val v = s / 32768f
                out[i++] = v
                val a = if (v < 0f) -v else v
                if (a > peak) peak = a
            }
            return Audio(out, peak)
        }
    }
}
