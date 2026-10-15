using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;

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
    public bool Pending { get; set; }        // recording kept but not transcribed (it failed or was cancelled)
}

internal static class Files
{
    /** Reads a file, waiting out a brief lock (antivirus, a backup in progress). tries: 1 reads once without waiting. */
    public static string ReadText(string path, int tries = 6)
    {
        for (int i = 1; ; i++)
        {
            try { return File.ReadAllText(path); }
            catch (IOException) when (i < tries) { Thread.Sleep(100); }
        }
    }

    /**
     * For a file that couldn't be read: true when it is time to try again (at most every two seconds),
     * so one lock at start-up doesn't leave the file unread, and unsaved, for the whole session.
     */
    public static bool RetryDue(ref long lastTry)
    {
        var now = Environment.TickCount64;
        if (now - lastTry < 2000) return false;
        lastTry = now;
        return true;
    }
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

    /**
     * The file exists but couldn't be read (not corrupt, just unavailable): never save over it. It is
     * read again on later use, and what was added meanwhile is kept and joined to it once it reads.
     */
    private static bool unread;
    private static long triedAt;

    private static List<Entry> Load()
    {
        if (cache != null && !(unread && Files.RetryDue(ref triedAt))) return cache;
        List<Entry> read;
        try
        {
            read = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<List<Entry>>(Files.ReadText(FilePath, unread ? 1 : 6), Settings.Json) ?? new()
                : new();
        }
        catch (JsonException)
        {
            try { File.Copy(FilePath, Paths.File("history.corrupt.json"), true); } catch { }
            read = new();
        }
        catch
        {
            if (cache == null) { unread = true; triedAt = Environment.TickCount64; cache = new(); }
            return cache;
        }
        if (unread && cache is { Count: > 0 })
        {
            // Dictations made while the file was out of reach: a retried one takes its old place, new ones go in front.
            var fresh = new List<Entry>();
            foreach (var e in cache)
            {
                var i = read.FindIndex(x => x.Id == e.Id);
                if (i >= 0) read[i] = e; else fresh.Add(e);
            }
            read.InsertRange(0, fresh);
            while (read.Count > Max) read.RemoveAt(read.Count - 1);
            cache = read;
            unread = false;
            try { Save(); } catch { }
        }
        else
        {
            cache = read;
            unread = false;
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

    /** Swaps in a new version of the entry with the same id (a retried transcription); adds it if it's gone. */
    public static void Replace(Entry e)
    {
        lock (Gate)
        {
            var l = Load();
            var i = l.FindIndex(x => x.Id == e.Id);
            if (i >= 0) l[i] = e; else l.Insert(0, e);
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
        lock (Gate) { cache = null; unread = false; }
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

        /** Adds another month's counts to this one. */
        public void Add(Month o)
        {
            Dictations += o.Dictations; Words += o.Words; Local += o.Local; Fillers += o.Fillers; Corrections += o.Corrections;
            foreach (var (k, v) in o.SttSeconds) SttSeconds[k] = SttSeconds.GetValueOrDefault(k) + v;
            foreach (var (k, v) in o.TokensIn) TokensIn[k] = TokensIn.GetValueOrDefault(k) + v;
            foreach (var (k, v) in o.TokensOut) TokensOut[k] = TokensOut.GetValueOrDefault(k) + v;
        }
    }

    private static Dictionary<string, Month>? data;
    private static readonly object Gate = new();
    private static string FilePath => Paths.File("usage.json");
    private static string Key(DateTime t) => t.ToString("yyyy-MM", CultureInfo.InvariantCulture);

    /** As in History: a file that couldn't be read is tried again later, and what was counted meanwhile is added to it. */
    private static bool unread;
    private static long triedAt;
    private static bool dirty;
    private static Timer? saveTimer;

    static Usage()
    {
        // Saves are put off for a moment (one dictation counts several things); quitting writes what is waiting.
        AppDomain.CurrentDomain.ProcessExit += (_, _) => Flush();
    }

    private static Dictionary<string, Month> Load()
    {
        if (data != null && !(unread && Files.RetryDue(ref triedAt))) return data;
        Dictionary<string, Month> read;
        try
        {
            read = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<Dictionary<string, Month>>(Files.ReadText(FilePath, unread ? 1 : 6), Settings.Json) ?? new()
                : new();
        }
        catch (JsonException) { read = new(); }
        catch
        {
            if (data == null) { unread = true; triedAt = Environment.TickCount64; data = new(); }
            return data;
        }
        if (unread && data != null)
        {
            foreach (var (k, m) in data)
                if (read.TryGetValue(k, out var had)) had.Add(m); else read[k] = m;
            dirty = data.Count > 0;
        }
        data = read;
        unread = false;
        if (dirty) SaveSoon();
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
            dirty = true;
            SaveSoon();
        }
    }

    private static void SaveSoon()
    {
        saveTimer ??= new Timer(_ => Flush());
        saveTimer.Change(1000, Timeout.Infinite);
    }

    /** Writes counts still waiting to be saved (before a backup, and when quitting). */
    public static void Flush()
    {
        lock (Gate)
        {
            if (!dirty || unread || data == null) return;
            dirty = false;
            // Counting usage must never fail a dictation, and a crash mid-write must not empty the file.
            try
            {
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(data, Settings.Json));
                File.Move(tmp, FilePath, true);
            }
            catch { }
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

    /** Every month counted on this device, added together. */
    public static Month AllTime()
    {
        lock (Gate)
        {
            var total = new Month();
            foreach (var m in Load().Values) total.Add(m);
            return total;
        }
    }

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

    /** This device's words and dictations per month, as the sync file holds them. */
    public static Dictionary<string, Sync.UsageMonth> ForSync()
    {
        lock (Gate)
        {
            return Load().Where(kv => kv.Value.Words > 0 || kv.Value.Dictations > 0)
                .ToDictionary(kv => kv.Key, kv => new Sync.UsageMonth(kv.Value.Words, kv.Value.Dictations));
        }
    }

    internal static void Reload() { lock (Gate) { data = null; dirty = false; unread = false; } }
}

/**
 * The numbers behind the word stats page. Words per month count every device that shares the sync file
 * (this one alone when it's the only one); pace, streaks and apps come from this device's history.
 */
public static class WordStats
{
    public sealed record MonthRow(string Key, long Words, long Dictations)
    {
        public string Label => DateTime.ParseExact(Key, "yyyy-MM", CultureInfo.InvariantCulture).ToString("MMMM yyyy", CultureInfo.InvariantCulture);
        public string Short => DateTime.ParseExact(Key, "yyyy-MM", CultureInfo.InvariantCulture).ToString("MMM yyyy", CultureInfo.InvariantCulture);
    }
    public sealed record AppRow(string Label, long Words);
    public sealed record Streaks(int Days, int Best, int Current);

    public sealed record Breakdown(
        List<MonthRow> Months, long Words, long Dictations, List<Sync.DeviceUsage> Devices,
        Usage.Month Here, double? Pace, int Longest, long LongestAt, Streaks Days, DayOfWeek? BusiestDay, List<AppRow> Apps)
    {
        public MonthRow? Best => Months.Where(m => m.Words > 0).OrderByDescending(m => m.Words).FirstOrDefault();
        public long Average => Months.Count == 0 ? 0 : Words / Months.Count;
        public string Since => Months.Count == 0 ? "" : Months[0].Label;
    }

    /** At most this many months are listed (a clock that was once set years back mustn't make a huge list). */
    public const int MaxMonths = 120;

    /** Every month from the first one with words up to [now], oldest first, the devices' counts added together. */
    public static List<MonthRow> Months(IEnumerable<Dictionary<string, Sync.UsageMonth>> devices, string now)
    {
        var sum = new SortedDictionary<string, (long W, long D)>(StringComparer.Ordinal);
        foreach (var d in devices)
            foreach (var (k, m) in d)
            {
                var had = sum.GetValueOrDefault(k);
                sum[k] = (had.W + m.Words, had.D + m.Dictations);
            }
        var counted = sum.Where(kv => kv.Value.W > 0 || kv.Value.D > 0).Select(kv => kv.Key).ToList();
        if (counted.Count == 0) return new();
        DateTime Parse(string k) => DateTime.ParseExact(k, "yyyy-MM", CultureInfo.InvariantCulture);
        var first = Parse(counted[0]);
        var last = Parse(string.CompareOrdinal(counted[^1], now) > 0 ? counted[^1] : now);
        var rows = new List<MonthRow>();
        for (var t = first; t <= last; t = t.AddMonths(1))
        {
            var k = t.ToString("yyyy-MM", CultureInfo.InvariantCulture);
            var c = sum.GetValueOrDefault(k);
            rows.Add(new MonthRow(k, c.W, c.D));
        }
        return rows.Count > MaxMonths ? rows.Skip(rows.Count - MaxMonths).ToList() : rows;
    }

    /** Days with a dictation, the longest run of days in a row, and the run that reaches today (or yesterday). */
    public static Streaks DayStreaks(IEnumerable<int> dayNumbers, int today)
    {
        var days = dayNumbers.Distinct().OrderBy(d => d).ToList();
        int best = 0, run = 0, prev = int.MinValue;
        foreach (var d in days)
        {
            run = d == prev + 1 ? run + 1 : 1;
            best = Math.Max(best, run);
            prev = d;
        }
        var set = days.ToHashSet();
        var from = set.Contains(today) ? today : today - 1;
        int current = 0;
        while (set.Contains(from - current)) current++;
        return new Streaks(days.Count, best, current);
    }

    private static int DayNumber(long ms) => DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime).DayNumber;

    public static Breakdown Build()
    {
        var now = DateTime.Now.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        var everywhere = Sync.AllDevices();
        var devices = everywhere is { Each.Count: > 1 } ? everywhere.Each : new List<Sync.DeviceUsage>();
        var perDevice = devices.Count > 1 ? devices.Select(d => d.Months).ToList() : new List<Dictionary<string, Sync.UsageMonth>> { Usage.ForSync() };
        var months = Months(perDevice, now);

        var spoken = History.All().Where(e => !e.Pending && e.Text.Length > 0).ToList();
        var counted = spoken.Select(e => (Entry: e, Words: TextTools.WordCount(e.Text))).ToList();
        var timed = spoken.Where(e => e.DurationMs > 0 && e.Raw.Length > 0).ToList();
        var minutes = timed.Sum(e => e.DurationMs) / 60000.0;
        double? pace = minutes > 0 ? timed.Sum(e => TextTools.WordCount(e.Raw)) / minutes : null;
        var longest = counted.OrderByDescending(c => c.Words).FirstOrDefault();
        var days = DayStreaks(spoken.Select(e => DayNumber(e.Time)), DateOnly.FromDateTime(DateTime.Now).DayNumber);
        DayOfWeek? busiest = days.Days < 3 ? null : counted
            .GroupBy(c => DateTimeOffset.FromUnixTimeMilliseconds(c.Entry.Time).LocalDateTime.DayOfWeek)
            .OrderByDescending(g => g.Sum(c => c.Words)).First().Key;
        var apps = counted.Where(c => c.Entry.AppLabel.Length > 0).GroupBy(c => c.Entry.AppLabel)
            .Select(g => new AppRow(g.Key, g.Sum(c => (long)c.Words))).OrderByDescending(a => a.Words).Take(5).ToList();

        return new Breakdown(
            months, months.Sum(m => m.Words), months.Sum(m => m.Dictations), devices, Usage.AllTime(),
            pace, longest.Words, longest.Entry?.Time ?? 0, days, busiest, apps);
    }

    /** How long typing the words would have taken at 40 words a minute, said the short way. */
    public static string TypingTime(long words)
    {
        var minutes = words / 40.0;
        if (minutes < 60) return Math.Round(minutes).ToString("0", CultureInfo.InvariantCulture) + " min";
        var hours = Short(minutes / 60);
        return hours + (hours == "1" ? " hour" : " hours");
    }

    /** What the words would fill: paperback pages (about 300 words each), or novels (about 90,000). Empty below a page. */
    public static string Equivalent(long words)
    {
        if (words >= 90_000) { var n = Short(words / 90_000.0); return n + (n == "1" ? " novel" : " novels"); }
        return words >= 300 ? (words / 300).ToString(CultureInfo.InvariantCulture) + (words < 600 ? " paperback page" : " paperback pages") : "";
    }

    /** One decimal under 10 (dropped when it's .0), whole numbers above. */
    private static string Short(double v) => v < 10 ? v.ToString("0.#", CultureInfo.InvariantCulture) : Math.Round(v).ToString("0", CultureInfo.InvariantCulture);
}
