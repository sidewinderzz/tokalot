using System;
using System.Linq;
using System.Text.RegularExpressions;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/** The app you're dictating into. Key is used for per-app overrides; Label is shown in history. */
public sealed record ActiveApp(string Key, string Label);

/**
 * Figures out which program (and, for browsers, which site) has focus, and what style it gets.
 * Linux: an X11 session can be asked which window is active. Wayland has no such question in general, so
 * it is asked of the compositor where one answers: KDE (a one-off KWin script), Sway, Hyprland and niri.
 * Anywhere else (GNOME, ...) Detect() returns null and the dictation gets "Everything else".
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
            var (cls, t) = Sh.IsWayland ? WaylandActiveWindow() : ActiveWindow();
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

    /** Class and title of the focused window, asked of whichever compositor this session runs. Each of these is best effort: empty when it can't answer. */
    private static (string Class, string Title) WaylandActiveWindow()
    {
        static bool Env(string n) => !string.IsNullOrEmpty(Environment.GetEnvironmentVariable(n));
        try
        {
            if (Env("SWAYSOCK") && Sh.Which("swaymsg") is { } sway && Sway(sway) is { } a1) return a1;
            if (Env("HYPRLAND_INSTANCE_SIGNATURE") && Sh.Which("hyprctl") is { } hypr && Hyprland(hypr) is { } a2) return a2;
            if (Env("NIRI_SOCKET") && Sh.Which("niri") is { } niri && Niri(niri) is { } a3) return a3;
            if (Sh.Desktop.Contains("KDE", StringComparison.OrdinalIgnoreCase) && Kwin() is { } a4) return a4;
        }
        catch { }
        return ("", "");
    }

    private static (string, string)? Sway(string swaymsg)
    {
        var r = Sh.Run(swaymsg, new[] { "-t", "get_tree" }, timeoutMs: 800);
        if (r.Exit != 0) return null;
        using var doc = System.Text.Json.JsonDocument.Parse(r.Out);
        return FindFocused(doc.RootElement);

        static (string, string)? FindFocused(System.Text.Json.JsonElement n)
        {
            if (n.TryGetProperty("focused", out var f) && f.ValueKind == System.Text.Json.JsonValueKind.True && n.TryGetProperty("pid", out _))
            {
                // Wayland windows have an app_id; XWayland ones have a window class instead.
                var id = Str(n, "app_id");
                if (id.Length == 0 && n.TryGetProperty("window_properties", out var wp)) id = Str(wp, "class");
                return (id, Str(n, "name"));
            }
            foreach (var key in new[] { "nodes", "floating_nodes" })
                if (n.TryGetProperty(key, out var kids) && kids.ValueKind == System.Text.Json.JsonValueKind.Array)
                    foreach (var k in kids.EnumerateArray())
                        if (FindFocused(k) is { } hit) return hit;
            return null;
        }
    }

    private static (string, string)? Hyprland(string hyprctl)
    {
        var r = Sh.Run(hyprctl, new[] { "activewindow", "-j" }, timeoutMs: 800);
        if (r.Exit != 0) return null;
        using var doc = System.Text.Json.JsonDocument.Parse(r.Out);
        return (Str(doc.RootElement, "class"), Str(doc.RootElement, "title"));
    }

    private static (string, string)? Niri(string niri)
    {
        var r = Sh.Run(niri, new[] { "msg", "--json", "focused-window" }, timeoutMs: 800);
        if (r.Exit != 0) return null;
        using var doc = System.Text.Json.JsonDocument.Parse(r.Out);
        return (Str(doc.RootElement, "app_id"), Str(doc.RootElement, "title"));
    }

    private static string Str(System.Text.Json.JsonElement e, string name) =>
        e.ValueKind == System.Text.Json.JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == System.Text.Json.JsonValueKind.String ? v.GetString() ?? "" : "";

    /**
     * KDE Plasma: KWin has no "who is focused" call, but it runs small scripts. This loads a one-line one that prints the
     * focused window's class and title (tagged with a fresh nonce) to KWin's log, runs it, unloads it, and reads the line back
     * from the journal. Needs busctl and journalctl, and permission to read the user's journal; without them it just returns null.
     */
    private static (string, string)? Kwin()
    {
        var busctl = Sh.Which("busctl");
        var journalctl = Sh.Which("journalctl");
        if (busctl == null || journalctl == null) return null;
        const string name = "tokalot-active-window";
        var nonce = Guid.NewGuid().ToString("N");
        var file = Paths.File("kwin-active-window.js");
        System.IO.File.WriteAllText(file,
            "var w = workspace.activeWindow || workspace.activeClient;\n" +
            "print(\"TOKALOT_ACTIVE " + nonce + " \" + (w ? String(w.resourceClass) + \"\\t\" + String(w.caption).replace(/[\\r\\n]+/g, \" \") : \"\"));\n");
        string[] Call(string path, string iface, string member, string sig, params string[] args) =>
            new[] { "--user", "call", "org.kde.KWin", path, iface, member }.Concat(sig.Length > 0 ? new[] { sig }.Concat(args) : args).ToArray();
        Sh.Run(busctl, Call("/Scripting", "org.kde.kwin.Scripting", "unloadScript", "s", name), timeoutMs: 800);
        var load = Sh.Run(busctl, Call("/Scripting", "org.kde.kwin.Scripting", "loadScript", "ss", file, name), timeoutMs: 800);
        var m = Regex.Match(load.Out, @"^i\s+(\d+)");
        if (load.Exit != 0 || !m.Success) return null;
        try
        {
            // Plasma 6 puts the script at /Scripting/Script<id>, Plasma 5 at /<id>.
            var run = Sh.Run(busctl, Call("/Scripting/Script" + m.Groups[1].Value, "org.kde.kwin.Script", "run", ""), timeoutMs: 800);
            if (run.Exit != 0) run = Sh.Run(busctl, Call("/" + m.Groups[1].Value, "org.kde.kwin.Script", "run", ""), timeoutMs: 800);
            if (run.Exit != 0) return null;
        }
        finally { Sh.Run(busctl, Call("/Scripting", "org.kde.kwin.Scripting", "unloadScript", "s", name), timeoutMs: 800); }
        // The line lands in the journal a moment after the script runs.
        for (int i = 0; i < 6; i++)
        {
            var log = Sh.Run(journalctl, new[] { "-t", "kwin_wayland", "--since", "-10s", "-o", "cat", "--no-pager" }, timeoutMs: 800);
            var hit = Regex.Match(log.Out, "TOKALOT_ACTIVE " + nonce + " ([^\\t\\r\\n]*)\\t?([^\\r\\n]*)");
            if (hit.Success) return (hit.Groups[1].Value, hit.Groups[2].Value);
            System.Threading.Thread.Sleep(80);
        }
        return null;
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
