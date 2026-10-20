using System;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/**
 * The app-icon waveform (five bars) drawn live. Still when idle; follows your voice while
 * listening; ripples while transcribing. Only animates while active.
 */
public sealed class Bars : Control
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
    // Redraws about 60 times a second while animating (WPF's CompositionTarget.Rendering).
    private readonly DispatcherTimer frames = new() { Interval = TimeSpan.FromMilliseconds(16) };

    public Func<float>? Level { get; set; }
    public IBrush BarBrush { get; set; } = Brushes.White;
    public IBrush? AccentBrush { get; set; } // tall 4th bar, like the icon

    public Bars() => frames.Tick += Tick;

    public Mode CurrentMode
    {
        get => mode;
        set
        {
            mode = value;
            start = DateTime.UtcNow;
            if (value != Mode.Idle && !hooked) { frames.Start(); hooked = true; }
            InvalidateVisual();
        }
    }

    private void Tick(object? s, EventArgs e)
    {
        InvalidateVisual();
        if (mode == Mode.Idle && Settled()) { frames.Stop(); hooked = false; }
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        if (hooked) { frames.Stop(); hooked = false; }
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (mode != Mode.Idle && !hooked) { frames.Start(); hooked = true; }
    }

    private bool Settled()
    {
        for (int i = 0; i < 5; i++) if (Math.Abs(cur[i] - Base[i]) > 0.3) return false;
        return true;
    }

    public override void Render(DrawingContext dc)
    {
        double s = Bounds.Width / 108.0;
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
            dc.DrawRectangle(brush, null, new Rect(cx - 3 * s, Bounds.Height / 2 - h / 2, 6 * s, h), 3 * s, 3 * s);
        }
    }
}

/**
 * The small floating pill that shows short messages next to the recording indicator. Click-through
 * and never takes focus, so typing continues in your app.
 */
public sealed class Pill : Window
{
    private readonly Bars bars = new() { Width = 30, Height = 30 };
    private Border shell = null!;
    private readonly TextBlock label = new()
    {
        Foreground = Brushes.White, FontSize = 13, FontFamily = C.Sans, VerticalAlignment = VerticalAlignment.Center,
        Margin = new Thickness(10, 0, 4, 0), IsVisible = false,
    };
    private readonly Control close = Icons.Get("close", 13, Brushes.White);
    private Action? onDismiss; // set while a closable hint is showing
    private readonly DispatcherTimer hideTimer = new();
    private readonly DispatcherTimer fadeDone = new();
    private double fadeTarget;

