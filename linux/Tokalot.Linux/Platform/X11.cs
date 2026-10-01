using System;
using System.Runtime.InteropServices;
using System.Text;
using Avalonia;
using Avalonia.Controls;

namespace Tokalot.Desktop.Platform;

/**
 * The few things Tokalot asks the X server directly (Avalonia has no API for them): which window
 * is active and whether it is full screen, where the pointer is, and making the indicator and
 * pill windows never take focus and only catch clicks where they are drawn.
 * Uses its own connection to the display. Everything here quietly does nothing when there is no
 * X server or libX11 (every call is guarded), and in a Wayland session it only sees XWayland windows.
 */
public static class X11
{
    private const string Lib = "libX11.so.6", Fixes = "libXfixes.so.3";

    [DllImport(Lib)] private static extern IntPtr XOpenDisplay(IntPtr name);
    [DllImport(Lib)] private static extern IntPtr XDefaultRootWindow(IntPtr d);
    [DllImport(Lib)] private static extern IntPtr XInternAtom(IntPtr d, [MarshalAs(UnmanagedType.LPStr)] string name, int onlyIfExists);
    [DllImport(Lib)] private static extern int XGetWindowProperty(IntPtr d, IntPtr w, IntPtr property, IntPtr offset, IntPtr length, int delete,
        IntPtr reqType, out IntPtr actualType, out int actualFormat, out IntPtr nItems, out IntPtr bytesAfter, out IntPtr prop);
    [DllImport(Lib)] private static extern int XChangeProperty(IntPtr d, IntPtr w, IntPtr property, IntPtr type, int format, int mode, IntPtr[] data, int n);
    [DllImport(Lib)] private static extern int XFree(IntPtr data);
    [DllImport(Lib)] private static extern int XFlush(IntPtr d);
    [DllImport(Lib)] private static extern IntPtr XGetWMHints(IntPtr d, IntPtr w);
    [DllImport(Lib)] private static extern int XSetWMHints(IntPtr d, IntPtr w, ref XWMHints hints);
    [DllImport(Lib)] private static extern int XQueryPointer(IntPtr d, IntPtr w, out IntPtr root, out IntPtr child, out int rootX, out int rootY, out int winX, out int winY, out uint mask);
    [DllImport(Lib)] private static extern int XGetGeometry(IntPtr d, IntPtr drawable, out IntPtr root, out int x, out int y, out uint width, out uint height, out uint border, out uint depth);
    [DllImport(Lib)] private static extern int XTranslateCoordinates(IntPtr d, IntPtr src, IntPtr dest, int srcX, int srcY, out int destX, out int destY, out IntPtr child);

    [DllImport(Fixes)] private static extern IntPtr XFixesCreateRegion(IntPtr d, XRectangle[]? rects, int n);
    [DllImport(Fixes)] private static extern void XFixesSetWindowShapeRegion(IntPtr d, IntPtr w, int kind, int xOff, int yOff, IntPtr region);
    [DllImport(Fixes)] private static extern void XFixesDestroyRegion(IntPtr d, IntPtr region);

    [StructLayout(LayoutKind.Sequential)]
    private struct XWMHints
    {
        public IntPtr flags;
        public int input, initial_state;
        public IntPtr icon_pixmap, icon_window;
        public int icon_x, icon_y;
        public IntPtr icon_mask, window_group;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct XRectangle { public short x, y; public ushort width, height; }

    private static readonly object Gate = new();
    private static IntPtr display;
    private static bool tried;

