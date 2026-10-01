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
public sealed class App : Application
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

    public static new App Current => (App)Application.Current!;

    public Controller? Controller { get; private set; }
    private MainWindow? window;
    private TrayIcon? tray;
    private NativeMenuItem? updateItem;

    /** Any of Tokalot's windows (the clipboard is reached through one). */
    internal Window? AnyWindow => (Window?)window ?? Controller?.AnyWindow;

    public override void Initialize()
    {
        // Base control templates (text boxes, menus, scroll viewers). Tokalot's own look is layered on in Ui.MenuStyles().
        Styles.Add(new FluentTheme());
    }

    public override void OnFrameworkInitializationCompleted()
    {
        base.OnFrameworkInitializationCompleted();
        if (shotsDir != null) return; // Shots.Run drives everything itself
        OnStartup();
    }

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

    private void FollowSystemTheme()
    {
        if (Settings.Current.Theme == "system" && C.SystemIsDark() != C.Dark) ApplyTheme();
    }

    /** Palette, light/dark variant for the stock controls, and Tokalot's styles on top. */
    private void ApplyLook()
    {
        C.Apply(Settings.Current.Theme);
        RequestedThemeVariant = C.Dark ? ThemeVariant.Dark : ThemeVariant.Light;
        Resources = Ui.MenuStyles();
    }

    // ---------- window ----------

    public void ShowWindow()
    {
        if (window == null)
        {
            window = new MainWindow();
            var mine = window;
            window.Closed += (_, _) => { if (window == mine) window = null; };
            window.Opened += (_, _) => Log("Window rendered");
        }
        window.Show();
        if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
        window.Activate();
    }

    /** Re-applies the theme (light/dark) by rebuilding the window. */
    public void ApplyTheme()
    {
        ApplyLook();
        if (window == null) return;
        var old = window;
        var page = old.CurrentPage;
        var state = old.WindowState;
        window = new MainWindow { WindowStartupLocation = WindowStartupLocation.Manual };
        if (state == WindowState.Normal)
        {
            window.Position = old.Position;
            window.Width = old.Bounds.Width;
            window.Height = old.Bounds.Height;
        }
        else window.WindowState = state;
        var mine = window;
        window.Closed += (_, _) => { if (window == mine) window = null; };
        window.Go(page);
        window.Show();
        old.Close();
    }

    public void Refresh() => window?.Render();

    /** After a restore: the theme, indicator and start-up setting all follow the restored settings. */
    public void AfterRestore(string message)
    {
        ApplyTheme();
        Controller?.RefreshIndicator();
        if (Startup.Supported) Startup.Apply(Settings.Current.LaunchAtStartup);
        KeyStore.Preload(Settings.Current);
        window?.Render();
        window?.ShowToast(message);
    }

    /** Home shows the new entry and stats. Other pages are left alone so a half-typed snippet or word list isn't wiped. */
    public void RefreshAfterDictation()
    {
        if (window?.CurrentPage == UI.MainWindow.Page.Home) window.Render();
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

    // ---------- updates ----------

    public string? UpdateVersion { get; private set; }
    public int? UpdateProgress { get; private set; }

    private async Task CheckForUpdatesLoop()
    {
        await Task.Delay(TimeSpan.FromSeconds(8));
        while (true)
        {
            await CheckForUpdates();
            await Task.Delay(TimeSpan.FromHours(6));
        }
    }

    public async Task<string?> CheckForUpdates()
    {
        var v = await Updater.Check();
        if (v != null && v != UpdateVersion)
        {
            UpdateVersion = v;
            if (updateItem != null) { updateItem.Header = $"Update to {v}"; updateItem.IsVisible = true; }
            Refresh();
        }
        return v;
    }

    public async Task InstallUpdate()
    {
        if (UpdateVersion == null || UpdateProgress != null) return;
        UpdateProgress = 0;
        Refresh();
        try
        {
            await Updater.DownloadAndRestart(p => Dispatcher.UIThread.Post(() =>
            {
                UpdateProgress = p;
                window?.SetBannerProgress(p);
            }));
        }
        catch (Exception e)
        {
            UpdateProgress = null;
            Log("Update failed: " + e.Message);
            await Ui.Dialog(window, "The update couldn't be installed: " + e.Message, cancel: null);
            Refresh();
        }
    }

    public static void Log(string line)
    {
        try { File.AppendAllText(Paths.File("log.txt"), $"{DateTime.Now:u} {line}{Environment.NewLine}"); } catch { }
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

/**
 * The hotkey → record → transcribe → paste loop.
 *   Hold Ctrl+Super: records while held, finishes on release.
 *   Tap Ctrl+Super (under 350 ms): hands-free; tap again to finish. Esc cancels either way.
 *   Ctrl+Super+another key quickly (a desktop shortcut): quietly cancels, so shortcuts keep working.
 */
public sealed class Controller : IDisposable
{
    private enum State { Idle, Recording, Processing }

    private const int TapMs = 350;          // shorter press = hands-free
    private const int ShortcutWindowMs = 600; // another key this soon = a desktop shortcut, not dictation
    private const int AutoStopSeconds = 30;

    private readonly Dispatcher ui = Dispatcher.UIThread;
    private readonly HotkeyHook hook;
    private readonly Recorder recorder = new();
    private readonly Dictation dictation = new();
    private readonly Pill pill;               // short messages, shown next to the indicator
    private readonly IndicatorWindow indicator;
    private readonly DispatcherTimer tick;
    private State state = State.Idle;
    private bool handsFree;
    private bool ignoreNextRelease;
    private DateTime pressedAt;
    private ActiveApp? app;
    private Task<ActiveApp?>? appLookup;   // which app has focus, found in the background while recording
    private CancellationTokenSource? work; // the transcription in progress, so Esc can stop it
    private bool userCancelled;

    public Controller()
    {
        pill = new Pill(() => 0);
        indicator = new IndicatorWindow(() => recorder.Level);
        indicator.Clicked += OnClicked;
        TextInjector.Init();
        hook = new HotkeyHook();
        hook.Pressed += () => ui.Post(OnPressed);
        hook.Released += () => ui.Post(OnReleased);
        hook.KeyWhileHeld += vk => ui.Post(() => OnOtherKey(vk));
        hook.Escape += () => ui.Post(() => { if (state == State.Processing) CancelWork(); else Cancel(true); });
        tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        tick.Tick += (_, _) => Tick();
    }

    public bool HotkeyWorks => hook.Installed;

    internal Window AnyWindow => pill;

    /** Re-reads the indicator's style, edge and idle visibility (after a Settings change). */
    public void RefreshIndicator() => indicator.ApplySettings();

    private void Say(string text, int ms) => pill.FlashNear(text, indicator.ScreenBounds, indicator.Dock, ms);

    private void OnPressed()
    {
        if (state == State.Recording && handsFree)
        {
            ignoreNextRelease = true;
            Finish();
            return;
        }
        if (state != State.Idle) return;
        Begin(handsFreeMode: false);
    }

    /** Clicking the indicator: start hands-free, or finish. Focus stays in your app. */
    private void OnClicked()
    {
        if (state == State.Recording) { Finish(); return; }
        if (state == State.Processing) { CancelWork(); return; }
        if (state != State.Idle) return;
        if (Begin(handsFreeMode: true)) Say("Listening · click again or press Ctrl+Super to finish", 2200);
    }

    private bool Begin(bool handsFreeMode)
    {
        // Finding the focused app can mean starting a helper program; it runs beside the recording and is
        // only needed once the recording is over.
        app = null;
        appLookup = Task.Run(AppDetect.Detect);
        recorder.CueSamples = Settings.Current.Sounds ? Recorder.SampleRate * 6 / 10 : 0;
        if (!recorder.Start())
        {
            Sounds.Play(Sounds.Kind.Error);
            Say(Recorder.Tool == null
                ? "No recorder found. Install PipeWire, pulseaudio-utils or alsa-utils."
                : "No microphone found. Check your sound settings.", 4000);
            return false;
        }
        state = State.Recording;
        handsFree = handsFreeMode;
        pressedAt = DateTime.UtcNow;
        hook.Listening = true;
        dictation.WarmUp();
        Sounds.Play(Sounds.Kind.Start);
        indicator.SetMode(IndicatorView.Mode.Listening);
        tick.Start();
        return true;
    }

    private void OnReleased()
    {
        if (ignoreNextRelease) { ignoreNextRelease = false; return; }
        if (state != State.Recording || handsFree) return;
        if ((DateTime.UtcNow - pressedAt).TotalMilliseconds < TapMs)
        {
            handsFree = true;
            Say("Hands-free · Ctrl+Super to finish · Esc to cancel", 2600);
            return;
        }
        Finish();
    }

    private void OnOtherKey(int vk)
    {
        if (state != State.Recording || handsFree) return;
        // Ctrl+Super+D, Ctrl+Super+Arrow, etc.: the user meant a desktop shortcut.
        if ((DateTime.UtcNow - pressedAt).TotalMilliseconds < ShortcutWindowMs) Cancel(false);
    }

    private void Tick()
    {
        if (state != State.Recording) { tick.Stop(); return; }
        var s = Settings.Current;
        // In hold mode, ask the keyboards what is really held, in case a key-up never arrived.
        if (!handsFree) hook.Poll();
        if (recorder.Full)
        {
            Say("Reached the 10 minute limit. Transcribing…", 3000);
            Finish();
        }
        else if (handsFree && s.AutoStop && (DateTime.UtcNow - recorder.LastVoiceAt).TotalSeconds > AutoStopSeconds)
            Finish(trimSilence: true);
        // Hold mode, but the keys are up and the release never arrived (the keyboard was unplugged mid-press).
        else if (!handsFree && !HotkeyHook.ComboHeld && (DateTime.UtcNow - pressedAt).TotalMilliseconds > ShortcutWindowMs)
            Finish();
    }

    /** Stops recording without transcribing. */
    public void Cancel(bool audible)
    {
        if (state != State.Recording) return;
        var samples = recorder.Stop();
        Reset();
        app = FoundApp();
        indicator.SetMode(IndicatorView.Mode.Idle);
        if (audible)
        {
            Sounds.Play(Sounds.Kind.Cancel);
            // Esc may have been meant for another app: anything longer than a few seconds is kept so it can still be transcribed.
            if (samples.Length >= Recorder.SampleRate * 3)
            {
                dictation.KeepAudio(samples, null, "Cancelled", app, cancelled: true);
                Say("Cancelled · the recording is in history", 2400);
            }
            else Say("Cancelled", 1200);
        }
    }

    /** The app found by the background look-up, if it has finished. */
    private ActiveApp? FoundApp() => appLookup is { IsCompletedSuccessfully: true } t ? t.Result : app;

    /** Stops a transcription in progress (Esc, or clicking the indicator). */
    private void CancelWork()
    {
        if (work == null) return;
        userCancelled = true;
        try { work.Cancel(); } catch { }
    }

    /** Gives up after a while even on a dead connection: a minute plus twice the recording's length. */
    private CancellationToken StartWork(int sampleCount)
    {
        work = new CancellationTokenSource();
        userCancelled = false;
        work.CancelAfter(TimeSpan.FromSeconds(60 + 2.0 * sampleCount / Recorder.SampleRate));
        hook.Listening = true; // so Esc is reported while working
        return work.Token;
    }

    private void EndWork()
    {
        hook.Listening = false;
        work?.Dispose();
        work = null;
    }

    /** Transcribes a saved recording again (one that failed or was cancelled) and copies the result. */
    public async void Retry(Entry e)
    {
        if (state != State.Idle) return;
        state = State.Processing;
        indicator.SetMode(IndicatorView.Mode.Working);
        try
        {
            var samples = await Task.Run(() => AudioStore.Load(e.Id));
            if (samples == null || samples.Length == 0) throw new InvalidOperationException("That recording couldn't be read");
            var ct = StartWork(samples.Length);
            var target = e.AppKey.Length > 0 ? new ActiveApp(e.AppKey, e.AppLabel) : null;
            var outcome = await Task.Run(() => dictation.Process(samples, target, ct: ct, entryId: e.Id));
            indicator.SetMode(IndicatorView.Mode.Idle);
            if (outcome.Text.Length > 0)
            {
                await TextInjector.Copy(outcome.Text);
                Sounds.Play(Sounds.Kind.Done);
                Say("Transcribed · copied to the clipboard", 2600);
            }
            else Say("Didn't catch anything", 1800);
        }
        catch (OperationCanceledException) when (userCancelled)
        {
            indicator.SetMode(IndicatorView.Mode.Idle);
            Say("Cancelled", 1200);
        }
        catch (Exception ex)
        {
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say(ex is OperationCanceledException ? "Timed out" : ex.Message, 4500);
        }
        finally
        {
            EndWork();
            state = State.Idle;
            App.Current.RefreshAfterDictation();
        }
    }

    private void Reset()
    {
        state = State.Idle;
        handsFree = false;
        hook.Listening = false;
        tick.Stop();
    }

    private async void Finish(bool trimSilence = false)
    {
        if (state != State.Recording) return;
        // Stopping lets the recorder hand over its last fraction of a second, which can take a moment:
        // it is done off the window's thread. The state changes first, so nothing else starts meanwhile.
        state = State.Processing;
        hook.Listening = false;
        tick.Stop();
        handsFree = false;
        var samples = await Task.Run(() => recorder.Stop());
        var lastVoice = recorder.LastVoiceSample;
        int speech = recorder.SpeechChunks, lateSpeech = recorder.LateSpeechChunks;
        try { if (appLookup != null) app = await appLookup; } catch { }

        // Auto-stop: drop the long silent tail (keep half a second after the last speech).
        if (trimSilence && lastVoice > 0)
        {
            var keep = Math.Min(samples.Length, lastVoice + Recorder.SampleRate / 2);
            samples = samples[..keep];
        }

        if (samples.Length < Recorder.SampleRate * 3 / 10)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Say("Too short. Hold Ctrl+Super while you talk.", 2200);
            return;
        }
        float peak = 0;
        foreach (var v in samples) { var a = Math.Abs(v); if (a > peak) peak = a; }
        if (peak < 0.0005f)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say("The mic heard nothing. Check the input device in your sound settings.", 4500);
            return;
        }

        // A quick press with nothing said: only the start tone or room noise got in, and Whisper
        // would turn that into "Thank you." Six chunks is 300 ms of sound.
        var heard = lateSpeech >= 2 || speech >= 6;
        if (!heard && samples.Length < Recorder.SampleRate * 5 / 2)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Say("Didn't catch anything", 1800);
            return;
        }

        state = State.Processing;
        Sounds.Play(Sounds.Kind.Stop);
        indicator.SetMode(IndicatorView.Mode.Working);
        var target = app;
        var ct = StartWork(samples.Length);
        try
        {
            var outcome = await Task.Run(() => dictation.Process(samples, target, sparse: speech < 6, ct: ct));
            hook.Listening = false;
            var pasted = TextInjector.Result.Pasted;
            if (outcome.Text.Length > 0)
            {
                pasted = await TextInjector.Paste(outcome.Text, target);
                // The "done" tone means the text is in your app; when it could only be copied, the message says so instead.
                if (pasted == TextInjector.Result.Pasted) Sounds.Play(Sounds.Kind.Done);
                else if (pasted == TextInjector.Result.Failed) Sounds.Play(Sounds.Kind.Error);
                if (pasted != TextInjector.Result.Pasted) App.Log("Paste: " + pasted + " (keys: " + TextInjector.Method + ", clipboard: " + TextInjector.ClipboardMethod + ")");
            }
            indicator.SetMode(IndicatorView.Mode.Idle);
            if (outcome.Warning != null) Say(outcome.Warning, 3500);
            else if (outcome.Text.Length == 0) Say("Didn't catch anything", 1800);
            // Linux only: Tokalot couldn't press the paste shortcut itself (see Settings › Setup).
            else if (pasted == TextInjector.Result.Copied) Say(TextInjector.ClipboardNeedsWlCopy ? "Copied · press Ctrl+V (install wl-clipboard to paste automatically)" : "Copied · press Ctrl+V", 3500);
            else if (pasted == TextInjector.Result.Failed) Say("Couldn't reach the clipboard. The text is in Tokalot's history.", 4500);
            dictation.KeepAudio(samples, outcome.EntryId, null, target);
        }
        catch (OperationCanceledException) when (userCancelled)
        {
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Cancel);
            Say("Cancelled · the recording is in history", 2400);
            dictation.KeepAudio(samples, null, "Cancelled", target, cancelled: true);
        }
        catch (Exception e)
        {
            var message = e is OperationCanceledException ? "Timed out" : e.Message;
            App.Log("Dictation failed: " + e);
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say(message + " · the recording is in history", 4500);
            dictation.KeepAudio(samples, null, message, target);
        }
        finally
        {
            EndWork();
            state = State.Idle;
            App.Current.RefreshAfterDictation();
        }
    }

    public void Dispose()
    {
        hook.Dispose();
        recorder.Dispose();
        dictation.Dispose();
        Player.Stop(false);
        pill.Close();
        indicator.Close();
    }
}
