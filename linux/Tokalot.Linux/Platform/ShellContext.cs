using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Platform;

/**
 * What Terminal commands tell the AI about this machine, so "update the system" becomes pacman on Arch and apt on Debian
 * instead of a guess: the distribution, its package manager, and the handier command-line tools that are installed
 * (fd, rg, bat...) that it should prefer over the older ones. Read once, the first time a command is needed.
 */
public static class ShellContext
{
    private static readonly string[] Tools = { "fd", "fdfind", "rg", "bat", "batcat", "eza", "duf", "dust", "fzf", "jq", "yay", "paru", "flatpak", "docker", "podman" };

    // /etc/os-release ID or ID_LIKE -> package manager
    private static readonly (string[] Ids, string Manager)[] Managers =
    {
        (new[] { "arch", "manjaro", "endeavouros", "cachyos", "garuda" }, "pacman"),
        (new[] { "debian", "ubuntu", "linuxmint", "pop", "raspbian", "kali" }, "apt"),
        (new[] { "fedora", "rhel", "centos", "rocky", "almalinux", "nobara" }, "dnf"),
        (new[] { "opensuse", "suse", "sles" }, "zypper"),
        (new[] { "alpine" }, "apk"),
        (new[] { "void" }, "xbps"),
        (new[] { "gentoo" }, "emerge"),
        (new[] { "nixos" }, "nix"),
    };

    private static string? cached;

    public static string Describe() => cached ??= Build();

    private static string Build()
    {
        string name = "Linux", ids = "";
        try
        {
            var text = File.ReadAllText("/etc/os-release");
            string Field(string key) => Regex.Match(text, "^" + key + "=\"?([^\"\\n]*)\"?", RegexOptions.Multiline).Groups[1].Value.Trim();
            if (Field("PRETTY_NAME") is { Length: > 0 } pretty) name = pretty;
            ids = (Field("ID") + " " + Field("ID_LIKE")).ToLowerInvariant();
        }
        catch { }
        var parts = new System.Collections.Generic.List<string> { name };
        var manager = Managers.FirstOrDefault(m => ids.Split(' ', StringSplitOptions.RemoveEmptyEntries).Any(i => m.Ids.Contains(i))).Manager;
        if (manager != null) parts.Add("package manager: " + manager);
        var shell = Path.GetFileName(Environment.GetEnvironmentVariable("SHELL") ?? "");
        if (shell.Length > 0) parts.Add("login shell: " + shell);
        var tools = Tools.Where(Sh.Has).ToList();
        if (tools.Count > 0) parts.Add("installed: " + string.Join(", ", tools));
        return string.Join("; ", parts);
    }
}
