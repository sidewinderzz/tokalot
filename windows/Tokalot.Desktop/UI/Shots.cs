using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.UI;

/**
 * Developer tool: renders every page (light and dark) plus the recording pill to PNGs,
 * using made-up sample data in a throwaway folder. Run: Tokalot.exe --screenshots <folder>
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
            C.Apply(theme);
            Page(dir, theme, "1-home", w => w.Go(MainWindow.Page.Home));
            Page(dir, theme, "2-dictionary", w => w.Go(MainWindow.Page.Dictionary));
            Page(dir, theme, "3-style", w => { w.SetStyleTab("MESSAGING"); w.Go(MainWindow.Page.Style); });
            Page(dir, theme, "4-snippets", w => w.Go(MainWindow.Page.Snippets));
            Page(dir, theme, "5-snippet-editor", w => { w.Go(MainWindow.Page.Snippets); w.OpenSnippetEditor(null); });
            Page(dir, theme, "6-settings", w => w.Go(MainWindow.Page.Settings));
            Page(dir, theme, "7-wide-home", w => { w.ForcedWidth = 1700; w.Go(MainWindow.Page.Home); w.Render(); }, 1700);
            Page(dir, theme, "8-wide-settings", w => { w.ForcedWidth = 1700; w.Go(MainWindow.Page.Settings); w.Render(); }, 1700);
            Page(dir, theme, "9-wide-style", w => { w.ForcedWidth = 1700; w.SetStyleTab("MESSAGING"); w.Go(MainWindow.Page.Style); w.Render(); }, 1700);
        }
        PillShots(dir);
        IndicatorShots(dir);
        MenuShot(dir);
    }

    /** The pop-up menu (history's three dots, the app list on Style) in the dark theme. */
    private static void MenuShot(string dir)
    {
        C.Apply("dark");
        var menu = new System.Windows.Controls.ContextMenu { Resources = Ui.MenuStyles() };
        menu.Items.Add(new System.Windows.Controls.MenuItem { Header = "Copy original" });
        menu.Items.Add(new System.Windows.Controls.MenuItem { Header = "Delete" });
        menu.Items.Add(new System.Windows.Controls.MenuItem { Header = "Messages", IsCheckable = true, IsChecked = true });
        Layout(menu, 240, double.NaN);
        Save(menu, Path.Combine(dir, "menu.png"));
    }

    /** Every indicator style in each state, plus the ripple pill on the side edges. */
    private static void IndicatorShots(string dir)
    {
        C.Apply("dark");
        var row = new System.Windows.Controls.WrapPanel { Width = 900, Background = C.Hex("#1F2023") };
        void Add(string look, string dock, IndicatorView.Mode mode)
        {
            var v = new IndicatorView { Look = look, Dock = dock, Scale = 1.3, CurrentMode = mode, Level = () => 0.06f, Margin = new Thickness(12) };
            row.Children.Add(new System.Windows.Controls.Border { Child = v, BorderBrush = C.Hex("#141416"), BorderThickness = new Thickness(0, 0, 0, 0) });
        }
        foreach (var look in IndicatorView.Styles)
            foreach (var mode in new[] { IndicatorView.Mode.Idle, IndicatorView.Mode.Listening, IndicatorView.Mode.Working })
                Add(look, "bottom", mode);
        Add("ripple", "left", IndicatorView.Mode.Listening);
        Add("ripple", "right", IndicatorView.Mode.Listening);
        Add("bars", "left", IndicatorView.Mode.Listening);
        for (int i = 0; i < 20; i++)
        {
            foreach (var c in row.Children) ((FrameworkElement)((System.Windows.Controls.Border)c).Child).InvalidateVisual();
            Layout(row, 900, double.NaN);
        }
        Save(row, Path.Combine(dir, "indicators.png"));
    }

    private static void Page(string dir, string theme, string name, Action<MainWindow> setup, double width = 1100)
    {
        var w = new MainWindow();
        setup(w);
        var root = (FrameworkElement)w.Content;
        w.Content = null;
        Layout(root, width, 800);
        var h = Math.Max(800, w.ExtentHeight + 4);
        Layout(root, width, h);
        Save(root, Path.Combine(dir, $"{theme}-{name}.png"));
        w.Close();
    }

    private static void PillShots(string dir)
    {
        C.Apply("dark");
        var states = new (string Name, Bars.Mode Mode, Brush Brush, string Text)[]
        {
            ("pill-listening", Bars.Mode.Listening, C.Argb(Settings.Current.Accent), ""),
            ("pill-handsfree", Bars.Mode.Listening, C.Argb(Settings.Current.Accent), "Hands-free · Ctrl+Win to finish · Esc to cancel"),
            ("pill-working", Bars.Mode.Working, Brushes.White, ""),
            ("pill-message", Bars.Mode.Idle, Brushes.White, "The mic heard nothing. Check Settings › Privacy › Microphone."),
        };
        foreach (var (name, mode, brush, text) in states)
        {
            var pill = new Pill(() => 0.09f);
            var content = pill.Preview(mode, brush, text);
            pill.Content = null;
            var host = new System.Windows.Controls.Border { Background = C.Hex("#6E6E73"), Padding = new Thickness(10), Child = content };
            for (int i = 0; i < 25; i++)
            {
                Layout(host, double.NaN, double.NaN);
                pill.BarsView.InvalidateVisual();
                Pump();
            }
            Save(host, Path.Combine(dir, name + ".png"));
            pill.Close();
        }
    }

    private static void Layout(FrameworkElement e, double w, double h)
    {
        var size = new Size(double.IsNaN(w) ? double.PositiveInfinity : w, double.IsNaN(h) ? double.PositiveInfinity : h);
        e.Measure(size);
        var final = new Size(double.IsNaN(w) ? e.DesiredSize.Width : w, double.IsNaN(h) ? e.DesiredSize.Height : h);
        e.Arrange(new Rect(final));
        e.UpdateLayout();
        Pump();
    }

    private static void Pump()
    {
        var frame = new DispatcherFrame();
        Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background, () => frame.Continue = false);
        Dispatcher.PushFrame(frame);
    }

    private static void Save(FrameworkElement e, string path)
    {
        var bmp = new RenderTargetBitmap((int)Math.Ceiling(e.ActualWidth * Scale), (int)Math.Ceiling(e.ActualHeight * Scale), 96 * Scale, 96 * Scale, PixelFormats.Pbgra32);
        bmp.Render(e);
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var f = File.Create(path);
        enc.Save(f);
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
