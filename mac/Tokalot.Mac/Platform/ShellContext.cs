using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace Tokalot.Desktop.Platform;

/**
 * What Terminal commands tell the AI about this Mac, so "install ffmpeg" becomes brew instead of apt and a find or sed
 * is written for macOS's own (BSD) tools: the macOS version, the chip, the login shell, Homebrew or MacPorts, and the
 * handier command-line tools that are installed (fd, rg, bat...) that it should prefer over the older ones.
 * Read once, the first time a command is needed.
 */
public static class ShellContext
{
    private static readonly string[] Tools =
    {
        "fd", "rg", "bat", "eza", "duf", "dust", "fzf", "jq", "gsed", "gawk", "gls", "mas", "docker", "podman", "orbctl",
    };

    // An app started from Finder or the Dock gets only /usr/bin:/bin:/usr/sbin:/sbin, so the usual install places are looked in too.
    private static readonly string[] Dirs = { "/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin" };

    private static string? cached;

    public static string Describe() => cached ??= Build();

    private static bool Has(string name) => Sh.Has(name) || Dirs.Any(d => File.Exists(Path.Combine(d, name)));

    private static string Build()
    {
        string version;
        try { version = Native.MacVersion(); } catch { version = "macOS"; }
        var parts = new System.Collections.Generic.List<string>
        {
            version + " on " + (RuntimeInformation.OSArchitecture == Architecture.Arm64 ? "Apple silicon" : "an Intel Mac"),
        };
        var shell = Path.GetFileName(Environment.GetEnvironmentVariable("SHELL") ?? "");
        parts.Add("login shell: " + (shell.Length > 0 ? shell : "zsh"));
        if (Has("brew")) parts.Add("package manager: Homebrew (brew)");
        else if (Has("port")) parts.Add("package manager: MacPorts (port)");
        else parts.Add("no package manager installed (Homebrew is the usual one)");
        var tools = Tools.Where(Has).ToList();
        if (tools.Count > 0) parts.Add("installed: " + string.Join(", ", tools));
        return string.Join("; ", parts);
    }
}
