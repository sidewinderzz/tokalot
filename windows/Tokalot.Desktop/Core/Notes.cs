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
    public static Note Add(string text)
    {
        lock (Gate)
        {
            var l = Load();
            var now = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            // Two notes in the same millisecond still get their own ids.
            var id = Math.Max(now, l.Count > 0 ? l.Max(n => n.Id) + 1 : now);
            var note = new Note { Id = id, Time = now, Text = text };
            l.Insert(0, note);
            Save();
            Sync.Queue();
            return note;
        }
    }

    public static void Delete(long id)
    {
        lock (Gate) { Load().RemoveAll(x => x.Id == id); Save(); }
        Sync.Queue();
    }

    /** The notes as the sync file holds them; null if the file is there but can't be read, so a sync never takes that for "no notes". */
    public static List<Sync.SyncNote>? ForSync()
    {
        lock (Gate)
        {
            var l = Load();
            return unread ? null : l.Select(n => new Sync.SyncNote(n.Id, n.Time, n.Text)).ToList();
        }
    }

    /** Stores a sync's result, unless a note was added or deleted here since [before] was read. */
    public static bool ApplySynced(List<Sync.SyncNote> before, List<Sync.SyncNote> after)
    {
        lock (Gate)
        {
            if (unread || !Load().Select(n => new Sync.SyncNote(n.Id, n.Time, n.Text)).SequenceEqual(before)) return false;
            cache = after.Select(n => new Note { Id = n.Id, Time = n.Time, Text = n.Text }).ToList();
            Save();
            return true;
        }
    }

    /** Drops the in-memory copy so the next read comes from disk (after a restore). */
    public static void Reload()
    {
        lock (Gate) { cache = null; unread = false; }
    }
}
