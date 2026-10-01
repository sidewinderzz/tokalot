using System;
using System.Reflection;
using System.Threading.Tasks;
using Velopack;
using Velopack.Sources;

namespace Tokalot.Desktop.Core;

/** In-app updates through Velopack, reading the newest Windows build from GitHub Releases. */
public static class Updater
{
    public const string RepoUrl = "https://github.com/sidewinderzz/tokalot";

    public static string CurrentVersion =>
        Assembly.GetExecutingAssembly().GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "0.0.0";

    private static UpdateManager? manager;
    private static UpdateInfo? pending;

    /** The rolling "desktop" release on GitHub always holds the newest Windows build. */
    public const string FeedUrl = RepoUrl + "/releases/download/desktop/";

    private static UpdateManager Manager => manager ??= new UpdateManager(new SimpleWebSource(FeedUrl));

    /** False when running from a build folder instead of the installed app (updates can't apply). */
    public static bool CanUpdate
    {
        get { try { return Manager.IsInstalled; } catch { return false; } }
    }

    public static string? AvailableVersion => pending?.TargetFullRelease.Version.ToString();

    /** Checks GitHub for a newer desktop release. Returns the version, or null. */
    public static async Task<string?> Check()
    {
        if (!CanUpdate) return null;
        try
        {
            pending = await Manager.CheckForUpdatesAsync();
            return AvailableVersion;
        }
        catch { return null; }
    }

    /** Downloads the pending update, then restarts into it. */
    public static async Task DownloadAndRestart(Action<int> progress)
    {
        if (pending == null) return;
        await Manager.DownloadUpdatesAsync(pending, progress);
        Manager.ApplyUpdatesAndRestart(pending);
    }
}
