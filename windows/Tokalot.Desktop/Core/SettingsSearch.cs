using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/**
 * The search box on the Settings page list. It matches what each setting is called, the line under it, and the
 * everyday words people type for it ("dark" finds Theme). Same words and scoring as Android's SettingsSearch.
 */
public static class SettingsSearch
{
    /** One setting: the title on its row, the line under it, the page it's on, and where to say it is. */
    public sealed record Row(string Title, string Sub, string Page, string Where);

    /** A result: open Page and flash Title. Note says if it's somewhere else as well. */
    public sealed record Hit(string Title, string Where, string Page, string? Note);

    private static readonly Dictionary<string, string[]> Same = new()
    {
        ["dark"] = new[] { "theme", "appearance" },
        ["light"] = new[] { "theme", "appearance" },
        ["night"] = new[] { "theme" },
        ["color"] = new[] { "theme", "accent" },
        ["colour"] = new[] { "theme", "accent" },
        ["font"] = new[] { "text size" },
        ["wifi"] = new[] { "weak signal", "on-device", "offline" },
        ["internet"] = new[] { "weak signal", "on-device" },
        ["offline"] = new[] { "on-device", "weak signal" },
        ["signal"] = new[] { "weak signal" },
        ["data"] = new[] { "weak signal", "sync" },
        ["local"] = new[] { "on-device" },
        ["download"] = new[] { "on-device", "model" },
        ["language"] = new[] { "detect language" },
        ["spanish"] = new[] { "detect language" },
        ["grammar"] = new[] { "cleanup", "polish" },
        ["ai"] = new[] { "cleanup", "polish", "api keys" },
        ["fix"] = new[] { "cleanup", "autocorrect" },
        ["key"] = new[] { "api keys" },
        ["openai"] = new[] { "api keys" },
        ["groq"] = new[] { "api keys" },
        ["gemini"] = new[] { "api keys" },
        ["push"] = new[] { "hold to talk" },
        ["press"] = new[] { "hold to talk" },
        ["vibrate"] = new[] { "haptics" },
        ["buzz"] = new[] { "haptics" },
        ["beep"] = new[] { "sounds" },
        ["chime"] = new[] { "sounds" },
        ["headset"] = new[] { "headset", "microphone" },
        ["bluetooth"] = new[] { "headset", "microphone" },
        ["mic"] = new[] { "microphone", "headset" },
        ["button"] = new[] { "floating", "mic button", "tile" },
        ["bubble"] = new[] { "floating", "mic button" },
        ["volume"] = new[] { "volume keys" },
        ["tile"] = new[] { "tile" },
        ["terminal"] = new[] { "shell" },
        ["termux"] = new[] { "shell" },
        ["typing"] = new[] { "keyboard" },
        ["spelling"] = new[] { "autocorrect" },
        ["swipe"] = new[] { "swipe" },
        ["drive"] = new[] { "sync" },
        ["dropbox"] = new[] { "sync" },
        ["restore"] = new[] { "backup" },
        ["storage"] = new[] { "recordings", "delete all" },
        ["space"] = new[] { "recordings", "delete all" },
        ["cost"] = new[] { "usage" },
        ["money"] = new[] { "usage" },
        ["stats"] = new[] { "usage" },
        ["update"] = new[] { "beta updates", "check for updates", "changelog" },
        ["version"] = new[] { "check for updates", "about" },
        ["pause"] = new[] { "skip silence" },
        ["pauses"] = new[] { "skip silence" },
        ["gaps"] = new[] { "skip silence" },
        ["um"] = new[] { "skip ums" },
        ["uh"] = new[] { "skip ums" },
        ["filler"] = new[] { "skip ums" },
        ["fast"] = new[] { "speed", "playback speed", "auto speed" },
        ["slow"] = new[] { "speed", "playback speed", "auto speed" },
        ["wpm"] = new[] { "auto speed aims" },
        ["words"] = new[] { "auto speed aims" },
        ["summary"] = new[] { "summary length" },
        ["whatsapp"] = new[] { "voice notes" },
        ["note"] = new[] { "voice notes", "note phrases" },
        ["private"] = new[] { "privacy" },
        ["privacy"] = new[] { "privacy" },
    };

    private static readonly HashSet<string> Stop = new() { "the", "a", "an", "to", "my", "turn", "on", "off", "mode", "setting", "settings", "change", "how", "set" };

    private static readonly Dictionary<string, string> Also = new()
    {
        ["Skip silence"] = "also in each note's ⋮ menu",
        ["Skip ums"] = "also in each note's ⋮ menu",
        ["Playback speed"] = "also the speed button on a note",
        ["Auto speed"] = "also the speed button on a note",
        ["Summary length"] = "also on a note's Summary card",
    };

    public static List<Hit> Find(string query, IReadOnlyList<Row> rows, int max = 25)
    {
        var words = Regex.Split(query.ToLowerInvariant(), @"[^\p{L}\p{N}-]+").Where(w => w.Length > 0 && !Stop.Contains(w)).ToList();
        if (words.Count == 0) return new();
        var scored = new List<(Row Row, int Score, int Index)>();
        for (var idx = 0; idx < rows.Count; idx++)
        {
            var r = rows[idx];
            var title = r.Title.ToLowerInvariant();
            var sub = r.Sub.ToLowerInvariant();
            var total = 0;
            var all = true;
            foreach (var w in words)
            {
                Same.TryGetValue(w, out var same);
                int s;
                if (title.StartsWith(w) || title.Split(' ').Any(t => t.StartsWith(w))) s = 10;
                else if (title.Contains(w)) s = 7;
                else if (same?.Any(title.Contains) == true) s = 6;
                else if (sub.Contains(w)) s = 3;
                else if (same?.Any(sub.Contains) == true) s = 2;
                else if (r.Where.ToLowerInvariant().Contains(w)) s = 1;
                else { all = false; break; } // every word has to match somewhere
                total += s;
            }
            if (all) scored.Add((r, total, idx));
        }
        return scored.OrderByDescending(x => x.Score).ThenBy(x => x.Index).Take(max)
            .Select(x => new Hit(x.Row.Title, x.Row.Where, x.Row.Page, Also.TryGetValue(x.Row.Title, out var n) ? n : null)).ToList();
    }
}
