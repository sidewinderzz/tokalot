using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/**
 * What the shared interface and controller (also compiled into the Mac app) need to say about this
 * platform: the shortcut's name, the paste keys, and so on. The Mac app has its own Host with the
 * same members.
 */
public static class Host
{
    /** The platform as it reads in "Tokalot for Linux". */
    public const string Name = "Linux";

    /** The dictation shortcut as it reads in messages ("Hold Ctrl+Super while you talk"). */
    public const string Shortcut = "Ctrl+Super";

    /** The two keys of the shortcut, as drawn on the key caps in the sidebar. */
    public static readonly string[] ShortcutKeys = { "Ctrl", "Super" };

    /** The keys that paste. */
    public const string PasteKeys = "Ctrl+V";

    /** Where Tokalot lives between dictations. */
    public const string TrayName = "tray";

    /** What to tell the user when the microphone couldn't be opened. */
    public static string MicProblem() => Recorder.Tool == null
        ? "No recorder found. Install PipeWire, pulseaudio-utils or alsa-utils."
        : "No microphone found. Check your sound settings.";
}
