using System;
using System.Diagnostics;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/** Plays one saved recording at a time, through macOS's own afplay (which also plays .m4a from a Windows backup). */
public static class Player
{
    private const string Afplay = "/usr/bin/afplay";
    private static Process? output;
    private static readonly object Gate = new();
    public static long? PlayingId { get; private set; }
    public static event Action? Changed;

    /** The program that plays a WAV file (the feedback tones use it too). */
    internal static (string Path, string[] Args)? WavPlayer(string file) =>
        System.IO.File.Exists(Afplay) ? (Afplay, new[] { file }) : null;

    public static void Play(long id)
    {
        Stop(false);
        var f = AudioStore.FileFor(id);
        if (f == null) return;
        try
        {
            // afplay can't read .wma; those (from a Windows backup) just don't play.
            if (!f.EndsWith(".wma") && WavPlayer(f) is { } cmd && Sh.Spawn(cmd.Path, cmd.Args) is { } p)
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
