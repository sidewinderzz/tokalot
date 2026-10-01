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
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/** The settings and history window. Closing it leaves Tokalot running in the tray. */
public sealed class MainWindow : Window
{
    public enum Page { Home, Dictionary, Style, Snippets, Settings }

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
    private int historyLimit = 100;
    private readonly HashSet<long> expanded = new(), showOriginal = new();
    private static string styleTab = "MESSAGING";
    private static int? downloadPct;
    private static string? downloadError;
    private Snippet? editing;
    private bool editingNew;
    private readonly Action onHistory, onPlayer;

    private static Settings S => Settings.Current;

    public MainWindow()
    {
        Title = "Tokalot";
        Width = 1040; Height = 760; MinWidth = 760; MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = C.Bg;
        FontFamily = C.Sans;
        try { Icon = BitmapFrame.Create(new Uri("pack://application:,,,/Assets/icon.png")); } catch { }

        (searchBox, search) = Ui.Field("", "Search your dictations");
        search.TextChanged += (_, _) => FillHistory();

        toastText = new TextBlock { Foreground = Brushes.White, FontSize = 13.5, FontFamily = C.Sans };
        toast = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x1C, 0x1C, 0x1E)), CornerRadius = new CornerRadius(100),
            Padding = new Thickness(18, 9, 18, 9), Child = toastText, Opacity = 0, IsHitTestVisible = false,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 28),
        };

        // Sidebar | content
        var root = new Grid();
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(232) });
        root.ColumnDefinitions.Add(new ColumnDefinition());
        root.Children.Add(Sidebar());
        var contentGrid = new Grid();
        contentGrid.Children.Add(scroller);
        contentGrid.Children.Add(toast);
        Grid.SetColumn(contentGrid, 1);
        root.Children.Add(contentGrid);
        Content = root;

        onHistory = () => Dispatcher.BeginInvoke(() => { if (CurrentPage == Page.Home) FillHistory(); });
        onPlayer = () => Dispatcher.BeginInvoke(() => { if (CurrentPage == Page.Home) FillHistory(); });
        History.Changed += onHistory;
        Player.Changed += onPlayer;
        Closed += (_, _) => { History.Changed -= onHistory; Player.Changed -= onPlayer; Player.Stop(false); };
        SourceInitialized += (_, _) => DarkTitleBar();
        Render();
    }

    // ---------- frame ----------

    private UIElement Sidebar()
    {
        var logo = new Bars { Width = 34, Height = 34, BarBrush = C.Text, AccentBrush = C.Argb(S.Accent) };
        var title = new TextBlock { Text = "Tokalot", FontFamily = C.Serif, FontSize = 30, Foreground = C.Text, Margin = new Thickness(8, 0, 0, 2), VerticalAlignment = VerticalAlignment.Center };
        var head = Ui.Row(logo, title);
        head.Margin = new Thickness(22, 26, 0, 4);

        var hint = Ui.Card(Ui.Stack(
            Ui.Text("Hold to talk", 13, C.Sub),
            Ui.Row(KeyCap("Ctrl"), Ui.Text(" + ", 14, C.Sub), KeyCap("Win")),
            Ui.Text("Tap once for hands-free. Esc cancels.", 12.5, C.Sub)), 16);
        ((StackPanel)hint.Child).Children[1].SetValue(MarginProperty, new Thickness(0, 8, 0, 8));
        hint.Margin = new Thickness(14, 0, 14, 18);
        hint.VerticalAlignment = VerticalAlignment.Bottom;

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
        void Item(Page p, string glyph, string label)
        {
            var active = p == CurrentPage;
            var row = Ui.Row(
                new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"), FontSize = 17, Foreground = C.Text, Width = 26, VerticalAlignment = VerticalAlignment.Center },
                Ui.Text(label, 15, C.Text, bold: active));
            var b = new Border
            {
                Child = row, CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(0, 0, 0, 4),
                Background = active ? C.NavActive : Brushes.Transparent, Cursor = Cursors.Hand,
            };
            b.MouseEnter += (_, _) => { if (!active) b.Background = C.Hover; };
            b.MouseLeave += (_, _) => { if (!active) b.Background = Brushes.Transparent; };
            b.MouseLeftButtonUp += (_, _) => Go(p);
            nav.Children.Add(b);
        }
        Item(Page.Home, "", "Home");
        Item(Page.Dictionary, "", "Dictionary");
        Item(Page.Style, "", "Style");
        Item(Page.Snippets, "", "Snippets");
        nav.Children.Add(new Border { Height = 14 });
        Item(Page.Settings, "", "Settings");
    }

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
        RenderNav();
        var col = new StackPanel { MaxWidth = 760, Margin = new Thickness(36, 28, 36, 48) };
        switch (CurrentPage)
        {
            case Page.Home: BuildHome(col); break;
            case Page.Dictionary: BuildDictionary(col); break;
            case Page.Style: BuildStyle(col); break;
            case Page.Snippets: BuildSnippets(col); break;
            case Page.Settings: BuildSettings(col); break;
        }
        scroller.Content = col;
        scroller.UpdateLayout();
        scroller.ScrollToVerticalOffset(offset);
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
        return t;
    }

    /** A row that stretches its first child and right-aligns the rest. */
    private static Grid Spread(UIElement left, params UIElement[] right)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition());
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

        if (!app.Controller.HotkeyWorks)
        {
            var c = Ui.Card(Ui.Text("Tokalot couldn't listen for Ctrl+Win. Quit it from the tray icon and open it again.", 15, C.Warn), 18);
            col.Children.Add(Spaced(c, 0, 0, 0, 14));
        }

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
        var cells = new[] { Stat(Ui.Compact(m.Words), "words"), Stat(m.Dictations.ToString(CultureInfo.InvariantCulture), "dictations"), Stat(Ui.Money(m.Total), "est. cost") };
        for (int i = 0; i < 3; i++) { Grid.SetColumn(cells[i], i); stats.Children.Add(cells[i]); }
        var statCard = Ui.Stack(Ui.Text(DateTime.Now.ToString("MMMM yyyy", CultureInfo.InvariantCulture).ToUpperInvariant(), 12, C.Sub, bold: true), stats);
        if (m.Fillers + m.Corrections > 0)
            statCard.Children.Add(Spaced(Ui.Text($"Cleaned up {m.Fillers} filler words and {m.Corrections} self-corrections", 13, C.Sub), 0, 10, 0, 0));
        var sc = Ui.Card(statCard, 22);
        sc.Cursor = Cursors.Hand;
        sc.MouseLeftButtonUp += (_, _) => Go(Page.Settings);
        col.Children.Add(Spaced(sc, 0, 0, 0, 16));

        (searchBox.Parent as Panel)?.Children.Remove(searchBox);
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

        var actions = new List<UIElement> { Ui.Button("Copy", () => { _ = TextInjector.Copy(e.Text); Toast("Copied"); }, glyph: "") };
        if (AudioStore.Exists(e.Id))
        {
            var playing = Player.PlayingId == e.Id;
            actions.Add(Ui.Button("", () => { if (playing) Player.Stop(); else Player.Play(e.Id); }, filled: playing, glyph: playing ? "" : ""));
        }
        if (e.Cleaned && e.Raw.Length > 0 && e.Raw != e.Text)
            actions.Add(Ui.Button("Original", () => { if (!showOriginal.Add(e.Id)) showOriginal.Remove(e.Id); FillHistory(); }, filled: showOriginal.Contains(e.Id)));
        if (open) actions.Add(Ui.Button("Less", () => { expanded.Remove(e.Id); FillHistory(); }));
        var row = Ui.Row(actions.ToArray());
        foreach (FrameworkElement a in row.Children) a.Margin = new Thickness(0, 0, 8, 0);

        var more = Ui.Button("", () => { }, glyph: "");
        var menu = new ContextMenu();
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
        more.MouseLeftButtonUp += (_, _) => { menu.PlacementTarget = more; menu.IsOpen = true; };
        box.Children.Add(Spread(row, more));
        return box;
    }

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

        if (S.Words.Count == 0)
        {
            col.Children.Add(Ui.Card(Ui.Text("No words yet.", 16, C.Sub), 22));
            return;
        }
        var rows = S.Words.OrderBy(w => w, StringComparer.OrdinalIgnoreCase).Select(w =>
        {
            var remove = Ui.Button("", () => { S.Words.Remove(w); S.Save(); Render(); }, glyph: "");
            remove.BorderThickness = new Thickness(0);
            remove.Background = Brushes.Transparent;
            return (UIElement)Spaced(Spread(Ui.Text(w, 16), remove), 20, 8, 12, 8);
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
            return (UIElement)item;
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

        var tabs = new WrapPanel();
        foreach (var c in Catalog.Categories)
        {
            var on = c.Id == styleTab;
            var t = new Border
            {
                CornerRadius = new CornerRadius(100), Padding = new Thickness(16, 8, 16, 8), Margin = new Thickness(0, 0, 8, 8),
                Background = on ? C.Text : C.Card, BorderBrush = on ? C.Text : C.Pill, BorderThickness = new Thickness(1), Cursor = Cursors.Hand,
                Child = Ui.Text(c.Label, 14.5, on ? C.Card : C.Text),
            };
            var id = c.Id;
            t.MouseLeftButtonUp += (_, _) => { styleTab = id; Render(); };
            tabs.Children.Add(t);
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
                return (UIElement)row;
            }).ToArray();
            col.Children.Add(Ui.List(rows));
        }

        col.Children.Add(Spaced(Ui.Heading("Your instructions", 28), 0, 28, 0, 4));
        col.Children.Add(Spaced(Ui.Text("Applies everywhere. E.g. \"Use US spelling\", \"Write numbers as digits\", \"Never use exclamation points\".", 14, C.Sub), 0, 0, 0, 12));
        var (box, input) = Ui.Field(S.CustomInstructions, "Optional", multiLine: true);
        input.TextChanged += (_, _) => { S.CustomInstructions = input.Text; S.Save(); };
        col.Children.Add(box);
    }

    // ---------- Settings ----------

    private static void Section(StackPanel col, string title) => col.Children.Add(Ui.Label(title));

    private void BuildSettings(StackPanel col)
    {
        col.Children.Add(Ui.Heading("Settings"));

        // --- Setup
        Section(col, "Setup");
        UIElement Status(string title, bool done, string doneText, string notDone, UIElement? action = null)
        {
            var st = Ui.Text(done ? doneText : notDone, 13.5, done ? C.Good : C.Warn);
            if (title.StartsWith("Offline")) modelStatus = st;
            return Spaced(Spread(Ui.Stack(Ui.Text(title, 15.5), st), action ?? new Border()), 20, 13, 16, 13);
        }
        var hookOk = App.Current.Controller.HotkeyWorks;
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
        col.Children.Add(Spaced(Ui.Text("Recording bars color", 14, C.Sub), 4, 16, 0, 8));
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
            swatches.Children.Add(dot);
        }
        col.Children.Add(swatches);
        col.Children.Add(Spaced(Ui.List(Ui.SettingRow("Compatibility rendering", "Draws this window without the graphics card. Turn on if Tokalot's window looks blank. Restart Tokalot to apply.",
            Ui.Switch(S.SoftwareRendering, v => { S.SoftwareRendering = v; S.Save(); }))), 0, 16, 0, 0));

        // --- Recording
        Section(col, "Recording");
        col.Children.Add(Ui.List(
            Spaced(Ui.Stack(Ui.Text("How to dictate", 15.5),
                Ui.Text("Hold Ctrl+Win while you talk and let go to paste. Or tap Ctrl+Win once for hands-free, then tap again to finish. Esc cancels.", 13.5, C.Sub)), 20, 13, 18, 13),
            Ui.SettingRow("Auto-stop after 30 s of silence", "Hands-free mode only. Long pauses to think are fine.",
                Ui.Switch(S.AutoStop, v => { S.AutoStop = v; S.Save(); })),
            Ui.SettingRow("Sounds", "A soft tone when recording starts, stops, finishes or fails.",
                Ui.Switch(S.Sounds, v => { S.Sounds = v; S.Save(); if (v) Sounds.Play(Sounds.Kind.Done); })),
            Ui.SettingRow("Detect language automatically", "Off keeps it English-only, which is most accurate for English. The offline backup is English-only either way.",
                Ui.Switch(S.AutoLanguage, v => { S.AutoLanguage = v; S.Save(); }))));

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
        col.Children.Add(Spaced(Ui.Text("Keys are stored only on this PC, encrypted with your Windows account. Nothing is synced anywhere.", 13, C.Sub), 4, 8, 0, 0));

        // --- Recordings
        Section(col, "Recordings");
        col.Children.Add(Ui.List(new[] { (0, "Don't save audio"), (7, "Keep 7 days"), (30, "Keep 30 days"), (int.MaxValue, "Keep forever") }
            .Select(t => (UIElement)Ui.Choice(t.Item2, "", S.AudioKeepDays == t.Item1, () =>
            {
                S.AudioKeepDays = t.Item1; S.Save();
                Task.Run(() => AudioStore.Prune(t.Item1)).ContinueWith(_ => Dispatcher.BeginInvoke(Render));
                Render();
            })).ToArray()));
        var mb = AudioStore.TotalBytes() / 1_048_576.0;
        col.Children.Add(Spaced(Spread(Ui.Text($"Using {mb:0.0} MB", 14, C.Sub), Ui.Button("Delete all", () =>
        {
            if (MessageBox.Show(this, "Delete all saved recordings? Transcripts stay.", "Tokalot", MessageBoxButton.OKCancel) != MessageBoxResult.OK) return;
            Player.Stop(false);
            AudioStore.Prune(0);
            Render();
        })), 4, 10, 0, 0));

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

    private static UIElement ModelField(string value, Action<string> save)
    {
        var (box, input) = Ui.Field(value, "Model");
        input.TextChanged += (_, _) => { if (input.Text.Trim().Length > 0) save(input.Text); };
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

    private void DoBackup(bool withAudio, bool withKeys)
    {
        var dlg = new Microsoft.Win32.SaveFileDialog
        {
            FileName = $"tokalot-desktop-backup-{DateTime.Now:yyyy-MM-dd}.zip", Filter = "Zip file|*.zip", DefaultExt = ".zip",
        };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            var s = Backup.Write(dlg.FileName, withAudio, withKeys);
            Toast($"Backed up {s.Entries} dictations" + (s.Recordings > 0 ? $" and {s.Recordings} recordings" : ""));
        }
        catch (Exception e)
        {
            MessageBox.Show(this, "Backup failed: " + e.Message, "Tokalot");
        }
    }

    private void DoRestore()
    {
        if (MessageBox.Show(this,
                "This replaces your current settings, dictionary, snippets and history with the backup's. API keys on this PC are kept unless the backup includes keys.",
                "Restore from backup?", MessageBoxButton.OKCancel) != MessageBoxResult.OK) return;
        var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "Tokalot backup|*.zip" };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            Player.Stop(false);
            var s = Backup.Restore(dlg.FileName);
            App.Current.ApplyTheme();
            Render();
            Toast($"Restored {s.Entries} dictations");
        }
        catch (Exception e)
        {
            MessageBox.Show(this, "Restore failed: " + e.Message, "Tokalot");
        }
    }

    // ---------- dark title bar ----------

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private void DarkTitleBar()
    {
        try
        {
            int on = C.Dark ? 1 : 0;
            DwmSetWindowAttribute(new WindowInteropHelper(this).Handle, 20, ref on, sizeof(int));
        }
        catch { }
    }
}
