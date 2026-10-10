using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/**
 * One-file backup (.zip): settings, dictionary, snippets, styles, usage, history, voice notes (Windows), and
 * optionally recordings and API keys. Keys are left out unless explicitly included.
 */
public static class Backup
{
    public sealed record Summary(int Entries, int Recordings, bool Keys);

    public static Summary Write(string path, bool includeAudio, bool includeKeys)
    {
        var s = Settings.Current;
        var settingsJson = JsonSerializer.SerializeToNode(s, Settings.Json)!.AsObject();
        settingsJson.Remove("encryptedKeys"); // DPAPI blobs only work on this PC; export plain keys instead if asked
        settingsJson.Remove("syncFile");      // where the sync file lives is this device's business
        settingsJson.Remove("syncKeys");
        var manifest = new JsonObject
        {
            ["format"] = 1,
            ["app"] = "tokalot-desktop",
            ["created"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
            ["appVersion"] = Updater.CurrentVersion,
            ["includesKeys"] = includeKeys,
            ["settings"] = settingsJson,
        };
        if (includeKeys)
        {
            var keys = new JsonObject();
            foreach (var (id, _, _) in Catalog.Services) { var k = s.Key(id); if (k.Length > 0) keys[id] = k; }
            manifest["keys"] = keys;
        }

        var recordings = 0;
        if (File.Exists(path)) File.Delete(path);
        using (var zip = ZipFile.Open(path, ZipArchiveMode.Create))
        {
            using (var w = new StreamWriter(zip.CreateEntry("backup.json").Open()))
                w.Write(manifest.ToJsonString(Settings.Json));
            Usage.Flush(); // counts from the last moment may not be on disk yet
            foreach (var name in new[] { "history.json", "usage.json", "notes.json" })
                if (File.Exists(Paths.File(name))) zip.CreateEntryFromFile(Paths.File(name), name);
            if (includeAudio)
                foreach (var f in Directory.GetFiles(Paths.Dir("audio")))
                {
                    zip.CreateEntryFromFile(f, "audio/" + Path.GetFileName(f));
                    recordings++;
                }
        }
        // On Linux and macOS a new file is readable by other users by default; a backup holding keys must not be.
        if (includeKeys && !OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        return new Summary(History.All().Count, recordings, includeKeys);
    }

    /** Replaces current data with the backup's. Keys already on this PC are kept if the backup has none. */
    public static Summary Restore(string path)
    {
        using var zip = ZipFile.OpenRead(path);
        var m = zip.GetEntry("backup.json") ?? throw new InvalidDataException("That file isn't a Tokalot backup");
        JsonObject manifest;
        using (var r = new StreamReader(m.Open())) manifest = JsonNode.Parse(r.ReadToEnd())!.AsObject();
        if (manifest["app"]?.ToString() != "tokalot-desktop")
            throw new InvalidDataException("That's a backup from the Android app; desktop backups use a different format");

        var old = Settings.Current;
        var restored = manifest["settings"].Deserialize<Settings>(Settings.Json) ?? new Settings();
        // Check the rest of the zip reads cleanly before replacing anything, so a damaged backup changes nothing.
        foreach (var name in new[] { "history.json", "usage.json", "notes.json" })
            if (zip.GetEntry(name) is { } je)
            {
                using var r = new StreamReader(je.Open());
                try { JsonNode.Parse(r.ReadToEnd()); }
                catch (JsonException) { throw new InvalidDataException($"The backup's {name} is damaged"); }
            }
        var hasKeys = manifest["includesKeys"]?.GetValue<bool>() == true && manifest["keys"] is JsonObject;
        if (hasKeys)
            foreach (var (id, val) in manifest["keys"]!.AsObject()) restored.SetKey(id, val?.ToString() ?? "");
        else
            restored.EncryptedKeys = new Dictionary<string, string>(old.EncryptedKeys);
        restored.SyncFile = old.SyncFile;
        restored.SyncKeys = old.SyncKeys;
        Settings.Replace(restored);

        var recordings = 0;
        Usage.Reload(); // drops counts waiting to be saved, so they can't land over the restored file
        foreach (var e in zip.Entries)
        {
            if (e.FullName is "history.json" or "usage.json" or "notes.json")
            {
                var tmp = Paths.File(e.FullName + ".restore");
                e.ExtractToFile(tmp, true);
                File.Move(tmp, Paths.File(e.FullName), true);
            }
            else if (e.FullName.StartsWith("audio/") && Regex.IsMatch(e.Name, @"^\d+\.(wma|m4a|wav)$"))
            {
                e.ExtractToFile(Path.Combine(Paths.Dir("audio"), e.Name), true);
                recordings++;
            }
        }
        Usage.Reload();
        History.Reload();
        return new Summary(History.All().Count, recordings, hasKeys);
    }
}
