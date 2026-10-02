using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Tokalot.Desktop.UI;

/**
 * The app-icon waveform (five bars) drawn live. Still when idle; follows your voice while
 * listening; ripples while transcribing. Only animates while active.
 */
public sealed class Bars : FrameworkElement
{
    public enum Mode { Idle, Listening, Working }

    private static readonly double[] Base = { 16, 36, 22, 44, 16 };
    private static readonly double[] Xs = { 30, 41, 52, 63, 74 };
    private static readonly double[] Gain = { 0.55, 0.85, 0.7, 1, 0.5 };
    private readonly double[] cur = (double[])Base.Clone();
    private Mode mode = Mode.Idle;
    private DateTime start = DateTime.UtcNow;
    private double smooth;
    private bool hooked;

    public Func<float>? Level { get; set; }
    public Brush BarBrush { get; set; } = Brushes.White;
    public Brush? AccentBrush { get; set; } // tall 4th bar, like the icon

    public Mode CurrentMode
    {
        get => mode;
        set
        {
            mode = value;
            start = DateTime.UtcNow;
            if (value != Mode.Idle && !hooked) { CompositionTarget.Rendering += Tick; hooked = true; }
            InvalidateVisual();
        }
    }

    private void Tick(object? s, EventArgs e)
    {
        InvalidateVisual();
        if (mode == Mode.Idle && Settled()) { CompositionTarget.Rendering -= Tick; hooked = false; }
    }

    private bool Settled()
    {
        for (int i = 0; i < 5; i++) if (Math.Abs(cur[i] - Base[i]) > 0.3) return false;
        return true;
    }

    protected override void OnRender(DrawingContext dc)
    {
        double s = ActualWidth / 108.0;
        double t = (DateTime.UtcNow - start).TotalSeconds;
        if (mode == Mode.Listening)
        {
            var raw = Math.Sqrt(Math.Clamp((Level?.Invoke() ?? 0) * 6, 0, 1));
            smooth += (raw - smooth) * 0.4;
        }
        for (int i = 0; i < 5; i++)
        {
            double target = mode switch
            {
                Mode.Listening => 10 + smooth * 52 * Gain[i] + 3 * Math.Sin(t * 9 + i * 1.7),
                Mode.Working => 12 + 34 * (0.5 + 0.5 * Math.Sin((t * 1.6 - i * 0.18) * 2 * Math.PI)),
                _ => Base[i],
            };
            cur[i] += (target - cur[i]) * 0.3;
            double h = Math.Clamp(cur[i], 6, 64) * s;
            double cx = (Xs[i] + 3) * s;
            var brush = i == 3 && AccentBrush != null ? AccentBrush : BarBrush;
            dc.DrawRoundedRectangle(brush, null, new Rect(cx - 3 * s, ActualHeight / 2 - h / 2, 6 * s, h), 3 * s, 3 * s);
        }
    }
}

/**
 * The small floating pill at the bottom of the screen while you dictate. Click-through and
 * never takes focus, so typing continues in your app.
 */
public sealed class Pill : Window
{
    private readonly Bars bars = new() { Width = 30, Height = 30 };
    private Border shell = null!;
    private readonly TextBlock label = new()
    {
        Foreground = Brushes.White, FontSize = 13, FontFamily = C.Sans, VerticalAlignment = VerticalAlignment.Center,
        Margin = new Thickness(10, 0, 4, 0), Visibility = Visibility.Collapsed,
    };
    private readonly DispatcherTimer hideTimer = new();
    private readonly FrameworkElement close = Icons.Get("close", 13, Brushes.White);
    private Action? onDismiss; // set while a closable hint is showing

