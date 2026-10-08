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

    /**
     * True when the installer was run from inside a Microsoft Store-style app (a download opened from
     * within it). Windows then keeps Tokalot in that app's private storage: no Start menu entry, no start
     * at sign-in, and a different settings folder depending on how it was launched.
     */
    public static bool InsideAnotherAppsStorage =>
        Environment.ProcessPath is { } p && p.Contains(@"\Packages\", StringComparison.OrdinalIgnoreCase) && p.Contains(@"\LocalCache\", StringComparison.OrdinalIgnoreCase);

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
        Resources = Ui.MenuStyles();
        // Signing out or shutting down mid-dictation: keep the recording, as Quit does.
        SessionEnding += (_, _) => Controller?.SaveBeforeExit();
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
        _ = Sync.Run(); // no-op unless sync is on
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
        // Opening the window is a good moment to look for an update, if it hasn't been checked lately.
        if (UpdateVersion == null && DateTime.UtcNow - lastUpdateCheck > TimeSpan.FromMinutes(20)) _ = CheckForUpdates();
    }

    /** Re-applies the theme (light/dark) by rebuilding the window. */
    public void ApplyTheme()
    {
        C.Apply(Settings.Current.Theme);
        Resources = Ui.MenuStyles();
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

    /** After a restore: the theme, indicator and start-up setting all follow the restored settings. */
    public void AfterRestore(string message)
    {
        Notes.Reload();
        ApplyTheme();
        Controller?.RefreshIndicator();
        if (Updater.CanUpdate) Platform.Startup.Apply(Settings.Current.LaunchAtStartup);
        window?.Render();
        window?.ShowToast(message);
    }

    /**
     * Home shows the new entry and stats, and Notes a new note. Other pages are left alone so a half-typed
     * snippet or word list isn't wiped.
     */
    public void RefreshAfterDictation()
    {
        if (window != null && window.CurrentPage is UI.MainWindow.Page.Home or UI.MainWindow.Page.Notes) window.Render();
    }


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
            var last = History.All().FirstOrDefault(e => !e.Pending);
            if (last != null) _ = TextInjector.Copy(last.Text);
        });
        // Voice notes (beta): only shown while that's switched on in Settings.
        var noteItem = new System.Windows.Forms.ToolStripMenuItem("New voice note", null, (_, _) => Controller?.StartNote());
        menu.Items.Add(noteItem);
        menu.Opening += (_, _) => noteItem.Visible = Settings.Current.NotesBeta;
        updateItem = new System.Windows.Forms.ToolStripMenuItem("Update available", null, (_, _) => { ShowWindow(); window?.Go(UI.MainWindow.Page.Home); }) { Visible = false };
        menu.Items.Add(updateItem);
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Quit Tokalot", null, (_, _) => Quit());
        tray.ContextMenuStrip = menu;
        tray.MouseClick += (_, e) => { if (e.Button == System.Windows.Forms.MouseButtons.Left) ShowWindow(); };
    }

    public void Quit()
    {
        Controller?.SaveBeforeExit();
        Controller?.Dispose();
        if (tray != null) { tray.Visible = false; tray.Dispose(); }
        Shutdown();
    }

    // ---------- updates ----------

    public string? UpdateVersion { get; private set; }
    public int? UpdateProgress { get; private set; }

    private DateTime lastUpdateCheck;

    private async Task CheckForUpdatesLoop()
    {
        await Task.Delay(TimeSpan.FromSeconds(20));
        while (true)
        {
            await CheckForUpdates();
            // Just after sign-in the network often isn't up yet: try again soon rather than in hours.
            await Task.Delay(Updater.LastCheckFailed ? TimeSpan.FromMinutes(3) : TimeSpan.FromHours(3));
        }
    }

    public async Task<string?> CheckForUpdates()
    {
        lastUpdateCheck = DateTime.UtcNow;
        var v = await Updater.Check();
        if (v != null && v != UpdateVersion)
        {
            UpdateVersion = v;
            if (updateItem != null) { updateItem.Text = $"Update to {v}"; updateItem.Visible = true; }
            // The banner lives on Home. Rebuilding another page would wipe whatever is being typed there.
            if (window?.CurrentPage == UI.MainWindow.Page.Home) Refresh();
        }
        // The release was withdrawn or replaced since the last look: a banner for it could only fail.
        else if (v == null && !Updater.LastCheckFailed && UpdateVersion != null && UpdateProgress == null)
        {
            UpdateVersion = null;
            if (updateItem != null) updateItem.Visible = false;
            if (window?.CurrentPage == UI.MainWindow.Page.Home) Refresh();
        }
        return v;
    }

    public async Task InstallUpdate()
    {
        if (UpdateVersion == null || UpdateProgress != null) return;
        // Installing restarts Tokalot, which would throw away a dictation that is being recorded or transcribed.
        if (Controller is { Busy: true })
        {
            Ui.Dialog(window, "A dictation is in progress. Update once it has finished.", cancel: null);
            return;
        }
        UpdateProgress = 0;
        Refresh();
        try
        {
            await Updater.DownloadAndRestart(p => Dispatcher.BeginInvoke(() =>
            {
                UpdateProgress = p;
                window?.SetBannerProgress(p);
            }));
            // Still here: there was nothing to install after all.
            UpdateProgress = null;
            Refresh();
        }
        catch (Exception e)
        {
            UpdateProgress = null;
            Log("Update failed: " + e.Message);
            if (window is { IsVisible: true }) Ui.Dialog(window, "The update couldn't be downloaded just now. A new version may still be publishing; try again in a few minutes.\n\n(" + e.Message + ")", cancel: null);
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
 *   Ctrl+Shift+Win, when voice notes are on: the same hold or tap, but the text is saved as a note and
 *   copied instead of pasted. So is New voice note in the tray menu (hands-free).
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
    private readonly Learner learner;         // learns names from corrections, when that's switched on
    private string? learnedNote;              // a learned word whose note hasn't been shown yet
    private DispatcherTimer? learnedTimer;
    private DateTime quietAt;                 // when the message now showing will be gone
    private readonly IndicatorWindow indicator;
    private readonly DispatcherTimer tick;
    private State state = State.Idle;
    private bool handsFree;
    private bool note;                      // this recording is a voice note: saved to Notes and copied, not pasted
    private bool ignoreNextRelease;
    private DateTime pressedAt;
    private ActiveApp? app;
    private const int RevertSeconds = 8;
    private string? plainText;             // the user's own wording for the last (polished) dictation
    private long? plainEntry;
    private DateTime revertUntil;          // Ctrl+Win+Z puts it back until then
    private CancellationTokenSource? work; // the transcription in progress, so Esc can stop it
    private bool userCancelled;

    public Controller(Dispatcher ui)
    {
        this.ui = ui;
        pill = new Pill(() => 0);
        learner = new Learner(ui);
        learner.Learned += word =>
        {
            learnedNote = word;
            learnedTimer!.Start();
            App.Current.RefreshAfterDictation();
        };
        // Learning usually happens just as the next dictation starts, when other messages are about to show.
        // The note (with its undo) waits until nothing is being recorded or processed and the last message has gone.
        learnedTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(500), DispatcherPriority.Background, (_, _) =>
        {
            if (learnedNote == null) { learnedTimer!.Stop(); return; }
            if (state != State.Idle || DateTime.UtcNow < quietAt) return;
            var word = learnedNote;
            learnedNote = null;
            learnedTimer!.Stop();
            quietAt = DateTime.UtcNow.AddMilliseconds(6000);
            pill.HintNear($"Learned “{word}” · click to undo", indicator.Bounds, indicator.Dock, 6000, () =>
            {
                Learner.Forget(word);
                App.Current.RefreshAfterDictation();
            });
        }, ui);
        learnedTimer.Stop();
        indicator = new IndicatorWindow(() => recorder.Level);
        indicator.Clicked += OnClicked;
        hook = new HotkeyHook();
        hook.Pressed += () =>
        {
            var shift = hook.ShiftAtPress; // read on the hook's thread, as the combo went down
            ui.BeginInvoke(() => OnPressed(shift));
        };
        hook.Released += () => ui.BeginInvoke(OnReleased);
        hook.KeyWhileHeld += vk => ui.BeginInvoke(() => OnOtherKey(vk));
        hook.TypedAfterPaste += () => ui.BeginInvoke(() => { if (state == State.Idle) plainText = null; });
        hook.Escape += () => ui.BeginInvoke(() => { if (state == State.Processing) CancelWork(); else Cancel(true); });
        Dictation.Heard += heard => ui.BeginInvoke(() => SuggestSpelled(heard));
        tick = new DispatcherTimer(TimeSpan.FromMilliseconds(250), DispatcherPriority.Normal, (_, _) => Tick(), ui);
        tick.Stop();
        App.Log("Shortcut listener " + (hook.Installed ? "installed" : "FAILED to install"));

        // Windows can drop the shortcut listener without saying so (most often while the PC is busy just after
        // sign-in, or across sleep and the lock screen). Put it back regularly, and whenever the PC wakes or unlocks.
        var rehookLogged = false;
        void Rehook()
        {
            if (state != State.Idle) return;
            hook.Refresh();
            if (rehookLogged) return;
            rehookLogged = true; // once is enough to show in the log that refreshing works on this PC
            Task.Delay(1000).ContinueWith(_ => App.Log("Shortcut listener refreshed: " + (hook.Installed ? "installed" : "FAILED")));
        }
        rehook = new DispatcherTimer(TimeSpan.FromSeconds(45), DispatcherPriority.Background, (_, _) => Rehook(), ui);
        Microsoft.Win32.SystemEvents.SessionSwitch += (_, e) =>
        {
            if (e.Reason is Microsoft.Win32.SessionSwitchReason.SessionUnlock or Microsoft.Win32.SessionSwitchReason.SessionLogon)
                ui.BeginInvoke(Rehook);
        };
        Microsoft.Win32.SystemEvents.PowerModeChanged += (_, e) =>
        {
            if (e.Mode == Microsoft.Win32.PowerModes.Resume) ui.BeginInvoke(Rehook);
        };
    }

    private readonly DispatcherTimer rehook;

    public bool HotkeyWorks => hook.Installed;

    /** Re-reads the indicator's style, edge and idle visibility (after a Settings change). */
    public void RefreshIndicator() => indicator.ApplySettings();

    private void Say(string text, int ms)
    {
        quietAt = DateTime.UtcNow.AddMilliseconds(ms + 300);
        pill.FlashNear(text, indicator.Bounds, indicator.Dock, ms);
    }

    /** A reminder of how hands-free works. It has an X; once closed it never shows again (Settings can bring it back). */
    private void Hint(string text, int ms)
    {
        var s = Settings.Current;
        if (s.HideHandsFreeHint) return;
        quietAt = DateTime.UtcNow.AddMilliseconds(ms + 300);
        pill.HintNear(text, indicator.Bounds, indicator.Dock, ms, () => { s.HideHandsFreeHint = true; s.Save(); });
    }

    /** shift: Shift was held too (Ctrl+Shift+Win), which makes it a voice note when those are on. */
    private void OnPressed(bool shift)
    {
        if (state == State.Recording && handsFree)
        {
            ignoreNextRelease = true;
            Finish();
            return;
        }
        if (state != State.Idle) return;
        Begin(handsFreeMode: false, asNote: shift && Settings.Current.NotesBeta);
    }

    /** Tray › New voice note: a hands-free recording that is saved to Notes and copied, not pasted. */
    public void StartNote()
    {
        if (state != State.Idle || !Settings.Current.NotesBeta) return;
        if (Begin(handsFreeMode: true, asNote: true)) Hint("Voice note · Ctrl+Win to finish · Esc to cancel", 3500);
    }

    /** Words spelled out letter by letter in a dictation are offered for the dictionary on Home and Dictionary. */
    private static void SuggestSpelled(string heard)
    {
        try
        {
            var s = Settings.Current;
            if (s.SuggestWords(Learn.Spelled(heard, s.Words))) s.Save();
        }
        catch (Exception e) { App.Log("Looking for spelled-out words failed: " + e.Message); }
    }

    /** Clicking the indicator: start hands-free, or finish. Focus stays in your app. */
    private void OnClicked()
    {
        if (state == State.Recording) { Finish(); return; }
        if (state == State.Processing) { CancelWork(); return; }
        if (state != State.Idle) return;
        if (Begin(handsFreeMode: true)) Hint("Listening · click again or press Ctrl+Win to finish", 3500);
    }

    /** asNote: a voice note, which goes to no app, so it's cleaned up in the general style. */
    private bool Begin(bool handsFreeMode, bool asNote = false)
    {
        learner.Stop(learn: true); // a correction made before this dictation still counts
        app = asNote ? null : AppDetect.Detect();
        recorder.CueSamples = Settings.Current.Sounds ? Recorder.SampleRate * 6 / 10 : 0;
        if (!recorder.Start())
        {
            Sounds.Play(Sounds.Kind.Error);
            Say("No microphone found. Check Windows sound settings.", 4000);
            return false;
        }
        state = State.Recording;
        handsFree = handsFreeMode;
        note = asNote;
        pressedAt = DateTime.UtcNow;
        hook.Listening = true;
        dictation.WarmUp();
        live = LiveStt.Usable(Settings.Current) ? new LiveStt(Settings.Current) : null;
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
            Hint(note ? "Voice note · Ctrl+Win to finish · Esc to cancel" : "Hands-free · Ctrl+Win to finish · Esc to cancel", 3500);
            return;
        }
        Finish();
    }

    private void OnOtherKey(int vk)
    {
        // Ctrl+Win+Z just after a polished dictation: put the user's own wording back.
        if (vk == 0x5A && plainText != null && DateTime.UtcNow < revertUntil)
        {
            if (state == State.Recording && !handsFree) Cancel(false); // the press also started a recording
            if (state == State.Idle) Revert();
            return;
        }
        if (state != State.Recording || handsFree) return;
        // Shift just after Ctrl+Win (pressed in that order) also makes a voice note. Once it is one, Shift
        // repeating while held changes nothing. With voice notes off, Shift counts like any other key.
        if ((vk is 0x10 or 0xA0 or 0xA1) && Settings.Current.NotesBeta)
        {
            if (!note && (DateTime.UtcNow - pressedAt).TotalMilliseconds < ShortcutWindowMs) { note = true; app = null; }
            if (note) return;
        }
        // Ctrl+Win+D, Ctrl+Win+Arrow, etc.: the user meant a Windows shortcut.
        if ((DateTime.UtcNow - pressedAt).TotalMilliseconds < ShortcutWindowMs) Cancel(false);
    }

    private void Tick()
    {
        if (state != State.Recording) { tick.Stop(); return; }
        var s = Settings.Current;
        live?.Feed(recorder); // long dictations: send what's been said so far at each pause
        if (recorder.Full)
        {
            Say("Reached the 10 minute limit. Transcribing…", 3000);
            Finish();
        }
        else if (handsFree && s.AutoStop && (DateTime.UtcNow - recorder.LastVoiceAt).TotalSeconds > AutoStopSeconds)
            Finish(trimSilence: true);
        // Hold mode, but the keys are up and the release never arrived (focus went to a UAC prompt or the lock screen).
        else if (!handsFree && !HotkeyHook.ComboHeld && (DateTime.UtcNow - pressedAt).TotalMilliseconds > ShortcutWindowMs)
            Finish();
    }

    private LiveStt? live; // pieces of the current recording already sent for transcription

    /** Stops recording without transcribing. */
    public void Cancel(bool audible)
    {
        if (state != State.Recording) return;
        var samples = recorder.Stop();
        live?.Cancel();
        live = null;
        Reset();
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

    /** Swaps the polished text that was just pasted for the user's own wording. */
    private async void Revert()
    {
        var text = plainText;
        var id = plainEntry;
        plainText = null;
        if (text == null) return;
        learner.Stop(learn: false);
        state = State.Processing; // no new recording while keys are being sent
        try
        {
            await TextInjector.Replace(text);
            var entry = id == null ? null : History.All().FirstOrDefault(e => e.Id == id);
            if (entry != null)
                History.Replace(new Entry
                {
                    Id = entry.Id, Time = entry.Time, Text = text, Raw = entry.Raw, DurationMs = entry.DurationMs,
                    Cleaned = false, AppKey = entry.AppKey, AppLabel = entry.AppLabel,
                });
            Say("Your own wording is back", 1800);
        }
        catch (Exception e) { App.Log("Revert failed: " + e.Message); }
        finally
        {
            state = State.Idle;
            App.Current.RefreshAfterDictation();
        }
    }

    /** Stops a transcription in progress (Esc, or clicking the indicator). */
    private void CancelWork()
    {
        if (work == null) return;
        userCancelled = true;
        try { userWork?.Cancel(); } catch { }
    }

    /** Gives up after a while even on a dead connection: a minute plus twice the recording's length. */
    private CancellationTokenSource? userWork; // cancelled only by the user; `work` also ends when time runs out
    // ---------- progress line while transcribing ----------
    // An estimate, not a measurement: the services don't report how far along they are. The line moves at the
    // pace recent dictations took (speech-to-text, then cleanup), slows as it nears the end of each step so it
    // never claims to be finished early, and jumps on when a step really completes.
    private DispatcherTimer? ringTimer;
    private DateTime ringFrom, ringStage1At;
    private double ringStt = 1, ringClean = 1, ringShown;

    /** audioSeconds: how much speech still has to be recognised (only the tail, when pieces were sent early). */
    private void StartRing(double audioSeconds)
    {
        ringStt = Math.Max(600, Dictation.SttRate * Math.Max(audioSeconds, 3));
        ringClean = Math.Max(500, Dictation.CleanRate * audioSeconds * 13); // about 13 characters a second of speech
        ringFrom = DateTime.UtcNow;
        ringStage1At = default;
        ringShown = 0;
        indicator.SetProgress(0);
        ringTimer ??= new DispatcherTimer(TimeSpan.FromMilliseconds(33), DispatcherPriority.Background, (_, _) => RingTick(), ui);
        ringTimer.Start();
    }

    private void RingTick()
    {
        if (state != State.Processing) { ringTimer?.Stop(); return; }
        var now = DateTime.UtcNow;
        int stage = Dictation.Stage;
        if (stage >= 1 && ringStage1At == default)
        {
            ringStage1At = now;
            ringClean = Math.Max(500, Dictation.CleanRate * Dictation.StageChars); // the real length is known now
        }
        static double Ease(double x) => 1 - Math.Exp(-1.8 * x);
        double share = ringStt / (ringStt + ringClean);
        double target = 0.97 * stage switch
        {
            0 => share * Ease((now - ringFrom).TotalMilliseconds / ringStt),
            1 => share + (1 - share) * Ease((now - ringStage1At).TotalMilliseconds / ringClean),
            _ => 1,
        };
        ringShown = Math.Max(ringShown, ringShown + (target - ringShown) * 0.2); // smooth, and never backwards
        indicator.SetProgress(ringShown);
    }

    private float[]? held;                     // the recording being transcribed, until its text is delivered
    private ActiveApp? heldApp;

    public bool Busy => state != State.Idle;

    /**
     * Tokalot is about to close. A recording that is still being made or transcribed exists only in memory,
     * so it is put in history first (with a Transcribe button), rather than lost.
     */
    public void SaveBeforeExit()
    {
        try
        {
            float[]? samples = null;
            var target = heldApp;
            if (state == State.Recording) { samples = recorder.Stop(); target = app; }
            else if (state == State.Processing) samples = held;
            held = null;
            if (samples == null || samples.Length < Recorder.SampleRate) return;
            dictation.KeepAudio(samples, null, "Cancelled", target, cancelled: true).Wait(8000);
            App.Log("Closing mid-dictation: the recording was kept in history");
        }
        catch (Exception e) { App.Log("Couldn't keep the recording on exit: " + e.Message); }
    }

    private CancellationToken StartWork(int sampleCount)
    {
        userWork = new CancellationTokenSource();
        work = CancellationTokenSource.CreateLinkedTokenSource(userWork.Token);
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
        userWork?.Dispose();
        userWork = null;
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
        note = false;
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
        var asNote = note;
        note = false;

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
        var early = live;
        live = null;
        if (early != null) early.LastVoice = lastVoice;
        var ct = StartWork(samples.Length);
        var userCt = userWork!.Token;
        held = samples;
        heldApp = target;
        var spoken = samples.Length / (double)Recorder.SampleRate;
        StartRing(early is { Pieces: > 0 } ? Math.Min(spoken, 10) : spoken);
        try
        {
            var landed = true; // false: no text box had the focus, so the text was left on the clipboard
            var noteSaved = false;
            var outcome = await Task.Run(() => dictation.Process(samples, target, sparse: speech < 6, ct: ct, live: early, user: userCt));
            if (Dictation.LastTiming.Length > 0) App.Log("Dictation: " + Dictation.LastTiming);
            hook.Listening = false;
            held = null; // the text exists and is in history: nothing left to lose
            if (outcome.Text.Length > 0 && asNote)
            {
                // A voice note goes to Notes and the clipboard. Nothing is pasted, so nothing to watch or put back.
                try { Notes.Add(outcome.Text); noteSaved = true; }
                catch (Exception e) { App.Log("Saving a note failed: " + e.Message); }
                await TextInjector.Copy(outcome.Text);
                Sounds.Play(Sounds.Kind.Done);
            }
            else if (outcome.Text.Length > 0)
            {
                landed = await TextInjector.Paste(outcome.Text);
                Sounds.Play(Sounds.Kind.Done);
                if (landed) learner.Watch(outcome.Text);
            }
            indicator.SetMode(IndicatorView.Mode.Idle);
            plainText = asNote ? null : outcome.Plain;
            plainEntry = outcome.EntryId;
            revertUntil = DateTime.UtcNow.AddSeconds(RevertSeconds);
            hook.OwnKeyUntil = revertUntil;
            hook.OwnKey = plainText != null ? 0x5A : 0; // Z
            if (outcome.Warning != null) Say(noteSaved ? outcome.Warning + " · saved to Notes" : outcome.Warning, 3500);
            else if (outcome.Text.Length == 0) Say("Didn't catch anything", 1800);
            else if (asNote) Say(noteSaved ? "Saved to Notes · copied" : "Couldn't save the note · copied", 2600);
            else if (!landed) Say("No text box selected · copied, paste it with Ctrl+V", 4000);
            else if (outcome.Plain != null) Say("Polished · Ctrl+Win+Z for your own wording", RevertSeconds * 1000);
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
            held = null;
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
