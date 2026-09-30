package com.tokalot.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Saves each recording as a small AAC (.m4a) file, about 3 KB per second of speech,
 * roughly 10x smaller than raw WAV. Old files are pruned by the retention setting.
 */
object AudioStore {
    private const val BITRATE = 24_000

    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "audio").apply { mkdirs() }
    fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.m4a")
    fun exists(ctx: Context, id: Long) = file(ctx, id).exists()
    fun delete(ctx: Context, id: Long) { file(ctx, id).delete() }

    fun totalBytes(ctx: Context) = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes recordings older than [days]. days <= 0 means keep none; Int.MAX_VALUE keeps all. */
    fun prune(ctx: Context, days: Int) {
        if (days == Int.MAX_VALUE) return
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 3600 * 1000
        dir(ctx).listFiles()?.forEach { if (days <= 0 || it.lastModified() < cutoff) it.delete() }
    }

    /** Blocking; call off the main thread. Silently gives up on any codec error. */
    fun save(ctx: Context, id: Long, samples: FloatArray) {
        val out = file(ctx, id)
        val tmp = File(out.parentFile, "$id.tmp")
        try {
            encode(samples, tmp)
            tmp.renameTo(out)
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    private fun encode(samples: FloatArray, dest: File) {
        val rate = Recorder.SAMPLE_RATE
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val bytes = pcm.array()

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            var track = -1
            var muxing = false
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), bytes.size - offset, 8192)
                        val ptsUs = offset / 2L * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(bytes, offset, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            offset += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (muxing && info.size > 0 && !isConfig) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (muxing) muxer.stop()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.release() }
        }
    }
}
