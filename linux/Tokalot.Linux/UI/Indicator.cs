using System;
using System.Collections.Generic;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using Avalonia.Platform;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.UI;

/**
 * Draws the recording indicator in one of five styles (same shapes as the design canvas).
 * Shapes are built in "edge space": x runs along the screen edge (0..W), y = 0 is the edge and
 * negative y points into the screen. A transform then lays that onto the bottom, left or right edge,
 * so on the sides every style turns and still swells into the screen.
 */
public sealed class IndicatorView : Control
{
    public enum Mode { Idle, Listening, Working }

    public static new readonly string[] Styles = { "ripple", "half", "bars", "edge", "disc" };

    public static (string Name, string Desc) Info(string style) => style switch
    {
        "half" => ("Half pill", "The same pill cut in half, flush with the screen edge."),
        "bars" => ("Bars pill", "Capsule with dancing voice bars."),
        "edge" => ("Edge tab", "Low tab attached to the screen edge. Its inner edge ripples."),
        "disc" => ("Disc", "Round disc with the app icon's five bars."),
        _ => ("Ripple pill", "Slim dark pill. Its top swells and ripples with your voice."),
    };

    /** Edge-space size: longest shape (bars, 120) plus room, and the deepest swell. */
    public const double SpanUnits = 150, DepthUnits = 56;

    private string style = "ripple";
    private Mode mode = Mode.Idle;
    private string dock = "bottom";
    private double scale = 1.3;
    private double lv;
    private readonly DateTime start = DateTime.UtcNow;
    private bool hooked, attached;
    // Redraws about 60 times a second while animating (WPF's CompositionTarget.Rendering).
    private readonly DispatcherTimer frames = new() { Interval = TimeSpan.FromMilliseconds(16) };

    /** Mic level (RMS), read every frame while listening. */
    public Func<float>? Level { get; set; }
    public bool Hover { get; set; }

    public string Look { get => style; set { style = value; Refresh(); } }
    public string Dock { get => dock; set { dock = value; InvalidateMeasure(); Refresh(); } }
    public double Scale { get => scale; set { scale = value; InvalidateMeasure(); Refresh(); } }
    public Mode CurrentMode
    {
        get => mode;
        set { mode = value; Refresh(); }
    }

    /** Keeps animating even when idle (Settings previews). */
    public bool AlwaysAnimate { get; set; }

    /** Holds still (a Settings preview while its window is minimized or in the background). */
    public bool Frozen { get => frozen; set { if (frozen == value) return; frozen = value; Refresh(); } }
    private bool frozen;

    private void Refresh()
    {
        bool animate = (mode != Mode.Idle || AlwaysAnimate) && !frozen;
        if (animate && !hooked && attached) { frames.Start(); hooked = true; }
        if (!animate && hooked) { frames.Stop(); hooked = false; }
        InvalidateVisual();
    }

