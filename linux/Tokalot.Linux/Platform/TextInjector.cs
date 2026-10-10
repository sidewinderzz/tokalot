using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/**
 * Puts text into whatever app has focus: clipboard + Ctrl+V, then puts your old clipboard text back.
 * Ctrl+V is pressed on a virtual keyboard (/dev/uinput), which works on X11 and Wayland alike.
 * Without access to /dev/uinput it tries wtype / xdotool / ydotool, and if none of those works
 * the text is left on the clipboard for you to paste.
 */
public static class TextInjector
{
    /** Makes the virtual keyboard up front, so it has settled long before the first paste. */
    public static void Init() => _ = Uinput.Ready;

    /** True when Tokalot can press the paste shortcut itself (or has a helper it hasn't found wanting yet). */
    public static bool CanType => Uinput.Ready || workingTool != null || (!toolsFailed && KeyTools().Count > 0);

    /** How the paste shortcut gets pressed, for Settings and --diagnose. Names the helper that actually worked once one has. */
    public static string Method =>
        Uinput.Ready ? "virtual keyboard (/dev/uinput)"
        : workingTool != null ? workingTool
        : toolsFailed ? "none (" + string.Join(", ", KeyTools().Select(t => t.Name)) + " didn't work here)"
        : KeyTools().Count > 0 ? KeyTools()[0].Name + " (not tried yet)"
        : "none";

    /**
     * True when the clipboard Tokalot can reach may not be the one Wayland apps paste from: a Wayland
     * desktop other than GNOME, without wl-copy. Tokalot's windows are XWayland windows there, and
     * such desktops don't pass a background XWayland app's clipboard on. Pressing Ctrl+V then would
     * paste whatever was copied before, so Tokalot doesn't press it.
     */
    public static bool ClipboardNeedsWlCopy =>
        Sh.IsWayland && !Sh.IsGnome && !Sh.Has("wl-copy") && string.IsNullOrEmpty(Environment.GetEnvironmentVariable("TOKALOT_CLIPBOARD"));

    public enum Result { Pasted, Copied, Failed }

    /** Copied means the text is on the clipboard but Tokalot didn't (or couldn't) press the paste shortcut itself. */
    public static async Task<Result> Paste(string text, ActiveApp? app = null)
    {
        text = text.Replace("\r\n", "\n");
        // Wait (briefly) until Ctrl/Super/Alt/Shift are up, so the paste isn't read as another shortcut.
        for (int i = 0; i < 30 && HotkeyHook.ModifiersDown; i++)
            await Task.Delay(50);

        var before = await Snapshot();

        if (!await SetClipboard(text, transient: true)) return Result.Failed;
        if (ClipboardNeedsWlCopy) return Result.Copied;
        // Give the desktop a moment to notice the new clipboard owner before the paste asks for it.
        await Task.Delay(60);
        var terminal = AppDetect.IsTerminal(app);
        if (!await Task.Run(() => SendPaste(terminal))) return Result.Copied;

        // Wayland can't say which app has focus, so the paste may have gone to a terminal as a plain Ctrl+V and done
        // nothing. The dictation stays on the clipboard then, ready for Ctrl+Shift+V, instead of the old clipboard.
        if (app == null && Sh.IsWayland) return Result.Pasted;
        // Give the target app time to read the clipboard before restoring it.
        await Task.Delay(450);
        // It held a picture, files, a password or something else that can't be put back as it was: leave the dictation there rather than a damaged copy.
        if (before.Kind == Held.Other || before.Text == text) return Result.Pasted;
        // Only put the old clipboard back if nothing else replaced ours in the meantime.
        try { if (await GetClipboard() is { } now && now != text) return Result.Pasted; } catch { }
        if (before.Kind == Held.Text) await SetClipboard(before.Text!);
        else await ClearClipboard(); // it was empty before, so leave it empty
        return Result.Pasted;
    }

    /**
     * Takes back what was just pasted (Ctrl+Z in the focused app) and pastes this instead.
     * Pasted only when both the undo and the paste were sent. If Tokalot can't press keys here (or its
     * clipboard may not reach the app), nothing is undone: the text is only copied and the result is Copied.
     */
    public static async Task<Result> Replace(string text, ActiveApp? app = null)
    {
        text = text.Replace("\r\n", "\n");
        if (ClipboardNeedsWlCopy || !CanType)
            return await SetClipboard(text) ? Result.Copied : Result.Failed;
        // The shortcut that asked for this (Ctrl+Super+Z) must be fully let go first, or the undo would be read as something else.
        for (int i = 0; i < 30 && HotkeyHook.ModifiersDown; i++)
            await Task.Delay(50);
        if (!await Task.Run(() => Send(Combo.CtrlZ)))
            return await SetClipboard(text) ? Result.Copied : Result.Failed;
        await Task.Delay(150);
        return await Paste(text, app);
    }

