using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.UI;

/**
 * Developer tool: renders every page (light and dark) plus the recording pill to PNGs,
 * using made-up sample data in a throwaway folder. Run: Tokalot.exe --screenshots <folder>
 */
internal static class Shots
{
    private const double Scale = 1.5;

    public static void Run(string dir)
    {
        Directory.CreateDirectory(dir);
        Seed();
        foreach (var theme in new[] { "light", "dark" })
        {
            C.Apply(theme);
            Page(dir, theme, "1-home", w => w.Go(MainWindow.Page.Home));
            Page(dir, theme, "2-dictionary", w => w.Go(MainWindow.Page.Dictionary));
            Page(dir, theme, "3-style", w => { w.SetStyleTab("MESSAGING"); w.Go(MainWindow.Page.Style); });
            Page(dir, theme, "4-snippets", w => w.Go(MainWindow.Page.Snippets));
            Page(dir, theme, "5-snippet-editor", w => { w.Go(MainWindow.Page.Snippets); w.OpenSnippetEditor(null); });
            Page(dir, theme, "6-settings", w => w.Go(MainWindow.Page.Settings));
            Page(dir, theme, "7-wide-home", w => { w.ForcedWidth = 1700; w.Go(MainWindow.Page.Home); w.Render(); }, 1700);
            Page(dir, theme, "8-wide-settings", w => { w.ForcedWidth = 1700; w.Go(MainWindow.Page.Settings); w.Render(); }, 1700);
            Page(dir, theme, "9-wide-style", w => { w.ForcedWidth = 1700; w.SetStyleTab("MESSAGING"); w.Go(MainWindow.Page.Style); w.Render(); }, 1700);
        }
        PillShots(dir);
        IndicatorShots(dir);
        MenuShot(dir);
        AudioRoundTrip(dir);
        SyncChecks(dir);
        LiveChecks(dir);
    }

    /** The sync merge cases the Android app is tested against too; writes PASS/FAIL per case. */
    private static void SyncChecks(string dir)
    {
        static Sync.Data D(string[]? words = null, (string, string)[]? snips = null, (string, string)[]? styles = null, string ins = "x") => new(
            (words ?? Array.Empty<string>()).ToList(),
            (snips ?? Array.Empty<(string, string)>()).Select(t => new Snippet(t.Item1, t.Item2)).ToList(),
            (styles ?? Array.Empty<(string, string)>()).ToDictionary(t => t.Item1, t => t.Item2), ins);
        var lines = new System.Collections.Generic.List<string>();
        void Check(string name, bool ok) => lines.Add((ok ? "PASS " : "FAIL ") + name);
        string W(Sync.Data d) => string.Join(",", d.Words);
        string St(Sync.Data d) => string.Join(",", d.Styles.OrderBy(kv => kv.Key).Select(kv => kv.Key + "=" + kv.Value));

        Check("1 first sync unions words", W(Sync.Merge(D(new[] { "Alpha", "Beta" }), D(new[] { "beta", "Gamma" }), D())) == "beta,Gamma,Alpha");
        Check("2 local delete wins", W(Sync.Merge(D(new[] { "Alpha", "Gamma" }), D(new[] { "Alpha", "Beta", "Gamma", "Delta" }), D(new[] { "Alpha", "Beta", "Gamma" }))) == "Alpha,Gamma,Delta");
        Check("3 remote delete applies", W(Sync.Merge(D(new[] { "Alpha", "Beta" }), D(new[] { "Alpha" }), D(new[] { "Alpha", "Beta" }))) == "Alpha");
        var b4 = D(snips: new[] { ("my email", "a@x") });
        Check("4a snippet changed elsewhere", Sync.Merge(D(snips: new[] { ("my email", "a@x") }), D(snips: new[] { ("my email", "b@x") }), b4).Snippets[0].Text == "b@x");
        Check("4b snippet changed here", Sync.Merge(D(snips: new[] { ("my email", "c@x") }), D(snips: new[] { ("my email", "b@x") }), b4).Snippets[0].Text == "c@x");
        Check("5a styles from elsewhere", St(Sync.Merge(D(styles: new[] { ("EMAIL", "FORMAL") }), D(styles: new[] { ("EMAIL", "CASUAL"), ("MESSAGING", "VERY_CASUAL") }), D(styles: new[] { ("EMAIL", "FORMAL") }))) == "EMAIL=CASUAL,MESSAGING=VERY_CASUAL");
        Check("5b style set here", St(Sync.Merge(D(styles: new[] { ("EMAIL", "CASUAL") }), D(styles: new[] { ("EMAIL", "FORMAL") }), D())) == "EMAIL=CASUAL");
        Check("6a instructions from elsewhere", Sync.Merge(D(ins: "x"), D(ins: "y"), D(ins: "x")).Instructions == "y");
        Check("6b instructions changed here", Sync.Merge(D(ins: "z"), D(ins: "y"), D(ins: "x")).Instructions == "z");
        var settled = Sync.Merge(D(new[] { "Alpha", "Beta" }), D(new[] { "beta", "Gamma" }), D());
        Check("9 settles: a second device with the same file changes nothing",
            W(Sync.Merge(D(new[] { "Gamma", "Alpha", "BETA" }), settled, D())) == W(settled) && W(Sync.Merge(settled, settled, settled)) == W(settled));
        var round = Sync.Parse(System.Text.Json.Nodes.JsonNode.Parse(Sync.ToJson(D(new[] { "A" }, new[] { ("t", "v") }, new[] { ("EMAIL", "FORMAL") }, "i"), null).ToJsonString())!.AsObject(), "d");
        Check("8 json round trip", Sync.Same(round, D(new[] { "A" }, new[] { ("t", "v") }, new[] { ("EMAIL", "FORMAL") }, "i")));
        File.WriteAllLines(Path.Combine(dir, "sync-checks.txt"), lines);
    }

