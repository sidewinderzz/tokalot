using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/**
 * Records 16 kHz mono 16-bit audio (what Whisper wants) from the default microphone.
 * Linux: the sound server's own recorder program does the capturing (pw-record for PipeWire,
 * parec for PulseAudio, arecord for plain ALSA) and its raw output is read here in 50 ms chunks.
 */
public sealed class Recorder : IDisposable
{
    public const int SampleRate = 16000;
    private const int MaxSamples = SampleRate * 600; // 10 minute cap
    private const float VoiceRms = 0.015f;            // the most a sound ever has to reach to count as "someone is talking"
    private const float VoiceMin = 0.004f;            // and the least, on a very quiet microphone
    private const float SpeechRms = 0.008f;           // lower bar for "was anything said at all" (quiet mics)
    private const int ChunkBytes = SampleRate / 20 * 2; // 50 ms

    private Process? proc;
    private Thread? reader;
    private volatile int session;
    private volatile bool bigEndian;                  // this recorder sends each sample high byte first (see Read)
    private float floor;                              // running estimate of the room's background level
    private readonly List<short> samples = new();
    private readonly object gate = new();

    /** The recorder that last delivered audio; tried first next time, with no start-up check. */
    private static string? proven;

    public bool IsRecording { get; private set; }
    /** The 10 minute cap was reached; later audio is not being kept. */
    public bool Full { get { lock (gate) return samples.Count >= MaxSamples; } }
    /** Loudness of the latest chunk (RMS 0..1), for the animated bars. */
    public float Level { get; private set; }
    /** When speech was last heard, and where it ended in the audio (for auto-stop trimming). */
    public DateTime LastVoiceAt { get; private set; }
    public int LastVoiceSample { get; private set; }
    /** How many 50 ms chunks had sound in them, and how many of those came after the start tone. */
    public int SpeechChunks { get; private set; }
    public int LateSpeechChunks { get; private set; }
    /** Audio this early may just be the start tone coming back through the speakers. */
    public int CueSamples { get; set; }

    private static readonly (string Tool, string[] Args)[] Tools =
    {
        ("pw-record", new[] { "--rate", "16000", "--channels", "1", "--format", "s16", "--latency", "50ms", "-" }),
        ("parec", new[] { "--raw", "--format=s16le", "--rate=16000", "--channels=1", "--latency-msec=50" }),
        ("arecord", new[] { "-q", "-t", "raw", "-f", "S16_LE", "-r", "16000", "-c", "1" }),
    };

    /** The installed recorder program Tokalot will use, or null when there is none. */
    public static string? Tool
    {
        get
        {
            foreach (var (tool, _) in Tools) if (Sh.Has(tool)) return tool;
            return null;
        }
    }

    /** How much has been recorded so far, in samples. */
    public int Count { get { lock (gate) return samples.Count; } }

    /** A copy of part of the recording so far, as floats in [-1, 1], while it carries on. */
    public float[] Snapshot(int from, int to)
    {
        lock (gate)
        {
            to = Math.Min(to, samples.Count);
            if (from < 0 || from >= to) return Array.Empty<float>();
            var outp = new float[to - from];
            for (int i = 0; i < outp.Length; i++) outp[i] = samples[from + i] / 32768f;
            return outp;
        }
    }

    /** Returns false if no microphone could be opened. */
    public bool Start()
    {
        if (IsRecording) return true;
        try
        {
            lock (gate) samples.Clear();
            LastVoiceAt = DateTime.UtcNow;
            LastVoiceSample = 0;
            SpeechChunks = 0;
            LateSpeechChunks = 0;
            floor = 0.002f;

            var order = new List<(string Tool, string[] Args)>(Tools);
            order.Sort((a, b) => (b.Tool == proven).CompareTo(a.Tool == proven));
            foreach (var (tool, args) in order)
            {
                if (Sh.Which(tool) is not { } path) continue;
                var p = Launch(path, args);
                if (p == null) continue;
                bigEndian = false;
                var first = new ManualResetEventSlim();
                var mine = ++session;
                var t = new Thread(() => Read(p, tool, mine, first)) { IsBackground = true, Name = "Tokalot recorder" };
                t.Start();
                // A recorder that has delivered audio before is trusted. A new one has to show it can:
                // one with no device quits straight away, and one talking to a sound server that isn't
                // running (pw-record on a PulseAudio system) stays alive but never sends a sample.
                // This wait happens once per run, on the first recording.
                if (tool != proven && !first.Wait(450))
                {
                    session++;
                    try { if (!p.HasExited) p.Kill(); } catch { }
                    try { t.Join(200); } catch { }
                    p.Dispose();
                    lock (gate) samples.Clear();
                    continue;
                }
                proc = p;
                reader = t;
                IsRecording = true;
                return true;
            }
            return false;
        }
        catch
        {
            Kill();
            return false;
        }
    }

