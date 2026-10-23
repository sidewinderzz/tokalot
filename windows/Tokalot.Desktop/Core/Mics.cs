using System;
using System.Collections.Generic;
using System.Linq;

namespace Tokalot.Desktop.Core;

/** One microphone the computer has. Id is what the recorder opens it by; Headset: a Bluetooth or other headset's. */
public sealed record Mic(string Id, string Name, bool Headset);

/**
 * Which microphone to record from (Settings › Recording › Microphone). Each platform's Recorder lists the mics
 * (Recorder.Mics) and opens the one picked here; null means the system's default, as before. Kept on this computer only.
 */
public static class Mics
{
    private static readonly string[] HeadsetWords = { "headset", "hands-free", "handsfree", "bluetooth", "airpods", "buds", "jabra", "bose", "plantronics", "poly " };

    /** Whether a mic's name sounds like a headset's ("Headset (Jabra Talk 25 Hands-Free)", "AirPods Pro"). */
    public static bool LooksLikeHeadset(string name) =>
        HeadsetWords.Any(w => name.Contains(w, StringComparison.OrdinalIgnoreCase));

    /** The mic a recording starting now should use, from Settings and [list] (Recorder.Mics); null for the default. */
    public static Mic? ForRecording(Func<List<Mic>> list)
    {
        var s = Settings.Current;
        if (s.MicDevice.Length == 0 && !s.PreferHeadset) return null; // the default: no need to look
        try { return Choose(list(), s.MicDevice, s.PreferHeadset); }
        catch { return null; }
    }

    /**
     * A connected headset's mic when [preferHeadset] is on and there is one; otherwise the mic picked in Settings if it's
     * connected; otherwise null (the system default).
     */
    public static Mic? Choose(IReadOnlyList<Mic> mics, string picked, bool preferHeadset)
    {
        if (preferHeadset && mics.FirstOrDefault(m => m.Headset) is { } h) return h;
        return picked.Length == 0 ? null : mics.FirstOrDefault(m => m.Id == picked);
    }
}