    public Pill(Func<float> level)
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        Focusable = false;
        Opacity = 0;
        bars.Level = level;
        Content = shell = Ui.Stadium(new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x1C, 0x1C, 0x1E)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0x33, 0xFF, 0xFF, 0xFF)),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(9),
            Child = Ui.Row(bars, label, close),
            Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 18, ShadowDepth = 3, Opacity = 0.35 },
            Margin = new Thickness(16),
        });
        close.Margin = new Thickness(6, 0, 0, 0);
        close.Opacity = 0.7;
        close.Visibility = Visibility.Collapsed;
        shell.MouseLeftButtonUp += (_, _) =>
        {
            if (onDismiss == null) return;
            var d = onDismiss;
            onDismiss = null;
            d();
            FadeOut();
        };
        hideTimer.Tick += (_, _) => { hideTimer.Stop(); FadeOut(); };
        SourceInitialized += (_, _) =>
        {
            var h = new WindowInteropHelper(this).Handle;
            SetWindowLong(h, GWL_EXSTYLE, GetWindowLong(h, GWL_EXSTYLE) | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TRANSPARENT);
        };
        SizeChanged += (_, _) => Place();
    }

    /** Sets what the pill shows without putting it on screen (screenshot mode). */
    internal FrameworkElement Preview(Bars.Mode mode, Brush barColor, string text)
    {
        bars.BarBrush = barColor;
        bars.CurrentMode = mode;
        SetText(text);
        return (FrameworkElement)Content;
    }

    internal Bars BarsView => bars;

    /** Listening / working / idle display. Text is optional (e.g. "Hands-free · Ctrl+Win to finish"). */
    public void Show(Bars.Mode mode, Brush barColor, string text = "")
    {
        hideTimer.Stop();
        bars.BarBrush = barColor;
        bars.CurrentMode = mode;
        SetText(text);
        if (!IsVisible) { base.Show(); }
        Place();
        Fade(1);
    }

    /** Shows a short message next to the recording indicator, then hides. */
    public void FlashNear(string text, Rect anchor, string dock, int ms = 2600)
    {
        anchorRect = anchor;
        anchorDock = dock;
        Flash(text, ms);
    }

    private Rect? anchorRect;
    private string anchorDock = "bottom";

    /**
     * A reminder with an X: clicking it hides the reminder for good (dismissed runs). Unlike other
     * messages it can be clicked, though it still never takes focus.
     */
    public void HintNear(string text, Rect anchor, string dock, int ms, Action dismissed)
    {
        FlashNear(text, anchor, dock, ms);
        onDismiss = dismissed;
        close.Visibility = Visibility.Visible;
        shell.Cursor = System.Windows.Input.Cursors.Hand;
        ClickThrough(false);
    }

    private void ClickThrough(bool on)
    {
        var h = new WindowInteropHelper(this).Handle;
        if (h == IntPtr.Zero) return;
        var style = GetWindowLong(h, GWL_EXSTYLE);
        SetWindowLong(h, GWL_EXSTYLE, on ? style | WS_EX_TRANSPARENT : style & ~WS_EX_TRANSPARENT);
    }

    /** Shows a short message, then hides. */
    public void Flash(string text, int ms = 2600)
    {
        onDismiss = null;
        close.Visibility = Visibility.Collapsed;
        ClickThrough(true);
        bars.BarBrush = Brushes.White;
        bars.CurrentMode = Bars.Mode.Idle;
        SetText(text);
        if (!IsVisible) base.Show();
        Place();
        Fade(1);
        hideTimer.Interval = TimeSpan.FromMilliseconds(ms);
        hideTimer.Start();
    }

    public void FadeOut()
    {
        hideTimer.Stop();
        bars.CurrentMode = Bars.Mode.Idle;
        Fade(0, () => { if (Opacity == 0) Hide(); });
    }

    private void SetText(string text)
    {
        label.Text = text;
        label.Visibility = text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        // Just the bars: a round disc like the Android button. With a message: a pill.
        shell.Padding = text.Length > 0 ? new Thickness(12, 9, 16, 9) : new Thickness(9);
    }

    private void Fade(double to, Action? done = null)
    {
        var a = new DoubleAnimation(to, TimeSpan.FromMilliseconds(to > 0 ? 120 : 180));
        if (done != null) a.Completed += (_, _) => done();
        BeginAnimation(OpacityProperty, a);
    }

    /** Next to the indicator when there is one; else bottom-center of the monitor you're working on. */
    private void Place()
    {
        if (anchorRect is { } a)
        {
            switch (anchorDock)
            {
                case "left": Left = a.Right - 8; Top = a.Top + a.Height / 2 - ActualHeight / 2; break;
                case "right": Left = a.Left - ActualWidth + 8; Top = a.Top + a.Height / 2 - ActualHeight / 2; break;
                default: Left = a.Left + a.Width / 2 - ActualWidth / 2; Top = a.Top - ActualHeight + 12; break;
            }
            return;
        }
        var mon = MonitorFromWindow(GetForegroundWindow(), 2);
        var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        double scale = VisualTreeHelper.GetDpi(this).DpiScaleX;
        if (mon != IntPtr.Zero && GetMonitorInfo(mon, ref info))
        {
            var w = info.rcWork;
            Left = (w.left + (w.right - w.left) / 2.0) / scale - ActualWidth / 2;
            Top = w.bottom / scale - ActualHeight - 28;
        }
        else
        {
            var wa = SystemParameters.WorkArea;
            Left = wa.Left + wa.Width / 2 - ActualWidth / 2;
            Top = wa.Bottom - ActualHeight - 28;
        }
    }

    private const int GWL_EXSTYLE = -20, WS_EX_NOACTIVATE = 0x08000000, WS_EX_TOOLWINDOW = 0x80, WS_EX_TRANSPARENT = 0x20;
    [DllImport("user32.dll")] private static extern int GetWindowLong(IntPtr h, int i);
    [DllImport("user32.dll")] private static extern int SetWindowLong(IntPtr h, int i, int v);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
    [DllImport("user32.dll")] private static extern bool GetMonitorInfo(IntPtr h, ref MONITORINFO info);
    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int left, top, right, bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct MONITORINFO { public int cbSize; public RECT rcMonitor, rcWork; public uint dwFlags; }
}
