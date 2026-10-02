using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/**
 * The Mac part of the main window: the three permissions macOS asks about (Input Monitoring,
 * Accessibility, Microphone), each named with where to switch it on and a button that opens that
 * page of System Settings.
 */
public sealed partial class MainWindow
{
    private const string MicHelp = "If dictation hears nothing, open System Settings › Sound › Input and check that the right microphone is chosen and its input level isn't at zero.";

    private const string BackupNote = "Backups work in the Mac, Windows and Linux apps.";

    private const string AfterUpdate = " If Tokalot is already switched on there, switch it off and on again: macOS forgets the permission each time Tokalot is updated.";

    /** What's allowed right now, so coming back from System Settings redraws the page only when something changed. */
    private static string PermissionState() =>
        $"{App.Current.Controller?.HotkeyWorks}{Permissions.Accessibility}{Permissions.Microphone}";

    private string? shownState;

    partial void PlatformActivated()
    {
        var now = PermissionState();
        if (shownState != null && now != shownState && CurrentPage is Page.Home or Page.Settings) Render();
        shownState = now;
    }

    private static Control OpenButton(string pane, Func<bool>? ask = null) => Ui.Button("Open settings", () =>
    {
        // Asking first puts Tokalot in the list (switched off) so there's something to switch on.
        try { ask?.Invoke(); } catch { }
        Permissions.Open(pane);
    });

    private void Warning(StackPanel col, string text, Control button)
    {
        var c = Ui.Card(Ui.Stack(Ui.Text(text, 15, C.Warn), Spaced(button, 0, 12, 0, 0)), 18);
        col.Children.Add(Spaced(c, 0, 0, 0, 14));
    }

    /** Home: a card for each permission that's missing. */
    private void PlatformWarnings(StackPanel col)
    {
        shownState = PermissionState();
        if (!(App.Current.Controller?.HotkeyWorks ?? true))
            Warning(col, "Tokalot can't see Ctrl+Cmd yet. Switch Tokalot on under System Settings › Privacy & Security › Input Monitoring." + AfterUpdate,
                OpenButton(Permissions.InputMonitoringPane, Permissions.RequestInputMonitoring));
        if (!Permissions.Accessibility)
            Warning(col, "Tokalot can't press ⌘V for you yet, so dictations are only copied. Switch Tokalot on under System Settings › Privacy & Security › Accessibility." + AfterUpdate,
                OpenButton(Permissions.AccessibilityPane, Permissions.RequestAccessibility));
        if (Permissions.Microphone is Permissions.Mic.Denied)
            Warning(col, "Tokalot isn't allowed to use the microphone. Switch Tokalot on under System Settings › Privacy & Security › Microphone.",
                OpenButton(Permissions.MicrophonePane));
    }

    /** Settings › Setup: the shortcut, pasting and microphone rows. */
    private void PlatformSetupRows(List<Control> setup)
    {
        shownState = PermissionState();
        var hookOk = App.Current.Controller?.HotkeyWorks ?? true;
        setup.Add(Status("Ctrl+Cmd shortcut", hookOk, "Working (Input Monitoring allowed)",
            "Not working: switch Tokalot on under Privacy & Security › Input Monitoring." + AfterUpdate,
            hookOk ? null : OpenButton(Permissions.InputMonitoringPane, Permissions.RequestInputMonitoring)));
        var canType = TextInjector.CanType;
        setup.Add(Status("Pasting into apps", canType, "Working (Accessibility allowed)",
            "Tokalot can't press ⌘V for you, so text is only copied and you paste it yourself. Switch Tokalot on under Privacy & Security › Accessibility." + AfterUpdate,
            canType ? null : OpenButton(Permissions.AccessibilityPane, Permissions.RequestAccessibility)));

        var device = Recorder.InputDevice();
        switch (Permissions.Microphone)
        {
            case Permissions.Mic.Allowed:
                setup.Add(Status("Microphone", device != null, "Allowed · " + device, "Allowed, but no microphone is connected. Check System Settings › Sound › Input."));
                break;
            case Permissions.Mic.Denied:
            case Permissions.Mic.Restricted:
                setup.Add(Status("Microphone", false, "", "Not allowed: switch Tokalot on under Privacy & Security › Microphone.", OpenButton(Permissions.MicrophonePane)));
                break;
            default:
                setup.Add(Status("Microphone", false, "", "macOS will ask the first time you dictate, or ask now.", Ui.Button("Ask now", AskForMicrophone)));
                break;
        }
    }

    /** Opens the microphone for a moment, which is what makes macOS ask; nothing is kept. */
    private void AskForMicrophone()
    {
        Task.Run(() =>
        {
            using var r = new Recorder();
            if (r.Start()) { System.Threading.Thread.Sleep(300); r.Stop(); }
        }).ContinueWith(_ => Dispatcher.UIThread.Post(Render));
    }
}