    /** Saves two seconds of tone and reads it back, the path "Transcribe" on a failed entry relies on. */
    /** Transcribing while talking, and the compressed upload, against a stand-in speech service on this PC. */
    private static void LiveChecks(string dir)
    {
        var lines = new List<string>();
        void Check(string name, bool ok) => lines.Add((ok ? "PASS " : "FAIL ") + name);
        const int R = Recorder.SampleRate;

        Check("cut: not while talking", LiveStt.NextCut(0, R * 20, R * 20 - 100) == -1);
        Check("cut: not before 10 s have been said", LiveStt.NextCut(0, R * 9, R * 8) == -1);
        Check("cut: in the middle of the pause", LiveStt.NextCut(0, R * 13, R * 12) == R * 12 + R / 4);
        Check("cut: counts from the last cut", LiveStt.NextCut(R * 12, R * 20, R * 19) == -1 && LiveStt.NextCut(R * 12, R * 24, R * 23) == R * 23 + R / 4);

        // A 40 s "recording": tone where there is speech, silence in the pauses and for the last 6 s.
        var audio = new float[R * 40];
        bool Speaking(int i) => i < R * 34 && (i / R) % 12 < 11; // an 11 s phrase, then a 1 s pause
        for (int i = 0; i < audio.Length; i++) audio[i] = Speaking(i) ? (float)Math.Sin(i * 0.2) * 0.2f : 0f;
        float[] Take(int from, int to) => audio[from..Math.Min(to, audio.Length)];
        int LastVoice(int count) { int i = count - 1; while (i > 0 && !Speaking(i)) i--; return i + 1; }

        var got = new List<int>();
        async System.Threading.Tasks.Task<string> Fake(float[] piece, System.Threading.CancellationToken ct)
        {
            await System.Threading.Tasks.Task.Delay(30, ct);
            lock (got) got.Add(piece.Length);
            return "Piece of " + (piece.Length / (double)R).ToString("0.00", System.Globalization.CultureInfo.InvariantCulture) + " seconds.";
        }
        var live = new LiveStt(Settings.Current, Fake);
        for (int count = R / 4; count <= audio.Length; count += R / 4) live.Feed(count, LastVoice(count), Take);
        Check("pieces sent while talking: " + live.Pieces, live.Pieces == 3);
        var text = System.Threading.Tasks.Task.Run(() => live.Finish(audio, default)).GetAwaiter().GetResult();
        Check("joined in order, silent tail skipped: " + text, text == "Piece of 11.25 seconds. Piece of 12.00 seconds. Piece of 11.00 seconds.");

        var failing = new LiveStt(Settings.Current, (p, ct) => p.Length > R * 11.5 ? throw new IOException("down") : System.Threading.Tasks.Task.FromResult("ok"));
        for (int count = R / 4; count <= audio.Length; count += R / 4) failing.Feed(count, LastVoice(count), Take);
        Check("a failed piece falls back to the whole recording", System.Threading.Tasks.Task.Run(() => failing.Finish(audio, default)).GetAwaiter().GetResult() == null);
        Check("nothing sent early in a short recording", System.Threading.Tasks.Task.Run(() => new LiveStt(Settings.Current, Fake).Finish(audio[..(R * 5)], default)).GetAwaiter().GetResult() == null);

        // The upload itself: FLAC first, WAV when the service turns FLAC down.
        var seen = new List<string>();
        using var http = new System.Net.HttpListener();
        var port = 47000 + Environment.ProcessId % 1000;
        http.Prefixes.Add($"http://127.0.0.1:{port}/");
        http.Start();
        bool refuseFlac = false;
        _ = System.Threading.Tasks.Task.Run(async () =>
        {
            while (http.IsListening)
            {
                System.Net.HttpListenerContext c;
                try { c = await http.GetContextAsync(); } catch { return; }
                using var ms = new MemoryStream();
                await c.Request.InputStream.CopyToAsync(ms);
                var body = System.Text.Encoding.Latin1.GetString(ms.ToArray());
                var flac = body.Contains("audio.flac");
                var magic = body.Contains("\r\n\r\nfLaC") ? "fLaC" : body.Contains("\r\n\r\nRIFF") ? "RIFF" : "?";
                lock (seen) seen.Add((flac ? "flac" : "wav") + ":" + magic + ":" + ms.Length);
                var reply = flac && refuseFlac ? "{\"error\":{\"message\":\"unsupported file\"}}" : "{\"text\":\"hello there\"}";
                c.Response.StatusCode = flac && refuseFlac ? 400 : 200;
                var bytes = System.Text.Encoding.UTF8.GetBytes(reply);
                await c.Response.OutputStream.WriteAsync(bytes);
                c.Response.Close();
            }
        });
        var url = $"http://127.0.0.1:{port}/v1";
        var clip = audio[..(R * 12)];
        var t1 = System.Threading.Tasks.Task.Run(() => CloudStt.Transcribe(url, "test", "m", clip, "", true)).GetAwaiter().GetResult();
        Check("upload goes as FLAC: " + string.Join(" ", seen), t1 == "hello there" && seen.Count == 1 && seen[0].StartsWith("flac:fLaC"));
        var flacBytes = seen.Count > 0 ? seen[0].Split(':')[2] : "?";
        refuseFlac = true;
        seen.Clear();
        var t2 = System.Threading.Tasks.Task.Run(() => CloudStt.Transcribe(url, "test", "m", clip, "", true)).GetAwaiter().GetResult();
        Check("FLAC refused: sent again as WAV: " + string.Join(" ", seen), t2 == "hello there" && seen.Count == 2 && seen[1].StartsWith("wav:RIFF"));
        seen.Clear();
        _ = System.Threading.Tasks.Task.Run(() => CloudStt.Transcribe(url, "test", "m", clip, "", true)).GetAwaiter().GetResult();
        Check("and WAV straight away after that: " + string.Join(" ", seen), seen.Count == 1 && seen[0].StartsWith("wav"));
        lines.Add($"12 s clip: FLAC request {flacBytes} bytes, WAV request {(seen.Count > 0 ? seen[0].Split(':')[2] : "?")} bytes (a pure tone; real speech compresses less)");
        http.Stop();
        File.WriteAllLines(Path.Combine(dir, "live-checks.txt"), lines);
    }

