using System;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.Templates;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using Avalonia.Platform;
using Avalonia.Styling;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/** Palette (light/dark, same as the Android and Windows apps) and small builders for a consistent look. */
public static class C
{
    public static bool Dark { get; private set; }
    public static IBrush Bg = null!, Card = null!, Text = null!, Sub = null!, Line = null!, Pill = null!,
        NavActive = null!, Field = null!, Good = null!, Warn = null!, Link = null!, Hover = null!;
    public static readonly IBrush Amber = Freeze(Color.FromRgb(0xF2, 0xA9, 0x3B));

    public static void Apply(string mode)
    {
        Dark = mode switch { "dark" => true, "light" => false, _ => SystemIsDark() };
        if (Dark)
        {
            Bg = Hex("#0E0E10"); Card = Hex("#1C1C1E"); Text = Hex("#F2F2F7"); Sub = Hex("#98989F"); Line = Hex("#2C2C2E");
            Pill = Hex("#3A3A3C"); NavActive = Hex("#2C2C2E"); Field = Hex("#2C2C2E"); Good = Hex("#66BB6A");
            Warn = Hex("#FFB74D"); Link = Hex("#6EA8FE"); Hover = Hex("#26FFFFFF");
        }
        else
        {
            Bg = Hex("#EFEFF1"); Card = Hex("#FFFFFF"); Text = Hex("#1C1C1E"); Sub = Hex("#8E8E93"); Line = Hex("#E4E4E7");
            Pill = Hex("#CDCDD2"); NavActive = Hex("#DEDEE2"); Field = Hex("#F4F4F6"); Good = Hex("#2E7D32");
            Warn = Hex("#B26A00"); Link = Hex("#2F6FDB"); Hover = Hex("#14000000");
        }
    }

    private static volatile bool desktopSaysLight;

    /**
     * "Match system": dark unless the desktop explicitly asks for light. Avalonia's own answer is
     * taken when it says dark (its "light" is also what it says when it doesn't know); otherwise
     * the answer comes from ProbeSystem, which is run in the background. Anything unknown is dark.
     */
    public static bool SystemIsDark()
    {
        try
        {
            if (Application.Current?.PlatformSettings?.GetColorValues().ThemeVariant == PlatformThemeVariant.Dark) return true;
        }
        catch { }
        return !desktopSaysLight;
    }

    /**
     * Asks the desktop for its light/dark preference: the freedesktop portal's color-scheme
     * (1 = dark, 2 = light, 0 = no preference), then GNOME's own setting. Starts helper programs, so
     * it must not run on the window's thread. Returns true if the answer changed.
     */
    public static bool ProbeSystem()
    {
        var was = desktopSaysLight;
        desktopSaysLight = AskDesktopForLight();
        return was != desktopSaysLight;
    }

    private static bool AskDesktopForLight()
    {
        if (!OperatingSystem.IsLinux()) return false;
        try
        {
            if (Sh.Which("gdbus") is { } gdbus)
            {
                var r = Sh.Run(gdbus, new[]
                {
                    "call", "--session", "--timeout", "1", "--dest", "org.freedesktop.portal.Desktop", "--object-path", "/org/freedesktop/portal/desktop",
                    "--method", "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", "color-scheme",
                }, timeoutMs: 1500);
                // Answer looks like: (<<uint32 2>>,)
                var m = System.Text.RegularExpressions.Regex.Match(r.Out, @"uint32 (\d+)");
                if (r.Exit == 0 && m.Success) return m.Groups[1].Value == "2";
            }
            if (Sh.Which("gsettings") is { } gsettings)
            {
                var r = Sh.Run(gsettings, new[] { "get", "org.gnome.desktop.interface", "color-scheme" }, timeoutMs: 1500);
                if (r.Exit == 0 && r.Out.Contains("prefer-light")) return true;
            }
        }
        catch { }
        return false;
    }

    public static ISolidColorBrush Hex(string hex) => Freeze(Color.Parse(hex));
    public static ISolidColorBrush Argb(uint argb) =>
        Freeze(Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb));
    private static ISolidColorBrush Freeze(Color c) => new ImmutableSolidColorBrush(c);
    public static Color ColorOf(IBrush b) => ((ISolidColorBrush)b).Color;

    public static readonly FontFamily Serif = new("avares://Tokalot/Assets#EB Garamond");
    /**
     * Inter, bundled (the Avalonia.Fonts.Inter package). System fonts were the plan, but most current
     * desktops ship theirs as variable fonts (Ubuntu, Cantarell, Adwaita Sans), which Avalonia 11 draws
     * at one weight only, so nothing came out bold. The names after it are fallbacks.
     */
    public static readonly FontFamily Sans = new("avares://Avalonia.Fonts.Inter/Assets#Inter, Noto Sans, DejaVu Sans");
}

