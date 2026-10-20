using System.Collections.Generic;
using Avalonia.Controls;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/** The Linux part of the main window: the two group permissions, named with the commands that grant them. */
public sealed partial class MainWindow
{
    /** One line that adds the user to the "input" group, which is what lets Tokalot see Ctrl+Super. */
    internal const string InputGroupCommand = "sudo usermod -aG input $USER";
    /** Lets the "input" group use /dev/uinput, which is how Tokalot presses Ctrl+V. */
    internal const string UinputRuleCommand =
        "echo 'KERNEL==\"uinput\", GROUP=\"input\", MODE=\"0660\", OPTIONS+=\"static_node=uinput\"' | sudo tee /etc/udev/rules.d/60-tokalot-uinput.rules && sudo udevadm control --reload-rules && sudo udevadm trigger";

    private const string MicHelp = "If dictation hears nothing, open your system's sound settings and check that the right microphone is chosen as the input device and isn't muted.";

    private const string BackupNote = "Backups work in the Linux, Mac and Windows apps.";

    /** Home: a card for each permission that's missing. */
    private void PlatformWarnings(StackPanel col)
    {
        if (!(App.Current.Controller?.HotkeyWorks ?? true))
        {
            var c = Ui.Card(Ui.Stack(
                Ui.Text("Tokalot can't listen for Ctrl+Super yet. Linux only lets members of the \"input\" group see the keyboard. Run this in a terminal, then log out and back in:", 15, C.Warn),
                Command(InputGroupCommand)), 18);
            col.Children.Add(Spaced(c, 0, 0, 0, 14));
        }
    }

    /** Settings › Setup: the shortcut, pasting, clipboard and microphone rows. */
    private void PlatformSetupRows(List<Control> setup)
    {
        var hookOk = App.Current.Controller?.HotkeyWorks ?? true;
        setup.Add(Status("Ctrl+Super shortcut", hookOk, "Working", "Not working: Tokalot isn't allowed to see the keyboard. Run this, then log out and back in.", command: InputGroupCommand));
        setup.Add(Status("Pasting into apps", TextInjector.CanType, "Working, using the " + TextInjector.Method,
            "Tokalot can't press Ctrl+V for you, so text is only copied and you paste it yourself. Run this (and the command above), then log out and back in.",
            command: UinputRuleCommand));
        if (TextInjector.ClipboardNeedsWlCopy)
            setup.Add(Status("Clipboard", false, "", "On this Wayland desktop Tokalot's clipboard may not reach your apps, so dictations are only copied and you press Ctrl+V yourself. Install the wl-clipboard package (wl-copy) to have them pasted for you."));
        if (Recorder.Tool == null)
            setup.Add(Status("Microphone", false, "", "No recorder program found. Install PipeWire (pw-record), pulseaudio-utils (parec) or alsa-utils (arecord)."));
    }
}
