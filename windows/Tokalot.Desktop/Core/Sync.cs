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
 * Synced: dictionary, snippets, per-category styles, custom instructions, voice notes, the other settings (see
 * SettingNames), each device's counts, API keys only if asked, and dictation history only if asked.
 * Never synced: recordings, cost and token usage, and what belongs to this computer (the on-device model, how long
 * recordings are kept, where the indicator sits, starting with Windows).
 */
public static class Sync
{
    public const string FileName = "tokalot-sync.json";
    private const int Format = 1;
    /**
     * What this version puts in the file, for telling an older Tokalot it is missing out. 1: dictionary,
     * snippets, styles, instructions, notes, word counts. 2: settings, clean-up counts, dictation history.
     * Raise it whenever something new goes in the file, on every platform at once (SyncFormat.LEVEL on Android).
     */
    public const int Level = 2;

    /** The synced part of the settings. Styles holds only the categories the user set themselves. */
    public sealed record Data(List<string> Words, List<Snippet> Snippets, Dictionary<string, string> Styles, string Instructions)
    {
        /** Voice notes, newest first. Null in a file last written by a version from before notes synced: the notes here are then kept. */
        public List<SyncNote>? Notes { get; init; }

        /**
         * Every other synced setting, by its name in the file, each value as canonical JSON text (see Canon) so two
         * values compare with ==. Null in a file or basis from before settings synced.
         */
        public SortedDictionary<string, string>? Settings { get; init; }
    }

    /**
     * One dictation as the sync file holds it (only when a device turned on "Include dictation history"). App is what
     * the app was known by where it was dictated (a Windows process name, an Android package), Label its name to show.
     */
    public sealed record SyncEntry(long Id, long Time, string Text, string Raw, long DurationMs, bool Cleaned, string App, string Label, bool Own)
    {
        /** Tells two versions of an entry apart. The basis keeps only this, not the entry. Same as Android's SyncEntry.hash. */
        public int Hash => Fnv(Text + "\u0000" + Raw + "\u0000" + (Own ? "true" : "false") + "\u0000" + (Cleaned ? "true" : "false"));
    }

    /** FNV-1a, so a hash is the same in every run (string.GetHashCode isn't). */
    public static int Fnv(string s)
    {
        unchecked
        {
            var h = (int)2166136261;
            foreach (var c in s) { h ^= c; h *= 16777619; }
            return h;
        }
    }

    /**
     * One voice note as the sync file holds it. The id is the time it was made, so two devices never pick the same one.
     * Title: null when the note has none, "" when it was cleared on purpose (so no device makes a new one).
     */
    public sealed record SyncNote(long Id, long Time, string Text, string? Title = null);

    private static JsonNode? NoteJson(SyncNote n)
    {
        var o = new JsonObject { ["id"] = n.Id, ["time"] = n.Time, ["text"] = n.Text };
        if (n.Title != null) o["title"] = n.Title;
        return o;
    }

    /** One month of one device's counts, as the sync file holds them: words, dictations, clean-ups and on-device dictations. */
    public sealed record UsageMonth(long Words, long Dictations, long Fillers = 0, long Corrections = 0, long Offline = 0);

    /**
     * One device's usage in the sync file, under that device's own id. Only that device ever changes its
     * entry, and nothing is added up in the file, so a count can't be counted twice or lost to another device.
     */
    public sealed record DeviceUsage(string Name, long Updated, Dictionary<string, UsageMonth> Months)
    {
        public long Words => Months.Values.Sum(m => m.Words);
        public long Dictations => Months.Values.Sum(m => m.Dictations);
        public long Fillers => Months.Values.Sum(m => m.Fillers);
        public long Corrections => Months.Values.Sum(m => m.Corrections);
        public long Offline => Months.Values.Sum(m => m.Offline);
        public bool SameCounts(DeviceUsage o) =>
            Name == o.Name && Months.Count == o.Months.Count && Months.All(kv => o.Months.TryGetValue(kv.Key, out var m) && m == kv.Value);
    }

    /** The file was last written by a newer Tokalot that syncs more than this one does: Home asks for an update. */
    public static bool NewerElsewhere
    {
        get
        {
            if (newer == null)
            {
                try { newer = File.Exists(StatePath) && (bool?)JsonNode.Parse(Files.ReadText(StatePath))?["newer"] == true; }
                catch { newer = false; }
            }
            return newer.Value && Settings.Current.SyncFile.Length > 0;
        }
    }
    private static bool? newer;

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
    public static Data Merge(Data local, Data? remote, Data basis) => Merge(local, remote, basis, (_, _) => true);