    private static void AudioRoundTrip(string dir)
    {
        var probe = new float[Recorder.SampleRate * 2];
        for (int i = 0; i < probe.Length; i++) probe[i] = (float)(0.3 * Math.Sin(i * 2 * Math.PI * 440 / Recorder.SampleRate));
        AudioStore.Save(1, probe);
        var back = AudioStore.Load(1);
        float peak = 0;
        if (back != null) foreach (var v in back) peak = Math.Max(peak, Math.Abs(v));
        File.WriteAllText(Path.Combine(dir, "audio-roundtrip.txt"),
            $"{probe.Length} samples saved as {Path.GetExtension(AudioStore.FileFor(1))}, {back?.Length} read back, peak {peak:0.00}");
        AudioStore.Delete(1);
    }

    /** The pop-up menu (history's three dots, the app list on Style) in the dark theme. */
    private static void MenuShot(string dir)
    {
        C.Apply("dark");
        var menu = new System.Windows.Controls.ContextMenu { Resources = Ui.MenuStyles() };
        menu.Items.Add(new System.Windows.Controls.MenuItem { Header = "Copy original" });
        menu.Items.Add(new System.Windows.Controls.MenuItem { Header = "Delete" });
        menu.Items.Add(new System.Windows.Controls.MenuItem { Header = "Messages", IsCheckable = true, IsChecked = true });
        Layout(menu, 240, double.NaN);
        Save(menu, Path.Combine(dir, "menu.png"));
    }

