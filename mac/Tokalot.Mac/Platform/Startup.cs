using System;
using System.IO;
using System.Security;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/**
 * "Start at login" through a LaunchAgent (~/Library/LaunchAgents/com.sidewinderzz.tokalot.plist),
 * which macOS starts when you log in. It opens Tokalot.app in the background with --background.
 * macOS shows a "Background item added" notice the first time, and lists it under System Settings ›
 * General › Login Items, where it can also be switched off.
 */
public static class Startup
{
    public const string Label = "com.sidewinderzz.tokalot";

    private static string AgentFile => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Library", "LaunchAgents", Label + ".plist");

    /** The Tokalot.app this copy runs from, or null for a test copy or one not inside an app bundle. */
    internal static string? Bundle
    {
        get
        {
            if (!OperatingSystem.IsMacOS() || Paths.IsTestInstance) return null;
            var exe = Environment.ProcessPath;
            if (exe == null) return null;
            // .../Tokalot.app/Contents/MacOS/Tokalot
            var macos = Path.GetDirectoryName(exe);
            var contents = macos == null ? null : Path.GetDirectoryName(macos);
            var app = contents == null ? null : Path.GetDirectoryName(contents);
            return app != null && app.EndsWith(".app", StringComparison.OrdinalIgnoreCase) && Path.GetFileName(macos) == "MacOS" ? app : null;
        }
    }

    /** False when this copy can't register itself (a development or test run). */
    public static bool Supported => Bundle != null;

    /** The LaunchAgent's contents for this copy of the app. */
    internal static string Plist(string app) =>
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
        "<plist version=\"1.0\">\n<dict>\n" +
        "  <key>Label</key>\n  <string>" + Label + "</string>\n" +
        "  <key>ProgramArguments</key>\n  <array>\n" +
        "    <string>/usr/bin/open</string>\n    <string>-g</string>\n    <string>-a</string>\n" +
        "    <string>" + SecurityElement.Escape(app) + "</string>\n" +
        "    <string>--args</string>\n    <string>--background</string>\n" +
        "  </array>\n" +
        "  <key>RunAtLoad</key>\n  <true/>\n" +
        "  <key>LimitLoadToSessionType</key>\n  <string>Aqua</string>\n" +
        "  <key>ProcessType</key>\n  <string>Interactive</string>\n" +
        "</dict>\n</plist>\n";

    public static void Apply(bool enabled)
    {
        try
        {
            if (Bundle is not { } app) return;
            if (enabled)
            {
                var content = Plist(app);
                if (File.Exists(AgentFile) && File.ReadAllText(AgentFile) == content) return;
                Directory.CreateDirectory(Path.GetDirectoryName(AgentFile)!);
                File.WriteAllText(AgentFile, content);
            }
            else File.Delete(AgentFile);
        }
        catch (Exception e) { App.Log("Start at login: " + e.Message); }
    }

    /** Linux adds an app-menu entry here; a Mac app is found in Applications and Spotlight already. */
    public static void EnsureMenuEntry() { }
}