    public static Data Merge(Data local, Data? remote, Data basis, Func<string, string, bool> usable)
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
        var settings = MergeSettings(local.Settings ?? new(), remote.Settings, basis.Settings, usable);
        return new Data(words, snippets, styles, instructions) { Notes = notes, Settings = settings };
    }

    /**
     * Settings are compared one by one. local holds every setting this device syncs. One this device doesn't have
     * (haptics from a phone) passes through the file untouched, and so does a value this device can't use (usable
     * false, say a choice only a newer version knows). Otherwise a setting changed here since the last sync wins, and
     * one left alone follows the file. A setting that isn't in the basis yet (the first sync, or one added in an
     * update) counts as left alone, so a device that joins takes the settings already in the file.
     */
    public static SortedDictionary<string, string> MergeSettings(
        IDictionary<string, string> local, IDictionary<string, string>? remote, IDictionary<string, string>? basis, Func<string, string, bool> usable)
    {
        var output = new SortedDictionary<string, string>(StringComparer.Ordinal);
        if (remote == null) { foreach (var (k, v) in local) output[k] = v; return output; }
        foreach (var k in remote.Keys.Concat(local.Keys).Distinct())
        {
            local.TryGetValue(k, out var l); remote.TryGetValue(k, out var r);
            string? b = null; basis?.TryGetValue(k, out b);
            var v = l == null ? r : r == null ? l : !usable(k, r) ? r : (b ?? l) == l ? r : l;
            if (v != null) output[k] = v;
        }
        return output;
    }

    /** At most this many dictations are in the file: the newest ones. */
    public const int HistoryMax = 1000;

    public sealed record HistoryResult(List<SyncEntry> File, List<SyncEntry> Put, HashSet<long> Delete);

    private static readonly IComparer<SyncEntry> NewestFirst =
        Comparer<SyncEntry>.Create((a, b) => b.Time != a.Time ? b.Time.CompareTo(a.Time) : b.Id.CompareTo(a.Id));

    /**
     * Dictation history, matched by id like notes: one a side had at the last sync (basis holds their ids and hashes)
     * and no longer has was deleted there, and a version changed since the last sync wins. The file keeps only the
     * newest HistoryMax; one that drops off the end was not deleted, so it stays wherever it is.
     */
    public static HistoryResult MergeHistory(List<SyncEntry> local, List<SyncEntry>? remote, Dictionary<long, int> basis)
    {
        var mine = new Dictionary<long, SyncEntry>(); foreach (var e in local) mine.TryAdd(e.Id, e);
        var theirs = new Dictionary<long, SyncEntry>(); foreach (var e in remote ?? new()) theirs.TryAdd(e.Id, e);
        // A full file's oldest entry: anything older fell off the end rather than being deleted.
        var edge = theirs.Count >= HistoryMax ? theirs.Values.OrderBy(e => e, NewestFirst).Last() : null;
        bool FellOff(SyncEntry e) => edge != null && NewestFirst.Compare(e, edge) > 0;
        var kept = new List<SyncEntry>();
        var put = new List<SyncEntry>();
        var delete = new HashSet<long>();
        foreach (var id in theirs.Keys.Concat(mine.Keys).Distinct())
        {
            mine.TryGetValue(id, out var here); theirs.TryGetValue(id, out var there);
            var known = basis.TryGetValue(id, out var was);
            if (here != null && there != null)
            {
                var pick = here.Hash == there.Hash || (known && was == here.Hash) ? there : here;
                kept.Add(pick);
                if (pick.Hash != here.Hash) put.Add(pick);
            }
            else if (here != null) { if (!known) kept.Add(here); else if (!FellOff(here)) delete.Add(id); }
            else if (there != null && !known) { kept.Add(there); put.Add(there); }
        }
        return new HistoryResult(kept.OrderBy(e => e, NewestFirst).Take(HistoryMax).ToList(), put, delete);
    }

    // ---------- settings ----------

    /**
     * The settings that sync besides the lists above, by their names in settings.json (and in the file; Android uses
     * the same names). A name this app's Settings doesn't have just passes through the file.
     */
    public static readonly string[] SettingNames =
    {
        "stt", "cleanup", "sttModels", "cleanupModels", "autoStop", "quickSkip", "liveStt", "weakSignal", "autoLanguage",
        "polish", "sounds", "soundSet", "theme", "accent", "learnWords", "learnedWords", "dismissedWords", "notesBeta",
        "indicatorStyle", "showIdleIndicator", "appOverrides", "noteSkipSilence", "noteSkipUms", "noteSummaryLevel", "noteSpeed",
    };

    /** The playback speeds a voice note offers, in the order the speed button steps through them. */
    public static readonly double[] NoteSpeeds = { 1, 1.25, 1.5, 2 };

    private static readonly string[] Themes = { "system", "light", "dark" };
    private static readonly string[] IndicatorStyles = { "ripple", "half", "bars", "edge", "disc" };

    private static System.Reflection.PropertyInfo? Prop(string name) =>
        typeof(Settings).GetProperties().FirstOrDefault(p => p.CanWrite && Settings.Json.PropertyNamingPolicy!.ConvertName(p.Name) == name);

    /** A setting's value as JSON text with nothing left to chance: object keys sorted, empty strings in an object dropped. */
    public static string? Canon(JsonNode? n)
    {
        switch (n)
        {
            case null: return null;
            case JsonObject o:
                return "{" + string.Join(",", o.OrderBy(kv => kv.Key, StringComparer.Ordinal)
                    .Select(kv => (kv.Key, V: Canon(kv.Value))).Where(x => x.V != null && x.V != "\"\"")
                    .Select(x => JsonSerializer.Serialize(x.Key) + ":" + x.V)) + "}";
            case JsonArray a:
                return "[" + string.Join(",", a.Select(Canon).Where(x => x != null)) + "]";
            case JsonValue v:
                switch (v.GetValueKind())
                {
                    case JsonValueKind.String: return JsonSerializer.Serialize(v.GetValue<string>());
                    case JsonValueKind.True: return "true";
                    case JsonValueKind.False: return "false";
                    case JsonValueKind.Number:
                        var d = double.Parse(v.ToJsonString(), System.Globalization.CultureInfo.InvariantCulture);
                        return d == Math.Floor(d) && Math.Abs(d) < 9e15
                            ? ((long)d).ToString(System.Globalization.CultureInfo.InvariantCulture)
                            : d.ToString("R", System.Globalization.CultureInfo.InvariantCulture);
                    default: return null;
                }
            default: return null;
        }
    }

    /** This device's synced settings as Canon text. */
    private static SortedDictionary<string, string> LocalSettings(Settings s)
    {
        var node = JsonSerializer.SerializeToNode(s, Settings.Json) as JsonObject ?? new JsonObject();
        var output = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var name in SettingNames)
            if (node.TryGetPropertyValue(name, out var v) && Canon(v) is { } c) output[name] = c;
        return output;
    }

    /** Whether a synced setting's value from the file is one this version can use. */
    public static bool Usable(string name, string text)
    {
        try
        {
            var n = JsonNode.Parse(text);
            string? str = n is JsonValue jv && jv.GetValueKind() == JsonValueKind.String ? jv.GetValue<string>() : null;
            switch (name)
            {
                case "stt": return Catalog.Stt.Any(x => x.Id == str);
                case "cleanup": return Catalog.Cleanup.Any(x => x.Id == str);
                case "theme": return Themes.Contains(str);
                case "soundSet": return str != null && Tones.Valid(str) == str;
                case "indicatorStyle": return IndicatorStyles.Contains(str);
                case "noteSummaryLevel": return n is JsonValue lv && lv.TryGetValue<double>(out var l) && l == Math.Floor(l) && l is >= 1 and <= 5;
                case "noteSpeed": return n is JsonValue sv && sv.TryGetValue<double>(out var sp) && NoteSpeeds.Any(x => Math.Abs(x - sp) < 0.001);
                case "appOverrides":
                    return n is JsonObject obj && obj.All(kv => kv.Value is JsonValue v && v.GetValueKind() == JsonValueKind.String &&
                        Catalog.Categories.Any(c => c.Id == v.GetValue<string>()));
            }
            var p = Prop(name);
            if (p == null) return false;
            JsonSerializer.Deserialize(n, p.PropertyType, Settings.Json);
            return true;
        }
        catch { return false; }
    }

    /** Puts synced settings into s. True if any changed. */
    private static bool ApplySettings(Settings s, IDictionary<string, string> local, IDictionary<string, string>? merged)
    {
        var changed = false;
        foreach (var (name, text) in merged ?? new SortedDictionary<string, string>())
        {
            if (!local.TryGetValue(name, out var mine) || mine == text || !Usable(name, text) || Prop(name) is not { } p) continue;
            p.SetValue(s, JsonSerializer.Deserialize(JsonNode.Parse(text), p.PropertyType, Settings.Json));
            changed = true;
        }
        return changed;
    }

    /**
     * Notes match by id. One that a side had at the last sync and no longer has was deleted there; a text
     * changed since the last sync wins over the unchanged one, and so does a title, on its own. A side that doesn't
     * know titles (an older version) never clears one. Newest first.
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
            if (here != null && there != null)
            {
                var title = here.Title != b?.Title ? here.Title ?? there.Title : there.Title ?? here.Title;
                output.Add((b != null && here.Text == b.Text ? there : here) with { Title = title });
            }
            else if (b == null) output.Add((here ?? there)!); // else: deleted on one side
        }
        return output.OrderByDescending(n => n.Time).ThenByDescending(n => n.Id).ToList();
    }

    /**
     * The usage the file should have: every other device's entry exactly as the file has it, and this
     * device's (mine, under id) in place of its own. There is nothing to merge, because each entry has
     * one writer. An entry whose counts haven't changed is kept as it was, so it doesn't make a write.
     */
    public static Dictionary<string, DeviceUsage> MergeUsage(Dictionary<string, DeviceUsage>? remote, string id, DeviceUsage mine)
    {
        var output = remote == null ? new Dictionary<string, DeviceUsage>() : new Dictionary<string, DeviceUsage>(remote);
        output.TryGetValue(id, out var had);
        if (had != null && had.SameCounts(mine)) { }
        else if (had == null && mine.Months.Count == 0) { } // nothing counted here yet: no entry to add
        else output[id] = mine;
        return output;
    }

    public static bool SameUsage(Dictionary<string, DeviceUsage>? a, Dictionary<string, DeviceUsage>? b)
    {
        a ??= new(); b ??= new();
        return a.Count == b.Count && a.All(kv => b.TryGetValue(kv.Key, out var o) && o.Updated == kv.Value.Updated && o.SameCounts(kv.Value));
    }

    private static Dictionary<string, Snippet> ByTrigger(IEnumerable<Snippet> list)
    {
        var d = new Dictionary<string, Snippet>();
        foreach (var s in list) d.TryAdd(Norm(s.Trigger), s);
        return d;
    }

    /**
     * True when the file has no state (missing or empty) but this device already shared something through
     * it. That is a cloud placeholder or another device mid-write far more often than a real fresh start,
     * and treating it as one would write only this device's settings and make every other device delete the rest.
     */
    public static bool RemoteLost(Data? remote, Data basis) =>
        remote == null && (basis.Words.Count > 0 || basis.Snippets.Count > 0 || basis.Styles.Count > 0 || (basis.Notes?.Count ?? 0) > 0 ||
            (basis.Settings?.Count ?? 0) > 0);

    public static bool Same(Data a, Data b) =>
        a.Words.SequenceEqual(b.Words) && a.Snippets.SequenceEqual(b.Snippets) && a.Instructions == b.Instructions &&
        a.Styles.Count == b.Styles.Count && a.Styles.All(kv => b.Styles.TryGetValue(kv.Key, out var v) && v == kv.Value) &&
        (a.Notes ?? new()).SequenceEqual(b.Notes ?? new()) &&
        (a.Settings == null && b.Settings == null || (a.Settings ?? new()).SequenceEqual(b.Settings ?? new()));

    /** History was turned on or off here: what was shared before says nothing about the next time. */
    public static void ForgetHistoryBasis()
    {
        try
        {
            if (File.Exists(StatePath) && JsonNode.Parse(Files.ReadText(StatePath)) is JsonObject o && o.Remove("historyBasis"))
                WriteSafely(StatePath, o.ToJsonString(Settings.Json));
        }
        catch { }
    }

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
            .Select(o => { var id = Num(o["id"])!.Value; return new SyncNote(id, Num(o["time"]) ?? id, o["text"]!.ToString(), o["title"] is JsonValue t ? t.ToString() : null); })
            .ToList();
        SortedDictionary<string, string>? settings = null;
        if (file["settings"] is JsonObject so)
        {
            settings = new(StringComparer.Ordinal);
            foreach (var (k, v) in so) if (Canon(v) is { } c) settings[k] = c;
        }
        return new Data(words, snippets, styles, file["instructions"]?.ToString() ?? defaultInstructions) { Notes = notes, Settings = settings };
    }

    /** The file's "history"; null if it has none. An entry that doesn't fit is skipped. */
    public static List<SyncEntry>? ParseHistory(JsonNode? node) =>
        (node as JsonArray)?.OfType<JsonObject>()
            .Where(o => Num(o["id"]) != null && o["text"] is JsonValue)
            .Select(o =>
            {
                var id = Num(o["id"])!.Value;
                static string Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";
                static bool Flag(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;
                return new SyncEntry(id, Num(o["time"]) ?? id, Str(o["text"]), Str(o["raw"]), Math.Max(0, Num(o["dur"]) ?? 0),
                    Flag(o["ai"]), Str(o["app"]), Str(o["label"]), Flag(o["own"]));
            })
            .ToList();

    public static JsonArray HistoryJson(IEnumerable<SyncEntry> history) => new(history.Select(e =>
    {
        var o = new JsonObject
        {
            ["id"] = e.Id, ["time"] = e.Time, ["text"] = e.Text, ["raw"] = e.Raw, ["dur"] = e.DurationMs,
            ["ai"] = e.Cleaned, ["app"] = e.App, ["label"] = e.Label,
        };
        if (e.Own) o["own"] = true;
        return (JsonNode?)o;
    }).ToArray());

    private static long? Num(JsonNode? n) => n is JsonValue v && v.TryGetValue<long>(out var x) ? x : null;

    private static readonly System.Text.RegularExpressions.Regex MonthKey = new(@"^\d{4}-\d{2}$");

    /** The file's "usage" object; null if it has none. Anything that doesn't fit is skipped, never an error. */
    public static Dictionary<string, DeviceUsage>? ParseUsage(JsonNode? node)
    {
        if (node is not JsonObject u) return null;
        var output = new Dictionary<string, DeviceUsage>();
        foreach (var (id, dn) in u)
        {
            if (dn is not JsonObject d || id.Trim().Length == 0) continue;
            var months = new Dictionary<string, UsageMonth>();
            if (d["months"] is JsonObject m)
                foreach (var (k, cn) in m)
                    if (cn is JsonObject c && MonthKey.IsMatch(k))
                        months[k] = new UsageMonth(
                            Math.Max(0, Num(c["words"]) ?? 0), Math.Max(0, Num(c["dictations"]) ?? 0), Math.Max(0, Num(c["fillers"]) ?? 0),
                            Math.Max(0, Num(c["corrections"]) ?? 0), Math.Max(0, Num(c["offline"]) ?? 0));
            output[id] = new DeviceUsage(d["name"] is JsonValue nv && nv.TryGetValue<string>(out var name) ? name : "", Num(d["updated"]) ?? 0, months);
        }
        return output;
    }

    public static JsonObject UsageJson(Dictionary<string, DeviceUsage> usage)
    {
        var u = new JsonObject();
        foreach (var (id, d) in usage.OrderBy(kv => kv.Key, StringComparer.Ordinal))
        {
            var months = new JsonObject();
            foreach (var (k, m) in d.Months.OrderBy(kv => kv.Key, StringComparer.Ordinal))
            {
                var o = new JsonObject { ["words"] = m.Words, ["dictations"] = m.Dictations };
                // Left out when zero, so a month only has what was counted.
                if (m.Fillers > 0) o["fillers"] = m.Fillers;
                if (m.Corrections > 0) o["corrections"] = m.Corrections;
                if (m.Offline > 0) o["offline"] = m.Offline;
                months[k] = o;
            }
            u[id] = new JsonObject { ["name"] = d.Name, ["updated"] = d.Updated, ["months"] = months };
        }
        return u;
    }

    /** usage null or empty leaves "usage" out of the file, and history null leaves "history" out. */
    public static JsonObject ToJson(Data d, JsonObject? keys, Dictionary<string, DeviceUsage>? usage = null, JsonArray? history = null, int level = Level)
    {
        var o = new JsonObject
        {
            ["app"] = "tokalot-sync",
            ["format"] = Format,
            ["level"] = Math.Max(level, Level),
            ["updated"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
            ["by"] = OperatingSystem.IsWindows() ? "Windows" : OperatingSystem.IsMacOS() ? "Mac" : "Linux",
            ["words"] = new JsonArray(d.Words.Select(w => (JsonNode?)w).ToArray()),
            ["snippets"] = new JsonArray(d.Snippets.Select(s => (JsonNode?)new JsonObject { ["trigger"] = s.Trigger, ["text"] = s.Text }).ToArray()),
            ["styles"] = new JsonObject(d.Styles.Select(kv => new KeyValuePair<string, JsonNode?>(kv.Key, kv.Value))),
            ["instructions"] = d.Instructions,
            ["notes"] = new JsonArray((d.Notes ?? new()).Select(NoteJson).ToArray()),
        };
        if (d.Settings != null)
            o["settings"] = new JsonObject(d.Settings.Select(kv => new KeyValuePair<string, JsonNode?>(kv.Key, JsonNode.Parse(kv.Value))));
        if (usage is { Count: > 0 }) o["usage"] = UsageJson(usage);
        if (history != null) o["history"] = history;
        if (keys is { Count: > 0 }) o["keys"] = keys;
        return o;
    }

    // ---------- running a sync ----------

    private static Data Local(Settings s, List<SyncNote> notes)
    {
        // The window may be editing these lists right now; a copy that catches it mid-change is simply taken again.
        for (int i = 0; ; i++)
        {
            try
            {
                return new(new List<string>(s.Words), new List<Snippet>(s.Snippets), new Dictionary<string, string>(s.CategoryStyles), s.CustomInstructions)
                { Notes = notes, Settings = LocalSettings(s) };
            }
            catch (InvalidOperationException) when (i < 5) { Thread.Sleep(20); }
        }
    }

    /** Writes a temporary file next to path and swaps it in, so a crash or a full disk never leaves half a file. */
    private static void WriteSafely(string path, string text)
    {
        var tmp = path + ".tmp";
        try
        {
            File.WriteAllText(tmp, text);
            File.Move(tmp, path, true);
        }
        catch
        {
            try { File.Delete(tmp); } catch { }
            throw;
        }
    }

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

    /** Every device's usage as the file had it after the last sync, kept so the totals show between syncs. */
    private static Dictionary<string, DeviceUsage>? lastUsage;

    /** Each dictation's id and hash as the file had them after the last sync; empty with history off here. */
    private static Dictionary<long, int> LoadHistoryBasis()
    {
        var output = new Dictionary<long, int>();
        try
        {
            if (File.Exists(StatePath) && JsonNode.Parse(Files.ReadText(StatePath))?["historyBasis"] is JsonObject h)
                foreach (var (k, v) in h)
                    if (long.TryParse(k, out var id) && v is JsonValue jv && jv.TryGetValue<int>(out var hash)) output[id] = hash;
        }
        catch { }
        return output;
    }

    private static void SaveState(Data basis, Dictionary<string, DeviceUsage> usage, List<SyncEntry>? history)
    {
        var o = new JsonObject
        {
            ["lastSynced"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(), ["basis"] = ToJson(basis, null), ["usage"] = UsageJson(usage),
            ["newer"] = newer == true,
        };
        if (history != null) o["historyBasis"] = new JsonObject(history.Select(e => new KeyValuePair<string, JsonNode?>(e.Id.ToString(System.Globalization.CultureInfo.InvariantCulture), e.Hash)));
        WriteSafely(StatePath, o.ToJsonString(Settings.Json));
        lastUsage = usage;
    }

    /** This device's id in the sync file's usage, made the first time it's needed. Kept in the settings, so a backup carries it. */
    private static string DeviceId(Settings s)
    {
        if (s.UsageDevice.Length == 0) { s.UsageDevice = Guid.NewGuid().ToString(); s.Save(); }
        return s.UsageDevice;
    }

    private static DeviceUsage MyUsage() => new(
        (OperatingSystem.IsWindows() ? "Windows" : OperatingSystem.IsMacOS() ? "Mac" : "Linux") + " · " + Environment.MachineName,
        DateTimeOffset.Now.ToUnixTimeMilliseconds(), Usage.ForSync());

    /** Words on every device that shares the sync file. Null when sync is off. */
    public sealed record Everywhere(long Month, long Total, List<DeviceUsage> Each);

    public static Everywhere? AllDevices()
    {
        var s = Settings.Current;
        if (s.SyncFile.Length == 0) return null;
        var cached = lastUsage;
        if (cached == null)
        {
            try { if (File.Exists(StatePath)) cached = ParseUsage(JsonNode.Parse(Files.ReadText(StatePath))?["usage"]); }
            catch { }
            lastUsage = cached ??= new();
        }
        // The file's entries as of the last sync, with this device's own counts as they are right now.
        var all = s.UsageDevice.Length == 0 ? cached : MergeUsage(cached, s.UsageDevice, MyUsage());
        var key = DateTime.Now.ToString("yyyy-MM", System.Globalization.CultureInfo.InvariantCulture);
        var each = all.Values.OrderByDescending(d => d.Words).ToList();
        return new Everywhere(each.Sum(d => d.Months.GetValueOrDefault(key)?.Words ?? 0), each.Sum(d => d.Words), each);
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
            var basis = LoadBasis();
            if (RemoteLost(remote, basis))
                throw new InvalidDataException("The sync file is missing or empty right now. Will try again. To start it over, stop syncing and choose it again.");
            var merged = Merge(local, remote, basis, Usable);
            var withHistory = s.SyncHistory;
            var localHistory = withHistory ? History.ForSync() ?? throw new InvalidDataException("Couldn't read your history. Will try again.") : null;
            var history = localHistory == null ? null : MergeHistory(localHistory, ParseHistory(file?["history"]), LoadHistoryBasis());

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

            // A word, snippet, style or the instructions changed while the file was being read: applying now
            // would undo it. Leave it all and sync again, which picks the change up.
            if (!Same(Local(s, localNotes), local)) { Queue(); return; }
            var notesChanged = !(merged.Notes ?? new()).SequenceEqual(localNotes);
            // A note made or deleted mid-sync: leave it all for the sync that change queued.
            if (notesChanged && !Notes.ApplySynced(localNotes, merged.Notes ?? new())) return;
            // Same for a dictation that landed mid-sync.
            if (history != null && localHistory != null && !History.ApplySynced(localHistory, history.Put, history.Delete)) { Queue(); return; }
            var historyChanged = history is { } h && (h.Put.Count > 0 || h.Delete.Count > 0);
            // Settings this computer doesn't have, or can't use, stay in the file without being applied here.
            if (ApplySettings(s, local.Settings ?? new(), merged.Settings)) localChanged = true;
            if (!Same(merged with { Notes = localNotes, Settings = local.Settings }, local))
            {
                s.Words = merged.Words;
                s.Snippets = merged.Snippets;
                s.CategoryStyles = merged.Styles;
                s.CustomInstructions = merged.Instructions;
                localChanged = true;
            }
            if (localChanged || noted) s.Save();
            if (notesChanged || historyChanged) localChanged = true;
            // Usage isn't merged: this device only ever replaces its own entry (see MergeUsage).
            var remoteUsage = ParseUsage(file?["usage"]);
            var usage = MergeUsage(remoteUsage, DeviceId(s), MyUsage());
            // With history off here, whatever other devices put in the file passes through untouched.
            var remoteHistory = file?["history"] as JsonArray;
            var fileHistory = history != null ? HistoryJson(history.File) : (JsonArray?)remoteHistory?.DeepClone();
            var historyDiffers = history != null && (remoteHistory == null || !(ParseHistory(remoteHistory) ?? new()).SequenceEqual(history.File));
            var level = Num(file?["level"]) is { } lv ? (int)Math.Max(1, lv) : 1;
            if (remote == null || !Same(merged, remote) || keysChanged || !SameUsage(usage, remoteUsage) || historyDiffers || level < Level)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
                WriteSafely(path, ToJson(merged, keys, usage, fileHistory, level).ToJsonString(Settings.Json));
            }
            // A newer Tokalot on another device puts things in the file that this one leaves out: Home asks for an update.
            newer = level > Level;
            SaveState(merged, usage, history?.File);
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
        lastUsage = null;
        newer = null;
        LastSynced = null;
        LastError = null;
    }
}
