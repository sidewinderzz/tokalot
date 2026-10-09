using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Tokalot.Desktop.Core;

/**
 * Optional settings sync with no account or server: one small JSON file that the user keeps in a
 * folder they already sync (OneDrive, Google Drive, Dropbox, Syncthing…). Every Tokalot pointed at
 * the file reads it and adds its own changes. The Android app uses the same file and the same rules.
 *
 * Synced: dictionary, snippets, per-category styles, custom instructions, voice notes, and API keys only if asked.
 * Never synced: history, recordings, usage, per-app overrides, appearance.
 */
public static class Sync
{
    public const string FileName = "tokalot-sync.json";
    private const int Format = 1;

    /** The synced part of the settings. Styles holds only the categories the user set themselves. */
    public sealed record Data(List<string> Words, List<Snippet> Snippets, Dictionary<string, string> Styles, string Instructions)
    {
        /** Voice notes, newest first. Null in a file last written by a version from before notes synced: the notes here are then kept. */
        public List<SyncNote>? Notes { get; init; }
    }

    /** One voice note as the sync file holds it. The id is the time it was made, so two devices never pick the same one. */
    public sealed record SyncNote(long Id, long Time, string Text);

    /** When this device last synced, and what went wrong the last time (null = fine). */
    public static DateTime? LastSynced { get; private set; }
    public static string? LastError { get; private set; }
    /** A sync finished (status changed). localChanged: it brought in changes from another device. */
    public static event Action<bool>? Finished;

    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static Timer? debounce;
    private static string StatePath => Paths.File("sync-state.json");

    private static string Norm(string s) => s.Trim().ToLowerInvariant();

    // ---------- the merge (pure; the unit of behaviour both apps must agree on) ----------

    /**
     * Three-way merge. local = this device now, remote = the file (null if it has no state yet),
     * basis = this device's values after its last sync. Something in the basis but missing from one
     * side was deleted there; a value that still equals the basis here was changed elsewhere.
     */
    public static Data Merge(Data local, Data? remote, Data basis)
    {
        if (remote == null) return local;
        var notes = MergeNotes(local.Notes ?? new(), remote.Notes, basis.Notes ?? new());

        // The file's order and spelling come first and this device's additions go after, so every device
        // settles on the same list and nobody rewrites the file just to reorder it.
        var lw = local.Words.ToLookup(Norm); var rw = remote.Words.ToLookup(Norm); var bw = basis.Words.ToLookup(Norm);
        var words = new List<string>();
        var seen = new HashSet<string>();
        foreach (var w in remote.Words.Concat(local.Words))
        {
            var k = Norm(w);
            if (k.Length == 0 || !seen.Add(k)) continue;
            if (bw.Contains(k) && (!lw.Contains(k) || !rw.Contains(k))) continue; // deleted on one side
            // Spelling: the file's, unless it was respelled here since the last sync.
            var respelledHere = lw.Contains(k) && bw.Contains(k) && lw[k].First() != bw[k].First();
            words.Add(respelledHere || !rw.Contains(k) ? lw[k].First() : rw[k].First());
        }

        var ls = ByTrigger(local.Snippets); var rs = ByTrigger(remote.Snippets); var bs = ByTrigger(basis.Snippets);
        var snippets = new List<Snippet>();
        seen.Clear();
        foreach (var sn in remote.Snippets.Concat(local.Snippets))
        {
            var k = Norm(sn.Trigger);
            if (k.Length == 0 || !seen.Add(k)) continue;
            if (bs.ContainsKey(k) && (!ls.ContainsKey(k) || !rs.ContainsKey(k))) continue;
            if (ls.TryGetValue(k, out var l) && rs.TryGetValue(k, out var r))
                // The file's trigger spelling; this device's text only if it was changed here (or never synced).
                snippets.Add(new Snippet(r.Trigger, bs.TryGetValue(k, out var b) && l.Text == b.Text ? r.Text : l.Text));
            else snippets.Add(sn);
        }

        var styles = new Dictionary<string, string>();
        foreach (var k in local.Styles.Keys.Union(remote.Styles.Keys).Union(basis.Styles.Keys))
        {
            local.Styles.TryGetValue(k, out var l); remote.Styles.TryGetValue(k, out var r); basis.Styles.TryGetValue(k, out var b);
            var v = l == b ? r : l;
            if (v != null) styles[k] = v;
        }

        var instructions = local.Instructions == basis.Instructions ? remote.Instructions : local.Instructions;
        return new Data(words, snippets, styles, instructions) { Notes = notes };
    }

