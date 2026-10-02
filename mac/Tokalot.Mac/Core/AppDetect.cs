using System;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/** The app you're dictating into. Key is used for per-app overrides; Label is shown in history. */
public sealed record ActiveApp(string Key, string Label);

/**
 * Figures out which app (and, for browsers, which site) has focus, and what style it gets.
 * Mac: the frontmost app comes from NSWorkspace, which needs no permission. A browser's tab title
 * comes from its focused window through the Accessibility interface, which works once Tokalot is
 * allowed under Accessibility (it needs that to paste anyway); without it, a browser counts as
 * "Everything else".
 */
public static class AppDetect
{
    // Bundle identifiers (lowercase) of browsers.
    private static readonly string[] Browsers =
    {
        "com.apple.safari", "com.google.chrome", "com.google.chrome.beta", "com.google.chrome.canary", "org.chromium.chromium", "org.mozilla.firefox",
        "org.mozilla.firefoxdeveloperedition", "com.microsoft.edgemac", "com.brave.browser", "company.thebrowser.browser", "com.operasoftware.opera",
        "com.vivaldi.vivaldi", "app.zen-browser.zen", "org.mozilla.librewolf", "com.kagi.kagimacos", "com.apple.safaritechnologypreview",
    };

    // Bundle identifiers (lowercase) -> category.
    private static readonly (string Category, string[] Bundles)[] KnownBundles =
    {
        ("MESSAGING", new[] { "com.apple.mobilesms", "net.whatsapp.whatsapp", "desktop.whatsapp", "com.tinyspeck.slackmacgap", "com.hnc.discord",
            "ru.keepcoder.telegram", "org.telegram.desktop", "org.whispersystems.signal-desktop", "com.microsoft.teams2", "com.microsoft.teams",
            "com.facebook.archon", "us.zoom.xos", "im.riot.app", "com.mattermost.desktop", "com.skype.skype", "com.apple.facetime" }),
        ("EMAIL", new[] { "com.apple.mail", "com.microsoft.outlook", "com.readdle.smartemail-mac", "com.superhuman.electron", "org.mozilla.thunderbird",
            "com.mimestream.mimestream", "it.bloop.airmail2", "com.freron.mailmate", "com.canarymail.mac", "io.canarymail.mac", "com.readdle.spark" }),
        ("AI_CODE", new[] { "com.microsoft.vscode", "com.microsoft.vscodeinsiders", "com.todesktop.230313mzl4w4u92", "com.exafunction.windsurf",
            "com.apple.dt.xcode", "com.anthropic.claudefordesktop", "com.openai.chat", "dev.zed.zed", "com.sublimetext.4", "com.sublimetext.3",
            "com.panic.nova", "com.barebones.bbedit", "com.google.android.studio", "com.vscodium" }),
    };

    /** Terminals; they count as AI & code. (⌘V pastes in them as everywhere else on a Mac.) */
    private static readonly string[] Terminals =
    {
        "com.apple.terminal", "com.googlecode.iterm2", "dev.warp.warp-stable", "dev.warp.warp", "com.mitchellh.ghostty", "net.kovidgoyal.kitty",
        "io.alacritty", "org.alacritty", "com.github.wez.wezterm", "co.zeit.hyper", "org.tabby",
    };

