using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;

namespace Tokalot.Desktop.Core;

/** One voice note: the text of one dictation made as a note (Ctrl+Shift+Win, or New voice note in the tray). */
public sealed class Note
{
    public long Id { get; set; }
    public long Time { get; set; }          // unix ms
    public string Text { get; set; } = "";
    /** The user's own labels, as Notes.CleanTags leaves them (sorted, no repeats). Null in a file from before tags. */
    public List<string>? Tags { get; set; }
}

/** Voice notes (beta), one per dictation, stored as one JSON file next to history. Newest first. Shared through the sync file. */
public static class Notes
{
    private static List<Note>? cache;
    private static readonly object Gate = new();

    private static string FilePath => Paths.File("notes.json");

    /**
     * The file exists but couldn't be read (not corrupt, just unavailable): never save over it. It is
     * read again on later use, and notes made meanwhile are kept and joined to it once it reads.
     */
    private static bool unread;
    private static long triedAt;

    private static List<Note> Load()
    {
        if (cache != null && !(unread && Files.RetryDue(ref triedAt))) return cache;
        List<Note> read;
        try
        {
            read = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<List<Note>>(Files.ReadText(FilePath, unread ? 1 : 6), Settings.Json) ?? new()
                : new();
        }
        catch (JsonException)
        {
            try { File.Copy(FilePath, Paths.File("notes.corrupt.json"), true); } catch { }
            read = new();
        }
        catch
        {
            if (cache == null) { unread = true; triedAt = Environment.TickCount64; cache = new(); }
            return cache;
        }
        var made = unread && cache != null ? cache.Where(n => read.All(x => x.Id != n.Id)).ToList() : new();
        read.InsertRange(0, made);
        cache = read;
        unread = false;
        if (made.Count > 0) try { Save(); } catch { }
        return cache;
    }

    private static void Save()
    {
        if (unread) return;
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(cache, Settings.Json));
        File.Move(tmp, FilePath, true);
    }

    public static List<Note> All() { lock (Gate) return new List<Note>(Load()); }

    /** Saves a new note and returns it. */
    public static Note Add(string text) => Add(text, 0);

    /** Saves a new note with [id] (a recording's, so its folder in NoteImport stays with it), or a new id if 0 or taken. */
    public static Note Add(string text, long id)
    {
        lock (Gate)
        {
            var l = Load();
            var now = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            // Two notes in the same millisecond still get their own ids.
            if (id <= 0 || l.Any(n => n.Id == id)) id = Math.Max(now, l.Count > 0 ? l.Max(n => n.Id) + 1 : now);
            var note = new Note { Id = id, Time = now, Text = text };
            l.Insert(0, note);
            Save();
            Sync.Queue();
            return note;
        }
    }

    /** Gives note [id] these tags (cleaned up first). Syncs. */
    public static void SetTags(long id, IEnumerable<string> tags)
    {
        var clean = CleanTags(tags);
        lock (Gate)
        {
            var n = Load().FirstOrDefault(x => x.Id == id);
            if (n == null) return;
            n.Tags = clean.Count > 0 ? clean : null;
            Save();
        }
        Sync.Queue();
    }

    /** Every tag used on any note, A to Z. */
    public static List<string> AllTags(IEnumerable<Note> notes) => CleanTags(notes.SelectMany(n => n.Tags ?? new()));

    /**
     * Tags as every app keeps them: spaces tidied, commas dropped, at most 30 characters, no repeats whatever the
     * capitals (the first spelling stays), A to Z. Same rules as SyncMerge.cleanTags on Android.
     */
    public static List<string> CleanTags(IEnumerable<string> tags)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var output = new List<string>();
        foreach (var raw in tags)
        {
            var t = System.Text.RegularExpressions.Regex.Replace((raw ?? "").Replace(",", " ").Trim(), @"\s+", " ");
            if (t.Length > 30) t = t[..30].Trim();
            if (t.Length > 0 && seen.Add(t)) output.Add(t);
        }
        return output.OrderBy(t => t.ToLowerInvariant(), StringComparer.Ordinal).ThenBy(t => t, StringComparer.Ordinal).ToList();
    }

    /** Tags as the sync file's notes carry them in this app: one line, comma-separated (so the records compare by value). */
    public static string TagLine(IEnumerable<string>? tags) => string.Join(",", tags ?? Array.Empty<string>());

    public static List<string> TagList(string? line) => string.IsNullOrEmpty(line) ? new() : line.Split(',').ToList();

    public static void Delete(long id)
    {
        lock (Gate) { Load().RemoveAll(x => x.Id == id); Save(); }
        NoteImport.DeleteMedia(id);
        Sync.Queue();
    }

    /** The notes as the sync file holds them; null if the file is there but can't be read, so a sync never takes that for "no notes". */
    public static List<Sync.SyncNote>? ForSync()
    {
        List<Note> l;
        lock (Gate)
        {
            l = Load();
            if (unread) return null;
            l = l.ToList();
        }
        // Titles live with the media, outside the lock: NoteImport takes its own, and asks for the notes while holding it.
        return l.Select(n => new Sync.SyncNote(n.Id, n.Time, n.Text, NoteImport.SyncTitle(n.Id), TagLine(n.Tags))).ToList();
    }

    /** Stores a sync's result, unless a note was added or deleted here since [before] was read. */
    public static bool ApplySynced(List<Sync.SyncNote> before, List<Sync.SyncNote> after)
    {
        HashSet<long> ids;
        lock (Gate)
        {
            if (unread || !Load().Select(n => (n.Id, n.Time, n.Text, TagLine(n.Tags))).SequenceEqual(before.Select(n => (n.Id, n.Time, n.Text, n.Tags ?? "")))) return false;
            cache = after.Select(n => new Note { Id = n.Id, Time = n.Time, Text = n.Text, Tags = string.IsNullOrEmpty(n.Tags) ? null : TagList(n.Tags) }).ToList();
            Save();
            ids = cache.Select(n => n.Id).ToHashSet();
        }
        // Outside the lock: NoteImport takes its own, and asks for the notes while holding it.
        try { NoteImport.Prune(ids); } catch { }
        // A title that came from another device, unless it changed here meanwhile.
        var had = new Dictionary<long, string?>(); foreach (var n in before) had.TryAdd(n.Id, n.Title);
        foreach (var n in after)
        {
            had.TryGetValue(n.Id, out var was);
            if (n.Title == null || n.Title == was || NoteImport.SyncTitle(n.Id) != was) continue;
            try { NoteImport.SetTitle(n.Id, n.Title); } catch { }
        }
        return true;
    }

    /** Drops the in-memory copy so the next read comes from disk (after a restore). */
    public static void Reload()
    {
        lock (Gate) { cache = null; unread = false; }
    }
}

