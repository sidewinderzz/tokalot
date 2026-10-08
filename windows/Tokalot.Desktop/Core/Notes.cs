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

/** Voice notes (beta), one per dictation, stored as one JSON file next to history. Newest first. */
public static class Notes
{
    private static List<Note>? cache;
    private static readonly object Gate = new();

    private static string FilePath => Paths.File("notes.json");

    /** The file exists but couldn't be read (not corrupt, just unavailable): never save over it. */
    private static bool unread;

    private static List<Note> Load()
    {
        if (cache != null) return cache;
        try
        {
            cache = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<List<Note>>(Files.ReadText(FilePath), Settings.Json) ?? new()
                : new();
        }
        catch (JsonException)
        {
            try { File.Copy(FilePath, Paths.File("notes.corrupt.json"), true); } catch { }
            cache = new();
        }
        catch
        {
            unread = true;
            cache = new();
        }
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
            return note;
        }
    }

    public static void Delete(long id)
    {
        lock (Gate) { Load().RemoveAll(x => x.Id == id); Save(); }
    }

    /** Drops the in-memory copy so the next read comes from disk (after a restore). */
    public static void Reload()
    {
        lock (Gate) cache = null;
    }
}