/** The two mouse cursors the app uses (WPF's Cursors.Hand / Cursors.SizeAll). */
public static class Cursors
{
    public static readonly Cursor Hand = new(StandardCursorType.Hand);
    public static readonly Cursor SizeAll = new(StandardCursorType.SizeAll);
}

/** Mouse events in the shape the Windows code uses them: enter, leave, and "left button released over this". */
public static class Mouse
{
    public static void OnEnter(this Control c, Action a) => c.PointerEntered += (_, _) => a();
    public static void OnLeave(this Control c, Action a) => c.PointerExited += (_, _) => a();

    /** handled: stop the click from also reaching the card or row this control sits in. */
    public static void OnClick(this Control c, Action a, bool handled = false)
    {
        c.PointerReleased += (_, e) =>
        {
            if (e.InitialPressMouseButton != MouseButton.Left) return;
            // The pressed control keeps receiving the pointer; only count releases that are still over it.
            if (!new Rect(c.Bounds.Size).Contains(e.GetPosition(c))) return;
            if (handled) e.Handled = true;
            a();
        };
    }
}

public static class Ui
{
    /**
     * Pill shape with straight sides (like Android's rounded(…, 100)): the corner radius follows the
     * actual height.
     */
    public static T Stadium<T>(T b) where T : Border
    {
        b.SizeChanged += (_, e) => b.CornerRadius = new CornerRadius(e.NewSize.Height / 2);
        return b;
    }

    /**
     * Makes a hand-built control reachable with Tab and usable with Enter or Space, with a focus ring
     * (shown only for keyboard focus) and a name for screen readers.
     */
    public static T Keys<T>(T e, Action act, string? name = null) where T : Control
    {
        e.Focusable = true;
        e.FocusAdorner = new FuncTemplate<Control>(() => new Border
        {
            BorderBrush = C.Link, BorderThickness = new Thickness(2), CornerRadius = new CornerRadius(12), Margin = new Thickness(-3),
        });
        if (!string.IsNullOrEmpty(name)) AutomationProperties.SetName(e, name);
        e.KeyDown += (_, k) => { if (k.Key is Key.Enter or Key.Space) { k.Handled = true; act(); } };
        return e;
    }

