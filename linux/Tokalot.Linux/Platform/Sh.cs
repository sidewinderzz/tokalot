using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace Tokalot.Desktop.Platform;

/** Small helpers for the command-line tools the Linux build leans on (pw-record, wl-copy, secret-tool…). Nothing goes through a shell. */
public static class Sh
{
    private static readonly ConcurrentDictionary<string, (string? Path, DateTime At)> Found = new();

    /**
     * Full path of a program on PATH, or null. Hits are remembered for good; a miss is looked up
     * again after 30 seconds, so installing wl-clipboard or xdotool doesn't need a restart.
     */
    public static string? Which(string name)
    {
        if (Found.TryGetValue(name, out var hit) && (hit.Path != null || (DateTime.UtcNow - hit.At).TotalSeconds < 30))
            return hit.Path;
        string? path = null;
        try
        {
            foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
            {
                if (dir.Length == 0) continue;
                var p = Path.Combine(dir, name);
                if (File.Exists(p) && Executable(p)) { path = p; break; }
            }
        }
        catch { }
        Found[name] = (path, DateTime.UtcNow);
        return path;
    }

    private static bool Executable(string path)
    {
        if (OperatingSystem.IsWindows()) return true;
        try { return (File.GetUnixFileMode(path) & (UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute)) != 0; }
        catch { return false; }
    }

    public static bool Has(string name) => Which(name) != null;

    /** The first of these programs that is installed, or null. */
    public static string? First(params string[] names) => names.FirstOrDefault(Has);

    /** True in a Wayland session (where Tokalot's own windows still run through XWayland). */
    public static bool IsWayland =>
        string.Equals(Environment.GetEnvironmentVariable("XDG_SESSION_TYPE"), "wayland", StringComparison.OrdinalIgnoreCase)
        || !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("WAYLAND_DISPLAY"));

    public static string Desktop => Environment.GetEnvironmentVariable("XDG_CURRENT_DESKTOP") ?? "";

    public static bool IsGnome => Desktop.Contains("GNOME", StringComparison.OrdinalIgnoreCase);

    public sealed record Result(int Exit, string Out, string Err = "");

    /**
     * Runs a program and returns its exit code and output. Exit is -1 if it couldn't start or
     * took longer than timeoutMs (it is killed then).
     */
    public static Result Run(string file, string[] args, string? stdin = null, int timeoutMs = 2000, bool capture = true)
    {
        try
        {
            var psi = new ProcessStartInfo(file)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardInput = stdin != null,
                // Programs that stay behind to serve the clipboard (wl-copy, xclip) inherit these pipes and
                // would keep them open forever, so only capture output from programs that exit.
                RedirectStandardOutput = capture, RedirectStandardError = capture,
            };
            foreach (var a in args) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi);
            if (p == null) return new Result(-1, "");
            string outp = "", errp = "";
            System.Threading.Tasks.Task<string>? reader = null, errors = null;
            if (capture)
            {
                reader = p.StandardOutput.ReadToEndAsync();
                errors = p.StandardError.ReadToEndAsync();
            }
            if (stdin != null)
            {
                p.StandardInput.Write(stdin);
                p.StandardInput.Close();
            }
            if (!p.WaitForExit(timeoutMs))
            {
                try { p.Kill(true); } catch { }
                return new Result(-1, "");
            }
            if (reader != null && reader.Wait(500)) outp = reader.Result;
            if (errors != null && errors.Wait(100)) errp = errors.Result;
            return new Result(p.ExitCode, outp, errp);
        }
        catch { return new Result(-1, ""); }
    }

    /** Starts a program and doesn't wait for it. */
    public static Process? Spawn(string file, params string[] args)
    {
        try
        {
            var psi = new ProcessStartInfo(file) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var a in args) psi.ArgumentList.Add(a);
            var p = Process.Start(psi);
            if (p != null)
            {
                // Drain so a chatty program can't block on a full pipe.
                _ = p.StandardOutput.ReadToEndAsync();
                _ = p.StandardError.ReadToEndAsync();
            }
            return p;
        }
        catch { return null; }
    }

    [DllImport("libc", SetLastError = true)] private static extern int kill(int pid, int sig);

    /** Asks a program to finish up and exit (SIGTERM), which lets a recorder hand over the audio it still holds. */
    public static void Terminate(Process p)
    {
        try
        {
            if (p.HasExited) return;
            if (OperatingSystem.IsWindows()) p.Kill(); else kill(p.Id, 15);
        }
        catch { }
    }

    /** A desktop notification (the stand-in for Windows' tray balloon). Does nothing if notify-send is missing. */
    public static void Notify(string title, string body)
    {
        // A Mac: Notification Center through AppleScript, with the texts passed as arguments (nothing to escape).
        if (OperatingSystem.IsMacOS())
            Spawn("/usr/bin/osascript", "-e", "on run argv", "-e", "display notification (item 2 of argv) with title (item 1 of argv)", "-e", "end run", title, body)?.Dispose();
        else if (Which("notify-send") is { } n) Spawn(n, "--app-name=Tokalot", title, body)?.Dispose();
    }
}

/**
 * Whether this login session is the one in front and unlocked. The keyboard devices Tokalot reads
 * keep delivering keys on the lock screen and while another user is switched in, and dictation must
 * not start there. Asked of logind through loginctl; where that isn't available, the answer is yes.
 */
public static class Session
{
    private static DateTime checkedAt;
    private static bool usable = true;
    private static readonly object Gate = new();

    /** Blocking for a few milliseconds when the cached answer is older than a second: call off the UI thread. */
    public static bool Usable
    {
        get
        {
            lock (Gate)
            {
                if ((DateTime.UtcNow - checkedAt).TotalMilliseconds < 1000) return usable;
                checkedAt = DateTime.UtcNow;
                usable = Ask();
                return usable;
            }
        }
    }

    private static bool Ask()
    {
        try
        {
            if (Sh.Which("loginctl") is not { } loginctl) return true;
            var id = Environment.GetEnvironmentVariable("XDG_SESSION_ID");
            var args = string.IsNullOrEmpty(id)
                ? new[] { "show-session", "-p", "Active", "-p", "LockedHint" }
                : new[] { "show-session", id, "-p", "Active", "-p", "LockedHint" };
            var r = Sh.Run(loginctl, args, timeoutMs: 500);
            if (r.Exit != 0) return true;
            return !r.Out.Contains("Active=no") && !r.Out.Contains("LockedHint=yes");
        }
        catch { return true; }
    }
}
