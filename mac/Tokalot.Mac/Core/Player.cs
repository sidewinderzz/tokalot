using System;
using System.Diagnostics;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/** Plays one saved recording at a time, through macOS's own afplay (which also plays .m4a from a Windows backup). */
public static class Player
{
    internal const string Afplay = "/usr/bin/afplay";
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

/**
 * Plays a voice note's recording from any point, for the note page (tap a word to hear it from there). Starting
 * part way in needs ffplay or mpv; without them the recording plays from the start.
 */
public static class NotePlayer
{
    private static Process? output;
    private static readonly object Gate = new();
    private static readonly Stopwatch Clock = new();
    private static double from;
    public static string? File { get; private set; }
    public static bool Playing { get { lock (Gate) return output != null; } }
    /** Where playback is now, in seconds (counted from when the player started). */
    public static double Position => from + Clock.Elapsed.TotalSeconds;
    /** Playback stopped or reached the end. Raised on a worker thread. */
    public static event Action? Changed;

    private static (string Path, string[] Args, bool Seeks)? Command(string file, double start)
    {
        var at = start.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        foreach (var dir in new[] { "", "/opt/homebrew/bin/", "/usr/local/bin/" })
        {
            string? ff = dir.Length == 0 ? Sh.Which("ffplay") : System.IO.File.Exists(dir + "ffplay") ? dir + "ffplay" : null;
            if (ff != null) return (ff, new[] { "-nodisp", "-autoexit", "-loglevel", "quiet", "-ss", at, file }, true);
            string? mpv = dir.Length == 0 ? Sh.Which("mpv") : System.IO.File.Exists(dir + "mpv") ? dir + "mpv" : null;
            if (mpv != null) return (mpv, new[] { "--no-video", "--really-quiet", "--start=" + at, file }, true);
        }
        return Fallback(file);
    }

    /** Plays [file] from [start] seconds (or from the start, if no player here can skip ahead). False if nothing can play it. */
    public static bool Play(string file, double start)
    {
        Stop(false);
        Player.Stop();
        try
        {
            if (Command(file, start) is not { } cmd || Sh.Spawn(cmd.Path, cmd.Args) is not { } p) return false;
            lock (Gate) { output = p; from = cmd.Seeks ? start : 0; Clock.Restart(); File = file; }
            p.EnableRaisingEvents = true;
            p.Exited += (_, _) => { bool current; lock (Gate) current = output == p; if (current) Stop(); };
            if (p.HasExited) { Stop(false); return false; }
            return true;
        }
        catch { Stop(false); return false; }
    }

    public static void Stop(bool notify = true)
    {
        Process? o;
        lock (Gate) { o = output; output = null; File = null; Clock.Reset(); }
        try { if (o != null && !o.HasExited) o.Kill(); } catch { }
        o?.Dispose();
        if (notify) Changed?.Invoke();
    }

    private static (string, string[], bool)? Fallback(string file) =>
        System.IO.File.Exists(Player.Afplay) && !file.EndsWith(".ogg") ? (Player.Afplay, new[] { file }, false) : null;
}
