using System;
using System.Linq;
using System.Threading.Tasks;

namespace Tokalot.Desktop.Core;

/**
 * The three privacy levels picked on first run and on Settings › Privacy. A level is only a template: it sets the
 * switches below, and every switch can still be changed one by one, there or on its own page. The level shown is
 * worked out from the switches each time, so a change made anywhere shows up, and a mix that matches no level is
 * "Custom". Nothing extra is stored or synced; the switches themselves sync as before.
 * Same levels as the Android app (Privacy.kt), less what only a phone has.
 */
public static class Privacy
{
    public enum Level { Full, Balanced, Private }

    public static string Label(Level l) => l switch
    {
        Level.Full => "Everything on",
        Level.Balanced => "Balanced",
        _ => "Most private",
    };

    public static string Blurb(Level l) => l switch
    {
        Level.Full => "Every feature, including learning from your corrections. Your speech goes to the services you pick, with your own keys.",
        Level.Balanced => "Cloud speech and AI cleanup, but Tokalot never looks at what's already in your text boxes.",
        _ => "Nothing leaves this computer. Speech to text runs here (slower, more mistakes), with no AI cleanup.",
    };

    /**
     * One switch on the Privacy page, and the levels that turn it on. A WindowsOnly (or NotMac) switch is still set
     * by a level everywhere (it syncs to the devices that have it), but only those computers show it or count it.
     */
    public sealed record Switch(string Title, string Note, Level[] Levels, Func<Settings, bool> IsOn, Action<Settings, bool> Set,
        bool WindowsOnly = false, bool NotMac = false)
    {
        public bool Shown => (!WindowsOnly || OperatingSystem.IsWindows()) && (!NotMac || !OperatingSystem.IsMacOS());
    }

    private static readonly Level[] Cloud = { Level.Full, Level.Balanced };

    public static readonly Switch[] Switches =
    {
        new("Cloud speech to text", "Off: speech to text runs on this computer and your voice never leaves it. Needs the offline model.", Cloud,
            s => s.Stt != "LOCAL",
            (s, on) => { if (on) s.Stt = CloudStt(s); else { s.LastCloudStt = s.Stt; s.Stt = "LOCAL"; } }),
        new("AI cleanup", "Removes ums, applies \"no wait, I mean\" corrections and fits the style of the app you're in. Off: only ums are removed, on this computer.", Cloud,
            s => s.Cleanup != "OFF",
            (s, on) => { if (on) s.Cleanup = CloudCleanup(s); else { s.LastCleanup = s.Cleanup; s.Cleanup = "OFF"; } }),
        new("Transcribe while I talk", "Sends what you've said so far each time you pause, so long dictations come back quickly.", Cloud,
            s => s.LiveStt, (s, on) => s.LiveStt = on),
        new("Learn from my corrections", "Looks at the text box again for two minutes after a dictation and adds names you respell, and words you spell out, to your dictionary.", new[] { Level.Full },
            s => s.LearnWords, (s, on) => s.LearnWords = on, WindowsOnly: true),
        new("Terminal commands (beta)", "In a terminal, what you say becomes a shell command. Sends your system's name, package manager and installed tools to your AI cleanup with the request.", new[] { Level.Full },
            s => s.ShellCommands, (s, on) => s.ShellCommands = on),
    };

    /** The switches this computer shows. */
    public static Switch[] Here => Switches.Where(w => w.Shown).ToArray();

    /** How long recordings are kept at each level, in days. */
    public static int KeepDays(Level l) => l switch { Level.Full => 30, Level.Balanced => 7, _ => 0 };

    /** The level the current switches match, or null for Custom. */
    public static Level? Current(Settings s)
    {
        foreach (var l in Enum.GetValues<Level>())
            if (Here.All(w => w.IsOn(s) == w.Levels.Contains(l)) && s.AudioKeepDays == KeepDays(l)) return l;
        return null;
    }

    /** Sets every switch to the level. Switches already right are left alone, so a cloud provider picked earlier stays. Call Save after. */
    public static void Apply(Settings s, Level l)
    {
        foreach (var w in Switches)
        {
            var want = w.Levels.Contains(l);
            if (w.IsOn(s) != want) w.Set(s, want);
        }
        var days = KeepDays(l);
        if (s.AudioKeepDays != days)
        {
            s.AudioKeepDays = days;
            Task.Run(() => AudioStore.Prune(days));
        }
    }

    /** The cloud speech service to go back to: the last one used, or the first with a key, else Groq. */
    private static string CloudStt(Settings s)
    {
        if (s.LastCloudStt != "LOCAL" && Catalog.Stt.Any(o => o.Id == s.LastCloudStt)) return s.LastCloudStt;
        return Catalog.Stt.FirstOrDefault(o => o.Service != null && s.Key(o.Service).Length > 0)?.Id ?? "GROQ";
    }

    private static string CloudCleanup(Settings s)
    {
        if (s.LastCleanup != "OFF" && Catalog.Cleanup.Any(o => o.Id == s.LastCleanup)) return s.LastCleanup;
        return Catalog.Cleanup.FirstOrDefault(o => o.Service != null && s.Key(o.Service).Length > 0)?.Id ?? "GROQ";
    }
}