    /**
     * Notes match by id. One that a side had at the last sync and no longer has was deleted there; a text
     * changed since the last sync wins over the unchanged one. Newest first.
     */
    private static List<SyncNote> MergeNotes(List<SyncNote> local, List<SyncNote>? remote, List<SyncNote> basis)
    {
        if (remote == null) return local;
        var mine = new Dictionary<long, SyncNote>(); foreach (var n in local) mine.TryAdd(n.Id, n);
        var theirs = new Dictionary<long, SyncNote>(); foreach (var n in remote) theirs.TryAdd(n.Id, n);
        var was = new Dictionary<long, SyncNote>(); foreach (var n in basis) was.TryAdd(n.Id, n);
        var output = new List<SyncNote>();
        foreach (var id in theirs.Keys.Concat(mine.Keys).Distinct())
        {
            mine.TryGetValue(id, out var here); theirs.TryGetValue(id, out var there); was.TryGetValue(id, out var b);
            if (here != null && there != null) output.Add(b != null && here.Text == b.Text ? there : here);
            else if (b == null) output.Add((here ?? there)!); // else: deleted on one side
        }
        return output.OrderByDescending(n => n.Time).ThenByDescending(n => n.Id).ToList();
    }

    private static Dictionary<string, Snippet> ByTrigger(IEnumerable<Snippet> list)
    {
        var d = new Dictionary<string, Snippet>();
        foreach (var s in list) d.TryAdd(Norm(s.Trigger), s);
        return d;
    }

    public static bool Same(Data a, Data b) =>
        a.Words.SequenceEqual(b.Words) && a.Snippets.SequenceEqual(b.Snippets) && a.Instructions == b.Instructions &&
        a.Styles.Count == b.Styles.Count && a.Styles.All(kv => b.Styles.TryGetValue(kv.Key, out var v) && v == kv.Value) &&
        (a.Notes ?? new()).SequenceEqual(b.Notes ?? new());

    // ---------- the file ----------

    /** Reads the synced part out of a sync file. Throws if it isn't one, or is from a newer Tokalot. */
    public static Data Parse(JsonObject file, string defaultInstructions)
    {
        if (file["app"]?.ToString() != "tokalot-sync") throw new InvalidDataException("That isn't a Tokalot sync file");
        if (((int?)file["format"] ?? 1) > Format) throw new InvalidDataException("Update Tokalot to sync with this file");
        var words = (file["words"] as JsonArray)?.Select(w => w?.ToString() ?? "").Where(w => w.Trim().Length > 0).ToList() ?? new();
        var snippets = (file["snippets"] as JsonArray)?.OfType<JsonObject>()
            .Select(o => new Snippet(o["trigger"]?.ToString() ?? "", o["text"]?.ToString() ?? ""))
            .Where(s => s.Trigger.Trim().Length > 0).ToList() ?? new();
        var styles = (file["styles"] as JsonObject)?.Where(kv => kv.Value != null).ToDictionary(kv => kv.Key, kv => kv.Value!.ToString()) ?? new();
        var notes = (file["notes"] as JsonArray)?.OfType<JsonObject>()
            .Where(o => Num(o["id"]) != null && o["text"] is JsonValue)
            .Select(o => { var id = Num(o["id"])!.Value; return new SyncNote(id, Num(o["time"]) ?? id, o["text"]!.ToString()); })
            .ToList();
        return new Data(words, snippets, styles, file["instructions"]?.ToString() ?? defaultInstructions) { Notes = notes };
    }

    private static long? Num(JsonNode? n) => n is JsonValue v && v.TryGetValue<long>(out var x) ? x : null;