    public static Task<bool> Copy(string text) => SetClipboard(text);

    // ---------- the paste shortcut ----------

    private enum Combo { CtrlV, CtrlShiftV, ShiftInsert, CtrlZ }

    /** Ctrl+V normally, Ctrl+Shift+V in terminals. TOKALOT_PASTE=ctrl+v | ctrl+shift+v | shift+insert forces one. */
    private static Combo Pick(bool terminal) => (Environment.GetEnvironmentVariable("TOKALOT_PASTE") ?? "").ToLowerInvariant().Replace(" ", "") switch
    {
        "ctrl+v" => Combo.CtrlV,
        "ctrl+shift+v" => Combo.CtrlShiftV,
        "shift+insert" => Combo.ShiftInsert,
        _ => terminal ? Combo.CtrlShiftV : Combo.CtrlV,
    };

    private static string? workingTool;
    private static bool toolsFailed;

    private static bool SendPaste(bool terminal) => Send(Pick(terminal));

    private static bool Send(Combo combo)
    {
        if (Uinput.Ready)
        {
            var sent = combo switch
            {
                Combo.CtrlZ => Uinput.Chord(new[] { Uinput.KEY_LEFTCTRL }, Uinput.KEY_Z),
                Combo.CtrlShiftV => Uinput.Chord(new[] { Uinput.KEY_LEFTCTRL, Uinput.KEY_LEFTSHIFT }, Uinput.KEY_V),
                Combo.ShiftInsert => Uinput.Chord(new[] { Uinput.KEY_LEFTSHIFT }, Uinput.KEY_INSERT),
                _ => Uinput.Chord(new[] { Uinput.KEY_LEFTCTRL }, Uinput.KEY_V),
            };
            if (sent) return true;
            // The virtual keyboard just broke; fall through to the helper programs.
        }
        // Installed is not the same as working (wtype needs a protocol GNOME and KDE don't offer, ydotool needs its
        // daemon): try each in turn, the one that worked last time first, and remember which one did.
        var tools = KeyTools().OrderByDescending(t => t.Name == workingTool).ToList();
        foreach (var tool in tools)
            foreach (var args in ToolArgs(tool.Name, combo))
                if (Sh.Run(tool.Path, args, timeoutMs: 1500).Exit == 0)
                {
                    workingTool = tool.Name;
                    toolsFailed = false;
                    return true;
                }
        if (tools.Count > 0)
        {
            if (!toolsFailed) App.Log("Paste helpers failed: " + string.Join(", ", tools.Select(t => t.Name)));
            workingTool = null;
            toolsFailed = true;
        }
        return false;
    }

    /** The command lines to try for one helper (ydotool changed its syntax between 0.1 and 1.0, so it gets both). */
    private static IEnumerable<string[]> ToolArgs(string tool, Combo combo)
    {
        switch (tool)
        {
            case "wtype":
                yield return combo switch
                {
                    Combo.CtrlShiftV => new[] { "-M", "ctrl", "-M", "shift", "v", "-m", "shift", "-m", "ctrl" },
                    Combo.ShiftInsert => new[] { "-M", "shift", "-k", "Insert", "-m", "shift" },
                    Combo.CtrlZ => new[] { "-M", "ctrl", "z", "-m", "ctrl" },
                    _ => new[] { "-M", "ctrl", "v", "-m", "ctrl" },
                };
                break;
            case "xdotool":
                yield return new[] { "key", "--clearmodifiers", combo switch { Combo.CtrlShiftV => "ctrl+shift+v", Combo.ShiftInsert => "shift+Insert", Combo.CtrlZ => "ctrl+z", _ => "ctrl+v" } };
                break;
            default:
                // ydotool 1.x takes raw key codes: 29 Ctrl, 42 Shift, 44 Z, 47 V, 110 Insert.
                yield return combo switch
                {
                    Combo.CtrlZ => new[] { "key", "29:1", "44:1", "44:0", "29:0" },
                    Combo.CtrlShiftV => new[] { "key", "29:1", "42:1", "47:1", "47:0", "42:0", "29:0" },
                    Combo.ShiftInsert => new[] { "key", "42:1", "110:1", "110:0", "42:0" },
                    _ => new[] { "key", "29:1", "47:1", "47:0", "29:0" },
                };
                // ydotool 0.1.x takes key names.
                yield return new[] { "key", combo switch { Combo.CtrlShiftV => "ctrl+shift+v", Combo.ShiftInsert => "shift+insert", Combo.CtrlZ => "ctrl+z", _ => "ctrl+v" } };
                break;
        }
    }

