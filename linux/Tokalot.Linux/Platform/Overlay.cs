using Avalonia;
using Avalonia.Controls;

namespace Tokalot.Desktop.Platform;

/**
 * What the always-on-top windows (the recording indicator and the message pill) need from the
 * desktop. Shared interface code calls this; on Linux it is the X11 calls, and the Mac app has its
 * own Overlay with the same members.
 */
public static class Overlay
{
    /** Clicking the window must never take the keyboard away from the app you're dictating into. Call before it is first shown. */
    public static void NeverFocus(Window window) => X11.NeverFocus(window);

    /** Only this rectangle of the window (pixels, relative to it) takes clicks; null lets every click through. */
    public static void SetInputRegion(Window window, PixelRect? rect) => X11.SetInputRegion(window, rect);

    /** Whether the focused window is full screen, and the middle of it on screen (pixels). */
    public static (bool FullScreen, PixelPoint? Center) ActiveWindowState() => X11.ActiveWindowState();

    /** Where the mouse pointer is on screen (pixels). */
    public static PixelPoint? Pointer() => X11.Pointer();
}
