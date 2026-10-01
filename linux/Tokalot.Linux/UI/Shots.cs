using System;
using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Styling;
using Avalonia.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.UI;

/**
 * Developer tool: renders every page (light and dark) plus the recording pill to PNGs,
 * using made-up sample data in a throwaway folder. Run: Tokalot --screenshots <folder>
 * Windows are opened on Avalonia's headless platform, so no display is needed.
 */
internal static class Shots
{
    private const double Scale = 1.5;

    public static void Run(string dir)
    {
        Directory.CreateDirectory(dir);
        Seed();
        foreach (var theme in new[] { "light", "dark" })
        {
            Apply(theme);
            Page(dir, theme, "1-home", w => w.Go(MainWindow.Page.Home));
            Page(dir, theme, "2-dictionary", w => w.Go(MainWindow.Page.Dictionary));
            Page(dir, theme, "3-style", w => { w.SetStyleTab("MESSAGING"); w.Go(MainWindow.Page.Style); });
            Page(dir, theme, "4-snippets", w => w.Go(MainWindow.Page.Snippets));
            Page(dir, theme, "5-snippet-editor", w => { w.Go(MainWindow.Page.Snippets); w.OpenSnippetEditor(null); });
            Page(dir, theme, "6-settings", w => w.Go(MainWindow.Page.Settings));
            Page(dir, theme, "7-wide-home", w => { w.Go(MainWindow.Page.Home); w.Render(); }, 1700);
            Page(dir, theme, "8-wide-settings", w => { w.Go(MainWindow.Page.Settings); w.Render(); }, 1700);
            Page(dir, theme, "9-wide-style", w => { w.SetStyleTab("MESSAGING"); w.Go(MainWindow.Page.Style); w.Render(); }, 1700);
        }
        PillShots(dir);
        IndicatorShots(dir);
        try { MenuShot(dir); } catch (Exception e) { Console.Error.WriteLine("menu.png skipped: " + e.Message); }
        AudioRoundTrip(dir);
    }

    /** Saves two seconds of tone and reads it back, the path "Transcribe" on a failed entry relies on. */
    private static void AudioRoundTrip(string dir)
    {
        var probe = new float[Recorder.SampleRate * 2];
        for (int i = 0; i < probe.Length; i++) probe[i] = (float)(0.3 * Math.Sin(i * 2 * Math.PI * 440 / Recorder.SampleRate));
        AudioStore.Save(1, probe);
        var back = AudioStore.Load(1);
        float peak = 0;
        if (back != null) foreach (var v in back) peak = Math.Max(peak, Math.Abs(v));
        File.WriteAllText(Path.Combine(dir, "audio-roundtrip.txt"),
            FormattableString.Invariant($"{probe.Length} samples saved as {Path.GetExtension(AudioStore.FileFor(1))}, {back?.Length} read back, peak {peak:0.00}"));
        AudioStore.Delete(1);
    }

    private static void Apply(string theme)
    {
        C.Apply(theme);
        Application.Current!.RequestedThemeVariant = C.Dark ? ThemeVariant.Dark : ThemeVariant.Light;
        Application.Current.Resources = Ui.MenuStyles();
    }

    /** Puts a control in a bare window sized to it, so it gets its styles and a layout. */
    private static Window Host(Control content) => new()
    {
        SystemDecorations = SystemDecorations.None, SizeToContent = SizeToContent.WidthAndHeight, Content = content, Background = Brushes.Transparent,
    };

    /** The pop-up menu (history's three dots, the app list on Style) in the dark theme. */
    private static void MenuShot(string dir)
    {
        Apply("dark");
        var menu = Ui.Menu();
        menu.Items.Add(new MenuItem { Header = "Copy original" });
        menu.Items.Add(new MenuItem { Header = "Delete" });
        menu.Items.Add(new MenuItem { Header = "Messages", ToggleType = MenuItemToggleType.CheckBox, IsChecked = true });
        var anchor = new Border { Width = 260, Height = 170, Background = C.Bg };
        var w = Host(anchor);
        w.Show();
        Pump(w);
        menu.Open(anchor);
        Pump(w);
        // On the headless platform the menu opens inside the window (an overlay), so the window's picture includes it.
        Save(w, Path.Combine(dir, "menu.png"));
        menu.Close();
        w.Close();
    }

    /** Every indicator style in each state, plus the ripple pill on the side edges. */
    private static void IndicatorShots(string dir)
    {
        Apply("dark");
        var row = new WrapPanel { Width = 900, Background = C.Hex("#1F2023") };
        void Add(string look, string dock, IndicatorView.Mode mode)
        {
            var v = new IndicatorView { Look = look, Dock = dock, Scale = 1.3, CurrentMode = mode, Level = () => 0.06f, Margin = new Thickness(12) };
            row.Children.Add(new Border { Child = v });
        }
        foreach (var look in IndicatorView.Styles)
            foreach (var mode in new[] { IndicatorView.Mode.Idle, IndicatorView.Mode.Listening, IndicatorView.Mode.Working })
                Add(look, "bottom", mode);
        Add("ripple", "left", IndicatorView.Mode.Listening);
        Add("ripple", "right", IndicatorView.Mode.Listening);
        Add("bars", "left", IndicatorView.Mode.Listening);
        var w = Host(row);
        w.Show();
        Pump(w);
        // Each drawing moves the animation one frame on; let the level meter settle.
        for (int i = 0; i < 20; i++) Draw(w);
        Save(w, Path.Combine(dir, "indicators.png"));
        w.Close();
    }

