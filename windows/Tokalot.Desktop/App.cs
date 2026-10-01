using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;
using Tokalot.Desktop.UI;
using Velopack;

namespace Tokalot.Desktop;

/**
 * Tokalot for Windows lives in the tray. Hold Ctrl+Win anywhere, talk, let go: the text is
 * cleaned up and pasted where your cursor is. A quick tap of Ctrl+Win starts hands-free mode.
 */
public sealed class App : Application
{
    // A copy started with its own TOKALOT_DATA folder (for testing) runs beside the installed one.
    private static readonly string Instance = Environment.GetEnvironmentVariable("TOKALOT_DATA") is { Length: > 0 } ? ".Test" : "";
    private static readonly string ShowSignal = "Tokalot.Desktop.ShowWindow" + Instance;

    [STAThread]
    public static void Main(string[] args)
    {
        // Must run first: lets the installer/updater hook into startup.
        VelopackApp.Build().Run();

        // Developer tool: "Tokalot.exe --screenshots <folder>" renders every page to PNGs with sample data.
        if (args.Length >= 2 && args[0] == "--screenshots")
        {
            Environment.SetEnvironmentVariable("TOKALOT_DATA", Path.Combine(Path.GetTempPath(), "tokalot-shots-" + Guid.NewGuid().ToString("N")));
            new App { ShutdownMode = ShutdownMode.OnExplicitShutdown, shotsDir = args[1] }.Run();
            return;
        }

        using var single = new Mutex(true, "Tokalot.Desktop.SingleInstance" + Instance, out var first);
        if (!first)
        {
            // Already running (probably in the tray): ask it to show its window instead.
            try { EventWaitHandle.OpenExisting(ShowSignal).Set(); } catch { }
            return;
        }
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log("Crash: " + e.ExceptionObject);
        var app = new App
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown,
            background = args.Contains("--background"),
            gpu = args.Contains("--gpu"),
        };
        app.Run();
    }

    public static new App Current => (App)Application.Current;

    public Controller? Controller { get; private set; }
    private MainWindow? window;
    private System.Windows.Forms.NotifyIcon? tray;
    private System.Windows.Forms.ToolStripMenuItem? updateItem;

    private bool background, gpu;
    private string? shotsDir;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var s = Settings.Current;
        // Some graphics drivers present WPF windows as blank white (seen on an NVIDIA 4K setup).
        // Tokalot's UI is simple, so drawing it on the CPU costs nothing noticeable and always works.
        // "--gpu" switches back to hardware rendering for testing.
        if (!gpu) System.Windows.Media.RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.SoftwareOnly;
        if (shotsDir != null)
        {
            try { Shots.Run(shotsDir); } catch (Exception ex) { Log("Screenshots failed: " + ex); }
            Shutdown();
            return;
        }
        Log($"Start {Updater.CurrentVersion} · render tier {System.Windows.Media.RenderCapability.Tier >> 16} · gpu={gpu} · {Environment.OSVersion}");
        C.Apply(s.Theme);
        DispatcherUnhandledException += (_, e) =>
        {
            Log("UI error: " + e.Exception);
            e.Handled = true;
        };
        TaskScheduler.UnobservedTaskException += (_, e) => { Log("Task error: " + e.Exception); e.SetObserved(); };

        Controller = new Controller(Dispatcher);
        SetUpTray();

        // Keep "start with Windows" pointing at the installed exe (its path changes on update).
        if (Updater.CanUpdate)
        {
            Platform.Startup.Apply(s.LaunchAtStartup);
            EnsureStartMenuShortcut();
        }

        // A second launch signals this one to show the window.
        var signal = new EventWaitHandle(false, EventResetMode.AutoReset, ShowSignal);
        new Thread(() => { while (signal.WaitOne()) Dispatcher.BeginInvoke(ShowWindow); }) { IsBackground = true }.Start();

        if (!background) ShowWindow();
        else if (!Controller!.HotkeyWorks)
            tray?.ShowBalloonTip(6000, "Tokalot", "Couldn't listen for Ctrl+Win. Try restarting Tokalot.", System.Windows.Forms.ToolTipIcon.Warning);

        _ = CheckForUpdatesLoop();
    }

    /** A Start menu entry, so Tokalot can be found by searching "Tokalot" (the silent installer skips it). */
    private static void EnsureStartMenuShortcut()
    {
        try
        {
            var lnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Tokalot.lnk");
            if (File.Exists(lnk)) return;
            // The launcher one folder up survives updates; fall back to this exe.
            var stub = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "Tokalot.exe"));
            var target = File.Exists(stub) ? stub : Environment.ProcessPath;
            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (target == null || shellType == null) return;
            dynamic shell = Activator.CreateInstance(shellType)!;
            dynamic sc = shell.CreateShortcut(lnk);
            sc.TargetPath = target;
            sc.WorkingDirectory = Path.GetDirectoryName(target);
            sc.IconLocation = Path.Combine(AppContext.BaseDirectory, "Assets", "icon.ico");
            sc.Description = "Voice typing: hold Ctrl+Win";
            sc.Save();
        }
        catch (Exception e) { Log("Start menu shortcut failed: " + e.Message); }
    }

    // ---------- window ----------

    public void ShowWindow()
    {
        if (window == null)
        {
            window = new MainWindow();
            window.Closed += (_, _) => window = null;
            window.ContentRendered += (_, _) => Log("Window rendered");
        }
        window.Show();
        if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
        window.Activate();
    }

    /** Re-applies the theme (light/dark) by rebuilding the window. */
    public void ApplyTheme()
    {
        C.Apply(Settings.Current.Theme);
        if (window == null) return;
        var bounds = new Rect(window.Left, window.Top, window.Width, window.Height);
        var page = window.CurrentPage;
        window.Close();
        window = new MainWindow { Left = bounds.Left, Top = bounds.Top, Width = bounds.Width, Height = bounds.Height, WindowStartupLocation = WindowStartupLocation.Manual };
        window.Closed += (_, _) => window = null;
        window.Go(page);
        window.Show();
    }

    public void Refresh() => window?.Render();

    // ---------- tray ----------

    private void SetUpTray()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "icon.ico");
        tray = new System.Windows.Forms.NotifyIcon
        {
            Text = "Tokalot: hold Ctrl+Win to dictate",
            Icon = File.Exists(iconPath) ? new System.Drawing.Icon(iconPath) : System.Drawing.SystemIcons.Application,
            Visible = true,
        };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Open Tokalot", null, (_, _) => ShowWindow());
        menu.Items.Add("Copy last dictation", null, (_, _) =>
        {
            var last = History.All().FirstOrDefault();
            if (last != null) _ = TextInjector.Copy(last.Text);
        });
        updateItem = new System.Windows.Forms.ToolStripMenuItem("Update available", null, (_, _) => { ShowWindow(); }) { Visible = false };
        menu.Items.Add(updateItem);
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Quit Tokalot", null, (_, _) => Quit());
        tray.ContextMenuStrip = menu;
        tray.MouseClick += (_, e) => { if (e.Button == System.Windows.Forms.MouseButtons.Left) ShowWindow(); };
    }

    public void Quit()
    {
        Controller?.Dispose();
        if (tray != null) { tray.Visible = false; tray.Dispose(); }
        Shutdown();
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
            if (updateItem != null) { updateItem.Text = $"Update to {v}"; updateItem.Visible = true; }
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
            await Updater.DownloadAndRestart(p => Dispatcher.BeginInvoke(() =>
            {
                UpdateProgress = p;
                window?.SetBannerProgress(p);
            }));
        }
        catch (Exception e)
        {
            UpdateProgress = null;
            Log("Update failed: " + e.Message);
            MessageBox.Show("The update couldn't be installed: " + e.Message, "Tokalot");
            Refresh();
        }
    }

    public static void Log(string line)
    {
        try { File.AppendAllText(Paths.File("log.txt"), $"{DateTime.Now:u} {line}{Environment.NewLine}"); } catch { }
    }
}

