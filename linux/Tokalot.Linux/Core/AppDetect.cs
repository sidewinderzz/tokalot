using System;
using System.Linq;
using System.Text.RegularExpressions;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/** The app you're dictating into. Key is used for per-app overrides; Label is shown in history. */
public sealed record ActiveApp(string Key, string Label);

/**
 * Figures out which program (and, for browsers, which site) has focus, and what style it gets.
 * Linux: only an X11 session can be asked which window is active. In a Wayland session there is
 * no such question to ask, so Detect() returns null and the dictation gets "Everything else".
 */
public static class AppDetect
{
    private static readonly string[] Browsers =
    {
        "chrome", "chromium", "msedge", "edge", "firefox", "brave", "opera", "vivaldi", "arc", "librewolf", "zen", "epiphany", "falkon", "floorp",
    };

    // Window class names (lowercase), whole or one of their parts ("org.telegram.desktop" has the part "telegram") -> category.
    // The Windows process names are kept so app choices restored from a Windows backup still mean the same thing.
    private static readonly (string Category, string[] Processes)[] KnownApps =
    {
        ("MESSAGING", new[] { "whatsapp", "slack", "discord", "telegram", "telegramdesktop", "signal", "ms-teams", "teams", "messenger", "element", "zoom",
            "fractal", "polari", "hexchat", "neochat", "vesktop", "webcord", "mattermost", "thunderbird-chat" }),
        ("EMAIL", new[] { "outlook", "olk", "thunderbird", "betterbird", "mailspring", "hxoutlook", "em client", "mailbird", "evolution", "geary", "kmail", "claws" }),
        ("AI_CODE", new[] { "code", "cursor", "windsurf", "devenv", "idea64", "rider64", "pycharm64", "webstorm64", "clion64", "goland64",
            "windowsterminal", "wt", "cmd", "powershell", "pwsh", "claude", "chatgpt", "zed", "sublime_text", "notepad++", "android studio", "studio64",
            "vscodium", "codium", "jetbrains", "idea", "pycharm", "webstorm", "clion", "goland", "rider", "rustrover", "emacs", "gvim", "neovide",
            "kate", "gedit", "gnome-builder" }),
    };

    /** Terminals paste with Ctrl+Shift+V (Ctrl+V means something else there). They also count as AI & code. */
    private static readonly string[] Terminals =
    {
        "terminal", "konsole", "kitty", "alacritty", "xterm", "uxterm", "wezterm", "foot", "footclient", "tilix", "terminator", "ptyxis", "kgx",
        "st", "urxvt", "rxvt", "ghostty", "yakuake", "guake", "tilda", "terminology", "qterminal", "lxterminal", "sakura", "warp", "tabby", "hyper",
        "blackbox", "cool-retro-term", "contour", "rio",
    };

    // Browser tab titles -> site (key, label, category)
    private static readonly (string Pattern, string Key, string Label, string Category)[] Sites =
    {
        ("Gmail", "web:gmail", "Gmail", "EMAIL"),
        ("Outlook", "web:outlook", "Outlook", "EMAIL"),
        ("Yahoo Mail", "web:yahoomail", "Yahoo Mail", "EMAIL"),
        ("Proton Mail", "web:protonmail", "Proton Mail", "EMAIL"),
        ("WhatsApp", "web:whatsapp", "WhatsApp", "MESSAGING"),
        ("Messenger", "web:messenger", "Messenger", "MESSAGING"),
        ("Slack", "web:slack", "Slack", "MESSAGING"),
        ("Discord", "web:discord", "Discord", "MESSAGING"),
        ("Google Chat", "web:googlechat", "Google Chat", "MESSAGING"),
        ("Microsoft Teams", "web:teams", "Teams", "MESSAGING"),
        ("Claude", "web:claude", "Claude", "AI_CODE"),
        ("ChatGPT", "web:chatgpt", "ChatGPT", "AI_CODE"),
        ("Gemini", "web:gemini", "Gemini", "AI_CODE"),
        ("Perplexity", "web:perplexity", "Perplexity", "AI_CODE"),
        ("GitHub", "web:github", "GitHub", "AI_CODE"),
        ("Replit", "web:replit", "Replit", "AI_CODE"),
        ("Copilot", "web:copilot", "Copilot", "AI_CODE"),
    };

    public static ActiveApp? Detect()
    {
        try
        {
            if (Sh.IsWayland) return null;
            var (cls, t) = ActiveWindow();
            if (string.IsNullOrWhiteSpace(cls)) return null;
            var name = cls.Trim().ToLowerInvariant();
            if (name == "tokalot") return null;
            var friendly = Friendly(cls.Trim());

            if (Parts(name).Any(Browsers.Contains))
            {
                foreach (var s in Sites)
                    if (Regex.IsMatch(t, $@"(?<![\w]){Regex.Escape(s.Pattern)}(?![\w])"))
                        return new ActiveApp(s.Key, $"{s.Label} ({friendly})");
                return new ActiveApp("app:" + name, friendly);
            }
            return new ActiveApp("app:" + name, friendly);
        }
        catch { return null; }
    }

