using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/**
 * Where Tokalot keeps its files: ~/.config/Tokalot (or $XDG_CONFIG_HOME/Tokalot). The folder and
 * everything under it is private to this user (mode 0700): it holds what you dictated.
 */
public static class Paths
{
    private const UnixFileMode Private = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute;
    private static readonly HashSet<string> Secured = new();

    /** Creates the folder if needed and makes sure only this user can enter it (once per run). */
    private static string Own(string p)
    {
        lock (Secured)
        {
            if (Secured.Contains(p) && Directory.Exists(p)) return p;
            if (OperatingSystem.IsWindows()) Directory.CreateDirectory(p);
            else
            {
                Directory.CreateDirectory(p, Private);
                // A folder made by an earlier version (or by hand) may be open to others: close it.
                try { if (System.IO.File.GetUnixFileMode(p) != Private) System.IO.File.SetUnixFileMode(p, Private); } catch { }
            }
            Secured.Add(p);
            return p;
        }
    }

    public static string Root
    {
        get
        {
            // TOKALOT_DATA points somewhere else (used by the screenshot mode, so real data is never touched).
            var p = Environment.GetEnvironmentVariable("TOKALOT_DATA") is { Length: > 0 } custom
                ? custom
                // DoNotVerify: without it a missing ~/.config comes back as "" and the data would land in the current folder.
                : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData, Environment.SpecialFolderOption.DoNotVerify), "Tokalot");
            return Own(p);
        }
    }

    /** True for a copy started with its own data folder (tests, screenshots). It leaves the keyring and autostart alone. */
    public static bool IsTestInstance => Environment.GetEnvironmentVariable("TOKALOT_DATA") is { Length: > 0 };

    public static string File(string name) => Path.Combine(Root, name);

    public static string Dir(string name) => Own(Path.Combine(Root, name));
}

/**
 * Everything the user sets, as one JSON file with the same shape as the Windows app's.
 * API keys are not in this file: Linux has no DPAPI, so they live in the login keyring
 * (through secret-tool) or, without one, in keys.json readable only by this user. See KeyStore.
 */
public sealed class Settings
{
    public string Stt { get; set; } = "GROQ";
    public string Cleanup { get; set; } = "GROQ";
    public Dictionary<string, string> SttModels { get; set; } = new();
    public Dictionary<string, string> CleanupModels { get; set; } = new();
    public Dictionary<string, string> CategoryStyles { get; set; } = new();
    public Dictionary<string, string> AppOverrides { get; set; } = new();
    public string CustomInstructions { get; set; } = Catalog.DefaultInstructions;
    public List<string> Words { get; set; } = new();
    public List<Snippet> Snippets { get; set; } = new();
    public bool AutoStop { get; set; } = true;
    public bool AutoLanguage { get; set; }
    public bool Sounds { get; set; } = true;
    public string Theme { get; set; } = "system";
    public uint Accent { get; set; } = Catalog.Accents[0].Argb;
    public int AudioKeepDays { get; set; } = 30;
    /** Recording indicator: style (ripple, half, bars, edge, disc), screen edge, spot along it (0..1). */
    public string IndicatorStyle { get; set; } = "ripple";
    public string IndicatorDock { get; set; } = "bottom";
    public double IndicatorAlong { get; set; } = 0.5;
    /** Keep a slim bar on screen between dictations (click it to dictate, drag it to move it). */
    public bool ShowIdleIndicator { get; set; } = true;
    public bool LaunchAtStartup { get; set; } = true;
    /** The "Hold to talk" card in the sidebar was closed. */
    public bool HideShortcutTip { get; set; }
    /**
     * Same name as on Windows (the shared Backup code copies it), but on Linux the value is only a
     * note of where that service's key is kept: "keyring" or "file". The key itself is in KeyStore.
     */
    public Dictionary<string, string> EncryptedKeys { get; set; } = new();

    // ---------- keys ----------

    public string Key(string? service)
    {
        if (service == null) return "";
        string? where;
        lock (Gate) { if (!EncryptedKeys.TryGetValue(service, out where) || string.IsNullOrEmpty(where)) return ""; }
        return KeyStore.Get(service, where);
    }

    public void SetKey(string service, string value)
    {
        value = value.Trim();
        if (value.Length == 0)
        {
            bool had;
            lock (Gate) had = EncryptedKeys.Remove(service);
            if (had) KeyStore.Remove(service);
            return;
        }
        var where = KeyStore.Set(service, value);
        lock (Gate) EncryptedKeys[service] = where;
    }

    /** The keyring turned a key away and it went to the file instead: correct the note. (Called from a background thread.) */
    internal void NoteKeyPlace(string service, string where)
    {
        lock (Gate)
        {
            if (!EncryptedKeys.TryGetValue(service, out var w) || w == where) return;
            EncryptedKeys[service] = where;
        }
        try { Save(); } catch { }
    }

