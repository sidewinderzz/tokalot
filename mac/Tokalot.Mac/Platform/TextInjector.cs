using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Avalonia.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/**
 * Puts text into whatever app has focus: clipboard + ⌘V, then puts your old clipboard back (text, a picture or
 * files). The dictation is only left on the clipboard as a fallback, when Tokalot can't press ⌘V itself.
 * The clipboard is the system pasteboard (NSPasteboard); the dictation is marked as passing through
 * (org.nspasteboard.TransientType / ConcealedType), which clipboard-history apps such as Maccy, Alfred
 * and Raycast leave out. ⌘V is a synthetic key press (CGEventPost), which macOS only allows once
 * Tokalot is switched on under Privacy & Security › Accessibility; without it the text is left on
 * the clipboard for you to paste.
 */
public static class TextInjector
{
    [DllImport(Native.CoreGraphics)] private static extern IntPtr CGEventCreateKeyboardEvent(IntPtr source, ushort key, [MarshalAs(UnmanagedType.U1)] bool down);
    [DllImport(Native.CoreGraphics)] private static extern void CGEventSetFlags(IntPtr ev, ulong flags);
    [DllImport(Native.CoreGraphics)] private static extern void CGEventPost(uint tap, IntPtr ev);
    [DllImport(Native.CoreGraphics)] private static extern IntPtr CGEventSourceCreate(int stateId);

    private const ushort KeyV = 9, KeyZ = 6;
    private const ulong FlagCommand = 1UL << 20;

    public static void Init() { }

    /** True when Tokalot is allowed to press ⌘V itself. */
    public static bool CanType => Permissions.Accessibility;

    public static string Method => CanType ? "system keyboard events (Accessibility allowed)" : "none (Accessibility not allowed yet)";

    /** Linux only; on a Mac the clipboard always reaches every app. */
    public static bool ClipboardNeedsWlCopy => false;

    public static string ClipboardMethod => "system pasteboard";

    public enum Result { Pasted, Copied, Failed }

    /** Copied means the text is on the clipboard but Tokalot didn't (or couldn't) press ⌘V itself. */
    public static async Task<Result> Paste(string text, ActiveApp? app = null)
    {
        text = text.Replace("\r\n", "\n");
        // Wait (briefly) until Ctrl/Cmd/Option/Shift are up, so the paste isn't read as another shortcut.
        for (int i = 0; i < 30 && HotkeyHook.ModifiersDown; i++)
            await Task.Delay(50);

        var before = await OnUi(Snapshot);
        var mine = await OnUi(() => Set(text, transient: true));
        if (mine < 0) return Result.Failed;
        if (!CanType)
        {
            // The first time, macOS's own prompt adds Tokalot to the Accessibility list, ready to switch on.
            await OnUi(() => { App.Current.AskForAccessibilityOnce(); return 0; });
            return Result.Copied;
        }

        // Clicking Tokalot's indicator can bring Tokalot to the front; the paste belongs in the app you were in.
        await OnUi(() => { Overlay.ReturnFocus(); return 0; });
        await Task.Delay(60);
        if (!await Task.Run(() => Chord(KeyV))) return Result.Copied;

        // Give the target app time to read the clipboard before restoring it.
        await Task.Delay(450);
        await OnUi(() =>
        {
            // Only put the old clipboard back if nothing else replaced ours in the meantime.
            if (ChangeCount() != mine) return 0;
            if (before.Items != null) { if (!Write(before.Items)) Clear(); }
            else if (before.Text != null) { if (before.Text != text) Set(before.Text, transient: false); }
            // It was empty, or held a password or something that couldn't be read back: the dictation landed, so
            // it doesn't stay on the clipboard either (it's in history).
            else Clear();
            return 0;
        });
        return Result.Pasted;
    }

    /**
     * Takes back what was just pasted (⌘Z in the focused app) and pastes this instead.
     * If Tokalot can't press keys, nothing is undone: the text is only copied.
     */
    public static async Task<Result> Replace(string text, ActiveApp? app = null)
    {
        text = text.Replace("\r\n", "\n");
        if (!CanType) return await Copy(text) ? Result.Copied : Result.Failed;
        // The shortcut that asked for this (Ctrl+Cmd+Z) must be fully let go first, or the undo would be read as something else.
        for (int i = 0; i < 30 && HotkeyHook.ModifiersDown; i++)
            await Task.Delay(50);
        if (!await Task.Run(() => Chord(KeyZ)))
            return await Copy(text) ? Result.Copied : Result.Failed;
        await Task.Delay(150);
        return await Paste(text, app);
    }

    public static async Task<bool> Copy(string text) => await OnUi(() => Set(text.Replace("\r\n", "\n"), transient: false)) >= 0;

    // ---------- the paste shortcut ----------

