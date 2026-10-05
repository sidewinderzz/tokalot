package com.tokalot.app

import kotlin.math.abs

/**
 * A small FLAC encoder for 16-bit mono speech. FLAC is lossless, so the speech model hears exactly
 * what a WAV would give it, in about half the bytes to upload. Each block of audio is predicted from
 * its last few samples (the format's "fixed" predictors) and the leftovers are Rice-coded.
 * The desktop app has the same encoder (Flac.cs); the two must stay byte-for-byte alike.
 */
object Flac {
    private const val BLOCK = 4096

    fun encode(samples: FloatArray, rate: Int = 16000): ByteArray {
        val pcm = IntArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort().toInt() }

        val o = Bits(samples.size + 256)
        for (ch in "fLaC") o.put(ch.code, 8)
        // STREAMINFO, the only metadata block.
        o.put(1, 1); o.put(0, 7); o.put(34, 24)
        o.put(BLOCK, 16); o.put(BLOCK, 16)   // smallest and largest block
        o.put(0, 24); o.put(0, 24)           // frame sizes: not known
        o.put(rate, 20); o.put(0, 3); o.put(15, 5) // mono, 16 bits
        o.put(0, 4); o.put(pcm.size, 32)
        repeat(16) { o.put(0, 8) }           // checksum of the audio: not given

        val res = IntArray(BLOCK)
        var at = 0
        var frame = 0
        while (at < pcm.size) {
            val n = minOf(BLOCK, pcm.size - at)
            val start = o.length
            o.put(0xFFF8, 16)
            o.put(if (n == BLOCK) 0xC else 0x7, 4)  // 4096, or "the size follows"
            o.put(if (rate == 16000) 0x5 else 0, 4) // 16 kHz, or "see STREAMINFO"
            o.put(0, 4); o.put(4, 3); o.put(0, 1)   // mono, 16 bits
            utf8(o, frame)
            if (n != BLOCK) o.put(n - 1, 16)
            o.put(crc8(o.data, start, o.length), 8)

            // Pick the predictor that leaves the least behind.
            var order = 0
            var best = Long.MAX_VALUE
            var k = 0
            while (k <= 4 && k < n) {
                var sum = 0L
                for (i in k until n) sum += abs(residual(pcm, at + i, k))
                if (sum < best) { best = sum; order = k }
                k++
            }
            val count = n - order
            for (i in 0 until count) {
                val r = residual(pcm, at + order + i, order)
                res[i] = (r shl 1) xor (r shr 31) // fold the sign into the lowest bit
            }
            // And the Rice parameter that codes those leftovers in the fewest bits.
            var rice = 0
            var bestBits = Long.MAX_VALUE
            for (p in 0..14) {
                var bits = count.toLong() * (p + 1)
                for (i in 0 until count) bits += (res[i] ushr p).toLong() and 0xFFFFFFFFL
                if (bits < bestBits) { bestBits = bits; rice = p }
            }

            o.put(0, 1); o.put(8 or order, 6); o.put(0, 1) // fixed predictor of this order
            for (i in 0 until order) o.put(pcm[at + i] and 0xFFFF, 16)
            o.put(0, 2); o.put(0, 4); o.put(rice, 4)       // one Rice partition
            for (i in 0 until count) {
                val u = res[i]
                var q = u ushr rice
                while (q > 0) { o.put(0, 1); q-- }
                o.put(1, 1)
                if (rice > 0) o.put(u and ((1 shl rice) - 1), rice)
            }
            o.align()
            o.put(crc16(o.data, start, o.length), 16)
            at += BLOCK
            frame++
        }
        return o.toArray()
    }

    private fun residual(x: IntArray, i: Int, order: Int): Int = when (order) {
        0 -> x[i]
        1 -> x[i] - x[i - 1]
        2 -> x[i] - 2 * x[i - 1] + x[i - 2]
        3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
        else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
    }

    /** The frame number, written the way UTF-8 writes a character. */
    private fun utf8(o: Bits, v: Int) {
        when {
            v < 0x80 -> o.put(v, 8)
            v < 0x800 -> { o.put(0xC0 or (v shr 6), 8); o.put(0x80 or (v and 0x3F), 8) }
            v < 0x10000 -> {
                o.put(0xE0 or (v shr 12), 8); o.put(0x80 or ((v shr 6) and 0x3F), 8); o.put(0x80 or (v and 0x3F), 8)
            }
            else -> {
                o.put(0xF0 or (v shr 18), 8); o.put(0x80 or ((v shr 12) and 0x3F), 8)
                o.put(0x80 or ((v shr 6) and 0x3F), 8); o.put(0x80 or (v and 0x3F), 8)
            }
        }
    }

    private fun crc8(d: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) {
            c = c xor (d[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private fun crc16(d: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) {
            c = c xor ((d[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    /** Writes values bit by bit, most significant first. */
    private class Bits(capacity: Int) {
        var data = ByteArray(capacity)
        var length = 0 // whole bytes written
        private var acc = 0
        private var held = 0 // bits waiting in acc (always under 8)

        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1)
                if (++held == 8) {
                    if (length == data.size) data = data.copyOf(data.size * 2)
                    data[length++] = acc.toByte()
                    acc = 0; held = 0
                }
            }
        }

        fun align() { while (held != 0) put(0, 1) }

        fun toArray(): ByteArray = data.copyOf(length)
    }
}
