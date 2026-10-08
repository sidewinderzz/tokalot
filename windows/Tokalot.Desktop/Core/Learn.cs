using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/**
 * Learning names and jargon from the user's own corrections (off unless switched on in Dictionary).
 * After Tokalot pastes a dictation, the text box is looked at again a few times. If exactly one word of
 * what was pasted has been respelled, and the new spelling looks like a name or a term rather than an
 * ordinary word, that spelling goes into the dictionary. Only the comparison below sees the text, in
 * memory; nothing but the learned word is kept.
 * The Android app has the same rules (Learn.kt).
 */
public static class Learn
{
    /** Found: the dictation is still there, whole or with one word changed. Word: the corrected spelling worth learning. */
    public sealed record Seen(bool Found, string? Word);

    private static readonly Seen NotFound = new(false, null);
    private const int MinWords = 4;      // fewer words than this is too little to recognise the dictation by
    private const int MaxField = 20000;  // only the end of a very long document is compared

    private static readonly HashSet<string> Calendar = new()
    {
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    };

    // Some apps turn ' into ’ as you type; that is not a correction.
    private static string[] Tokens(string s) => s.Replace('’', '\'').Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);

    /** A word without the punctuation around it or a possessive 's. */
    public static string Bare(string token)
    {
        int a = 0, b = token.Length;
        while (a < b && !char.IsLetterOrDigit(token[a])) a++;
        while (b > a && !char.IsLetterOrDigit(token[b - 1])) b--;
        var w = token[a..b];
        if (w.EndsWith("'s", StringComparison.OrdinalIgnoreCase) || w.EndsWith("’s", StringComparison.OrdinalIgnoreCase)) w = w[..^2];
        return w;
    }

    /** inserted: what Tokalot pasted. field: what the text box holds now. known: the dictionary. */
    public static Seen Look(string inserted, string field, ICollection<string> known, bool english = true)
    {
        var typed = Tokens(inserted);
        if (typed.Length < MinWords) return NotFound;
        var now = Tokens(field.Length > MaxField ? field[^MaxField..] : field);
        Seen? oneOff = null;
        for (int start = 0; start <= now.Length - typed.Length; start++)
        {
            int changed = -1, misses = 0;
            for (int j = 0; j < typed.Length; j++)
            {
                if (typed[j] == now[start + j]) continue;
                if (++misses > 1) break;
                changed = j;
            }
            if (misses == 0) return new Seen(true, null); // untouched
            if (misses == 1 && oneOff == null)
            {
                int at = start + changed;
                bool sentenceStart = at == 0 || ".!?:".Contains(now[at - 1][^1]);
                var word = Bare(now[at]);
                oneOff = new Seen(true, Worth(Bare(typed[changed]), word, sentenceStart, known, english) ? word : null);
            }
        }
        return oneOff ?? NotFound;
    }

    /**
     * Is `fresh` a respelling of `old` that belongs in the dictionary? Yes for names and terms
     * (Caitlyn, GitHub, K8s, NASA); no for ordinary words, different words, and plain capitalisation.
     */
    public static bool Worth(string old, string fresh, bool sentenceStart, ICollection<string> known, bool english = true)
    {
        if (fresh.Length < 2 || fresh.Length > 40 || old.Length == 0 || fresh == old) return false;
        if (!fresh.Any(char.IsLetter) || fresh.Any(c => !(char.IsLetterOrDigit(c) || "'’-.".Contains(c)))) return false;
        if (known.Any(k => string.Equals(k, fresh, StringComparison.OrdinalIgnoreCase))) return false;

        var rest = fresh[1..];
        bool mixed = rest.Any(char.IsUpper) && fresh.Any(char.IsLower);              // iPhone, GitHub, McKay
        bool digits = fresh.Any(char.IsDigit);                                        // K8s, GPT4
        bool acronym = fresh.Length >= 3 && fresh.All(c => char.IsUpper(c) || char.IsDigit(c)); // NASA
        bool capital = char.IsUpper(fresh[0]) && !rest.Any(char.IsUpper);             // Caitlyn

        // Only the capitals changed: that's worth keeping for GitHub or iPhone, not for "Apple" or "STOP".
        if (string.Equals(old, fresh, StringComparison.OrdinalIgnoreCase)) return mixed;
        // Unusual capitals or digits can only be a term. A plain capital mid-sentence means a name in English,
        // but not in a language such as German, where every noun has one.
        bool term = mixed || digits || acronym;
        bool name = english && capital && !sentenceStart;
        if (!term && !name) return false;

        // A respelling of what was heard, not a different word put in its place: Katelyn to Caitlyn is,
        // Sam to Tom or Boston to Austin is not. Terms get more room (kates to K8s). Days and months never count.
        var a = old.ToLowerInvariant();
        var b = fresh.ToLowerInvariant();
        if (Calendar.Contains(b)) return false;
        int longest = Math.Max(a.Length, b.Length);
        return Distance(a, b) <= (term ? Math.Max(2, longest * 2 / 3) : Math.Max(1, longest * 3 / 7));
    }

    // ---------- words spelled out while dictating ----------

    /** Three or more lone letters in a row, the way a recognizer writes spelling: "K-U-B-O-T-A", "K U B O T A", "S. T. E." */
    private static readonly Regex SpelledOut = new(@"(?<![\p{L}\d])\p{L}(?![\p{L}\d])(?:[\s.,-]+\p{L}(?![\p{L}\d])){2,}");
    private static readonly Regex SaidSpelled = new(@"(?i)\b(?:spelled|spelt|spelling|spells?)\W*$");

    /**
     * Words the user spelled out letter by letter in raw (the transcript before cleanup) to make clear how
     * they're written: "it's a Kubota, K-U-B-O-T-A" or "Stewart, spelled S-T-E-W-A-R-T". Only letters that
     * follow "spelled", or that come right after a word that sounds like them, count, so a part number or
     * initials ("part A-B-C") aren't taken for a word. Words already in known are left out.
     * The Android app has the same rules (Learn.spelled).
     */
    public static List<string> Spelled(string raw, ICollection<string> known)
    {
        var found = new List<string>();
        foreach (Match m in SpelledOut.Matches(raw))
        {
            var letters = new string(m.Value.Where(char.IsLetter).ToArray());
            if (letters.Length < 3 || letters.Length > 24) continue;
            var lower = letters.ToLowerInvariant();
            var before = raw[..m.Index];
            var near = Tokens(before).TakeLast(4).Select(Bare).Where(w => w.Length >= 2).ToList();
            var said = SaidSpelled.IsMatch(before);
            if (!said && !near.Any(w => Distance(w.ToLowerInvariant(), lower) <= Math.Max(1, letters.Length / 3))) continue;
            // Keep the casing of the word as said when it was heard right (NASA, iPhone); otherwise like a name.
            var word = near.LastOrDefault(w => string.Equals(w, letters, StringComparison.OrdinalIgnoreCase) && w[1..].Any(char.IsUpper))
                ?? char.ToUpperInvariant(lower[0]) + lower[1..];
            if (known.Any(k => string.Equals(k, word, StringComparison.OrdinalIgnoreCase))
                || found.Any(f => string.Equals(f, word, StringComparison.OrdinalIgnoreCase))) continue;
            found.Add(word);
        }
        return found;
    }

    /** How many single-letter edits turn a into b. */
    public static int Distance(string a, string b)
    {
        var prev = new int[b.Length + 1];
        var cur = new int[b.Length + 1];
        for (int j = 0; j <= b.Length; j++) prev[j] = j;
        for (int i = 1; i <= a.Length; i++)
        {
            cur[0] = i;
            for (int j = 1; j <= b.Length; j++)
            {
                int swap = prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1);
                cur[j] = Math.Min(swap, Math.Min(prev[j] + 1, cur[j - 1] + 1));
            }
            (prev, cur) = (cur, prev);
        }
        return prev[b.Length];
    }
}
