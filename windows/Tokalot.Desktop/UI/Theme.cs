using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;

namespace Tokalot.Desktop.UI;

/** Palette (light/dark, same as the Android app) and small builders for a consistent look. */
public static class C
{
    public static bool Dark { get; private set; }
    public static Brush Bg = null!, Card = null!, Text = null!, Sub = null!, Line = null!, Pill = null!,
        NavActive = null!, Field = null!, Good = null!, Warn = null!, Link = null!, Hover = null!;
    public static readonly Brush Amber = Freeze(Color.FromRgb(0xF2, 0xA9, 0x3B));

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

    private static bool SystemIsDark()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
        catch { return false; }
    }

    public static SolidColorBrush Hex(string hex) => Freeze((Color)ColorConverter.ConvertFromString(hex));
    public static SolidColorBrush Argb(uint argb) =>
        Freeze(Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb));
    private static SolidColorBrush Freeze(Color c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }
    private static SolidColorBrush Freeze(SolidColorBrush b) { b.Freeze(); return b; }

    public static readonly FontFamily Serif = new(new Uri("pack://application:,,,/"), "./Assets/#EB Garamond");
    public static readonly FontFamily Sans = new("Segoe UI Variable Text, Segoe UI");
}

public static class Ui
{
    /**
     * Pill shape with straight sides (like Android's rounded(…, 100)). WPF turns an oversized
     * CornerRadius into an ellipse, so the radius follows the actual height instead.
     */
    public static T Stadium<T>(T b) where T : Border
    {
        b.SizeChanged += (_, e) => b.CornerRadius = new CornerRadius(e.NewSize.Height / 2);
        return b;
    }

    public static TextBlock Text(string s, double size = 15, Brush? color = null, bool bold = false) => new()
    {
        Text = s, FontSize = size, Foreground = color ?? C.Text, FontFamily = C.Sans,
        FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal, TextWrapping = TextWrapping.Wrap,
        LineHeight = size * 1.4,
    };

    public static TextBlock Heading(string s, double size = 40) => new()
    {
        Text = s, FontSize = size, Foreground = C.Text, FontFamily = C.Serif, TextWrapping = TextWrapping.Wrap,
    };

    public static TextBlock Label(string s) => new()
    {
        Text = s.ToUpperInvariant(), FontSize = 11.5, Foreground = C.Sub, FontFamily = C.Sans,
        FontWeight = FontWeights.SemiBold, Margin = new Thickness(4, 26, 0, 8),
    };

    public static Border Card(UIElement? child = null, double pad = 0) => new()
    {
        Background = C.Card, CornerRadius = new CornerRadius(28), Padding = new Thickness(pad), Child = child,
    };

    public static StackPanel Stack(params UIElement[] children)
    {
        var p = new StackPanel();
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static StackPanel Row(params UIElement[] children)
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
        var normal = b.Background;
        b.MouseEnter += (_, _) => b.Opacity = 0.82;
        b.MouseLeave += (_, _) => b.Opacity = 1;
        b.MouseLeftButtonUp += (_, e) => { e.Handled = true; onClick(); };
        return b;
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
        };
        return (Wrap(tb, hint), tb);
    }

    public static (Border Box, PasswordBox Input) Secret(string value, string hint)
    {
        var pb = new PasswordBox
        {
            Password = value, FontSize = 15, Foreground = C.Text, Background = Brushes.Transparent,
            BorderThickness = new Thickness(0), CaretBrush = C.Text,
        };
        return (Wrap(pb, hint), pb);
    }

    private static Border Wrap(Control input, string hint)
    {
        var placeholder = new TextBlock
        {
            Text = hint, Foreground = C.Sub, FontSize = 15, FontFamily = C.Sans, IsHitTestVisible = false,
            Margin = new Thickness(2, 0, 0, 0), VerticalAlignment = VerticalAlignment.Top,
        };
        void Sync()
        {
            var empty = input is TextBox t ? t.Text.Length == 0 : ((PasswordBox)input).Password.Length == 0;
            placeholder.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        }
        if (input is TextBox tb) tb.TextChanged += (_, _) => Sync(); else ((PasswordBox)input).PasswordChanged += (_, _) => Sync();
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
        track.MouseLeftButtonUp += (_, e) => { e.Handled = true; on = !on; Paint(); changed(on); };
        return track;
    }

    /** Title + subtitle on the left, a control on the right. */
    public static Grid SettingRow(string title, string sub, UIElement right)
    {
        var g = new Grid { Margin = new Thickness(20, 14, 18, 14) };
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var texts = Stack(Text(title, 15));
        if (sub.Length > 0) texts.Children.Add(Text(sub, 13, C.Sub));
        g.Children.Add(texts);
        if (right is FrameworkElement fe) { fe.VerticalAlignment = VerticalAlignment.Center; fe.Margin = new Thickness(16, 0, 0, 0); }
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
        b.MouseEnter += (_, _) => b.Background = C.Hover;
        b.MouseLeave += (_, _) => b.Background = Brushes.Transparent;
        b.MouseLeftButtonUp += (_, e) => { e.Handled = true; onPick(); };
        return b;
    }

    /** A card whose children are separated by thin lines. */
    public static Border List(params UIElement[] rows)
    {
        var s = new StackPanel();
        for (int i = 0; i < rows.Length; i++)
        {
            if (i > 0) s.Children.Add(Divider());
            s.Children.Add(rows[i]);
        }
        return Card(s);
    }

    public static string Money(double v) => v == 0 ? "$0" : v < 0.01 ? "<$0.01" : $"${v:0.00}";

    public static string Compact(long n) => n >= 1_000_000 ? $"{n / 1e6:0.0}M" : n >= 10_000 ? $"{n / 1000}K" : n >= 1000 ? $"{n / 1e3:0.0}K" : n.ToString();
}
