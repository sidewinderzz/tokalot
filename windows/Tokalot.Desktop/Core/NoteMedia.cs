using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/**
 * The parts of a voice-note recording that need no UI, the same as the Android app's NoteMedia: lining Whisper's
 * word times up with its punctuated text, the stretches playback can skip, and the prompts and parsing for
 * summaries and the index. Times are in seconds.
 */
public static class NoteMedia
{
    public sealed record Word(string Text, double Start, double End);
    public sealed record Segment(double Start, double End, string Text);
    public sealed record Point(double Time, string Topic, string Text);

    private static readonly Regex Strip = new("[^a-z0-9'-]");
    public static string Bare(string w) => Strip.Replace(w.ToLowerInvariant(), "");

    /** The words to show, with times: each word of the segment text takes the time of the matching timed word. */
    public static List<Word> Align(IReadOnlyList<Segment> segments, IReadOnlyList<Word> timed)
    {
        var output = new List<Word>();
        var j = 0;
        foreach (var seg in segments)
        {
            var tokens = seg.Text.Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (tokens.Length == 0) continue;
            if (timed.Count == 0)
            {
                var step = Math.Max(0, seg.End - seg.Start) / tokens.Length;
                for (var i = 0; i < tokens.Length; i++) output.Add(new Word(tokens[i], seg.Start + i * step, seg.Start + (i + 1) * step));
                continue;
            }
            foreach (var t in tokens)
            {
                var b = Bare(t);
                var hit = -1;
                if (b.Length > 0)
                    for (var k = j; k < Math.Min(j + 4, timed.Count); k++)
                        if (Bare(timed[k].Text) == b) { hit = k; break; }
                if (hit >= 0)
                {
                    output.Add(new Word(t, timed[hit].Start, timed[hit].End));
                    j = hit + 1;
                }
                else
                {
                    var from = output.Count > 0 ? output[^1].End : seg.Start;
                    var to = j < timed.Count ? Math.Max(timed[j].Start, from) : Math.Max(from, seg.End);
                    output.Add(new Word(t, from, to));
                }
            }
        }
        return output;
    }

    /** The transcript as text, with a new paragraph wherever the speaker paused for [pause] seconds or more. */
    public static string Text(IReadOnlyList<Word> words, double pause = 2.0)
    {
        var b = new StringBuilder();
        for (var i = 0; i < words.Count; i++)
        {
            if (i > 0) b.Append(words[i].Start - words[i - 1].End >= pause ? "\n\n" : " ");
            b.Append(words[i].Text);
        }
        return b.ToString();
    }

