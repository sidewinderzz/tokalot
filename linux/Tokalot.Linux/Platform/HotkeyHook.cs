using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;

namespace Tokalot.Desktop.Platform;

/**
 * Watches the keyboard system-wide for Ctrl+Super being held. Linux has no one hook that works on
 * both X11 and Wayland, so this reads the kernel's keyboard devices (/dev/input/event*) directly,
 * which needs the user to be in the "input" group. It only looks at Ctrl, Super and, while the
 * combo is held, the next key; it never logs keys. Reading is passive: keys still reach your apps.
 */
public sealed class HotkeyHook : IDisposable
{
    public event Action? Pressed;          // Ctrl+Super both went down
    public event Action? Released;         // one of them came back up
    public event Action<int>? KeyWhileHeld; // another key while the combo (or recording) is active
    public event Action? Escape;            // Esc pressed (used to cancel recording)

    /** The code this reader reports for the Z key (the shortcut plus Z puts your own wording back). */
    public const int KeyZ = 44;

    private const ushort EV_KEY = 1;
    private const int KEY_ESC = 1, KEY_LEFTCTRL = 29, KEY_RIGHTCTRL = 97, KEY_LEFTMETA = 125, KEY_RIGHTMETA = 126,
        KEY_LEFTSHIFT = 42, KEY_RIGHTSHIFT = 54, KEY_LEFTALT = 56, KEY_RIGHTALT = 100;

    private sealed class Device
    {
        public string Path = "";
        public FileStream Stream = null!;
        public readonly HashSet<int> Down = new(); // which of Ctrl/Super/Shift/Alt this keyboard is holding
    }

    private readonly object gate = new();
    private readonly Dictionary<string, Device> devices = new();
    private readonly Thread scanner;
    private volatile bool disposed;
    private bool ctrl, win, active, suppressed;
    private int press;           // counts combo presses, so a lock-screen check that comes back late knows which one it was for
    private bool deciding;       // the lock-screen check for this press is still out
    private bool releasedEarly;  // the combo was let go before that check came back
    private static volatile bool modifiersDown, comboHeld;

    /** When true, Esc and other keys are reported even after the combo is released (hands-free). */
    public volatile bool Listening;

    /** True while Ctrl, Super, Alt or Shift is held on any keyboard (so a paste isn't read as another shortcut). */
    public static bool ModifiersDown => modifiersDown;

    /** True while Ctrl and Super are held, as last seen on the keyboards being read. */
    public static bool ComboHeld => comboHeld;

    /** Keyboards exist but none could be opened: the user isn't in the "input" group yet. */
    public bool PermissionDenied { get; private set; }

    /**
     * Each keyboard is read on its own background thread, and a scanner looks for keyboards that
     * were plugged in (or unplugged) every few seconds.
     */
    public HotkeyHook()
    {
        Scan();
        scanner = new Thread(() =>
        {
            while (!disposed)
            {
                Thread.Sleep(3000);
                if (disposed) break;
                // /dev/input only changes when a keyboard comes or goes; until then there's nothing to look for.
                var stamp = InputStamp();
                if (stamp != scannedStamp) Scan();
                // A missed key-up only matters while a key is believed held.
                if (modifiersDown) Poll();
            }
        }) { IsBackground = true, Name = "Tokalot hotkey scan" };
        scanner.Start();
    }

    /** At least one keyboard is being read. */
    public bool Installed { get { lock (gate) return devices.Count > 0; } }

    private DateTime scannedStamp;

    private static DateTime InputStamp()
    {
        try { return Directory.GetLastWriteTimeUtc("/dev/input"); } catch { return DateTime.MinValue; }
    }