    /** Every indicator style in each state, plus the ripple pill on the side edges. */
    private static void IndicatorShots(string dir)
    {
        C.Apply("dark");
        var row = new System.Windows.Controls.WrapPanel { Width = 900, Background = C.Hex("#1F2023") };
        void Add(string look, string dock, IndicatorView.Mode mode)
        {
            var v = new IndicatorView { Look = look, Dock = dock, Scale = 1.3, CurrentMode = mode, Level = () => 0.06f, Margin = new Thickness(12) };
            row.Children.Add(new System.Windows.Controls.Border { Child = v, BorderBrush = C.Hex("#141416"), BorderThickness = new Thickness(0, 0, 0, 0) });
        }
        foreach (var look in IndicatorView.Styles)
            foreach (var mode in new[] { IndicatorView.Mode.Idle, IndicatorView.Mode.Listening, IndicatorView.Mode.Working })
                Add(look, "bottom", mode);
        Add("ripple", "left", IndicatorView.Mode.Listening);
        Add("ripple", "right", IndicatorView.Mode.Listening);
        Add("bars", "left", IndicatorView.Mode.Listening);
        for (int i = 0; i < 20; i++)
        {
            foreach (var c in row.Children) ((FrameworkElement)((System.Windows.Controls.Border)c).Child).InvalidateVisual();
            Layout(row, 900, double.NaN);
        }
        Save(row, Path.Combine(dir, "indicators.png"));
    }

    private static void Page(string dir, string theme, string name, Action<MainWindow> setup, double width = 1100)
    {
        var w = new MainWindow();
        setup(w);
        var root = (FrameworkElement)w.Content;
        w.Content = null;
        root.LayoutTransform = null; // the window draws at 90%; screenshots are taken at full size
        Layout(root, width, 800);
        var h = Math.Max(800, w.ExtentHeight + 4);
        Layout(root, width, h);
        Save(root, Path.Combine(dir, $"{theme}-{name}.png"));
        w.Close();
    }

    private static void PillShots(string dir)
    {
        C.Apply("dark");
        var states = new (string Name, Bars.Mode Mode, Brush Brush, string Text)[]
        {
            ("pill-listening", Bars.Mode.Listening, C.Argb(Settings.Current.Accent), ""),
            ("pill-handsfree", Bars.Mode.Listening, C.Argb(Settings.Current.Accent), "Hands-free · Ctrl+Win to finish · Esc to cancel"),
            ("pill-working", Bars.Mode.Working, Brushes.White, ""),
            ("pill-message", Bars.Mode.Idle, Brushes.White, "The mic heard nothing. Check Settings › Privacy › Microphone."),
        };
        foreach (var (name, mode, brush, text) in states)
        {
            var pill = new Pill(() => 0.09f);
            var content = pill.Preview(mode, brush, text);
            pill.Content = null;
            var host = new System.Windows.Controls.Border { Background = C.Hex("#6E6E73"), Padding = new Thickness(10), Child = content };
            for (int i = 0; i < 25; i++)
            {
                Layout(host, double.NaN, double.NaN);
                pill.BarsView.InvalidateVisual();
                Pump();
            }
            Save(host, Path.Combine(dir, name + ".png"));
            pill.Close();
        }
    }

