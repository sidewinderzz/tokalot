using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace Tokalot.Desktop.Platform;

/**
 * A virtual keyboard made through the kernel's /dev/uinput. Keys pressed on it look like a real
 * keyboard to every app, on X11 and Wayland alike, which is how Tokalot presses Ctrl+V.
 * Needs write access to /dev/uinput (see the README: a udev rule plus the "input" group).
 * It can only press the few keys listed here; it is not a general typing device.
 */
public static class Uinput
{
    public const string DeviceName = "Tokalot virtual keyboard";

    public const ushort KEY_LEFTCTRL = 29, KEY_LEFTSHIFT = 42, KEY_Z = 44, KEY_V = 47, KEY_INSERT = 110;
    private static readonly ushort[] Keys = { KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_Z, KEY_V, KEY_INSERT };

    private const int O_WRONLY = 1, O_NONBLOCK = 0x800;
    private const ushort EV_SYN = 0, EV_KEY = 1;
    // _IOW('U', 100, int), _IOW('U', 101, int), _IOW('U', 3, struct uinput_setup /* 92 bytes */), _IO('U', 1), _IO('U', 2)
    private static readonly nuint UI_SET_EVBIT = 0x40045564, UI_SET_KEYBIT = 0x40045565, UI_DEV_SETUP = 0x405C5503, UI_DEV_CREATE = 0x5501, UI_DEV_DESTROY = 0x5502;

    [DllImport("libc", SetLastError = true)] private static extern int open([MarshalAs(UnmanagedType.LPStr)] string path, int flags);
    [DllImport("libc", SetLastError = true)] private static extern int close(int fd);
    [DllImport("libc", SetLastError = true)] private static extern int ioctl(int fd, nuint request, nint arg);
    [DllImport("libc", SetLastError = true, EntryPoint = "ioctl")] private static extern int ioctl_buf(int fd, nuint request, byte[] arg);
    [DllImport("libc", SetLastError = true)] private static extern nint write(int fd, byte[] buf, nint count);

    private static readonly object Gate = new();
    private static int fd = -1;
    private static bool tried;
    private static DateTime createdAt;

    /** Why the device couldn't be made (shown by --diagnose). */
    public static string? Error { get; private set; }

    /** True once the virtual keyboard exists. The first call tries to create it. */
    public static bool Ready
    {
        get
        {
            lock (Gate)
            {
                if (!tried) { tried = true; Create(); }
                return fd >= 0;
            }
        }
    }

    private static void Create()
    {
        if (!OperatingSystem.IsLinux()) { Error = "not Linux"; return; }
        try
        {
            int f = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
            if (f < 0) { Error = "can't open /dev/uinput (errno " + Marshal.GetLastPInvokeError() + ")"; return; }
            bool ok = ioctl(f, UI_SET_EVBIT, EV_KEY) >= 0;
            foreach (var k in Keys) ok &= ioctl(f, UI_SET_KEYBIT, k) >= 0;

            // struct uinput_setup { struct input_id { u16 bustype, vendor, product, version; } id; char name[80]; u32 ff_effects_max; }
            var setup = new byte[92];
            BitConverter.GetBytes((ushort)0x06).CopyTo(setup, 0);   // BUS_VIRTUAL
            BitConverter.GetBytes((ushort)0x544B).CopyTo(setup, 2); // "TK"
            BitConverter.GetBytes((ushort)0x4C54).CopyTo(setup, 4); // "LT"
            BitConverter.GetBytes((ushort)1).CopyTo(setup, 6);
            Encoding.ASCII.GetBytes(DeviceName).CopyTo(setup, 8);
            ok &= ioctl_buf(f, UI_DEV_SETUP, setup) >= 0;
            ok &= ioctl(f, UI_DEV_CREATE, 0) >= 0;
            if (!ok)
            {
                Error = "/dev/uinput refused the device (errno " + Marshal.GetLastPInvokeError() + ")";
                close(f);
                return;
            }
            fd = f;
            createdAt = DateTime.UtcNow;
        }
        catch (Exception e) { Error = e.Message; }
    }

    private static bool Emit(ushort type, ushort code, int value)
    {
        // struct input_event { struct timeval time; u16 type; u16 code; s32 value; }  (24 bytes on 64-bit)
        int timeSize = IntPtr.Size * 2;
        var ev = new byte[timeSize + 8];
        BitConverter.GetBytes(type).CopyTo(ev, timeSize);
        BitConverter.GetBytes(code).CopyTo(ev, timeSize + 2);
        BitConverter.GetBytes(value).CopyTo(ev, timeSize + 4);
        return write(fd, ev, ev.Length) == ev.Length;
    }

    private static bool Key(ushort code, bool down) => Emit(EV_KEY, code, down ? 1 : 0) & Emit(EV_SYN, 0, 0);

    /** Holds the modifiers, taps the key, lets go. Returns false if there is no virtual keyboard. */
    public static bool Chord(ushort[] modifiers, ushort key)
    {
        if (!Ready) return false;
        lock (Gate)
        {
            try
            {
                // A brand-new device takes a moment before the desktop starts listening to it.
                var age = (DateTime.UtcNow - createdAt).TotalMilliseconds;
                if (age < 700) Thread.Sleep((int)(700 - age));
                bool ok = true;
                foreach (var m in modifiers) { ok &= Key(m, true); Thread.Sleep(8); }
                ok &= Key(key, true); Thread.Sleep(12);
                // The releases are sent even after a failure, so nothing is left held down.
                ok &= Key(key, false); Thread.Sleep(8);
                for (int i = modifiers.Length - 1; i >= 0; i--) { ok &= Key(modifiers[i], false); Thread.Sleep(4); }
                if (!ok) Broken("writing to /dev/uinput failed (errno " + Marshal.GetLastPInvokeError() + ")");
                return ok;
            }
            catch (Exception e) { Broken(e.Message); return false; }
        }
    }

    /** The device stopped taking keys: drop it, so the next paste makes a fresh one. Call with the lock held. */
    private static void Broken(string why)
    {
        Error = why;
        App.Log("Virtual keyboard: " + why);
        try { if (fd >= 0) { ioctl(fd, UI_DEV_DESTROY, 0); close(fd); } } catch { }
        fd = -1;
        tried = false;
    }

    public static void Dispose()
    {
        lock (Gate)
        {
            if (fd < 0) return;
            try { ioctl(fd, UI_DEV_DESTROY, 0); close(fd); } catch { }
            fd = -1;
        }
    }
}