/**
 * The hotkey → record → transcribe → paste loop.
 *   Hold Ctrl+Win: records while held, finishes on release.
 *   Tap Ctrl+Win (under 350 ms): hands-free; tap again to finish. Esc cancels either way.
 *   Ctrl+Win+another key quickly (a Windows shortcut): quietly cancels, so shortcuts keep working.
 */
public sealed class Controller : IDisposable
{
    private enum State { Idle, Recording, Processing }

    private const int TapMs = 350;          // shorter press = hands-free
    private const int ShortcutWindowMs = 600; // another key this soon = a Windows shortcut, not dictation
    private const int AutoStopSeconds = 30;

    private readonly Dispatcher ui;
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

    public Controller(Dispatcher ui)
    {
        this.ui = ui;
        pill = new Pill(() => 0);
        indicator = new IndicatorWindow(() => recorder.Level);
        indicator.Clicked += OnClicked;
        hook = new HotkeyHook();
        hook.Pressed += () => ui.BeginInvoke(OnPressed);
        hook.Released += () => ui.BeginInvoke(OnReleased);
        hook.KeyWhileHeld += vk => ui.BeginInvoke(() => OnOtherKey(vk));
        hook.Escape += () => ui.BeginInvoke(() => Cancel(true));
        tick = new DispatcherTimer(TimeSpan.FromMilliseconds(250), DispatcherPriority.Normal, (_, _) => Tick(), ui);
        tick.Stop();
    }

