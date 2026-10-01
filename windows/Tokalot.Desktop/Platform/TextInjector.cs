using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using NAudio.Wave;

namespace Tokalot.Desktop.Platform;

/** Puts text into whatever app has focus: clipboard + Ctrl+V, then puts your old clipboard back. */
public static class TextInjector
{
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int vk);
    private static bool Down(int vk) => (GetAsyncKeyState(vk) & 0x8000) != 0;

    public static async Task Paste(string text)
    {
        // Windows apps expect CRLF line breaks (lists and paragraphs from the cleanup step).
        text = text.Replace("\r\n", "\n").Replace("\n", "\r\n");
        // Wait (briefly) until Ctrl/Win/Alt/Shift are up, so the paste isn't read as another shortcut.
        for (int i = 0; i < 30 && (Down(0x11) || Down(0x5B) || Down(0x5C) || Down(0x12) || Down(0x10)); i++)
            await Task.Delay(50);

        var previous = Snapshot();

        if (!await SetClipboard(text)) return;
        var inputs = new[]
        {
            HotkeyHook.Key(0x11, false), HotkeyHook.Key(0x56, false), // Ctrl down, V down
            HotkeyHook.Key(0x56, true), HotkeyHook.Key(0x11, true),   // V up, Ctrl up
        };
        HotkeyHook.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<HotkeyHook.INPUT>());

        // Give the target app time to read the clipboard before restoring it.
        await Task.Delay(450);
        if (previous == null) return;
        // Only put the old clipboard back if nothing else replaced ours in the meantime.
        try { if (Clipboard.ContainsText() && Clipboard.GetText() != text) return; } catch { }
        for (int i = 0; i < 8; i++)
        {
            try { Clipboard.SetDataObject(previous, true); return; }
            catch { await Task.Delay(40); }
        }
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

    private static async Task<bool> SetClipboard(string text)
    {
        // Other apps can hold the clipboard open for a moment; retry a few times.
        for (int i = 0; i < 8; i++)
        {
            try { Clipboard.SetDataObject(text, true); return true; }
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
