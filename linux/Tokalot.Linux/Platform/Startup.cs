using System;
using System.IO;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/** "Start at login" through an XDG autostart entry (~/.config/autostart/tokalot.desktop), plus the app-menu entry. */
public static class Startup
{
    private static string ConfigHome => Environment.GetEnvironmentVariable("XDG_CONFIG_HOME") is { Length: > 0 } c
        ? c : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config");
    private static string DataHome => Environment.GetEnvironmentVariable("XDG_DATA_HOME") is { Length: > 0 } d
        ? d : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");

    private static string AutostartFile => Path.Combine(ConfigHome, "autostart", "tokalot.desktop");

    /** The program to start: Tokalot's own executable. Null for a test copy or one run through "dotnet". */
    private static string? Exe
    {
        get
        {
            if (!OperatingSystem.IsLinux() || Paths.IsTestInstance) return null;
            var exe = Environment.ProcessPath;
            return exe == null || Path.GetFileNameWithoutExtension(exe) == "dotnet" ? null : exe;
        }
    }

    /** False when this copy can't register itself (a development or test run). */
    public static bool Supported => Exe != null;

    /** A path as one quoted argument of an Exec line, per the Desktop Entry spec. */
    private static string ExecQuote(string path)
    {
        // Inside double quotes: backslash, quote, backtick and dollar get a backslash in front...
        var q = path.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("`", "\\`").Replace("$", "\\$");
        // ...then, the whole value being a desktop-file string, every backslash is doubled again, and a literal % is %%.
        return "\"" + q.Replace("\\", "\\\\").Replace("%", "%%") + "\"";
    }

    private static string Entry(string exe, string args) =>
        "[Desktop Entry]\n" +
        "Type=Application\n" +
        "Name=Tokalot\n" +
        "Comment=Voice typing: hold Ctrl+Super\n" +
        "Exec=" + ExecQuote(exe) + args + "\n" +
        // Icon takes a bare path (the spec has no quoting for it); only the string escape for backslashes applies.
        "Icon=" + Path.Combine(AppContext.BaseDirectory, "Assets", "icon.png").Replace("\\", "\\\\") + "\n" +
        "Terminal=false\n" +
        "Categories=Utility;Accessibility;\n" +
        "StartupWMClass=Tokalot\n";

    private static void WriteIfChanged(string file, string content)
    {
        if (File.Exists(file) && File.ReadAllText(file) == content) return;
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        File.WriteAllText(file, content);
    }

    public static void Apply(bool enabled)
    {
        try
        {
            if (Exe is not { } exe) return;
            if (enabled) WriteIfChanged(AutostartFile, Entry(exe, " --background") + "X-GNOME-Autostart-enabled=true\n");
            else File.Delete(AutostartFile);
        }
        catch { }
    }

    /** An app-menu entry, so Tokalot can be found by searching "Tokalot" (the download is a plain folder with no installer). */
    public static void EnsureMenuEntry()
    {
        try
        {
            if (Exe is not { } exe) return;
            WriteIfChanged(Path.Combine(DataHome, "applications", "tokalot.desktop"), Entry(exe, ""));
        }
        catch (Exception e) { App.Log("App menu entry failed: " + e.Message); }
    }
}
