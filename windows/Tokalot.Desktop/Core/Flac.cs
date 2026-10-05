using System;

namespace Tokalot.Desktop.Core;

/**
 * A small FLAC encoder for 16-bit mono speech. FLAC is lossless, so the speech model hears exactly
 * what a WAV would give it, in about half the bytes to upload. Each block of audio is predicted from
 * its last few samples (the format's "fixed" predictors) and the leftovers are Rice-coded.
 * The Android app has the same encoder (Flac.kt); the two must stay byte-for-byte alike.
 */
public static class Flac
{
    private const int Block = 4096;

    public static byte[] Encode(float[] samples, int rate = 16000)
    {
        var pcm = new int[samples.Length];
        for (int i = 0; i < pcm.Length; i++) pcm[i] = (short)(Math.Clamp(samples[i], -1f, 1f) * 32767);

        var o = new Bits(samples.Length + 256);
        o.Put('f', 8); o.Put('L', 8); o.Put('a', 8); o.Put('C', 8);
        // STREAMINFO, the only metadata block.
        o.Put(1, 1); o.Put(0, 7); o.Put(34, 24);
        o.Put(Block, 16); o.Put(Block, 16);   // smallest and largest block
        o.Put(0, 24); o.Put(0, 24);           // frame sizes: not known
        o.Put((uint)rate, 20); o.Put(0, 3); o.Put(15, 5); // mono, 16 bits
        o.Put(0, 4); o.Put((uint)pcm.Length, 32);
        for (int i = 0; i < 16; i++) o.Put(0, 8); // checksum of the audio: not given

        var res = new int[Block];
        for (int at = 0, frame = 0; at < pcm.Length; at += Block, frame++)
        {
            int n = Math.Min(Block, pcm.Length - at);
            int start = o.Length;
            o.Put(0xFFF8, 16);
            o.Put(n == Block ? 0xCu : 0x7u, 4);  // 4096, or "the size follows"
            o.Put(rate == 16000 ? 0x5u : 0u, 4); // 16 kHz, or "see STREAMINFO"
            o.Put(0, 4); o.Put(4, 3); o.Put(0, 1); // mono, 16 bits
            Utf8(o, frame);
            if (n != Block) o.Put((uint)(n - 1), 16);
            o.Put(Crc8(o.Data, start, o.Length), 8);

            // Pick the predictor that leaves the least behind.
            int order = 0;
            long best = long.MaxValue;
            for (int k = 0; k <= 4 && k < n; k++)
            {
                long sum = 0;
                for (int i = k; i < n; i++) sum += Math.Abs(Residual(pcm, at + i, k));
                if (sum < best) { best = sum; order = k; }
            }
            int count = n - order;
            for (int i = 0; i < count; i++)
            {
                int r = Residual(pcm, at + order + i, order);
                res[i] = (r << 1) ^ (r >> 31); // fold the sign into the lowest bit
            }
            // And the Rice parameter that codes those leftovers in the fewest bits.
            int rice = 0;
            long bestBits = long.MaxValue;
            for (int k = 0; k <= 14; k++)
            {
                long bits = (long)count * (k + 1);
                for (int i = 0; i < count; i++) bits += (uint)res[i] >> k;
                if (bits < bestBits) { bestBits = bits; rice = k; }
            }

            o.Put(0, 1); o.Put((uint)(8 | order), 6); o.Put(0, 1); // fixed predictor of this order
            for (int i = 0; i < order; i++) o.Put((uint)pcm[at + i] & 0xFFFF, 16);
            o.Put(0, 2); o.Put(0, 4); o.Put((uint)rice, 4);        // one Rice partition
            for (int i = 0; i < count; i++)
            {
                uint u = (uint)res[i];
                for (uint q = u >> rice; q > 0; q--) o.Put(0, 1);
                o.Put(1, 1);
                if (rice > 0) o.Put(u & ((1u << rice) - 1), rice);
            }
            o.Align();
            o.Put(Crc16(o.Data, start, o.Length), 16);
        }
        return o.ToArray();
    }

    private static int Residual(int[] x, int i, int order) => order switch
    {
        0 => x[i],
        1 => x[i] - x[i - 1],
        2 => x[i] - 2 * x[i - 1] + x[i - 2],
        3 => x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3],
        _ => x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4],
    };

    /** The frame number, written the way UTF-8 writes a character. */
    private static void Utf8(Bits o, int v)
    {
        if (v < 0x80) o.Put((uint)v, 8);
        else if (v < 0x800) { o.Put((uint)(0xC0 | (v >> 6)), 8); o.Put((uint)(0x80 | (v & 0x3F)), 8); }
        else if (v < 0x10000)
        {
            o.Put((uint)(0xE0 | (v >> 12)), 8); o.Put((uint)(0x80 | ((v >> 6) & 0x3F)), 8); o.Put((uint)(0x80 | (v & 0x3F)), 8);
        }
        else
        {
            o.Put((uint)(0xF0 | (v >> 18)), 8); o.Put((uint)(0x80 | ((v >> 12) & 0x3F)), 8);
            o.Put((uint)(0x80 | ((v >> 6) & 0x3F)), 8); o.Put((uint)(0x80 | (v & 0x3F)), 8);
        }
    }

    private static uint Crc8(byte[] d, int from, int to)
    {
        uint c = 0;
        for (int i = from; i < to; i++)
        {
            c ^= d[i];
            for (int b = 0; b < 8; b++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
        }
        return c;
    }

    private static uint Crc16(byte[] d, int from, int to)
    {
        uint c = 0;
        for (int i = from; i < to; i++)
        {
            c ^= (uint)d[i] << 8;
            for (int b = 0; b < 8; b++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF;
        }
        return c;
    }

    /** Writes values bit by bit, most significant first. */
    private sealed class Bits
    {
        public byte[] Data;
        public int Length; // whole bytes written
        private uint acc;
        private int held;  // bits waiting in acc (always under 8)

        public Bits(int capacity) { Data = new byte[capacity]; }

        public void Put(uint value, int bits)
        {
            for (int i = bits - 1; i >= 0; i--)
            {
                acc = (acc << 1) | ((value >> i) & 1);
                if (++held == 8)
                {
                    if (Length == Data.Length) Array.Resize(ref Data, Data.Length * 2);
                    Data[Length++] = (byte)acc;
                    acc = 0; held = 0;
                }
            }
        }

        public void Align() { while (held != 0) Put(0, 1); }

        public byte[] ToArray()
        {
            var a = new byte[Length];
            Array.Copy(Data, a, Length);
            return a;
        }
    }
}
