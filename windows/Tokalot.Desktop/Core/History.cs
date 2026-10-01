using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;

namespace Tokalot.Desktop.Core;

public sealed class Entry
{
    public long Id { get; set; }
    public long Time { get; set; }          // unix ms
    public string Text { get; set; } = "";  // what was typed
    public string Raw { get; set; } = "";   // straight from speech recognition
    public long DurationMs { get; set; }
    public bool Cleaned { get; set; }        // true if an AI model rewrote it
    public string AppKey { get; set; } = "";
    public string AppLabel { get; set; } = "";
}

/** Every dictation, stored as one JSON file. Newest first. */
public static class History
{
    private const int Max = 5000;
    private static List<Entry>? cache;
    private static readonly object Gate = new();

    /** Raised (on a background thread) whenever history changes. */
    public static event Action? Changed;

    private static string FilePath => Paths.File("history.json");

    private static List<Entry> Load()
    {
        if (cache != null) return cache;
        try
        {
            cache = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<List<Entry>>(File.ReadAllText(FilePath), Settings.Json) ?? new()
                : new();
        }
        catch
        {
            try { File.Copy(FilePath, Paths.File("history.corrupt.json"), true); } catch { }
            cache = new();
        }
        return cache;
    }

    private static void Save()
    {
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(cache, Settings.Json));
        File.Move(tmp, FilePath, true);
    }

    public static List<Entry> All() { lock (Gate) return new List<Entry>(Load()); }

    public static void Add(Entry e)
    {
        lock (Gate)
        {
            var l = Load();
            l.Insert(0, e);
            while (l.Count > Max) l.RemoveAt(l.Count - 1);
            Save();
        }
        Changed?.Invoke();
    }

    public static void Delete(long id)
    {
        lock (Gate) { Load().RemoveAll(x => x.Id == id); Save(); }
        Changed?.Invoke();
    }

    /** Drops the in-memory copy so the next read comes from disk (after a restore). */
    public static void Reload()
    {
        lock (Gate) cache = null;
        Changed?.Invoke();
    }

    public static void NotifyChanged() => Changed?.Invoke();
}

/**
 * Per-month usage and estimated cost, counted from what each API call reports.
 * Estimates from list prices; the provider's billing page is the source of truth.
 */
public static class Usage
{
    // $ per hour of audio, and minimum billed seconds per request (Groq bills at least 10 s).
    private static readonly Dictionary<string, double> SttPrice = new() { ["GROQ"] = 0.04, ["OPENAI"] = 0.36 };
    private static readonly Dictionary<string, double> SttMinSec = new() { ["GROQ"] = 10.0, ["OPENAI"] = 0.0 };
    // $ per million tokens (input, output).
    private static readonly Dictionary<string, (double In, double Out)> LlmPrice = new()
    {
        ["GROQ"] = (0.075, 0.30), ["CLAUDE"] = (1.0, 5.0), ["GEMINI"] = (0.25, 1.50), ["OPENAI"] = (0.10, 0.40),
    };

    public sealed class Month
    {
        public int Dictations { get; set; }
        public int Words { get; set; }
        public int Local { get; set; }
        public int Fillers { get; set; }
        public int Corrections { get; set; }
        public Dictionary<string, double> SttSeconds { get; set; } = new();
        public Dictionary<string, long> TokensIn { get; set; } = new();
        public Dictionary<string, long> TokensOut { get; set; } = new();

        public double SttCost(string id) => SttSeconds.GetValueOrDefault(id) / 3600.0 * SttPrice.GetValueOrDefault(id);
        public double LlmCost(string id)
        {
            var (pi, po) = LlmPrice.GetValueOrDefault(id);
            return TokensIn.GetValueOrDefault(id) / 1e6 * pi + TokensOut.GetValueOrDefault(id) / 1e6 * po;
        }
        public double Total => SttSeconds.Keys.Sum(SttCost) + TokensIn.Keys.Union(TokensOut.Keys).Sum(LlmCost);
    }

    private static Dictionary<string, Month>? data;
    private static readonly object Gate = new();
    private static string FilePath => Paths.File("usage.json");
    private static string Key(DateTime t) => t.ToString("yyyy-MM", CultureInfo.InvariantCulture);

    private static Dictionary<string, Month> Load()
    {
        if (data != null) return data;
        try
        {
            data = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<Dictionary<string, Month>>(File.ReadAllText(FilePath), Settings.Json) ?? new()
                : new();
        }
        catch { data = new(); }
        return data;
    }

    private static void Edit(Action<Month> f)
    {
        lock (Gate)
        {
            var d = Load();
            var k = Key(DateTime.Now);
            if (!d.TryGetValue(k, out var m)) d[k] = m = new Month();
            f(m);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(d, Settings.Json));
        }
    }

    public static void RecordDictation(int words, bool local) => Edit(m => { m.Dictations++; m.Words += words; if (local) m.Local++; });
    public static void RecordEdits(int fillers, int corrections) => Edit(m => { m.Fillers += fillers; m.Corrections += corrections; });
    public static void RecordStt(string id, double seconds) =>
        Edit(m => m.SttSeconds[id] = m.SttSeconds.GetValueOrDefault(id) + Math.Max(seconds, SttMinSec.GetValueOrDefault(id)));
    public static void RecordLlm(string id, long inTok, long outTok) => Edit(m =>
    {
        m.TokensIn[id] = m.TokensIn.GetValueOrDefault(id) + inTok;
        m.TokensOut[id] = m.TokensOut.GetValueOrDefault(id) + outTok;
    });

    public static Month This() { lock (Gate) return Load().GetValueOrDefault(Key(DateTime.Now)) ?? new Month(); }

    /** Most recent months first: (label, month). */
    public static List<(string Label, Month Month)> Recent(int n = 6)
    {
        lock (Gate)
        {
            return Load().OrderByDescending(kv => kv.Key).Take(n)
                .Select(kv => (DateTime.ParseExact(kv.Key, "yyyy-MM", CultureInfo.InvariantCulture).ToString("MMMM yyyy", CultureInfo.InvariantCulture), kv.Value))
                .ToList();
        }
    }

    internal static void Reload() { lock (Gate) data = null; }
}
