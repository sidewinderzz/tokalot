using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shell;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/** The settings and history window. Closing it leaves Tokalot running in the tray. */
public sealed class MainWindow : Window
{
    public enum Page { Home, Dictionary, Style, Snippets, Notes, Settings }

    public Page CurrentPage { get; private set; } = Page.Home;

    private readonly StackPanel nav = new() { Margin = new Thickness(14, 18, 14, 0) };
    private readonly ScrollViewer scroller = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private readonly Border toast;
    private readonly TextBlock toastText;
    private readonly TextBox search;
    private readonly Border searchBox;
    private StackPanel? historyBox;
    private TextBlock? bannerText;
    private TextBlock? modelStatus;
    private int historyLimit = 100, notesLimit = 100;
    private readonly HashSet<long> expanded = new(), showOriginal = new();
    private static string styleTab = "MESSAGING";
    private static int? downloadPct;
    private static string? downloadError;
    private Snippet? editing;
    private bool editingNew;
    private readonly Action onHistory, onPlayer;
    private readonly Action<bool> onSync;

    private static Settings S => Settings.Current;

    /** The strip at the top you drag the window by; the minimize and close buttons sit in its right end. */
    private const double CaptionHeight = 32;
    /** Sizes were carried over from the phone app and read large on a monitor, so the whole window is drawn a bit smaller. */
    private const double UiScale = 0.9;

    public MainWindow()
    {
        Title = "Tokalot";
        // Never open larger than the screen: the title strip with Close must stay reachable.
        var area = SystemParameters.WorkArea;
        Width = Math.Min(1040, area.Width); Height = Math.Min(760, area.Height);
        MinWidth = Math.Min(760, Width); MinHeight = Math.Min(520, Height);
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = C.Bg;
        FontFamily = C.Sans;
        try { Icon = BitmapFrame.Create(new Uri("pack://application:,,,/Assets/icon.png")); } catch { }
        // No system title bar: the app draws to the top edge. Resizing, snapping and double-click to maximize still work.
        WindowChrome.SetWindowChrome(this, new WindowChrome
        {
            // Windows adds the top resize border to the caption, so take it off to match the drawn strip.
            CaptionHeight = CaptionHeight * UiScale - 6, ResizeBorderThickness = new Thickness(6), GlassFrameThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0), UseAeroCaptionButtons = false,
        });

        (searchBox, search) = Ui.Field("", "Search your dictations");
        search.TextChanged += (_, _) => FillHistory();