    // App names (lowercase), whole or one of their words -> category, for apps not in the list above.
    // The Windows and Linux names are kept so app choices restored from their backups still mean the same thing.
    private static readonly (string Category, string[] Names)[] KnownApps =
    {
        ("MESSAGING", new[] { "whatsapp", "slack", "discord", "telegram", "signal", "teams", "messenger", "messages", "element", "zoom", "mattermost" }),
        ("EMAIL", new[] { "outlook", "mail", "thunderbird", "spark", "airmail", "mimestream", "superhuman" }),
        ("AI_CODE", new[] { "code", "cursor", "windsurf", "xcode", "claude", "chatgpt", "zed", "sublime", "jetbrains", "intellij", "pycharm", "webstorm",
            "clion", "goland", "rider", "rustrover", "terminal", "iterm", "iterm2", "warp", "ghostty", "kitty", "alacritty", "wezterm", "nova", "bbedit" }),
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

    /** The frontmost app as macOS reports it: process id, name, bundle identifier. */
    public readonly record struct Front(int Pid, string Name, string Bundle);

    private static readonly int OwnPid = Environment.ProcessId;

    /** The app in front right now, or null. Needs no permission. Safe on any thread. */
    public static Front? Frontmost()
    {
        if (!OperatingSystem.IsMacOS()) return null;
        try
        {
            using var pool = Native.AutoreleasePool();
            Native.Load(Native.AppKit);
            var ws = Native.Send(Native.Class("NSWorkspace"), "sharedWorkspace");
            var app = Native.Send(ws, "frontmostApplication");
            if (app == IntPtr.Zero) return null;
            return new Front(Native.SendRI(app, "processIdentifier"), Native.SendText(app, "localizedName") ?? "", Native.SendText(app, "bundleIdentifier") ?? "");
        }
        catch { return null; }
    }

    /** The last app other than Tokalot that was in front (Tokalot comes to the front when you click its indicator). */
    public static Front? LastOther { get; private set; }

    /** Notes the frontmost app if it isn't Tokalot. Called often; cheap. */
    public static Front? Remember()
    {
        var f = Frontmost();
        if (f is { } x && x.Pid != OwnPid && x.Pid > 0)
        {
            LastOther = x;
            if (x.Name.Length > 0) Note(x);
        }
        return f;
    }

    public static ActiveApp? Detect()
    {
        try
        {
            var f = Remember();
            // Tokalot itself is in front (its indicator was clicked): the app you were in before counts.
            if (f is { } x && x.Pid == OwnPid) f = LastOther;
            if (f is not { } front || front.Name.Length == 0) return null;
            var bundle = front.Bundle.ToLowerInvariant();
            var name = front.Name;
            if (Browsers.Contains(bundle))
            {
                var t = WindowTitle(front.Pid) ?? "";
                foreach (var s in Sites)
                    if (Regex.IsMatch(t, $@"(?<![\w]){Regex.Escape(s.Pattern)}(?![\w])"))
                        return new ActiveApp(s.Key, $"{s.Label} ({name})");
            }
            return new ActiveApp("app:" + name.ToLowerInvariant(), name);
        }
        catch { return null; }
    }

    /** Remembered from Detect so Categorize can look the bundle up later (history only keeps the key). */
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, string> BundleOf = new();

    // ---------- the focused window's title (Accessibility) ----------

    [DllImport(Native.ApplicationServices)] private static extern IntPtr AXUIElementCreateApplication(int pid);
    [DllImport(Native.ApplicationServices)] private static extern int AXUIElementCopyAttributeValue(IntPtr element, IntPtr attribute, out IntPtr value);

    /** The title of the app's focused window, or null (no Accessibility permission, or no window). */
    private static string? WindowTitle(int pid)
    {
        if (!Permissions.Accessibility) return null;
        IntPtr app = IntPtr.Zero, win = IntPtr.Zero, title = IntPtr.Zero, aFocused = Native.Str("AXFocusedWindow"), aTitle = Native.Str("AXTitle");
        try
        {
            app = AXUIElementCreateApplication(pid);
            if (app == IntPtr.Zero || AXUIElementCopyAttributeValue(app, aFocused, out win) != 0 || win == IntPtr.Zero) return null;
            if (AXUIElementCopyAttributeValue(win, aTitle, out title) != 0) return null;
            return Native.Text(title);
        }
        catch { return null; }
        finally
        {
            foreach (var p in new[] { title, win, app, aFocused, aTitle }) if (p != IntPtr.Zero) Native.CFRelease(p);
        }
    }

    /** The words of an app name ("Visual Studio Code" -> visual, studio, code). */
    private static string[] Parts(string name) =>
        new[] { name }.Concat(name.Split(new[] { '.', '-', '_', ' ' }, StringSplitOptions.RemoveEmptyEntries)).ToArray();

    /** Never a terminal for pasting purposes: ⌘V works in Mac terminals. */
    public static bool IsTerminal(ActiveApp? app) => false;

    public static AppCategory Categorize(ActiveApp? app, Settings s)
    {
        if (app == null) return Catalog.CategoryById("OTHER");
        if (s.AppOverrides.TryGetValue(app.Key, out var o)) return Catalog.CategoryById(o);
        var site = Sites.FirstOrDefault(x => x.Key == app.Key);
        if (site.Key != null) return Catalog.CategoryById(site.Category);
        var proc = app.Key.StartsWith("app:") ? app.Key[4..] : app.Key;
        if (BundleFor(proc) is { } bundle)
        {
            if (Terminals.Contains(bundle)) return Catalog.CategoryById("AI_CODE");
            if (Browsers.Contains(bundle)) return Catalog.CategoryById("OTHER");
            foreach (var (cat, bundles) in KnownBundles)
                if (bundles.Contains(bundle)) return Catalog.CategoryById(cat);
        }
        var parts = Parts(proc);
        if (!new[] { "safari", "chrome", "firefox", "edge", "brave", "arc", "opera", "vivaldi", "zen", "browser" }.Any(parts.Contains))
            foreach (var (cat, names) in KnownApps)
                if (parts.Any(names.Contains)) return Catalog.CategoryById(cat);
        if (proc.Contains("mail")) return Catalog.CategoryById("EMAIL");
        if (proc.Contains("chat") || proc.Contains("messag")) return Catalog.CategoryById("MESSAGING");
        return Catalog.CategoryById("OTHER");
    }

    /** The bundle id behind an app name seen this run (the frontmost app's, or a running app's). */
    private static string? BundleFor(string lowerName)
    {
        if (BundleOf.TryGetValue(lowerName, out var b)) return b;
        if (LastOther is { } l && l.Name.ToLowerInvariant() == lowerName) return BundleOf[lowerName] = l.Bundle.ToLowerInvariant();
        return null;
    }

    /** Notes a name -> bundle pair, so Categorize can tell apps apart by bundle id (history keeps only the name). */
    internal static void Note(Front f) => BundleOf[f.Name.ToLowerInvariant()] = f.Bundle.ToLowerInvariant();

    // ---------- "what did cleanup fix" counters ----------

    private static readonly Regex Filler = new(@"(?i)\b(?:um+|uh+|erm|hmm+|you know)\b");
    private static readonly Regex Correction = new(@"(?i)\b(?:no wait|wait no|actually no|no actually|scratch that|sorry,? i meant|let me rephrase|or rather|correction)\b");
    public static int FillerCount(string raw) => Filler.Matches(raw).Count;
    public static int CorrectionCount(string raw) => Correction.Matches(raw).Count;
}
