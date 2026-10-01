using System;
using System.Reflection;
using System.Threading.Tasks;

namespace Tokalot.Desktop.Core;

/**
 * Same surface as the Windows updater (which uses Velopack), but the Linux build has no
 * installer or release feed yet, so nothing is ever offered: CanUpdate is false.
 */
public static class Updater
{
    public const string RepoUrl = "https://github.com/sidewinderzz/tokalot";

    public static string CurrentVersion =>
        Assembly.GetExecutingAssembly().GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "0.0.0";

    /** False: there is no installed/updatable Linux package yet. */
    public static bool CanUpdate => false;

    public static string? AvailableVersion => null;

    public static Task<string?> Check() => Task.FromResult<string?>(null);

    public static Task DownloadAndRestart(Action<int> progress) => Task.CompletedTask;
}
