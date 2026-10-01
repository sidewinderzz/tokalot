using System;
using System.Collections.Generic;
using NAudio.Wave;

namespace Tokalot.Desktop.Core;

/** Records 16 kHz mono 16-bit audio (what Whisper wants) from the default microphone. */
public sealed class Recorder : IDisposable
{
    public const int SampleRate = 16000;
    private const int MaxSamples = SampleRate * 600; // 10 minute cap
    private const float VoiceRms = 0.015f;            // above this counts as "someone is talking"
    private const float SpeechRms = 0.008f;           // lower bar for "was anything said at all" (quiet mics)

    private WaveInEvent? wave;
    private readonly List<short> samples = new();
    private readonly object gate = new();

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
            wave = new WaveInEvent { WaveFormat = new WaveFormat(SampleRate, 16, 1), BufferMilliseconds = 50 };
            wave.DataAvailable += OnData;
            wave.StartRecording();
            IsRecording = true;
            return true;
        }
        catch
        {
            wave?.Dispose();
            wave = null;
            return false;
        }
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        int n = e.BytesRecorded / 2;
        if (n == 0) return;
        double sum = 0;
        lock (gate)
        {
            for (int i = 0; i < n; i++)
            {
                short s = BitConverter.ToInt16(e.Buffer, i * 2);
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
            if (Level > VoiceRms)
            {
                LastVoiceAt = DateTime.UtcNow;
                LastVoiceSample = samples.Count;
            }
        }
    }

    /** Stops the mic and returns the audio as floats in [-1, 1]. */
    public float[] Stop()
    {
        if (wave != null)
        {
            try { wave.StopRecording(); } catch { }
            wave.DataAvailable -= OnData;
            wave.Dispose();
            wave = null;
        }
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
