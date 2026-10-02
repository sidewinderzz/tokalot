using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Platform;
using Avalonia.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/**
 * What the always-on-top windows (the recording indicator and the message pill) need from macOS.
 * Same members as the Linux Overlay, which the shared interface code calls.
 *
 * Clicking a window normally brings its app to the front, which would take the keyboard away from
 * the app you're dictating into. Tokalot's overlay windows are told not to become the key window and
 * not to activate Tokalot; in case macOS activates it anyway, the app you were in is given the focus
 * back as soon as the click lands (and again just before a paste).
 */
public static unsafe class Overlay
{
    private static readonly HashSet<IntPtr> Windows = new();

    private static IntPtr NSWindow(Window w)
    {
        try { return w.TryGetPlatformHandle() is IMacOSTopLevelPlatformHandle h ? h.NSWindow : IntPtr.Zero; }
        catch { return IntPtr.Zero; }
    }

    /** Clicking the window must never take the keyboard away from the app you're dictating into. Call before it is first shown. */
    public static void NeverFocus(Window window)
    {
        if (!OperatingSystem.IsMacOS()) return;
        Configure(window);
        // Avalonia may set some of this again when the window first appears.
        window.Opened += (_, _) => Configure(window);
        // Hovering comes before the click: note the app that is still in front.
        window.PointerEntered += (_, _) => AppDetect.Remember();
        window.AddHandler(InputElement.PointerPressedEvent, (_, _) => Dispatcher.UIThread.Post(ReturnFocus), RoutingStrategies.Tunnel, handledEventsToo: true);
    }

    private static void Configure(Window window)
    {
        var ns = NSWindow(window);
        if (ns == IntPtr.Zero) return;
        lock (Windows) Windows.Add(ns);
        try
        {
            using var pool = Native.AutoreleasePool();
            // The pill and indicator draw their own shadow; a window shadow would outline the clear area too.
            Native.SendB(ns, "setHasShadow:", false);
            // On every Space, stays put when Spaces change, skipped by ⌘`, and allowed over a full-screen app.
            Native.SendL(ns, "setCollectionBehavior:", (1 << 0) | (1 << 4) | (1 << 6) | (1 << 8));
            Native.SendB(ns, "setHidesOnDeactivate:", false);
            // Avalonia's own window class can be told not to take keyboard focus.
            if (Native.Responds(ns, "setCanBecomeKeyWindow:")) Native.SendB(ns, "setCanBecomeKeyWindow:", false);
            // What a "non-activating panel" uses: a click doesn't bring Tokalot to the front. Not public, so only if present.
            if (Native.Responds(ns, "_setPreventsActivation:")) Native.SendB(ns, "_setPreventsActivation:", true);
        }
        catch (Exception e) { LogOnce("window setup", e); }
    }

    /**
     * If Tokalot came to the front without one of its own windows taking the keyboard (its indicator
     * was clicked), hands the front back to the app you were in.
     */
    public static void ReturnFocus()
    {
        if (!OperatingSystem.IsMacOS()) return;
        try
        {
            using var pool = Native.AutoreleasePool();
            // NSApp itself, without creating one in a process that has none (the command-line checks).
            var nsApp = System.Runtime.InteropServices.Marshal.ReadIntPtr(Native.Address(Native.AppKit, "NSApp"));
            if (nsApp == IntPtr.Zero || !Native.SendRB(nsApp, "isActive")) return;
            var key = Native.Send(nsApp, "keyWindow");
            bool overlayOnly;
            lock (Windows) overlayOnly = key == IntPtr.Zero || Windows.Contains(key);
            // Tokalot's own window has the keyboard: you're typing into Tokalot, so it stays.
            if (!overlayOnly) return;
            if (AppDetect.LastOther is not { } other) return;
            var running = Native.Send(Native.Class("NSRunningApplication"), "runningApplicationWithProcessIdentifier:", (IntPtr)other.Pid);
            if (running == IntPtr.Zero) return;
            // NSApplicationActivateIgnoringOtherApps (still honoured from the app that is active).
            Native.SendRB(running, "activateWithOptions:", (IntPtr)2);
        }
        catch (Exception e) { LogOnce("focus hand-back", e); }
    }

    /** Only the drawn shape takes clicks (macOS lets clicks through where a window is fully clear); null lets every click through. */
    public static void SetInputRegion(Window window, PixelRect? rect)
    {
        var ns = NSWindow(window);
        if (ns == IntPtr.Zero) return;
        try { Native.SendB(ns, "setIgnoresMouseEvents:", rect == null); }
        catch (Exception e) { LogOnce("click-through", e); }
    }

    // ---------- the window in front ----------

