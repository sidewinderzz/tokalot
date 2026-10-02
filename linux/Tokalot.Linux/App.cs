using System;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Headless;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;
using Tokalot.Desktop.UI;

namespace Tokalot.Desktop;

/**
 * Tokalot for Linux lives in the tray. Hold Ctrl+Super anywhere, talk, let go: the text is
 * cleaned up and pasted where your cursor is. A quick tap of Ctrl+Super starts hands-free mode.
 */
public sealed partial class App : Application
{
    private static bool background, software;
    private static string? shotsDir;
    private static FileStream? instanceLock;
    private static Task<bool>? themeProbe;

    [STAThread]
    public static int Main(string[] args)
    {
        // "Tokalot --diagnose" prints what this computer lets Tokalot do (keyboard, paste, microphone…) and exits.
        if (args.Contains("--diagnose")) { Diagnose(args.Contains("--download-model")); return 0; }

        // Developer tool: "Tokalot --screenshots <folder>" renders every page to PNGs with sample data. Needs no display.
        if (args.Length >= 2 && args[0] == "--screenshots")
        {
            Environment.SetEnvironmentVariable("TOKALOT_DATA", Path.Combine(Path.GetTempPath(), "tokalot-shots-" + Guid.NewGuid().ToString("N")));
            shotsDir = args[1];
            AppBuilder.Configure<App>().UseSkia().WithInterFont().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).SetupWithoutStarting();
            try { Shots.Run(shotsDir); }
            catch (Exception ex) { Log("Screenshots failed: " + ex); Console.Error.WriteLine(ex); return 1; }
            return 0;
        }

        // One copy per data folder. (A copy started with its own TOKALOT_DATA folder, for testing, runs beside the installed one.)
        try
        {
            instanceLock = new FileStream(Paths.File("tokalot.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException)
        {
            // Already running (probably in the tray): ask it to show its window instead.
            try
            {
                using var s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                s.Connect(new UnixDomainSocketEndPoint(ShowSocket));
            }
            catch { }
            return 0;
        }
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log("Crash: " + e.ExceptionObject);
        // Asking the desktop for light/dark and fetching keys from the keyring both start helper programs;
        // they run alongside start-up rather than holding the window up.
        themeProbe = Task.Run(C.ProbeSystem);
        KeyStore.Preload(Settings.Current);
        background = args.Contains("--background");
        software = args.Contains("--software");

        var builder = AppBuilder.Configure<App>().UsePlatformDetect().WithInterFont();
        // "--software" draws without the graphics card, for machines where the window comes up blank.
        if (software) builder = builder.With(new X11PlatformOptions { RenderingMode = new[] { X11RenderingMode.Software } });
        return builder.StartWithClassicDesktopLifetime(args, ShutdownMode.OnExplicitShutdown);
    }

    private static string ShowSocket => Paths.File("show.sock");

    private TrayIcon? tray;

    private void OnStartup()
    {
        var s = Settings.Current;
        // The light/dark probe has had the whole of Avalonia's start-up to finish; give it a moment more, then go on without it.
        try { themeProbe?.Wait(150); } catch { }
        Log($"Start {Updater.CurrentVersion} · {(Sh.IsWayland ? "wayland (through XWayland)" : "x11")} · {Sh.Desktop} · software={software} · {Environment.OSVersion}");
        // (the theme is logged once it is applied, below)
        ApplyLook();
        Log($"Theme: {s.Theme} -> {(C.Dark ? "dark" : "light")}");
        Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            Log("UI error: " + e.Exception);
            e.Handled = true;
        };
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            e.SetObserved();
            // Avalonia looks for a global-menu service that most desktops don't have; its "not found" is not a fault.
            if (e.Exception.ToString().Contains("com.canonical.AppMenu.Registrar")) return;
            Log("Task error: " + e.Exception);
        };
        // Keys arrive from the keyring a moment after start-up, and a refused key changes the note under the key
        // fields: redraw the pages that show either (never one where the user may be half-way through typing).
        KeyStore.Changed += () => Dispatcher.UIThread.Post(() =>
        {
            if (window?.CurrentPage is MainWindow.Page.Home or MainWindow.Page.Settings) window.Render();
        });
        // If the probe was slower than start-up and the desktop turns out to want light, switch now.
        themeProbe?.ContinueWith(_ => Dispatcher.UIThread.Post(FollowSystemTheme));

        Controller = new Controller();
        SetUpTray();

        // Keep "start at login" and the app-menu entry pointing at this copy (its path changes if the folder is moved).
        if (Startup.Supported)
        {
            Startup.Apply(s.LaunchAtStartup);
            Startup.EnsureMenuEntry();
        }

        // A second launch signals this one to show the window.
        try
        {
            File.Delete(ShowSocket);
            var listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            listener.Bind(new UnixDomainSocketEndPoint(ShowSocket));
            listener.Listen(4);
            new Thread(() =>
            {
                while (true)
                {
                    try { using var c = listener.Accept(); } catch { return; }
                    Dispatcher.UIThread.Post(ShowWindow);
                }
            }) { IsBackground = true, Name = "Tokalot show signal" }.Start();
        }
        catch (Exception e) { Log("Show signal unavailable: " + e.Message); }

        // "Match system" follows the desktop when it switches between light and dark.
        try
        {
            if (PlatformSettings is { } ps)
                ps.ColorValuesChanged += (_, _) => Task.Run(C.ProbeSystem).ContinueWith(_ => Dispatcher.UIThread.Post(FollowSystemTheme));
        }
        catch { }

        if (!background) ShowWindow();
        else if (!Controller!.HotkeyWorks)
            Sh.Notify("Tokalot", "Couldn't listen for Ctrl+Super. Open Tokalot to see how to fix it.");

        _ = Sync.Run(); // no-op unless sync is on
        _ = CheckForUpdatesLoop();
    }

