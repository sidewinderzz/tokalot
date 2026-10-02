using System;
using System.Diagnostics;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/** Plays one saved recording at a time, through the sound server's player program. */
public static class Player
{
    private static Process? output;
    private static readonly object Gate = new();
    public static long? PlayingId { get; private set; }
    public static event Action? Changed;

    /** Programs that play a WAV file, in order of preference. */
    internal static (string Path, string[] Args)? WavPlayer(string file)
    {
        if (Sh.Which("pw-play") is { } pw) return (pw, new[] { file });
        if (Sh.Which("paplay") is { } pa) return (pa, new[] { file });
        if (Sh.Which("aplay") is { } al) return (al, new[] { "-q", file });
        return AnyPlayer(file);
    }

    private static (string Path, string[] Args)? AnyPlayer(string file)
    {
        if (Sh.Which("ffplay") is { } ff) return (ff, new[] { "-nodisp", "-autoexit", "-loglevel", "quiet", file });
        if (Sh.Which("mpv") is { } mpv) return (mpv, new[] { "--no-video", "--really-quiet", file });
        return null;
    }

    public static void Play(long id)
    {
        Stop(false);
        var f = AudioStore.FileFor(id);
        if (f == null) return;
        try
        {
            var cmd = f.EndsWith(".wav") ? WavPlayer(f) : AnyPlayer(f);
            if (cmd != null && Sh.Spawn(cmd.Value.Path, cmd.Value.Args) is { } p)
            {
                lock (Gate) { output = p; PlayingId = id; }
                p.EnableRaisingEvents = true;
                p.Exited += (_, _) => { bool current; lock (Gate) current = output == p; if (current) Stop(); };
                if (p.HasExited) Stop(false);
            }
        }
        catch { Stop(false); }
        Changed?.Invoke();
    }

    public static void Stop(bool notify = true)
    {
        Process? o;
        long? was;
        lock (Gate)
        {
            o = output;
            output = null;
            was = PlayingId;
            PlayingId = null;
        }
        try { if (o != null && !o.HasExited) o.Kill(); } catch { }
        o?.Dispose();
        if (notify && was != null) Changed?.Invoke();
    }
}
