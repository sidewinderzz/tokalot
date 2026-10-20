using System;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Core;

/** The app you're dictating into. Key is used for per-app overrides; Label is shown in history. */
public sealed record ActiveApp(string Key, string Label);

/** Figures out which program (and, for browsers, which site) has focus, and what style it gets. */
public static class AppDetect
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);

    private static readonly string[] Browsers = { "chrome", "msedge", "firefox", "brave", "opera", "vivaldi", "arc", "librewolf", "zen" };

    // Process names (lowercase, no .exe) -> category
    private static readonly (string Category, string[] Processes)[] KnownApps =
    {
        ("MESSAGING", new[] { "whatsapp", "slack", "discord", "telegram", "signal", "ms-teams", "teams", "messenger", "element", "zoom" }),
        ("EMAIL", new[] { "outlook", "olk", "thunderbird", "mailspring", "hxoutlook", "em client", "mailbird" }),
        ("AI_CODE", new[] { "code", "cursor", "windsurf", "devenv", "idea64", "rider64", "pycharm64", "webstorm64", "clion64", "goland64",
            "windowsterminal", "wt", "cmd", "powershell", "pwsh", "claude", "chatgpt", "zed", "sublime_text", "notepad++", "android studio", "studio64" }),
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
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return null;
            GetWindowThreadProcessId(hwnd, out var pid);
            using var p = Process.GetProcessById((int)pid);
            var name = p.ProcessName.ToLowerInvariant();
            if (name == "tokalot") return null;
            var title = new StringBuilder(512);
            GetWindowText(hwnd, title, title.Capacity);
            var t = title.ToString();
            var friendly = Friendly(p, name);

            if (Browsers.Contains(name))
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

    private static string Friendly(Process p, string fallback)
    {
        try
        {
            var d = p.MainModule?.FileVersionInfo.FileDescription;
            if (!string.IsNullOrWhiteSpace(d)) return d.Trim();
        }
        catch { }
        return char.ToUpperInvariant(fallback[0]) + fallback[1..];
    }

    public static AppCategory Categorize(ActiveApp? app, Settings s)
    {
        if (app == null) return Catalog.CategoryById("OTHER");
        if (s.AppOverrides.TryGetValue(app.Key, out var o)) return Catalog.CategoryById(o);
        var site = Sites.FirstOrDefault(x => x.Key == app.Key);
        if (site.Key != null) return Catalog.CategoryById(site.Category);
        var proc = app.Key.StartsWith("app:") ? app.Key[4..] : app.Key;
        foreach (var (cat, procs) in KnownApps)
            if (procs.Contains(proc)) return Catalog.CategoryById(cat);
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
