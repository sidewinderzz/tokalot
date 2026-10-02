using System;
using System.IO;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;
using Tokalot.Desktop.UI;

namespace Tokalot.Desktop;

/**
 * The part of the app shared by Linux and Mac: the window, light/dark, and the update banner.
 * Start-up, the tray (menu bar) and --diagnose are in each platform's App.cs.
 */
public sealed partial class App
{
    public static new App Current => (App)Application.Current!;

    public Controller? Controller { get; private set; }
    private MainWindow? window;
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
}
