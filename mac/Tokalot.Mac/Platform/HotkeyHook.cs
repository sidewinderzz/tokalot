using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace Tokalot.Desktop.Platform;

/**
 * Watches the keyboard system-wide for Ctrl+Cmd being held, through a listen-only event tap
 * (Quartz's CGEventTap). macOS only allows that once the user has switched Tokalot on under
 * System Settings › Privacy & Security › Input Monitoring; until then the tap can't be made, and
 * it is tried again every few seconds so granting it takes effect without a restart where macOS
 * allows that. It looks at the modifier keys and, while the combo is held or a recording is under
 * way, at which other key was pressed; it never logs keys. Listen-only means keys still reach your
 * apps untouched.
 *
 * Why Ctrl+Cmd: holding the two together does nothing on its own anywhere in macOS. Fn/Globe
 * already opens the emoji picker or Apple's dictation and isn't on most PC keyboards, and
 * Ctrl+Option is VoiceOver's own key pair. The few system shortcuts that start with Ctrl+Cmd (Q to
 * lock the screen, F for full screen, Space for emoji, D to look up a word) still work: another
 * key pressed soon after the combo cancels the dictation quietly, as on Windows and Linux.
 */
public sealed unsafe class HotkeyHook : IDisposable
{
    public event Action? Pressed;          // Ctrl+Cmd both went down
    public event Action? Released;         // one of them came back up
    public event Action<int>? KeyWhileHeld; // another key while the combo (or recording) is active
    public event Action? Escape;            // Esc pressed (used to cancel recording)

    /** The key code macOS reports for Z (the shortcut plus Z puts your own wording back). */
    public const int KeyZ = 6;
    private const int KeyEscape = 53;

    private const uint KeyDown = 10, KeyUp = 11, FlagsChanged = 12;
    private const uint TapDisabledByTimeout = 0xFFFFFFFE, TapDisabledByUserInput = 0xFFFFFFFF;
    private const ulong FlagShift = 1UL << 17, FlagControl = 1UL << 18, FlagOption = 1UL << 19, FlagCommand = 1UL << 20;
    private const int FieldKeycode = 9, FieldAutorepeat = 8;

    [DllImport(Native.CoreGraphics)]
    private static extern IntPtr CGEventTapCreate(uint tap, uint place, uint options, ulong eventsOfInterest,
        delegate* unmanaged<IntPtr, uint, IntPtr, IntPtr, IntPtr> callback, IntPtr userInfo);
    [DllImport(Native.CoreGraphics)] private static extern void CGEventTapEnable(IntPtr tap, [MarshalAs(UnmanagedType.U1)] bool enable);
    [DllImport(Native.CoreGraphics)] private static extern ulong CGEventGetFlags(IntPtr ev);
    [DllImport(Native.CoreGraphics)] private static extern long CGEventGetIntegerValueField(IntPtr ev, int field);
    [DllImport(Native.CoreGraphics)] private static extern ulong CGEventSourceFlagsState(int stateId);

    private readonly object gate = new();
    private readonly Thread thread;
    private GCHandle self;
    private IntPtr tap, runLoop;
    private volatile bool disposed;
    private bool ctrl, cmd, active;
    private static volatile bool modifiersDown, comboHeld;

    /** When true, Esc and other keys are reported even after the combo is released (hands-free). */
    public volatile bool Listening;

    /** True while Ctrl, Cmd, Option or Shift is held (so a paste isn't read as another shortcut). Asks macOS directly. */
    public static bool ModifiersDown
    {
        get
        {
            try { return (CGEventSourceFlagsState(0) & (FlagShift | FlagControl | FlagOption | FlagCommand)) != 0; }
            catch { return modifiersDown; }
        }
    }

    /** True while Ctrl and Cmd are held, as last seen. */
    public static bool ComboHeld => comboHeld;

    /** The tap couldn't be made because Input Monitoring isn't allowed for Tokalot yet. */
    public bool PermissionDenied { get; private set; }

    /** The keyboard is being watched. */
    public bool Installed => tap != IntPtr.Zero;

    private readonly ManualResetEventSlim firstTry = new();