        toastText = new TextBlock { Foreground = Brushes.White, FontSize = 13.5, FontFamily = C.Sans };
        toast = Ui.Stadium(new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x1C, 0x1C, 0x1E)),
            Padding = new Thickness(18, 9, 18, 9), Child = toastText, Opacity = 0, IsHitTestVisible = false,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 28),
        });

        // Sidebar | content
        var root = new Grid { Background = C.Bg, LayoutTransform = new ScaleTransform(UiScale, UiScale) };
        root.Resources[typeof(System.Windows.Controls.Primitives.ScrollBar)] = Ui.ScrollBarStyle();
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(232) });
        root.ColumnDefinitions.Add(new ColumnDefinition());
        root.Children.Add(Sidebar());
        var contentGrid = new Grid { Margin = new Thickness(0, CaptionHeight, 0, 0) };
        scroller.PreviewMouseWheel += PassWheelToPage;
        contentGrid.Children.Add(scroller);
        contentGrid.Children.Add(toast);
        Grid.SetColumn(contentGrid, 1);
        root.Children.Add(contentGrid);
        var caption = CaptionButtons();
        Grid.SetColumn(caption, 1);
        root.Children.Add(caption);
        Content = root;
        // A maximized window hangs a few pixels past the screen edge; pull the content back in.
        StateChanged += (_, _) => root.Margin = new Thickness(WindowState == WindowState.Maximized ? 7 : 0);

        onHistory = () => Dispatcher.BeginInvoke(() => { if (CurrentPage == Page.Home) FillHistory(); });
        onPlayer = () => Dispatcher.BeginInvoke(() => { if (CurrentPage == Page.Home) FillHistory(); });
        History.Changed += onHistory;
        Player.Changed += onPlayer;
        Closed += (_, _) => { History.Changed -= onHistory; Player.Changed -= onPlayer; Player.Stop(false); };
        SourceInitialized += (_, _) => DarkTitleBar();
        Activated += (_, _) => Sync.Queue();
        // A sync that changed nothing only updates the status line on Settings: not worth taking the cursor out of a box being typed in.
        onSync = changed => Dispatcher.BeginInvoke(() =>
        {
            if (changed || (CurrentPage == Page.Settings && Keyboard.FocusedElement is not System.Windows.Controls.Primitives.TextBoxBase)) Render();
        });
        Sync.Finished += onSync;
        Closed += (_, _) => Sync.Finished -= onSync;
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control)
            {
                e.Handled = true;
                if (CurrentPage != Page.Home) Go(Page.Home);
                search.Focus();
            }
            else if (e.Key == Key.Escape && (editing != null || editingNew)) { e.Handled = true; editing = null; editingNew = false; Render(); }
            else if (e.Key == Key.Escape && CurrentPage == Page.Home && search.Text.Length > 0) { e.Handled = true; search.Text = ""; }
        };
        SizeChanged += (_, _) => { if (IsLoaded && IsWide != wideLayout) Render(); };
        Render();
    }

    // ---------- frame ----------

    private UIElement CaptionButtons()
    {
        Border Button(string path, string tip, Brush hover, Brush hoverGlyph, Action onClick)
        {
            var glyph = new System.Windows.Shapes.Path
            {
                Data = Geometry.Parse(path), Stroke = C.Text, StrokeThickness = 1, Width = 10, Height = 10, SnapsToDevicePixels = true,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            };
            var b = new Border { Width = 46, Height = CaptionHeight, Background = Brushes.Transparent, Child = glyph, ToolTip = tip };
            WindowChrome.SetIsHitTestVisibleInChrome(b, true);
            b.MouseEnter += (_, _) => { b.Background = hover; glyph.Stroke = hoverGlyph; };
            b.MouseLeave += (_, _) => { b.Background = Brushes.Transparent; glyph.Stroke = C.Text; };
            b.MouseLeftButtonUp += (_, _) => onClick();
            return Ui.Keys(b, onClick, tip);
        }
        var row = Ui.Row(
            Button("M0,5.5 H10", "Minimize", C.Hover, C.Text, () => WindowState = WindowState.Minimized),
            Button("M0,0 L10,10 M10,0 L0,10", "Close", C.Hex("#C42B1C"), Brushes.White, Close));
        row.HorizontalAlignment = HorizontalAlignment.Right;
        row.VerticalAlignment = VerticalAlignment.Top;
        return row;
    }

    private UIElement Sidebar()
    {
        var logo = new Bars { Width = 34, Height = 34, BarBrush = C.Text, AccentBrush = !C.Dark && S.Accent == 0xFFFFFFFF ? C.Text : C.Argb(S.Accent) };
        var title = new TextBlock { Text = "Tokalot", FontFamily = C.Sans, FontWeight = FontWeights.Bold, FontSize = 23, Foreground = C.Text, Margin = new Thickness(8, 0, 0, 1), VerticalAlignment = VerticalAlignment.Center };
        var head = Ui.Row(logo, title);
        head.Margin = new Thickness(22, 26, 0, 4);

        var dismiss = new Border
        {
            Width = 22, Height = 22, CornerRadius = new CornerRadius(11), Background = Brushes.Transparent, Cursor = Cursors.Hand,
            Child = Icons.Get("close", 14, C.Sub), ToolTip = "Hide this tip",
        };
        var hint = Ui.Card(Ui.Stack(
            Spread(Ui.Text("Hold to talk", 13, C.Sub), dismiss),
            Ui.Row(KeyCap("Ctrl"), new TextBlock { Text = "+", FontSize = 14, Foreground = C.Sub, Margin = new Thickness(7, 0, 7, 0), VerticalAlignment = VerticalAlignment.Center }, KeyCap("Win")),
            Ui.Text("Tap once for hands-free. Esc cancels.", 12.5, C.Sub)), 16);
        ((StackPanel)hint.Child).Children[1].SetValue(MarginProperty, new Thickness(0, 8, 0, 8));
        hint.Margin = new Thickness(14, 0, 14, 18);
        hint.VerticalAlignment = VerticalAlignment.Bottom;
        if (S.HideShortcutTip) hint.Visibility = Visibility.Collapsed;
        dismiss.MouseEnter += (_, _) => dismiss.Background = C.Hover;
        dismiss.MouseLeave += (_, _) => dismiss.Background = Brushes.Transparent;
        void HideTip() { S.HideShortcutTip = true; S.Save(); hint.Visibility = Visibility.Collapsed; }
        dismiss.MouseLeftButtonUp += (_, _) => HideTip();
        Ui.Keys(dismiss, HideTip, "Hide this tip");

        var g = new Grid();
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition());
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.Children.Add(head);
        Grid.SetRow(nav, 1);
        g.Children.Add(nav);
        Grid.SetRow(hint, 2);
        g.Children.Add(hint);
        return g;
    }

    private static Border KeyCap(string k) => new()
    {
        Background = C.Field, BorderBrush = C.Pill, BorderThickness = new Thickness(1, 1, 1, 2), CornerRadius = new CornerRadius(7),
        Padding = new Thickness(10, 4, 10, 4), Child = Ui.Text(k, 14, bold: true),
    };

    private void RenderNav()
    {
        nav.Children.Clear();
        void Item(Page p, string icon, string label)
        {
            var active = p == CurrentPage;
            var row = Ui.Row(
                Spaced(Icons.Get(icon, 22, C.Text), 0, 0, 12, 0),
                Ui.Text(label, 15, C.Text, bold: active));
            var b = new Border
            {
                Child = row, CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(0, 0, 0, 4),
                Background = active ? C.NavActive : Brushes.Transparent, Cursor = Cursors.Hand,
            };
            b.MouseEnter += (_, _) => { if (!active) b.Background = C.Hover; };
            b.MouseLeave += (_, _) => { if (!active) b.Background = Brushes.Transparent; };
            b.MouseLeftButtonUp += (_, _) => Go(p);
            nav.Children.Add(Ui.Keys(b, () => Go(p), label));
        }
        Item(Page.Home, "home", "Home");
        Item(Page.Dictionary, "book", "Dictionary");
        Item(Page.Style, "style", "Style");
        Item(Page.Snippets, "snippet", "Snippets");
        if (S.NotesBeta) Item(Page.Notes, "notes", "Notes");
        nav.Children.Add(new Border { Height = 14 });
        Item(Page.Settings, "settings", "Settings");
    }

    /** Full height of the current page (screenshot mode). */
    internal double ExtentHeight => scroller.ExtentHeight;

    /**
     * Every text and key box holds a small scroll area of its own, and WPF lets it take the mouse wheel even
     * when it has nothing to scroll. So the page stopped while the pointer crossed one, which is most noticeable
     * over the stack of boxes in Settings › API keys. The wheel now goes to the page unless something under the
     * pointer can really scroll that way.
     */
    private void PassWheelToPage(object sender, MouseWheelEventArgs e)
    {
        ScrollViewer? inner = null;
        for (var d = e.OriginalSource as DependencyObject; d != null && d != scroller;
             d = d is Visual or System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d))
        {
            if (d is not ScrollViewer sv) continue;
            var canScroll = e.Delta > 0 ? sv.VerticalOffset > 0 : sv.VerticalOffset < sv.ScrollableHeight;
            if (sv.ScrollableHeight > 0 && canScroll) return; // a box with more text than shows: let it scroll
            inner ??= sv;
        }
        if (inner == null) return; // nothing in the way: the page gets the wheel as usual
        e.Handled = true;
        scroller.RaiseEvent(new MouseWheelEventArgs(e.MouseDevice, e.Timestamp, e.Delta) { RoutedEvent = UIElement.MouseWheelEvent, Source = scroller });
    }

    internal void SetStyleTab(string id) => styleTab = id;

    internal void OpenSnippetEditor(Snippet? s) { editing = s; editingNew = s == null; Render(); }

    public void Go(Page p)
    {
        if (p != CurrentPage) scroller.ScrollToTop();
        CurrentPage = p;
        Render();
    }

    /** Rebuilds the current page, keeping the scroll position. */
    public void Render()
    {
        var offset = scroller.VerticalOffset;
        if (CurrentPage == Page.Notes && !S.NotesBeta) CurrentPage = Page.Home; // voice notes were switched off (or restored away)
        RenderNav();
        var col = new StackPanel { MaxWidth = 760, Margin = new Thickness(36, 10, 36, 48) };
        switch (CurrentPage)
        {
            case Page.Home: BuildHome(col); break;
            case Page.Dictionary: BuildDictionary(col); break;
            case Page.Style: BuildStyle(col); break;
            case Page.Snippets: BuildSnippets(col); break;
            case Page.Notes: BuildNotes(col); break;
            case Page.Settings: BuildSettings(col); break;
        }
        wideLayout = IsWide;
        scroller.Content = wideLayout ? TwoColumns(col) : StripMarkers(col);
        scroller.UpdateLayout();
        scroller.ScrollToVerticalOffset(offset);
    }

    // ---------- two columns on wide windows ----------

    /** Marks the end of a page's full-width header (title and intro). */
    private sealed class HeaderEnd : FrameworkElement { }
    /** Marks where the second column starts; pages without one are balanced by section. */
    private sealed class ColBreak : FrameworkElement { }

    private const double ColumnGap = 32, WideAt = 1180;
    private bool wideLayout;
    /** Screenshot mode renders without a real window, so it sets the width here. */
    internal double? ForcedWidth { get; set; }
    private double ContentWidth => Math.Max(0, (ForcedWidth ?? ActualWidth / UiScale) - 232 - 72 - 20);
    private bool IsWide => ContentWidth >= WideAt;

    private static StackPanel StripMarkers(StackPanel col)
    {
        foreach (var m in col.Children.OfType<FrameworkElement>().Where(e => e is HeaderEnd or ColBreak).ToList()) col.Children.Remove(m);
        return col;
    }

    /** Rebuilds a one-column page as a header plus two columns. */
    private UIElement TwoColumns(StackPanel col)
    {
        var items = col.Children.Cast<UIElement>().ToList();
        col.Children.Clear();
        var root = new StackPanel { MaxWidth = 1500, Margin = col.Margin };

        int headerEnd = items.FindIndex(e => e is HeaderEnd);
        foreach (var e in items.Take(Math.Max(0, headerEnd))) root.Children.Add(e);
        var body = items.Skip(headerEnd + 1).ToList();

        var left = new StackPanel();
        var right = new StackPanel();
        int brk = body.FindIndex(e => e is ColBreak);
        if (brk >= 0)
        {
            foreach (var e in body.Take(brk)) left.Children.Add(e);
            foreach (var e in body.Skip(brk + 1)) right.Children.Add(e);
        }
        else
        {
            // Settings: keep sections whole and in order, filling the left column to about half.
            var groups = new List<List<UIElement>>();
            foreach (var e in body)
            {
                if (groups.Count == 0 || (e is FrameworkElement fe && Equals(fe.Tag, "section"))) groups.Add(new List<UIElement>());
                groups[^1].Add(e);
            }
            double colW = (Math.Min(1500, ContentWidth) - ColumnGap) / 2;
            var heights = groups.Select(g => g.Sum(e => { e.Measure(new Size(colW, double.PositiveInfinity)); return e.DesiredSize.Height; })).ToList();
            double total = heights.Sum(), acc = 0;
            for (int i = 0; i < groups.Count; i++)
            {
                var target = acc + heights[i] / 2 <= total / 2 ? left : right;
                if (target == left) acc += heights[i];
                foreach (var e in groups[i]) target.Children.Add(e);
            }
        }
        // The first item of each column shouldn't carry a big top gap.
        foreach (var c in new[] { left, right })
            if (c.Children.Count > 0 && c.Children[0] is FrameworkElement f && f.Margin.Top > 8)
                f.Margin = new Thickness(f.Margin.Left, 4, f.Margin.Right, f.Margin.Bottom);

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ColumnGap) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.Children.Add(left);
        Grid.SetColumn(right, 2);
        grid.Children.Add(right);
        root.Children.Add(grid);
        return root;
    }

    private void Toast(string msg)
    {
        toastText.Text = msg;
        var a = new DoubleAnimationUsingKeyFrames();
        a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(120))));
        a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(1700))));
        a.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(1950))));
        toast.BeginAnimation(OpacityProperty, a);
    }

    private static void Intro(StackPanel col, string title, string sub)
    {
        col.Children.Add(Ui.Heading(title));
        col.Children.Add(Spaced(Ui.Text(sub, 15, C.Sub), 0, 4, 0, 20));
        col.Children.Add(new HeaderEnd());
    }

    private static T Spaced<T>(T e, double l, double t, double r, double b) where T : FrameworkElement
    {
        e.Margin = new Thickness(l, t, r, b);
        return e;
    }

    private static void Open(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }

    private static TextBlock Link(string text, Action onClick)
    {
        var t = Ui.Text(text, 14, C.Link);
        t.Cursor = Cursors.Hand;
        t.MouseLeftButtonUp += (_, _) => onClick();
        return Ui.Keys(t, onClick, text);
    }

    /** A row that stretches its first child and right-aligns the rest. */
    private static Grid Spread(UIElement left, params UIElement[] right)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition());
        if (left is FrameworkElement lf) lf.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(left);
        for (int i = 0; i < right.Length; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(right[i], i + 1);
            if (right[i] is FrameworkElement fe) { fe.VerticalAlignment = VerticalAlignment.Center; fe.Margin = new Thickness(8, 0, 0, 0); }
            g.Children.Add(right[i]);
        }
        return g;
    }

    // ---------- Home ----------

    public static bool SetupComplete => S.CloudSttReady || ModelManager.IsReady;

    public void SetBannerProgress(int pct)
    {
        if (bannerText != null) bannerText.Text = $"Downloading Tokalot {App.Current.UpdateVersion}… {pct}%";
    }

    private void BuildHome(StackPanel col)
    {
        var app = App.Current;
        if (app.UpdateVersion != null)
        {
            var downloading = app.UpdateProgress != null;
            bannerText = Ui.Text(downloading ? $"Downloading Tokalot {app.UpdateVersion}… {app.UpdateProgress}%" : $"Tokalot {app.UpdateVersion} is available", 15);
            bannerText.VerticalAlignment = VerticalAlignment.Center;
            var banner = downloading
                ? Spread(bannerText)
                : Spread(bannerText, Ui.Button("Update", () => _ = app.InstallUpdate(), filled: true));
            col.Children.Add(Spaced(Ui.Card(banner, 14), 0, 0, 0, 14));
        }

        if (App.InsideAnotherAppsStorage)
        {
            var c = Ui.Stack(
                Ui.Text("Tokalot is installed inside another app's private storage", 15.5, C.Warn, bold: true),
                Spaced(Ui.Text("That happens when the installer is opened from within another app instead of from your browser's downloads. Windows then hides Tokalot from the Start menu, doesn't start it when you sign in, and can show it an empty set of settings. To fix it, download the installer with your web browser and run it from File Explorer. Your settings and history are kept.", 14, C.Sub), 0, 4, 0, 12),
                Ui.Button("Download the installer", () => Open(Updater.RepoUrl + "/releases/download/desktop/TokalotSetup.exe"), filled: true));
            col.Children.Add(Spaced(Ui.Card(c, 20), 0, 0, 0, 14));
        }

        if (!(app.Controller?.HotkeyWorks ?? true))
        {
            var c = Ui.Card(Ui.Text("Tokalot couldn't listen for Ctrl+Win. Quit it from the tray icon and open it again.", 15, C.Warn), 18);
            col.Children.Add(Spaced(c, 0, 0, 0, 14));
        }

        SpelledCard(col);

        if (!SetupComplete)
        {
            var c = Ui.Stack(
                Ui.Heading("Finish setup", 30),
                Spaced(Ui.Text("Add a Groq API key (free) or download the offline model, and Ctrl+Win will start working in any app.", 15, C.Sub), 0, 6, 0, 14),
                Ui.Button("Open settings", () => Go(Page.Settings), filled: true));
            col.Children.Add(Spaced(Ui.Card(c, 22), 0, 0, 0, 16));
        }

        // This month at a glance.
        var m = Usage.This();
        FrameworkElement Stat(string value, string label) => Ui.Stack(Ui.Heading(value, 32), Ui.Text(label, 13, C.Sub));
        var stats = new Grid { Margin = new Thickness(0, 10, 0, 0) };
        for (int i = 0; i < 3; i++) stats.ColumnDefinitions.Add(new ColumnDefinition());
        // Speaking pace: words Whisper heard per minute of recording this month. (Cost lives in Settings > Usage.)
        var now = DateTime.Now;
        var monthStart = new DateTimeOffset(new DateTime(now.Year, now.Month, 1)).ToUnixTimeMilliseconds();
        var spoken = History.All().Where(e => e.Time >= monthStart && e.DurationMs > 0 && e.Raw.Length > 0).ToList();
        var minutes = spoken.Sum(e => e.DurationMs) / 60000.0;
        var pace = minutes > 0 ? Math.Round(spoken.Sum(e => TextTools.WordCount(e.Raw)) / minutes).ToString(CultureInfo.InvariantCulture) : "\u2013";
        var cells = new[] { Stat(Ui.Compact(m.Words), "words"), Stat(m.Dictations.ToString(CultureInfo.InvariantCulture), "dictations"), Stat(pace, "words a minute") };
        for (int i = 0; i < 3; i++) { Grid.SetColumn(cells[i], i); stats.Children.Add(cells[i]); }
        var statCard = Ui.Stack(Ui.Text(DateTime.Now.ToString("MMMM yyyy", CultureInfo.InvariantCulture).ToUpperInvariant(), 12, C.Sub, bold: true), stats);
        if (m.Fillers + m.Corrections > 0)
            statCard.Children.Add(Spaced(Ui.Text($"Cleaned up {Plural(m.Fillers, "filler word")} and {Plural(m.Corrections, "self-correction")}", 13, C.Sub), 0, 10, 0, 0));
        var sc = Ui.Card(statCard, 22);
        sc.Cursor = Cursors.Hand;
        sc.MouseLeftButtonUp += (_, _) => Go(Page.Settings);
        col.Children.Add(Spaced(sc, 0, 0, 0, 16));

        (searchBox.Parent as Panel)?.Children.Remove(searchBox);
        col.Children.Add(new ColBreak());
        col.Children.Add(searchBox);
        historyBox = new StackPanel();
        col.Children.Add(historyBox);
        FillHistory();
    }

    private void FillHistory()
    {
        var box = historyBox;
        if (box == null) return;
        box.Children.Clear();
        var q = search.Text.Trim();
        var all = History.All();
        var list = q.Length == 0 ? all : all.Where(e =>
            e.Text.Contains(q, StringComparison.OrdinalIgnoreCase) || e.Raw.Contains(q, StringComparison.OrdinalIgnoreCase) ||
            e.AppLabel.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
        if (list.Count == 0)
        {
            box.Children.Add(Spaced(Ui.Heading(q.Length == 0 ? "Today" : "No matches", 30), 0, 24, 0, 12));
            box.Children.Add(Ui.Card(Ui.Text(q.Length == 0
                ? "Nothing yet. Click into any text box, hold Ctrl+Win and talk."
                : $"Nothing matches “{q}”.", 16, C.Sub), 24));
            return;
        }
        string lastDay = "";
        StackPanel? group = null;
        foreach (var e in list.Take(historyLimit))
        {
            var day = DayLabel(e.Time);
            if (day != lastDay)
            {
                box.Children.Add(Spaced(Ui.Heading(day, 30), 0, 24, 0, 12));
                group = new StackPanel();
                box.Children.Add(Ui.Card(group));
                lastDay = day;
            }
            else group!.Children.Add(Ui.Divider());
            group!.Children.Add(EntryView(e));
        }
        if (list.Count > historyLimit)
        {
            var more = Ui.Button("Show older", () => { historyLimit += 200; FillHistory(); });
            more.HorizontalAlignment = HorizontalAlignment.Center;
            box.Children.Add(Spaced(more, 0, 20, 0, 0));
        }
    }

    private UIElement EntryView(Entry e)
    {
        var box = new StackPanel { Margin = new Thickness(22, 18, 22, 16) };
        var open = expanded.Contains(e.Id);
        if (open)
        {
            // Selectable when expanded.
            box.Children.Add(new TextBox
            {
                Text = e.Text, IsReadOnly = true, BorderThickness = new Thickness(0), Background = Brushes.Transparent,
                Foreground = C.Text, FontSize = 16.5, FontFamily = C.Sans, TextWrapping = TextWrapping.Wrap, Padding = new Thickness(-2, 0, 0, 0),
            });
        }
        else
        {
            var t = Ui.Text(e.Text, 16.5);
            t.MaxHeight = 16.5 * 1.4 * 6;
            t.TextTrimming = TextTrimming.CharacterEllipsis;
            t.Cursor = Cursors.Hand;
            t.MouseLeftButtonUp += (_, _) => { expanded.Add(e.Id); FillHistory(); };
            box.Children.Add(t);
            t.Focusable = false;
        }

        if (showOriginal.Contains(e.Id))
        {
            var orig = new Border
            {
                Background = C.Field, CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 10, 14, 12), Margin = new Thickness(0, 12, 0, 0),
                Child = Ui.Stack(Ui.Text("ORIGINAL", 11, C.Sub, bold: true), new TextBox
                {
                    Text = e.Raw, IsReadOnly = true, BorderThickness = new Thickness(0), Background = Brushes.Transparent,
                    Foreground = C.Sub, FontSize = 14.5, FontFamily = C.Sans, TextWrapping = TextWrapping.Wrap, Padding = new Thickness(-2, 0, 0, 0),
                }),
            };
            box.Children.Add(orig);
        }

        var secs = e.DurationMs / 1000;
        var meta = DateTimeOffset.FromUnixTimeMilliseconds(e.Time).LocalDateTime.ToString("MMM d, h:mm tt", CultureInfo.InvariantCulture);
        if (e.AppLabel.Length > 0) meta += " · " + e.AppLabel;
        if (secs > 0) meta += $" · {secs}s";
        if (e.Cleaned) meta += " · AI";
        box.Children.Add(Spaced(Ui.Text(meta, 13.5, C.Sub), 0, 8, 0, 12));

        var actions = new List<UIElement> { Ui.Button("Copy", () => { _ = TextInjector.Copy(e.Text); Toast("Copied"); }, icon: "copy") };
        if (e.Pending && AudioStore.Exists(e.Id))
            actions.Insert(0, Ui.Button("Transcribe", () => App.Current.Controller?.Retry(e), filled: true));
        if (AudioStore.Exists(e.Id))
        {
            var playing = Player.PlayingId == e.Id;
            actions.Add(Ui.Button("", () => { if (playing) Player.Stop(); else Player.Play(e.Id); }, filled: playing, icon: playing ? "stop" : "play"));
        }
        if (e.Cleaned && e.Raw.Length > 0 && e.Raw != e.Text)
            actions.Add(Ui.Button("Original", () => { if (!showOriginal.Add(e.Id)) showOriginal.Remove(e.Id); FillHistory(); }, filled: showOriginal.Contains(e.Id)));
        if (open) actions.Add(Ui.Button("Less", () => { expanded.Remove(e.Id); FillHistory(); }));
        var row = Ui.Row(actions.ToArray());
        foreach (FrameworkElement a in row.Children) a.Margin = new Thickness(0, 0, 8, 0);

        var menu = new ContextMenu();
        // The button marks its click handled, so the menu has to open from the button's own action.
        Border more = null!;
        more = Ui.Button("", () => { menu.PlacementTarget = more; menu.IsOpen = true; }, icon: "more");
        var copyOrig = new MenuItem { Header = "Copy original" };
        copyOrig.Click += (_, _) => { _ = TextInjector.Copy(e.Raw.Length > 0 ? e.Raw : e.Text); Toast("Copied"); };
        var del = new MenuItem { Header = "Delete" };
        del.Click += (_, _) =>
        {
            if (Player.PlayingId == e.Id) Player.Stop(false);
            History.Delete(e.Id);
            AudioStore.Delete(e.Id);
        };
        menu.Items.Add(copyOrig);
        menu.Items.Add(del);
        more.ContextMenu = menu;
        box.Children.Add(Spread(row, more));
        return box;
    }

    private static string Plural(int n, string word) => $"{n} {word}{(n == 1 ? "" : "s")}";

    private static string DayLabel(long t)
    {
        var d = DateTimeOffset.FromUnixTimeMilliseconds(t).LocalDateTime.Date;
        var today = DateTime.Today;
        if (d == today) return "Today";
        if (d == today.AddDays(-1)) return "Yesterday";
        return d.Year == today.Year
            ? d.ToString("dddd, MMM d", CultureInfo.InvariantCulture)
            : d.ToString("MMM d, yyyy", CultureInfo.InvariantCulture);
    }

    // ---------- Dictionary ----------

    private void BuildDictionary(StackPanel col)
    {
        Intro(col, "Dictionary", "Names, places and jargon Tokalot should always spell right. They're given to speech recognition and to the cleanup model as hints.");
        SpelledCard(col);
        var (box, input) = Ui.Field("", "Add words, separated by commas, e.g. Kubernetes, Nguyen, PostgreSQL", multiLine: true);
        col.Children.Add(box);
        var add = Ui.Button("Add", () =>
        {
            var existing = new HashSet<string>(S.Words.Select(w => w.ToLowerInvariant()));
            var fresh = input.Text.Split(new[] { ',', '\n', '\r' }).Select(w => w.Trim())
                .Where(w => w.Length > 0 && existing.Add(w.ToLowerInvariant())).ToList();
            if (fresh.Count > 0)
            {
                S.Words.AddRange(fresh);
                S.Save();
                Render();
                Toast($"Added {fresh.Count} {(fresh.Count == 1 ? "word" : "words")}");
            }
            else if (input.Text.Trim().Length > 0) Toast("Already in your dictionary");
        }, filled: true);
        add.HorizontalAlignment = HorizontalAlignment.Right;
        col.Children.Add(Spaced(add, 0, 10, 0, 16));

        col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Learn from my corrections",
            "Off unless you turn it on. After Tokalot pastes a dictation, it looks at that text box again for up to two minutes. " +
            "If you respell one word into a name or a term (Kaitlin to Caitlyn, get hub to GitHub), the new spelling is added here, " +
            "and a note lets you undo it. Ordinary words are left alone. The text is only compared on this PC, never saved or sent; " +
            "the learned word is all that's kept. Works in most apps, but not ones that don't let other programs read their text boxes.",
            Ui.Switch(S.LearnWords, v => { S.LearnWords = v; S.Save(); }))), 0, 0, 0, 16));

        col.Children.Add(new ColBreak());
        if (S.Words.Count == 0)
        {
            col.Children.Add(Ui.Card(Ui.Text("No words yet.", 16, C.Sub), 22));
            return;
        }
        var rows = S.Words.OrderBy(w => w, StringComparer.OrdinalIgnoreCase).Select(w =>
        {
            var remove = Ui.Button("", () => { S.Words.Remove(w); S.LearnedWords.Remove(w); S.Save(); Render(); }, icon: "close");
            remove.BorderThickness = new Thickness(0);
            remove.Background = Brushes.Transparent;
            var name = S.LearnedWords.Contains(w)
                ? (UIElement)Ui.Row(Ui.Text(w, 16), Spaced(Ui.Text("learned", 12.5, C.Sub), 10, 2, 0, 0))
                : Ui.Text(w, 16);
            return (UIElement)Spaced(Spread(name, remove), 20, 8, 12, 8);
        }).ToArray();
        col.Children.Add(Ui.List(rows));
    }

    /**
     * Words the user spelled out letter by letter while dictating (see Learn.Spelled), offered for the dictionary.
     * Shown on Home and Dictionary until each is added or turned down.
     */
    private void SpelledCard(StackPanel col)
    {
        var words = S.SuggestedWords.Where(w => !S.Words.Contains(w, StringComparer.OrdinalIgnoreCase)).ToList();
        if (words.Count == 0) return;
        var rows = new List<UIElement>
        {
            Spaced(Ui.Stack(
                Ui.Text(words.Count == 1 ? "You spelled out a word" : $"You spelled out {words.Count} words", 15.5, bold: true),
                Ui.Text(words.Count == 1
                    ? "Add it to your dictionary so it's spelled right without spelling it next time."
                    : "Add them to your dictionary so they're spelled right without spelling them next time.", 13.5, C.Sub)), 20, 14, 18, 14),
        };
        foreach (var w in words)
        {
            var notNow = Ui.Button("Not now", () =>
            {
                S.SuggestedWords.Remove(w);
                if (!S.DismissedWords.Contains(w, StringComparer.OrdinalIgnoreCase)) S.DismissedWords.Add(w);
                S.Save();
                Render();
            });
            var add = Ui.Button("Add", () =>
            {
                S.SuggestedWords.Remove(w);
                if (!S.Words.Contains(w, StringComparer.OrdinalIgnoreCase)) S.Words.Add(w);
                S.Save();
                Render();
                Toast($"Added “{w}” to your dictionary");
            }, filled: true);
            rows.Add(Spaced(Spread(Ui.Text(w, 16), notNow, add), 20, 10, 16, 10));
        }
        col.Children.Add(Spaced(Ui.List(rows.ToArray()), 0, 0, 0, 16));
    }

    // ---------- Notes (beta) ----------

    private void BuildNotes(StackPanel col)
    {
        Intro(col, "Notes", "Dictate without a text box: hold Ctrl+Shift+Win, or pick New voice note in the tray menu. Each dictation is saved here as its own note and copied to the clipboard.");
        var notes = Notes.All();
        if (notes.Count == 0)
        {
            col.Children.Add(Ui.Card(Ui.Text("No notes yet. Press Ctrl+Shift+Win, or New voice note in the tray menu, and talk.", 16, C.Sub), 24));
            return;
        }
        string lastDay = "";
        StackPanel? group = null;
        foreach (var n in notes.Take(notesLimit))
        {
            var day = DayLabel(n.Time);
            if (day != lastDay)
            {
                // Each day is a section, so on a wide window the days fill both columns.
                var heading = Spaced(Ui.Heading(day, 30), 0, group == null ? 0 : 24, 0, 12);
                heading.Tag = "section";
                col.Children.Add(heading);
                group = new StackPanel();
                col.Children.Add(Ui.Card(group));
                lastDay = day;
            }
            else group!.Children.Add(Ui.Divider());
            group!.Children.Add(NoteView(n));
        }
        if (notes.Count > notesLimit)
        {
            var more = Ui.Button("Show older", () => { notesLimit += 200; Render(); });
            more.HorizontalAlignment = HorizontalAlignment.Center;
            col.Children.Add(Spaced(more, 0, 20, 0, 0));
        }
    }

    private UIElement NoteView(Note n)
    {
        var box = new StackPanel { Margin = new Thickness(22, 18, 22, 16) };
        // Selectable, so part of a note can be copied too.
        box.Children.Add(new TextBox
        {
            Text = n.Text, IsReadOnly = true, BorderThickness = new Thickness(0), Background = Brushes.Transparent,
            Foreground = C.Text, FontSize = 16.5, FontFamily = C.Sans, TextWrapping = TextWrapping.Wrap, Padding = new Thickness(-2, 0, 0, 0),
        });
        var meta = DateTimeOffset.FromUnixTimeMilliseconds(n.Time).LocalDateTime.ToString("MMM d, h:mm tt", CultureInfo.InvariantCulture);
        box.Children.Add(Spaced(Ui.Text(meta, 13.5, C.Sub), 0, 8, 0, 12));
        var copy = Ui.Button("Copy", () => { _ = TextInjector.Copy(n.Text); Toast("Copied"); }, icon: "copy");
        var delete = Ui.Button("Delete", () =>
        {
            if (!Ui.Dialog(this, "Delete this note? It can't be brought back.", "Delete")) return;
            Notes.Delete(n.Id);
            Render();
        });
        copy.Margin = new Thickness(0, 0, 8, 0);
        box.Children.Add(Ui.Row(copy, delete));
        return box;
    }

    // ---------- Snippets ----------

    private void BuildSnippets(StackPanel col)
    {
        Intro(col, "Snippets", "Say a trigger phrase and it's replaced with the full text, exactly as you wrote it. The AI never rewrites snippet text.");
        if (editing == null && !editingNew)
            col.Children.Add(Spaced(Ui.Button("New snippet", () => { editingNew = true; Render(); }, filled: true), 0, 0, 0, 16));
        else col.Children.Add(Spaced(SnippetEditor(editing), 0, 0, 0, 16));
        col.Children.Add(new ColBreak());

        if (S.Snippets.Count == 0)
        {
            col.Children.Add(Ui.Card(Ui.Text("Example: say \"my email\" and get your full email address, or \"home address\" for your full street address.", 16, C.Sub), 22));
            return;
        }
        var rows = S.Snippets.Select(sn =>
        {
            var body = Ui.Text(sn.Text, 14.5, C.Sub);
            body.MaxHeight = 14.5 * 1.4 * 3;
            body.TextTrimming = TextTrimming.CharacterEllipsis;
            var item = new Border
            {
                Padding = new Thickness(22, 15, 22, 15), Background = Brushes.Transparent, Cursor = Cursors.Hand,
                Child = Ui.Stack(Ui.Text($"“{sn.Trigger}”", 16, bold: true), body),
            };
            item.MouseEnter += (_, _) => item.Background = C.Hover;
            item.MouseLeave += (_, _) => item.Background = Brushes.Transparent;
            item.MouseLeftButtonUp += (_, _) => { editing = sn; editingNew = false; Render(); scroller.ScrollToTop(); };
            return (UIElement)Ui.Keys(item, () => { editing = sn; editingNew = false; Render(); scroller.ScrollToTop(); }, sn.Trigger);
        }).ToArray();
        col.Children.Add(Ui.List(rows));
    }

    private FrameworkElement SnippetEditor(Snippet? existing)
    {
        var (tBox, trig) = Ui.Field(existing?.Trigger ?? "", "Trigger phrase you'll say");
        var (bBox, body) = Ui.Field(existing?.Text ?? "", "Text to insert", multiLine: true);
        void Close() { editing = null; editingNew = false; Render(); }
        var buttons = new List<UIElement>
        {
            Ui.Button("Save", () =>
            {
                var t = trig.Text.Trim();
                if (t.Length == 0 || body.Text.Trim().Length == 0) { Toast("Add a trigger and some text"); return; }
                S.Snippets.RemoveAll(x => x == existing || string.Equals(x.Trigger, t, StringComparison.OrdinalIgnoreCase));
                S.Snippets.Add(new Snippet(t, body.Text));
                S.Save();
                Close();
            }, filled: true),
            Ui.Button("Cancel", Close),
        };
        if (existing != null) buttons.Add(Ui.Button("Delete", () => { S.Snippets.Remove(existing); S.Save(); Close(); }));
        var row = Ui.Row(buttons.ToArray());
        foreach (FrameworkElement b in row.Children) b.Margin = new Thickness(0, 0, 8, 0);
        var card = Ui.Card(Ui.Stack(
            Ui.Text(existing == null ? "New snippet" : "Edit snippet", 17, bold: true),
            Spaced(tBox, 0, 12, 0, 0), Spaced(bBox, 0, 10, 0, 14), row), 20);
        Dispatcher.BeginInvoke(() => trig.Focus());
        return card;
    }

    // ---------- Style ----------

    private void BuildStyle(StackPanel col)
    {
        Intro(col, "Style", "How the cleanup model writes what you say, per kind of app. Tokalot checks which app you're in when you press Ctrl+Win.");
        if (!S.CleanupReady)
        {
            var warn = Ui.Card(Ui.Text("AI cleanup isn't active, so styles won't apply yet. Pick a cleanup model and add its key in Settings.", 15, C.Warn), 18);
            warn.Cursor = Cursors.Hand;
            warn.MouseLeftButtonUp += (_, _) => Go(Page.Settings);
            col.Children.Add(Spaced(warn, 0, 0, 0, 16));
        }

        col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Polish my wording",
            "Off: your own words are kept, with fillers removed and punctuation and formatting fixed. On: the AI may also tighten and clarify what you said, " +
            "and for a few seconds after each dictation Ctrl+Win+Z puts your own words back.",
            Ui.Switch(S.Polish, v => { S.Polish = v; S.Save(); }))), 0, 0, 0, 18));

        var tabs = new WrapPanel();
        foreach (var c in Catalog.Categories)
        {
            var on = c.Id == styleTab;
            var t = Ui.Stadium(new Border
            {
                Padding = new Thickness(16, 9, 16, 9), Margin = new Thickness(0, 0, 8, 8),
                Background = on ? C.Text : C.Card, BorderBrush = on ? C.Text : C.Pill, BorderThickness = new Thickness(1), Cursor = Cursors.Hand,
                Child = Ui.Text(c.Label, 14.5, on ? C.Card : C.Text),
            });
            var id = c.Id;
            t.MouseLeftButtonUp += (_, _) => { styleTab = id; Render(); };
            tabs.Children.Add(Ui.Keys(t, () => { styleTab = id; Render(); }, c.Label));
        }
        col.Children.Add(Spaced(tabs, 0, 0, 0, 4));
        var cat = Catalog.CategoryById(styleTab);
        col.Children.Add(Spaced(Ui.Text(cat.Blurb, 14, C.Sub), 4, 0, 0, 12));

        var current = S.StyleFor(cat);
        col.Children.Add(Ui.List(Catalog.Styles.Select(st => (UIElement)Ui.Choice(st.Label, st.Example, current.Id == st.Id, () =>
        {
            S.CategoryStyles[cat.Id] = st.Id;
            S.Save();
            Render();
        })).ToArray()));
        if (cat.Id == "AI_CODE")
            col.Children.Add(Spaced(Ui.Text("In these apps Tokalot also keeps technical terms exact and puts file names, commands and code names in `backticks`. Lists use markdown dashes.", 13, C.Sub), 4, 8, 0, 0));
        if (cat.Id == "EMAIL")
            col.Children.Add(Spaced(Ui.Text("In email apps a spoken greeting and sign-off go on their own lines, with blank lines between paragraphs.", 13, C.Sub), 4, 8, 0, 0));

        // Apps you've dictated into (tap to change their category).
        var apps = History.All().Where(e => e.AppKey.Length > 0).GroupBy(e => e.AppKey).Select(g => g.First()).Take(30).ToList();
        if (apps.Count > 0)
        {
            col.Children.Add(new ColBreak());
            col.Children.Add(Spaced(Ui.Heading("Your apps", 28), 0, 28, 0, 4));
            col.Children.Add(Spaced(Ui.Text("Click an app to change which style it uses.", 14, C.Sub), 0, 0, 0, 12));
            var rows = apps.Select(e =>
            {
                var appCat = AppDetect.Categorize(new ActiveApp(e.AppKey, e.AppLabel), S);
                var row = new Border
                {
                    Padding = new Thickness(22, 13, 22, 13), Background = Brushes.Transparent, Cursor = Cursors.Hand,
                    Child = Spread(Ui.Text(e.AppLabel, 16), Ui.Text(appCat.Label, 14.5, C.Sub)),
                };
                row.MouseEnter += (_, _) => row.Background = C.Hover;
                row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
                var menu = new ContextMenu();
                foreach (var c in Catalog.Categories)
                {
                    var mi = new MenuItem { Header = c.Label, IsCheckable = true, IsChecked = c.Id == appCat.Id };
                    var id = c.Id;
                    mi.Click += (_, _) => { S.AppOverrides[e.AppKey] = id; S.Save(); Render(); };
                    menu.Items.Add(mi);
                }
                row.ContextMenu = menu;
                row.MouseLeftButtonUp += (_, _) => { menu.PlacementTarget = row; menu.IsOpen = true; };
                return (UIElement)Ui.Keys(row, () => { menu.PlacementTarget = row; menu.IsOpen = true; }, e.AppLabel);
            }).ToArray();
            col.Children.Add(Ui.List(rows));
        }

        if (!col.Children.OfType<ColBreak>().Any()) col.Children.Add(new ColBreak());
        col.Children.Add(Spaced(Ui.Heading("Your instructions", 28), 0, 28, 0, 4));
        col.Children.Add(Spaced(Ui.Text("Applies everywhere. E.g. \"Use US spelling\", \"Write numbers as digits\", \"Never use exclamation points\".", 14, C.Sub), 0, 0, 0, 12));
        var (box, input) = Ui.Field(S.CustomInstructions, "Optional", multiLine: true);
        input.TextChanged += (_, _) => { S.CustomInstructions = input.Text; S.Save(); };
        col.Children.Add(box);
    }

    // ---------- Settings ----------

    private static void Section(StackPanel col, string title)
    {
        var l = Ui.Label(title);
        l.Tag = "section";
        col.Children.Add(l);
    }

    private void BuildSettings(StackPanel col)
    {
        col.Children.Add(Ui.Heading("Settings"));
        col.Children.Add(new HeaderEnd());

        // --- Setup
        Section(col, "Setup");
        UIElement Status(string title, bool done, string doneText, string notDone, UIElement? action = null)
        {
            var st = Ui.Text(done ? doneText : notDone, 13.5, done ? C.Good : C.Warn);
            if (title.StartsWith("Offline")) modelStatus = st;
            return Spaced(Spread(Ui.Stack(Ui.Text(title, 15.5), st), action ?? new Border()), 20, 13, 16, 13);
        }
        var hookOk = App.Current.Controller?.HotkeyWorks ?? true;
        var modelText = ModelManager.IsReady ? "Downloaded" : downloadPct != null ? $"Downloading {downloadPct}%" : downloadError != null ? "Failed: " + downloadError : "Not downloaded";
        col.Children.Add(Ui.List(
            Status("Ctrl+Win shortcut", hookOk, "Working", "Not working. Restart Tokalot."),
            Status("Speech-to-text key", S.CloudSttReady, "Added", "Add a Groq key below (free)"),
            Status("Offline backup model (60 MB)", ModelManager.IsReady || downloadPct != null, modelText, modelText,
                ModelManager.IsReady || downloadPct != null ? null : Ui.Button("Download", StartDownload)),
            Ui.SettingRow("Start with Windows", Updater.CanUpdate ? "Runs quietly in the tray so Ctrl+Win always works." : "Available in the installed version.",
                Ui.Switch(S.LaunchAtStartup, v => { S.LaunchAtStartup = v; S.Save(); if (Updater.CanUpdate) Startup.Apply(v); }))));
        col.Children.Add(Spaced(Ui.Text("Windows asks before any app can use the microphone. If dictation hears nothing, check Settings › Privacy & security › Microphone and allow desktop apps.", 13, C.Sub), 4, 8, 0, 0));

        // --- Appearance
        Section(col, "Appearance");
        col.Children.Add(Ui.List(new[] { ("system", "Match Windows"), ("light", "Light"), ("dark", "Dark") }
            .Select(t => (UIElement)Ui.Choice(t.Item2, "", S.Theme == t.Item1, () =>
            {
                if (S.Theme == t.Item1) return;
                S.Theme = t.Item1; S.Save();
                App.Current.ApplyTheme();
            })).ToArray()));
        col.Children.Add(Spaced(Ui.Text("Accent color (voice bars, disc and edge tab)", 14, C.Sub), 4, 16, 0, 8));
        var swatches = new WrapPanel { Margin = new Thickness(4, 0, 0, 0) };
        foreach (var (name, argb) in Catalog.Accents)
        {
            var on = S.Accent == argb;
            var dot = new Border
            {
                Width = 32, Height = 32, CornerRadius = new CornerRadius(16), Background = C.Argb(argb), Margin = new Thickness(0, 0, 10, 0),
                BorderBrush = on ? C.Text : C.Pill, BorderThickness = new Thickness(on ? 3 : 1), Cursor = Cursors.Hand, ToolTip = name,
            };
            dot.MouseLeftButtonUp += (_, _) => { S.Accent = argb; S.Save(); Render(); };
            swatches.Children.Add(Ui.Keys(dot, () => { S.Accent = argb; S.Save(); Render(); }, name + " accent"));
        }
        col.Children.Add(swatches);

        // --- Recording indicator
        Section(col, "Recording indicator");
        col.Children.Add(Ui.List(IndicatorView.Styles.Select(id => (UIElement)IndicatorRow(id)).ToArray()));
        var dockRow = Ui.Row(Ui.Text("Edge", 14, C.Sub));
        ((FrameworkElement)dockRow.Children[0]).Margin = new Thickness(4, 0, 12, 0);
        foreach (var (id, label) in new[] { ("bottom", "Bottom"), ("left", "Left"), ("right", "Right") })
        {
            var b = Ui.Button(label, () =>
            {
                S.IndicatorDock = id;
                S.IndicatorAlong = 0.5;
                S.Save();
                App.Current.Controller?.RefreshIndicator();
                Render();
            }, filled: S.IndicatorDock == id);
            b.Margin = new Thickness(0, 0, 8, 0);
            dockRow.Children.Add(b);
        }
        if (S.IndicatorStyle != "ripple")
            dockRow.Children.Add(Ui.Button("Reset to default", () =>
            {
                S.IndicatorStyle = "ripple"; S.IndicatorDock = "bottom"; S.IndicatorAlong = 0.5; S.Save();
                App.Current.Controller?.RefreshIndicator();
                Render();
            }));
        col.Children.Add(Spaced(dockRow, 0, 12, 0, 0));
        col.Children.Add(Spaced(Ui.Text("Or drag the indicator itself to the bottom, left or right edge of your screen. On the sides it turns to face the screen.", 13, C.Sub), 4, 8, 0, 0));
        col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Show when idle", "A slim bar stays on the edge between dictations. Click it to dictate, drag it to move it. Hidden while an app is full screen.",
            Ui.Switch(S.ShowIdleIndicator, v => { S.ShowIdleIndicator = v; S.Save(); App.Current.Controller?.RefreshIndicator(); }))), 0, 12, 0, 0));

        // --- Recording
        Section(col, "Recording");
        col.Children.Add(Ui.List(
            Spaced(Ui.Stack(Ui.Text("How to dictate", 15.5),
                Ui.Text("Hold Ctrl+Win while you talk and let go to paste. Or tap Ctrl+Win once for hands-free, then tap again to finish. Esc cancels.", 13.5, C.Sub)), 20, 13, 18, 13),
            Ui.SettingRow("Auto-stop after 30 s of silence", "Hands-free mode only. Long pauses to think are fine.",
                Ui.Switch(S.AutoStop, v => { S.AutoStop = v; S.Save(); })),
            Ui.SettingRow("Transcribe while I talk", "In a long dictation, what you've said so far is sent to the speech service each time you pause, so the wait at the end stays short. Nothing appears until you finish. Needs a cloud speech service and AI cleanup.",
                Ui.Switch(S.LiveStt, v => { S.LiveStt = v; S.Save(); })),
            Ui.SettingRow("Hands-free reminder", "A short note above the indicator when hands-free starts. Its X turns this off.",
                Ui.Switch(!S.HideHandsFreeHint, v => { S.HideHandsFreeHint = !v; S.Save(); })),
            Ui.SettingRow("Sounds", "A soft tone when recording starts, stops, finishes or fails.",
                Ui.Switch(S.Sounds, v => { S.Sounds = v; S.Save(); if (v) Sounds.Play(Sounds.Kind.Done); })),
            Ui.SettingRow("Detect language automatically", "Off keeps it English-only, which is most accurate for English. The offline backup is English-only either way.",
                Ui.Switch(S.AutoLanguage, v => { S.AutoLanguage = v; S.Save(); })),
            Ui.SettingRow("Voice notes (beta)", "A Notes page, a tray item and Ctrl+Shift+Win to dictate a note without a text box. Each dictation is saved as its own note and copied to the clipboard.",
                Ui.Switch(S.NotesBeta, v => { S.NotesBeta = v; S.Save(); Render(); }))));

        // --- Speed
        Section(col, "Speed");
        var speedRows = new List<UIElement>
        {
            Ui.SettingRow("Quick mode (beta)", "Skips the AI cleanup when a short dictation (20 words or fewer) has nothing for it to fix: no ums, corrections, repeats or spoken punctuation. " +
                "The text is tidied on this computer instead, which is instant. Email, snippets, the very casual style and your own instructions always go through the AI.",
                Ui.Switch(S.QuickSkip, v => { S.QuickSkip = v; S.Save(); })),
        };
        var timings = Dictation.Recent;
        speedRows.Add(Spaced(Ui.Stack(
            Ui.Text("Last dictations", 15.5),
            Ui.Text(timings.Length == 0 ? "Nothing yet since Tokalot started. Dictate something, then reopen Settings." : string.Join("\n", timings.Take(8)), 13, C.Sub)), 20, 13, 18, 13));
        col.Children.Add(Ui.List(speedRows.ToArray()));
        if (timings.Length > 0)
            col.Children.Add(Spaced(Ui.Button("Copy timings", () => { try { Clipboard.SetText(string.Join("\r\n", timings)); Toast("Copied"); } catch { } }), 0, 10, 0, 0));

        // --- Speech to text
        Section(col, "Speech to text");
        col.Children.Add(Ui.List(Catalog.Stt.Select(o =>
        {
            var needsKey = o.Service != null && S.Key(o.Service).Length == 0;
            return (UIElement)Ui.Choice(o.Label, o.Note + (needsKey ? " Needs a key." : ""), S.Stt == o.Id, () => { S.Stt = o.Id; S.Save(); Render(); });
        }).ToArray()));
        if (S.Stt != "LOCAL") col.Children.Add(ModelField(S.SttModel(S.SttOption), v => { S.SttModels[S.Stt] = v.Trim(); S.Save(); }));

        // --- Cleanup
        Section(col, "AI cleanup");
        col.Children.Add(Ui.List(Catalog.Cleanup.Select(o =>
        {
            var needsKey = o.Service != null && S.Key(o.Service).Length == 0;
            return (UIElement)Ui.Choice(o.Label, o.Note + (needsKey ? " Needs a key." : ""), S.Cleanup == o.Id, () => { S.Cleanup = o.Id; S.Save(); Render(); });
        }).ToArray()));
        if (S.Cleanup != "OFF")
        {
            col.Children.Add(ModelField(S.CleanupModel(S.CleanupOption), v => { S.CleanupModels[S.Cleanup] = v.Trim(); S.Save(); }));
            var result = Ui.Text("", 14.5, C.Sub);
            col.Children.Add(Spaced(Ui.Button("Test cleanup", () => TestCleanup(result)), 0, 12, 0, 0));
            col.Children.Add(Spaced(result, 0, 8, 0, 0));
        }
        col.Children.Add(Spaced(Ui.Text("If your chosen provider fails or hits a limit, Tokalot tries another provider you have a key for, then falls back to offline.", 13, C.Sub), 4, 8, 0, 0));

        // --- Keys
        Section(col, "API keys");
        var keys = new StackPanel();
        foreach (var (id, name, page) in Catalog.Services)
        {
            var (box, input) = Ui.Secret(S.Key(id), "Paste key");
            input.PasswordChanged += (_, _) => { S.SetKey(id, input.Password); S.Save(); };
            var pageLink = Link("Get one at " + page, () => Open("https://" + page));
            pageLink.FontSize = 13;
            keys.Children.Add(Spaced(Ui.Text(name, 15.5, bold: true), 0, keys.Children.Count == 0 ? 0 : 16, 0, 0));
            keys.Children.Add(pageLink);
            keys.Children.Add(Spaced(box, 0, 6, 0, 0));
        }
        col.Children.Add(Ui.Card(keys, 20));
        col.Children.Add(Spaced(Ui.Text("Keys are stored only on this PC, encrypted with your Windows account. They never leave it unless you turn on Sync below and choose to include them.", 13, C.Sub), 4, 8, 0, 0));

        // --- Recordings
        Section(col, "Recordings");
        col.Children.Add(Ui.List(new[] { (0, "Don't save audio"), (7, "Keep 7 days"), (30, "Keep 30 days"), (int.MaxValue, "Keep forever") }
            .Select(t => (UIElement)Ui.Choice(t.Item2, "", S.AudioKeepDays == t.Item1, () =>
            {
                if (t.Item1 < S.AudioKeepDays && AudioStore.TotalBytes() > 0 && !Ui.Dialog(this,
                        t.Item1 == 0 ? "Delete all saved recordings now? Transcripts stay."
                            : $"Delete recordings older than {t.Item1} days now? Transcripts stay.", "Delete")) return;
                S.AudioKeepDays = t.Item1; S.Save();
                Task.Run(() => AudioStore.Prune(t.Item1)).ContinueWith(_ => Dispatcher.BeginInvoke(Render));
                Render();
            })).ToArray()));
        var mb = AudioStore.TotalBytes() / 1_048_576.0;
        col.Children.Add(Spaced(Spread(Ui.Text($"Using {mb:0.0} MB", 14, C.Sub), Ui.Button("Delete all", () =>
        {
            if (!Ui.Dialog(this, "Delete all saved recordings? Transcripts stay.", "Delete")) return;
            Player.Stop(false);
            AudioStore.Prune(0);
            Render();
        })), 4, 10, 0, 0));

        // --- Sync (optional)
        Section(col, "Sync");
        BuildSync(col);

        // --- Backup
        Section(col, "Backup");
        bool withAudio = false, withKeys = false;
        col.Children.Add(Ui.List(
            Ui.SettingRow("Include recordings", "Makes the file much bigger.", Ui.Switch(false, v => withAudio = v)),
            Ui.SettingRow("Include API keys", "Only if you'll keep the file somewhere private. Anyone with the file could use your keys.", Ui.Switch(false, v => withKeys = v))));
        var backupRow = Ui.Row(Ui.Button("Back up…", () => DoBackup(withAudio, withKeys), filled: true), Ui.Button("Restore…", DoRestore));
        ((FrameworkElement)backupRow.Children[0]).Margin = new Thickness(0, 0, 10, 0);
        col.Children.Add(Spaced(backupRow, 0, 12, 0, 0));
        col.Children.Add(Spaced(Ui.Text("Saves one .zip file wherever you choose: Documents, OneDrive, a USB stick, etc.", 13, C.Sub), 4, 8, 0, 0));

        // --- Usage
        Section(col, "Usage");
        var months = Usage.Recent();
        if (months.Count == 0) months.Add((DateTime.Now.ToString("MMMM yyyy", CultureInfo.InvariantCulture), Usage.This()));
        col.Children.Add(Ui.List(months.Select(mo =>
        {
            var (label, m) = mo;
            var box = new StackPanel { Margin = new Thickness(20, 15, 20, 15) };
            box.Children.Add(Spread(Ui.Text(label, 15.5, bold: true), Ui.Text(Ui.Money(m.Total), 15.5, bold: true)));
            box.Children.Add(Ui.Text($"{m.Dictations} dictations · {m.Words} words · {m.Local} offline", 13.5, C.Sub));
            foreach (var o in Catalog.Stt)
            {
                var s = m.SttSeconds.GetValueOrDefault(o.Id);
                if (s > 0) box.Children.Add(Ui.Text($"{o.Label}: {s / 60:0.0} min billed · {Ui.Money(m.SttCost(o.Id))}", 13.5, C.Sub));
            }
            foreach (var o in Catalog.Cleanup)
            {
                long i = m.TokensIn.GetValueOrDefault(o.Id), x = m.TokensOut.GetValueOrDefault(o.Id);
                if (i + x > 0) box.Children.Add(Ui.Text($"{o.Label}: {Ui.Compact(i)} in / {Ui.Compact(x)} out tokens · {Ui.Money(m.LlmCost(o.Id))}", 13.5, C.Sub));
            }
            return (UIElement)box;
        }).ToArray()));
        col.Children.Add(Spaced(Ui.Text("Estimated at each provider's paid list price. Free-tier usage actually costs $0. Check your provider's dashboard for real billing.", 13, C.Sub), 4, 8, 0, 0));

        // --- About
        Section(col, "About");
        var about = new StackPanel();
        about.Children.Add(Ui.Text("Tokalot for Windows " + Updater.CurrentVersion, 16, bold: true));
        about.Children.Add(Spaced(Ui.Text("Your recordings, history and keys stay on this PC. Dictations go only to the speech and cleanup services you chose, with your own keys. No Tokalot servers, accounts or tracking. The Ctrl+Win listener only watches for that shortcut; it never records your typing.", 14, C.Sub), 0, 4, 0, 0));
        about.Children.Add(Spaced(Link("Source code: github.com/sidewinderzz/tokalot", () => Open(Updater.RepoUrl)), 0, 8, 0, 0));
        var status = Ui.Text("", 14, C.Sub);
        about.Children.Add(Spaced(Ui.Button("Check for updates", async () =>
        {
            if (!Updater.CanUpdate) { status.Text = "Updates work in the installed version (TokalotSetup.exe)."; return; }
            status.Text = "Checking…";
            var v = await App.Current.CheckForUpdates();
            status.Text = v != null ? $"Version {v} is available. See the banner on Home." : "You're up to date.";
        }), 0, 12, 0, 0));
        about.Children.Add(Spaced(status, 0, 6, 0, 0));
        var licenses = new TextBox
        {
            Text = Licenses.Text, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, FontSize = 12.5, Foreground = C.Sub,
            Background = C.Field, BorderThickness = new Thickness(0), Padding = new Thickness(12), MaxHeight = 320,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 8, 0, 0),
        };
        about.Children.Add(Spaced(Link("Open-source licenses", () =>
            licenses.Visibility = licenses.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible), 0, 12, 0, 0));
        about.Children.Add(licenses);
        about.Children.Add(Spaced(Link("Quit Tokalot", () => App.Current.Quit()), 0, 12, 0, 0));
        col.Children.Add(Ui.Card(about, 20));
    }

    /** One choosable indicator style with a live preview on a little dark "screen". */
    private UIElement IndicatorRow(string id)
    {
        var (name, desc) = IndicatorView.Info(id);
        var selected = S.IndicatorStyle == id;
        var start = DateTime.UtcNow;
        var view = new IndicatorView
        {
            Look = id, Dock = "bottom", Scale = 0.85, CurrentMode = IndicatorView.Mode.Listening,
            VerticalAlignment = VerticalAlignment.Bottom, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 8),
            Level = () =>
            {
                // A made-up voice so the preview moves like real speech.
                double t = (DateTime.UtcNow - start).TotalSeconds + id.Length;
                double syl = Math.Max(0, Math.Sin(t * Math.PI * 2 * 2.4));
                double talking = Math.Sin(t * Math.PI * 2 * 0.21 + 0.6) > -0.35 ? 1 : 0.06;
                double v = (0.3 + 0.7 * syl * syl) * talking * (0.8 + 0.2 * Math.Sin(t * 7.3));
                return (float)(v * v / 8);
            },
        };
        var screen = new Grid { Width = 150, Height = 66, ClipToBounds = true };
        screen.Children.Add(new Border { Background = C.Hex("#1F2023"), CornerRadius = new CornerRadius(10) });
        screen.Children.Add(new Border { Background = C.Hex("#141416"), Height = 8, VerticalAlignment = VerticalAlignment.Bottom, CornerRadius = new CornerRadius(0, 0, 10, 10) });
        screen.Children.Add(view);

        var dot = new Border
        {
            Width = 20, Height = 20, CornerRadius = new CornerRadius(10), VerticalAlignment = VerticalAlignment.Center,
            Background = selected ? C.Text : C.Card, BorderBrush = C.Pill, BorderThickness = new Thickness(selected ? 0 : 2), Margin = new Thickness(0, 0, 16, 0),
        };
        var title = Ui.Row(Ui.Text(name, 15.5, bold: selected));
        if (id == "ripple")
        {
            var badge = new Border { Background = C.Field, CornerRadius = new CornerRadius(8), Padding = new Thickness(8, 1, 8, 2), Margin = new Thickness(10, 0, 0, 0), Child = Ui.Text("Default", 11.5, C.Sub) };
            title.Children.Add(badge);
        }
        var texts = Ui.Stack(title, Ui.Text(desc, 13, C.Sub));
        texts.VerticalAlignment = VerticalAlignment.Center;
        texts.Margin = new Thickness(18, 0, 0, 0);

        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.Children.Add(dot);
        Grid.SetColumn(screen, 1); g.Children.Add(screen);
        Grid.SetColumn(texts, 2); g.Children.Add(texts);
        var row = new Border { Padding = new Thickness(20, 12, 20, 12), Child = g, Background = Brushes.Transparent, Cursor = Cursors.Hand };
        row.MouseEnter += (_, _) => row.Background = C.Hover;
        row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
        void Pick()
        {
            S.IndicatorStyle = id;
            S.Save();
            App.Current.Controller?.RefreshIndicator();
            Render();
        }
        row.MouseLeftButtonUp += (_, _) => Pick();
        return Ui.Keys(row, Pick, name);
    }

    private static UIElement ModelField(string value, Action<string> save)
    {
        var (box, input) = Ui.Field(value, "Model");
        input.TextChanged += (_, _) => save(input.Text); // empty = the provider's default model
        return Spaced(Ui.Stack(Spaced(Ui.Text("Model name (change only if the provider renames it)", 13, C.Sub), 4, 10, 0, 4), box), 0, 0, 0, 0);
    }

    private async void TestCleanup(TextBlock result)
    {
        const string sample = "um so i was thinking uh we could meet on tuesday no wait wednesday at the the office around 3 and bring three things one the contract two the laptop three the charger";
        result.Text = "Testing…";
        var sw = Stopwatch.StartNew();
        try
        {
            var r = await Task.Run(() => Cleanup.Run(S, S.CleanupOption, sample, false, Catalog.CategoryById("OTHER"), null));
            result.Text = $"“{r.Text}”\n{sw.ElapsedMilliseconds} ms";
        }
        catch (Exception e)
        {
            result.Text = "Failed: " + e.Message;
        }
    }

    private void StartDownload()
    {
        if (downloadPct != null) return;
        downloadPct = 0;
        downloadError = null;
        Render();
        var progress = new Progress<int>(p =>
        {
            downloadPct = p;
            if (modelStatus != null) modelStatus.Text = $"Downloading {p}%";
        });
        Task.Run(() => ModelManager.Download(progress)).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            downloadPct = null;
            downloadError = t.Result;
            Render();
        }));
    }

    internal void ShowToast(string msg) => Toast(msg);

    private const string SyncInfo =
        "Tokalot keeps your dictionary, snippets, styles and instructions in one small file. Put that file in a folder you already sync, " +
        "such as OneDrive, Google Drive, Dropbox or Syncthing, and point each of your devices at it. Each device reads the file and adds its own changes.\n\n" +
        "It's private. There is no Tokalot account and no Tokalot server. The file only goes where your own sync service takes it. " +
        "Your history and recordings are never put in it.\n\n" +
        "API keys are left out unless you turn on Include API keys. The file isn't encrypted, so only do that if the folder is private to you. " +
        "You can stop syncing at any time; your settings stay on this device.";

    /** Optional: share dictionary, snippets, styles and instructions between devices through one file in a synced folder. */
    private void BuildSync(StackPanel col)
    {
        var info = Ui.Button("", () => Ui.Dialog(this, SyncInfo, "Got it", cancel: null, title: "How sync works"), icon: "info");
        info.ToolTip = "How sync works";
        System.Windows.Automation.AutomationProperties.SetName(info, "How sync works");

        async void Use(string path)
        {
            S.SyncFile = path;
            S.Save();
            await Sync.Run();
            Render();
        }

        if (S.SyncFile.Length == 0)
        {
            var create = Ui.Button("Create a sync file", () =>
            {
                var dlg = new Microsoft.Win32.SaveFileDialog { FileName = Sync.FileName, Filter = "Tokalot sync file|*.json", DefaultExt = ".json", OverwritePrompt = false };
                if (dlg.ShowDialog(this) == true) Use(dlg.FileName);
            }, filled: true);
            var existing = Ui.Button("Use an existing sync file", () =>
            {
                var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "Tokalot sync file|*.json" };
                if (dlg.ShowDialog(this) == true) Use(dlg.FileName);
            });
            create.Margin = new Thickness(0, 0, 8, 0);
            var buttons = Ui.Row(create, existing);
            buttons.Margin = new Thickness(20, 0, 20, 16);
            col.Children.Add(Ui.Card(Ui.Stack(
                Ui.SettingRow("Sync settings between your devices", "Optional. Off.", info), buttons)));
            return;
        }

        var status = Ui.Text(Sync.Status(), 13.5, Sync.LastError != null ? C.Warn : C.Good);
        var where = Ui.Text(S.SyncFile, 13, C.Sub);
        where.TextTrimming = TextTrimming.CharacterEllipsis;
        where.TextWrapping = TextWrapping.NoWrap;
        var head = Spaced(Spread(Ui.Stack(Ui.Text("Sync settings between your devices", 15), status, where), info), 20, 14, 18, 14);
        col.Children.Add(Ui.List(
            head,
            Ui.SettingRow("Include API keys", "Off by default. The sync file isn't encrypted, so only turn this on if the folder is private to you.",
                Ui.Switch(S.SyncKeys, v => { S.SyncKeys = v; S.Save(); }))));
        var now = Ui.Button("Sync now", async () => { await Sync.Run(); Render(); }, filled: true);
        var stop = Ui.Button("Stop syncing", () =>
        {
            if (!Ui.Dialog(this, "Stop syncing on this PC? The sync file and your settings here stay as they are.", "Stop syncing")) return;
            S.SyncFile = "";
            S.SyncKeys = false;
            Sync.Forget();
            S.Save();
            Render();
        });
        now.Margin = new Thickness(0, 0, 8, 0);
        col.Children.Add(Spaced(Ui.Row(now, stop), 0, 12, 0, 0));
    }

    private bool busy; // a backup or restore is running

    private async void DoBackup(bool withAudio, bool withKeys)
    {
        if (busy) return;
        var dlg = new Microsoft.Win32.SaveFileDialog
        {
            FileName = $"tokalot-desktop-backup-{DateTime.Now:yyyy-MM-dd}.zip", Filter = "Zip file|*.zip", DefaultExt = ".zip",
        };
        if (dlg.ShowDialog(this) != true) return;
        busy = true;
        Toast("Backing up…");
        try
        {
            // Zipping recordings can take a while; keep the window (and Ctrl+Win) responsive.
            var s = await Task.Run(() => Backup.Write(dlg.FileName, withAudio, withKeys));
            Toast($"Backed up {s.Entries} dictations" + (s.Recordings > 0 ? $" and {s.Recordings} recordings" : ""));
        }
        catch (Exception e)
        {
            Ui.Dialog(this, "Backup failed: " + e.Message, cancel: null);
        }
        finally { busy = false; }
    }

    private async void DoRestore()
    {
        if (busy) return;
        if (!Ui.Dialog(this,
                "This replaces your current settings, dictionary, snippets and history with the backup's. API keys on this PC are kept unless the backup includes keys.",
                "Restore", title: "Restore from backup?")) return;
        var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "Tokalot backup|*.zip" };
        if (dlg.ShowDialog(this) != true) return;
        busy = true;
        Toast("Restoring…");
        try
        {
            Player.Stop(false);
            var s = await Task.Run(() => Backup.Restore(dlg.FileName));
            // The theme may have changed, which rebuilds the window: finish up on the app, not on this (old) window.
            App.Current.AfterRestore($"Restored {s.Entries} dictations");
        }
        catch (Exception e)
        {
            Ui.Dialog(this, "Restore failed: " + e.Message, cancel: null);
        }
        finally { busy = false; }
    }

    // ---------- dark title bar ----------

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private void DarkTitleBar()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            int on = C.Dark ? 1 : 0;
            DwmSetWindowAttribute(hwnd, 20, ref on, sizeof(int));
            // Windows 11: rounded corners, and a window border that matches the app instead of the system accent.
            int round = 2;
            DwmSetWindowAttribute(hwnd, 33, ref round, sizeof(int));
            var c = ((SolidColorBrush)C.Line).Color;
            int border = c.R | c.G << 8 | c.B << 16;
            DwmSetWindowAttribute(hwnd, 34, ref border, sizeof(int));
        }
        catch { }
    }
}
