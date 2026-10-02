using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/** Soft feedback sounds (the desktop stand-in for the phone's haptics), generated in code. */
public static class Sounds
{
    public enum Kind { Start, Stop, Done, Cancel, Error }

    private static readonly Dictionary<Kind, string> Cache = new();

    public static void Play(Kind kind)
    {
        if (!Core.Settings.Current.Sounds) return;
        Task.Run(() =>
        {
            try
            {
                string file;
                lock (Cache)
                {
                    if (!Cache.TryGetValue(kind, out file!) || !File.Exists(file))
                    {
                        // Written once per run; the sound server's player needs a file to play. Kept in Tokalot's own
                        // private folder: a fixed name under /tmp could be set up by someone else on the machine.
                        file = Path.Combine(Paths.Dir("sounds"), kind.ToString().ToLowerInvariant() + ".wav");
                        File.WriteAllBytes(file, Wav(Make(kind), 44100));
                        Cache[kind] = file;
                    }
                }
                if (Player.WavPlayer(file) is not { } cmd) return;
                using var p = Sh.Spawn(cmd.Path, cmd.Args);
                p?.WaitForExit(3000);
            }
            catch { }
        });
    }

    private static byte[] Wav(byte[] pcm, int rate)
    {
        using var ms = new MemoryStream(44 + pcm.Length);
        using var w = new BinaryWriter(ms);
        w.Write("RIFF"u8); w.Write(36 + pcm.Length); w.Write("WAVE"u8);
        w.Write("fmt "u8); w.Write(16); w.Write((short)1); w.Write((short)1);
        w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16);
        w.Write("data"u8); w.Write(pcm.Length);
        w.Write(pcm);
        w.Flush();
        return ms.ToArray();
    }

    private static byte[] Make(Kind kind)
    {
        // (frequency Hz, duration ms) notes; gentle and quiet.
        (double f, int ms)[] notes = kind switch
        {
            Kind.Start => new[] { (660.0, 55), (990.0, 70) },
            Kind.Stop => new[] { (880.0, 45) },
            Kind.Done => new[] { (784.0, 50), (1175.0, 80) },
            Kind.Cancel => new[] { (520.0, 70) },
            _ => new[] { (330.0, 90), (0.0, 40), (330.0, 90) },
        };
        const int rate = 44100;
        using var ms = new MemoryStream();
        foreach (var (f, dur) in notes)
        {
            int n = rate * dur / 1000;
            for (int i = 0; i < n; i++)
            {
                double env = Math.Min(1, Math.Min(i / (rate * 0.006), (n - i) / (rate * 0.03))); // soft attack/release
                double v = f == 0 ? 0 : Math.Sin(2 * Math.PI * f * i / rate) * env * 0.12;
                short s = (short)(v * short.MaxValue);
                ms.WriteByte((byte)s); ms.WriteByte((byte)(s >> 8));
            }
        }
        return ms.ToArray();
    }
}