    public HotkeyHook()
    {
        self = GCHandle.Alloc(this);
        thread = new Thread(Run) { IsBackground = true, Name = "Tokalot hotkey", Priority = ThreadPriority.AboveNormal };
        thread.Start();
        // Callers (Settings, --diagnose) ask Installed right after; give the first attempt a moment.
        firstTry.Wait(1500);
    }

    /** Makes the tap on its own thread (it delivers on that thread's run loop), retrying until it is allowed. */
    private void Run()
    {
        try
        {
            while (!disposed)
            {
                if (TryCreate())
                {
                    firstTry.Set();
                    App.Log("Keyboard tap installed");
                    runLoop = Native.CFRunLoopGetCurrent();
                    var source = Native.CFMachPortCreateRunLoopSource(IntPtr.Zero, tap, 0);
                    Native.CFRunLoopAddSource(runLoop, source, Native.Constant(Native.CoreFoundation, "kCFRunLoopCommonModes"));
                    CGEventTapEnable(tap, true);
                    Native.CFRunLoopRun(); // returns when stopped (Dispose)
                    return;
                }
                PermissionDenied = !Permissions.InputMonitoring;
                firstTry.Set();
                Thread.Sleep(3000);
            }
        }
        catch (Exception e) { App.Log("Keyboard tap failed: " + e.Message); firstTry.Set(); }
    }

    private bool TryCreate()
    {
        if (!OperatingSystem.IsMacOS()) return false;
        ulong mask = (1UL << (int)KeyDown) | (1UL << (int)KeyUp) | (1UL << (int)FlagsChanged);
        // Session tap, head of the queue, listen-only.
        tap = CGEventTapCreate(1, 0, 1, mask, &OnEvent, GCHandle.ToIntPtr(self));
        if (tap != IntPtr.Zero) PermissionDenied = false;
        return tap != IntPtr.Zero;
    }

    [UnmanagedCallersOnly]
    private static IntPtr OnEvent(IntPtr proxy, uint type, IntPtr ev, IntPtr user)
    {
        try
        {
            if (GCHandle.FromIntPtr(user).Target is HotkeyHook h) h.Handle(type, ev);
        }
        catch { }
        return ev;
    }

    private void Handle(uint type, IntPtr ev)
    {
        if (disposed) return;
        // macOS switches a tap off if it ever answers too slowly, or while secure input is on; switch it back on.
        if (type is TapDisabledByTimeout or TapDisabledByUserInput)
        {
            if (tap != IntPtr.Zero) CGEventTapEnable(tap, true);
            return;
        }
        lock (gate)
        {
            if (type == FlagsChanged)
            {
                SetFlags(CGEventGetFlags(ev));
            }
            else if (type == KeyDown)
            {
                if (CGEventGetIntegerValueField(ev, FieldAutorepeat) != 0) return;
                int code = (int)CGEventGetIntegerValueField(ev, FieldKeycode);
                if (active || Listening)
                {
                    if (code == KeyEscape) Escape?.Invoke();
                    else KeyWhileHeld?.Invoke(code);
                }
            }
        }
    }

    /** Works out Ctrl/Cmd and raises Pressed/Released when the combo starts or ends. Call with the lock held. */
    private void SetFlags(ulong flags)
    {
        ctrl = (flags & FlagControl) != 0;
        cmd = (flags & FlagCommand) != 0;
        modifiersDown = (flags & (FlagShift | FlagControl | FlagOption | FlagCommand)) != 0;
        comboHeld = ctrl && cmd;
        if (!active && ctrl && cmd)
        {
            active = true;
            Pressed?.Invoke();
        }
        else if (active && (!ctrl || !cmd))
        {
            active = false;
            Released?.Invoke();
        }
    }

    /** Asks macOS which modifier keys are down right now, in case a key-up was missed. */
    public void Poll()
    {
        if (disposed || !OperatingSystem.IsMacOS() || tap == IntPtr.Zero) return;
        try
        {
            var flags = CGEventSourceFlagsState(0);
            lock (gate) SetFlags(flags);
        }
        catch { }
    }

    public void Dispose()
    {
        disposed = true;
        modifiersDown = false;
        comboHeld = false;
        try
        {
            if (tap != IntPtr.Zero) CGEventTapEnable(tap, false);
            if (runLoop != IntPtr.Zero) Native.CFRunLoopStop(runLoop);
        }
        catch { }
    }
}