    /** The helper programs that can press keys when /dev/uinput is off limits, in the order worth trying. */
    private static List<(string Name, string Path)> KeyTools()
    {
        // wtype only speaks to wlroots-style Wayland desktops; xdotool only reaches X11 (and XWayland) windows.
        // ydotool goes through /dev/uinput itself (its daemon may have the access Tokalot lacks).
        var order = Sh.IsWayland ? new[] { "wtype", "ydotool", "xdotool" } : new[] { "xdotool", "ydotool" };
        var list = new List<(string, string)>();
        foreach (var n in order)
            if (Sh.Which(n) is { } p) list.Add((n, p));
        return list;
    }

    // ---------- clipboard ----------

    private enum Backend { Avalonia, WlCopy, Xclip, Xsel, None }

    /**
     * Avalonia's own clipboard is used wherever it is known to reach every app. In a Wayland session
     * Avalonia runs through XWayland, and outside GNOME the desktop may not pass a background X11
     * app's clipboard on to Wayland apps, so wl-copy is preferred there when installed.
     * TOKALOT_CLIPBOARD=avalonia | wl-copy | xclip | xsel forces one.
     */
    private static Backend Choose()
    {
        switch ((Environment.GetEnvironmentVariable("TOKALOT_CLIPBOARD") ?? "").ToLowerInvariant())
        {
            case "avalonia": return Backend.Avalonia;
            case "wl-copy" when Sh.Has("wl-copy"): return Backend.WlCopy;
            case "xclip" when Sh.Has("xclip"): return Backend.Xclip;
            case "xsel" when Sh.Has("xsel"): return Backend.Xsel;
        }
        if (Sh.IsWayland && !Sh.IsGnome && Sh.Has("wl-copy")) return Backend.WlCopy;
        if (Host() != null) return Backend.Avalonia;
        if (Sh.IsWayland && Sh.Has("wl-copy")) return Backend.WlCopy;
        if (Sh.Has("xclip")) return Backend.Xclip;
        if (Sh.Has("xsel")) return Backend.Xsel;
        return Backend.None;
    }

    public static string ClipboardMethod => Choose() switch
    {
        Backend.Avalonia => "built in",
        Backend.WlCopy => "wl-copy",
        Backend.Xclip => "xclip",
        Backend.Xsel => "xsel",
        _ => "none",
    };

    /** Any Tokalot window gives access to the clipboard; the pill and indicator always exist while Tokalot runs. */
    private static IClipboard? Host()
    {
        try { return App.Current.AnyWindow?.Clipboard; } catch { return null; }
    }

    private static Task<T> OnUi<T>(Func<Task<T>> f) =>
        Dispatcher.UIThread.CheckAccess() ? f() : Dispatcher.UIThread.InvokeAsync(f);

    private static bool? wlSensitive;

    /** Newer wl-copy can mark what it offers as sensitive, which keeps it out of clipboard-history tools. */
    private static bool WlSensitive(string wlCopy) => wlSensitive ??= Sh.Run(wlCopy, new[] { "--help" }, timeoutMs: 800).Out.Contains("--sensitive");

    /** transient: just passing through for a paste, so ask clipboard managers (KDE's Klipper and others) not to keep it. */
    private static async Task<bool> SetClipboard(string text, bool transient = false)
    {
        var backend = Choose();
        if (backend == Backend.Avalonia)
        {
            // Another app can hold the clipboard for a moment; retry a few times.
            for (int i = 0; i < 4; i++)
            {
                try
                {
                    await OnUi(async () =>
                    {
                        if (transient)
                        {
                            try
                            {
                                var item = new DataTransferItem();
                                item.SetText(text);
                                item.Set(DataFormat.CreateBytesPlatformFormat("x-kde-passwordManagerHint"), System.Text.Encoding.UTF8.GetBytes("secret"));
                                var data = new DataTransfer();
                                data.Add(item);
                                await Host()!.SetDataAsync(data);
                                return true;
                            }
                            catch { }
                        }
                        await Host()!.SetTextAsync(text);
                        return true;
                    });
                    return true;
                }
                catch { await Task.Delay(40); }
            }
            backend = Sh.Has("wl-copy") && Sh.IsWayland ? Backend.WlCopy : Sh.Has("xclip") ? Backend.Xclip : Sh.Has("xsel") ? Backend.Xsel : Backend.None;
        }
        // These programs stay running in the background to hand the text out, so their output isn't captured.
        return await Task.Run(() =>
        {
            switch (backend)
            {
                case Backend.WlCopy:
                    var wl = Sh.Which("wl-copy")!;
                    var args = new List<string> { "--type", "text/plain;charset=utf-8" };
                    if (transient && WlSensitive(wl)) args.Add("--sensitive");
                    return Sh.Run(wl, args.ToArray(), stdin: text, capture: false).Exit == 0;
                case Backend.Xclip: return Sh.Run(Sh.Which("xclip")!, new[] { "-selection", "clipboard" }, stdin: text, capture: false).Exit == 0;
                case Backend.Xsel: return Sh.Run(Sh.Which("xsel")!, new[] { "--clipboard", "--input" }, stdin: text, capture: false).Exit == 0;
                default: return false;
            }
        });
    }

