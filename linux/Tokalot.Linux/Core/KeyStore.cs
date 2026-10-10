using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/**
 * Where API keys are kept on Linux.
 *   "keyring": the desktop's login keyring (GNOME Keyring, KWallet's Secret Service) through the
 *              secret-tool program from libsecret. Encrypted with your login password.
 *   "file":    keys.json in Tokalot's data folder, readable only by your user (mode 0600). Used
 *              when secret-tool isn't installed or no keyring answers.
 * The keyring can be slow or waiting on an unlock prompt, so it is never asked on the window's
 * thread: keys are fetched once in the background when Tokalot starts (Preload) and everything
 * else reads the copy in memory. Until that fetch finishes a keyring key reads as empty.
 */
public static class KeyStore
{
    public const string Keyring = "keyring", FileStore = "file";

    private static readonly Dictionary<string, string> Cache = new();
    private static readonly object Gate = new();
    private static readonly object WriteGate = new();
    private static readonly List<Task> Pending = new();
    private static bool? keyringWorks;
    private static Task? preload;

    /** Raised (on a background thread) when keys finished loading or the note under the key fields changed. */
    public static event Action? Changed;

    /** Something the user should know (the keyring refused a key, or didn't answer). Shown under the key fields. */
    public static string? Notice { get; private set; }

    private static string? SecretTool =>
        !Paths.IsTestInstance && !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DBUS_SESSION_BUS_ADDRESS"))
            ? Sh.Which("secret-tool") : null;

    /** Which store new keys go to. */
    public static string Backend => SecretTool != null && keyringWorks != false ? Keyring : FileStore;

    /** A sentence for Settings saying where keys are kept. */
    public static string Where => (Backend == Keyring
        ? "Keys are stored only on this computer, in your login keyring (through secret-tool)."
        : "Keys are stored only on this computer, in " + Pretty(KeysFile) + ", which only your user account can read (not encrypted; install secret-tool to use the login keyring instead).")
        + " They never leave it unless you turn on Sync below and choose to include them."
        + (Notice != null ? " " + Notice : "");

    private static string KeysFile => Paths.File("keys.json");

