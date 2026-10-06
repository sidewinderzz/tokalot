using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Tokalot.Desktop.Platform;

/**
 * Watches the keyboard system-wide for Ctrl+Win being held (a "low-level keyboard hook").
 * It only looks at Ctrl, Win and, while the combo is held, the next key; it never logs keys.
 * Also stops Windows from opening the Start menu when Win is released after the combo.
 */
public sealed class HotkeyHook : IDisposable
{
    public event Action? Pressed;          // Ctrl+Win both went down
    public event Action? Released;         // one of them came back up
    public event Action<int>? KeyWhileHeld; // another key while the combo (or recording) is active
    public event Action? Escape;            // Esc pressed (used to cancel recording)

    private const int WH_KEYBOARD_LL = 13;
    private const int WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, WM_SYSKEYDOWN = 0x0104, WM_SYSKEYUP = 0x0105;
    private const int VK_LWIN = 0x5B, VK_RWIN = 0x5C, VK_LCONTROL = 0xA2, VK_RCONTROL = 0xA3, VK_CONTROL = 0x11, VK_ESCAPE = 0x1B;
    private const uint LLKHF_INJECTED = 0x10;
    private static readonly IntPtr OurMarker = new(0x544B4C54); // "TKLT": tags keys we send ourselves

    [StructLayout(LayoutKind.Sequential)]
    private struct KBDLLHOOKSTRUCT { public uint vkCode, scanCode, flags, time; public IntPtr dwExtraInfo; }

