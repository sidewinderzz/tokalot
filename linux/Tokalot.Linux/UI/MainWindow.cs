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
using Avalonia.VisualTree;
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
    public enum Page { Home, Dictionary, Style, Snippets, Notes, Settings, Stats }

    public Page CurrentPage { get; private set; } = Page.Home;

    private readonly StackPanel nav = new() { Margin = new Thickness(14, 18, 14, 0) };
    private readonly ScrollViewer scroller = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private readonly Border toast;
    private readonly TextBlock toastText;
    private readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromMilliseconds(1700) };
    /** "Note deleted · Undo" along the bottom, and "Back to playing" over a voice note's text. */
    private readonly Border undoBar, followChip;
    private readonly DispatcherTimer undoTimer = new() { Interval = TimeSpan.FromSeconds(6) };
    private readonly TextBox search;
    private readonly Border searchBox;
    private readonly List<Control> grips = new();
    private StackPanel? historyBox;
    private TextBlock? bannerText;
    /** The theme this window was built with (see onSync). */
    private readonly string shownTheme = Settings.Current.Theme;
    private TextBlock? modelStatus;
    private TextBlock? modelProgress; // under the list in Settings › Offline model
    private int historyLimit = 100, notesLimit = 100;
    private readonly HashSet<long> expanded = new(), showOriginal = new();
    private static string styleTab = "MESSAGING";
    private static int? downloadPct;
    private static LocalModel? modelDownloading; // one at a time
    private static string? downloadError;
    private Snippet? editing;
    private bool editingNew;
    private readonly Action onHistory, onPlayer, onNoteImport;
    private readonly Border playerHost = new() { IsVisible = false };
    private readonly DispatcherTimer noteTimer = new() { Interval = TimeSpan.FromMilliseconds(50) };
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
        var undo = new TextBlock
        {
            Text = "Undo", Foreground = C.Hex("#6EA8FE"), FontSize = 13.5, FontFamily = C.Sans, FontWeight = FontWeight.SemiBold,
            Margin = new Thickness(18, 0, 0, 0), Cursor = Cursors.Hand, Background = Brushes.Transparent, VerticalAlignment = VerticalAlignment.Center,
        };
        undo.OnClick(UndoDelete, handled: true);
        undoBar = Ui.Stadium(new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x1C, 0x1C, 0x1E)), Padding = new Thickness(18, 9, 16, 9), IsVisible = false,
            Child = Ui.Row(new TextBlock { Text = "Note deleted", Foreground = Brushes.White, FontSize = 13.5, FontFamily = C.Sans, VerticalAlignment = VerticalAlignment.Center },
                Ui.Keys(undo, UndoDelete, "Undo")),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 28),
        });
        undoTimer.Tick += (_, _) => CommitDelete();
        followChip = Ui.Stadium(new Border
        {
            Background = C.Text, Padding = new Thickness(14, 7, 16, 7), Cursor = Cursors.Hand, IsVisible = false,
            Child = Ui.Row(Icons.Get("play", 14, C.Card), new TextBlock { Text = "Back to playing", Foreground = C.Card, FontSize = 13.5, FontFamily = C.Sans, Margin = new Thickness(6, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center }),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 16),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 12, OffsetY = 2, Color = Color.FromArgb(0x40, 0, 0, 0) }),
        });
        followChip.OnClick(NoteFollowAgain, handled: true);
        Ui.Keys(followChip, NoteFollowAgain, "Back to playing");
        // Scrolling by hand (wheel, scroll bar, touch or keys) stops the page following the word being played.
        scroller.AddHandler(PointerWheelChangedEvent, (_, _) => NoteScrolledByHand(), RoutingStrategies.Tunnel, true);
        scroller.AddHandler(PointerPressedEvent, (_, e) => { if (e.Source is Visual v && v.FindAncestorOfType<ScrollBar>(true) != null) NoteScrolledByHand(); }, RoutingStrategies.Tunnel, true);
        scroller.AddHandler(Gestures.ScrollGestureEvent, (_, _) => NoteScrolledByHand(), RoutingStrategies.Bubble, true);
        scroller.AddHandler(KeyDownEvent, (_, e) => { if (e.Key is Key.PageUp or Key.PageDown or Key.Up or Key.Down or Key.Home or Key.End) NoteScrolledByHand(); }, RoutingStrategies.Tunnel, true);

        // Sidebar | content
        var root = new Grid { Background = C.Bg };
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(232) });
        root.ColumnDefinitions.Add(new ColumnDefinition());
        root.Children.Add(Sidebar());
        var contentGrid = new Grid { Margin = new Thickness(0, CaptionHeight, 0, 0) };
        contentGrid.RowDefinitions.Add(new RowDefinition());
        contentGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        contentGrid.Children.Add(scroller);
        Grid.SetRow(playerHost, 1);
        contentGrid.Children.Add(playerHost);
        contentGrid.Children.Add(followChip);
        contentGrid.Children.Add(undoBar);
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

        // One dictation changes history a few times in a row; the list is redrawn once for all of them.
        onHistory = () =>
        {
            if (System.Threading.Interlocked.Exchange(ref historyRedrawQueued, 1) == 1) return;
            Dispatcher.UIThread.Post(() =>
            {
                historyRedrawQueued = 0;
                if (CurrentPage == Page.Home) FillHistory();
            }, DispatcherPriority.Background);
        };
        onPlayer = () => Dispatcher.UIThread.Post(() => { if (CurrentPage == Page.Home) FillHistory(); });
        History.Changed += onHistory;
        Player.Changed += onPlayer;
        Closed += (_, _) => { History.Changed -= onHistory; Player.Changed -= onPlayer; Player.Stop(false); };
        noteTimer.Tick += (_, _) => NoteTick();
        NotePlayer.Changed += NotePlayerChanged;
        Closed += (_, _) => { NotePlayer.Changed -= NotePlayerChanged; NotePlayer.Stop(false); noteTimer.Stop(); CommitDelete(); };
        Activated += (_, _) => Sync.Queue();
        // Coming back from System Settings on a Mac: redraw if a permission changed meanwhile.
        Activated += (_, _) => PlatformActivated();
        // The indicator previews in Settings redraw 60 times a second; they hold still while nobody is looking.
        Activated += (_, _) => FreezePreviews();
        Deactivated += (_, _) => FreezePreviews();
        onSync = changed => Dispatcher.UIThread.Post(() =>
        {
            // The theme may have come in from another device; changing it rebuilds this window.
            if (changed && S.Theme != shownTheme) { App.Current.ApplyTheme(); return; }
            if (changed || CurrentPage is Page.Settings or Page.Stats) Render();
        });
        Sync.Finished += onSync;
        Closed += (_, _) => Sync.Finished -= onSync;
        onNoteImport = () => Dispatcher.UIThread.Post(() => { if (CurrentPage == Page.Notes) Render(); });
        NoteImport.Changed += onNoteImport;
        Closed += (_, _) => NoteImport.Changed -= onNoteImport;
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
            else if (e.Key == Key.Escape && CurrentPage == Page.Notes && openNoteId != null) { e.Handled = true; if (noteRenaming) { noteRenaming = false; Render(); } else CloseNote(); }
            else if (e.Key == Key.Escape && CurrentPage == Page.Home && !string.IsNullOrEmpty(search.Text)) { e.Handled = true; search.Text = ""; }
        }, RoutingStrategies.Tunnel);
        SizeChanged += (_, _) => { if (IsLoaded && IsWide != wideLayout) Render(); };
        Render();
    }

    private int historyRedrawQueued;
    private readonly List<IndicatorView> previews = new();

    private bool PreviewsStill => !IsActive || WindowState == WindowState.Minimized;

    private void FreezePreviews()
    {
        previews.RemoveAll(v => TopLevel.GetTopLevel(v) == null); // pages drawn since
        foreach (var v in previews) v.Frozen = PreviewsStill;
    }

    /**
     * Saves what is typed into a box once typing pauses (or the box loses focus or goes away), rather
     * than on every keystroke: each save rewrites settings.json, and a key also goes to the keyring.
     */
    private static void SaveWhenIdle(TextBox input, Action<string> save)
    {
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(600) };
        var dirty = false;
        void Commit()
        {
            timer.Stop();
            if (!dirty) return;
            dirty = false;
            save(input.Text ?? "");
        }
        timer.Tick += (_, _) => Commit();
        input.TextChanged += (_, _) => { dirty = true; timer.Stop(); timer.Start(); };
        input.LostFocus += (_, _) => Commit();
        input.DetachedFromVisualTree += (_, _) => Commit();
    }

    /** The platform's part may react to the window coming to the front (only the Mac's does). */
    partial void PlatformActivated();

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        // A maximized window has no edges to drag.
        if (change.Property == WindowStateProperty)
        {
            foreach (var g in grips) g.IsVisible = WindowState == WindowState.Normal;
            FreezePreviews();
        }
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
            var active = p == CurrentPage || (p == Page.Home && CurrentPage == Page.Stats); // word stats open from Home
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
        if (S.NotesBeta) Item(Page.Notes, "notes", "Notes");
        nav.Children.Add(new Border { Height = 14 });
        Item(Page.Settings, "settings", "Settings");
    }

    /** Full height of the current page (screenshot mode). */
    internal double ExtentHeight => scroller.Extent.Height;

    internal void SetStyleTab(string id) => styleTab = id;

    internal void OpenSnippetEditor(Snippet? s) { editing = s; editingNew = s == null; Render(); }

    public void Go(Page p)
    {
        if (openNoteId != null) { NotePlayer.Stop(false); openNoteId = null; } // the sidebar's Notes goes back to the list too
        if (p != Page.Notes) CommitDelete();
        if (p != CurrentPage) scroller.Offset = default;
        CurrentPage = p;
        Render();
    }

    /** Rebuilds the current page, keeping the scroll position. */
    public void Render()
    {
        var offset = scroller.Offset.Y;
        RenderNav();
        if (CurrentPage == Page.Notes && !S.NotesBeta) CurrentPage = Page.Home; // voice notes were switched off (or restored away)
        playerHost.IsVisible = false; // the note page puts it back
        var col = new StackPanel { MaxWidth = 760, Margin = new Thickness(36, 10, 36, 48) };
        switch (CurrentPage)
        {
            case Page.Home: BuildHome(col); break;
            case Page.Dictionary: BuildDictionary(col); break;
            case Page.Style: BuildStyle(col); break;
            case Page.Snippets: BuildSnippets(col); break;
            case Page.Notes: BuildNotes(col); break;
            case Page.Settings: BuildSettings(col); break;
            case Page.Stats: BuildStats(col); break;
        }
        wideLayout = IsWide;
        scroller.Content = wideLayout && !(CurrentPage == Page.Notes && openNoteId != null) ? TwoColumns(col) : StripMarkers(col);
        scroller.UpdateLayout();
        scroller.Offset = new Vector(0, offset);
        NoteChip();
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
        else if (Sync.NewerElsewhere)
        {
            // Another device runs a newer Tokalot that syncs things this one can't: worth updating even before a release shows up here.
            var check = Ui.Button("Check for updates", async () => { await app.CheckForUpdates(); Render(); }, filled: true);
            col.Children.Add(Spaced(Ui.Card(Spread(Ui.Stack(
                Ui.Text("Update Tokalot to sync everything", 15),
                Ui.Text("Another device that shares your sync file has a newer Tokalot. This one keeps syncing what it knows, but settings or history added since then only arrive once it's updated.", 13, C.Sub)),
                check), 14), 0, 0, 0, 14));
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
        // The month isn't over yet, so say how far in it is.
        var header = $"{now.ToString("MMMM yyyy", CultureInfo.InvariantCulture)} \u00b7 Day {now.Day} of {DateTime.DaysInMonth(now.Year, now.Month)}";
        var statCard = Ui.Stack(Ui.Text(header.ToUpperInvariant(), 12, C.Sub, bold: true), stats);
        // Clean-ups, other devices and all-time totals live on the word stats page; Home keeps just the three numbers.
        statCard.Children.Add(Spaced(Ui.Text("View stats ›", 13, C.Sub), 0, 10, 0, 0));
        var sc = Ui.Card(statCard, 22);
        sc.Cursor = Cursors.Hand;
        sc.OnClick(() => Go(Page.Stats));
        Ui.Keys(sc, () => Go(Page.Stats), "Word stats");
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

    // ---------- Word stats (opened from the card on Home) ----------

    private void BuildStats(StackPanel col)
    {
        var b = WordStats.Build();
        col.Children.Add(Spaced(Link("‹ Home", () => Go(Page.Home)), 0, 0, 0, 10));
        var where = b.Devices.Count > 1 ? $" on all {b.Devices.Count} of your devices" : "";
        Intro(col, "Your words", b.Months.Count == 0
            ? "Nothing counted yet. Dictate something and it shows up here."
            : $"Since {b.Since}{where}.");

        Control Stat(string value, string label) => Ui.Stack(Ui.Heading(value, 32), Ui.Text(label, 13, C.Sub));
        var cells = new[]
        {
            Stat(Ui.Compact(b.Words), "words"),
            Stat(Ui.Compact(b.Dictations), "dictations"),
            Stat(b.Dictations > 0 ? (b.Words / b.Dictations).ToString(CultureInfo.InvariantCulture) : "–", "words each"),
        };
        var grid = new Grid { Margin = new Thickness(0, 10, 0, 0) };
        for (int i = 0; i < 3; i++) { grid.ColumnDefinitions.Add(new ColumnDefinition()); Grid.SetColumn(cells[i], i); grid.Children.Add(cells[i]); }
        col.Children.Add(Spaced(Ui.Card(Ui.Stack(Ui.Text("ALL TIME", 12, C.Sub, bold: true), grid), 22), 0, 0, 0, 4));

        if (b.Months.Count > 0)
        {
            Section(col, "Words per month");
            var max = Math.Max(1, b.Months.Max(m => m.Words));
            // The month in progress says so, with where it's heading at the pace so far.
            var today = DateTime.Now;
            var nowKey = today.ToString("yyyy-MM", CultureInfo.InvariantCulture);
            var days = DateTime.DaysInMonth(today.Year, today.Month);
            string MonthSub(WordStats.MonthRow m) => m.Key != nowKey ? $"{m.Dictations} dictations"
                : $"So far, day {today.Day} of {days}" + (WordStats.OnTrack(m.Words, today.Day, days) is { } w ? $" \u00b7 on track for {Ui.Compact(w)}" : "");
            col.Children.Add(Ui.List(b.Months.AsEnumerable().Reverse().Select(m => StatBar(m.Short, m.Words, max, MonthSub(m))).ToArray()));
        }

        col.Children.Add(new ColBreak());
        Section(col, "Highlights");
        var rows = new List<Control>();
        void Fact(string label, string value, string? sub = null)
        {
            if (value.Length == 0) return;
            var texts = Ui.Stack(Ui.Text(label, 15));
            if (sub != null) texts.Children.Add(Ui.Text(sub, 13, C.Sub));
            rows.Add(Spaced(Spread(texts, Ui.Text(value, 15, bold: true)), 20, 13, 20, 13));
        }
        if (b.Best is { } best) Fact("Best month", Ui.Compact(best.Words) + " words", best.Label);
        if (b.Months.Count > 1) Fact("Average month", Ui.Compact(b.Average) + " words", "Not counting this month yet");
        if (b.Words > 0) Fact("Typing it would have taken", WordStats.TypingTime(b.Words), $"At the average typing speed, {WordStats.TypingWpm} words a minute");
        Fact("That's about", WordStats.Equivalent(b.Words));
        if (b.Pace is { } pace) Fact("Speaking pace", $"{Math.Round(pace)} words a minute");
        if (b.Longest > 0)
            Fact("Longest dictation", Plural(b.Longest, "word"), DateTimeOffset.FromUnixTimeMilliseconds(b.LongestAt).LocalDateTime.ToString("MMMM d, yyyy", CultureInfo.InvariantCulture));
        if (b.Days.Days > 0) Fact("Days you dictated", b.Days.Days.ToString(CultureInfo.InvariantCulture),
            $"Longest streak {Plural(b.Days.Best, "day")}" + (b.Days.Current > 1 ? $" · {b.Days.Current} days running now" : ""));
        if (b.BusiestDay is { } day) Fact("Busiest day of the week", day.ToString());
        if (b.Here.Fillers + b.Here.Corrections > 0)
            Fact("Cleaned up", Plural(b.Here.Fillers, "filler word"), $"and {Plural(b.Here.Corrections, "self-correction")}");
        if (b.Here.Local > 0) Fact("Offline dictations", b.Here.Local.ToString(CultureInfo.InvariantCulture));
        if (rows.Count == 0) rows.Add(Spaced(Ui.Text("Highlights show up once you've dictated a few times.", 14, C.Sub), 20, 13, 20, 13));
        col.Children.Add(Ui.List(rows.ToArray()));

        if (b.Apps.Count > 0)
        {
            Section(col, "Where you talk the most");
            var max = Math.Max(1, b.Apps[0].Words);
            col.Children.Add(Ui.List(b.Apps.Select(a => StatBar(a.Label, a.Words, max)).ToArray()));
        }

        if (b.Devices.Count > 1)
        {
            Section(col, "By device");
            var max = Math.Max(1, b.Devices.Max(d => d.Words));
            col.Children.Add(Ui.List(b.Devices.Select(d => StatBar(d.Name.Length > 0 ? d.Name : "Unnamed device", d.Words, max, $"{d.Dictations} dictations")).ToArray()));
        }

        var history = OperatingSystem.IsMacOS() ? "this Mac's history" : "this computer's history";
        col.Children.Add(Spaced(Ui.Text(
            (b.Devices.Count > 1 ? "Words per month and clean-ups count every device that syncs. " : "") +
            (S.SyncFile.Length > 0 && S.SyncHistory ? "Pace, streaks and apps come from the dictation history, synced from your other devices too."
                : $"Pace, streaks and apps come from {history}."), 13, C.Sub), 4, 8, 0, 0));
    }

    /** One row of a bar list: a label, a bar as long as [value] is next to [max], and the number. */
    private static Control StatBar(string label, long value, long max, string? sub = null)
    {
        var bar = new Grid { Height = 8, VerticalAlignment = VerticalAlignment.Center };
        bar.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(Math.Max(value, 0), GridUnitType.Star)));
        bar.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(Math.Max(max - value, 0), GridUnitType.Star)));
        bar.Children.Add(new Border { Background = C.Field, CornerRadius = new CornerRadius(4), [Grid.ColumnSpanProperty] = 2 });
        if (value > 0) bar.Children.Add(new Border { Background = C.Text, CornerRadius = new CornerRadius(4), MinWidth = 8 });
        var texts = Ui.Stack(Ui.Text(label, 15));
        if (sub != null) texts.Children.Add(Ui.Text(sub, 12.5, C.Sub));
        var g = new Grid { Margin = new Thickness(20, 12, 20, 12) };
        g.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(170)));
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(64)));
        texts.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(texts);
        Grid.SetColumn(bar, 1);
        g.Children.Add(bar);
        var n = Ui.Text(Ui.Compact(value), 15, bold: true);
        n.HorizontalAlignment = HorizontalAlignment.Right;
        n.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(n, 2);
        g.Children.Add(n);
        return g;
    }

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

    // ---------- Notes (beta) ----------

    private void BuildNotes(StackPanel col)
    {
        if (openNoteId is { } open) { BuildNote(col, open); return; }
        Intro(col, "Notes", "Voice notes from your other devices show up here when sync is set up. You can also add a recording, like a WhatsApp voice message saved to this computer, and it's transcribed into a note you can summarize.");
        col.Children.Add(Spaced(Ui.Button("Add a recording", AddRecording, filled: true), 0, 0, 0, 16));
        if (!S.NotesFeedbackDone)
        {
            // The beta asks whether it's worth keeping: a short public form on GitHub, nothing sent from here.
            void Done() { S.NotesFeedbackDone = true; S.Save(); Render(); }
            var useful = Ui.Button("Useful", () => { Open(NotesFeedbackUrl("useful")); Done(); }, filled: true);
            useful.Margin = new Thickness(0, 0, 8, 0);
            var close = CloseButton(Done);
            col.Children.Add(Spaced(Ui.Card(Ui.Stack(
                Spread(Ui.Text("Voice notes are a beta. Worth keeping?", 15.5, bold: true), close),
                Spaced(Ui.Text("Opens a short form on GitHub (a free account is needed). It's public, so your notes aren't in it.", 13.5, C.Sub), 0, 2, 0, 10),
                Ui.Row(useful, Ui.Button("Not for me", () => { Open(NotesFeedbackUrl("not for me")); Done(); }))), 20), 0, 0, 0, 16));
        }
        // Recordings still being transcribed, or that failed: they become notes once they're done.
        foreach (var w in NoteImport.Waiting())
        {
            var running = NoteImport.IsRunning(w.Id);
            var status = running ? "Transcribing…"
                : w.Status == "pending" ? "Tokalot closed before this was transcribed."
                : "Couldn't transcribe it: " + (w.Error.Length > 0 ? w.Error : "unknown error");
            var actions = new List<Control>();
            if (!running)
            {
                var again = Ui.Button("Try again", () => { _ = NoteImport.Transcribe(S, w.Id); Render(); }, filled: true);
                again.Margin = new Thickness(0, 0, 8, 0);
                actions.Add(again);
                actions.Add(Ui.Button("Remove", async () =>
                {
                    if (!await Ui.Dialog(this, "Remove this recording? It can't be brought back.", "Remove")) return;
                    NoteImport.DeleteMedia(w.Id);
                    Render();
                }));
            }
            col.Children.Add(Spaced(Ui.Card(Ui.Stack(
                Ui.Text(w.Name.Length > 0 ? w.Name : "Recording", 15.5, bold: true),
                Spaced(Ui.Text(status, 13.5, running ? C.Sub : C.Warn), 0, 2, 0, actions.Count > 0 ? 10 : 0),
                Ui.Row(actions.ToArray())), 20), 0, 0, 0, 16));
        }
        var notes = Notes.All().Where(n => n.Id != pendingDelete).ToList();
        if (notes.Count == 0)
        {
            col.Children.Add(Ui.Card(Ui.Text(S.SyncFile.Length > 0
                ? "No notes yet. Notes dictated on your phone or another computer show up here after a sync."
                : "No notes yet. Add a recording, or set up sync in Settings to see the notes from your other devices.", 16, C.Sub), 24));
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
            group!.Children.Add(NoteRow(n));
        }
        if (notes.Count > notesLimit)
        {
            var more = Ui.Button("Show older", () => { notesLimit += 200; Render(); });
            more.HorizontalAlignment = HorizontalAlignment.Center;
            col.Children.Add(Spaced(more, 0, 20, 0, 0));
        }
    }

    private async void AddRecording()
    {
        try
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "Add a recording", AllowMultiple = true,
                FileTypeFilter = new[]
                {
                    new FilePickerFileType("Recordings") { Patterns = NoteImport.Extensions.Select(e => "*" + e).ToArray() },
                    FilePickerFileTypes.All,
                },
            });
            if (files.Count == 0) return;
            var added = files.Count(f => f.TryGetLocalPath() is { } path && NoteImport.AddRecording(S, path));
            if (added < files.Count) Toast(added == 0 ? "Couldn't read that file" : "Some files couldn't be read");
            Render();
        }
        catch (Exception e) { await Ui.Dialog(this, "Couldn't open the file chooser: " + e.Message, cancel: null); }
    }

    /**
     * The beta's feedback form on GitHub (.github/ISSUE_TEMPLATE/voice-notes-feedback.yml), with the vote in the
     * title and the app and system filled in. Nothing else about the computer or the notes is sent.
     */
    private static string NotesFeedbackUrl(string vote) =>
        Updater.RepoUrl + "/issues/new?template=voice-notes-feedback.yml&title=" + Uri.EscapeDataString("[Voice notes] " + vote) +
        "&app=" + Uri.EscapeDataString($"Tokalot {Updater.CurrentVersion} on {Host.Name} {Environment.OSVersion.Version}");

    /** Per note: what's being asked for (a summary or the timeline) right now, and the last error. */
    private readonly Dictionary<long, string> noteBusy = new(), noteError = new();

    private async void RunNote(long id, string what, Func<Task> work)
    {
        if (noteBusy.ContainsKey(id)) return;
        noteBusy[id] = what;
        noteError.Remove(id);
        Render();
        try { await work(); }
        catch (Exception e) { noteError[id] = e.Message; }
        noteBusy.Remove(id);
        if (CurrentPage == Page.Notes) Render();
    }

    /** A small round ✕. */
    private static Border CloseButton(Action onClose)
    {
        var close = new Border
        {
            Width = 26, Height = 26, CornerRadius = new CornerRadius(13), Background = Brushes.Transparent, Cursor = Cursors.Hand,
            Child = Icons.Get("close", 15, C.Sub),
        };
        ToolTip.SetTip(close, "Close");
        close.OnEnter(() => close.Background = C.Hover);
        close.OnLeave(() => close.Background = Brushes.Transparent);
        close.OnClick(onClose);
        return Ui.Keys(close, onClose, "Close");
    }

    /** Three grey bars that gently pulse while the AI writes a summary or the timeline. */
    private static Control Skeleton()
    {
        var box = new StackPanel { Margin = new Thickness(0, 6, 0, 0) };
        foreach (var w in new[] { 1.0, 0.82, 0.58 })
        {
            var g = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            g.ColumnDefinitions.Add(new ColumnDefinition(w, GridUnitType.Star));
            g.ColumnDefinitions.Add(new ColumnDefinition(1 - w, GridUnitType.Star));
            g.Children.Add(new Border { Height = 12, CornerRadius = new CornerRadius(6), Background = C.Pill });
            box.Children.Add(g);
        }
        // Fades between full and a third about once a second, only while it's on screen.
        var pulse = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        var start = DateTime.UtcNow;
        pulse.Tick += (_, _) => box.Opacity = 0.675 + 0.325 * Math.Cos((DateTime.UtcNow - start).TotalSeconds * Math.PI / 0.9);
        box.AttachedToVisualTree += (_, _) => pulse.Start();
        box.DetachedFromVisualTree += (_, _) => pulse.Stop();
        return box;
    }

    private static SelectableTextBlock Selectable(string text, double size) => new()
    {
        Text = text, Foreground = C.Text, FontSize = size, FontFamily = C.Sans, TextWrapping = TextWrapping.Wrap,
    };

    /** A note in the list: its first lines and when it was made. Clicking it opens the note. */
    private Control NoteRow(Note n)
    {
        var info = NoteImport.Get(n.Id);
        var voice = info?.Audio != null;
        var flat = System.Text.RegularExpressions.Regex.Replace(n.Text.Trim(), @"\s+", " ");
        // With a title: the title in bold, and the start of the text under it, in grey.
        var preview = Ui.Text(info?.Title ?? flat, 16.5, bold: info?.Title != null);
        preview.MaxLines = 2;
        preview.TextTrimming = TextTrimming.CharacterEllipsis;
        var lines = Ui.Stack(preview);
        if (info?.Title != null)
        {
            var first = Ui.Text(flat, 14.5, C.Sub);
            first.MaxLines = 1;
            first.TextTrimming = TextTrimming.CharacterEllipsis;
            lines.Children.Add(Spaced(first, 0, 2, 0, 0));
        }
        var meta = DateTimeOffset.FromUnixTimeMilliseconds(n.Time).LocalDateTime.ToString("h:mm tt", CultureInfo.InvariantCulture);
        if (voice)
        {
            meta += " · voice note";
            if (info!.Duration > 0) meta += " · " + NoteMedia.Clock(info.Duration);
            if (info.Source != null) meta += " · from " + info.Source;
        }
        var metaRow = Ui.Row();
        if (voice) metaRow.Children.Add(Spaced(Icons.Get("play", 14, C.Sub), 0, 0, 4, 0));
        metaRow.Children.Add(Ui.Text(meta, 13.5, C.Sub));
        var row = new Border
        {
            Padding = new Thickness(16, 12, 16, 12), Margin = new Thickness(6), CornerRadius = new CornerRadius(22),
            Background = Brushes.Transparent, Cursor = Cursors.Hand, Child = Ui.Stack(lines, Spaced(metaRow, 0, 6, 0, 0)),
        };
        row.OnEnter(() => row.Background = C.Hover);
        row.OnLeave(() => row.Background = Brushes.Transparent);
        row.OnClick(() => OpenNote(n.Id));
        return Ui.Keys(row, () => OpenNote(n.Id), "Open note" + (info?.Title is { } t ? ": " + t : ""));
    }

    // ---------- one note ----------

    private long? openNoteId;
    private bool noteSummaryShown, noteTimelineShown;
    private string? noteFile;
    private List<NoteMedia.Word> noteWords = new();
    private List<NoteMedia.Cut>? noteSilence;
    private List<NoteMedia.Cut> noteCuts = new();
    private double noteDuration;
    private readonly List<Avalonia.Controls.Documents.Run> noteRuns = new();
    private int[] noteStarts = Array.Empty<int>();
    private int noteLit = -1;
    private Slider? noteSlider;
    private TextBlock? noteElapsed, noteLeft, noteText;
    private bool noteSliderSetting;
    /** Jumping over silence restarts the player part way in, which needs ffplay or mpv. */
    private bool noteCanSeek;
    /** The timeline's points (time, the bar beside it, its text), to mark the one being played. */
    private readonly List<(double Time, Border Bar, TextBlock Text)> noteMarks = new();
    private int noteMarkLit = -1;
    /** Whether the page scrolls along with the word being played; scrolling by hand turns it off. */
    private bool noteFollow = true;
    private double? noteScrollTo;
    private Control? noteHint;
    private bool noteRenaming;
    private string noteRenameText = "";
    /** Notes a title was already asked for this session, so a failure isn't retried on every redraw. */
    private readonly HashSet<long> titleTried = new();
    /** The note hidden by Delete, until Undo brings it back or the bar goes and it's deleted for real. */
    private long? pendingDelete;

    private static IBrush Accent => !C.Dark && S.Accent == 0xFFFFFFFF ? C.Text : C.Argb(S.Accent);

    private void OpenNote(long id)
    {
        openNoteId = id;
        noteSummaryShown = noteTimelineShown = noteRenaming = false;
        noteSilence = null;
        noteResumeAt = 0;
        noteFollow = true;
        noteScrollTo = null;
        NotePlayer.Stop(false);
        scroller.Offset = default;
        Render();
    }

    private void CloseNote()
    {
        NotePlayer.Stop(false);
        noteTimer.Stop();
        openNoteId = null;
        noteRenaming = false;
        noteScrollTo = null;
        Render();
    }

    /** Hides the note at once; it's deleted when the "Note deleted · Undo" bar goes, unless Undo is clicked. */
    private void DeleteNote(long id)
    {
        CommitDelete();
        pendingDelete = id;
        undoBar.IsVisible = true;
        undoTimer.Start();
        CloseNote();
    }

    /** Deletes the hidden note for real: the bar ran out, another note was deleted, or the Notes page was left. */
    private void CommitDelete()
    {
        undoTimer.Stop();
        undoBar.IsVisible = false;
        if (pendingDelete is not { } id) return;
        pendingDelete = null;
        Notes.Delete(id);
    }

    private void UndoDelete()
    {
        undoTimer.Stop();
        undoBar.IsVisible = false;
        if (pendingDelete == null) return;
        pendingDelete = null;
        Render();
    }

    private async void MakeTitle(long id, string text)
    {
        if (await NoteImport.MakeTitle(S, id, text) != null && CurrentPage == Page.Notes) Render();
    }

    /** Summary and Timeline: quiet outline pills with an icon, filled while their card is open. */
    private static Border NotePill(string icon, string label, bool open, Action onClick)
    {
        var fg = open ? C.Card : C.Text;
        var b = Ui.Stadium(new Border
        {
            Background = open ? C.Text : Brushes.Transparent, BorderBrush = open ? C.Text : C.Line, BorderThickness = new Thickness(1),
            Height = 32, Padding = new Thickness(11, 0, 14, 0), Cursor = Cursors.Hand, HorizontalAlignment = HorizontalAlignment.Left,
            Child = Ui.Row(Spaced(Icons.Get(icon, 15, fg), 0, 0, 6, 0),
                new TextBlock { Text = label, FontSize = 13.5, Foreground = fg, FontFamily = C.Sans, VerticalAlignment = VerticalAlignment.Center }),
        });
        b.OnEnter(() => b.Opacity = 0.82);
        b.OnLeave(() => b.Opacity = 1);
        b.OnClick(onClick, handled: true);
        return Ui.Keys(b, onClick, label);
    }

    /**
     * The note page, laid out like the phone's: the title, then the text (a voice note's words can be clicked to
     * play from there), Summary and Timeline as small buttons above it that open below them, the rest in the ⋮
     * menu, and a slim player along the bottom of the window.
     */
    private void BuildNote(StackPanel col, long id)
    {
        var back = Ui.Button("Notes", CloseNote, icon: "back");
        var note = id == pendingDelete ? null : Notes.All().FirstOrDefault(x => x.Id == id);
        if (note == null)
        {
            col.Children.Add(back);
            col.Children.Add(Spaced(Ui.Text("This note was deleted.", 16, C.Sub), 4, 16, 0, 0));
            return;
        }
        var info = NoteImport.Get(id);
        var file = NoteImport.AudioFile(id);
        noteWords = info?.Words ?? new();
        noteDuration = info?.Duration ?? 0;
        var voice = file != null && noteWords.Count > 0;
        noteFile = voice ? file : null;
        noteCanSeek = voice && NotePlayer.CanSeek;
        if (voice && noteSilence == null)
        {
            noteSilence = info!.Silence;
            if (noteSilence == null)
            {
                noteSilence = new();
                var words = noteWords; var duration = noteDuration;
                Task.Run(() =>
                {
                    var found = NotePlayer.FindSilence(file!, words, duration);
                    if (found == null) return;
                    NoteImport.Update(id, o => o["silence"] = new System.Text.Json.Nodes.JsonArray(
                        found.Select(c => (System.Text.Json.Nodes.JsonNode)new System.Text.Json.Nodes.JsonArray(c.Start, c.End)).ToArray()));
                    Dispatcher.UIThread.Post(() => { if (openNoteId == id) { noteSilence = found; NoteRecut(); } });
                });
            }
        }
        NoteRecut();
        // A short title from the AI, made once in the background; quietly nothing without a key or on failure.
        if (info?.Title == null && info?.TitleOff != true && note.Text.Trim().Length >= 40 && titleTried.Add(id)) MakeTitle(id, note.Text);

        // The ⋮ menu: what's set once and left alone, and the rarer actions.
        var menu = Ui.Menu();
        Border more = null!;
        more = Ui.Button("", () => menu.Open(more), icon: "more");
        var rename = new MenuItem { Header = "Rename" };
        rename.Click += (_, _) => { noteRenaming = true; noteRenameText = NoteImport.Get(id)?.Title ?? ""; Render(); };
        menu.Items.Add(rename);
        if (voice)
        {
            var silence = new MenuItem { Header = "Skip silence", ToggleType = MenuItemToggleType.CheckBox, IsChecked = S.NoteSkipSilence };
            silence.Click += (_, _) => { S.NoteSkipSilence = !S.NoteSkipSilence; S.Save(); NoteRecut(); };
            var ums = new MenuItem { Header = "Skip ums", ToggleType = MenuItemToggleType.CheckBox, IsChecked = S.NoteSkipUms };
            ums.Click += (_, _) => { S.NoteSkipUms = !S.NoteSkipUms; S.Save(); NoteRecut(); };
            menu.Items.Add(silence);
            menu.Items.Add(ums);
        }
        var copy = new MenuItem { Header = "Copy text" };
        copy.Click += (_, _) => { _ = TextInjector.Copy(note.Text); Toast("Copied"); };
        menu.Items.Add(copy);
        var delete = new MenuItem { Header = "Delete note" };
        delete.Click += (_, _) => DeleteNote(id);
        menu.Items.Add(delete);
        more.ContextMenu = menu;
        col.Children.Add(Spread(back, more));

        // The title (Rename edits it here; saving it empty removes it and stops the AI making another).
        if (noteRenaming)
        {
            var (box, input) = Ui.Field(noteRenameText, "Title");
            void Save() { NoteImport.SetTitle(id, noteRenameText); noteRenaming = false; Render(); }
            input.TextChanged += (_, _) => noteRenameText = input.Text ?? "";
            input.KeyDown += (_, e) => { if (e.Key == Key.Enter) { e.Handled = true; Save(); } };
            input.AttachedToVisualTree += (_, _) => Dispatcher.UIThread.Post(() => { input.Focus(); input.SelectAll(); });
            col.Children.Add(Spaced(Spread(box, Ui.Button("Cancel", () => { noteRenaming = false; Render(); }), Ui.Button("Save", Save, filled: true)), 0, 14, 0, 0));
        }
        else if (info?.Title is { } title) col.Children.Add(Spaced(Ui.Heading(title, 30), 4, 14, 0, 0));

        var stamp = DateTimeOffset.FromUnixTimeMilliseconds(note.Time).LocalDateTime.ToString("dddd, MMM d · h:mm tt", CultureInfo.InvariantCulture);
        if (info?.Source != null) stamp += " · from " + info.Source;
        col.Children.Add(Spaced(Ui.Text(stamp, 13.5, C.Sub), 4, noteRenaming || info?.Title != null ? 6 : 16, 0, 10));

        // Summary and Timeline: small buttons above the text, each opening right under them. They're reached for
        // instead of reading, so they come first and the text follows.
        var segments = info?.Segments ?? new();
        var buttons = Ui.Row(NotePill("notes", "Summary", noteSummaryShown, () =>
        {
            noteSummaryShown = !noteSummaryShown;
            if (noteSummaryShown && NoteImport.Get(id)?.Summary(S.NoteSummaryLevel) == null) Summarize(note, S.NoteSummaryLevel, voice, false);
            else Render();
        }));
        if (voice && segments.Count >= 2)
        {
            var timeline = NotePill("schedule", "Timeline", noteTimelineShown, () =>
            {
                noteTimelineShown = !noteTimelineShown;
                if (noteTimelineShown && NoteImport.Get(id)?.Index == null) RunNote(id, "Making the timeline…", () => NoteImport.MakeIndex(S, id, segments));
                else Render();
            });
            timeline.Margin = new Thickness(8, 0, 0, 0);
            buttons.Children.Add(timeline);
        }
        col.Children.Add(Spaced(buttons, 0, 0, 0, 14));

        noteBusy.TryGetValue(id, out var busy);
        noteMarks.Clear();
        noteMarkLit = -1;
        if (noteError.TryGetValue(id, out var err)) col.Children.Add(Spaced(Ui.Text("Couldn't do that: " + err, 14, C.Warn), 4, 0, 0, 12));
        if (noteSummaryShown) col.Children.Add(Spaced(SummaryPanel(note, info, voice, busy), 0, 0, 0, 14));
        if (noteTimelineShown && voice) col.Children.Add(Spaced(TimelinePanel(id, info, segments, busy), 0, 0, 0, 14));

        // The text. A voice note's is built word by word so a click plays from that word and playback lights it up.
        Control body;
        noteRuns.Clear();
        noteLit = -1;
        noteText = null;
        noteHint = null;
        if (voice)
        {
            // Shown until the first click on a word or its ✕; this computer only.
            void HintSeen()
            {
                if (noteHint != null) noteHint.IsVisible = false;
                if (S.NoteWordHintSeen) return;
                S.NoteWordHintSeen = true;
                S.Save();
            }
            if (!S.NoteWordHintSeen)
            {
                var hint = Spaced(Spread(Ui.Text("Click any word to play from there", 13.5, C.Sub), CloseButton(HintSeen)), 4, 0, 0, 8);
                noteHint = hint;
                col.Children.Add(hint);
            }
            var t = noteText = new TextBlock { FontSize = 16.5, FontFamily = C.Sans, Foreground = C.Text, TextWrapping = TextWrapping.Wrap, LineHeight = 16.5 * 1.5, Cursor = Cursors.Hand, Background = Brushes.Transparent };
            // Screen readers get the whole text as one block; the words aren't stops for Tab.
            Avalonia.Automation.AutomationProperties.SetName(t, note.Text);
            KeyboardNavigation.SetIsTabStop(t, false);
            var inlines = new Avalonia.Controls.Documents.InlineCollection();
            noteStarts = new int[noteWords.Count];
            var pos = 0;
            for (var i = 0; i < noteWords.Count; i++)
            {
                if (i > 0)
                {
                    var gap = noteWords[i].Start - noteWords[i - 1].End >= 2.0 ? "\n\n" : " ";
                    inlines.Add(new Avalonia.Controls.Documents.Run(gap));
                    pos += gap.Length;
                }
                var run = new Avalonia.Controls.Documents.Run(noteWords[i].Text);
                inlines.Add(run);
                noteRuns.Add(run);
                noteStarts[i] = pos;
                pos += noteWords[i].Text.Length;
            }
            t.Inlines = inlines;
            // Runs don't take clicks here, so the click is matched to the word under it.
            t.PointerReleased += (_, e) =>
            {
                if (e.InitialPressMouseButton != MouseButton.Left) return;
                var hit = t.TextLayout.HitTestPoint(e.GetPosition(t));
                if (!hit.IsInside) return;
                var i = Array.BinarySearch(noteStarts, hit.TextPosition);
                if (i < 0) i = ~i - 1;
                if (i >= 0 && i < noteWords.Count && hit.TextPosition < noteStarts[i] + noteWords[i].Text.Length) { HintSeen(); NotePlay(noteWords[i].Start); }
            };
            body = t;
        }
        else
        {
            var t = Selectable(note.Text, 16.5);
            body = t;
        }
        col.Children.Add(Ui.Card(body, 24));

        if (voice) { playerHost.Child = NotePlayerBar(); playerHost.IsVisible = true; }
    }

    private void Summarize(Note n, int level, bool voice, bool redo)
    {
        if (!redo && NoteImport.Get(n.Id)?.Summary(level) != null) { Render(); return; }
        RunNote(n.Id, "Summarizing…", () => NoteImport.Summarize(S, n.Id, n.Text, level, voice));
    }

    private Control SummaryPanel(Note n, NoteImport.Info? info, bool voice, string? busy)
    {
        var level = S.NoteSummaryLevel;
        // The length, e.g. "Short ▾", in the header: it picks another and shows (or makes) that summary.
        var lengths = Ui.Menu();
        for (var l = 1; l <= NoteMedia.LevelNames.Length; l++)
        {
            var lv = l;
            var item = new MenuItem { Header = NoteMedia.LevelNames[l - 1], ToggleType = MenuItemToggleType.CheckBox, IsChecked = level == l };
            item.Click += (_, _) =>
            {
                S.NoteSummaryLevel = lv;
                S.Save();
                Summarize(n, lv, voice, false);
            };
            lengths.Items.Add(item);
        }
        var length = Ui.Text(NoteMedia.LevelNames[level - 1] + " ▾", 13, C.Sub);
        length.Cursor = Cursors.Hand;
        length.Background = Brushes.Transparent;
        length.ContextMenu = lengths;
        void Pick() => lengths.Open(length);
        length.OnClick(Pick, handled: true);
        var box = Ui.Stack(Spaced(Spread(Ui.Text("Summary", 15.5, bold: true), Ui.Keys(length, Pick, "Summary length: " + NoteMedia.LevelNames[level - 1])), 0, 0, 0, 10));
        if (busy == "Summarizing…") box.Children.Add(Skeleton());
        else if (info?.Summary(level) is { } summary)
        {
            box.Children.Add(Selectable(summary, 15.5));
            var copy = Ui.Button("Copy", () => { _ = TextInjector.Copy(summary); Toast("Copied"); }, icon: "copy");
            copy.Margin = new Thickness(0, 10, 8, 0);
            var redo = Ui.Button("Redo", () => Summarize(n, level, voice, true));
            redo.Margin = new Thickness(0, 10, 0, 0);
            box.Children.Add(Ui.Row(copy, redo));
        }
        else box.Children.Add(Ui.Button("Summarize", () => Summarize(n, level, voice, false), filled: true));
        return Ui.Card(box, 22);
    }

    private Control TimelinePanel(long id, NoteImport.Info? info, List<NoteMedia.Segment> segments, string? busy)
    {
        var box = Ui.Stack(Ui.Text("Timeline", 15.5, bold: true),
            Spaced(Ui.Text("Every point in the order it was said. Click one to hear it.", 13, C.Sub), 0, 2, 0, 8));
        var points = info?.Index;
        if (busy == "Making the timeline…") box.Children.Add(Skeleton());
        else if (points == null)
            box.Children.Add(Ui.Button("Make the timeline", () => RunNote(id, "Making the timeline…", () => NoteImport.MakeIndex(S, id, segments)), filled: true));
        else if (points.Count == 0) box.Children.Add(Ui.Text("Nothing to put on it.", 14, C.Sub));
        else
        {
            var lastTopic = "";
            foreach (var p in points)
            {
                if (p.Topic.Length > 0 && !p.Topic.Equals(lastTopic, StringComparison.OrdinalIgnoreCase))
                    box.Children.Add(Spaced(Ui.Text(p.Topic.ToUpperInvariant(), 11.5, C.Sub, bold: true), 0, 10, 0, 2));
                lastTopic = p.Topic;
                // An accent bar and bold text mark the point being played (see NoteShow).
                var bar = new Border { Width = 3, CornerRadius = new CornerRadius(1.5), Margin = new Thickness(0, 2, 8, 2), Background = Brushes.Transparent };
                DockPanel.SetDock(bar, Dock.Left);
                var time = Ui.Text(NoteMedia.Clock(p.Time), 14, C.Link);
                time.Width = 52;
                DockPanel.SetDock(time, Dock.Left);
                var text = Ui.Text(p.Text, 14.5);
                var row = new DockPanel { Margin = new Thickness(0, 3, 0, 3), Background = Brushes.Transparent, Cursor = Cursors.Hand };
                row.Children.Add(bar);
                row.Children.Add(time);
                row.Children.Add(text);
                noteMarks.Add((p.Time, bar, text));
                var at = p.Time;
                row.OnClick(() => NotePlay(at));
                box.Children.Add(Ui.Keys(row, () => NotePlay(at), NoteMedia.Clock(p.Time) + ", " + p.Text));
            }
            var redo = Ui.Button("Redo", () => RunNote(id, "Making the timeline…", () => NoteImport.MakeIndex(S, id, segments)));
            redo.Margin = new Thickness(0, 10, 0, 0);
            box.Children.Add(redo);
        }
        return Ui.Card(box, 22);
    }

    // ---------- the player ----------

    /** What playback jumps over: stretches with no voice and "um"s, as switched on in the ⋮ menu. */
    private void NoteRecut() =>
        noteCuts = NoteMedia.Merge((S.NoteSkipSilence ? noteSilence ?? new List<NoteMedia.Cut>() : new List<NoteMedia.Cut>())
            .Concat(S.NoteSkipUms ? NoteMedia.FillerCuts(noteWords) : new List<NoteMedia.Cut>()));

    private static string SpeedLabel(double v) => v.ToString("0.##", CultureInfo.InvariantCulture) + "×";

    /** Play, a position bar with the time gone and left under its ends, and the speed. */
    private Control NotePlayerBar()
    {
        var playing = NotePlayer.Playing && NotePlayer.File == noteFile;
        var play = Ui.Button("", NoteToggle, filled: true, icon: playing ? "pause" : "play");
        var slider = noteSlider = new Slider
        {
            Theme = Ui.SeekBarTheme(Accent), Minimum = 0, Maximum = Math.Max(0.1, noteDuration),
            Value = NotePlayer.File == noteFile ? NotePlayer.Position : 0,
        };
        slider.ValueChanged += (_, e) =>
        {
            if (noteSliderSetting) return;
            if (NotePlayer.File == noteFile) NotePlayer.Seek(e.NewValue);
            noteResumeAt = e.NewValue;
            NoteShow(e.NewValue);
        };
        noteElapsed = Ui.Text("", 12, C.Sub);
        noteLeft = Ui.Text("", 12, C.Sub);
        noteLeft.HorizontalAlignment = HorizontalAlignment.Right;
        var times = new Grid { Margin = new Thickness(0, -2, 0, 0) };
        times.Children.Add(noteElapsed);
        times.Children.Add(noteLeft);
        var middle = Ui.Stack(slider, times);
        middle.Margin = new Thickness(14, 0, 14, 0);
        middle.VerticalAlignment = VerticalAlignment.Center;
        var speed = Ui.Button(SpeedLabel(S.NoteSpeed), () =>
        {
            var i = Array.FindIndex(Sync.NoteSpeeds, v => Math.Abs(v - S.NoteSpeed) < 0.001);
            S.NoteSpeed = Sync.NoteSpeeds[(i + 1) % Sync.NoteSpeeds.Length];
            S.Save();
            NotePlayer.SetSpeed(S.NoteSpeed);
            playerHost.Child = NotePlayerBar();
        });
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(play);
        Grid.SetColumn(middle, 1); g.Children.Add(middle);
        Grid.SetColumn(speed, 2); g.Children.Add(speed);
        var bar = Ui.Stack(g);
        // Without ffplay or mpv the recording only plays from the start (pw-play, paplay or afplay).
        if (!noteCanSeek) bar.Children.Add(Spaced(Ui.Text("Install ffmpeg to jump to words and skip silence.", 12.5, C.Sub), 0, 6, 0, 0));
        NoteShow(slider.Value);
        return new Border { Background = C.Card, BorderBrush = C.Line, BorderThickness = new Thickness(0, 1, 0, 0), Padding = new Thickness(24, 10, 24, 10), Child = bar };
    }

    /** Where Play starts when nothing is loaded yet: the start, or wherever the bar was dragged. */
    private double noteResumeAt;

    private void NoteToggle()
    {
        if (noteFile == null) return;
        if (NotePlayer.Playing && NotePlayer.File == noteFile) NotePlayer.Pause();
        else NotePlay(NotePlayer.File == noteFile ? NotePlayer.Position : noteResumeAt);
    }

    private void NotePlay(double from)
    {
        if (noteFile == null) return;
        noteFollow = true;
        noteScrollTo = null;
        NotePlayer.Play(noteFile, from, S.NoteSpeed);
    }

    private void NotePlayerChanged() => Dispatcher.UIThread.Post(() =>
    {
        if (NotePlayer.Error is { } err) Toast("Couldn't play the recording: " + err);
        if (NotePlayer.File == null) noteResumeAt = 0;
        if (NotePlayer.Playing) noteTimer.Start(); else noteTimer.Stop();
        if (openNoteId != null && noteFile != null && playerHost.IsVisible) playerHost.Child = NotePlayerBar();
        NoteChip();
    });

    /** About 20 times a second while playing: jumps over a cut, moves the bar, lights up the word and follows it. */
    private void NoteTick()
    {
        if (!NotePlayer.Playing || NotePlayer.File != noteFile) { noteTimer.Stop(); return; }
        var at = NotePlayer.Position;
        if (noteCanSeek && NoteMedia.SkipTo(noteCuts, at) is { } to)
        {
            if (to >= noteDuration - 0.05) { NotePlayer.Pause(); NotePlayer.Seek(0); NoteShow(0); return; }
            NotePlayer.Seek(to);
            at = to;
        }
        NoteShow(at);
        // Glides a third of the way to where the page should be on each tick.
        if (noteScrollTo is { } y)
        {
            var now = scroller.Offset.Y;
            var next = now + (y - now) * 0.3;
            if (Math.Abs(y - next) < 1) { next = y; noteScrollTo = null; }
            scroller.Offset = new Vector(scroller.Offset.X, next);
        }
    }

    private void NoteShow(double at)
    {
        if (noteSlider != null) { noteSliderSetting = true; noteSlider.Value = at; noteSliderSetting = false; }
        if (noteElapsed != null) noteElapsed.Text = NoteMedia.Clock(at);
        if (noteLeft != null) noteLeft.Text = "-" + NoteMedia.Clock(Math.Max(0, noteDuration - at));
        var here = NotePlayer.File == noteFile;
        // The timeline's point being played: the latest one at or before here.
        var m = -1;
        if (here)
            for (var k = 0; k < noteMarks.Count; k++)
                if (noteMarks[k].Time <= at && (m < 0 || noteMarks[k].Time >= noteMarks[m].Time)) m = k;
        if (m != noteMarkLit)
        {
            if (noteMarkLit >= 0 && noteMarkLit < noteMarks.Count) { noteMarks[noteMarkLit].Bar.Background = Brushes.Transparent; noteMarks[noteMarkLit].Text.FontWeight = FontWeight.Normal; }
            noteMarkLit = m;
            if (m >= 0) { noteMarks[m].Bar.Background = Accent; noteMarks[m].Text.FontWeight = FontWeight.SemiBold; }
        }
        var i = here ? NoteMedia.WordAt(noteWords, at + 0.02) : -1;
        if (i == noteLit) return;
        if (noteLit >= 0 && noteLit < noteRuns.Count) noteRuns[noteLit].Background = null;
        noteLit = i;
        if (i >= 0 && i < noteRuns.Count) noteRuns[i].Background = new SolidColorBrush(Color.FromArgb(0x55, 0x6E, 0xA8, 0xFE));
        NoteFollow(i, false);
    }

    /**
     * Keeps the word being played in view: once it leaves the comfortable middle of the page, the page glides so
     * it sits about a third of the way down ([force]: whatever it is now).
     */
    private void NoteFollow(int i, bool force)
    {
        if (!noteFollow || noteText == null || i < 0 || i >= noteStarts.Length || !NotePlayer.Playing) return;
        var word = noteText.TextLayout.HitTestTextPosition(noteStarts[i]);
        if (noteText.TranslatePoint(word.TopLeft, scroller) is not { } p) return;
        var view = scroller.Viewport.Height;
        if (view <= 0 || (!force && p.Y >= view * 0.1 && p.Y + word.Height <= view * 0.75)) return;
        noteScrollTo = Math.Clamp(scroller.Offset.Y + p.Y - view / 3, 0, Math.Max(0, scroller.Extent.Height - view));
    }

    private void NoteScrolledByHand()
    {
        if (openNoteId == null || noteFile == null || !noteFollow) return;
        noteFollow = false;
        noteScrollTo = null;
        NoteChip();
    }

    /** "Back to playing" shows while a voice note plays and the page was scrolled away from it. */
    private void NoteChip() =>
        followChip.IsVisible = CurrentPage == Page.Notes && openNoteId != null && noteFile != null && !noteFollow && NotePlayer.Playing && NotePlayer.File == noteFile;

    private void NoteFollowAgain()
    {
        noteFollow = true;
        NoteChip();
        NoteFollow(noteLit, true);
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

        if (!OperatingSystem.IsMacOS()) // the Mac build can't tell a terminal yet
            col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Terminal commands (beta)",
            "In a terminal, what you say becomes a shell command: say what you want (\"show the ten biggest files here\") or speak a command (\"git commit dash m quote fix quote\"). " +
            "It is only pasted, never run, so you read it and press Enter yourself. Risky ones start with # CHECK:. Needs AI cleanup.",
            Ui.Switch(S.ShellCommands, v => { S.ShellCommands = v; S.Save(); }))), 0, 0, 0, 18));

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
        SaveWhenIdle(input, v => { S.CustomInstructions = v; S.Save(); });
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
        var active = ModelManager.Active;
        var modelText = active != null ? active.Label + " downloaded" : downloadPct != null ? $"Downloading {downloadPct}%" : downloadError != null ? "Failed: " + downloadError : "Not downloaded";
        var setup = new List<Control>();
        // The shortcut, pasting and microphone rows, which name this platform's own permissions.
        PlatformSetupRows(setup);
        setup.Add(Status("Speech-to-text key", S.CloudSttReady, "Added", "Add a Groq key below (free)"));
        setup.Add(Status("Offline backup model", active != null || downloadPct != null, modelText, modelText,
            active != null || downloadPct != null ? null : Ui.Button("Download", () => StartDownload(ModelManager.Of(S.LocalModel)))));
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
                Ui.Switch(S.Sounds, v => { S.Sounds = v; S.Save(); if (v) Sounds.Play(Sounds.Kind.Done); Render(); })),
            Ui.SettingRow("Detect language automatically", "Off keeps it English-only, which is most accurate for English. The offline backup is English-only either way.",
                Ui.Switch(S.AutoLanguage, v => { S.AutoLanguage = v; S.Save(); })),
            Ui.SettingRow("Voice notes (beta)", "A Notes page with the voice notes from your other devices (when sync is set up), and recordings you add here, like a WhatsApp voice message, transcribed into notes you can summarize.",
                Ui.Switch(S.NotesBeta, v => { S.NotesBeta = v; S.Save(); Render(); }))));
        if (S.Sounds)
        {
            col.Children.Add(Spaced(Ui.Text("Sound style (click one to hear it)", 14, C.Sub), 4, 16, 0, 8));
            col.Children.Add(Ui.List(Tones.Sets.Select(t => (Control)Ui.Choice(t.Label, t.Note, Tones.Valid(S.SoundSet) == t.Id, () =>
            {
                Sounds.Play(Sounds.Kind.Start, t.Id);
                if (S.SoundSet == t.Id) return;
                S.SoundSet = t.Id; S.Save(); Render();
            })).ToArray()));
        }

        // --- Speed
        Section(col, "Speed");
        var speedRows = new List<Control>
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

        // --- Offline model
        Section(col, "Offline model");
        col.Children.Add(Ui.List(ModelManager.All.Select(m =>
        {
            var state = modelDownloading == m ? "Downloading…"
                : ModelManager.IsDownloaded(m) ? "Downloaded"
                : modelDownloading == null && downloadError != null && S.LocalModel == m.Id ? "Download failed: " + downloadError
                : "Click to download";
            return (Control)Ui.Choice(m.Label, m.Note + "\n" + state, S.LocalModel == m.Id, () =>
            {
                S.LocalModel = m.Id; S.Save();
                if (!ModelManager.IsDownloaded(m)) StartDownload(m); else Render();
            });
        }).ToArray()));
        modelProgress = Ui.Text(modelDownloading != null ? $"Downloading {modelDownloading.Label}: {downloadPct ?? 0}%" : "", 13, C.Sub);
        if (modelDownloading != null) col.Children.Add(Spaced(modelProgress, 4, 8, 0, 0));
        var others = ModelManager.All.Where(m => m.Id != S.LocalModel && m != modelDownloading && ModelManager.IsDownloaded(m)).ToArray();
        if (others.Length > 0)
            col.Children.Add(Spaced(Ui.Button($"Remove the others ({others.Sum(m => m.Size) / 1_000_000} MB)", () =>
            {
                foreach (var m in others) ModelManager.Delete(m);
                Render();
            }), 0, 10, 0, 0));
        col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Prefer offline in weak signal",
            "Skips the cloud when there's no connection, or the speech service was slow to answer while you talked, and gives the cloud only a few seconds before switching to the offline model. Needs an offline model.",
            Ui.Switch(S.WeakSignal, v => { S.WeakSignal = v; S.Save(); }))), 0, 12, 0, 0));

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
            SaveWhenIdle(input, v => { S.SetKey(id, v); S.Save(); });
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

        // --- AI assistants (optional)
        Section(col, "AI assistants");
        var mcpInfo = Ui.Button("", () => _ = Ui.Dialog(this, NotesMcp.Help, "Got it", cancel: null, title: "Connect an AI assistant"), icon: "info");
        mcpInfo.Margin = new Thickness(0, 0, 8, 0);
        ToolTip.SetTip(mcpInfo, "How to connect an AI assistant");
        Avalonia.Automation.AutomationProperties.SetName(mcpInfo, "How to connect an AI assistant");
        col.Children.Add(Ui.List(
            Ui.SettingRow("Let AI assistants read my notes", "Off by default. Claude Desktop, Claude Code and other MCP apps on this computer can search and read your voice notes. Read-only.",
                Ui.Row(mcpInfo, Ui.Switch(S.NotesMcp, v => { S.NotesMcp = v; S.Save(); Render(); })))));
        if (S.NotesMcp)
            col.Children.Add(Spaced(Ui.Row(Ui.Button("Copy setup", () => { _ = TextInjector.Copy(NotesMcp.Config(NotesMcp.Exe)); Toast("Copied"); }, icon: "copy")), 0, 12, 0, 0));

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

        // Words on every device that shares the sync file, from each device's own entry in it.
        if (Sync.AllDevices() is { } all)
        {
            var box = new StackPanel { Margin = new Thickness(20, 15, 20, 15) };
            box.Children.Add(Spread(Ui.Text("All your devices", 15.5, bold: true), Ui.Text($"{Ui.Compact(all.Total)} words", 15.5, bold: true)));
            box.Children.Add(Ui.Text($"{Ui.Compact(all.Month)} words this month", 13.5, C.Sub));
            foreach (var d in all.Each)
                box.Children.Add(Ui.Text($"{(d.Name.Length > 0 ? d.Name : "Unnamed device")}: {Ui.Compact(d.Words)} words · {d.Dictations} dictations", 13.5, C.Sub));
            col.Children.Add(Spaced(Ui.List((Control)box), 0, 12, 0, 0));
            col.Children.Add(Spaced(Ui.Text("Each device adds its own counts to the sync file when it syncs.", 13, C.Sub), 4, 8, 0, 0));
        }

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
        "Tokalot keeps your dictionary, snippets, styles, instructions, voice notes, word stats and settings in one small file. Put that file in a folder you already sync, " +
        "such as Nextcloud, Google Drive, Dropbox or Syncthing, and point each of your devices at it. Each device reads the file and adds its own changes.\n\n" +
        "It's private. There is no Tokalot account and no Tokalot server. The file only goes where your own sync service takes it. " +
        "Recordings are never put in it, and your dictation history only if you turn on Include dictation history (the newest 1,000 dictations, as text). " +
        "A few settings stay on each device because they depend on it: the on-device model, how long recordings are kept, where the indicator sits " +
        "and starting with the computer.\n\n" +
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
                Ui.Switch(S.SyncKeys, v => { S.SyncKeys = v; S.Save(); })),
            Ui.SettingRow("Include dictation history", "Off by default. Shares the text of your newest 1,000 dictations, so every device has the same history and stats. Recordings stay on each device.",
                Ui.Switch(S.SyncHistory, v => { S.SyncHistory = v; Sync.ForgetHistoryBasis(); S.Save(); }))));
        var now = Ui.Button("Sync now", async () => { await Sync.Run(); Render(); }, filled: true);
        var stop = Ui.Button("Stop syncing", async () =>
        {
            if (!await Ui.Dialog(this, "Stop syncing on this computer? The sync file and your settings here stay as they are.", "Stop syncing")) return;
            S.SyncFile = "";
            S.SyncKeys = false;
            S.SyncHistory = false;
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
            Look = id, Dock = "bottom", Scale = 0.85, CurrentMode = IndicatorView.Mode.Listening, Frozen = PreviewsStill,
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
        previews.Add(view);
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
        SaveWhenIdle(input, save); // empty = the provider's default model
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

    private void StartDownload(LocalModel m)
    {
        if (modelDownloading != null) { Toast($"Wait for the {modelDownloading.Label} download to finish"); Render(); return; }
        modelDownloading = m;
        downloadPct = 0;
        downloadError = null;
        Render();
        var progress = new Progress<int>(p =>
        {
            downloadPct = p;
            if (modelStatus != null) modelStatus.Text = $"Downloading {p}%";
            if (modelProgress != null) modelProgress.Text = $"Downloading {m.Label}: {p}%";
        });
        Task.Run(() => ModelManager.Download(m, progress)).ContinueWith(t => Dispatcher.UIThread.Post(() =>
        {
            modelDownloading = null;
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
