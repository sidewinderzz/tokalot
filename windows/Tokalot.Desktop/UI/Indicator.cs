using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.UI;

/**
 * Draws the recording indicator in one of five styles (same shapes as the design canvas).
 * Shapes are built in "edge space": x runs along the screen edge (0..W), y = 0 is the edge and
 * negative y points into the screen. A transform then lays that onto the bottom, left or right edge,
 * so on the sides every style turns and still swells into the screen.
 */
public sealed class IndicatorView : FrameworkElement
{
    public enum Mode { Idle, Listening, Working }

    public static readonly string[] Styles = { "ripple", "half", "bars", "edge", "disc" };

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
    private bool hooked;

    /** Mic level (RMS), read every frame while listening. */
    public Func<float>? Level { get; set; }
    public bool Hover { get; set; }

    public string Look { get => style; set { style = value; Refresh(); } }
    public string Dock { get => dock; set { dock = value; Refresh(); } }
    public double Scale { get => scale; set { scale = value; Refresh(); } }
    public Mode CurrentMode
    {
        get => mode;
        set { mode = value; Refresh(); }
    }

    /** Keeps animating even when idle (Settings previews). */
    public bool AlwaysAnimate { get; set; }

    private void Refresh()
    {
        bool animate = mode != Mode.Idle || AlwaysAnimate;
        if (animate && !hooked && IsLoaded) { CompositionTarget.Rendering += Tick; hooked = true; }
        if (!animate && hooked) { CompositionTarget.Rendering -= Tick; hooked = false; }
        InvalidateVisual();
    }

    public IndicatorView()
    {
        Loaded += (_, _) => Refresh();
        Unloaded += (_, _) => { if (hooked) { CompositionTarget.Rendering -= Tick; hooked = false; } };
    }

    private void Tick(object? s, EventArgs e) => InvalidateVisual();

    /** Window/element size in DIPs for the current dock. */
    public Size Extent => dock == "bottom"
        ? new Size(SpanUnits * scale, DepthUnits * scale)
        : new Size(DepthUnits * scale, SpanUnits * scale);

    protected override Size MeasureOverride(Size available) => Extent;