    private delegate IntPtr LowLevelKeyboardProc(int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelKeyboardProc fn, IntPtr hMod, uint threadId);
    [DllImport("user32.dll")] private static extern bool UnhookWindowsHookEx(IntPtr hhk);
    [DllImport("user32.dll")] private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr GetModuleHandle(string? name);

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam, lParam; public uint time; public int x, y; }
    [DllImport("user32.dll")] private static extern int GetMessage(out MSG msg, IntPtr hwnd, uint min, uint max);
    [DllImport("user32.dll")] private static extern bool PostThreadMessage(uint threadId, uint msg, IntPtr w, IntPtr l);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();

    private readonly LowLevelKeyboardProc proc; // kept as a field so the GC can't collect it
    private IntPtr hook;
    private uint threadId;
    private volatile bool ctrl, win, active, comboUsed;

    /** When true, Esc and other keys are reported even after the combo is released (hands-free). */
    public volatile bool Listening;

    /**
     * A key that, pressed with the combo before this time, belongs to Tokalot (Ctrl+Win+Z = put my wording back).
     * It is reported but not passed on, so the app in front doesn't also act on it (many treat it as Ctrl+Z).
     */
    public volatile int OwnKey;
    public DateTime OwnKeyUntil;

    /**
     * The hook runs on its own thread with its own message loop. Windows silently removes a
     * low-level hook that answers too slowly, so it must never wait on the (sometimes busy) UI thread.
     */
    public HotkeyHook()
    {
        proc = Callback;
        WarmUp();
        using var ready = new System.Threading.ManualResetEventSlim();
        var t = new System.Threading.Thread(() =>
        {
            threadId = GetCurrentThreadId();
            Install();
            ready.Set();
            while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                if (msg.message != WM_REHOOK) continue;
                // Mid-shortcut (even just Win still down after Ctrl+Win) the swap would lose track of the keys
                // and let the Start menu open. Skip it; the next refresh is under a minute away.
                if (active || comboUsed || Held(VK_CONTROL) || Held(VK_LWIN) || Held(VK_RWIN)) continue;
                // Take the hook out and put it back. If Windows had dropped it, this is what brings it back.
                if (hook != IntPtr.Zero) UnhookWindowsHookEx(hook);
                ctrl = win = active = comboUsed = false;
                Install();
            }
            if (hook != IntPtr.Zero) UnhookWindowsHookEx(hook);
            hook = IntPtr.Zero;
        }) { IsBackground = true, Name = "Tokalot hotkey", Priority = System.Threading.ThreadPriority.Highest };
        t.Start();
        ready.Wait(3000);
    }

    private const uint WM_REHOOK = 0x8001; // WM_APP + 1

    private void Install()
    {
        using var cur = Process.GetCurrentProcess();
        hook = SetWindowsHookEx(WH_KEYBOARD_LL, proc, GetModuleHandle(cur.MainModule?.ModuleName), 0);
    }

    /**
     * Windows stops calling a low-level hook that answers too slowly, without telling the app, and never
     * says whether a hook is still in place. Right after sign-in the PC is busy, and the first key press
     * also had to compile this code, which is the likeliest moment to be too slow. So the code is compiled
     * up front here, and Refresh() re-installs the hook now and then in case it was dropped anyway.
     */
    private void WarmUp()
    {
        try
        {
            const System.Reflection.BindingFlags f = System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.Static;
            foreach (var name in new[] { nameof(Callback), nameof(SendMaskedWinUp), nameof(Held) })
                if (typeof(HotkeyHook).GetMethod(name, f) is { } m)
                    System.Runtime.CompilerServices.RuntimeHelpers.PrepareMethod(m.MethodHandle);
            _ = ComboHeld; // loads the key-state call
            var size = Marshal.SizeOf<KBDLLHOOKSTRUCT>();
            var p = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.Copy(new byte[size], 0, p, size);
                _ = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(p);
            }
            finally { Marshal.FreeHGlobal(p); }
        }
        catch { }
    }

    /** Re-installs the hook. Call only while nothing is being recorded and the shortcut isn't held. */
    public void Refresh()
    {
        if (threadId != 0 && !ComboHeld) PostThreadMessage(threadId, WM_REHOOK, IntPtr.Zero, IntPtr.Zero);
    }

    public bool Installed => hook != IntPtr.Zero;

    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int vk);
    private static bool Held(int vk) => (GetAsyncKeyState(vk) & 0x8000) != 0;

    /** True while Ctrl and Win are physically held, whatever the hook last saw. */
    public static bool ComboHeld => Held(VK_CONTROL) && (Held(VK_LWIN) || Held(VK_RWIN));

    private IntPtr Callback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode < 0) return CallNextHookEx(hook, nCode, wParam, lParam);
        var k = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(lParam);
        if ((k.flags & LLKHF_INJECTED) != 0 && k.dwExtraInfo == OurMarker)
            return CallNextHookEx(hook, nCode, wParam, lParam);

        int msg = wParam.ToInt32();
        bool down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
        bool up = msg == WM_KEYUP || msg == WM_SYSKEYUP;
        int vk = (int)k.vkCode;
        bool isCtrl = vk is VK_LCONTROL or VK_RCONTROL or VK_CONTROL;
        bool isWin = vk is VK_LWIN or VK_RWIN;

        // The hook sees nothing on the lock screen, a UAC prompt or an elevated window, so a key-up
        // can be missed there. Re-read the key this event isn't about, so a stale "held" never sticks.
        if (!isCtrl) ctrl = Held(VK_CONTROL);
        if (!isWin) win = Held(VK_LWIN) || Held(VK_RWIN);

        if (active && OwnKey != 0 && vk == OwnKey && DateTime.UtcNow < OwnKeyUntil)
        {
            if (down) KeyWhileHeld?.Invoke(vk);
            return (IntPtr)1;
        }

        if (isCtrl) { if (down) ctrl = true; else if (up) ctrl = false; }
        else if (isWin) { if (down) win = true; else if (up) win = false; }
        else if (down)
        {
            if (vk == VK_ESCAPE && (active || Listening)) Escape?.Invoke();
            else if (active || Listening) KeyWhileHeld?.Invoke(vk);
        }

        if (!active && ctrl && win)
        {
            active = true;
            comboUsed = true;
            Pressed?.Invoke();
        }
        else if (active && (!ctrl || !win))
        {
            active = false;
            Released?.Invoke();
        }

        // Releasing Win would normally pop the Start menu. Swallow this Win-up and resend it
        // behind an unused "mask" key, which tells Windows a shortcut was used instead.
        if (isWin && up && comboUsed)
        {
            comboUsed = false;
            SendMaskedWinUp(vk);
            return (IntPtr)1;
        }
        return CallNextHookEx(hook, nCode, wParam, lParam);
    }

    // ---------- SendInput ----------

    [StructLayout(LayoutKind.Sequential)]
    internal struct INPUT { public int type; public InputUnion u; }
    [StructLayout(LayoutKind.Explicit)]
    internal struct InputUnion { [FieldOffset(0)] public KEYBDINPUT ki; [FieldOffset(0)] public MOUSEINPUT mi; }
    [StructLayout(LayoutKind.Sequential)]
    internal struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    internal struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [DllImport("user32.dll", SetLastError = true)] internal static extern uint SendInput(uint n, INPUT[] inputs, int size);
    internal const uint KEYEVENTF_KEYUP = 0x0002;

    internal static INPUT Key(ushort vk, bool up) => new()
    {
        type = 1,
        u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = up ? KEYEVENTF_KEYUP : 0, dwExtraInfo = OurMarker } },
    };

    private static void SendMaskedWinUp(int winVk)
    {
        const ushort mask = 0xE8; // unassigned virtual key
        var inputs = new[] { Key(mask, false), Key(mask, true), Key((ushort)winVk, true) };
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
    }

    public void Dispose()
    {
        if (threadId != 0) PostThreadMessage(threadId, 0x0012 /* WM_QUIT */, IntPtr.Zero, IntPtr.Zero);
    }
}