    private static void Page(string dir, string theme, string name, Action<MainWindow> setup, double width = 1100)
    {
        var w = new MainWindow { Width = width, Height = 800, WindowStartupLocation = WindowStartupLocation.Manual };
        w.Show();
        Pump(w);
        setup(w);
        Pump(w);
        // Grow the window until the whole page fits without scrolling (the page is drawn at 0.9 scale under the 32 px top strip).
        var h = Math.Max(800, Math.Ceiling((w.ExtentHeight + 32) * 0.9) + 4);
        w.Height = h;
        Pump(w);
        w.Render();
        Pump(w);
        Save(w, Path.Combine(dir, $"{theme}-{name}.png"));
        w.Close();
        Dispatcher.UIThread.RunJobs();
    }

    private static void PillShots(string dir)
    {
        Apply("dark");
        var states = new (string Name, Bars.Mode Mode, IBrush Brush, string Text)[]
        {
            ("pill-listening", Bars.Mode.Listening, C.Argb(Settings.Current.Accent), ""),
            ("pill-handsfree", Bars.Mode.Listening, C.Argb(Settings.Current.Accent), "Hands-free · Ctrl+Super to finish · Esc to cancel"),
            ("pill-working", Bars.Mode.Working, Brushes.White, ""),
            ("pill-message", Bars.Mode.Idle, Brushes.White, "The mic heard nothing. Check the input device in your sound settings."),
        };
        foreach (var (name, mode, brush, text) in states)
        {
            var pill = new Pill(() => 0.09f);
            var content = pill.Preview(mode, brush, text);
            pill.Content = null;
            var w = Host(new Border { Background = C.Hex("#6E6E73"), Padding = new Thickness(10), Child = content });
            w.Show();
            Pump(w);
            // Drawn by the renderer real windows use (at 1x): the off-screen one misplaces what is inside a shadowed border.
            for (int i = 0; i < 25; i++)
            {
                AvaloniaHeadlessPlatform.ForceRenderTimerTick();
                Dispatcher.UIThread.RunJobs();
            }
            w.CaptureRenderedFrame()?.Save(Path.Combine(dir, name + ".png"));
            w.Close();
            pill.Close();
        }
    }

    private static void Pump(Window w)
    {
        Dispatcher.UIThread.RunJobs();
        w.UpdateLayout();
        Dispatcher.UIThread.RunJobs();
    }

    private static RenderTargetBitmap Draw(Window w)
    {
        var bmp = new RenderTargetBitmap(
            new PixelSize((int)Math.Ceiling(w.Bounds.Width * Scale), (int)Math.Ceiling(w.Bounds.Height * Scale)), new Vector(96 * Scale, 96 * Scale));
        bmp.Render(w);
        return bmp;
    }

    private static void Save(Window w, string path)
    {
        using var bmp = Draw(w);
        bmp.Save(path);
    }

    /** Made-up sample content so every screen has something on it. */
    private static void Seed()
    {
        var s = Settings.Current;
        s.Words.AddRange(new[] { "Kubernetes", "PostgreSQL", "Nguyen", "Tokalot", "Groq", "Wispr" });
        s.Snippets.Add(new Snippet("my email", "me@example.com"));
        s.Snippets.Add(new Snippet("home address", "123 Main St, Springfield"));
        s.Snippets.Add(new Snippet("meeting link", "Here's the link for our call: https://meet.example.com/abc-defg-hij"));
        s.SetKey("groq", "sample-key-for-screenshots");
        s.Save();

        var now = DateTimeOffset.Now;
        void Add(TimeSpan ago, string text, string raw, string app, int secs, bool cleaned = true)
        {
            var t = now - ago;
            History.Add(new Entry
            {
                Id = t.ToUnixTimeMilliseconds(), Time = t.ToUnixTimeMilliseconds(), Text = text, Raw = raw, DurationMs = secs * 1000,
                Cleaned = cleaned, AppKey = "app:" + app.ToLowerInvariant(), AppLabel = app,
            });
            Usage.RecordDictation(TextTools.WordCount(text), false);
        }
        Add(TimeSpan.FromDays(1.2), "Running about 10 minutes late, save me a seat lol", "um running about ten minutes late uh save me a seat lol", "WhatsApp", 4);
        Add(TimeSpan.FromHours(5), "Hi Sam,\n\nThanks for sending the quote. Could we move the delivery to Thursday? Friday is booked.\n\nThanks,\nAlex", "hi sam thanks for sending the quote could we move the delivery to wednesday no wait thursday friday is booked thanks alex", "Outlook", 11);
        Add(TimeSpan.FromHours(2), "We have three options:\n1. We fix it ourselves\n2. We call the dealer\n3. We wait until spring", "we have three options option one we fix it ourselves option two we call the dealer option three we wait until spring", "Slack", 8);
        Add(TimeSpan.FromMinutes(20), "Add a retry to `fetchOrders()` (three attempts with backoff) and log the final error.", "add a retry to fetch orders three attempts with backoff and uh log the final error", "Claude", 7);
        Usage.RecordStt("GROQ", 30);
        Usage.RecordLlm("GROQ", 4200, 380);
        Usage.RecordEdits(5, 1);
    }
}
