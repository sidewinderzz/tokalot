using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using Microsoft.Win32;
using NAudio.Wave;

namespace Tokalot.Desktop.Platform;

/** Puts text into whatever app has focus: clipboard + Ctrl+V, then puts your old clipboard back. */
public static class TextInjector
{
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int vk);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr pid);
    [DllImport("user32.dll")] private static extern bool GetGUIThreadInfo(uint thread, ref GuiThreadInfo info);

    [StructLayout(LayoutKind.Sequential)]
    private struct GuiThreadInfo
    {
        public int cbSize, flags;
        public IntPtr hwndActive, hwndFocus, hwndCapture, hwndMenuOwner, hwndMoveSize, hwndCaret;
        public int left, top, right, bottom;
    }
    private static bool Down(int vk) => (GetAsyncKeyState(vk) & 0x8000) != 0;

    private static Task restoring = Task.CompletedTask; // the previous paste putting the old clipboard back

    /**
     * Pastes [text] where the cursor is. True when a text box had the focus. False when none seemed to: the
     * paste is still sent (a terminal or a spreadsheet cell may take it), but the text is then left on the
     * clipboard instead of the old contents being put back, so it isn't lost.
     */
    public static async Task<bool> Paste(string text)
    {
        // Windows apps expect CRLF line breaks (lists and paragraphs from the cleanup step).
        text = text.Replace("\r\n", "\n").Replace("\n", "\r\n");
        // Let the last paste finish with the clipboard first, or its text would be saved as "what was there before".
        try { await restoring; } catch { }
        // Wait (briefly) until Ctrl/Win/Alt/Shift are up, so the paste isn't read as another shortcut.
        for (int i = 0; i < 30 && (Down(0x11) || Down(0x5B) || Down(0x5C) || Down(0x12) || Down(0x10)); i++)
            await Task.Delay(50);

        var box = await FocusIsTextBox();
        var previous = box ? Snapshot() : null;

        // No text box: the dictation stays on the clipboard as an ordinary copy (in Win+V history too).
        if (!await SetClipboard(text, transient: box)) return true; // couldn't use the clipboard at all; nothing more to say
        var inputs = new[]
        {
            HotkeyHook.Key(0x11, false), HotkeyHook.Key(0x56, false), // Ctrl down, V down
            HotkeyHook.Key(0x56, true), HotkeyHook.Key(0x11, true),   // V up, Ctrl up
        };
        HotkeyHook.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<HotkeyHook.INPUT>());

        if (!box) return false;
        // A slow app, a remote desktop or a virtual machine can take over a second to read the clipboard, and
        // would paste the old contents if they were put back sooner. The wait happens in the background.
        await Task.Delay(150);
        restoring = Restore(previous, text);
        return true;
    }

    /**
     * Whether the keyboard focus is in a text box: a blinking caret in a classic app, or (through UI Automation,
     * the way screen readers ask) a focused edit field or document, or anything that takes text. Nothing focused,
     * the desktop, a button or a list is not. An app that doesn't answer within a moment counts as a text box,
     * so a slow app gets the usual paste.
     */
    private static async Task<bool> FocusIsTextBox()
    {
        var look = Task.Run(() =>
        {
            try
            {
                var info = new GuiThreadInfo { cbSize = Marshal.SizeOf<GuiThreadInfo>() };
                var thread = GetWindowThreadProcessId(GetForegroundWindow(), IntPtr.Zero);
                if (thread != 0 && GetGUIThreadInfo(thread, ref info) && info.hwndCaret != IntPtr.Zero) return true;
                var el = AutomationElement.FocusedElement;
                if (el == null) return false;
                var type = el.Current.ControlType;
                if (type == ControlType.Edit || type == ControlType.Document) return true;
                if (el.TryGetCurrentPattern(ValuePattern.Pattern, out var vp) && !((ValuePattern)vp).Current.IsReadOnly) return true;
                return el.TryGetCurrentPattern(TextPattern.Pattern, out _);
            }
            catch { return true; } // couldn't tell: paste as usual
        });
        return await Task.WhenAny(look, Task.Delay(800)) != look || look.Result;
    }

    private static async Task Restore(DataObject? previous, string text)
    {
        await Task.Delay(1300);
        // Only put the old clipboard back if nothing else replaced ours in the meantime.
        try { if (Clipboard.ContainsText() && Clipboard.GetText() != text) return; } catch { }
        for (int i = 0; i < 8; i++)
        {
            try
            {
                if (previous != null) Clipboard.SetDataObject(previous, true);
                else Clipboard.Clear(); // it was empty before, so leave it empty
                return;
            }
            catch { await Task.Delay(40); }
        }
    }

    /** Takes back what was just pasted (Ctrl+Z in the focused app) and pastes this instead. */
    public static async Task Replace(string text)
    {
        for (int i = 0; i < 30 && (Down(0x11) || Down(0x5B) || Down(0x5C) || Down(0x12) || Down(0x10)); i++)
            await Task.Delay(50);
        var undo = new[]
        {
            HotkeyHook.Key(0x11, false), HotkeyHook.Key(0x5A, false), // Ctrl down, Z down
            HotkeyHook.Key(0x5A, true), HotkeyHook.Key(0x11, true),   // Z up, Ctrl up
        };
        HotkeyHook.SendInput((uint)undo.Length, undo, Marshal.SizeOf<HotkeyHook.INPUT>());
        await Task.Delay(150);
        await Paste(text);
    }

    /** Copies every format currently on the clipboard (text, images, files…) so it can be restored. */
    private static DataObject? Snapshot()
    {
        try
        {
            var cur = Clipboard.GetDataObject();
            if (cur == null) return null;
            var copy = new DataObject();
            var any = false;
            foreach (var f in cur.GetFormats(false))
            {
                try
                {
                    var d = cur.GetData(f, false);
                    if (d != null) { copy.SetData(f, d); any = true; }
                }
                catch { }
            }
            return any ? copy : null;
        }
        catch { return null; }
    }

    public static Task<bool> Copy(string text) => SetClipboard(text);

    /** transient: just passing through for a paste, so keep it out of Win+V history and cloud clipboard sync. */
    private static async Task<bool> SetClipboard(string text, bool transient = false)
    {
        // Other apps can hold the clipboard open for a moment; retry a few times.
        for (int i = 0; i < 8; i++)
        {
            try
            {
                var data = new DataObject();
                data.SetText(text);
                if (transient)
                {
                    data.SetData("ExcludeClipboardContentFromMonitorProcessing", new MemoryStream(new byte[4]));
                    data.SetData("CanIncludeInClipboardHistory", new MemoryStream(new byte[4]));
                    data.SetData("CanUploadToCloudClipboard", new MemoryStream(new byte[4]));
                }
                Clipboard.SetDataObject(data, true);
                return true;
            }
            catch { await Task.Delay(40); }
        }
        return false;
    }
}

