using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/** Plain-code text handling: noise stripping, the no-AI fallback cleanup, and snippets. Mirrors Android's TextTools. */
public static class TextTools
{
    private static readonly Regex Noise = new(@"\[[^\]]*]|\((?:music|laughs?|applause|silence|inaudible|blank_audio|noise)[^)]*\)|\*[^*]*\*", RegexOptions.IgnoreCase);
    private static readonly Regex Fillers = new(@"(?i)\b(?:um+|uh+|erm|hmm+)\b[,.]?\s*"); // not "er"/"mm": those are real words (the ER, 5 mm)
    private static readonly Regex Spaces = new(@"\s+");
    private static readonly Regex SpaceBeforePunct = new(@"\s+([,.!?;:])");

    /** Removes Whisper's sound tags like [BLANK_AUDIO] or (music). */
    public static string StripNoise(string s) => Spaces.Replace(Noise.Replace(s, " "), " ").Trim();

    private static readonly Regex NotLetters = new(@"[^a-z ]");
    private static readonly HashSet<string> Phantoms = new()
    {
        "thank you", "thank you very much", "thank you for watching", "thanks for watching", "you", "bye", "beep",
    };

    /** Whisper invents these for silence or a lone beep. Only trusted as "nothing said" when barely any sound was heard. */
    public static bool IsPhantom(string s) => Phantoms.Contains(Spaces.Replace(NotLetters.Replace(s.ToLowerInvariant(), " "), " ").Trim());

    /** The fallback when AI cleanup is off or unreachable. */
    public static string BasicClean(string s)
    {
        var t = SpaceBeforePunct.Replace(Spaces.Replace(Fillers.Replace(s, ""), " "), "$1").Trim();
        return t.Length == 0 ? t : char.ToUpperInvariant(t[0]) + t[1..];
    }

    public static int WordCount(string s) => Spaces.Split(s).Count(w => w.Length > 0);

    // Things only the AI cleanup can deal with: fillers, self-corrections, spoken punctuation and formatting.
    private static readonly Regex NeedsAi = new(@"(?i)\b(?:um+|uh+|er+m?|hmm+|you know|i mean|actually|scratch that|no wait|wait no|sorry|or rather|correction|let me rephrase|new line|new paragraph|next line|bullet|number (?:one|two|three|four|five|\d+)|first(?:ly)?|second(?:ly)?|third(?:ly)?|comma|period|full stop|question mark|exclamation (?:point|mark)|colon|semicolon|quote|unquote|open paren\w*|close paren\w*|dash|hyphen|slash|at sign|dot com|hashtag|emoji|all caps|capital|lol)\b");
    private static readonly Regex Stutter = new(@"(?i)\b(\w+)[ ,]+\1\b");
    public const int QuickWords = 20;

    /**
     * True when a short dictation came out of speech recognition already fit to type, so the AI cleanup
     * (the slower half of a short dictation) has nothing to do: no fillers, corrections, repeats or spoken
     * formatting, no snippets, a style that is just normal capitals and punctuation, and no instructions
     * of the user's own. Email is left out because its greeting and sign-off are laid out by the cleanup.
     */
    public static bool NothingToFix(string text, bool hasSnippets, string styleId, string categoryId, string custom)
    {
        if (hasSnippets || text.Length == 0 || WordCount(text) > QuickWords) return false;
        if (styleId != "FORMAL" && styleId != "CASUAL") return false;
        if (categoryId == "EMAIL") return false;
        var own = custom.Trim();
        if (own.Length > 0 && own != Catalog.DefaultInstructions) return false;
        if (text.Contains('\n')) return false;
        return !NeedsAi.IsMatch(text) && !Stutter.IsMatch(text);
    }

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