    public Pill(Func<float> level)
    {
        SystemDecorations = SystemDecorations.None;
        TransparencyLevelHint = new[] { WindowTransparencyLevel.Transparent };
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        CanResize = false;
        SizeToContent = SizeToContent.WidthAndHeight;
        Focusable = false;
        WindowStartupLocation = WindowStartupLocation.Manual;
        Title = "Tokalot";
        bars.Level = level;
        Content = shell = Ui.Stadium(new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x1C, 0x1C, 0x1E)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0x33, 0xFF, 0xFF, 0xFF)),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(9),
            Child = Ui.Row(bars, label, close),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 18, OffsetY = 3, Color = Color.FromArgb(0x59, 0, 0, 0) }),
            Margin = new Thickness(16),
            // The window itself can't fade on every Linux desktop, so the content does.
            Opacity = 0,
        });
        close.Margin = new Thickness(6, 0, 0, 0);
        close.Opacity = 0.7;
        close.IsVisible = false;
        shell.PointerReleased += (_, _) =>
        {
            if (onDismiss == null) return;
            var d = onDismiss;
            onDismiss = null;
            d();
            FadeOut();
        };
        hideTimer.Tick += (_, _) => { hideTimer.Stop(); FadeOut(); };
        fadeDone.Tick += (_, _) =>
        {
            fadeDone.Stop();
            if (fadeTarget != 0) return;
            Hide();
            EndHint();
        };
        // Never takes focus, and clicks pass through to whatever is underneath.
        Overlay.NeverFocus(this);
        Opened += (_, _) => ApplyInput();
        SizeChanged += (_, _) => { Place(); ApplyInput(); };
    }

    /** Sets what the pill shows without putting it on screen (screenshot mode). */
    internal Control Preview(Bars.Mode mode, IBrush barColor, string text)
    {
        bars.BarBrush = barColor;
        bars.CurrentMode = mode;
        SetText(text);
        shell.Opacity = 1;
        return shell;
    }

    internal Bars BarsView => bars;

    /** Listening / working / idle display. Text is optional (e.g. "Hands-free · Ctrl+Super to finish"). */
    public void Show(Bars.Mode mode, IBrush barColor, string text = "")
    {
        hideTimer.Stop();
        bars.BarBrush = barColor;
        bars.CurrentMode = mode;
        SetText(text);
        if (!IsVisible) { base.Show(); }
        Place();
        Fade(1);
    }

    /** Shows a short message next to the recording indicator (its place on screen, in pixels), then hides. */
    public void FlashNear(string text, PixelRect anchor, string dock, int ms = 2600)
    {
        anchorRect = anchor;
        anchorDock = dock;
        Flash(text, ms);
    }

    /**
     * A reminder with an X: clicking it hides the reminder for good (dismissed runs). Unlike other
     * messages it can be clicked, though it still never takes focus.
     */
    public void HintNear(string text, PixelRect anchor, string dock, int ms, Action dismissed)
    {
        FlashNear(text, anchor, dock, ms);
        onDismiss = dismissed;
        close.IsVisible = true;
        shell.Cursor = Cursors.Hand;
        ApplyInput();
    }

    private void EndHint()
    {
        onDismiss = null;
        close.IsVisible = false;
        shell.Cursor = null;
        ApplyInput();
    }

    /**
     * Clicks pass through the pill to whatever is underneath, except while a hint with an X is showing:
     * then the drawn pill (not the clear margin around it) takes them.
     */
    private void ApplyInput()
    {
        if (onDismiss == null) { Overlay.SetInputRegion(this, null); return; }
        double k = RenderScaling;
        var m = shell.Margin;
        Overlay.SetInputRegion(this, new PixelRect((int)(m.Left * k), (int)(m.Top * k),
            (int)Math.Ceiling((Bounds.Width - m.Left - m.Right) * k), (int)Math.Ceiling((Bounds.Height - m.Top - m.Bottom) * k)));
    }

    private PixelRect? anchorRect;
    private string anchorDock = "bottom";

    /** Shows a short message, then hides. */
    public void Flash(string text, int ms = 2600)
    {
        EndHint();
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
        Fade(0);
    }

    private void SetText(string text)
    {
        label.Text = text;
        label.IsVisible = text.Length > 0;
        // Just the bars: a round disc like the Android button. With a message: a pill.
        shell.Padding = text.Length > 0 ? new Thickness(12, 9, 16, 9) : new Thickness(9);
    }

    private void Fade(double to)
    {
        var ms = to > 0 ? 120 : 180;
        shell.Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(ms) } };
        shell.Opacity = to;
        fadeTarget = to;
        fadeDone.Stop();
        if (to == 0)
        {
            fadeDone.Interval = TimeSpan.FromMilliseconds(ms + 40);
            fadeDone.Start();
        }
    }

    /** Next to the indicator when there is one; else bottom-center of the main monitor. */
    private void Place()
    {
        double k = RenderScaling;
        int w = (int)Math.Ceiling(Bounds.Width * k), h = (int)Math.Ceiling(Bounds.Height * k);
        if (w == 0 || h == 0) return;
        PixelPoint p;
        if (anchorRect is { } a)
        {
            p = anchorDock switch
            {
                "left" => new PixelPoint(a.Right - (int)(8 * k), a.Y + a.Height / 2 - h / 2),
                "right" => new PixelPoint(a.X - w + (int)(8 * k), a.Y + a.Height / 2 - h / 2),
                _ => new PixelPoint(a.X + a.Width / 2 - w / 2, a.Y - h + (int)(12 * k)),
            };
        }
        else
        {
            var screen = Screens.Primary ?? (Screens.All.Count > 0 ? Screens.All[0] : null);
            if (screen == null) return;
            var wa = screen.WorkingArea;
            p = new PixelPoint(wa.X + wa.Width / 2 - w / 2, wa.Bottom - h - (int)(28 * k));
        }
        // Only move when it actually changed: some window managers nudge a window that is told to move to where it already is.
        if (p != Position) Position = p;
    }
}