    /** ⌘ plus a key, as one press and release. */
    internal static bool Chord(ushort key)
    {
        if (!OperatingSystem.IsMacOS()) return false;
        try
        {
            // A private event source, so the keys Tokalot sends don't mix with what you're holding down.
            var source = CGEventSourceCreate(-1);
            var down = CGEventCreateKeyboardEvent(source, key, true);
            var up = CGEventCreateKeyboardEvent(source, key, false);
            if (down == IntPtr.Zero || up == IntPtr.Zero) return false;
            CGEventSetFlags(down, FlagCommand);
            CGEventSetFlags(up, FlagCommand);
            CGEventPost(0, down); // kCGHIDEventTap
            System.Threading.Thread.Sleep(8);
            CGEventPost(0, up);
            Native.CFRelease(down);
            Native.CFRelease(up);
            if (source != IntPtr.Zero) Native.CFRelease(source);
            return true;
        }
        catch (Exception e)
        {
            App.Log("Key press failed: " + e.Message);
            return false;
        }
    }

    // ---------- clipboard ----------

    private static Task<T> OnUi<T>(Func<T> f) =>
        Dispatcher.UIThread.CheckAccess() ? Task.FromResult(f()) : Dispatcher.UIThread.InvokeAsync(f).GetTask();

    private static IntPtr Board => Native.Send(Native.Class("NSPasteboard"), "generalPasteboard");

    private const string TextType = "public.utf8-plain-text";

    /** The pasteboard's change count: it goes up whenever anything is copied. */
    public static long ChangeCount()
    {
        using var pool = Native.AutoreleasePool();
        return Native.SendRL(Board, "changeCount");
    }

    /**
     * Puts text on the clipboard and returns the change count it got (or -1). transient: just passing
     * through for a paste, so clipboard-history apps are asked not to keep it.
     */
    internal static long Set(string text, bool transient)
    {
        if (!OperatingSystem.IsMacOS()) return -1;
        try
        {
            using var pool = Native.AutoreleasePool();
            var board = Board;
            Native.Send(board, "clearContents");
            var types = transient ? new[] { TextType, "org.nspasteboard.TransientType", "org.nspasteboard.ConcealedType" } : new[] { TextType };
            var ptrs = new IntPtr[types.Length];
            for (int i = 0; i < types.Length; i++) ptrs[i] = Native.NSString(types[i]);
            var array = MakeArray(ptrs);
            Native.Send(board, "declareTypes:owner:", array, IntPtr.Zero);
            bool ok = Native.SendRB(board, "setString:forType:", Native.NSString(text), Native.NSString(TextType));
            foreach (var marker in types[1..]) Native.SendRB(board, "setString:forType:", Native.NSString(""), Native.NSString(marker));
            Native.CFRelease(array);
            return ok ? Native.SendRL(board, "changeCount") : -1;
        }
        catch (Exception e)
        {
            App.Log("Clipboard write failed: " + e.Message);
            return -1;
        }
    }

    private static readonly IntPtr MsgSend = NativeLibrary.GetExport(NativeLibrary.Load(Native.ObjC), "objc_msgSend");

    /** A new NSArray of these objects (release it). */
    private static unsafe IntPtr MakeArray(IntPtr[] items)
    {
        var alloc = Native.Send(Native.Class("NSArray"), "alloc");
        fixed (IntPtr* p = items)
            return ((delegate* unmanaged<IntPtr, IntPtr, IntPtr*, nuint, IntPtr>)MsgSend)(alloc, Native.Sel("initWithObjects:count:"), p, (nuint)items.Length);
    }

    private static void Clear()
    {
        using var pool = Native.AutoreleasePool();
        Native.Send(Board, "clearContents");
    }

    /** One pasteboard item, as each of its types and the bytes it holds for that type. */
    private sealed record Item((string Type, byte[] Data)[] Types);

    /** Text: plain text to put back. Items: a picture or files, copied as they were. Neither: empty it after the paste. */
    private readonly record struct Held(string? Text, Item[]? Items = null);

    /** Bigger copies than this (a huge picture, say) aren't held on to; the clipboard is emptied after the paste instead. */
    private const long MaxHeld = 64L << 20;