    private static async Task ClearClipboard()
    {
        try
        {
            switch (Choose())
            {
                case Backend.Avalonia: await OnUi(async () => { await Host()!.ClearAsync(); return true; }); break;
                case Backend.WlCopy: await Task.Run(() => Sh.Run(Sh.Which("wl-copy")!, new[] { "--clear" }, capture: false)); break;
                case Backend.Xsel: await Task.Run(() => Sh.Run(Sh.Which("xsel")!, new[] { "--clipboard", "--clear" }, capture: false)); break;
                case Backend.Xclip: await Task.Run(() => Sh.Run(Sh.Which("xclip")!, new[] { "-selection", "clipboard" }, stdin: "", capture: false)); break;
            }
        }
        catch { }
    }

    private enum Held { Text, Empty, Other }

    /**
     * What the clipboard holds before a paste. Only text can be put back afterwards: a picture or a
     * file list offered by another app can't be re-offered by Tokalot (a copied file would come back
     * as just its name), and a password copied from a password manager would come back without its
     * "secret" mark, so clipboard-history tools would keep it. Those are reported as Other and the
     * dictation is left on the clipboard instead. Formatted text comes back as plain text. When it
     * can't be told what the clipboard holds, it counts as Other too.
     */
    private static async Task<(Held Kind, string? Text)> Snapshot()
    {
        try
        {
            List<string>? types = null;
            switch (Choose())
            {
                case Backend.Avalonia:
                    var formats = await OnUi(async () => await Host()!.GetDataFormatsAsync());
                    if (formats.Any(f => f == DataFormat.File || f == DataFormat.Bitmap)) return (Held.Other, null);
                    types = formats.Select(f => f.Identifier).ToList();
                    break;
                case Backend.WlCopy when Sh.Which("wl-paste") is { } wp:
                    var listed = await Task.Run(() => Sh.Run(wp, new[] { "--list-types" }, timeoutMs: 600));
                    // wl-paste fails when the clipboard is empty.
                    types = listed.Exit == 0 ? Lines(listed.Out) : new List<string>();
                    break;
                case Backend.Xclip:
                    var targets = await Task.Run(() => Sh.Run(Sh.Which("xclip")!, new[] { "-selection", "clipboard", "-o", "-t", "TARGETS" }, timeoutMs: 600));
                    types = targets.Exit == 0 ? Lines(targets.Out) : new List<string>();
                    break;
            }
            if (types != null && types.Any(CantPutBack)) return (Held.Other, null);
            var text = await GetClipboard();
            if (!string.IsNullOrEmpty(text)) return (Held.Text, text);
            // xsel can't list what the clipboard holds.
            if (types != null) return (types.Count == 0 ? Held.Empty : Held.Other, null);
        }
        catch { }
        return (Held.Other, null);
    }

    private static List<string> Lines(string s) => s.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();

    /** Clipboard types that a plain-text copy would lose: pictures, files, and a password manager's "secret" mark. */
    private static bool CantPutBack(string type)
    {
        var t = type.ToLowerInvariant();
        return t.StartsWith("image/") || t == "text/uri-list" || t.StartsWith("x-special/") || t.Contains("passwordmanagerhint")
            || t == "application/x-kde-cutselection" || t is "files" or "filenames" or "file" or "bitmap" or "pixmap";
    }

    /** The text on the clipboard, or null if there is none (or it holds something that isn't text). */
    private static async Task<string?> GetClipboard()
    {
        try
        {
            switch (Choose())
            {
                case Backend.Avalonia:
                    return await OnUi(async () => await Host()!.TryGetTextAsync());
                case Backend.WlCopy when Sh.Which("wl-paste") is { } wp:
                    return await Task.Run(() => Sh.Run(wp, new[] { "--no-newline", "--type", "text" }, timeoutMs: 600) is { Exit: 0 } r ? r.Out : null);
                case Backend.Xclip:
                    return await Task.Run(() => Sh.Run(Sh.Which("xclip")!, new[] { "-selection", "clipboard", "-o" }, timeoutMs: 600) is { Exit: 0 } r ? r.Out : null);
                case Backend.Xsel:
                    return await Task.Run(() => Sh.Run(Sh.Which("xsel")!, new[] { "--clipboard", "--output" }, timeoutMs: 600) is { Exit: 0 } r ? r.Out : null);
            }
        }
        catch { }
        return null;
    }
}
