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
 * One-file backup (.zip): settings, dictionary, snippets, styles, usage, history, and
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
            foreach (var name in new[] { "history.json", "usage.json" })
                if (File.Exists(Paths.File(name))) zip.CreateEntryFromFile(Paths.File(name), name);
            if (includeAudio)
                foreach (var f in Directory.GetFiles(Paths.Dir("audio")))
                {
                    zip.CreateEntryFromFile(f, "audio/" + Path.GetFileName(f));
                    recordings++;
                }
        }
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
        var hasKeys = manifest["includesKeys"]?.GetValue<bool>() == true && manifest["keys"] is JsonObject;
        if (hasKeys)
            foreach (var (id, val) in manifest["keys"]!.AsObject()) restored.SetKey(id, val?.ToString() ?? "");
        else
            restored.EncryptedKeys = new Dictionary<string, string>(old.EncryptedKeys);
        Settings.Replace(restored);

        var recordings = 0;
        foreach (var e in zip.Entries)
        {
            if (e.FullName is "history.json" or "usage.json")
                e.ExtractToFile(Paths.File(e.FullName), true);
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
