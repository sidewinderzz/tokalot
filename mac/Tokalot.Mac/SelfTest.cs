using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop;

/**
 * "Tokalot --selftest": exercises the Mac-only pieces directly and prints one PASS / FAIL / INFO line
 * each, for the build machine (and for a tester who wants to see what works). It exits non-zero if
 * anything FAILs. Permissions a CI machine can't have are reported as INFO unless asked for:
 *   --synthetic-keys   press Ctrl+Cmd, Z and Esc (synthetically) and check the keyboard tap sees them
 *   --expect-tap       the keyboard tap must work (and, with --synthetic-keys, see those presses)
 *   --expect-paste     posting ⌘V must be allowed
 *   --record <secs>    record from the default input device and report what came in
 *   --record-to <wav>  also save that recording
 *   --no-keychain      skip the Keychain round trip
 */
internal static unsafe class SelfTest
{
    private static int failures;

    private static void Pass(string name, string detail) => Console.WriteLine($"PASS {name}: {detail}");
    private static void Fail(string name, string detail) { failures++; Console.WriteLine($"FAIL {name}: {detail}"); }
    private static void Info(string name, string detail) => Console.WriteLine($"INFO {name}: {detail}");

    private static void Check(string name, Func<(bool Ok, string Detail)> test)
    {
        try
        {
            var (ok, detail) = test();
            if (ok) Pass(name, detail); else Fail(name, detail);
        }
        catch (Exception e) { Fail(name, e.GetType().Name + ": " + e.Message); }
    }

    public static int Run(string[] args)
    {
        string? Arg(string name) { var i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }

        Info("system", Native.MacVersion() + " · " + RuntimeInformation.ProcessArchitecture + " · bundle " + (Startup.Bundle ?? (Paths.IsTestInstance ? "(test copy)" : "none")));

        if (!args.Contains("--no-keychain"))
            Check("keychain", () =>
            {
                const string svc = "Tokalot selftest", acct = "selftest";
                var a = Keychain.Store(svc, acct, "first");
                var b = Keychain.Store(svc, acct, "second ✓");
                var (fs, found) = Keychain.Find(svc, acct);
                var d = Keychain.Delete(svc, acct);
                var (gone, _) = Keychain.Find(svc, acct);
                return (a == 0 && b == 0 && fs == 0 && found == "second ✓" && d == 0 && gone == Keychain.NotFound,
                    $"add {a}, update {b}, read {fs} \"{found}\", delete {d}, read after delete {gone}");
            });

        Check("clipboard", () => { var r = TextInjector.SelfTest(); return (r.StartsWith("OK"), r); });

        Check("front app", () => AppDetect.Frontmost() is { } f ? (true, $"{f.Name} ({f.Bundle}, pid {f.Pid})") : (false, "none"));

        Check("modifier keys", () => (true, "read; held now: " + HotkeyHook.ModifiersDown));

        Check("full-screen check", () => { var (full, _) = Overlay.ActiveWindowState(); return (true, "front window full screen: " + full); });

        Check("start-at-login file", () =>
        {
            var tmp = Path.Combine(Path.GetTempPath(), "tokalot-selftest-agent.plist");
            File.WriteAllText(tmp, Startup.Plist("/Applications/Tokalot & Co.app"));
            var r = Sh.Run("/usr/bin/plutil", new[] { "-lint", tmp }, timeoutMs: 5000);
            File.Delete(tmp);
            return (r.Exit == 0, r.Out.Trim());
        });

        Check("feedback tone", () =>
        {
            var file = Path.Combine(Paths.Dir("sounds"), "selftest.wav");
            File.WriteAllBytes(file, Net.Wav(Enumerable.Range(0, 3200).Select(i => (float)(0.1 * Math.Sin(i * 2 * Math.PI * 660 / Recorder.SampleRate))).ToArray()));
            var r = Sh.Run("/usr/bin/afplay", new[] { file }, timeoutMs: 5000);
            // A machine with no speakers can't play it; that isn't Tokalot's fault, so it's only reported.
            if (r.Exit != 0) Info("feedback tone", "afplay exited " + r.Exit + " (no output device?)");
            return (true, "afplay exit " + r.Exit);
        });

        Info("engine", App.EngineCheck());

        // Permissions: a build machine normally has none of these.
        var tap = Permissions.InputMonitoring;
        var post = Permissions.Accessibility;
        Info("permissions", $"input monitoring {tap}, accessibility {post}, microphone {Permissions.Microphone}");

        Check("keyboard tap", () =>
        {
            using var hook = new HotkeyHook();
            if (!hook.Installed)
                return (!args.Contains("--expect-tap"), "not installed (Input Monitoring not allowed" + (args.Contains("--expect-tap") ? ")" : "; expected on a build machine)"));
            // Pressing keys on someone's own Mac would land in whatever app is in front: only on request (the build machine).
            if (!args.Contains("--synthetic-keys")) return (true, "installed (add --synthetic-keys to also press Ctrl+Cmd, Z and Esc and check they're seen)");
            if (!post) return (!args.Contains("--expect-tap"), "installed; synthetic keys skipped (posting events not allowed)");
            return SyntheticKeys(hook);
        });

        Check("paste permission", () => (post || !args.Contains("--expect-paste"), post ? "⌘V can be posted" : "not allowed (expected on a build machine)"));

        Info("microphone device", Recorder.InputDevice() ?? "none");
        if (Arg("--record") is { } secs && double.TryParse(secs, System.Globalization.CultureInfo.InvariantCulture, out var seconds))
            Check("recording", () => Record(seconds, Arg("--record-to")));

        Console.WriteLine(failures == 0 ? "All checks passed." : $"{failures} check(s) failed.");
        return failures == 0 ? 0 : 1;
    }

