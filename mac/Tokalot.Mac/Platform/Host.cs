using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/** What the shared interface and controller need to say about the Mac. Same members as the Linux Host. */
public static class Host
{
    public const string Name = "Mac";

    /** Ctrl+Cmd: see HotkeyHook for why this pair. */
    public const string Shortcut = "Ctrl+Cmd";

    public static readonly string[] ShortcutKeys = { "⌃ Ctrl", "⌘ Cmd" };

    public const string PasteKeys = "⌘V";

    public const string TrayName = "menu bar";

    /** What to tell the user when the microphone couldn't be opened. */
    public static string MicProblem() => Permissions.Microphone switch
    {
        Permissions.Mic.Denied => "Tokalot isn't allowed to use the microphone. Allow it in System Settings › Privacy & Security › Microphone.",
        Permissions.Mic.Restricted => "This Mac doesn't allow apps to use the microphone (a profile or parental control blocks it).",
        _ => Recorder.InputDevice() == null ? "No microphone found. Check System Settings › Sound › Input." : "The microphone couldn't be opened. Check System Settings › Sound › Input.",
    };
}
