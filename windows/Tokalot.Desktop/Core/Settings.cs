using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tokalot.Desktop.Core;

/** Where Tokalot keeps its files: %APPDATA%\Tokalot (survives updates and reinstalls). */
public static class Paths
{
    public static string Root
    {
        get
        {
            // TOKALOT_DATA points somewhere else (used by the screenshot mode, so real data is never touched).
            var p = Environment.GetEnvironmentVariable("TOKALOT_DATA") is { Length: > 0 } custom
                ? custom
                : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Tokalot");
            Directory.CreateDirectory(p);
            return p;
        }
    }

    public static string File(string name) => Path.Combine(Root, name);

    public static string Dir(string name)
    {
        var p = Path.Combine(Root, name);
        Directory.CreateDirectory(p);
        return p;
    }
}

/**
 * Everything the user sets, as one JSON file. API keys are encrypted with Windows' DPAPI,
 * so they can only be read by this Windows user on this PC.
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
    /** Optional sync: the shared file's path on this PC ("" = off), and whether this PC puts its API keys in it. */
    public string SyncFile { get; set; } = "";
    public bool SyncKeys { get; set; }
    /** Encrypted (DPAPI, base64) keys by service id. */
    public Dictionary<string, string> EncryptedKeys { get; set; } = new();

    // ---------- keys ----------

    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Tokalot.Desktop.keys.v1");

    public string Key(string? service)
    {
        if (service == null || !EncryptedKeys.TryGetValue(service, out var enc) || string.IsNullOrEmpty(enc)) return "";
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(enc), Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch { return ""; }
    }

    public void SetKey(string service, string value)
    {
        value = value.Trim();
        if (value.Length == 0) { EncryptedKeys.Remove(service); return; }
        var enc = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), Entropy, DataProtectionScope.CurrentUser);
        EncryptedKeys[service] = Convert.ToBase64String(enc);
    }

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
                catch (IOException)
                {
                    // The file is there but locked: run on defaults for now and never save over it (it holds the API keys).
                    current = new Settings { unread = true };
                }
                catch
                {
                    // Keep the broken file for recovery rather than silently losing it.
                    try { System.IO.File.Copy(f, Paths.File("settings.corrupt.json"), true); } catch { }
                    current = new Settings();
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