    /**
     * Runs a WAV file through the dictation pipeline (speech to text with the chosen provider or the
     * offline model, snippets, cleanup) exactly as a recording would be, and prints the result. The
     * file is read back the way a saved recording is, through the audio store.
     */
    public static int Transcribe(string wav)
    {
        try
        {
            var id = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            File.Copy(wav, Path.Combine(Paths.Dir("audio"), id + ".wav"), true);
            var samples = AudioStore.Load(id);
            AudioStore.Delete(id);
            if (samples == null || samples.Length == 0) { Console.WriteLine("Couldn't read " + wav + " (16-bit PCM WAV expected)"); return 1; }
            Console.WriteLine(FormattableString.Invariant($"Audio: {samples.Length / (double)Recorder.SampleRate:0.0} s"));
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var dictation = new Dictation();
            var outcome = dictation.Process(samples, null).GetAwaiter().GetResult();
            Console.WriteLine($"Time: {sw.ElapsedMilliseconds} ms");
            if (outcome.Warning != null) Console.WriteLine("Note: " + outcome.Warning);
            Console.WriteLine("Text: " + outcome.Text);
            return outcome.Text.Length > 0 ? 0 : 1;
        }
        catch (Exception e)
        {
            Console.WriteLine("Transcription failed: " + e.Message);
            return 1;
        }
    }

    /** Records from the default input for a while and describes what came in. */
    private static (bool, string) Record(double seconds, string? saveTo)
    {
        using var r = new Recorder();
        if (!r.Start()) return (false, "couldn't start: " + Recorder.LastError);
        Thread.Sleep(TimeSpan.FromSeconds(seconds));
        var speech = r.SpeechChunks;
        var samples = r.Stop();
        float peak = samples.Length == 0 ? 0 : samples.Max(Math.Abs);
        if (saveTo != null) File.WriteAllBytes(saveTo, Net.Wav(samples));
        var expected = (int)(seconds * Recorder.SampleRate);
        return (samples.Length >= expected * 0.8,
            FormattableString.Invariant($"{samples.Length} samples ({samples.Length / (double)Recorder.SampleRate:0.00} s of {seconds} s), peak {peak:0.000}, {speech} chunks with sound, from {Recorder.InputDevice() ?? "no device"}"));
    }

    /** "Tokalot --mic-check <seconds>": only the recording check. */
    public static int MicCheck(string seconds)
    {
        Check("recording", () => Record(double.Parse(seconds, System.Globalization.CultureInfo.InvariantCulture), null));
        return failures == 0 ? 0 : 1;
    }

    // ---------- pasting into a real app ----------

    /** Runs the app's own async code on this thread, letting Avalonia's dispatcher do its work meanwhile. */
    private static T Pump<T>(System.Threading.Tasks.Task<T> t)
    {
        while (!t.IsCompleted) { Avalonia.Threading.Dispatcher.UIThread.RunJobs(); Thread.Sleep(5); }
        return t.GetAwaiter().GetResult();
    }

    private static void Wait(int ms)
    {
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        while (DateTime.UtcNow < until) { Avalonia.Threading.Dispatcher.UIThread.RunJobs(); Thread.Sleep(5); }
    }