    public IndicatorView() => frames.Tick += Tick;

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        attached = true;
        Refresh();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        attached = false;
        if (hooked) { frames.Stop(); hooked = false; }
    }

    private void Tick(object? s, EventArgs e) => InvalidateVisual();

    /** Window/element size in DIPs for the current dock. */
    public Size Extent => dock == "bottom"
        ? new Size(SpanUnits * scale, DepthUnits * scale)
        : new Size(DepthUnits * scale, SpanUnits * scale);

    protected override Size MeasureOverride(Size available) => Extent;

    /** Edge space -> this element, for the current dock and scale. */
    private Matrix Transform(double W)
    {
        double S = scale, L = SpanUnits, D = DepthUnits;
        var m = dock switch
        {
            "left" => new Matrix(0, 1, -1, 0, 0, (L - W) / 2),     // (x,y) -> (-y, x)
            "right" => new Matrix(0, -1, 1, 0, D, (L + W) / 2),    // (x,y) -> (D + y, (L+W)/2 - x)
            _ => Matrix.CreateTranslation((L - W) / 2, D),         // (x,y) -> (x, D + y)
        };
        return m * Matrix.CreateScale(S, S);
    }

    /**
     * The part of this element that takes clicks (DIPs): the shape plus a little room, so the thin
     * idle bar is easy to grab. The window limits its mouse input to this rectangle.
     */
    public Rect HitArea
    {
        get
        {
            var shape = Build(style, mode, 0, 0, Brushes.White);
            return new Rect(-8, -shape.Depth - 8, shape.W + 16, shape.Depth + 8).TransformToAABB(Transform(shape.W));
        }
    }

    public override void Render(DrawingContext dc)
    {
        double t = (DateTime.UtcNow - start).TotalSeconds;
        float raw = Level?.Invoke() ?? 0;
        double target = Math.Min(1, Math.Sqrt(Math.Max(0, raw) * 8));
        lv += (target - lv) * 0.35;

        var shape = Build(style, mode, lv, t, C.Argb(Settings.Current.Accent));
        double S = scale, W = shape.W;
        using var _ = dc.PushTransform(Transform(W));

        // Near-invisible hit area so the thin idle bar is easy to grab.
        dc.DrawRectangle(HitBrush, null, new Rect(-8, -shape.Depth - 8, W + 16, shape.Depth + 8));

        var rimAlpha = Hover ? Math.Min(0.4, shape.Rim + 0.18) : shape.Rim;
        var rim = new Pen(new ImmutableSolidColorBrush(Color.FromArgb((byte)(rimAlpha * 255), 255, 255, 255)), 1 / S);
        dc.DrawGeometry(shape.Fill, rim, shape.Body);
        if (shape.Lines != null)
            dc.DrawGeometry(null, new Pen(shape.LineBrush, shape.LineWidth, lineCap: PenLineCap.Round), shape.Lines);
    }

    private static readonly IBrush HitBrush = new ImmutableSolidColorBrush(Color.FromArgb(1, 0, 0, 0));

    // ---------- shapes (edge space, same numbers as the design canvas) ----------

    private sealed record Shape(double W, double Depth, Geometry Body, IBrush Fill, double Rim, Geometry? Lines, IBrush LineBrush, double LineWidth);

    private static IBrush Gradient(Color top, double topOpacity, Color mid) => new LinearGradientBrush
    {
        // Runs from the side facing into the screen to the screen edge (WPF's 90 degree gradient).
        StartPoint = new RelativePoint(0.5, 0, RelativeUnit.Relative),
        EndPoint = new RelativePoint(0.5, 1, RelativeUnit.Relative),
        GradientStops =
        {
            new GradientStop(Color.FromArgb((byte)(topOpacity * 255), top.R, top.G, top.B), 0),
            new GradientStop(mid, 0.6),
            new GradientStop(Color.FromRgb(0x0F, 0x0F, 0x11), 1),
        },
    }.ToImmutable();

    private static Color Hex(string h) => Color.Parse(h);
    private static IBrush Solid(string h) => new ImmutableSolidColorBrush(Hex(h));
    private static readonly IBrush Dark = Solid("#1C1C1E");
    private static readonly IBrush White = Solid("#F2F2F7");

    private static Shape Build(string style, Mode mode, double lv, double t, IBrush accent)
    {
        bool idle = mode == Mode.Idle, work = mode == Mode.Working;
        double sp = work ? 0.35 : 1, amp = idle ? 0 : work ? 3.5 : 2 + lv * 18;

        if (style is "ripple" or "half")
        {
            bool half = style == "half";
            if (idle)
                return new Shape(40, 16, Pill(40, 6, half ? 0 : 8, half, 0, t, 1), Solid("#2E2E33"), 0.12, null, White, 0);
            var fill = work ? Gradient(Hex("#4A4A50"), 1, Hex("#1C1C20")) : Gradient(Hex("#F2F2F7"), 0.9, Hex("#232327"));
            return new Shape(100, half ? 30 : 46, Pill(100, 16, half ? 0 : 8, half, amp, t, sp), fill, 0.1, null, White, 0);
        }
        if (style == "bars")
        {
            if (idle)
                return new Shape(40, 16, new RectangleGeometry(new Rect(0, -14, 40, 6), 3, 3), Solid("#5A5A60"), 0.1, null, White, 0);
            double Gw(int i) => 0.35 + 0.65 * Math.Sin(Math.PI * (i + 0.5) / 13);
            var lines = work
                ? Bars(13, 60, -22, 6.2, i => 4 + 7 * (0.5 + 0.5 * Math.Sin(t * 6 - i * 0.6)))
                : Bars(13, 60, -22, 6.2, i => 4 + lv * 20 * Gw(i) * (0.55 + 0.45 * Math.Sin(t * 11 + i * 1.9)));
            return new Shape(120, 40, new RectangleGeometry(new Rect(0, -36, 120, 28), 14, 14), Dark, 0.16, lines, work ? White : accent, 2.6);
        }
        if (style == "disc")
        {
            if (idle)
                return new Shape(44, 16, new EllipseGeometry(new Rect(18, -16, 8, 8)), Solid("#5A5A60"), 0.1, null, White, 0);
            double[] gain = { 0.55, 0.85, 0.7, 1, 0.5 };
            var lines = work
                ? Bars(5, 22, -30, 5.5, i => (12 + 30 * (0.5 + 0.5 * Math.Sin((t * 1.6 - i * 0.18) * Math.PI * 2))) * 0.5)
                : Bars(5, 22, -30, 5.5, i => (6 + lv * 30 * gain[i] + 2 * Math.Sin(t * 9 + i * 1.7)) * 0.62);
            return new Shape(44, 52, new EllipseGeometry(new Rect(0, -52, 44, 44)), Dark, 0.16, lines, work ? White : accent, 3);
        }
        // edge tab
        if (idle)
            return new Shape(44, 12, Tab(44, 5, 3), Solid("#6E6E73"), 0.1, null, White, 0);
        var pts = new List<Point>();
        for (double x = 12; x <= 78.01; x += 3) pts.Add(new Point(x, -26 - Wave(x, 90, 12, work ? 2.5 : 1 + lv * 8, t, sp)));
        return new Shape(90, 40, Tab(90, 26, 12), Dark, 0.16, Polyline(pts), work ? White : accent, work ? 1.5 : 2);
    }

    private static double Wave(double x, double W, double r, double amp, double t, double sp)
    {
        double taper = Math.Sin(Math.PI * (x - r) / (W - 2 * r));
        double w = 0.55 * Math.Sin(x * 0.11 - t * 10 * sp) + 0.3 * Math.Sin(x * 0.23 + t * 7 * sp) + 0.15 * Math.Sin(x * 0.05 - t * 4 * sp);
        return amp * taper * (0.45 + 0.55 * w);
    }

    /** A pill (or half pill flush with the edge) whose top edge ripples. */
    private static Geometry Pill(double W, double H, double gap, bool half, double amp, double t, double sp)
    {
        double r = H / 2;
        var pts = new List<Point>();
        if (half)
        {
            for (double x = r; x <= W - r + 0.01; x += 3) pts.Add(new Point(x, -r - Wave(x, W, r, amp, t, sp)));
            for (int i = 0; i <= 10; i++) { double a = -Math.PI / 2 + Math.PI / 2 * i / 10; pts.Add(new Point(W - r + r * Math.Cos(a), r * Math.Sin(a))); }
            for (int i = 0; i <= 10; i++) { double a = Math.PI + Math.PI / 2 * i / 10; pts.Add(new Point(r + r * Math.Cos(a), r * Math.Sin(a))); }
        }
        else
        {
            double T = -gap - H, cy = T + r;
            for (double x = r; x <= W - r + 0.01; x += 3) pts.Add(new Point(x, T - Wave(x, W, r, amp, t, sp)));
            for (int i = 0; i <= 14; i++) { double a = -Math.PI / 2 + Math.PI * i / 14; pts.Add(new Point(W - r + r * Math.Cos(a), cy + r * Math.Sin(a))); }
            for (int i = 0; i <= 14; i++) { double a = Math.PI / 2 + Math.PI * i / 14; pts.Add(new Point(r + r * Math.Cos(a), cy + r * Math.Sin(a))); }
        }
        return Polygon(pts);
    }

    /** Flat on the edge, rounded corners facing into the screen. */
    private static Geometry Tab(double W, double H, double r)
    {
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            c.BeginFigure(new Point(0, 0), true);
            c.LineTo(new Point(0, -H + r));
            c.ArcTo(new Point(r, -H), new Size(r, r), 0, false, SweepDirection.Clockwise);
            c.LineTo(new Point(W - r, -H));
            c.ArcTo(new Point(W, -H + r), new Size(r, r), 0, false, SweepDirection.Clockwise);
            c.LineTo(new Point(W, 0));
            c.EndFigure(true);
        }
        return g;
    }

    private static Geometry Bars(int n, double cx, double cy, double gap, Func<int, double> h)
    {
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            for (int i = 0; i < n; i++)
            {
                double x = cx + (i - (n - 1) / 2.0) * gap, hh = Math.Max(2, h(i));
                c.BeginFigure(new Point(x, cy - hh / 2), false);
                c.LineTo(new Point(x, cy + hh / 2));
                c.EndFigure(false);
            }
        }
        return g;
    }

    private static Geometry Polygon(List<Point> pts)
    {
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            c.BeginFigure(pts[0], true);
            for (int i = 1; i < pts.Count; i++) c.LineTo(pts[i]);
            c.EndFigure(true);
        }
        return g;
    }

    private static Geometry Polyline(List<Point> pts)
    {
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            c.BeginFigure(pts[0], false);
            for (int i = 1; i < pts.Count; i++) c.LineTo(pts[i]);
            c.EndFigure(false);
        }
        return g;
    }
}