    /** The index of the word being said at [t], or -1 before the first. */
    public static int WordAt(IReadOnlyList<Word> words, double t)
    {
        int lo = 0, hi = words.Count - 1, found = -1;
        while (lo <= hi)
        {
            var mid = (lo + hi) / 2;
            if (words[mid].Start <= t) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found;
    }

    // ---------- what playback can skip ----------

    public readonly record struct Cut(double Start, double End);

    /** Sounds that are never a word. */
    private static readonly HashSet<string> Disfluencies = new()
    {
        "uh", "uhh", "uhhh", "um", "umm", "ummm", "er", "err", "erm", "ah", "ahh",
        "eh", "hm", "hmm", "hmmm", "mm", "mmm", "mhm", "mmhm", "uh-huh", "huh",
    };

    /** Real words that are filler only when set off by a comma or a pause ("I like that" must survive). */
    private static readonly HashSet<string> SoftFillers = new()
    {
        "like", "basically", "literally", "actually", "honestly", "obviously", "seriously", "anyway", "right", "well", "so",
    };
    private static readonly (string, string)[] SoftPhrases = { ("you", "know"), ("i", "mean"), ("sort", "of"), ("kind", "of"), ("you", "see") };

    private const double SetOffPause = 0.18;
    /** Left on each side of a cut so a jump never clips the start of a word. */
    private const double Pad = 0.06;

    private static bool SetOff(IReadOnlyList<Word> words, int i)
    {
        var raw = words[i].Text.Trim();
        if (raw.Length > 0 && ",.!?…;".Contains(raw[^1])) return true;
        var before = i > 0 ? words[i].Start - words[i - 1].End : SetOffPause;
        var after = i + 1 < words.Count ? words[i + 1].Start - words[i].End : SetOffPause;
        return before >= SetOffPause || after >= SetOffPause;
    }

    public static HashSet<int> FillerIndices(IReadOnlyList<Word> words)
    {
        var found = new HashSet<int>();
        for (var i = 0; i < words.Count; i++)
        {
            var b = Bare(words[i].Text);
            if (Disfluencies.Contains(b) || (SoftFillers.Contains(b) && SetOff(words, i))) found.Add(i);
        }
        for (var i = 0; i < words.Count - 1; i++)
            if (SoftPhrases.Contains((Bare(words[i].Text), Bare(words[i + 1].Text))) && (SetOff(words, i) || SetOff(words, i + 1)))
            { found.Add(i); found.Add(i + 1); }
        return found;
    }

    /** Sorted, joined where they overlap or touch, and without the negligible ones. */
    public static List<Cut> Merge(IEnumerable<Cut> ranges, double minLength = 0.08)
    {
        var output = new List<Cut>();
        foreach (var r in ranges.Where(r => r.End - r.Start >= minLength).OrderBy(r => r.Start))
        {
            if (output.Count > 0 && r.Start <= output[^1].End + 0.02) output[^1] = new Cut(output[^1].Start, Math.Max(output[^1].End, r.End));
            else output.Add(r);
        }
        return output;
    }

    public static List<Cut> FillerCuts(IReadOnlyList<Word> words) =>
        Merge(FillerIndices(words).OrderBy(i => i).Select(i => new Cut(Math.Max(0, words[i].Start - 0.02), words[i].End + 0.02)), 0.03);

    /** Where playback at [t] should jump to, or null when [t] isn't inside a cut. */
    public static double? SkipTo(IReadOnlyList<Cut> cuts, double t)
    {
        foreach (var c in cuts) if (t >= c.Start && t < c.End - 0.01) return c.End;
        return null;
    }

    // ---------- voice detection, for skipping silence ----------

    /** Loudness is measured in frames this long, in seconds. */
    public const double Frame = 0.02;
    /** Kept around speech so a jump never clips a soft start or a trailing word. */
    private const double Hangover = 0.25;
    /** Stretches without speech shorter than this are left in. */
    public const double MinSilence = 0.6;

    /**
     * Which frames hold speech, from their loudness in dB. The noise floor is measured from the recording itself
     * (its quietest stretches), so a steady background (an engine, wind, a fan) counts as silence and only what
     * stands clearly above it counts as speech. Short dips inside speech and the edges around it are kept.
     */
    public static bool[] SpeechFrames(float[] db)
    {
        var n = db.Length;
        if (n == 0) return Array.Empty<bool>();
        var sorted = (float[])db.Clone();
        Array.Sort(sorted);
        var floor = sorted[Math.Clamp((int)(n * 0.10), 0, n - 1)];
        var loud = sorted[Math.Clamp((int)(n * 0.90), 0, n - 1)];
        var threshold = floor + Math.Max(6f, (loud - floor) * 0.35f);
        var output = new bool[n];
        var pad = (int)(Hangover / Frame);
        for (var i = 0; i < n; i++)
            if (db[i] > threshold)
                for (var k = Math.Max(0, i - pad); k <= Math.Min(n - 1, i + pad); k++) output[k] = true;
        return output;
    }

    /**
     * The stretches to skip as silence: no speech heard in the audio and no word placed there by the transcript.
     * Requiring both keeps every word even where the word times are off or the detector misses a quiet one.
     */
    public static List<Cut> SilenceCuts(bool[] speech, IReadOnlyList<Word> words, double duration)
    {
        var quiet = new List<Cut>();
        var i = 0;
        while (i < speech.Length)
        {
            if (speech[i]) { i++; continue; }
            var start = i;
            while (i < speech.Length && !speech[i]) i++;
            quiet.Add(new Cut(start * Frame, Math.Min(i * Frame, duration > 0 ? duration : i * Frame)));
        }
        var spoken = words.Select(w => new Cut(Math.Max(0, w.Start - Pad), w.End + Pad)).OrderBy(w => w.Start).ToList();
        var cuts = new List<Cut>();
        foreach (var q in quiet)
        {
            var from = q.Start;
            foreach (var w in spoken)
            {
                if (w.End <= from) continue;
                if (w.Start >= q.End) break;
                if (w.Start > from) cuts.Add(new Cut(from, w.Start));
                from = Math.Max(from, w.End);
            }
            if (from < q.End) cuts.Add(new Cut(from, q.End));
        }
        return Merge(cuts.Where(c => c.End - c.Start >= MinSilence));
    }

    /** Loudness in dB of each [Frame] of mono samples (-1 to 1), after a gentle high-pass that drops rumble below speech. */
    public static List<float> FrameLoudness(IEnumerable<float> samples, int rate)
    {
        var output = new List<float>();
        var per = Math.Max(1, (int)(rate * Frame));
        var a = (float)(1.0 / (1.0 + 2 * Math.PI * 150.0 / rate)); // one-pole high-pass at about 150 Hz
        float px = 0, py = 0, sum = 0;
        var got = 0;
        foreach (var x in samples)
        {
            var y = a * (py + x - px);
            px = x; py = y;
            sum += y * y;
            if (++got >= per) { output.Add((float)(10 * Math.Log10(sum / got + 1e-10))); sum = 0; got = 0; }
        }
        return output;
    }

    // ---------- summaries ----------

    private static readonly Dictionary<int, (string Instruction, int Bullets, int Cap)> Levels = new()
    {
        [1] = ("Capture only the single most important thing said.", 0, 400),
        [2] = ("Capture the main points.", 2, 700),
        [3] = ("Capture the key points and the context needed to act on them.", 4, 1200),
        [4] = ("Cover all important points, supporting details, and context.", 6, 2000),
        [5] = ("Cover everything: main points, nuance, context, details, implications.", 9, 3000),
    };
    public static readonly string[] LevelNames = { "One line", "Short", "Medium", "Detailed", "Everything" };

    private static (string Instruction, int Bullets, int Cap) Level(int level) => Levels.TryGetValue(level, out var l) ? l : Levels[3];

    public static int SummaryMaxTokens(int level) => Level(level).Cap;

    public static string SummarySystem(int level, bool voice)
    {
        var (instruction, bullets, _) = Level(level);
        var shape = bullets == 0 ? "Reply with a single sentence and nothing else."
            : $"Reply in this exact shape:\nFirst line: one sentence giving the gist.\nThen up to {bullets} lines, each starting with '- ', one point per line.\nNo headings, no preamble, no closing remark.";
        var what = voice ? "a voice message" : "a note";
        return $"You summarize {what} for someone who does not have time to {(voice ? "listen to" : "read")} it. {instruction}\n\n{shape}\n\n" +
            "Write plainly, in the third person, and never mention the transcript, the audio, or yourself. " +
            (voice ? "Transcripts come from speech recognition and may contain misheard words: summarize the evident intent rather than quoting oddities." : "");
    }

    public static string SummaryUser(string text) => "Summarize this:\n\n<note>\n" + text + "\n</note>";

    // ---------- the title ----------

    public static string TitleSystem() =>
        "Write a short title, 3 to 7 words, for this note. Reply with the title only: no quotes, no trailing period.";

    public static string TitleUser(string text) => "Title this:\n\n<note>\n" + (text.Length > 4000 ? text[..4000] : text) + "\n</note>";

    /** The model's reply as a title: its first line, without quotes, a "Title:" label or a closing period, at most 80 characters. Null if nothing's left. */
    public static string? CleanTitle(string raw)
    {
        var t = raw.Split('\n').FirstOrDefault(l => l.Trim().Length > 0)?.Trim();
        if (t == null) return null;
        if (t.StartsWith("Title:", StringComparison.OrdinalIgnoreCase)) t = t[6..];
        t = t.Trim().Trim('"', '\'', '“', '”', '‘', '’', '*', '`').Trim();
        if (t.EndsWith('.') && !t.EndsWith("..")) t = t[..^1].TrimEnd();
        if (t.Length > 80) t = t[..80];
        t = t.Trim();
        return t.Length > 0 ? t : null;
    }

    // ---------- the index ----------

    public const int MaxPoints = 40;

    public const string IndexSystem =
        "You index voice messages so someone can see everything in one at a glance and jump to any part of it.\n\n" +
        "You are given the message in numbered segments. Return one line per distinct point, in the order it was said, in exactly this shape:\n\n" +
        "<segment number>|<topic>|<the point>\n\n" +
        "Rules:\n" +
        "- One line per distinct thing said. Never merge two points into one line, and never split one point across two.\n" +
        "- The segment number is where that point starts.\n" +
        "- Topic is one or two words. Repeat it on consecutive lines about the same subject so the run reads as a group.\n" +
        "- Drop the reasons, the background and the retelling. Keep every doubt, condition and qualifier (maybe, if, not sure, unless), because those change what a reply should say.\n" +
        "- Write a question as a question, and an ask as an ask.\n" +
        "- Say it plainly and in the third person. Do not mention the message, the recording or yourself.\n" +
        "- Output nothing but those lines: no preamble, no numbering, no bullets, no closing remark.";

    public static string IndexUser(IReadOnlyList<Segment> segments) =>
        "Index this message:\n\n" + string.Join("\n", segments.Select((s, i) => $"[{i + 1}] {s.Text.Trim()}"));

    /** The model's lines as points in time order. A bad line costs only itself; a number out of range is clamped. */
    public static List<Point> ParseIndex(string raw, IReadOnlyList<Segment> segments)
    {
        var points = new List<Point>();
        if (string.IsNullOrWhiteSpace(raw) || segments.Count == 0) return points;
        foreach (var line0 in raw.Split('\n'))
        {
            var line = line0.Trim().TrimStart('-', '*', '•', '#').Trim();
            if (line.Length == 0 || !line.Contains('|')) continue;
            var head = line[..line.IndexOf('|')];
            var rest = line[(line.IndexOf('|') + 1)..];
            string topic, text;
            if (rest.Contains('|')) { topic = rest[..rest.IndexOf('|')].Trim(); text = rest[(rest.IndexOf('|') + 1)..].Trim(); }
            else { topic = rest.Trim(); text = ""; }
            if (text.Length == 0) { text = topic; topic = ""; }
            if (text.Length == 0) continue;
            var num = head.Trim().Trim('[', ']').Split('.')[0].Trim();
            var n = int.TryParse(num, out var v) ? v : points.Count + 1;
            points.Add(new Point(segments[Math.Clamp(n, 1, segments.Count) - 1].Start, topic, text));
            if (points.Count >= MaxPoints) break;
        }
        return points.OrderBy(p => p.Time).ToList(); // a stable sort, like the Android app's
    }

    public static string Clock(double seconds)
    {
        var s = (int)Math.Max(0, seconds);
        return $"{s / 60}:{s % 60:00}";
    }
}