    private static IntPtr Display
    {
        get
        {
            if (tried) return display;
            tried = true;
            try
            {
                if (OperatingSystem.IsLinux() && !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DISPLAY")))
                    display = XOpenDisplay(IntPtr.Zero);
            }
            catch { display = IntPtr.Zero; }
            return display;
        }
    }

    public static bool Available { get { lock (Gate) return Display != IntPtr.Zero; } }

    private static IntPtr Atom(string name) => XInternAtom(display, name, 0);

    /** Reads a whole property. Returns its bytes, or null. For 32-bit formats each item is a C long (8 bytes here). */
    private static byte[]? Property(IntPtr w, string name, out int format)
    {
        format = 0;
        if (XGetWindowProperty(display, w, Atom(name), IntPtr.Zero, new IntPtr(4096), 0, IntPtr.Zero,
                out _, out format, out var n, out _, out var data) != 0 || data == IntPtr.Zero)
            return null;
        try
        {
            int count = (int)n.ToInt64();
            int size = format == 32 ? IntPtr.Size : format / 8;
            var bytes = new byte[count * size];
            Marshal.Copy(data, bytes, 0, bytes.Length);
            return bytes;
        }
        finally { XFree(data); }
    }

    private static IntPtr[] Longs(byte[]? bytes)
    {
        if (bytes == null) return Array.Empty<IntPtr>();
        var r = new IntPtr[bytes.Length / IntPtr.Size];
        for (int i = 0; i < r.Length; i++) r[i] = new IntPtr(BitConverter.ToInt64(bytes, i * IntPtr.Size));
        return r;
    }

    private static IntPtr ActiveWindow()
    {
        var ids = Longs(Property(XDefaultRootWindow(display), "_NET_ACTIVE_WINDOW", out _));
        return ids.Length > 0 ? ids[0] : IntPtr.Zero;
    }

    /** Class and title of the focused window. Null when the X server can't be asked at all; empty strings when nothing is focused. */
    public static (string Class, string Title)? ActiveWindowClassAndTitle()
    {
        lock (Gate)
        {
            try
            {
                if (Display == IntPtr.Zero) return null;
                var w = ActiveWindow();
                if (w == IntPtr.Zero) return ("", "");
                // WM_CLASS is "instance\0Class\0".
                var cls = "";
                if (Property(w, "WM_CLASS", out _) is { } c)
                {
                    var parts = Encoding.UTF8.GetString(c).Split('\0', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length > 0) cls = parts[^1];
                }
                var title = Property(w, "_NET_WM_NAME", out _) is { Length: > 0 } t ? Encoding.UTF8.GetString(t)
                    : Property(w, "WM_NAME", out _) is { } t2 ? Encoding.UTF8.GetString(t2) : "";
                return (cls, title.TrimEnd('\0'));
            }
            catch { return null; }
        }
    }

    /** Whether the focused window is full screen, and the middle of it on screen (pixels). */
    public static (bool FullScreen, PixelPoint? Center) ActiveWindowState()
    {
        lock (Gate)
        {
            try
            {
                if (Display == IntPtr.Zero) return (false, null);
                var w = ActiveWindow();
                if (w == IntPtr.Zero) return (false, null);
                bool full = false;
                var fs = Atom("_NET_WM_STATE_FULLSCREEN");
                foreach (var a in Longs(Property(w, "_NET_WM_STATE", out _))) if (a == fs) full = true;
                var desktop = Atom("_NET_WM_WINDOW_TYPE_DESKTOP");
                foreach (var a in Longs(Property(w, "_NET_WM_WINDOW_TYPE", out _))) if (a == desktop) full = false;

                PixelPoint? center = null;
                if (XGetGeometry(display, w, out var root, out _, out _, out var width, out var height, out _, out _) != 0
                    && XTranslateCoordinates(display, w, root, 0, 0, out var x, out var y, out _) != 0)
                    center = new PixelPoint(x + (int)width / 2, y + (int)height / 2);
                return (full, center);
            }
            catch { return (false, null); }
        }
    }

    /** Where the mouse pointer is on screen (pixels). */
    public static PixelPoint? Pointer()
    {
        lock (Gate)
        {
            try
            {
                if (Display == IntPtr.Zero) return null;
                return XQueryPointer(display, XDefaultRootWindow(display), out _, out _, out var x, out var y, out _, out _, out _) != 0
                    ? new PixelPoint(x, y) : null;
            }
            catch { return null; }
        }
    }

    private static IntPtr Xid(Window window)
    {
        var h = window.TryGetPlatformHandle();
        return h != null && h.HandleDescriptor == "XID" ? h.Handle : IntPtr.Zero;
    }

    /**
     * Tells the window manager this window never wants keyboard focus (so clicking the indicator
     * leaves the cursor in the app you're dictating into) and that it belongs on every workspace.
     * Call before the window is first shown.
     */
    public static void NeverFocus(Window window)
    {
        lock (Gate)
        {
            try
            {
                var w = Xid(window);
                if (w == IntPtr.Zero || Display == IntPtr.Zero) return;
                var hints = new XWMHints();
                var existing = XGetWMHints(display, w);
                if (existing != IntPtr.Zero)
                {
                    hints = Marshal.PtrToStructure<XWMHints>(existing);
                    XFree(existing);
                }
                hints.flags = new IntPtr(hints.flags.ToInt64() | 1); // InputHint
                hints.input = 0;
                XSetWMHints(display, w, ref hints);
                // A notification-like floating window: tiling window managers (i3, and sway/Hyprland for XWayland
                // windows) leave these alone instead of giving them a tile. Utility is the fallback type.
                XChangeProperty(display, w, Atom("_NET_WM_WINDOW_TYPE"), new IntPtr(4) /* XA_ATOM */, 32, 0,
                    new[] { Atom("_NET_WM_WINDOW_TYPE_NOTIFICATION"), Atom("_NET_WM_WINDOW_TYPE_UTILITY") }, 2);
                // _NET_WM_DESKTOP = 0xFFFFFFFF: all workspaces.
                XChangeProperty(display, w, Atom("_NET_WM_DESKTOP"), new IntPtr(6) /* XA_CARDINAL */, 32, 0, new[] { new IntPtr(0xFFFFFFFFL) }, 1);
                XFlush(display);
            }
            catch (Exception e) { LogOnce("window hints", e); }
        }
    }

    private static readonly System.Collections.Generic.HashSet<string> Logged = new();

    /** These calls run often; say what went wrong once rather than on every frame. */
    private static void LogOnce(string what, Exception e)
    {
        if (Logged.Add(what)) App.Log("X11 " + what + " unavailable: " + e.GetType().Name + ": " + e.Message);
    }

    /**
     * Limits where the window catches mouse clicks to one rectangle (pixels, relative to the window),
     * or nowhere at all when rect is null. A transparent X11 window otherwise blocks clicks over its
     * whole area, including the parts where nothing is drawn.
     */
    public static void SetInputRegion(Window window, PixelRect? rect)
    {
        lock (Gate)
        {
            try
            {
                var w = Xid(window);
                if (w == IntPtr.Zero || Display == IntPtr.Zero) return;
                var rects = rect is { } r
                    ? new[] { new XRectangle { x = (short)r.X, y = (short)r.Y, width = (ushort)Math.Max(1, r.Width), height = (ushort)Math.Max(1, r.Height) } }
                    : Array.Empty<XRectangle>();
                var region = XFixesCreateRegion(display, rects, rects.Length);
                XFixesSetWindowShapeRegion(display, w, 2 /* ShapeInput */, 0, 0, region);
                XFixesDestroyRegion(display, region);
                XFlush(display);
            }
            catch (Exception e) { LogOnce("click area (libXfixes)", e); }
        }
    }
}