    /** Class and title of the focused window: straight from the X server, or through xdotool / xprop. */
    private static (string Class, string Title) ActiveWindow()
    {
        if (X11.ActiveWindowClassAndTitle() is { } direct) return direct;

        if (Sh.Which("xdotool") is { } xdo)
        {
            var c = Sh.Run(xdo, new[] { "getactivewindow", "getwindowclassname" }, timeoutMs: 400);
            if (c.Exit == 0 && c.Out.Trim().Length > 0)
            {
                var n = Sh.Run(xdo, new[] { "getactivewindow", "getwindowname" }, timeoutMs: 400);
                return (c.Out.Trim(), n.Exit == 0 ? n.Out.Trim() : "");
            }
        }
        if (Sh.Which("xprop") is { } xprop)
        {
            var root = Sh.Run(xprop, new[] { "-root", "_NET_ACTIVE_WINDOW" }, timeoutMs: 400);
            var id = Regex.Match(root.Out, @"0x[0-9a-fA-F]+").Value;
            if (id.Length > 2 && id != "0x0")
            {
                var w = Sh.Run(xprop, new[] { "-id", id, "WM_CLASS", "_NET_WM_NAME" }, timeoutMs: 400);
                // WM_CLASS(STRING) = "instance", "Class"
                var cls = Regex.Match(w.Out, "WM_CLASS[^=]*=\\s*\"[^\"]*\",\\s*\"([^\"]*)\"").Groups[1].Value;
                var title = Regex.Match(w.Out, "_NET_WM_NAME[^=]*=\\s*\"(.*)\"").Groups[1].Value;
                return (cls, title);
            }
        }
        return ("", "");
    }

    /** "org.gnome.Nautilus" and "google-chrome" are split into their parts, so one known word is enough to match. */
    private static string[] Parts(string name) =>
        new[] { name }.Concat(name.Split(new[] { '.', '-', '_', ' ' }, StringSplitOptions.RemoveEmptyEntries)).ToArray();

    private static readonly string[] Generic = { "desktop", "client", "app", "bin", "stable", "beta", "browser", "wrapped", "server" };

    /** A readable name from a window class: "org.gnome.Nautilus" -> "Nautilus", "google-chrome" -> "Google Chrome". */
    private static string Friendly(string cls)
    {
        var s = cls;
        if (s.Contains('.') && !s.Contains(' '))
        {
            var segs = s.Split('.', StringSplitOptions.RemoveEmptyEntries);
            s = segs.LastOrDefault(x => !Generic.Contains(x.ToLowerInvariant())) ?? segs[^1];
        }
        var words = s.Split(new[] { '-', '_', ' ' }, StringSplitOptions.RemoveEmptyEntries)
            .Where(w => !Generic.Contains(w.ToLowerInvariant())).ToArray();
        if (words.Length == 0) words = new[] { s };
        return string.Join(" ", words.Select(w => char.ToUpperInvariant(w[0]) + w[1..]));
    }

    /** True when the app is a terminal, which needs Ctrl+Shift+V to paste. */
    public static bool IsTerminal(ActiveApp? app)
    {
        if (app == null || !app.Key.StartsWith("app:")) return false;
        return Parts(app.Key[4..]).Any(Terminals.Contains);
    }

    public static AppCategory Categorize(ActiveApp? app, Settings s)
    {
        if (app == null) return Catalog.CategoryById("OTHER");
        if (s.AppOverrides.TryGetValue(app.Key, out var o)) return Catalog.CategoryById(o);
        var site = Sites.FirstOrDefault(x => x.Key == app.Key);
        if (site.Key != null) return Catalog.CategoryById(site.Category);
        var proc = app.Key.StartsWith("app:") ? app.Key[4..] : app.Key;
        var parts = Parts(proc);
        if (parts.Any(Terminals.Contains)) return Catalog.CategoryById("AI_CODE");
        // A browser is "Everything else" even when its name has a known word in it.
        if (!parts.Any(Browsers.Contains))
            foreach (var (cat, procs) in KnownApps)
                if (parts.Any(procs.Contains)) return Catalog.CategoryById(cat);
        if (proc.Contains("mail")) return Catalog.CategoryById("EMAIL");
        if (proc.Contains("chat") || proc.Contains("messag")) return Catalog.CategoryById("MESSAGING");
        return Catalog.CategoryById("OTHER");
    }

    // ---------- "what did cleanup fix" counters ----------

    private static readonly Regex Filler = new(@"(?i)\b(?:um+|uh+|erm|hmm+|you know)\b");
    private static readonly Regex Correction = new(@"(?i)\b(?:no wait|wait no|actually no|no actually|scratch that|sorry,? i meant|let me rephrase|or rather|correction)\b");
    public static int FillerCount(string raw) => Filler.Matches(raw).Count;
    public static int CorrectionCount(string raw) => Correction.Matches(raw).Count;
}