    /** The services that have a key noted, and where. A copy, safe to walk on any thread. */
    internal Dictionary<string, string> KeyPlaces() { lock (Gate) return new Dictionary<string, string>(EncryptedKeys); }

    // ---------- derived ----------

    [JsonIgnore] public SttOption SttOption => Catalog.SttById(Stt);
    [JsonIgnore] public CleanupOption CleanupOption => Catalog.CleanupById(Cleanup);

    public string SttModel(SttOption o) =>
        SttModels.TryGetValue(o.Id, out var m) && !string.IsNullOrWhiteSpace(m) ? m : o.Model;

    public string CleanupModel(CleanupOption o) =>
        CleanupModels.TryGetValue(o.Id, out var m) && !string.IsNullOrWhiteSpace(m) ? m : o.Model;

    public StyleOption StyleFor(AppCategory c) =>
        Catalog.StyleById(CategoryStyles.TryGetValue(c.Id, out var s) ? s : c.DefaultStyle);

    [JsonIgnore] public bool CloudSttReady => Stt != "LOCAL" && Key(SttOption.Service).Length > 0;
    [JsonIgnore] public bool CleanupReady => Cleanup != "OFF" && Key(CleanupOption.Service).Length > 0;

    // ---------- load / save ----------

    internal static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private static Settings? current;
    private static readonly object Gate = new();

    /** The live settings object. Call Save() after changing it. */
    public static Settings Current
    {
        get
        {
            lock (Gate)
            {
                if (current != null) return current;
                var f = Paths.File("settings.json");
                try
                {
                    current = System.IO.File.Exists(f)
                        ? JsonSerializer.Deserialize<Settings>(Files.ReadText(f), Json) ?? new Settings()
                        : new Settings();
                }
                catch (JsonException)
                {
                    // Keep the broken file for recovery rather than silently losing it.
                    try { System.IO.File.Copy(f, Paths.File("settings.corrupt.json"), true); } catch { }
                    current = new Settings();
                }
                catch
                {
                    // The file is there but couldn't be read (locked, no permission…): run on defaults for now and never save over it.
                    current = new Settings { unread = true };
                }
                return current;
            }
        }
    }

    private bool unread;

    public void Save()
    {
        if (unread) return;
        lock (Gate)
        {
            var f = Paths.File("settings.json");
            var tmp = f + ".tmp";
            System.IO.File.WriteAllText(tmp, JsonSerializer.Serialize(this, Json));
            System.IO.File.Move(tmp, f, true);
        }
    }

    /** Replaces the live settings (used by restore). */
    internal static void Replace(Settings s)
    {
        lock (Gate) { current = s; }
        s.Save();
    }
}

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
        ? "Keys are stored only on this computer, in your login keyring (through secret-tool). Nothing is synced anywhere."
        : "Keys are stored only on this computer, in " + Pretty(KeysFile) + ", which only your user account can read (not encrypted; install secret-tool to use the login keyring instead). Nothing is synced anywhere.")
        + (Notice != null ? " " + Notice : "");

    private static string KeysFile => Paths.File("keys.json");

    private static string Pretty(string path)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return home.Length > 0 && path.StartsWith(home) ? "~" + path[home.Length..] : path;
    }

    /** Fetches every noted key in the background. Call once at start-up (and after a restore). */
    public static Task Preload(Settings s)
    {
        var places = s.KeyPlaces();
        return preload = Task.Run(() =>
        {
            foreach (var (service, where) in places)
            {
                lock (Gate) { if (Cache.ContainsKey(service)) continue; }
                var value = Fetch(service, where);
                lock (Gate) Cache.TryAdd(service, value);
            }
            Changed?.Invoke();
        });
    }

    /** Blocking: asks the keyring (up to 20 s, in case an unlock prompt is showing), then the file. A failure is remembered as "no key". */
    private static string Fetch(string service, string where)
    {
        string? value = null;
        if (where == Keyring && SecretTool is { } tool)
        {
            var r = Sh.Run(tool, new[] { "lookup", "application", "tokalot", "service", service }, timeoutMs: 20000);
            if (r.Exit == 0) value = r.Out.TrimEnd('\n', '\r');
            else if (r.Exit == -1) Notice = "The login keyring didn't answer when Tokalot started, so keys kept there aren't loaded. Unlock it and restart Tokalot.";
        }
        value ??= ReadFile().GetValueOrDefault(service);
        return value ?? "";
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
            // Not fetched yet: make sure the background fetch is on its way and answer "no key" for now.
            if (preload == null) Preload(Settings.Current);
            return "";
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
