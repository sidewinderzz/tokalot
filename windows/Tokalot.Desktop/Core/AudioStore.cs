using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using NAudio.MediaFoundation;
using NAudio.Wave;

namespace Tokalot.Desktop.Core;

/**
 * Keeps each recording for playback. Saved compressed with Windows' built-in encoders
 * (WMA, falling back to AAC), or as WAV if neither is available. Old files are pruned.
 */
public static class AudioStore
{
    private static string Dir => Paths.Dir("audio");
    private static readonly string[] Exts = { ".wma", ".m4a", ".wav" };

    public static string? FileFor(long id) =>
        Exts.Select(e => Path.Combine(Dir, id + e)).FirstOrDefault(File.Exists);

    public static bool Exists(long id) => FileFor(id) != null;

    public static void Delete(long id)
    {
        foreach (var e in Exts) { try { File.Delete(Path.Combine(Dir, id + e)); } catch { } }
    }

    public static long TotalBytes() => new DirectoryInfo(Dir).GetFiles().Sum(f => f.Length);

    /** Deletes recordings older than [days]. days <= 0 keeps none; int.MaxValue keeps all. */
    public static void Prune(int days)
    {
        if (days == int.MaxValue) return;
        var cutoff = DateTime.Now.AddDays(-Math.Max(days, 0));
        // A recording that still needs transcribing is kept until it's retried or deleted.
        var pending = History.All().Where(e => e.Pending).Select(e => e.Id.ToString()).ToHashSet();
        foreach (var f in new DirectoryInfo(Dir).GetFiles())
        {
            if (pending.Contains(Path.GetFileNameWithoutExtension(f.Name))) continue;
            if (days <= 0 || f.LastWriteTime < cutoff) { try { f.Delete(); } catch { } }
        }
    }

    /** Reads a saved recording back as 16 kHz mono samples (to transcribe it again). Blocking. */
    public static float[]? Load(long id)
    {
        var f = FileFor(id);
        if (f == null) return null;
        try
        {
            using WaveStream reader = f.EndsWith(".wav") ? new WaveFileReader(f) : new MediaFoundationReader(f);
            var sp = reader.ToSampleProvider();
            int rate = sp.WaveFormat.SampleRate, ch = sp.WaveFormat.Channels;
            var all = new List<float>();
            var buf = new float[rate * ch];
            int n;
            while ((n = sp.Read(buf, 0, buf.Length)) > 0)
                for (int i = 0; i + ch <= n; i += ch) all.Add(buf[i]);
            // AAC files were saved at 48 kHz by repeating each sample three times; take every third back.
            int step = Math.Max(1, rate / Recorder.SampleRate);
            if (step == 1) return all.ToArray();
            var outp = new float[all.Count / step];
            for (int i = 0; i < outp.Length; i++) outp[i] = all[i * step];
            return outp;
        }
        catch { return null; }
    }

    /** Blocking; call off the UI thread. */
    public static void Save(long id, float[] samples)
    {
        var pcm = new byte[samples.Length * 2];
        for (int i = 0; i < samples.Length; i++)
        {
            var s = (short)(Math.Clamp(samples[i], -1f, 1f) * 32767);
            pcm[i * 2] = (byte)s; pcm[i * 2 + 1] = (byte)(s >> 8);
        }
        var fmt = new WaveFormat(Recorder.SampleRate, 16, 1);
        try
        {
            MediaFoundationApi.Startup();
            try
            {
                using var src = new RawSourceWaveStream(new MemoryStream(pcm), fmt);
                MediaFoundationEncoder.EncodeToWma(src, Path.Combine(Dir, id + ".wma"), 20000);
                return;
            }
            catch { }
            try
            {
                // Windows' AAC encoder only takes 44.1/48 kHz, so upsample 3x (16k -> 48k).
                var up = new byte[pcm.Length * 3];
                for (int i = 0; i < samples.Length; i++)
                    for (int k = 0; k < 3; k++) { up[(i * 3 + k) * 2] = pcm[i * 2]; up[(i * 3 + k) * 2 + 1] = pcm[i * 2 + 1]; }
                using var src = new RawSourceWaveStream(new MemoryStream(up), new WaveFormat(48000, 16, 1));
                MediaFoundationEncoder.EncodeToAac(src, Path.Combine(Dir, id + ".m4a"), 96000);
                return;
            }
            catch { }
        }
        catch { }
        try
        {
            using var w = new WaveFileWriter(Path.Combine(Dir, id + ".wav"), fmt);
            w.Write(pcm, 0, pcm.Length);
        }
        catch { }
    }
}

/** Plays one saved recording at a time. */
public static class Player
{
    private static WaveOutEvent? output;
    private static WaveStream? reader;
    public static long? PlayingId { get; private set; }
    public static event Action? Changed;

    public static void Play(long id)
    {
        Stop(false);
        var f = AudioStore.FileFor(id);
        if (f == null) return;
        try
        {
            reader = f.EndsWith(".wav") ? new WaveFileReader(f) : new MediaFoundationReader(f);
            var o = output = new WaveOutEvent();
            o.Init(reader);
            o.PlaybackStopped += (_, _) => { if (ReferenceEquals(output, o)) Stop(); };
            o.Play();
            PlayingId = id;
        }
        catch { Stop(false); }
        Changed?.Invoke();
    }

    public static void Stop(bool notify = true)
    {
        var o = output; var r = reader;
        output = null; reader = null;
        try { o?.Stop(); } catch { }
        o?.Dispose(); r?.Dispose();
        var was = PlayingId;
        PlayingId = null;
        if (notify && was != null) Changed?.Invoke();
    }
}