    protected override void OnRender(DrawingContext dc)
    {
        double t = (DateTime.UtcNow - start).TotalSeconds;
        float raw = Level?.Invoke() ?? 0;
        double target = Math.Min(1, Math.Sqrt(Math.Max(0, raw) * 8));
        lv += (target - lv) * 0.35;

        var shape = Build(style, mode, lv, t, C.Argb(Settings.Current.Accent));
        double S = scale, L = SpanUnits, D = DepthUnits, W = shape.W;
        var m = new Matrix();
        switch (dock)
        {
            case "left": m = new Matrix(0, 1, -1, 0, 0, (L - W) / 2); break;      // (x,y) -> (-y, x)
            case "right": m = new Matrix(0, -1, 1, 0, D, (L + W) / 2); break;     // (x,y) -> (D + y, (L+W)/2 - x)
            default: m.Translate((L - W) / 2, D); break;                          // (x,y) -> (x, D + y)
        }
        m.Scale(S, S);
        dc.PushTransform(new MatrixTransform(m));

        // Near-invisible hit area so the thin idle bar is easy to grab (fully clear pixels don't take clicks).
        dc.DrawRectangle(HitBrush, null, new Rect(-8, -shape.Depth - 8, W + 16, shape.Depth + 8));

        var rimAlpha = Hover ? Math.Min(0.4, shape.Rim + 0.18) : shape.Rim;
        var rim = new Pen(new SolidColorBrush(Color.FromArgb((byte)(rimAlpha * 255), 255, 255, 255)), 1 / S);
        dc.DrawGeometry(shape.Fill, rim, shape.Body);
        if (shape.Lines != null)
            dc.DrawGeometry(null, new Pen(shape.LineBrush, shape.LineWidth) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round }, shape.Lines);
        dc.Pop();
    }

    private static readonly Brush HitBrush = Freeze(new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)));
    private static T Freeze<T>(T f) where T : Freezable { f.Freeze(); return f; }

    // ---------- shapes (edge space, same numbers as the design canvas) ----------

    private sealed record Shape(double W, double Depth, Geometry Body, Brush Fill, double Rim, Geometry? Lines, Brush LineBrush, double LineWidth);

    private static Brush Gradient(Color top, double topOpacity, Color mid) => Freeze(new LinearGradientBrush(new GradientStopCollection
    {
        new GradientStop(Color.FromArgb((byte)(topOpacity * 255), top.R, top.G, top.B), 0),
        new GradientStop(mid, 0.6),
        new GradientStop(Color.FromRgb(0x0F, 0x0F, 0x11), 1),
    }, 90));

    private static Color Hex(string h) => (Color)ColorConverter.ConvertFromString(h);
    private static readonly Brush Dark = Freeze(new SolidColorBrush(Hex("#1C1C1E")));
    private static readonly Brush White = Freeze(new SolidColorBrush(Hex("#F2F2F7")));

    private static Shape Build(string style, Mode mode, double lv, double t, Brush accent)
    {
        bool idle = mode == Mode.Idle, work = mode == Mode.Working;
        double sp = work ? 0.35 : 1, amp = idle ? 0 : work ? 3.5 : 2 + lv * 18;

        if (style is "ripple" or "half")
        {
            bool half = style == "half";
            if (idle)
                return new Shape(40, 16, Pill(40, 6, half ? 0 : 8, half, 0, t, 1), Freeze(new SolidColorBrush(Hex("#2E2E33"))), 0.12, null, White, 0);
            var fill = work ? Gradient(Hex("#4A4A50"), 1, Hex("#1C1C20")) : Gradient(Hex("#F2F2F7"), 0.9, Hex("#232327"));
            return new Shape(100, half ? 30 : 46, Pill(100, 16, half ? 0 : 8, half, amp, t, sp), fill, 0.1, null, White, 0);
        }
        if (style == "bars")
        {
            if (idle)
                return new Shape(40, 16, new RectangleGeometry(new Rect(0, -14, 40, 6), 3, 3), Freeze(new SolidColorBrush(Hex("#5A5A60"))), 0.1, null, White, 0);
            double Gw(int i) => 0.35 + 0.65 * Math.Sin(Math.PI * (i + 0.5) / 13);
            var lines = work
                ? Bars(13, 60, -22, 6.2, i => 4 + 7 * (0.5 + 0.5 * Math.Sin(t * 6 - i * 0.6)))
                : Bars(13, 60, -22, 6.2, i => 4 + lv * 20 * Gw(i) * (0.55 + 0.45 * Math.Sin(t * 11 + i * 1.9)));
            return new Shape(120, 40, new RectangleGeometry(new Rect(0, -36, 120, 28), 14, 14), Dark, 0.16, lines, work ? White : accent, 2.6);
        }
        if (style == "disc")
        {
            if (idle)
                return new Shape(44, 16, new EllipseGeometry(new Point(22, -12), 4, 4), Freeze(new SolidColorBrush(Hex("#5A5A60"))), 0.1, null, White, 0);
            double[] gain = { 0.55, 0.85, 0.7, 1, 0.5 };
            var lines = work
                ? Bars(5, 22, -30, 5.5, i => (12 + 30 * (0.5 + 0.5 * Math.Sin((t * 1.6 - i * 0.18) * Math.PI * 2))) * 0.5)
                : Bars(5, 22, -30, 5.5, i => (6 + lv * 30 * gain[i] + 2 * Math.Sin(t * 9 + i * 1.7)) * 0.62);
            return new Shape(44, 52, new EllipseGeometry(new Point(22, -30), 22, 22), Dark, 0.16, lines, work ? White : accent, 3);
        }
        // edge tab
        if (idle)
            return new Shape(44, 12, Tab(44, 5, 3), Freeze(new SolidColorBrush(Hex("#6E6E73"))), 0.1, null, White, 0);
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
            c.BeginFigure(new Point(0, 0), true, true);
            c.LineTo(new Point(0, -H + r), true, false);
            c.ArcTo(new Point(r, -H), new Size(r, r), 0, false, SweepDirection.Clockwise, true, false);
            c.LineTo(new Point(W - r, -H), true, false);
            c.ArcTo(new Point(W, -H + r), new Size(r, r), 0, false, SweepDirection.Clockwise, true, false);
            c.LineTo(new Point(W, 0), true, false);
        }
        g.Freeze();
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
                c.BeginFigure(new Point(x, cy - hh / 2), false, false);
                c.LineTo(new Point(x, cy + hh / 2), true, false);
            }
        }
        g.Freeze();
        return g;
    }

    private static Geometry Polygon(List<Point> pts)
    {
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            c.BeginFigure(pts[0], true, true);
            c.PolyLineTo(pts.GetRange(1, pts.Count - 1), true, true);
        }
        g.Freeze();
        return g;
    }

    private static Geometry Polyline(List<Point> pts)
    {
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            c.BeginFigure(pts[0], false, false);
            c.PolyLineTo(pts.GetRange(1, pts.Count - 1), true, true);
        }
        g.Freeze();
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
    private Point? pressAt;
    private bool dragging;
    private IntPtr dragMonitor;

    public IndicatorView.Mode Mode => view.CurrentMode;

    public IndicatorWindow(Func<float> level)
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        ResizeMode = ResizeMode.NoResize;
        Focusable = false;
        view.Level = level;
        Content = view;
        Cursor = Cursors.Hand;
        ToolTip = "Tokalot: click or hold Ctrl+Win to dictate. Drag to move.";
        SourceInitialized += (_, _) =>
        {
            var h = new WindowInteropHelper(this).Handle;
            SetWindowLong(h, GWL_EXSTYLE, GetWindowLong(h, GWL_EXSTYLE) | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW);
        };
        MouseEnter += (_, _) => { view.Hover = true; view.InvalidateVisual(); };
        MouseLeave += (_, _) => { view.Hover = false; view.InvalidateVisual(); };
        MouseLeftButtonDown += OnDown;
        MouseMove += OnMove;
        MouseLeftButtonUp += OnUp;

        // While idle: follow the monitor you're working on, and step aside for full-screen apps.
        watch = new DispatcherTimer(TimeSpan.FromMilliseconds(1200), DispatcherPriority.Background, (_, _) => Sync(), Dispatcher);
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

    /** Where the indicator is on screen (DIPs), for placing messages next to it. */
    public Rect Bounds => new(Left, Top, Width, Height);
    public string Dock => view.Dock;

    private void Sync(bool force = false)
    {
        if (dragging) return;
        var s = Settings.Current;
        bool show = view.CurrentMode != IndicatorView.Mode.Idle || (s.ShowIdleIndicator && !ForegroundIsFullScreen());
        if (!show) { if (IsVisible) Hide(); return; }
        if (view.CurrentMode == IndicatorView.Mode.Idle || force) Place(ActiveMonitor());
        if (!IsVisible) Show();
    }

    // ---------- placement ----------

    private void Place(IntPtr monitor)
    {
        var s = Settings.Current;
        var wa = WorkArea(monitor);
        var size = view.Extent;
        Width = size.Width;
        Height = size.Height;
        double k = Scale();
        double margin = 40;
        double along = Math.Clamp(s.IndicatorAlong, 0, 1);
        double l = wa.Left / k, t = wa.Top / k, r = wa.Right / k, b = wa.Bottom / k;
        switch (s.IndicatorDock)
        {
            case "left":
                Left = l;
                Top = t + margin + along * (b - t - 2 * margin) - Height / 2;
                break;
            case "right":
                Left = r - Width;
                Top = t + margin + along * (b - t - 2 * margin) - Height / 2;
                break;
            default:
                Left = l + margin + along * (r - l - 2 * margin) - Width / 2;
                Top = b - Height;
                break;
        }
    }

    /** Pixels per DIP. Tokalot is system-DPI aware, so the system DPI applies on every monitor. */
    private static double Scale()
    {
        try { return GetDpiForSystem() / 96.0; } catch { return 1; }
    }

    // ---------- drag and click ----------

    private void OnDown(object sender, MouseButtonEventArgs e)
    {
        pressAt = CursorPos();
        dragMonitor = MonitorFromPoint(ToPOINT(pressAt.Value), 2);
        CaptureMouse();
        e.Handled = true;
    }

    private void OnMove(object sender, MouseEventArgs e)
    {
        if (pressAt == null || e.LeftButton != MouseButtonState.Pressed) return;
        var p = CursorPos();
        if (!dragging && (Math.Abs(p.X - pressAt.Value.X) > 6 || Math.Abs(p.Y - pressAt.Value.Y) > 6))
        {
            dragging = true;
            Cursor = Cursors.SizeAll;
        }
        if (!dragging) return;

        // Snap to whichever of the bottom, left or right edge is nearest the cursor.
        var monitor = MonitorFromPoint(ToPOINT(p), 2);
        if (monitor != IntPtr.Zero) dragMonitor = monitor;
        var wa = WorkArea(dragMonitor);
        double dl = p.X - wa.Left, dr = wa.Right - p.X, db = Math.Max(0, wa.Bottom - p.Y);
        string dock = "bottom";
        if (dl <= dr && dl < db) dock = "left";
        else if (dr < dl && dr < db) dock = "right";
        double margin = 40 * Scale();
        double along = dock == "bottom"
            ? (p.X - wa.Left - margin) / Math.Max(1, wa.Width - 2 * margin)
            : (p.Y - wa.Top - margin) / Math.Max(1, wa.Height - 2 * margin);
        var s = Settings.Current;
        s.IndicatorDock = dock;
        s.IndicatorAlong = Math.Clamp(along, 0, 1);
        view.Dock = dock;
        Place(dragMonitor);
    }

    private void OnUp(object sender, MouseButtonEventArgs e)
    {
        ReleaseMouseCapture();
        var wasDrag = dragging;
        dragging = false;
        pressAt = null;
        Cursor = Cursors.Hand;
        if (wasDrag)
        {
            Settings.Current.Save();
            App.Current.Refresh();
        }
        else Clicked?.Invoke();
        e.Handled = true;
    }

    // ---------- Win32 ----------

    private static Point CursorPos() { GetCursorPos(out var p); return new Point(p.x, p.y); }
    private static POINT ToPOINT(Point p) => new() { x = (int)p.X, y = (int)p.Y };

    private static IntPtr ActiveMonitor()
    {
        var fg = GetForegroundWindow();
        return fg != IntPtr.Zero ? MonitorFromWindow(fg, 2) : MonitorFromPoint(new POINT(), 1);
    }

    private static Rect WorkArea(IntPtr monitor)
    {
        var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        if (monitor != IntPtr.Zero && GetMonitorInfo(monitor, ref info))
            return new Rect(info.rcWork.left, info.rcWork.top, info.rcWork.right - info.rcWork.left, info.rcWork.bottom - info.rcWork.top);
        var wa = SystemParameters.WorkArea;
        return wa;
    }

    /** True when the app in front covers its whole monitor (games, videos, presentations). */
    private static bool ForegroundIsFullScreen()
    {
        var fg = GetForegroundWindow();
        if (fg == IntPtr.Zero || fg == GetShellWindow() || fg == GetDesktopWindow()) return false;
        var cls = new System.Text.StringBuilder(64);
        GetClassName(fg, cls, cls.Capacity);
        if (cls.ToString() is "WorkerW" or "Progman") return false;
        if (!GetWindowRect(fg, out var r)) return false;
        var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        if (!GetMonitorInfo(MonitorFromWindow(fg, 2), ref info)) return false;
        var m = info.rcMonitor;
        return r.left <= m.left && r.top <= m.top && r.right >= m.right && r.bottom >= m.bottom;
    }

    private const int GWL_EXSTYLE = -20, WS_EX_NOACTIVATE = 0x08000000, WS_EX_TOOLWINDOW = 0x80;
    [DllImport("user32.dll")] private static extern int GetWindowLong(IntPtr h, int i);
    [DllImport("user32.dll")] private static extern int SetWindowLong(IntPtr h, int i, int v);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern IntPtr GetShellWindow();
    [DllImport("user32.dll")] private static extern uint GetDpiForSystem();
    [DllImport("user32.dll")] private static extern IntPtr GetDesktopWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr h, System.Text.StringBuilder s, int n);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromPoint(POINT p, uint flags);
    [DllImport("user32.dll")] private static extern bool GetMonitorInfo(IntPtr h, ref MONITORINFO info);
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int x, y; }
    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int left, top, right, bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct MONITORINFO { public int cbSize; public RECT rcMonitor, rcWork; public uint dwFlags; }
}