    [DllImport(Native.CoreGraphics)] private static extern IntPtr CGWindowListCopyWindowInfo(uint option, uint relativeToWindow);
    [DllImport(Native.CoreGraphics)] [return: MarshalAs(UnmanagedType.U1)] private static extern bool CGRectMakeWithDictionaryRepresentation(IntPtr dict, out CGRect rect);
    [DllImport(Native.CoreGraphics)] private static extern int CGGetActiveDisplayList(uint max, uint* displays, out uint count);
    [DllImport(Native.CoreGraphics)] private static extern CGRect CGDisplayBounds(uint display);
    [DllImport(Native.CoreGraphics)] private static extern IntPtr CGEventCreate(IntPtr source);
    [DllImport(Native.CoreGraphics)] private static extern CGPoint CGEventGetLocation(IntPtr ev);

    [StructLayout(LayoutKind.Sequential)] private struct CGPoint { public double X, Y; }
    [StructLayout(LayoutKind.Sequential)] private struct CGRect { public double X, Y, W, H; }

    /**
     * Whether the frontmost app's front window covers a whole display (full screen), and no center
     * (the indicator follows the screen with the mouse pointer instead). Window positions are known
     * without any permission; only their titles would need one, and they aren't read.
     */
    public static (bool FullScreen, PixelPoint? Center) ActiveWindowState()
    {
        if (!OperatingSystem.IsMacOS()) return (false, null);
        var front = AppDetect.Remember();
        if (front is not { } f || f.Pid == Environment.ProcessId) return (false, null);
        IntPtr list = IntPtr.Zero;
        try
        {
            list = CGWindowListCopyWindowInfo(1 | 16, 0); // on screen only, no desktop icons
            if (list == IntPtr.Zero) return (false, null);
            var kPid = Native.Constant(Native.CoreGraphics, "kCGWindowOwnerPID");
            var kLayer = Native.Constant(Native.CoreGraphics, "kCGWindowLayer");
            var kBounds = Native.Constant(Native.CoreGraphics, "kCGWindowBounds");
            var n = Native.CFArrayGetCount(list);
            for (nint i = 0; i < n; i++)
            {
                var w = Native.CFArrayGetValueAtIndex(list, i);
                if (!Number(Native.CFDictionaryGetValue(w, kPid), out var pid) || pid != f.Pid) continue;
                if (!Number(Native.CFDictionaryGetValue(w, kLayer), out var layer) || layer != 0) continue;
                if (!CGRectMakeWithDictionaryRepresentation(Native.CFDictionaryGetValue(w, kBounds), out var r)) continue;
                // The app's frontmost ordinary window: is it exactly one display's size?
                foreach (var d in Displays())
                    if (Math.Abs(d.X - r.X) < 1 && Math.Abs(d.Y - r.Y) < 1 && Math.Abs(d.W - r.W) < 1 && Math.Abs(d.H - r.H) < 1)
                        return (true, null);
                return (false, null);
            }
            return (false, null);
        }
        catch (Exception e) { LogOnce("window list", e); return (false, null); }
        finally { if (list != IntPtr.Zero) Native.CFRelease(list); }
    }

    private static bool Number(IntPtr cfNumber, out long value)
    {
        value = 0;
        return cfNumber != IntPtr.Zero && Native.CFNumberGetValue(cfNumber, 4 /* kCFNumberSInt64Type */, out value);
    }

    private static List<CGRect> Displays()
    {
        var ids = new uint[16];
        var outp = new List<CGRect>();
        fixed (uint* p = ids)
            if (CGGetActiveDisplayList(16, p, out var count) == 0)
                for (int i = 0; i < count; i++) outp.Add(CGDisplayBounds(ids[i]));
        return outp;
    }

    /**
     * Where the mouse pointer is, in the screen pixels Avalonia uses: macOS gives points from the top
     * left of the main display, which are turned into pixels with the scale of the screen they're on.
     */
    public static PixelPoint? Pointer()
    {
        if (!OperatingSystem.IsMacOS()) return null;
        try
        {
            var ev = CGEventCreate(IntPtr.Zero);
            if (ev == IntPtr.Zero) return null;
            var p = CGEventGetLocation(ev);
            Native.CFRelease(ev);
            var screens = App.Current.AnyWindow?.Screens;
            if (screens != null)
                foreach (var s in screens.All)
                {
                    var k = s.Scaling;
                    var b = s.Bounds;
                    if (p.X >= b.X / k && p.X < b.Right / k && p.Y >= b.Y / k && p.Y < b.Bottom / k)
                        return new PixelPoint((int)(p.X * k), (int)(p.Y * k));
                }
            return new PixelPoint((int)p.X, (int)p.Y);
        }
        catch (Exception e) { LogOnce("pointer", e); return null; }
    }

    private static readonly HashSet<string> Logged = new();

    private static void LogOnce(string what, Exception e)
    {
        lock (Logged) if (!Logged.Add(what)) return;
        App.Log("Overlay " + what + " unavailable: " + e.GetType().Name + ": " + e.Message);
    }
}