    /**
     * "Tokalot --paste-test": opens a text file in TextEdit and pastes into it exactly as a dictation
     * does (clipboard, ⌘V, old clipboard put back), then does the "own words back" swap (⌘Z, paste),
     * saving with ⌘S after each and reading the file. Needs Accessibility; for the build machine.
     */
    public static int PasteTest()
    {
        App.StartForTools();
        var file = Path.Combine(Path.GetTempPath(), "tokalot-paste-test.txt");
        File.WriteAllText(file, "");
        Sh.Run("/usr/bin/open", new[] { "-a", "TextEdit", file }, timeoutMs: 10000);
        for (int i = 0; i < 40 && AppDetect.Frontmost()?.Bundle != "com.apple.TextEdit"; i++) Thread.Sleep(250);
        Wait(1500);
        Info("front app", AppDetect.Frontmost()?.Name ?? "none");

        string Saved()
        {
            TextInjector.Chord(1); // ⌘S
            Wait(1200);
            return File.ReadAllText(file).Trim();
        }

        const string previous = "Something copied earlier ✓";
        TextInjector.Set(previous, transient: false);
        const string dictated = "Hello from the Tokalot paste test, with commas and 123.";
        var r1 = Pump(TextInjector.Paste(dictated));
        Wait(300);
        var after = Saved();
        if (r1 == TextInjector.Result.Pasted && after == dictated) Pass("paste", $"\"{after}\" landed in TextEdit");
        else Fail("paste", $"result {r1}, file holds \"{after}\"");
        var clip = TextInjector.Read();
        if (clip == previous) Pass("clipboard restored", "the earlier clipboard text is back");
        else Fail("clipboard restored", $"clipboard holds \"{clip}\"");

        const string own = "Your own words are back.";
        var r2 = Pump(TextInjector.Replace(own));
        Wait(300);
        var swapped = Saved();
        if (r2 == TextInjector.Result.Pasted && swapped == own) Pass("revert", $"⌘Z then paste left \"{swapped}\"");
        else Fail("revert", $"result {r2}, file holds \"{swapped}\"");

        Sh.Run("/usr/bin/killall", new[] { "TextEdit" }, timeoutMs: 5000);
        Console.WriteLine(failures == 0 ? "All checks passed." : $"{failures} check(s) failed.");
        return failures == 0 ? 0 : 1;
    }

    /** "Tokalot --hold-shortcut <seconds>": holds Ctrl+Cmd for that long, as a person would (for the build machine). */
    public static int HoldShortcut(string seconds)
    {
        var s = double.Parse(seconds, System.Globalization.CultureInfo.InvariantCulture);
        Post(59, true, Control, true);
        Post(55, true, Control | Command, true);
        Thread.Sleep(TimeSpan.FromSeconds(s));
        Post(55, false, Control, true);
        Post(59, false, 0, true);
        return 0;
    }

    // ---------- synthetic key presses (only where the machine allows posting events) ----------

    [DllImport(Native.CoreGraphics)] private static extern IntPtr CGEventCreateKeyboardEvent(IntPtr source, ushort key, [MarshalAs(UnmanagedType.U1)] bool down);
    [DllImport(Native.CoreGraphics)] private static extern void CGEventSetFlags(IntPtr ev, ulong flags);
    [DllImport(Native.CoreGraphics)] private static extern void CGEventSetType(IntPtr ev, uint type);
    [DllImport(Native.CoreGraphics)] private static extern void CGEventPost(uint tap, IntPtr ev);

    private const ulong Control = 1UL << 18, Command = 1UL << 20;

    private static void Post(ushort key, bool down, ulong flags, bool modifier)
    {
        var e = CGEventCreateKeyboardEvent(IntPtr.Zero, key, down);
        if (modifier) CGEventSetType(e, 12); // flagsChanged, as a real modifier key sends
        CGEventSetFlags(e, flags);
        CGEventPost(0, e);
        Native.CFRelease(e);
        Thread.Sleep(40);
    }

    /** Holds Ctrl+Cmd, presses Z, Esc, lets go: the hook must report each, as it would for real keys. */
    private static (bool, string) SyntheticKeys(HotkeyHook hook)
    {
        var seen = new List<string>();
        hook.Pressed += () => { lock (seen) seen.Add("pressed"); };
        hook.Released += () => { lock (seen) seen.Add("released"); };
        hook.KeyWhileHeld += k => { lock (seen) seen.Add("key " + k); };
        hook.Escape += () => { lock (seen) seen.Add("esc"); };
        Post(59, true, Control, true);              // left Control down
        Post(55, true, Control | Command, true);    // left Command down
        Post(6, true, Control | Command, false);    // Z
        Post(6, false, Control | Command, false);
        Post(53, true, Control | Command, false);   // Esc
        Post(53, false, Control | Command, false);
        Post(55, false, Control, true);             // Command up
        Post(59, false, 0, true);                   // Control up
        Thread.Sleep(300);
        string got;
        lock (seen) got = string.Join(", ", seen);
        return (got == "pressed, key 6, esc, released", "saw: " + (got.Length > 0 ? got : "nothing"));
    }
}