    private static Process? Launch(string path, string[] args)
    {
        try
        {
            var psi = new ProcessStartInfo(path) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var a in args) psi.ArgumentList.Add(a);
            var p = Process.Start(psi);
            if (p != null) _ = p.StandardError.ReadToEndAsync();
            return p;
        }
        catch { return null; }
    }

    private void Read(Process p, string tool, int mine, ManualResetEventSlim firstData)
    {
        try
        {
            var stream = p.StandardOutput.BaseStream;
            var buf = new byte[ChunkBytes];
            bool first = true;
            while (session == mine)
            {
                // The last read before the recorder exits can be a part-filled chunk; it is kept too.
                int got = Fill(stream, buf);
                if (got < 2) break;
                int from = 0;
                if (first)
                {
                    first = false;
                    // Some recorder versions put a WAV header in front of the samples; step over it.
                    if (got >= 12 && buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F')
                    {
                        from = SkipWavHeader(stream, buf, ref got);
                        if (got - from < 2) { first = true; continue; }
                    }
                    // pw-record sends an AU stream when it writes to a pipe: a ".snd" header (its length is
                    // the second field), then the samples with their two bytes the other way round.
                    else if (got >= 24 && buf[0] == '.' && buf[1] == 's' && buf[2] == 'n' && buf[3] == 'd')
                    {
                        int header = (buf[4] << 24) | (buf[5] << 16) | (buf[6] << 8) | buf[7];
                        if (header < 24 || header > got - 2) header = 24;
                        from = header + ((got - header) & 1); // keep whole samples
                        bigEndian = true;
                    }
                    proven = tool;
                    firstData.Set();
                }
                if (session != mine) break;
                OnData(buf, from, got - from);
            }
        }
        catch { }
        // The recorder died on its own (microphone unplugged, sound server restarted).
        if (session == mine) Level = 0;
    }

    /** Reads until the buffer is full or the stream ends, so every chunk is a whole 50 ms. */
    private static int Fill(Stream s, byte[] buf)
    {
        int got = 0;
        while (got < buf.Length)
        {
            int n = s.Read(buf, got, buf.Length - got);
            if (n <= 0) break;
            got += n;
        }
        return got;
    }

    /** Returns the offset of the first sample in buf (refilling buf if the header ran past it). */
    private static int SkipWavHeader(Stream s, byte[] buf, ref int got)
    {
        for (int i = 12; i + 8 <= got; i++)
            if (buf[i] == 'd' && buf[i + 1] == 'a' && buf[i + 2] == 't' && buf[i + 3] == 'a')
                return i + 8;
        got = Fill(s, buf);
        return 0;
    }

    private void OnData(byte[] buffer, int offset, int bytes)
    {
        int n = bytes / 2;
        if (n == 0) return;
        double sum = 0;
        lock (gate)
        {
            for (int i = 0; i < n; i++)
            {
                short s = bigEndian
                    ? (short)((buffer[offset + i * 2] << 8) | buffer[offset + i * 2 + 1])
                    : BitConverter.ToInt16(buffer, offset + i * 2);
                double v = s / 32768.0;
                sum += v * v;
                if (samples.Count < MaxSamples) samples.Add(s);
            }
            Level = (float)Math.Sqrt(sum / n);
            if (Level > SpeechRms)
            {
                SpeechChunks++;
                if (samples.Count - n >= CueSamples) LateSpeechChunks++;
            }
            // The bar for "talking" follows the room: three times the background level, which drops at once in a
            // quiet moment and creeps up slowly. A fixed bar read soft speech on a quiet mic as silence, and
            // auto-stop then ended the recording 30 s in, mid-sentence.
            floor = Level < floor ? Level : Math.Min(Level, floor * 1.003f + 0.00001f);
            if (Level > Math.Clamp(floor * 3, VoiceMin, VoiceRms))
            {
                LastVoiceAt = DateTime.UtcNow;
                LastVoiceSample = samples.Count;
            }
        }
    }

    private void Kill()
    {
        session++;
        var p = proc;
        proc = null;
        if (p != null)
        {
            try { if (!p.HasExited) p.Kill(); } catch { }
            try { reader?.Join(300); } catch { }
            p.Dispose();
        }
        reader = null;
    }

    /**
     * Stops the mic and returns the audio as floats in [-1, 1]. The recorder is asked to finish
     * (not killed outright) and what it still had on the way is read to the end, so the last
     * syllable isn't cut off. That can take up to a quarter of a second: call it off the UI thread
     * where that matters.
     */
    public float[] Stop()
    {
        var p = proc;
        var t = reader;
        if (p != null && t != null)
        {
            Sh.Terminate(p);
            try { t.Join(250); } catch { }
        }
        Kill();
        IsRecording = false;
        Level = 0;
        lock (gate)
        {
            var outp = new float[samples.Count];
            for (int i = 0; i < outp.Length; i++) outp[i] = samples[i] / 32768f;
            samples.Clear();
            return outp;
        }
    }

    public void Dispose() { if (IsRecording) Stop(); }
}