/** Soft feedback sounds (the desktop stand-in for the phone's haptics), generated in code. */
public static class Sounds
{
    public enum Kind { Start, Stop, Done, Cancel, Error }

    private static readonly System.Collections.Generic.Dictionary<Kind, byte[]> Cache = new();

    public static void Play(Kind kind)
    {
        if (!Core.Settings.Current.Sounds) return;
        Task.Run(() =>
        {
            try
            {
                byte[] pcm;
                lock (Cache)
                {
                    if (!Cache.TryGetValue(kind, out pcm!)) Cache[kind] = pcm = Make(kind);
                }
                using var stream = new RawSourceWaveStream(new MemoryStream(pcm), new WaveFormat(44100, 16, 1));
                using var outp = new WaveOutEvent();
                outp.Init(stream);
                outp.Play();
                while (outp.PlaybackState == PlaybackState.Playing) System.Threading.Thread.Sleep(20);
            }
            catch { }
        });
    }

    private static byte[] Make(Kind kind)
    {
        // (frequency Hz, duration ms) notes; gentle and quiet.
        (double f, int ms)[] notes = kind switch
        {
            Kind.Start => new[] { (660.0, 55), (990.0, 70) },
            Kind.Stop => new[] { (880.0, 45) },
            Kind.Done => new[] { (784.0, 50), (1175.0, 80) },
            Kind.Cancel => new[] { (520.0, 70) },
            _ => new[] { (330.0, 90), (0.0, 40), (330.0, 90) },
        };
        const int rate = 44100;
        using var ms = new MemoryStream();
        foreach (var (f, dur) in notes)
        {
            int n = rate * dur / 1000;
            for (int i = 0; i < n; i++)
            {
                double env = Math.Min(1, Math.Min(i / (rate * 0.006), (n - i) / (rate * 0.03))); // soft attack/release
                double v = f == 0 ? 0 : Math.Sin(2 * Math.PI * f * i / rate) * env * 0.12;
                short s = (short)(v * short.MaxValue);
                ms.WriteByte((byte)s); ms.WriteByte((byte)(s >> 8));
            }
        }
        return ms.ToArray();
    }
}

/** "Start with Windows" via the per-user Run key (no admin rights needed). */
public static class Startup
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static void Apply(bool enabled)
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true);
            if (k == null) return;
            var exe = Environment.ProcessPath;
            if (enabled && exe != null) k.SetValue("Tokalot", $"\"{exe}\" --background");
            else k.DeleteValue("Tokalot", false);
        }
        catch { }
    }
}