/**
 * Finding notes on the Notes page: the search box, the tag chips, the sort menu and the date range. Same rules as
 * NoteFind on Android.
 */
public static class NoteFind
{
    public enum Sort { Newest, Oldest, Title }

    public static string Label(Sort s) => s switch { Sort.Oldest => "Oldest first", Sort.Title => "Title A–Z", _ => "Newest first" };

    /** What the list shows: Tag null for all tags; From/To a range in unix ms (To exclusive), 0 for none. */
    public sealed record Filter(string Query = "", string? Tag = null, Sort Order = Sort.Newest, long From = 0, long To = 0);

    /** One note as the list sees it. Title: the AI's or the user's, or null. */
    public sealed record Item(long Id, long Time, string Text, string? Title, List<string> Tags);

    /** The words of a search, lowercased. Every one has to be in a note for it to match. */
    public static List<string> Words(string query) =>
        query.ToLowerInvariant().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).ToList();

    public static bool Matches(Item i, List<string> words)
    {
        if (words.Count == 0) return true;
        var hay = ((i.Title ?? "") + "\n" + i.Text + "\n" + string.Join(" ", i.Tags)).ToLowerInvariant();
        return words.All(w => hay.Contains(w, StringComparison.Ordinal));
    }

    /** The notes the filter lets through, in its order. Title A–Z puts untitled notes last, by their first words. */
    public static List<Item> Apply(IEnumerable<Item> items, Filter f)
    {
        var w = Words(f.Query);
        var l = items.Where(i => (f.Tag == null || i.Tags.Any(t => string.Equals(t, f.Tag, StringComparison.OrdinalIgnoreCase))) &&
            (f.From <= 0 || i.Time >= f.From) && (f.To <= 0 || i.Time < f.To) && Matches(i, w));
        return (f.Order switch
        {
            Sort.Oldest => l.OrderBy(i => i.Time).ThenBy(i => i.Id),
            Sort.Title => l.OrderBy(i => string.IsNullOrEmpty(i.Title))
                .ThenBy(i => (string.IsNullOrEmpty(i.Title) ? i.Text.Trim() : i.Title).ToLowerInvariant(), StringComparer.Ordinal)
                .ThenByDescending(i => i.Time),
            _ => l.OrderByDescending(i => i.Time).ThenByDescending(i => i.Id),
        }).ToList();
    }

    /** A bit of the text around the first search word it has (whole words, … where it's cut), or null. */
    public static string? Snippet(string text, List<string> words, int around = 40)
    {
        var flat = System.Text.RegularExpressions.Regex.Replace(text.Trim(), @"\s+", " ");
        var low = flat.ToLowerInvariant();
        var hits = words.Select(w => low.IndexOf(w, StringComparison.Ordinal)).Where(i => i >= 0).ToList();
        if (hits.Count == 0) return null;
        var first = hits.Min();
        var start = Math.Max(0, first - around);
        var end = Math.Min(flat.Length, first + around * 2);
        if (start > 0) { var sp = flat.IndexOf(' ', start); if (sp >= start && sp < first) start = sp + 1; }
        if (end < flat.Length) { var sp = flat.LastIndexOf(' ', end); if (sp > first) end = sp; }
        return (start > 0 ? "…" : "") + flat[start..end] + (end < flat.Length ? "…" : "");
    }

    /** Where each search word is in [s] (start, length), in order, for highlighting. */
    public static List<(int Start, int Length)> Hits(string s, List<string> words)
    {
        var low = s.ToLowerInvariant();
        var output = new List<(int, int)>();
        foreach (var w in words)
            for (var at = low.IndexOf(w, StringComparison.Ordinal); at >= 0; at = low.IndexOf(w, at + w.Length, StringComparison.Ordinal))
                output.Add((at, w.Length));
        return output.OrderBy(h => h.Item1).ToList();
    }

    /** [s] cut into pieces, each marked when it's a search word (overlapping hits are joined). */
    public static List<(string Text, bool Hit)> Pieces(string s, List<string> words)
    {
        var output = new List<(string, bool)>();
        var at = 0;
        foreach (var (start, length) in Hits(s, words))
        {
            if (start + length <= at) continue;
            var from = Math.Max(start, at);
            if (from > at) output.Add((s[at..from], false));
            output.Add((s[from..(start + length)], true));
            at = start + length;
        }
        if (at < s.Length) output.Add((s[at..], false));
        return output;
    }
}