/**
 * The on-screen indicator: sits on the bottom, left or right edge of the monitor you're working on,
 * never takes focus, can be dragged to another edge or spot, and clicked to start or finish dictating.
 */
public sealed class IndicatorWindow : Window
{
    public event Action? Clicked;

    private readonly IndicatorView view = new();
    private readonly DispatcherTimer watch;
    private PixelPoint? pressAt;
    private bool dragging;
    private Screen? dragMonitor;

    public IndicatorView.Mode Mode => view.CurrentMode;

    public IndicatorWindow(Func<float> level)
    {
        SystemDecorations = SystemDecorations.None;
        TransparencyLevelHint = new[] { WindowTransparencyLevel.Transparent };
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        CanResize = false;
        Focusable = false;
        WindowStartupLocation = WindowStartupLocation.Manual;
        Title = "Tokalot";
        view.Level = level;
        Content = view;
        Cursor = Cursors.Hand;
        ToolTip.SetTip(this, $"Tokalot: click or hold {Host.Shortcut} to dictate. Drag to move.");
        // Clicking it must not pull the keyboard away from the app you're dictating into.
        Overlay.NeverFocus(this);
        PointerEntered += (_, _) => { view.Hover = true; view.InvalidateVisual(); };
        PointerExited += (_, _) => { view.Hover = false; view.InvalidateVisual(); };
        PointerPressed += OnDown;
        PointerMoved += OnMove;
        PointerReleased += OnUp;
        Opened += (_, _) => ApplyInputRegion();

        // While idle: follow the monitor you're working on, and step aside for full-screen apps.
        watch = new DispatcherTimer(TimeSpan.FromMilliseconds(1200), DispatcherPriority.Background, (_, _) => Sync());
        watch.Start();
        ApplySettings();
    }