    private void Scan()
    {
        try
        {
            scannedStamp = InputStamp();
            if (!Directory.Exists("/dev/input")) return;
            bool denied = false;
            foreach (var path in Directory.GetFiles("/dev/input", "event*"))
            {
                lock (gate) { if (devices.ContainsKey(path)) continue; }
                if (!IsKeyboard(Path.GetFileName(path))) continue;
                try
                {
                    var dev = new Device
                    {
                        Path = path,
                        Stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1, FileOptions.None),
                    };
                    lock (gate) devices[path] = dev;
                    new Thread(() => Read(dev)) { IsBackground = true, Name = "Tokalot hotkey", Priority = ThreadPriority.AboveNormal }.Start();
                }
                catch (UnauthorizedAccessException) { denied = true; }
                catch { }
            }
            PermissionDenied = denied && !Installed;
        }
        catch { }
    }

    // EVIOCGKEY(96): _IOC(_IOC_READ, 'E', 0x18, 96) - a bitmap of the keys that are down right now.
    private const int KeyBytes = 96;
    private static readonly nuint EVIOCGKEY = 0x80604518;
    [DllImport("libc", SetLastError = true)] private static extern int ioctl(int fd, nuint request, byte[] arg);
    private static readonly int[] Modifiers = { KEY_LEFTCTRL, KEY_RIGHTCTRL, KEY_LEFTMETA, KEY_RIGHTMETA, KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTALT, KEY_RIGHTALT };

    /**
     * Asks every keyboard which modifier keys are down right now, instead of trusting the events
     * seen so far. A key-up can be missed (the keyboard was grabbed by another program, events were
     * dropped), and without this a "held" key would stay held for good.
     */
    public void Poll()
    {
        if (disposed || !OperatingSystem.IsLinux()) return;
        lock (gate)
        {
            bool changed = false;
            foreach (var dev in devices.Values)
            {
                try
                {
                    var bits = new byte[KeyBytes];
                    if (ioctl((int)dev.Stream.SafeFileHandle.DangerousGetHandle(), EVIOCGKEY, bits) < 0) continue;
                    foreach (var k in Modifiers)
                    {
                        bool down = (bits[k / 8] >> (k % 8) & 1) != 0;
                        if (down ? dev.Down.Add(k) : dev.Down.Remove(k)) changed = true;
                    }
                }
                catch { }
            }
            if (changed) Update();
        }
    }

    /** A device counts as a keyboard if it has a Ctrl, Super or Esc key. Tokalot's own virtual keyboard is skipped. */
    private static bool IsKeyboard(string ev)
    {
        try
        {
            var sys = "/sys/class/input/" + ev + "/device/";
            if (File.Exists(sys + "name") && File.ReadAllText(sys + "name").Trim() == Uinput.DeviceName) return false;
            if (!File.Exists(sys + "capabilities/key")) return true;
            // Hex words, most significant first; each holds as many bits as a kernel long.
            var words = File.ReadAllText(sys + "capabilities/key").Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
            int bits = IntPtr.Size * 8;
            bool Has(int key)
            {
                int w = words.Length - 1 - key / bits;
                return w >= 0 && (Convert.ToUInt64(words[w], 16) >> (key % bits) & 1) != 0;
            }
            return Has(KEY_LEFTCTRL) || Has(KEY_RIGHTCTRL) || Has(KEY_LEFTMETA) || Has(KEY_RIGHTMETA) || Has(KEY_ESC);
        }
        catch { return true; }
    }

    private void Read(Device dev)
    {
        // struct input_event { struct timeval time; u16 type; u16 code; s32 value; }  (24 bytes on 64-bit)
        int timeSize = IntPtr.Size * 2, size = timeSize + 8;
        var buf = new byte[size * 32];
        try
        {
            int have = 0;
            while (!disposed)
            {
                int n = dev.Stream.Read(buf, have, buf.Length - have);
                if (n <= 0) break;
                have += n;
                int o = 0;
                for (; o + size <= have; o += size)
                {
                    if (BitConverter.ToUInt16(buf, o + timeSize) != EV_KEY) continue;
                    OnKey(dev, BitConverter.ToUInt16(buf, o + timeSize + 2), BitConverter.ToInt32(buf, o + timeSize + 4));
                }
                if (o < have) Array.Copy(buf, o, buf, 0, have - o);
                have -= o;
            }
        }
        catch { } // unplugged
        try { dev.Stream.Dispose(); } catch { }
        lock (gate)
        {
            devices.Remove(dev.Path);
            // Keys held on a keyboard that just vanished are no longer held.
            dev.Down.Clear();
            if (!disposed) Update();
        }
    }

    /** value: 1 = down, 0 = up, 2 = auto-repeat. */
    private void OnKey(Device dev, int code, int value)
    {
        if (disposed) return;
        bool down = value == 1, up = value == 0;
        lock (gate)
        {
            bool isCtrl = code is KEY_LEFTCTRL or KEY_RIGHTCTRL;
            bool isWin = code is KEY_LEFTMETA or KEY_RIGHTMETA;
            bool isOtherMod = code is KEY_LEFTSHIFT or KEY_RIGHTSHIFT or KEY_LEFTALT or KEY_RIGHTALT;

            if (isCtrl || isWin || isOtherMod)
            {
                if (down) dev.Down.Add(code); else if (up) dev.Down.Remove(code);
            }
            if (!isCtrl && !isWin && down && !suppressed)
            {
                if (code == KEY_ESC && (active || Listening)) Escape?.Invoke();
                else if (active || Listening) KeyWhileHeld?.Invoke(code);
            }
            Update();
        }
    }

    /** Works out Ctrl/Super across all keyboards and raises Pressed/Released when the combo starts or ends. Call with the lock held. */
    private void Update()
    {
        var held = devices.Values.SelectMany(d => d.Down).ToHashSet();
        ctrl = held.Contains(KEY_LEFTCTRL) || held.Contains(KEY_RIGHTCTRL);
        win = held.Contains(KEY_LEFTMETA) || held.Contains(KEY_RIGHTMETA);
        modifiersDown = held.Count > 0;
        comboHeld = ctrl && win;

        if (!active && ctrl && win)
        {
            active = true;
            // The devices keep delivering keys on the lock screen and while another user is switched in:
            // the shortcut does nothing there. Asking can mean running loginctl, so it is asked on another
            // thread: waiting here, with the lock held, would hold up every keyboard's events meanwhile.
            deciding = true;
            releasedEarly = false;
            var mine = ++press;
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var usable = Session.Usable;
                lock (gate)
                {
                    if (mine != press || disposed) return;
                    deciding = false;
                    // Still held: the release decides what to report. Already let go: this press is over either way.
                    suppressed = !usable && !releasedEarly;
                    if (!usable) return;
                    Pressed?.Invoke();
                    // A quick tap can be over before the answer came.
                    if (releasedEarly) Released?.Invoke();
                }
            });
        }
        else if (active && (!ctrl || !win))
        {
            active = false;
            if (deciding) { releasedEarly = true; return; }
            if (!suppressed) Released?.Invoke();
            suppressed = false;
        }
    }

    public void Dispose()
    {
        disposed = true;
        modifiersDown = false;
        comboHeld = false;
        // The reader threads are background threads blocked in read(); they end with the process.
    }
}