    // ---------- tray ----------

    private void SetUpTray()
    {
        try
        {
            tray = new TrayIcon
            {
                ToolTipText = "Tokalot: hold Ctrl+Super to dictate",
                Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://Tokalot/Assets/icon.png"))),
                IsVisible = true,
            };
            var menu = new NativeMenu();
            var open = new NativeMenuItem("Open Tokalot");
            open.Click += (_, _) => ShowWindow();
            menu.Add(open);
            var copy = new NativeMenuItem("Copy last dictation");
            copy.Click += (_, _) =>
            {
                var last = History.All().FirstOrDefault(e => !e.Pending);
                if (last != null) _ = TextInjector.Copy(last.Text);
            };
            menu.Add(copy);
            updateItem = new NativeMenuItem("Update available") { IsVisible = false };
            updateItem.Click += (_, _) => ShowWindow();
            menu.Add(updateItem);
            menu.Add(new NativeMenuItemSeparator());
            var quit = new NativeMenuItem("Quit Tokalot");
            quit.Click += (_, _) => Quit();
            menu.Add(quit);
            tray.Menu = menu;
            tray.Clicked += (_, _) => ShowWindow();
            TrayIcon.SetIcons(this, new TrayIcons { tray });
        }
        catch (Exception e) { Log("Tray icon unavailable: " + e.Message); }
    }