    /** Re-reads style, dock and visibility from Settings. */
    public void ApplySettings()
    {
        var s = Settings.Current;
        view.Look = s.IndicatorStyle;
        view.Dock = s.IndicatorDock;
        Sync(force: true);
    }

    public void SetMode(IndicatorView.Mode mode)
    {
        view.CurrentMode = mode;
        Sync(force: true);
    }

    /** Where the indicator is on screen (pixels), for placing messages next to it. */
    public PixelRect ScreenBounds
    {
        get
        {
            double k = RenderScaling;
            return new PixelRect(Position, new PixelSize((int)(Width * k), (int)(Height * k)));
        }
    }

    public string Dock => view.Dock;

    private void Sync(bool force = false)
    {
        if (dragging) return;
        var s = Settings.Current;
        // Idle with the idle bar switched off: nothing to show or follow, so the window isn't asked about every tick.
        if (!force && !IsVisible && view.CurrentMode == IndicatorView.Mode.Idle && !s.ShowIdleIndicator) return;
        var (fullScreen, center) = Overlay.ActiveWindowState();
        bool show = view.CurrentMode != IndicatorView.Mode.Idle || (s.ShowIdleIndicator && !fullScreen);
        // Placed even when hidden, so messages still know where to appear.
        if (view.CurrentMode == IndicatorView.Mode.Idle || force) Place(ActiveMonitor(center));
        if (!show) { if (IsVisible) Hide(); return; }
        if (!IsVisible) Show();
    }

    // ---------- placement ----------

