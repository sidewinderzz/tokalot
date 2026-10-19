using System;
using System.Reflection;
using System.Runtime.InteropServices;
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

    /** This copy is the Intel/AMD build running emulated on a Windows on ARM PC. */
    public static bool EmulatedOnArm =>
        RuntimeInformation.ProcessArchitecture == Architecture.X64 && RuntimeInformation.OSArchitecture == Architecture.Arm64;

    /** The installer for this PC: the native ARM64 one on Windows on ARM, otherwise the x64 one. */
    public static string SetupUrl => FeedUrl + (RuntimeInformation.OSArchitecture == Architecture.Arm64 ? "TokalotSetup-arm64.exe" : "TokalotSetup.exe");

    // Each build reads its own channel's feed (releases.win.json for x64, releases.win-arm64.json for ARM64).
    // An x64 copy on an ARM PC reads the ARM64 feed instead, so its next update moves it to the native build.
    private static UpdateManager Manager => manager ??= new UpdateManager(new SimpleWebSource(FeedUrl),
        EmulatedOnArm ? new UpdateOptions { ExplicitChannel = "win-arm64" } : null);

    /** False when running from a build folder instead of the installed app (updates can't apply). */
    public static bool CanUpdate
    {
        get { try { return Manager.IsInstalled; } catch { return false; } }
    }

    public static string? AvailableVersion => pending?.TargetFullRelease.Version.ToString();

    /** The last Check() couldn't reach GitHub (as opposed to finding nothing new). */
    public static bool LastCheckFailed { get; private set; }

    /** Checks GitHub for a newer desktop release. Returns the version, or null. */
    public static async Task<string?> Check()
    {
        if (!CanUpdate) return null;
        try
        {
            pending = await Manager.CheckForUpdatesAsync();
            LastCheckFailed = false;
            return AvailableVersion;
        }
        catch
        {
            LastCheckFailed = true;
            return null;
        }
    }

    /** Downloads the pending update, then restarts into it. [beforeRestart] runs just before the app exits. */
    public static async Task DownloadAndRestart(Action<int> progress, Action? beforeRestart = null)
    {
        if (pending == null) return;
        await Manager.DownloadUpdatesAsync(pending, progress);
        beforeRestart?.Invoke();
        Manager.ApplyUpdatesAndRestart(pending);
    }
}