    /**
     * A small modal in the app's own style. Returns true for the main button. cancel: null shows a
     * single button. (Windows' version blocks until it closes; here it is awaited.)
     */
    public static async Task<bool> Dialog(Window? owner, string message, string ok = "OK", string? cancel = "Cancel", string? title = null)
    {
        var result = false;
        var w = new Window
        {
            SystemDecorations = SystemDecorations.None, TransparencyLevelHint = new[] { WindowTransparencyLevel.Transparent },
            Background = Brushes.Transparent, ShowInTaskbar = false, FontFamily = C.Sans,
            SizeToContent = SizeToContent.WidthAndHeight, CanResize = false, Title = "Tokalot",
            WindowStartupLocation = WindowStartupLocation.CenterScreen,
        };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 20, 0, 0) };
        if (cancel != null)
        {
            var c = Button(cancel, () => w.Close());
            c.Margin = new Thickness(0, 0, 8, 0);
            buttons.Children.Add(c);
        }
        var main = Button(ok, () => { result = true; w.Close(); }, filled: true);
        buttons.Children.Add(main);
        var body = new StackPanel();
        if (title != null)
        {
            var h = Heading(title, 26);
            h.Margin = new Thickness(0, 0, 0, 8);
            body.Children.Add(h);
        }
        body.Children.Add(Text(message, 15));
        body.Children.Add(buttons);
        w.Content = new Border
        {
            Background = C.Card, BorderBrush = C.Pill, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(22),
            Padding = new Thickness(24, 22, 24, 20), Margin = new Thickness(18), MinWidth = 300, MaxWidth = 440, Child = body,
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 18, OffsetY = 3, Color = Color.FromArgb(0x59, 0, 0, 0) }),
        };
        w.KeyDown += (_, e) => { if (e.Key == Key.Escape) w.Close(); };
        w.Opened += (_, _) => main.Focus();
        var closed = new TaskCompletionSource<bool>();
        w.Closed += (_, _) => closed.TrySetResult(result);
        if (owner is { IsVisible: true })
        {
            w.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            _ = w.ShowDialog(owner);
        }
        else w.Show();
        return await closed.Task;
    }

    public static TextBlock Text(string s, double size = 15, IBrush? color = null, bool bold = false) => new()
    {
        Text = s, FontSize = size, Foreground = color ?? C.Text, FontFamily = C.Sans,
        FontWeight = bold ? FontWeight.SemiBold : FontWeight.Normal, TextWrapping = TextWrapping.Wrap,
        LineHeight = size * 1.4,
    };

    public static TextBlock Heading(string s, double size = 40) => new()
    {
        Text = s, FontSize = size, Foreground = C.Text, FontFamily = C.Serif, TextWrapping = TextWrapping.Wrap,
    };

    public static TextBlock Label(string s) => new()
    {
        Text = s.ToUpperInvariant(), FontSize = 11.5, Foreground = C.Sub, FontFamily = C.Sans,
        FontWeight = FontWeight.SemiBold, Margin = new Thickness(4, 26, 0, 8),
    };

    public static Border Card(Control? child = null, double pad = 0) => new()
    {
        Background = C.Card, CornerRadius = new CornerRadius(28), Padding = new Thickness(pad), Child = child,
        // Rows inside a card (hover highlights, dividers) must not poke out past its rounded corners.
        ClipToBounds = true,
    };

    public static StackPanel Stack(params Control[] children)
    {
        var p = new StackPanel();
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static StackPanel Row(params Control[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static Border Divider() => new() { Height = 1, Background = C.Line };

    /** Rounded pill button (outlined, or filled for primary actions). */
    public static Border Button(string label, Action onClick, bool filled = false, string? icon = null)
    {
        var fg = filled ? C.Card : C.Text;
        var content = new StackPanel { Orientation = Orientation.Horizontal };
        if (icon != null)
        {
            var ic = Icons.Get(icon, 18, fg);
            ic.Margin = new Thickness(0, 0, label.Length > 0 ? 8 : 0, 0);
            content.Children.Add(ic);
        }
        if (label.Length > 0)
            content.Children.Add(new TextBlock { Text = label, FontSize = 14.5, Foreground = fg, FontFamily = C.Sans, VerticalAlignment = VerticalAlignment.Center });
        var b = Stadium(new Border
        {
            Background = filled ? C.Text : C.Card,
            BorderBrush = filled ? C.Text : C.Pill,
            BorderThickness = new Thickness(1),
            Padding = label.Length > 0 ? new Thickness(18, 9, 18, 9) : new Thickness(11, 9, 11, 9),
            MinWidth = label.Length > 0 ? 0 : 38,
            Child = content,
            Cursor = Cursors.Hand,
            HorizontalAlignment = HorizontalAlignment.Left,
        });
        content.HorizontalAlignment = HorizontalAlignment.Center;
        b.OnEnter(() => b.Opacity = 0.82);
        b.OnLeave(() => b.Opacity = 1);
        b.OnClick(onClick, handled: true);
        return Keys(b, onClick, label.Length > 0 ? label : icon);
    }

    /** Text field inside a rounded box. */
    public static (Border Box, TextBox Input) Field(string value = "", string hint = "", bool multiLine = false)
    {
        var tb = new TextBox
        {
            Text = value, FontSize = 15, FontFamily = C.Sans, Foreground = C.Text, Background = Brushes.Transparent,
            BorderThickness = new Thickness(0), CaretBrush = C.Text, AcceptsReturn = multiLine,
            TextWrapping = multiLine ? TextWrapping.Wrap : TextWrapping.NoWrap, MinHeight = multiLine ? 64 : 0,
            VerticalContentAlignment = multiLine ? VerticalAlignment.Top : VerticalAlignment.Center,
            Padding = new Thickness(2, 0, 2, 0), MinWidth = 0,
        };
        return (Wrap(tb, hint), tb);
    }

    /** A field that shows dots instead of its text (API keys). */
    public static (Border Box, TextBox Input) Secret(string value, string hint)
    {
        var pb = new TextBox
        {
            Text = value, PasswordChar = '●', FontSize = 15, FontFamily = C.Sans, Foreground = C.Text, Background = Brushes.Transparent,
            BorderThickness = new Thickness(0), CaretBrush = C.Text, Padding = new Thickness(2, 0, 2, 0), MinHeight = 0, MinWidth = 0,
            VerticalContentAlignment = VerticalAlignment.Center,
        };
        return (Wrap(pb, hint), pb);
    }

    private static Border Wrap(TextBox input, string hint)
    {
        var placeholder = new TextBlock
        {
            Text = hint, Foreground = C.Sub, FontSize = 15, FontFamily = C.Sans, IsHitTestVisible = false,
            Margin = new Thickness(2, 0, 0, 0), VerticalAlignment = VerticalAlignment.Top,
        };
        void Sync() => placeholder.IsVisible = string.IsNullOrEmpty(input.Text);
        input.TextChanged += (_, _) => Sync();
        Sync();
        var grid = new Grid();
        grid.Children.Add(input);
        grid.Children.Add(placeholder);
        return new Border { Background = C.Field, CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 11, 14, 11), Child = grid };
    }

    /** iOS-style on/off switch. */
    public static Border Switch(bool on, Action<bool> changed)
    {
        var knob = new Border { Width = 18, Height = 18, CornerRadius = new CornerRadius(9), Background = Brushes.White };
        var track = new Border { Width = 40, Height = 24, CornerRadius = new CornerRadius(12), Padding = new Thickness(3), Child = knob, Cursor = Cursors.Hand };
        void Paint()
        {
            track.Background = on ? C.Text : C.Pill;
            knob.Background = on ? C.Card : Brushes.White;
            knob.HorizontalAlignment = on ? HorizontalAlignment.Right : HorizontalAlignment.Left;
        }
        Paint();
        void Toggle() { on = !on; Paint(); changed(on); }
        track.OnClick(Toggle, handled: true);
        return Keys(track, Toggle);
    }

    /** Title + subtitle on the left, a control on the right. */
    public static Grid SettingRow(string title, string sub, Control right)
    {
        var g = new Grid { Margin = new Thickness(20, 14, 18, 14) };
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var texts = Stack(Text(title, 15));
        if (sub.Length > 0) texts.Children.Add(Text(sub, 13, C.Sub));
        g.Children.Add(texts);
        right.VerticalAlignment = VerticalAlignment.Center;
        right.Margin = new Thickness(16, 0, 0, 0);
        if (string.IsNullOrEmpty(AutomationProperties.GetName(right))) AutomationProperties.SetName(right, title);
        Grid.SetColumn(right, 1);
        g.Children.Add(right);
        return g;
    }

    /** Round selection dot + title/subtitle, for picking one option. */
    public static Border Choice(string title, string sub, bool selected, Action onPick)
    {
        var dot = new Border
        {
            Width = 20, Height = 20, CornerRadius = new CornerRadius(10),
            Background = selected ? C.Text : C.Card, BorderBrush = C.Pill, BorderThickness = new Thickness(selected ? 0 : 2),
            VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 2, 14, 0),
        };
        var texts = Stack(Text(title, 15, bold: selected));
        if (sub.Length > 0) texts.Children.Add(Text(sub, 13, C.Sub));
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.Children.Add(dot);
        Grid.SetColumn(texts, 1);
        g.Children.Add(texts);
        var b = new Border { Padding = new Thickness(20, 14, 20, 14), Child = g, Background = Brushes.Transparent, Cursor = Cursors.Hand };
        b.OnEnter(() => b.Background = C.Hover);
        b.OnLeave(() => b.Background = Brushes.Transparent);
        b.OnClick(onPick, handled: true);
        return Keys(b, onPick, title);
    }

    /** A card whose children are separated by thin lines. */
    public static Border List(params Control[] rows)
    {
        var s = new StackPanel();
        for (int i = 0; i < rows.Length; i++)
        {
            if (i > 0) s.Children.Add(Divider());
            s.Children.Add(rows[i]);
        }
        return Card(s);
    }

    /** Slim rounded scroll bar in the theme's colors, with no arrows or track (the stock one is wider and has arrows). */
    public static ControlTheme ScrollBarStyle()
    {
        var thumbBrush = C.Pill;
        var theme = new ControlTheme(typeof(ScrollBar));
        theme.Setters.Add(new Setter(TemplatedControl.BackgroundProperty, Brushes.Transparent));
        theme.Setters.Add(new Setter(TemplatedControl.TemplateProperty, new FuncControlTemplate<ScrollBar>((sb, ns) =>
        {
            var vertical = sb.Orientation == Orientation.Vertical;
            var thumb = new Thumb
            {
                MinHeight = vertical ? 28 : 0, MinWidth = vertical ? 0 : 28,
                Template = new FuncControlTemplate<Thumb>((_, _) => new Border { Background = thumbBrush, CornerRadius = new CornerRadius(3), Margin = new Thickness(2) }),
            };
            var track = new Track
            {
                Name = "PART_Track", IsDirectionReversed = vertical, Thumb = thumb,
                [!Track.MinimumProperty] = sb[!RangeBase.MinimumProperty],
                [!Track.MaximumProperty] = sb[!RangeBase.MaximumProperty],
                [!!Track.ValueProperty] = sb[!!RangeBase.ValueProperty],
                [!Track.ViewportSizeProperty] = sb[!ScrollBar.ViewportSizeProperty],
                [!Track.OrientationProperty] = sb[!ScrollBar.OrientationProperty],
            };
            ns.Register("PART_Track", track);
            return new Border { Background = Brushes.Transparent, Child = track };
        })));
        theme.Add(new Style(x => x.Nesting().PropertyEquals(ScrollBar.OrientationProperty, Orientation.Vertical))
        {
            Setters = { new Setter(Layoutable.WidthProperty, 10.0), new Setter(Layoutable.MinWidthProperty, 10.0) },
        });
        theme.Add(new Style(x => x.Nesting().PropertyEquals(ScrollBar.OrientationProperty, Orientation.Horizontal))
        {
            Setters = { new Setter(Layoutable.HeightProperty, 10.0), new Setter(Layoutable.MinHeightProperty, 10.0) },
        });
        return theme;
    }

    /**
     * App-wide looks, on top of Avalonia's Fluent base templates: pop-up menus and tooltips as rounded
     * cards in the theme's colors, text boxes with no frame of their own (Field draws the rounded box), and
     * the slim scroll bar. Goes in the app's resources; rebuilt when the theme changes.
     */
    public static ResourceDictionary MenuStyles()
    {
        var r = new ResourceDictionary();
        // Pop-up menus
        r["MenuFlyoutPresenterBackground"] = C.Card;
        r["MenuFlyoutPresenterBorderBrush"] = C.Pill;
        r["MenuFlyoutPresenterBorderThemeThickness"] = new Thickness(1);
        r["MenuFlyoutPresenterThemePadding"] = new Thickness(5);
        r["OverlayCornerRadius"] = new CornerRadius(12);
        r["ControlCornerRadius"] = new CornerRadius(8);
        r["MenuFlyoutItemForeground"] = C.Text;
        r["MenuFlyoutItemForegroundPointerOver"] = C.Text;
        r["MenuFlyoutItemForegroundPressed"] = C.Text;
        r["MenuFlyoutItemBackgroundPointerOver"] = C.Hover;
        r["MenuFlyoutItemBackgroundPressed"] = C.Hover;
        r["MenuFlyoutItemThemePadding"] = new Thickness(12, 7, 14, 7);
        // Tooltips
        r["ToolTipBackground"] = C.Card;
        r["ToolTipBorderBrush"] = C.Pill;
        r["ToolTipForeground"] = C.Text;
        r["ToolTipBorderThemeThickness"] = new Thickness(1);
        r["ToolTipBorderThemePadding"] = new Thickness(9, 5, 9, 6);
        r["ToolTipContentThemeFontSize"] = 12.5;
        // Text boxes: the same in every state
        foreach (var state in new[] { "", "PointerOver", "Focused", "Disabled" })
        {
            r["TextControlBackground" + state] = Brushes.Transparent;
            r["TextControlBorderBrush" + state] = Brushes.Transparent;
            r["TextControlForeground" + state] = C.Text;
        }
        r["TextControlBorderThemeThickness"] = new Thickness(0);
        r["TextControlBorderThemeThicknessFocused"] = new Thickness(0);
        r["TextControlSelectionHighlightColor"] = C.Link;
        r[typeof(ScrollBar)] = ScrollBarStyle();
        return r;
    }

    /** A pop-up menu in the app's font. */
    public static ContextMenu Menu() => new() { FontFamily = C.Sans, FontSize = 14 };

    public static string Money(double v) => v == 0 ? "$0" : v < 0.01 ? "<$0.01" : $"${v:0.00}";

    public static string Compact(long n) => n >= 1_000_000 ? $"{n / 1e6:0.0}M" : n >= 10_000 ? $"{n / 1000}K" : n >= 1000 ? $"{n / 1e3:0.0}K" : n.ToString();
}