    public bool HotkeyWorks => hook.Installed;

    /** Re-reads the indicator's style, edge and idle visibility (after a Settings change). */
    public void RefreshIndicator() => indicator.ApplySettings();

    private void Say(string text, int ms) => pill.FlashNear(text, indicator.Bounds, indicator.Dock, ms);

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
        if (state != State.Idle) return;
        if (Begin(handsFreeMode: true)) Say("Listening · click again or press Ctrl+Win to finish", 2200);
    }

    private bool Begin(bool handsFreeMode)
    {
        app = AppDetect.Detect();
        recorder.CueSamples = Settings.Current.Sounds ? Recorder.SampleRate * 6 / 10 : 0;
        if (!recorder.Start())
        {
            Sounds.Play(Sounds.Kind.Error);
            Say("No microphone found. Check Windows sound settings.", 4000);
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
            Say("Hands-free · Ctrl+Win to finish · Esc to cancel", 2600);
            return;
        }
        Finish();
    }

    private void OnOtherKey(int vk)
    {
        if (state != State.Recording || handsFree) return;
        // Ctrl+Win+D, Ctrl+Win+Arrow, etc.: the user meant a Windows shortcut.
        if ((DateTime.UtcNow - pressedAt).TotalMilliseconds < ShortcutWindowMs) Cancel(false);
    }

    private void Tick()
    {
        if (state != State.Recording) { tick.Stop(); return; }
        var s = Settings.Current;
        if (handsFree && s.AutoStop && (DateTime.UtcNow - recorder.LastVoiceAt).TotalSeconds > AutoStopSeconds)
            Finish(trimSilence: true);
    }

    /** Stops recording without transcribing. */
    public void Cancel(bool audible)
    {
        if (state != State.Recording) return;
        recorder.Stop();
        Reset();
        indicator.SetMode(IndicatorView.Mode.Idle);
        if (audible)
        {
            Sounds.Play(Sounds.Kind.Cancel);
            Say("Cancelled", 1200);
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
        var lastVoice = recorder.LastVoiceSample;
        int speech = recorder.SpeechChunks, lateSpeech = recorder.LateSpeechChunks;
        var samples = recorder.Stop();
        hook.Listening = false;
        tick.Stop();
        handsFree = false;

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
            Say("Too short. Hold Ctrl+Win while you talk.", 2200);
            return;
        }
        float peak = 0;
        foreach (var v in samples) { var a = Math.Abs(v); if (a > peak) peak = a; }
        if (peak < 0.0005f)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say("The mic heard nothing. Check Settings › Privacy › Microphone.", 4500);
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
        try
        {
            var outcome = await Task.Run(() => dictation.Process(samples, target, sparse: speech < 6));
            if (outcome.Text.Length > 0)
            {
                await TextInjector.Paste(outcome.Text);
                Sounds.Play(Sounds.Kind.Done);
            }
            indicator.SetMode(IndicatorView.Mode.Idle);
            if (outcome.Warning != null) Say(outcome.Warning, 3500);
            else if (outcome.Text.Length == 0) Say("Didn't catch anything", 1800);
            dictation.KeepAudio(samples, outcome.EntryId, null, target);
        }
        catch (Exception e)
        {
            App.Log("Dictation failed: " + e);
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say(e.Message, 4500);
            dictation.KeepAudio(samples, null, e.Message, target);
        }
        finally
        {
            state = State.Idle;
            App.Current.Refresh();
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