    public static JsonObject ToJson(Data d, JsonObject? keys)
    {
        var o = new JsonObject
        {
            ["app"] = "tokalot-sync",
            ["format"] = Format,
            ["updated"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
            ["by"] = OperatingSystem.IsWindows() ? "Windows" : OperatingSystem.IsMacOS() ? "Mac" : "Linux",
            ["words"] = new JsonArray(d.Words.Select(w => (JsonNode?)w).ToArray()),
            ["snippets"] = new JsonArray(d.Snippets.Select(s => (JsonNode?)new JsonObject { ["trigger"] = s.Trigger, ["text"] = s.Text }).ToArray()),
            ["styles"] = new JsonObject(d.Styles.Select(kv => new KeyValuePair<string, JsonNode?>(kv.Key, kv.Value))),
            ["instructions"] = d.Instructions,
            ["notes"] = new JsonArray((d.Notes ?? new()).Select(n => (JsonNode?)new JsonObject { ["id"] = n.Id, ["time"] = n.Time, ["text"] = n.Text }).ToArray()),
        };
        if (keys is { Count: > 0 }) o["keys"] = keys;
        return o;
    }

    // ---------- running a sync ----------

    private static Data Local(Settings s, List<SyncNote> notes) => new(
        new List<string>(s.Words), new List<Snippet>(s.Snippets), new Dictionary<string, string>(s.CategoryStyles), s.CustomInstructions)
    { Notes = notes };

    private static Data LoadBasis()
    {
        try
        {
            if (File.Exists(StatePath) && JsonNode.Parse(Files.ReadText(StatePath))?["basis"] is JsonObject b)
                return Parse(b, Catalog.DefaultInstructions);
        }
        catch { }
        return new Data(new(), new(), new(), Catalog.DefaultInstructions);
    }

    private static void SaveState(Data basis)
    {
        var o = new JsonObject { ["lastSynced"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(), ["basis"] = ToJson(basis, null) };
        File.WriteAllText(StatePath, o.ToJsonString(Settings.Json));
    }

    /** Status line for Settings: the last error, or how long ago it synced. */
    public static string Status()
    {
        if (LastError != null) return LastError;
        if (LastSynced == null)
        {
            try
            {
                if (File.Exists(StatePath) && (long?)JsonNode.Parse(Files.ReadText(StatePath))?["lastSynced"] is { } t)
                    LastSynced = DateTimeOffset.FromUnixTimeMilliseconds(t).LocalDateTime;
            }
            catch { }
        }
        if (LastSynced == null) return "Not synced yet";
        var min = (int)(DateTime.Now - LastSynced.Value).TotalMinutes;
        return min < 1 ? "Synced just now" : min < 60 ? $"Synced {min} min ago" : min < 1440 ? $"Synced {min / 60} h ago" : $"Synced {LastSynced:MMM d}";
    }

    /** Syncs soon (a couple of seconds after the last change), so a burst of edits is one write. */
    public static void Queue()
    {
        if (Settings.Current.SyncFile.Length == 0) return;
        debounce ??= new Timer(_ => _ = Run());
        debounce.Change(2000, Timeout.Infinite);
    }

    /** One sync, off the caller's thread. Never throws; the result is in LastError. */
    public static Task Run() => Task.Run(async () =>
    {
        var s = Settings.Current;
        var path = s.SyncFile;
        if (path.Length == 0) return;
        await Gate.WaitAsync();
        var localChanged = false;
        try
        {
            JsonObject? file = null;
            if (File.Exists(path))
            {
                var text = Files.ReadText(path);
                if (text.Trim().Length > 0)
                    file = JsonNode.Parse(text) as JsonObject ?? throw new InvalidDataException("That isn't a Tokalot sync file");
            }
            var remote = file == null ? null : Parse(file, Catalog.DefaultInstructions);
            var localNotes = Notes.ForSync() ?? throw new InvalidDataException("Couldn't read your voice notes. Will try again.");
            var local = Local(s, localNotes);
            var merged = Merge(local, remote, LoadBasis());

            // Keys have no basis: take one this device lacks; hand ours over only if the user asked.
            var keys = (file?["keys"] as JsonObject)?.DeepClone().AsObject() ?? new JsonObject();
            var keysChanged = false;
            var noted = false; // KnownKeys grew: worth saving, but nothing the window needs to redraw for
            foreach (var (id, _, _) in Catalog.Services)
            {
                var mine = s.Key(id);
                var theirs = keys[id]?.ToString() ?? "";
                // A key this device has had before and now lacks was cleared here on purpose: leave it cleared.
                if (mine.Length == 0 && theirs.Length > 0 && !s.KnownKeys.Contains(id)) { s.SetKey(id, theirs); mine = theirs; localChanged = true; }
                else if (s.SyncKeys && mine.Length > 0 && mine != theirs) { keys[id] = mine; keysChanged = true; }
                if (mine.Length > 0 && !s.KnownKeys.Contains(id)) { s.KnownKeys.Add(id); noted = true; }
            }

            var notesChanged = !(merged.Notes ?? new()).SequenceEqual(localNotes);
            // A note made or deleted mid-sync: leave it all for the sync that change queued.
            if (notesChanged && !Notes.ApplySynced(localNotes, merged.Notes ?? new())) return;
            if (!Same(merged with { Notes = localNotes }, local))
            {
                s.Words = merged.Words;
                s.Snippets = merged.Snippets;
                s.CategoryStyles = merged.Styles;
                s.CustomInstructions = merged.Instructions;
                localChanged = true;
            }
            if (localChanged || noted) s.Save();
            if (notesChanged) localChanged = true;
            if (remote == null || !Same(merged, remote) || keysChanged)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
                File.WriteAllText(path, ToJson(merged, keys).ToJsonString(Settings.Json));
            }
            SaveState(merged);
            LastSynced = DateTime.Now;
            LastError = null;
        }
        catch (JsonException) { LastError = "That isn't a Tokalot sync file"; }
        catch (InvalidDataException e) { LastError = e.Message; }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            LastError = "The sync file can't be reached. Check the folder, or choose the file again.";
        }
        catch (Exception e) { LastError = "Sync failed: " + e.Message; }
        finally { Gate.Release(); }
        Finished?.Invoke(localChanged);
    });

    /** Stops syncing on this device: forgets the file and the basis. The file and local settings stay as they are. */
    public static void Forget()
    {
        debounce?.Change(Timeout.Infinite, Timeout.Infinite);
        try { File.Delete(StatePath); } catch { }
        LastSynced = null;
        LastError = null;
    }
}
