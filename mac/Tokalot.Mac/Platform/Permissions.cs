using System;
using System.Runtime.InteropServices;

namespace Tokalot.Desktop.Platform;

/**
 * The three things a Mac asks the user to allow, each under System Settings › Privacy & Security:
 *   Input Monitoring  lets Tokalot see the Ctrl+Cmd shortcut (and Esc) while you're in other apps.
 *   Accessibility     lets Tokalot press ⌘V for you (and read the title of the browser tab you're in).
 *   Microphone        lets it record.
 * Each can be checked without asking, and asked for once (macOS then shows its own prompt).
 */
public static class Permissions
{
    [DllImport(Native.CoreGraphics)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool CGPreflightListenEventAccess();
    [DllImport(Native.CoreGraphics)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool CGRequestListenEventAccess();
    [DllImport(Native.CoreGraphics)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool CGPreflightPostEventAccess();
    [DllImport(Native.CoreGraphics)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool CGRequestPostEventAccess();
    [DllImport(Native.ApplicationServices)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool AXIsProcessTrusted();
    [DllImport(Native.ApplicationServices)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool AXIsProcessTrustedWithOptions(IntPtr options);

    private static bool Mac => OperatingSystem.IsMacOS();

    /** Input Monitoring: Tokalot may watch the keyboard (for the shortcut). */
    public static bool InputMonitoring
    {
        get { try { return !Mac || CGPreflightListenEventAccess(); } catch { return false; } }
    }

    /** Accessibility: Tokalot may press keys in other apps (⌘V) and read their window titles. */
    public static bool Accessibility
    {
        get { try { return !Mac || AXIsProcessTrusted() || CGPreflightPostEventAccess(); } catch { return false; } }
    }

    public enum Mic { NotAsked, Denied, Restricted, Allowed, Unknown }

    /** The microphone permission as macOS records it (AVCaptureDevice's authorization status for audio). */
    public static Mic Microphone
    {
        get
        {
            if (!Mac) return Mic.Allowed;
            try
            {
                Native.Load(Native.AVFoundation);
                using var pool = Native.AutoreleasePool();
                var type = Native.Constant(Native.AVFoundation, "AVMediaTypeAudio");
                var status = Native.SendRL(Native.Class("AVCaptureDevice"), "authorizationStatusForMediaType:", type);
                return status switch { 0 => Mic.NotAsked, 1 => Mic.Restricted, 2 => Mic.Denied, 3 => Mic.Allowed, _ => Mic.Unknown };
            }
            catch { return Mic.Unknown; }
        }
    }

    /** Shows macOS's own "Tokalot would like to receive keystrokes" prompt (only the first time; later it just answers). */
    public static bool RequestInputMonitoring()
    {
        try { return Mac && CGRequestListenEventAccess(); } catch { return false; }
    }

    /** Shows macOS's "Tokalot would like to control this computer using accessibility features" prompt. */
    public static bool RequestAccessibility()
    {
        if (!Mac) return true;
        try
        {
            var key = Native.Constant(Native.ApplicationServices, "kAXTrustedCheckOptionPrompt");
            var options = Native.Dict((key, Native.CFTrue));
            try { return AXIsProcessTrustedWithOptions(options); }
            finally { Native.CFRelease(options); }
        }
        catch
        {
            try { return CGRequestPostEventAccess(); } catch { return false; }
        }
    }

    public const string InputMonitoringPane = "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent";
    public const string AccessibilityPane = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";
    public const string MicrophonePane = "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone";
    public const string SoundInputPane = "x-apple.systempreferences:com.apple.preference.sound?input";

    /** Opens a page of System Settings. */
    public static void Open(string pane)
    {
        try { Sh.Spawn("/usr/bin/open", pane)?.Dispose(); } catch { }
    }
}