    public void Quit()
    {
        Controller?.Dispose();
        try { if (tray != null) { tray.IsVisible = false; tray.Dispose(); } } catch { }
        try { File.Delete(ShowSocket); } catch { }
        // A key pasted a moment ago may still be on its way to the keyring.
        KeyStore.Flush();
        Uinput.Dispose();
        (ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.Shutdown();
    }

    // ---------- diagnose ----------

    /** Loads the speech engine's native libraries the way the app will, and names what the system is missing if they won't load. */
    private static string EngineCheck()
    {
        var dir = Path.Combine(AppContext.BaseDirectory, "runtimes", System.Runtime.InteropServices.RuntimeInformation.RuntimeIdentifier);
        if (!Directory.Exists(dir)) return "NOT FOUND: no engine for " + System.Runtime.InteropServices.RuntimeInformation.RuntimeIdentifier + " in this download";
        // In dependency order; each failure message from the system names the library it couldn't find.
        foreach (var lib in new[] { "libggml-base-whisper.so", "libggml-cpu-whisper.so", "libggml-whisper.so", "libwhisper.so" })
        {
            var path = Path.Combine(dir, lib);
            if (!File.Exists(path)) continue;
            try { System.Runtime.InteropServices.NativeLibrary.Load(path); }
            catch (Exception e)
            {
                var why = e.Message.Replace("\n", " ");
                return "NOT WORKING: " + lib + " won't load (" + why + "). It needs glibc 2.34 or newer and libgomp.so.1 "
                    + "(Debian/Ubuntu: sudo apt install libgomp1 · Fedora: sudo dnf install libgomp · Arch: gcc-libs).";
            }
        }
        return "OK (libraries load)";
    }

    /** A plain-text report of what works on this computer, for setup and bug reports. Starts no windows. */
    private static void Diagnose(bool downloadModel)
    {
        void Line(string k, string v) => Console.WriteLine($"{k,-22} {v}");
        Line("Tokalot", Updater.CurrentVersion);
        Line("Session", (Sh.IsWayland ? "Wayland (Tokalot's windows use XWayland)" : "X11") + (Sh.Desktop.Length > 0 ? " · " + Sh.Desktop : ""));
        Line("Data folder", Paths.Root);

        using (var hook = new HotkeyHook())
            Line("Ctrl+Super shortcut", hook.Installed ? "OK (keyboard readable)"
                : hook.PermissionDenied ? "NOT WORKING: no permission. Run: " + MainWindow.InputGroupCommand + "   then log out and back in"
                : "NOT WORKING: no keyboard found under /dev/input");

        Line("Paste shortcut", Uinput.Ready ? "OK (virtual keyboard through /dev/uinput)"
            : TextInjector.CanType ? "OK through " + TextInjector.Method + " (/dev/uinput: " + Uinput.Error + ")"
            : "NOT WORKING: " + Uinput.Error + ". Text will only be copied. Fix: " + MainWindow.UinputRuleCommand);
        if (TextInjector.ClipboardNeedsWlCopy)
            Line("Clipboard", "NEEDS wl-clipboard: on this Wayland desktop Tokalot's clipboard may not reach your apps, so it will only copy, not paste. Install wl-clipboard.");
        else Line("Clipboard", "built in" + string.Concat(new[] { "wl-copy", "wl-paste", "xclip", "xsel" }.Where(Sh.Has).Select(t => ", " + t + " installed")));
        Line("Microphone recorder", Recorder.Tool ?? "NOT FOUND: install PipeWire (pw-record), pulseaudio-utils (parec) or alsa-utils (arecord)");
        Line("Sound player", Sh.First("pw-play", "paplay", "aplay") ?? "not found (no feedback tones or playback)");
        Line("API key storage", KeyStore.Backend == KeyStore.Keyring ? "login keyring (secret-tool)" : "keys.json in the data folder, mode 0600");
        Line("Active-app detection", Sh.IsWayland ? "off (Wayland doesn't expose the focused window)" : X11.Available ? "OK (X11)" : Sh.First("xdotool", "xprop") ?? "not available");
        if (downloadModel && !ModelManager.IsReady)
        {
            Console.WriteLine("Downloading the offline model (60 MB)…");
            var err = ModelManager.Download(new Progress<int>()).GetAwaiter().GetResult();
            if (err != null) Console.WriteLine("Download failed: " + err);
        }
        Line("Offline engine", EngineCheck());
        if (!ModelManager.IsReady) Line("Offline model", "not downloaded (run with --diagnose --download-model to fetch and test it)");
        else
        {
            // Two seconds of a quiet tone through the real engine: proves the native library loads on this machine.
            try
            {
                var clip = new float[Recorder.SampleRate * 2];
                for (int i = 0; i < clip.Length; i++) clip[i] = (float)(0.01 * Math.Sin(i * 2 * Math.PI * 220 / Recorder.SampleRate));
                var sw = System.Diagnostics.Stopwatch.StartNew();
                using var whisper = new LocalWhisper();
                var text = whisper.Transcribe(clip, "", true).GetAwaiter().GetResult();
                Line("Offline model", $"OK (engine ran a test clip in {sw.ElapsedMilliseconds} ms, heard \"{text.Trim()}\")");
            }
            catch (Exception e) { Line("Offline model", "downloaded, but the engine FAILED: " + e.Message); }
        }
        Uinput.Dispose();
    }
}
