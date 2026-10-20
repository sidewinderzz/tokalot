using System;
using System.Globalization;
using System.Text.RegularExpressions;

namespace Tokalot.Desktop.Platform;

/**
 * Fractional display scaling (125%, 150%…) on GNOME's X11 session can stall windows drawn with the
 * graphics card for many seconds, freezing them until the driver catches up. Windows drawn with the
 * processor aren't affected, and Tokalot's window is light enough that nobody notices the difference,
 * so start-up switches to that when a monitor uses a fractional scale.
 */
public static class Scaling
{
    /** The first non-whole monitor scale in use, like 1.25, or null if every monitor is at 100%, 200%… or the desktop can't be asked. */
    public static double? Fractional()
    {
        try
        {
            if (!OperatingSystem.IsLinux() || Sh.Which("gdbus") is not { } gdbus) return null;
            var r = Sh.Run(gdbus, new[]
            {
                "call", "--session", "--timeout", "1", "--dest", "org.gnome.Mutter.DisplayConfig", "--object-path", "/org/gnome/Mutter/DisplayConfig",
                "--method", "org.gnome.Mutter.DisplayConfig.GetCurrentState",
            }, timeoutMs: 1500);
            return r.Exit == 0 ? FractionalIn(r.Out) : null;
        }
        catch { return null; }
    }

    /**
     * Reads GetCurrentState's answer. Each logical monitor in it starts "(x, y, scale, uint32 transform, primary, [";
     * the display modes also hold decimals but never in that shape.
     */
    public static double? FractionalIn(string state)
    {
        foreach (Match m in Regex.Matches(state, @"\(-?\d+, -?\d+, (\d+(?:\.\d+)?), uint32 \d+, (?:true|false), \["))
        {
            if (!double.TryParse(m.Groups[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var scale)) continue;
            if (Math.Abs(scale - Math.Round(scale)) > 0.01) return scale;
        }
        return null;
    }
}
