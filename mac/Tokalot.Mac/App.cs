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
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;
using Tokalot.Desktop.UI;

namespace Tokalot.Desktop;

/**
 * Tokalot for Mac lives in the menu bar. Hold Ctrl+Cmd anywhere, talk, let go: the text is
 * cleaned up and pasted where your cursor is. A quick tap of Ctrl+Cmd starts hands-free mode.
 * Everything but start-up, the menu-bar icon and the command-line checks is shared with the Linux
 * app (App.Shared.cs, Controller.cs and the UI folder, linked from linux/Tokalot.Linux).
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
        // "Tokalot --diagnose" prints what this Mac lets Tokalot do (keyboard, paste, microphone…) and exits.
        if (args.Contains("--diagnose")) { Diagnose(args.Contains("--download-model")); return 0; }

        // Developer and CI check of the native pieces (Keychain, clipboard, keyboard tap, microphone…). Prints PASS/FAIL lines.
        if (args.Contains("--selftest")) return SelfTest.Run(args);

        // "Tokalot --transcribe <file.wav>" runs a recording through the same pipeline a dictation takes and prints the text.
        if (args.Length >= 2 && args[0] == "--transcribe") return SelfTest.Transcribe(args[1]);

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

        // One copy per data folder. (Opening Tokalot.app again normally just brings the running copy forward;
        // this covers starting the program itself twice.)
        try
        {
            instanceLock = new FileStream(Paths.File("tokalot.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException)
        {
            try
            {
                using var s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                s.Connect(new UnixDomainSocketEndPoint(ShowSocket));
            }
            catch { }
            return 0;
        }
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log("Crash: " + e.ExceptionObject);
        themeProbe = Task.Run(C.ProbeSystem);
        KeyStore.Preload(Settings.Current);
        background = args.Contains("--background");
        software = args.Contains("--software");

        // No Dock icon: Tokalot lives in the menu bar (Info.plist says the same with LSUIElement).
        var builder = AppBuilder.Configure<App>().UsePlatformDetect().WithInterFont()
            .With(new MacOSPlatformOptions { ShowInDock = false });
        // "--software" draws without the graphics card, for Macs where the window comes up blank.
        if (software) builder = builder.With(new AvaloniaNativePlatformOptions { RenderingMode = new[] { AvaloniaNativeRenderingMode.Software } });
        return builder.StartWithClassicDesktopLifetime(args, ShutdownMode.OnExplicitShutdown);
    }

    private static string ShowSocket => Paths.File("show.sock");

    private TrayIcon? tray;
    private bool askedForAccessibility;

    private void OnStartup()
    {
        var s = Settings.Current;
        try { themeProbe?.Wait(150); } catch { }
        Log($"Start {Updater.CurrentVersion} · {Native.MacVersion()} · {System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture} · bundle={Startup.Bundle ?? "none"} · software={software}");
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
            Log("Task error: " + e.Exception);
        };
        KeyStore.Changed += () => Dispatcher.UIThread.Post(() =>
        {
            if (window?.CurrentPage is MainWindow.Page.Home or MainWindow.Page.Settings) window.Render();
        });
        themeProbe?.ContinueWith(_ => Dispatcher.UIThread.Post(FollowSystemTheme));

        Controller = new Controller();
        SetUpTray();
        Log($"Permissions: input monitoring={Permissions.InputMonitoring}, accessibility={Permissions.Accessibility}, microphone={Permissions.Microphone}, keyboard tap={Controller.HotkeyWorks}");

        if (Startup.Supported) Startup.Apply(s.LaunchAtStartup);

        // Opening Tokalot.app (Finder, Spotlight, Launchpad) while it already runs brings its window up.
        if (TryGetFeature(typeof(IActivatableLifetime)) is IActivatableLifetime life)
            life.Activated += (_, e) => { if (e.Kind == ActivationKind.Reopen) Dispatcher.UIThread.Post(ShowWindow); };

        // A second start of the program itself signals this one to show the window.
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

        // "Match system" follows macOS when it switches between light and dark.
        try
        {
            if (PlatformSettings is { } ps)
                ps.ColorValuesChanged += (_, _) => Task.Run(C.ProbeSystem).ContinueWith(_ => Dispatcher.UIThread.Post(FollowSystemTheme));
        }
        catch { }

        if (!background)
        {
            ShowWindow();
            // The first time, macOS shows its own "receive keystrokes" prompt, which puts Tokalot in the
            // Input Monitoring list; later starts just get the answer. Home names what is still missing.
            if (!Controller.HotkeyWorks && !Paths.IsTestInstance) Permissions.RequestInputMonitoring();
        }
        else if (!Controller.HotkeyWorks)
            Sh.Notify("Tokalot", "Tokalot can't see Ctrl+Cmd yet. Open Tokalot to see how to allow it.");

        _ = Sync.Run(); // no-op unless sync is on
        _ = CheckForUpdatesLoop();
    }

    /** Before the first paste without Accessibility, macOS's own prompt is shown once (it adds Tokalot to the list). */
    internal void AskForAccessibilityOnce()
    {
        if (askedForAccessibility || Paths.IsTestInstance) return;
        askedForAccessibility = true;
        Permissions.RequestAccessibility();
    }

    // ---------- menu bar ----------

    private void SetUpTray()
    {
        try
        {
            tray = new TrayIcon
            {
                ToolTipText = "Tokalot: hold Ctrl+Cmd to dictate",
                Icon = MenuBarIcon(),
                IsVisible = true,
            };
            // A template icon: macOS draws it black or white to match the menu bar.
            MacOSProperties.SetIsTemplateIcon(tray, true);
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
            Log("Menu bar icon added");
        }
        catch (Exception e) { Log("Menu bar icon unavailable: " + e.Message); }
    }

    /** The app icon's five bars, drawn in black on clear for the menu bar (macOS recolors template icons). */
    internal static WindowIcon MenuBarIcon()
    {
        const int size = 44;
        using var bmp = new RenderTargetBitmap(new PixelSize(size, size), new Vector(96, 96));
        using (var dc = bmp.CreateDrawingContext())
        {
            double[] heights = { 0.36, 0.7, 1.0, 0.62, 0.3 };
            double bar = 5.5, gap = 3.2, total = heights.Length * bar + (heights.Length - 1) * gap, x = (size - total) / 2, full = 34;
            foreach (var h in heights)
            {
                double hh = full * h;
                dc.DrawRectangle(Brushes.Black, null, new Rect(x, (size - hh) / 2, bar, hh), bar / 2, bar / 2);
                x += bar + gap;
            }
        }
        var ms = new MemoryStream();
        bmp.Save(ms);
        ms.Position = 0;
        return new WindowIcon(ms);
    }

    public void Quit()
    {
        Controller?.Dispose();
        try { if (tray != null) { tray.IsVisible = false; tray.Dispose(); } } catch { }
        try { File.Delete(ShowSocket); } catch { }
        KeyStore.Flush();
        (ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.Shutdown();
    }

    // ---------- diagnose ----------

    /** Where the speech engine's native libraries are for this Mac (Whisper.net names the folder macos-arm64 / macos-x64). */
    internal static string EngineDir => Path.Combine(AppContext.BaseDirectory, "runtimes",
        System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture == System.Runtime.InteropServices.Architecture.Arm64 ? "macos-arm64" : "macos-x64");

    /** Loads the speech engine's native libraries the way the app will, and names what fails. */
    internal static string EngineCheck()
    {
        var dir = EngineDir;
        if (!Directory.Exists(dir)) return "NOT FOUND: no engine in " + dir;
        foreach (var lib in new[] { "libggml-base-whisper.dylib", "libggml-cpu-whisper.dylib", "libggml-blas-whisper.dylib", "libggml-metal-whisper.dylib", "libggml-whisper.dylib", "libwhisper.dylib" })
        {
            var path = Path.Combine(dir, lib);
            if (!File.Exists(path)) continue;
            try { System.Runtime.InteropServices.NativeLibrary.Load(path); }
            catch (Exception e) { return "NOT WORKING: " + lib + " won't load (" + e.Message.Replace("\n", " ") + ")"; }
        }
        return "OK (libraries load)";
    }

    /** A plain-text report of what works on this Mac, for setup and bug reports. Starts no windows. */
    private static void Diagnose(bool downloadModel)
    {
        void Line(string k, string v) => Console.WriteLine($"{k,-22} {v}");
        Line("Tokalot", Updater.CurrentVersion);
        Line("System", Native.MacVersion() + " · " + System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture);
        Line("Data folder", Paths.Root);
        Line("App bundle", Startup.Bundle ?? "not running from Tokalot.app (start at login is unavailable)");

        using (var hook = new HotkeyHook())
            Line("Ctrl+Cmd shortcut", hook.Installed ? "OK (Input Monitoring allowed; keyboard tap running)"
                : "NOT WORKING: allow Tokalot under System Settings › Privacy & Security › Input Monitoring"
                  + (Permissions.InputMonitoring ? " (macOS says it is allowed, but the tap couldn't be made: switch it off and on again, then restart Tokalot)" : ""));
        Line("Paste shortcut", TextInjector.CanType ? "OK (Accessibility allowed; ⌘V can be pressed)"
            : "NOT WORKING: allow Tokalot under System Settings › Privacy & Security › Accessibility. Text will only be copied.");
        Line("Clipboard", TextInjector.ClipboardMethod);
        var mic = Permissions.Microphone;
        Line("Microphone", "permission " + mic switch
        {
            Permissions.Mic.Allowed => "allowed",
            Permissions.Mic.NotAsked => "not asked yet (macOS asks on the first recording)",
            Permissions.Mic.Denied => "DENIED (System Settings › Privacy & Security › Microphone)",
            Permissions.Mic.Restricted => "RESTRICTED by a profile",
            _ => "unknown",
        } + " · input device: " + (Recorder.InputDevice() ?? "NONE FOUND"));
        Line("Sound player", File.Exists("/usr/bin/afplay") ? "afplay" : "not found (no feedback tones or playback)");
        Line("API key storage", KeyStore.Backend == KeyStore.Keyring ? "login Keychain" : "keys.json in the data folder, mode 0600");
        Line("Active-app detection", AppDetect.Frontmost() is { } f ? $"OK (in front now: {f.Name})" : "not available");
        Line("Window titles", Permissions.Accessibility ? "readable (browser tabs get their site's style)" : "not readable without Accessibility (browsers use \"Everything else\")");
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
            // Two seconds of a quiet tone through the real engine: proves the native library loads on this Mac.
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
    }
}
