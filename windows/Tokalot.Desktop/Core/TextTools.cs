using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/** Plain-code text handling: noise stripping, the no-AI fallback cleanup, and snippets. Mirrors Android's TextTools. */
public static class TextTools
{
    private static readonly Regex Noise = new(@"\[[^\]]*]|\((?:music|laughs?|applause|silence|inaudible|blank_audio|noise)[^)]*\)|\*[^*]*\*", RegexOptions.IgnoreCase);
    private static readonly Regex Fillers = new(@"(?i)\b(?:um+|uh+|erm?|hmm+|mm+)\b[,.]?\s*");
    private static readonly Regex Spaces = new(@"\s+");
    private static readonly Regex SpaceBeforePunct = new(@"\s+([,.!?;:])");

    /** Removes Whisper's sound tags like [BLANK_AUDIO] or (music). */
    public static string StripNoise(string s) => Spaces.Replace(Noise.Replace(s, " "), " ").Trim();

    /** The fallback when AI cleanup is off or unreachable. */
    public static string BasicClean(string s)
    {
        var t = SpaceBeforePunct.Replace(Spaces.Replace(Fillers.Replace(s, ""), " "), "$1").Trim();
        return t.Length == 0 ? t : char.ToUpperInvariant(t[0]) + t[1..];
    }

    public static int WordCount(string s) => Spaces.Split(s).Count(w => w.Length > 0);

    private static Regex TriggerRegex(string trigger)
    {
        var parts = Spaces.Split(trigger.Trim()).Where(p => p.Length > 0).Select(Regex.Escape);
        // Whisper may add commas/periods between spoken words, so allow punctuation between them.
        return new Regex(@"(?i)(?<![\w])" + string.Join(@"[\s,.!?;:\-]*", parts) + @"(?![\w])");
    }

    /** Swaps spoken trigger phrases for {{SNIPn}} placeholders so the AI can't reword snippet text. */
    public static (string Text, Dictionary<string, string> Map) Protect(string text, IEnumerable<Snippet> snippets)
    {
        var map = new Dictionary<string, string>();
        foreach (var sn in snippets.Where(s => !string.IsNullOrWhiteSpace(s.Trigger)).OrderByDescending(s => s.Trigger.Length))
        {
            var re = TriggerRegex(sn.Trigger);
            if (!re.IsMatch(text)) continue;
            var token = "{{SNIP" + (map.Count + 1) + "}}";
            map[token] = sn.Text;
            text = re.Replace(text, token);
        }
        return (text, map);
    }

    /** Puts snippet text back. If the model dropped a placeholder, append it rather than lose it. */
    public static string Restore(string text, Dictionary<string, string> map)
    {
        foreach (var (token, value) in map)
            text = text.Contains(token) ? text.Replace(token, value) : (text + " " + value).Trim();
        return text;
    }
}