    /**
     * What the clipboard holds before a paste. A picture (a screenshot) or copied files are kept exactly, every
     * type of every item, so they come back as they were. A password from a password manager is not kept: it
     * would come back as a new copy, which the manager's auto-clear wouldn't recognise, and clipboard-history apps
     * would keep it. Anything else comes back as plain text, so formatted text comes back plain.
     */
    private static Held Snapshot()
    {
        if (!OperatingSystem.IsMacOS()) return new Held(null);
        try
        {
            using var pool = Native.AutoreleasePool();
            var board = Board;
            var types = Native.Send(board, "types");
            bool files = false, picture = false, text = false;
            if (types != IntPtr.Zero)
                for (nint i = 0, n = Native.CFArrayGetCount(types); i < n; i++)
                    if (Native.Text(Native.CFArrayGetValueAtIndex(types, i)) is { } type)
                    {
                        if (IsSecret(type)) return new Held(null);
                        files |= type is "public.file-url" or "NSFilenamesPboardType";
                        picture |= type is "public.tiff" or "public.png" or "public.jpeg" or "public.heic" or "com.adobe.pdf" or "NSTIFFPboardType";
                        text |= type == TextType;
                    }
            // A picture only when it's the point of the copy: a spreadsheet range or a web page also offers itself as
            // a picture, drawn on request, which is slow for a big copy. Those come back as their text.
            if (files || (picture && !text)) return Copy(board) is { } items ? new Held(null, items) : new Held(null);
            var plain = Native.Text(Native.Send(board, "stringForType:", Native.NSString(TextType)));
            if (!string.IsNullOrEmpty(plain)) return new Held(plain);
            return new Held(null);
        }
        catch { return new Held(null); }
    }

    /** Every item on the pasteboard with all its data, or null when it can't all be read (or is too big to hold). */
    private static Item[]? Copy(IntPtr board)
    {
        var items = Native.Send(board, "pasteboardItems");
        if (items == IntPtr.Zero) return null;
        var list = new List<Item>();
        long size = 0;
        for (nint i = 0, n = Native.CFArrayGetCount(items); i < n; i++)
        {
            var item = Native.CFArrayGetValueAtIndex(items, i);
            var types = Native.Send(item, "types");
            if (types == IntPtr.Zero) continue;
            var kept = new List<(string, byte[])>();
            for (nint j = 0, m = Native.CFArrayGetCount(types); j < m; j++)
            {
                if (Native.Text(Native.CFArrayGetValueAtIndex(types, j)) is not { } type) continue;
                var data = Native.Send(item, "dataForType:", Native.NSString(type));
                if (data == IntPtr.Zero) continue; // offered but not given (a promise that wasn't kept): leave that type out
                var bytes = Native.Bytes(data);
                if ((size += bytes.Length) > MaxHeld) return null;
                kept.Add((type, bytes));
            }
            if (kept.Count > 0) list.Add(new Item(kept.ToArray()));
        }
        return list.Count > 0 ? list.ToArray() : null;
    }

    /** Puts copied items back on the clipboard. False: they couldn't be written. */
    private static bool Write(Item[] items)
    {
        try
        {
            using var pool = Native.AutoreleasePool();
            var board = Board;
            Native.Send(board, "clearContents");
            var made = new IntPtr[items.Length];
            for (int i = 0; i < items.Length; i++)
            {
                made[i] = Native.Send(Native.Send(Native.Class("NSPasteboardItem"), "alloc"), "init");
                foreach (var (type, bytes) in items[i].Types)
                {
                    var data = Native.Data(bytes);
                    Native.SendRB(made[i], "setData:forType:", data, Native.NSString(type));
                    Native.CFRelease(data);
                }
            }
            var array = MakeArray(made);
            var ok = Native.SendRB(board, "writeObjects:", array);
            Native.CFRelease(array);
            foreach (var m in made) Native.CFRelease(m);
            return ok;
        }
        catch (Exception e)
        {
            App.Log("Clipboard restore failed: " + e.Message);
            return false;
        }
    }

    /** The marks password managers put on what they copy. */
    private static bool IsSecret(string type) =>
        type == "org.nspasteboard.ConcealedType" || type.StartsWith("com.agilebits.", StringComparison.Ordinal); // 1Password's own marks

    /** The text on the clipboard, or null (used by --selftest). */
    internal static string? Read()
    {
        using var pool = Native.AutoreleasePool();
        return Native.Text(Native.Send(Board, "stringForType:", Native.NSString(TextType)));
    }

    /** Puts a PNG picture on the clipboard, as a screenshot would (for --paste-test). */
    internal static bool SetPicture(byte[] png) => Write(new[] { new Item(new[] { ("public.png", png) }) });

    /** The PNG picture on the clipboard, or null (for --paste-test). */
    internal static byte[]? ReadPicture()
    {
        using var pool = Native.AutoreleasePool();
        var data = Native.Send(Board, "dataForType:", Native.NSString("public.png"));
        return data == IntPtr.Zero ? null : Native.Bytes(data);
    }

    /** Writes, reads back and restores the clipboard; for --selftest. */
    internal static string SelfTest()
    {
        var before = Snapshot();
        var probe = "Tokalot clipboard test " + Guid.NewGuid().ToString("N")[..8];
        var count = Set(probe, transient: true);
        var back = Read();
        if (before.Items != null) Write(before.Items); else if (before.Text != null) Set(before.Text, false); else Clear();
        return count >= 0 && back == probe ? "OK (wrote, read back and restored)" : $"FAILED (wrote {count >= 0}, read back {(back == null ? "nothing" : back == probe ? "the same" : "something else")})";
    }
}
