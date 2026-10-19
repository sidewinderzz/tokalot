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

    private static readonly Dictionary<(string, Kind), string> Cache = new();

    public static void Play(Kind kind) => Play(kind, Core.Settings.Current.SoundSet);

    /** Plays a sound from the given set, for previewing a set before it's picked. */
    public static void Play(Kind kind, string set)
    {
        if (!Core.Settings.Current.Sounds) return;
        set = Tones.Valid(set);
        Task.Run(() =>
        {
            try
            {
                string file;
                lock (Cache)
                {
                    if (!Cache.TryGetValue((set, kind), out file!) || !File.Exists(file))
                    {
                        // Written once per run; the sound server's player needs a file to play. Kept in Tokalot's own
                        // private folder: a fixed name under /tmp could be set up by someone else on the machine.
                        file = Path.Combine(Paths.Dir("sounds"), set + "-" + kind.ToString().ToLowerInvariant() + ".wav");
                        File.WriteAllBytes(file, Wav(Tones.Pcm(set, (Tones.Kind)kind), Tones.Rate));
                        Cache[(set, kind)] = file;
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
}
