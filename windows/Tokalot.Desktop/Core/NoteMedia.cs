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