    private static void Layout(FrameworkElement e, double w, double h)
    {
        var size = new Size(double.IsNaN(w) ? double.PositiveInfinity : w, double.IsNaN(h) ? double.PositiveInfinity : h);
        e.Measure(size);
        var final = new Size(double.IsNaN(w) ? e.DesiredSize.Width : w, double.IsNaN(h) ? e.DesiredSize.Height : h);
        e.Arrange(new Rect(final));
        e.UpdateLayout();
        Pump();
    }

    private static void Pump()
    {
        var frame = new DispatcherFrame();
        Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background, () => frame.Continue = false);
        Dispatcher.PushFrame(frame);
    }

    private static void Save(FrameworkElement e, string path)
    {
        var bmp = new RenderTargetBitmap((int)Math.Ceiling(e.ActualWidth * Scale), (int)Math.Ceiling(e.ActualHeight * Scale), 96 * Scale, 96 * Scale, PixelFormats.Pbgra32);
        bmp.Render(e);
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var f = File.Create(path);
        enc.Save(f);
    }

    /** Made-up sample content so every screen has something on it. */
    private static void Seed()
    {
        var s = Settings.Current;
        s.Words.AddRange(new[] { "Kubernetes", "PostgreSQL", "Nguyen", "Tokalot", "Groq", "Wispr" });
        s.Snippets.Add(new Snippet("my email", "me@example.com"));
        s.Snippets.Add(new Snippet("home address", "123 Main St, Springfield"));
        s.Snippets.Add(new Snippet("meeting link", "Here's the link for our call: https://meet.example.com/abc-defg-hij"));
        s.SetKey("groq", "sample-key-for-screenshots");
        s.Save();

        var now = new DateTimeOffset(DateTime.Today.AddHours(23.5)); // late evening, so "hours ago" entries are still today
        void Add(TimeSpan ago, string text, string raw, string app, int secs, bool cleaned = true)
        {
            var t = now - ago;
            History.Add(new Entry
            {
                Id = t.ToUnixTimeMilliseconds(), Time = t.ToUnixTimeMilliseconds(), Text = text, Raw = raw, DurationMs = secs * 1000,
                Cleaned = cleaned, AppKey = "app:" + app.ToLowerInvariant(), AppLabel = app,
            });
            Usage.RecordDictation(TextTools.WordCount(text), false);
        }
        Add(TimeSpan.FromDays(1.2), "Running about 10 minutes late, save me a seat lol", "um running about ten minutes late uh save me a seat lol", "WhatsApp", 4);
        Add(TimeSpan.FromHours(5), "Hi Sam,\n\nThanks for sending the quote. Could we move the delivery to Thursday? Friday is booked.\n\nThanks,\nAlex", "hi sam thanks for sending the quote could we move the delivery to wednesday no wait thursday friday is booked thanks alex", "Outlook", 11);
        Add(TimeSpan.FromHours(2), "We have three options:\n1. We fix it ourselves\n2. We call the dealer\n3. We wait until spring", "we have three options option one we fix it ourselves option two we call the dealer option three we wait until spring", "Slack", 8);
        Add(TimeSpan.FromMinutes(20), "Add a retry to `fetchOrders()` (three attempts with backoff) and log the final error.", "add a retry to fetch orders three attempts with backoff and uh log the final error", "Claude", 7);
        Usage.RecordStt("GROQ", 30);
        Usage.RecordLlm("GROQ", 4200, 380);
        Usage.RecordEdits(5, 1);
    }
}