    private static string Pretty(string path)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return home.Length > 0 && path.StartsWith(home) ? "~" + path[home.Length..] : path;
    }

    /** Fetches every noted key in the background. Call once at start-up (and after a restore). */
    private static volatile bool unanswered; // the last fetch gave up waiting on at least one key

    public static Task Preload(Settings s)
    {
        var places = s.KeyPlaces();
        return preload = Task.Run(() =>
        {
            var missed = false;
            foreach (var (service, where) in places)
            {
                lock (Gate) { if (Cache.ContainsKey(service)) continue; }
                var value = Fetch(service, where);
                // No answer in time is not "no key": leave it unknown, so it is asked for again later.
                if (value == null) { missed = true; continue; }
                lock (Gate) Cache.TryAdd(service, value);
            }
            unanswered = missed;
            Changed?.Invoke();
        });
    }

    /** Blocking: asks the keyring (up to 20 s, in case an unlock prompt is showing), then the file. Null when the keyring couldn't say (asked again later). */
    private static string? Fetch(string service, string where)
    {
        string? value = null;
        var timedOut = false;
        if (where == Keyring && SecretTool is { } tool)
        {
            var r = Sh.Run(tool, new[] { "lookup", "application", "tokalot", "service", service }, timeoutMs: 20000);
            if (r.Exit == 0) { value = r.Out.TrimEnd('\n', '\r'); Notice = null; }
            // No answer, or an error (the unlock prompt was cancelled): unknown, not "no key". A key that just
            // isn't there makes secret-tool quit with nothing to say.
            else if (r.Exit == -1 || r.Err.Trim().Length > 0)
            {
                timedOut = true;
                Notice = "The login keyring didn't answer or stayed locked, so keys kept there aren't loaded yet. Unlock it; Tokalot asks again at the next dictation.";
            }
        }
        value ??= ReadFile().GetValueOrDefault(service);
        return value ?? (timedOut ? null : "");
    }

    /** Never waits on the keyring: the copy in memory, or (for a key kept in the file) a quick read of the file. */
    public static string Get(string service, string where)
    {
        lock (Gate)
        {
            if (Cache.TryGetValue(service, out var hit)) return hit;
        }
        if (where == Keyring && SecretTool != null)
        {
            // Not fetched yet: make sure the background fetch is on its way.
            // A finished fetch that gave up waiting (an unlock prompt left open at start-up) is run again.
            // So is one that finished before this key was noted (settings.json was read late) or that left it unknown.
            var loading = preload == null || preload.IsCompleted ? Preload(Settings.Current) : preload;
            // The window's thread never waits (it shows "no key" for a moment). Background work does: a dictation
            // would otherwise fail for want of a key, and sync would take a key from the file over the one in the keyring.
            if (Avalonia.Threading.Dispatcher.UIThread.CheckAccess()) return "";
            try { loading.Wait(25000); } catch { }
            lock (Gate) return Cache.GetValueOrDefault(service, "");
        }
        var value = ReadFile().GetValueOrDefault(service) ?? "";
        lock (Gate) Cache[service] = value;
        return value;
    }

    /** Remembers the key right away and writes it to the store in the background. Returns where it will live. */
    public static string Set(string service, string value)
    {
        lock (Gate) Cache[service] = value;
        var where = Backend;
        Queue(() => Write(service, value));
        return where;
    }

    public static void Remove(string service)
    {
        lock (Gate) Cache[service] = "";
        Queue(() => Write(service, ""));
    }

    private static void Queue(Action write)
    {
        var t = Task.Run(write);
        lock (Pending)
        {
            Pending.RemoveAll(x => x.IsCompleted);
            Pending.Add(t);
        }
    }

    /** Waits for key writes still on their way to the keyring or the file (called when quitting). */
    public static void Flush(int timeoutMs = 6000)
    {
        Task[] waiting;
        lock (Pending) waiting = Pending.ToArray();
        try { Task.WaitAll(waiting, timeoutMs); } catch { }
    }

    private static void Write(string service, string value)
    {
        lock (WriteGate)
        {
            // Typing a key fires one write per character; only the newest value matters.
            lock (Gate) { if (Cache.GetValueOrDefault(service, "") != value) return; }

            var stored = false;
            if (SecretTool is { } tool && keyringWorks != false)
            {
                if (value.Length == 0)
                {
                    Sh.Run(tool, new[] { "clear", "application", "tokalot", "service", service }, timeoutMs: 8000);
                    stored = true;
                }
                else
                {
                    var r = Sh.Run(tool, new[] { "store", "--label=Tokalot " + service + " API key", "application", "tokalot", "service", service },
                        stdin: value, timeoutMs: 20000);
                    stored = r.Exit == 0;
                    keyringWorks = stored;
                    if (!stored)
                    {
                        Notice = "The login keyring didn't take the key (it refused or didn't answer), so it was saved in " + Pretty(KeysFile) + " instead.";
                        App.Log("Keyring store failed (exit " + r.Exit + "); using keys.json");
                    }
                }
            }

            var file = ReadFile();
            if (stored || value.Length == 0)
            {
                // In the keyring (or deleted): make sure no plain copy is left behind.
                if (file.Remove(service)) WriteFile(file);
                return;
            }
            file[service] = value;
            WriteFile(file);
            // The keyring refused, so the note in settings.json has to say "file".
            Settings.Current.NoteKeyPlace(service, FileStore);
            Changed?.Invoke();
        }
    }

    private static Dictionary<string, string> ReadFile()
    {
        try
        {
            if (File.Exists(KeysFile))
                return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(KeysFile)) ?? new();
        }
        catch { }
        return new();
    }

    private static void WriteFile(Dictionary<string, string> keys)
    {
        try
        {
            if (keys.Count == 0) { File.Delete(KeysFile); return; }
            var tmp = KeysFile + ".tmp";
            // Create it private before anything is written into it.
            var options = new FileStreamOptions { Mode = FileMode.Create, Access = FileAccess.Write };
            if (!OperatingSystem.IsWindows()) options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
            using (var f = new FileStream(tmp, options))
            using (var w = new StreamWriter(f))
                w.Write(JsonSerializer.Serialize(keys, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, KeysFile, true);
        }
        catch (Exception e) { App.Log("Couldn't write keys.json: " + e.Message); }
    }
}
