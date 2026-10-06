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
 * Where Tokalot keeps its files: ~/.config/Tokalot (or $XDG_CONFIG_HOME/Tokalot; on a Mac
 * ~/Library/Application Support/Tokalot). The folder and
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
                // A Mac keeps app data in ~/Library/Application Support.
                : OperatingSystem.IsMacOS()
                    ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Library", "Application Support", "Tokalot")
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
 * (through secret-tool) or, without one, in keys.json readable only by this user; a Mac keeps them
 * in the Keychain. See KeyStore.
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
    /** Windows-only features; kept here so a backup that passes through this app doesn't lose them. */
    public bool LearnWords { get; set; }
    public List<string> LearnedWords { get; set; } = new();
    /** Beta: skip the AI cleanup when a short dictation has nothing for it to fix. */
    public bool QuickSkip { get; set; }
    /** Send long dictations to the speech service in pieces while they are still being spoken. */
    public bool LiveStt { get; set; } = true;
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
    /** The "Hands-free · Ctrl+Super to finish" reminder above the indicator was closed. */
    public bool HideHandsFreeHint { get; set; }
    /** Let the cleanup model reword for clarity. Off: the user's own words are kept. */
    public bool Polish { get; set; }
    /** Optional sync: the shared file's path on this computer ("" = off), and whether this computer puts its API keys in it. */
    public string SyncFile { get; set; } = "";
    public bool SyncKeys { get; set; }
    /** Services this device has held a key for. One of these with no key now was cleared by the user, so sync doesn't refill it. */
    public List<string> KnownKeys { get; set; } = new();
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
        Sync.Queue(); // no-op unless sync is on
    }

    /** Replaces the live settings (used by restore). */
    internal static void Replace(Settings s)
    {
        lock (Gate) { current = s; }
        s.Save();
    }
}
