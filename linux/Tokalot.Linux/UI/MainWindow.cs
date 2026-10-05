using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/**
 * The settings and history window. Closing it leaves Tokalot running in the tray (the menu bar on a Mac).
 * Shared by the Linux and Mac apps; each has its own part of this class (MainWindow.Linux.cs,
 * MainWindow.Mac.cs) with the setup rows and warnings that name its own permissions.
 */
public sealed partial class MainWindow : Window
{
    public enum Page { Home, Dictionary, Style, Snippets, Settings }

    public Page CurrentPage { get; private set; } = Page.Home;

    private readonly StackPanel nav = new() { Margin = new Thickness(14, 18, 14, 0) };
    private readonly ScrollViewer scroller = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private readonly Border toast;
    private readonly TextBlock toastText;
    private readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromMilliseconds(1700) };
    private readonly TextBox search;
    private readonly Border searchBox;
    private readonly List<Control> grips = new();
    private StackPanel? historyBox;
    private TextBlock? bannerText;
    private TextBlock? modelStatus;
    private int historyLimit = 100;
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
        var area = new Size(double.PositiveInfinity, double.PositiveInfinity);
        try { if (Screens.Primary is { } sc) area = new Size(sc.WorkingArea.Width / sc.Scaling, sc.WorkingArea.Height / sc.Scaling); } catch { }
        Width = Math.Min(1040, area.Width); Height = Math.Min(760, area.Height);
        MinWidth = Math.Min(760, Width); MinHeight = Math.Min(520, Height);
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = C.Bg;
        FontFamily = C.Sans;
        try { Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://Tokalot/Assets/icon.png"))); } catch { }
        // No system title bar: the app draws to the top edge. Dragging, resizing and double-click to maximize are done below.
        // A Mac keeps its own window buttons (top left) and edges, with the page drawn up under them.
        if (OperatingSystem.IsMacOS())
        {
            ExtendClientAreaToDecorationsHint = true;
            ExtendClientAreaChromeHints = Avalonia.Platform.ExtendClientAreaChromeHints.PreferSystemChrome;
            ExtendClientAreaTitleBarHeightHint = CaptionHeight * UiScale;
        }
        else SystemDecorations = SystemDecorations.None;

        (searchBox, search) = Ui.Field("", "Search your dictations");
        search.TextChanged += (_, _) => FillHistory();

        toastText = new TextBlock { Foreground = Brushes.White, FontSize = 13.5, FontFamily = C.Sans };
        toast = Ui.Stadium(new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x1C, 0x1C, 0x1E)),
            Padding = new Thickness(18, 9, 18, 9), Child = toastText, Opacity = 0, IsHitTestVisible = false,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 28),
        });
        toastTimer.Tick += (_, _) =>
        {
            toastTimer.Stop();
            toast.Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(250) } };
            toast.Opacity = 0;
        };

        // Sidebar | content
        var root = new Grid { Background = C.Bg };
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(232) });
        root.ColumnDefinitions.Add(new ColumnDefinition());
        root.Children.Add(Sidebar());
        var contentGrid = new Grid { Margin = new Thickness(0, CaptionHeight, 0, 0) };
        contentGrid.Children.Add(scroller);
        contentGrid.Children.Add(toast);
        Grid.SetColumn(contentGrid, 1);
        root.Children.Add(contentGrid);
        var strip = DragStrip();
        Grid.SetColumnSpan(strip, 2);
        root.Children.Add(strip);
        if (!OperatingSystem.IsMacOS())
        {
            var caption = CaptionButtons();
            Grid.SetColumn(caption, 1);
            root.Children.Add(caption);
        }

        // The scaled page, a thin outline in place of the system window border, and the resize edges on top.
        var frame = new Grid();
        frame.Children.Add(new LayoutTransformControl { LayoutTransform = new ScaleTransform(UiScale, UiScale), Child = root });
        frame.Children.Add(new Border { BorderBrush = C.Line, BorderThickness = new Thickness(1), IsHitTestVisible = false });
        if (!OperatingSystem.IsMacOS())
            foreach (var g in ResizeGrips()) { grips.Add(g); frame.Children.Add(g); }
        Content = frame;

        onHistory = () => Dispatcher.UIThread.Post(() => { if (CurrentPage == Page.Home) FillHistory(); });
        onPlayer = () => Dispatcher.UIThread.Post(() => { if (CurrentPage == Page.Home) FillHistory(); });
        History.Changed += onHistory;
        Player.Changed += onPlayer;
        Closed += (_, _) => { History.Changed -= onHistory; Player.Changed -= onPlayer; Player.Stop(false); };
        Activated += (_, _) => Sync.Queue();
        // Coming back from System Settings on a Mac: redraw if a permission changed meanwhile.
        Activated += (_, _) => PlatformActivated();
        onSync = changed => Dispatcher.UIThread.Post(() => { if (changed || CurrentPage == Page.Settings) Render(); });
        Sync.Finished += onSync;
        Closed += (_, _) => Sync.Finished -= onSync;
        // Seen before the focused control gets the key (WPF's PreviewKeyDown).
        AddHandler(KeyDownEvent, (_, e) =>
        {
            if (e.Key == Key.F && (e.KeyModifiers == KeyModifiers.Control || (OperatingSystem.IsMacOS() && e.KeyModifiers == KeyModifiers.Meta)))
            {
                e.Handled = true;
                if (CurrentPage != Page.Home) Go(Page.Home);
                search.Focus();
            }
            else if (e.Key == Key.Escape && (editing != null || editingNew)) { e.Handled = true; editing = null; editingNew = false; Render(); }
            else if (e.Key == Key.Escape && CurrentPage == Page.Home && !string.IsNullOrEmpty(search.Text)) { e.Handled = true; search.Text = ""; }
        }, RoutingStrategies.Tunnel);
        SizeChanged += (_, _) => { if (IsLoaded && IsWide != wideLayout) Render(); };
        Render();
    }

    /** The platform's part may react to the window coming to the front (only the Mac's does). */
    partial void PlatformActivated();

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        // A maximized window has no edges to drag.
        if (change.Property == WindowStateProperty)
            foreach (var g in grips) g.IsVisible = WindowState == WindowState.Normal;
    }

    // ---------- frame ----------

    /** The top strip moves the window when dragged and maximizes it on a double click, like a title bar. */
    private Control DragStrip()
    {
        var strip = new Border { Height = CaptionHeight, VerticalAlignment = VerticalAlignment.Top, Background = Brushes.Transparent };
        strip.PointerPressed += (_, e) =>
        {
            if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
            if (e.ClickCount == 2) WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
            else BeginMoveDrag(e);
        };
        return strip;
    }

    /** Invisible edges and corners that resize the window (the system frame that would do it is switched off). */
    private IEnumerable<Control> ResizeGrips()
    {
        const double edge = 5, corner = 12;
        Control Grip(WindowEdge e, StandardCursorType cursor, HorizontalAlignment h, VerticalAlignment v, double w, double hgt)
        {
            var b = new Border { Background = Brushes.Transparent, HorizontalAlignment = h, VerticalAlignment = v, Width = w, Height = hgt, Cursor = new Cursor(cursor) };
            b.PointerPressed += (_, a) => { if (a.GetCurrentPoint(this).Properties.IsLeftButtonPressed) BeginResizeDrag(e, a); };
            return b;
        }
        yield return Grip(WindowEdge.North, StandardCursorType.TopSide, HorizontalAlignment.Stretch, VerticalAlignment.Top, double.NaN, 4);
        yield return Grip(WindowEdge.South, StandardCursorType.BottomSide, HorizontalAlignment.Stretch, VerticalAlignment.Bottom, double.NaN, edge);
        yield return Grip(WindowEdge.West, StandardCursorType.LeftSide, HorizontalAlignment.Left, VerticalAlignment.Stretch, edge, double.NaN);
        yield return Grip(WindowEdge.East, StandardCursorType.RightSide, HorizontalAlignment.Right, VerticalAlignment.Stretch, edge, double.NaN);
        yield return Grip(WindowEdge.NorthWest, StandardCursorType.TopLeftCorner, HorizontalAlignment.Left, VerticalAlignment.Top, corner, corner);
        yield return Grip(WindowEdge.NorthEast, StandardCursorType.TopRightCorner, HorizontalAlignment.Right, VerticalAlignment.Top, 6, 6);
        yield return Grip(WindowEdge.SouthWest, StandardCursorType.BottomLeftCorner, HorizontalAlignment.Left, VerticalAlignment.Bottom, corner, corner);
        yield return Grip(WindowEdge.SouthEast, StandardCursorType.BottomRightCorner, HorizontalAlignment.Right, VerticalAlignment.Bottom, corner, corner);
    }

    private Control CaptionButtons()
    {
        Border Button(string path, string tip, IBrush hover, IBrush hoverGlyph, Action onClick)
        {
            var glyph = new Avalonia.Controls.Shapes.Path
            {
                Data = Geometry.Parse(path), Stroke = C.Text, StrokeThickness = 1, Width = 10, Height = 10,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            };
            var b = new Border { Width = 46, Height = CaptionHeight, Background = Brushes.Transparent, Child = glyph };
            ToolTip.SetTip(b, tip);
            b.OnEnter(() => { b.Background = hover; glyph.Stroke = hoverGlyph; });
            b.OnLeave(() => { b.Background = Brushes.Transparent; glyph.Stroke = C.Text; });
            b.OnClick(onClick, handled: true);
            return Ui.Keys(b, onClick, tip);
        }
        var row = Ui.Row(
            Button("M0,5.5 H10", "Minimize", C.Hover, C.Text, () => WindowState = WindowState.Minimized),
            Button("M0,0 L10,10 M10,0 L0,10", "Close", C.Hex("#C42B1C"), Brushes.White, Close));
        row.HorizontalAlignment = HorizontalAlignment.Right;
        row.VerticalAlignment = VerticalAlignment.Top;
        return row;
    }

    private Control Sidebar()
    {
        var logo = new Bars { Width = 34, Height = 34, BarBrush = C.Text, AccentBrush = !C.Dark && S.Accent == 0xFFFFFFFF ? C.Text : C.Argb(S.Accent) };
        var title = new TextBlock { Text = "Tokalot", FontFamily = C.Sans, FontWeight = FontWeight.Bold, FontSize = 23, Foreground = C.Text, Margin = new Thickness(8, 0, 0, 1), VerticalAlignment = VerticalAlignment.Center };
        var head = Ui.Row(logo, title);
        head.Margin = new Thickness(22, 26, 0, 4);

        var dismiss = new Border
        {
            Width = 22, Height = 22, CornerRadius = new CornerRadius(11), Background = Brushes.Transparent, Cursor = Cursors.Hand,
            Child = Icons.Get("close", 14, C.Sub),
        };
        ToolTip.SetTip(dismiss, "Hide this tip");
        var keys = Ui.Row(KeyCap(Host.ShortcutKeys[0]), new TextBlock { Text = "+", FontSize = 14, Foreground = C.Sub, Margin = new Thickness(7, 0, 7, 0), VerticalAlignment = VerticalAlignment.Center }, KeyCap(Host.ShortcutKeys[1]));
        keys.Margin = new Thickness(0, 8, 0, 8);
        var hint = Ui.Card(Ui.Stack(
            Spread(Ui.Text("Hold to talk", 13, C.Sub), dismiss),
            keys,
            Ui.Text("Tap once for hands-free. Esc cancels.", 12.5, C.Sub)), 16);
        hint.Margin = new Thickness(14, 0, 14, 18);
        hint.VerticalAlignment = VerticalAlignment.Bottom;
        if (S.HideShortcutTip) hint.IsVisible = false;
        dismiss.OnEnter(() => dismiss.Background = C.Hover);
        dismiss.OnLeave(() => dismiss.Background = Brushes.Transparent);
        void HideTip() { S.HideShortcutTip = true; S.Save(); hint.IsVisible = false; }
        dismiss.OnClick(HideTip);
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
            b.OnEnter(() => { if (!active) b.Background = C.Hover; });
            b.OnLeave(() => { if (!active) b.Background = Brushes.Transparent; });
            b.OnClick(() => Go(p));
            nav.Children.Add(Ui.Keys(b, () => Go(p), label));
        }
        Item(Page.Home, "home", "Home");
        Item(Page.Dictionary, "book", "Dictionary");
        Item(Page.Style, "style", "Style");
        Item(Page.Snippets, "snippet", "Snippets");
        nav.Children.Add(new Border { Height = 14 });
        Item(Page.Settings, "settings", "Settings");
    }

    /** Full height of the current page (screenshot mode). */
    internal double ExtentHeight => scroller.Extent.Height;

    internal void SetStyleTab(string id) => styleTab = id;

    internal void OpenSnippetEditor(Snippet? s) { editing = s; editingNew = s == null; Render(); }

    public void Go(Page p)
    {
        if (p != CurrentPage) scroller.Offset = default;
        CurrentPage = p;
        Render();
    }

    /** Rebuilds the current page, keeping the scroll position. */
    public void Render()
    {
        var offset = scroller.Offset.Y;
        RenderNav();
        var col = new StackPanel { MaxWidth = 760, Margin = new Thickness(36, 10, 36, 48) };
        switch (CurrentPage)
        {
            case Page.Home: BuildHome(col); break;
            case Page.Dictionary: BuildDictionary(col); break;
            case Page.Style: BuildStyle(col); break;
            case Page.Snippets: BuildSnippets(col); break;
            case Page.Settings: BuildSettings(col); break;
        }
        wideLayout = IsWide;
        scroller.Content = wideLayout ? TwoColumns(col) : StripMarkers(col);
        scroller.UpdateLayout();
        scroller.Offset = new Vector(0, offset);
    }

    // ---------- two columns on wide windows ----------

    /** Marks the end of a page's full-width header (title and intro). */
    private sealed class HeaderEnd : Control { }
    /** Marks where the second column starts; pages without one are balanced by section. */
    private sealed class ColBreak : Control { }

    private const double ColumnGap = 32, WideAt = 1180;
    private bool wideLayout;
    /** Screenshot mode sets the width here. */
    internal double? ForcedWidth { get; set; }
    private double WindowWidth => Bounds.Width > 0 ? Bounds.Width : Width;
    private double ContentWidth => Math.Max(0, (ForcedWidth ?? WindowWidth / UiScale) - 232 - 72 - 20);
    private bool IsWide => ContentWidth >= WideAt;

    private static StackPanel StripMarkers(StackPanel col)
    {
        foreach (var m in col.Children.Where(e => e is HeaderEnd or ColBreak).ToList()) col.Children.Remove(m);
        return col;
    }

    /** Rebuilds a one-column page as a header plus two columns. */
    private Control TwoColumns(StackPanel col)
    {
        var items = col.Children.ToList();
        col.Children.Clear();
        var root = new StackPanel { MaxWidth = 1500, Margin = col.Margin };

        int headerEnd = items.FindIndex(e => e is HeaderEnd);
        foreach (var e in items.Take(Math.Max(0, headerEnd))) root.Children.Add(e);
        var body = items.Skip(headerEnd + 1).ToList();

        var left = new StackPanel();
        var right = new StackPanel();
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ColumnGap) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.Children.Add(left);
        Grid.SetColumn(right, 2);
        grid.Children.Add(right);
        root.Children.Add(grid);

        int brk = body.FindIndex(e => e is ColBreak);
        if (brk >= 0)
        {
            foreach (var e in body.Take(brk)) left.Children.Add(e);
            foreach (var e in body.Skip(brk + 1)) right.Children.Add(e);
        }
        else
        {
            // Settings: keep sections whole and in order, filling the left column to about half.
            var groups = new List<List<Control>>();
            foreach (var e in body)
            {
                if (groups.Count == 0 || Equals(e.Tag, "section")) groups.Add(new List<Control>());
                groups[^1].Add(e);
            }
            // Controls only pick up their templates (and so their real height) once they are in the window,
            // so everything goes into the left column first, is measured there, and the rest is moved over.
            foreach (var e in body) left.Children.Add(e);
            scroller.Content = root;
            double colW = (Math.Min(1500, ContentWidth) - ColumnGap) / 2;
            var heights = groups.Select(g => g.Sum(e => { e.Measure(new Size(colW, double.PositiveInfinity)); return e.DesiredSize.Height; })).ToList();
            double total = heights.Sum(), acc = 0;
            for (int i = 0; i < groups.Count; i++)
            {
                if (acc + heights[i] / 2 <= total / 2) { acc += heights[i]; continue; }
                foreach (var e in groups[i]) { left.Children.Remove(e); right.Children.Add(e); }
            }
        }
        // The first item of each column shouldn't carry a big top gap.
        foreach (var c in new[] { left, right })
            if (c.Children.Count > 0 && c.Children[0] is { } f && f.Margin.Top > 8)
                f.Margin = new Thickness(f.Margin.Left, 4, f.Margin.Right, f.Margin.Bottom);
        return root;
    }

    private void Toast(string msg)
    {
        toastText.Text = msg;
        toastTimer.Stop();
        toast.Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(120) } };
        toast.Opacity = 1;
        toastTimer.Start();
    }

    private static void Intro(StackPanel col, string title, string sub)
    {
        col.Children.Add(Ui.Heading(title));
        col.Children.Add(Spaced(Ui.Text(sub, 15, C.Sub), 0, 4, 0, 20));
        col.Children.Add(new HeaderEnd());
    }

    private static T Spaced<T>(T e, double l, double t, double r, double b) where T : Control
    {
        e.Margin = new Thickness(l, t, r, b);
        return e;
    }

    private static void Open(string url)
    {
        try
        {
            if (OperatingSystem.IsLinux() && Sh.Which("xdg-open") is { } x) Sh.Spawn(x, url)?.Dispose();
            else Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch { }
    }

    private static TextBlock Link(string text, Action onClick)
    {
        var t = Ui.Text(text, 14, C.Link);
        t.Cursor = Cursors.Hand;
        t.Background = Brushes.Transparent; // so the gaps between letters take the click too
        t.HorizontalAlignment = HorizontalAlignment.Left;
        t.OnClick(onClick);
        return Ui.Keys(t, onClick, text);
    }

    /** A row that stretches its first child and right-aligns the rest. */
    private static Grid Spread(Control left, params Control[] right)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition());
        left.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(left);
        for (int i = 0; i < right.Length; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(right[i], i + 1);
            right[i].VerticalAlignment = VerticalAlignment.Center;
            right[i].Margin = new Thickness(8, 0, 0, 0);
            g.Children.Add(right[i]);
        }
        return g;
    }

    /** A grey box holding a terminal command, with a button that copies it. */
    private Control Command(string command)
    {
        var text = new SelectableTextBlock
        {
            Text = command, FontFamily = new FontFamily("DejaVu Sans Mono, Liberation Mono, Noto Sans Mono, Menlo, monospace"), FontSize = 12.5,
            Foreground = C.Text, TextWrapping = TextWrapping.Wrap,
        };
        var copy = Ui.Button("Copy", () => { _ = TextInjector.Copy(command); Toast("Copied"); }, icon: "copy");
        return new Border
        {
            Background = C.Field, CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 10, 10, 10), Margin = new Thickness(0, 10, 0, 0),
            Child = Spread(text, copy),
        };
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

        // The permissions this platform needs that aren't in place yet, each naming what to do.
        PlatformWarnings(col);

        if (!SetupComplete)
        {
            var c = Ui.Stack(
                Ui.Heading("Finish setup", 30),
                Spaced(Ui.Text($"Add a Groq API key (free) or download the offline model, and {Host.Shortcut} will start working in any app.", 15, C.Sub), 0, 6, 0, 14),
                Ui.Button("Open settings", () => Go(Page.Settings), filled: true));
            col.Children.Add(Spaced(Ui.Card(c, 22), 0, 0, 0, 16));
        }

        // This month at a glance.
        var m = Usage.This();
        Control Stat(string value, string label) => Ui.Stack(Ui.Heading(value, 32), Ui.Text(label, 13, C.Sub));
        var stats = new Grid { Margin = new Thickness(0, 10, 0, 0) };
        for (int i = 0; i < 3; i++) stats.ColumnDefinitions.Add(new ColumnDefinition());
        // Speaking pace: words Whisper heard per minute of recording this month. (Cost lives in Settings > Usage.)
        var now = DateTime.Now;
        var monthStart = new DateTimeOffset(new DateTime(now.Year, now.Month, 1)).ToUnixTimeMilliseconds();
        var spoken = History.All().Where(e => e.Time >= monthStart && e.DurationMs > 0 && e.Raw.Length > 0).ToList();
        var minutes = spoken.Sum(e => e.DurationMs) / 60000.0;
        var pace = minutes > 0 ? Math.Round(spoken.Sum(e => TextTools.WordCount(e.Raw)) / minutes).ToString(CultureInfo.InvariantCulture) : "–";
        var cells = new[] { Stat(Ui.Compact(m.Words), "words"), Stat(m.Dictations.ToString(CultureInfo.InvariantCulture), "dictations"), Stat(pace, "words a minute") };
        for (int i = 0; i < 3; i++) { Grid.SetColumn(cells[i], i); stats.Children.Add(cells[i]); }
        var statCard = Ui.Stack(Ui.Text(DateTime.Now.ToString("MMMM yyyy", CultureInfo.InvariantCulture).ToUpperInvariant(), 12, C.Sub, bold: true), stats);
        if (m.Fillers + m.Corrections > 0)
            statCard.Children.Add(Spaced(Ui.Text($"Cleaned up {Plural(m.Fillers, "filler word")} and {Plural(m.Corrections, "self-correction")}", 13, C.Sub), 0, 10, 0, 0));
        var sc = Ui.Card(statCard, 22);
        sc.Cursor = Cursors.Hand;
        sc.OnClick(() => Go(Page.Settings));
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
        var q = (search.Text ?? "").Trim();
        var all = History.All();
        var list = q.Length == 0 ? all : all.Where(e =>
            e.Text.Contains(q, StringComparison.OrdinalIgnoreCase) || e.Raw.Contains(q, StringComparison.OrdinalIgnoreCase) ||
            e.AppLabel.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
        if (list.Count == 0)
        {
            box.Children.Add(Spaced(Ui.Heading(q.Length == 0 ? "Today" : "No matches", 30), 0, 24, 0, 12));
            box.Children.Add(Ui.Card(Ui.Text(q.Length == 0
                ? $"Nothing yet. Click into any text box, hold {Host.Shortcut} and talk."
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

    private Control EntryView(Entry e)
    {
        var box = new StackPanel { Margin = new Thickness(22, 18, 22, 16) };
        var open = expanded.Contains(e.Id);
        if (open)
        {
            // Selectable when expanded.
            box.Children.Add(new SelectableTextBlock
            {
                Text = e.Text, Foreground = C.Text, FontSize = 16.5, FontFamily = C.Sans, TextWrapping = TextWrapping.Wrap, LineHeight = 16.5 * 1.4,
            });
        }
        else
        {
            var t = Ui.Text(e.Text, 16.5);
            t.MaxLines = 6;
            t.TextTrimming = TextTrimming.CharacterEllipsis;
            t.Cursor = Cursors.Hand;
            t.Background = Brushes.Transparent;
            t.OnClick(() => { expanded.Add(e.Id); FillHistory(); });
            box.Children.Add(t);
        }

        if (showOriginal.Contains(e.Id))
        {
            var orig = new Border
            {
                Background = C.Field, CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 10, 14, 12), Margin = new Thickness(0, 12, 0, 0),
                Child = Ui.Stack(Ui.Text("ORIGINAL", 11, C.Sub, bold: true), new SelectableTextBlock
                {
                    Text = e.Raw, Foreground = C.Sub, FontSize = 14.5, FontFamily = C.Sans, TextWrapping = TextWrapping.Wrap, LineHeight = 14.5 * 1.4,
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

        var actions = new List<Control> { Ui.Button("Copy", () => { _ = TextInjector.Copy(e.Text); Toast("Copied"); }, icon: "copy") };
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
        foreach (var a in row.Children) a.Margin = new Thickness(0, 0, 8, 0);

        var menu = Ui.Menu();
        // The button marks its click handled, so the menu has to open from the button's own action.
        Border more = null!;
        more = Ui.Button("", () => menu.Open(more), icon: "more");
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
        var (box, input) = Ui.Field("", "Add words, separated by commas, e.g. Kubernetes, Nguyen, PostgreSQL", multiLine: true);
        col.Children.Add(box);
        var add = Ui.Button("Add", () =>
        {
            var typed = input.Text ?? "";
            var existing = new HashSet<string>(S.Words.Select(w => w.ToLowerInvariant()));
            var fresh = typed.Split(new[] { ',', '\n', '\r' }).Select(w => w.Trim())
                .Where(w => w.Length > 0 && existing.Add(w.ToLowerInvariant())).ToList();
            if (fresh.Count > 0)
            {
                S.Words.AddRange(fresh);
                S.Save();
                Render();
                Toast($"Added {fresh.Count} {(fresh.Count == 1 ? "word" : "words")}");
            }
            else if (typed.Trim().Length > 0) Toast("Already in your dictionary");
        }, filled: true);
        add.HorizontalAlignment = HorizontalAlignment.Right;
        col.Children.Add(Spaced(add, 0, 10, 0, 16));

        col.Children.Add(new ColBreak());
        if (S.Words.Count == 0)
        {
            col.Children.Add(Ui.Card(Ui.Text("No words yet.", 16, C.Sub), 22));
            return;
        }
        var rows = S.Words.OrderBy(w => w, StringComparer.OrdinalIgnoreCase).Select(w =>
        {
            var remove = Ui.Button("", () => { S.Words.Remove(w); S.Save(); Render(); }, icon: "close");
            remove.BorderThickness = new Thickness(0);
            remove.Background = Brushes.Transparent;
            return (Control)Spaced(Spread(Ui.Text(w, 16), remove), 20, 8, 12, 8);
        }).ToArray();
        col.Children.Add(Ui.List(rows));
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
            body.MaxLines = 3;
            body.TextTrimming = TextTrimming.CharacterEllipsis;
            var item = new Border
            {
                Padding = new Thickness(22, 15, 22, 15), Background = Brushes.Transparent, Cursor = Cursors.Hand,
                Child = Ui.Stack(Ui.Text($"“{sn.Trigger}”", 16, bold: true), body),
            };
            item.OnEnter(() => item.Background = C.Hover);
            item.OnLeave(() => item.Background = Brushes.Transparent);
            void Edit() { editing = sn; editingNew = false; Render(); scroller.Offset = default; }
            item.OnClick(Edit);
            return (Control)Ui.Keys(item, Edit, sn.Trigger);
        }).ToArray();
        col.Children.Add(Ui.List(rows));
    }

    private Control SnippetEditor(Snippet? existing)
    {
        var (tBox, trig) = Ui.Field(existing?.Trigger ?? "", "Trigger phrase you'll say");
        var (bBox, body) = Ui.Field(existing?.Text ?? "", "Text to insert", multiLine: true);
        void Close() { editing = null; editingNew = false; Render(); }
        var buttons = new List<Control>
        {
            Ui.Button("Save", () =>
            {
                var t = (trig.Text ?? "").Trim();
                var text = body.Text ?? "";
                if (t.Length == 0 || text.Trim().Length == 0) { Toast("Add a trigger and some text"); return; }
                S.Snippets.RemoveAll(x => x == existing || string.Equals(x.Trigger, t, StringComparison.OrdinalIgnoreCase));
                S.Snippets.Add(new Snippet(t, text));
                S.Save();
                Close();
            }, filled: true),
            Ui.Button("Cancel", Close),
        };
        if (existing != null) buttons.Add(Ui.Button("Delete", () => { S.Snippets.Remove(existing); S.Save(); Close(); }));
        var row = Ui.Row(buttons.ToArray());
        foreach (var b in row.Children) b.Margin = new Thickness(0, 0, 8, 0);
        var card = Ui.Card(Ui.Stack(
            Ui.Text(existing == null ? "New snippet" : "Edit snippet", 17, bold: true),
            Spaced(tBox, 0, 12, 0, 0), Spaced(bBox, 0, 10, 0, 14), row), 20);
        Dispatcher.UIThread.Post(() => trig.Focus());
        return card;
    }

    // ---------- Style ----------

    private void BuildStyle(StackPanel col)
    {
        Intro(col, "Style", "How the cleanup model writes what you say, per kind of app. Tokalot checks which app you're in when you press " + Host.Shortcut + "."
            + (Sh.IsWayland ? " On Wayland the desktop doesn't tell apps which window is in front, so every dictation uses “Everything else”." : ""));
        if (!S.CleanupReady)
        {
            var warn = Ui.Card(Ui.Text("AI cleanup isn't active, so styles won't apply yet. Pick a cleanup model and add its key in Settings.", 15, C.Warn), 18);
            warn.Cursor = Cursors.Hand;
            warn.OnClick(() => Go(Page.Settings));
            col.Children.Add(Spaced(warn, 0, 0, 0, 16));
        }

        col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Polish my wording",
            "Off: your own words are kept, with fillers removed and punctuation and formatting fixed. On: the AI may also tighten and clarify what you said, " +
            $"and for a few seconds after each dictation {Host.Shortcut}+Z puts your own words back.",
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
            t.OnClick(() => { styleTab = id; Render(); });
            tabs.Children.Add(Ui.Keys(t, () => { styleTab = id; Render(); }, c.Label));
        }
        col.Children.Add(Spaced(tabs, 0, 0, 0, 4));
        var cat = Catalog.CategoryById(styleTab);
        col.Children.Add(Spaced(Ui.Text(cat.Blurb, 14, C.Sub), 4, 0, 0, 12));

        var current = S.StyleFor(cat);
        col.Children.Add(Ui.List(Catalog.Styles.Select(st => (Control)Ui.Choice(st.Label, st.Example, current.Id == st.Id, () =>
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
                row.OnEnter(() => row.Background = C.Hover);
                row.OnLeave(() => row.Background = Brushes.Transparent);
                var menu = Ui.Menu();
                foreach (var c in Catalog.Categories)
                {
                    var mi = new MenuItem { Header = c.Label, ToggleType = MenuItemToggleType.CheckBox, IsChecked = c.Id == appCat.Id };
                    var id = c.Id;
                    mi.Click += (_, _) => { S.AppOverrides[e.AppKey] = id; S.Save(); Render(); };
                    menu.Items.Add(mi);
                }
                row.ContextMenu = menu;
                row.OnClick(() => menu.Open(row));
                return (Control)Ui.Keys(row, () => menu.Open(row), e.AppLabel);
            }).ToArray();
            col.Children.Add(Ui.List(rows));
        }

        if (!col.Children.OfType<ColBreak>().Any()) col.Children.Add(new ColBreak());
        col.Children.Add(Spaced(Ui.Heading("Your instructions", 28), 0, 28, 0, 4));
        col.Children.Add(Spaced(Ui.Text("Applies everywhere. E.g. \"Use US spelling\", \"Write numbers as digits\", \"Never use exclamation points\".", 14, C.Sub), 0, 0, 0, 12));
        var (box, input) = Ui.Field(S.CustomInstructions, "Optional", multiLine: true);
        input.TextChanged += (_, _) => { S.CustomInstructions = input.Text ?? ""; S.Save(); };
        col.Children.Add(box);
    }

    // ---------- Settings ----------

    private static void Section(StackPanel col, string title)
    {
        var l = Ui.Label(title);
        l.Tag = "section";
        col.Children.Add(l);
    }

    /** One row of Settings › Setup: green when done, otherwise orange with what to do (and a command to copy, if any). */
    private Control Status(string title, bool done, string doneText, string notDone, Control? action = null, string? command = null)
    {
        var st = Ui.Text(done ? doneText : notDone, 13.5, done ? C.Good : C.Warn);
        if (title.StartsWith("Offline")) modelStatus = st;
        var texts = Ui.Stack(Ui.Text(title, 15.5), st);
        // What to type in a terminal to fix it, shown only while it needs fixing.
        if (!done && command != null) texts.Children.Add(Command(command));
        return Spaced(Spread(texts, action ?? new Border()), 20, 13, 16, 13);
    }

    private void BuildSettings(StackPanel col)
    {
        col.Children.Add(Ui.Heading("Settings"));
        col.Children.Add(new HeaderEnd());

        // --- Setup
        Section(col, "Setup");
        var modelText = ModelManager.IsReady ? "Downloaded" : downloadPct != null ? $"Downloading {downloadPct}%" : downloadError != null ? "Failed: " + downloadError : "Not downloaded";
        var setup = new List<Control>();
        // The shortcut, pasting and microphone rows, which name this platform's own permissions.
        PlatformSetupRows(setup);
        setup.Add(Status("Speech-to-text key", S.CloudSttReady, "Added", "Add a Groq key below (free)"));
        setup.Add(Status("Offline backup model (60 MB)", ModelManager.IsReady || downloadPct != null, modelText, modelText,
            ModelManager.IsReady || downloadPct != null ? null : Ui.Button("Download", StartDownload)));
        setup.Add(Ui.SettingRow("Start at login", Startup.Supported ? $"Runs quietly in the {Host.TrayName} so {Host.Shortcut} always works." : "Not available in a test or development copy.",
            Ui.Switch(S.LaunchAtStartup, v => { S.LaunchAtStartup = v; S.Save(); Startup.Apply(v); })));
        col.Children.Add(Ui.List(setup.ToArray()));
        col.Children.Add(Spaced(Ui.Text(MicHelp, 13, C.Sub), 4, 8, 0, 0));

        // --- Appearance
        Section(col, "Appearance");
        col.Children.Add(Ui.List(new[] { ("system", "Match system"), ("light", "Light"), ("dark", "Dark") }
            .Select(t => (Control)Ui.Choice(t.Item2, "", S.Theme == t.Item1, () =>
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
                BorderBrush = on ? C.Text : C.Pill, BorderThickness = new Thickness(on ? 3 : 1), Cursor = Cursors.Hand,
            };
            ToolTip.SetTip(dot, name);
            dot.OnClick(() => { S.Accent = argb; S.Save(); Render(); });
            swatches.Children.Add(Ui.Keys(dot, () => { S.Accent = argb; S.Save(); Render(); }, name + " accent"));
        }
        col.Children.Add(swatches);

        // --- Recording indicator
        Section(col, "Recording indicator");
        col.Children.Add(Ui.List(IndicatorView.Styles.Select(id => IndicatorRow(id)).ToArray()));
        var dockRow = Ui.Row(Ui.Text("Edge", 14, C.Sub));
        dockRow.Children[0].Margin = new Thickness(4, 0, 12, 0);
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
                Ui.Text($"Hold {Host.Shortcut} while you talk and let go to paste. Or tap {Host.Shortcut} once for hands-free, then tap again to finish. Esc cancels.", 13.5, C.Sub)), 20, 13, 18, 13),
            Ui.SettingRow("Auto-stop after 30 s of silence", "Hands-free mode only. Long pauses to think are fine.",
                Ui.Switch(S.AutoStop, v => { S.AutoStop = v; S.Save(); })),
            Ui.SettingRow("Transcribe while I talk", "In a long dictation, what you've said so far is sent to the speech service each time you pause, so the wait at the end stays short. Nothing appears until you finish. Needs a cloud speech service and AI cleanup.",
                Ui.Switch(S.LiveStt, v => { S.LiveStt = v; S.Save(); })),
            Ui.SettingRow("Hands-free reminder", "A short note above the indicator when hands-free starts. Its X turns this off.",
                Ui.Switch(!S.HideHandsFreeHint, v => { S.HideHandsFreeHint = !v; S.Save(); })),
            Ui.SettingRow("Sounds", "A soft tone when recording starts, stops, finishes or fails.",
                Ui.Switch(S.Sounds, v => { S.Sounds = v; S.Save(); if (v) Sounds.Play(Sounds.Kind.Done); })),
            Ui.SettingRow("Detect language automatically", "Off keeps it English-only, which is most accurate for English. The offline backup is English-only either way.",
                Ui.Switch(S.AutoLanguage, v => { S.AutoLanguage = v; S.Save(); }))));

        // --- Speech to text
        Section(col, "Speech to text");
        col.Children.Add(Ui.List(Catalog.Stt.Select(o =>
        {
            var needsKey = o.Service != null && S.Key(o.Service).Length == 0;
            // The shared catalog says "this PC"; same thing, said for any computer.
            var note = o.Note.Replace("this PC", "this computer");
            return (Control)Ui.Choice(o.Label, note + (needsKey ? " Needs a key." : ""), S.Stt == o.Id, () => { S.Stt = o.Id; S.Save(); Render(); });
        }).ToArray()));
        if (S.Stt != "LOCAL") col.Children.Add(ModelField(S.SttModel(S.SttOption), v => { S.SttModels[S.Stt] = v.Trim(); S.Save(); }));

        // --- Cleanup
        Section(col, "AI cleanup");
        col.Children.Add(Ui.List(Catalog.Cleanup.Select(o =>
        {
            var needsKey = o.Service != null && S.Key(o.Service).Length == 0;
            return (Control)Ui.Choice(o.Label, o.Note + (needsKey ? " Needs a key." : ""), S.Cleanup == o.Id, () => { S.Cleanup = o.Id; S.Save(); Render(); });
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
            input.TextChanged += (_, _) => { S.SetKey(id, input.Text ?? ""); S.Save(); };
            var pageLink = Link("Get one at " + page, () => Open("https://" + page));
            pageLink.FontSize = 13;
            keys.Children.Add(Spaced(Ui.Text(name, 15.5, bold: true), 0, keys.Children.Count == 0 ? 0 : 16, 0, 0));
            keys.Children.Add(pageLink);
            keys.Children.Add(Spaced(box, 0, 6, 0, 0));
        }
        col.Children.Add(Ui.Card(keys, 20));
        col.Children.Add(Spaced(Ui.Text(KeyStore.Where, 13, C.Sub), 4, 8, 0, 0));

        // --- Recordings
        Section(col, "Recordings");
        col.Children.Add(Ui.List(new[] { (0, "Don't save audio"), (7, "Keep 7 days"), (30, "Keep 30 days"), (int.MaxValue, "Keep forever") }
            .Select(t => (Control)Ui.Choice(t.Item2, "", S.AudioKeepDays == t.Item1, async () =>
            {
                if (t.Item1 < S.AudioKeepDays && AudioStore.TotalBytes() > 0 && !await Ui.Dialog(this,
                        t.Item1 == 0 ? "Delete all saved recordings now? Transcripts stay."
                            : $"Delete recordings older than {t.Item1} days now? Transcripts stay.", "Delete")) return;
                S.AudioKeepDays = t.Item1; S.Save();
                _ = Task.Run(() => AudioStore.Prune(t.Item1)).ContinueWith(_ => Dispatcher.UIThread.Post(Render));
                Render();
            })).ToArray()));
        var mb = AudioStore.TotalBytes() / 1_048_576.0;
        col.Children.Add(Spaced(Spread(Ui.Text(FormattableString.Invariant($"Using {mb:0.0} MB"), 14, C.Sub), Ui.Button("Delete all", async () =>
        {
            if (!await Ui.Dialog(this, "Delete all saved recordings? Transcripts stay.", "Delete")) return;
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
        backupRow.Children[0].Margin = new Thickness(0, 0, 10, 0);
        col.Children.Add(Spaced(backupRow, 0, 12, 0, 0));
        col.Children.Add(Spaced(Ui.Text("Saves one .zip file wherever you choose: Documents, a cloud folder, a USB stick, etc. " + BackupNote, 13, C.Sub), 4, 8, 0, 0));

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
            return (Control)box;
        }).ToArray()));
        col.Children.Add(Spaced(Ui.Text("Estimated at each provider's paid list price. Free-tier usage actually costs $0. Check your provider's dashboard for real billing.", 13, C.Sub), 4, 8, 0, 0));

        // --- About
        Section(col, "About");
        var about = new StackPanel();
        about.Children.Add(Ui.Text($"Tokalot for {Host.Name} " + Updater.CurrentVersion, 16, bold: true));
        about.Children.Add(Spaced(Ui.Text("Your recordings, history and keys stay on this computer. Dictations go only to the speech and cleanup services you chose, with your own keys. No Tokalot servers, accounts or tracking. The " + Host.Shortcut + " listener only watches for that shortcut; it never records your typing.", 14, C.Sub), 0, 4, 0, 0));
        about.Children.Add(Spaced(Link("Source code: github.com/sidewinderzz/tokalot", () => Open(Updater.RepoUrl)), 0, 8, 0, 0));
        var status = Ui.Text("", 14, C.Sub);
        about.Children.Add(Spaced(Ui.Button("Check for updates", async () =>
        {
            if (!Updater.CanUpdate) { status.Text = $"The {Host.Name} app doesn't update itself yet. Newer builds are on the GitHub page above."; return; }
            status.Text = "Checking…";
            var v = await App.Current.CheckForUpdates();
            status.Text = v != null ? $"Version {v} is available. See the banner on Home." : "You're up to date.";
        }), 0, 12, 0, 0));
        about.Children.Add(Spaced(status, 0, 6, 0, 0));
        var licenses = new Border
        {
            Background = C.Field, CornerRadius = new CornerRadius(14), Padding = new Thickness(12), MaxHeight = 320, IsVisible = false, Margin = new Thickness(0, 8, 0, 0),
            Child = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                Content = new SelectableTextBlock { Text = Licenses.Text, TextWrapping = TextWrapping.Wrap, FontSize = 12.5, Foreground = C.Sub, FontFamily = C.Sans, Margin = new Thickness(0, 0, 12, 0) },
            },
        };
        about.Children.Add(Spaced(Link("Open-source licenses", () => licenses.IsVisible = !licenses.IsVisible), 0, 12, 0, 0));
        about.Children.Add(licenses);
        about.Children.Add(Spaced(Link("Quit Tokalot", () => App.Current.Quit()), 0, 12, 0, 0));
        col.Children.Add(Ui.Card(about, 20));
    }

    private const string SyncInfo =
        "Tokalot keeps your dictionary, snippets, styles and instructions in one small file. Put that file in a folder you already sync, " +
        "such as Nextcloud, Google Drive, Dropbox or Syncthing, and point each of your devices at it. Each device reads the file and adds its own changes.\n\n" +
        "It's private. There is no Tokalot account and no Tokalot server. The file only goes where your own sync service takes it. " +
        "Your history and recordings are never put in it.\n\n" +
        "API keys are left out unless you turn on Include API keys. The file isn't encrypted, so only do that if the folder is private to you. " +
        "You can stop syncing at any time; your settings stay on this device.";

    private static readonly FilePickerFileType SyncType = new("Tokalot sync file") { Patterns = new[] { "*.json" }, MimeTypes = new[] { "application/json" } };

    /** Optional: share dictionary, snippets, styles and instructions between devices through one file in a synced folder. */
    private void BuildSync(StackPanel col)
    {
        var info = Ui.Button("", () => _ = Ui.Dialog(this, SyncInfo, "Got it", cancel: null, title: "How sync works"), icon: "info");
        ToolTip.SetTip(info, "How sync works");
        Avalonia.Automation.AutomationProperties.SetName(info, "How sync works");

        async void Use(string path)
        {
            S.SyncFile = path;
            S.Save();
            await Sync.Run();
            Render();
        }

        if (S.SyncFile.Length == 0)
        {
            var create = Ui.Button("Create a sync file", async () =>
            {
                try
                {
                    // No overwrite question is wanted (an existing sync file is joined, not replaced), but the system's
                    // save dialog may still ask; answering yes is fine, Tokalot reads the file before it writes.
                    var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
                    {
                        Title = "Create a sync file", SuggestedFileName = Sync.FileName, DefaultExtension = "json", FileTypeChoices = new[] { SyncType }, ShowOverwritePrompt = false,
                    });
                    if (file?.TryGetLocalPath() is { } path) Use(path);
                }
                catch (Exception e) { await Ui.Dialog(this, "Couldn't open the file chooser: " + e.Message, cancel: null); }
            }, filled: true);
            var existing = Ui.Button("Use an existing sync file", async () =>
            {
                try
                {
                    var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
                    {
                        Title = "Use an existing sync file", AllowMultiple = false, FileTypeFilter = new[] { SyncType },
                    });
                    if (files.Count > 0 && files[0].TryGetLocalPath() is { } path) Use(path);
                }
                catch (Exception e) { await Ui.Dialog(this, "Couldn't open the file chooser: " + e.Message, cancel: null); }
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
        var stop = Ui.Button("Stop syncing", async () =>
        {
            if (!await Ui.Dialog(this, "Stop syncing on this computer? The sync file and your settings here stay as they are.", "Stop syncing")) return;
            S.SyncFile = "";
            S.SyncKeys = false;
            Sync.Forget();
            S.Save();
            Render();
        });
        now.Margin = new Thickness(0, 0, 8, 0);
        col.Children.Add(Spaced(Ui.Row(now, stop), 0, 12, 0, 0));
    }

    /** One choosable indicator style with a live preview on a little dark "screen". */
    private Control IndicatorRow(string id)
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
        row.OnEnter(() => row.Background = C.Hover);
        row.OnLeave(() => row.Background = Brushes.Transparent);
        void Pick()
        {
            S.IndicatorStyle = id;
            S.Save();
            App.Current.Controller?.RefreshIndicator();
            Render();
        }
        row.OnClick(Pick);
        return Ui.Keys(row, Pick, name);
    }

    private static Control ModelField(string value, Action<string> save)
    {
        var (box, input) = Ui.Field(value, "Model");
        input.TextChanged += (_, _) => save(input.Text ?? ""); // empty = the provider's default model
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
        Task.Run(() => ModelManager.Download(progress)).ContinueWith(t => Dispatcher.UIThread.Post(() =>
        {
            downloadPct = null;
            downloadError = t.Result;
            Render();
        }));
    }

    private static readonly FilePickerFileType ZipType = new("Zip file") { Patterns = new[] { "*.zip" }, MimeTypes = new[] { "application/zip" } };

    internal void ShowToast(string msg) => Toast(msg);

    private bool busy; // a backup or restore is running

    private async void DoBackup(bool withAudio, bool withKeys)
    {
        if (busy) return;
        string? path;
        try
        {
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                Title = "Back up Tokalot",
                SuggestedFileName = $"tokalot-desktop-backup-{DateTime.Now:yyyy-MM-dd}.zip", DefaultExtension = "zip", FileTypeChoices = new[] { ZipType },
            });
            path = file?.TryGetLocalPath();
        }
        catch (Exception e)
        {
            await Ui.Dialog(this, "Couldn't open the file chooser: " + e.Message, cancel: null);
            return;
        }
        if (path == null) return;
        busy = true;
        Toast("Backing up…");
        try
        {
            // Zipping recordings can take a while; keep the window (and Ctrl+Super) responsive.
            var s = await Task.Run(() => Backup.Write(path, withAudio, withKeys));
            Toast($"Backed up {s.Entries} dictations" + (s.Recordings > 0 ? $" and {s.Recordings} recordings" : ""));
        }
        catch (Exception e)
        {
            await Ui.Dialog(this, "Backup failed: " + e.Message, cancel: null);
        }
        finally { busy = false; }
    }

    private async void DoRestore()
    {
        if (busy) return;
        if (!await Ui.Dialog(this,
                "This replaces your current settings, dictionary, snippets and history with the backup's. API keys on this computer are kept unless the backup includes keys.",
                "Restore", title: "Restore from backup?")) return;
        string? path;
        try
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "Restore from backup", AllowMultiple = false, FileTypeFilter = new[] { new FilePickerFileType("Tokalot backup") { Patterns = new[] { "*.zip" } } },
            });
            path = files.Count > 0 ? files[0].TryGetLocalPath() : null;
        }
        catch (Exception e)
        {
            await Ui.Dialog(this, "Couldn't open the file chooser: " + e.Message, cancel: null);
            return;
        }
        if (path == null) return;
        busy = true;
        Toast("Restoring…");
        try
        {
            Player.Stop(false);
            var s = await Task.Run(() => Backup.Restore(path));
            // The theme may have changed, which rebuilds the window: finish up on the app, not on this (old) window.
            App.Current.AfterRestore($"Restored {s.Entries} dictations");
        }
        catch (Exception e)
        {
            await Ui.Dialog(this, "Restore failed: " + e.Message, cancel: null);
        }
        finally { busy = false; }
    }
}