    private void Place(Screen? monitor)
    {
        if (monitor == null) return;
        var s = Settings.Current;
        var wa = monitor.WorkingArea;
        var size = view.Extent;
        Width = size.Width;
        Height = size.Height;
        double k = monitor.Scaling;
        double margin = 40;
        double along = Math.Clamp(s.IndicatorAlong, 0, 1);
        double l = wa.X / k, t = wa.Y / k, r = wa.Right / k, b = wa.Bottom / k;
        double left, top;
        switch (s.IndicatorDock)
        {
            case "left":
                left = l;
                top = t + margin + along * (b - t - 2 * margin) - Height / 2;
                break;
            case "right":
                left = r - Width;
                top = t + margin + along * (b - t - 2 * margin) - Height / 2;
                break;
            default:
                left = l + margin + along * (r - l - 2 * margin) - Width / 2;
                top = b - Height;
                break;
        }
        var p = new PixelPoint((int)Math.Round(left * k), (int)Math.Round(top * k));
        if (p != Position) Position = p;
        ApplyInputRegion();
    }

    /** Only the drawn shape takes clicks; the rest of this (transparent) window lets them through. */
    private void ApplyInputRegion()
    {
        double k = RenderScaling;
        var h = view.HitArea;
        Overlay.SetInputRegion(this, new PixelRect((int)Math.Floor(h.X * k), (int)Math.Floor(h.Y * k), (int)Math.Ceiling(h.Width * k), (int)Math.Ceiling(h.Height * k)));
    }

    // ---------- drag and click ----------

    private PixelPoint CursorPos(PointerEventArgs e) => this.PointToScreen(e.GetPosition(this));

    private void OnDown(object? sender, PointerPressedEventArgs e)
    {
        if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        pressAt = CursorPos(e);
        dragMonitor = Screens.ScreenFromPoint(pressAt.Value) ?? Screens.Primary;
        e.Pointer.Capture(this);
        e.Handled = true;
    }

    private void OnMove(object? sender, PointerEventArgs e)
    {
        if (pressAt == null || !e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        var p = CursorPos(e);
        if (!dragging && (Math.Abs(p.X - pressAt.Value.X) > 6 || Math.Abs(p.Y - pressAt.Value.Y) > 6))
        {
            dragging = true;
            Cursor = Cursors.SizeAll;
            // While dragging, the whole window follows the pointer; let all of it take the mouse.
            Overlay.SetInputRegion(this, new PixelRect(0, 0, 4096, 4096));
        }
        if (!dragging) return;

        // Snap to whichever of the bottom, left or right edge is nearest the cursor.
        var monitor = Screens.ScreenFromPoint(p);
        if (monitor != null) dragMonitor = monitor;
        if (dragMonitor == null) return;
        var wa = dragMonitor.WorkingArea;
        double dl = p.X - wa.X, dr = wa.Right - p.X, db = Math.Max(0, wa.Bottom - p.Y);
        string dock = "bottom";
        if (dl <= dr && dl < db) dock = "left";
        else if (dr < dl && dr < db) dock = "right";
        double margin = 40 * dragMonitor.Scaling;
        double along = dock == "bottom"
            ? (p.X - wa.X - margin) / Math.Max(1, wa.Width - 2 * margin)
            : (p.Y - wa.Y - margin) / Math.Max(1, wa.Height - 2 * margin);
        var s = Settings.Current;
        s.IndicatorDock = dock;
        s.IndicatorAlong = Math.Clamp(along, 0, 1);
        view.Dock = dock;
        var keep = dragMonitor;
        Place(keep);
        Overlay.SetInputRegion(this, new PixelRect(0, 0, 4096, 4096));
    }

    private void OnUp(object? sender, PointerReleasedEventArgs e)
    {
        if (pressAt == null) return;
        e.Pointer.Capture(null);
        var wasDrag = dragging;
        dragging = false;
        pressAt = null;
        Cursor = Cursors.Hand;
        ApplyInputRegion();
        if (wasDrag)
        {
            Settings.Current.Save();
            App.Current.Refresh();
        }
        else Clicked?.Invoke();
        e.Handled = true;
    }

    // ---------- which monitor ----------

    /** The monitor holding the focused window; else the one under the mouse; else the main one. */
    private Screen? ActiveMonitor(PixelPoint? activeWindowCenter)
    {
        try
        {
            if (activeWindowCenter is { } c && Screens.ScreenFromPoint(c) is { } a) return a;
            if (Overlay.Pointer() is { } p && Screens.ScreenFromPoint(p) is { } b) return b;
            return Screens.Primary ?? (Screens.All.Count > 0 ? Screens.All[0] : null);
        }
        catch { return null; }
    }
}
