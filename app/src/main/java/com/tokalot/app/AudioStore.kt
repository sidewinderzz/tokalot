package com.tokalot.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

    /**
     * Deletes recordings older than [days]. days <= 0 means keep none; Int.MAX_VALUE keeps all.
     * Recordings that still have no transcript (failed or cancelled) are always kept, so they can be retried.
     */
    fun prune(ctx: Context, days: Int) {
        if (days == Int.MAX_VALUE) return
        val keep = History.pendingIds(ctx)
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 3600 * 1000
        dir(ctx).listFiles()?.forEach {
            if (it.name.removeSuffix(".m4a").toLongOrNull() in keep) return@forEach
            if (days <= 0 || it.lastModified() < cutoff) it.delete()
        }
    }

    /** The explicit "Delete all" button: every recording, including ones waiting for a retry. */
    fun deleteAll(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    /** Blocking; call off the main thread. Returns false (and saves nothing) on any codec error. */
    fun save(ctx: Context, id: Long, samples: FloatArray): Boolean {
        val out = file(ctx, id)
        val tmp = File(out.parentFile, "$id.tmp")
        return try {
            encode(samples, tmp)
            tmp.renameTo(out)
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Reads a saved recording back into 16 kHz mono floats, to transcribe it again.
     * Blocking; throws if the file can't be decoded.
     */
    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcm = ByteArrayOutputStream()
        var channels = 1
        try {
            extractor.setDataSource(file.absolutePath)
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    fmt = f
                    break
                }
            }
            if (fmt == null) throw IOException("No audio in that recording")
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val n = extractor.readSampleData(dec.getInputBuffer(inIdx)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = dec.getOutputBuffer(outIdx)!!
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(chunk)
                            pcm.write(chunk)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
        // The decoder hands back 16-bit PCM. We only ever save mono, but take the first channel to be safe.
        val shorts = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining() / channels) { shorts.get(it * channels) / 32768f }
    }

    /**
     * The audio as an AAC (.m4a) file in memory, about a tenth of the WAV, for uploading over a slow
     * connection. Null if this phone's encoder fails.
     */
    fun aac(ctx: Context, samples: FloatArray): ByteArray? {
        val tmp = File.createTempFile("piece", ".m4a", ctx.applicationContext.cacheDir)
        return try {
            encode(samples, tmp)
            tmp.readBytes()
        } catch (_: Exception) {
            null
        } finally {
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
