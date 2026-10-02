using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/**
 * Keeps each recording for playback. Linux saves plain 16 kHz WAV (about 2 MB a minute): the
 * Windows app compresses with Windows' own WMA/AAC encoders, which don't exist here. Recordings
 * restored from a Windows backup (.wma, .m4a) are still listed and play if ffplay or mpv is installed.
 * Old files are pruned.
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
            return f.EndsWith(".wav") ? ReadWav(File.ReadAllBytes(f)) : Decode(f);
        }
        catch { return null; }
    }

    /** 16-bit PCM WAV -> mono floats at 16 kHz (first channel; whole-number rate reductions only, which is all Tokalot writes). */
    private static float[]? ReadWav(byte[] b)
    {
        if (b.Length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;
        int channels = 1, rate = Recorder.SampleRate, bits = 16;
        for (int p = 12; p + 8 <= b.Length;)
        {
            var tag = System.Text.Encoding.ASCII.GetString(b, p, 4);
            long size = BitConverter.ToUInt32(b, p + 4);
            int body = p + 8;
            if (tag == "fmt " && body + 16 <= b.Length)
            {
                channels = Math.Max(1, (int)BitConverter.ToInt16(b, body + 2));
                rate = BitConverter.ToInt32(b, body + 4);
                bits = BitConverter.ToInt16(b, body + 14);
            }
            else if (tag == "data")
            {
                if (bits != 16) return null;
                // A recorder that was piped can leave a bogus length; trust the file size instead.
                long len = Math.Min(size, b.Length - body);
                int frame = 2 * channels, step = Math.Max(1, rate / Recorder.SampleRate);
                var outp = new float[len / frame / step];
                for (int i = 0; i < outp.Length; i++) outp[i] = BitConverter.ToInt16(b, body + i * step * frame) / 32768f;
                return outp;
            }
            p = body + (int)Math.Min(size + (size & 1), int.MaxValue / 2);
        }
        return null;
    }

    /** Recordings from a Windows backup (.wma, .m4a) are decoded by ffmpeg, if it is installed (on a Mac, .m4a by the built-in afconvert). */
    private static float[]? Decode(string file)
    {
        if (OperatingSystem.IsMacOS() && file.EndsWith(".m4a")) return Afconvert(file);
        if (Sh.Which("ffmpeg") is not { } ffmpeg) return null;
        var psi = new ProcessStartInfo(ffmpeg) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in new[] { "-v", "quiet", "-i", file, "-f", "s16le", "-ac", "1", "-ar", Recorder.SampleRate.ToString(), "-" }) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi);
        if (p == null) return null;
        _ = p.StandardError.ReadToEndAsync();
        using var ms = new MemoryStream();
        // Half a minute at most, so a stuck decoder can't leave "Transcribe" spinning forever.
        var copy = p.StandardOutput.BaseStream.CopyToAsync(ms);
        if (!copy.Wait(30000) || !p.WaitForExit(2000))
        {
            try { p.Kill(true); } catch { }
            return null;
        }
        var pcm = ms.ToArray();
        var samples = new float[pcm.Length / 2];
        for (int i = 0; i < samples.Length; i++) samples[i] = BitConverter.ToInt16(pcm, i * 2) / 32768f;
        return samples.Length > 0 ? samples : null;
    }

    /** macOS: afconvert turns the file into a 16 kHz mono WAV in Tokalot's own folder, which is then read like any other. */
    private static float[]? Afconvert(string file)
    {
        var tmp = Path.Combine(Paths.Dir("sounds"), "decode-" + Guid.NewGuid().ToString("N") + ".wav");
        try
        {
            var r = Sh.Run("/usr/bin/afconvert", new[] { "-f", "WAVE", "-d", "LEI16@" + Recorder.SampleRate, "-c", "1", file, tmp }, timeoutMs: 30000);
            return r.Exit == 0 && File.Exists(tmp) ? ReadWav(File.ReadAllBytes(tmp)) : null;
        }
        finally { try { File.Delete(tmp); } catch { } }
    }

    /** Blocking; call off the UI thread. */
    public static void Save(long id, float[] samples)
    {
        try { File.WriteAllBytes(Path.Combine(Dir, id + ".wav"), Net.Wav(samples)); }
        catch { }
    }
}
