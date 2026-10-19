using System;
using System.Collections.Generic;
using System.Linq;
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

/**
 * Plays a voice note's recording from any point, for the note page (tap a word to hear it from there). Starting
 * part way in needs ffplay or mpv; without them the recording plays from the start.
 */
public static class NotePlayer
{
    private static Process? output;
    private static readonly object Gate = new();
    private static readonly Stopwatch Clock = new();
    private static double from, rate = 1, pausedAt;
    public static string? File { get; private set; }
    public static bool Playing { get { lock (Gate) return output != null; } }
    /** Where playback is now, in seconds (counted from when the player started, at its speed). */
    public static double Position { get { lock (Gate) return output != null ? from + Clock.Elapsed.TotalSeconds * rate : pausedAt; } }
    /** Started, paused, stopped or reached the end. Raised on any thread. */
    public static event Action? Changed;
    /** Set when nothing here could play the recording. */
    public static string? Error { get; private set; }

    private static string? Find(string name)
    {
        foreach (var dir in new[] { "", "/opt/homebrew/bin/", "/usr/local/bin/" })
        {
            var path = dir.Length == 0 ? Sh.Which(name) : System.IO.File.Exists(dir + name) ? dir + name : null;
            if (path != null) return path;
        }
        return null;
    }

    /** The command that plays [file] from [start] at [speed], and whether it could start part way in at that speed. */
    private static (string Path, string[] Args, bool Seeks)? Command(string file, double start, double speed)
    {
        var at = start.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        var x = speed.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        if (Find("ffplay") is { } ff)
            return (ff, new[] { "-nodisp", "-autoexit", "-loglevel", "quiet", "-ss", at, "-af", "atempo=" + x, file }, true);
        if (Find("mpv") is { } mpv) return (mpv, new[] { "--no-video", "--really-quiet", "--start=" + at, "--speed=" + x, file }, true);
        return Fallback(file, speed);
    }

    /** Whether this computer can start a recording part way in, which skipping and clicking a word need. */
    public static bool CanSeek => Find("ffplay") != null || Find("mpv") != null;

    /** Plays [file] from [start] seconds at [speed] (1 = normal); from the start if no player here can skip ahead. */
    public static void Play(string file, double start, double speed)
    {
        Kill();
        Player.Stop();
        Error = null;
        try
        {
            if (Command(file, start, speed) is not { } cmd || Sh.Spawn(cmd.Path, cmd.Args) is not { } p)
            {
                Error = "it needs ffplay (from ffmpeg) or mpv";
                lock (Gate) File = null;
                Changed?.Invoke();
                return;
            }
            lock (Gate) { output = p; from = cmd.Seeks ? start : 0; rate = cmd.Seeks ? speed : 1; Clock.Restart(); File = file; }
            p.EnableRaisingEvents = true;
            p.Exited += (_, _) => { bool current; lock (Gate) current = output == p; if (current) { lock (Gate) pausedAt = 0; Stop(); } };
            Changed?.Invoke();
        }
        catch (Exception e) { Error = e.Message; Stop(); }
    }

    public static void Pause()
    {
        if (!Playing) return;
        var at = Position;
        Kill();
        lock (Gate) pausedAt = at;
        Changed?.Invoke();
    }

    /** Moves playback to [t]: the player is restarted there, or the pause is moved. */
    public static void Seek(double t)
    {
        string? f;
        lock (Gate) f = File;
        if (f == null) return;
        if (Playing) Play(f, Math.Max(0, t), rate);
        else lock (Gate) pausedAt = Math.Max(0, t);
    }

    public static void SetSpeed(double speed)
    {
        string? f;
        lock (Gate) f = File;
        if (f != null && Playing) Play(f, Position, speed);
    }

    private static void Kill()
    {
        Process? o;
        lock (Gate) { o = output; output = null; Clock.Reset(); }
        try { if (o != null && !o.HasExited) o.Kill(); } catch { }
        o?.Dispose();
    }

    public static void Stop(bool notify = true)
    {
        Kill();
        lock (Gate) { File = null; pausedAt = 0; }
        if (notify) Changed?.Invoke();
    }

    /** The stretches of [file] with no voice in them (decoded with ffmpeg), or null if it can't be decoded here. Slow: run it off the UI thread. */
    public static List<NoteMedia.Cut>? FindSilence(string file, IReadOnlyList<NoteMedia.Word> words, double duration)
    {
        if (Find("ffmpeg") is not { } ffmpeg) return null;
        try
        {
            const int Rate = 16000;
            var psi = new ProcessStartInfo(ffmpeg) { RedirectStandardOutput = true, RedirectStandardError = false, UseShellExecute = false };
            foreach (var a in new[] { "-nostdin", "-loglevel", "quiet", "-i", file, "-f", "s16le", "-ac", "1", "-ar", Rate.ToString(), "-" }) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi);
            if (p == null) return null;
            var stream = p.StandardOutput.BaseStream;
            IEnumerable<float> Samples()
            {
                var buf = new byte[Rate * 2];
                int n, carry = 0;
                while ((n = stream.Read(buf, carry, buf.Length - carry)) > 0)
                {
                    n += carry;
                    var whole = n & ~1;
                    for (var i = 0; i < whole; i += 2) yield return (short)(buf[i] | buf[i + 1] << 8) / 32768f;
                    carry = n - whole;
                    if (carry > 0) buf[0] = buf[whole];
                }
            }
            var db = NoteMedia.FrameLoudness(Samples(), Rate).ToArray();
            p.WaitForExit(5000);
            if (db.Length == 0) return null;
            return NoteMedia.SilenceCuts(NoteMedia.SpeechFrames(db), words, duration > 0 ? duration : db.Length * NoteMedia.Frame);
        }
        catch { return null; }
    }

    private static (string, string[], bool)? Fallback(string file, double speed) =>
        Sh.Which("pw-play") is { } pw ? (pw, new[] { file }, false) : Sh.Which("paplay") is { } pa ? (pa, new[] { file }, false) : null;
}
