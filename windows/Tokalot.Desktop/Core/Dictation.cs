using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Tokalot.Desktop.Core;

/**
 * The whole speech -> text pipeline:
 *   1. speech-to-text: chosen cloud provider, then any other keyed cloud provider, then on-device
 *   2. snippets swapped for placeholders
 *   3. AI cleanup with the chosen provider, then one keyed backup, then basic cleanup
 *   4. snippets restored, saved to history, usage counted, recording kept
 */
public sealed class Dictation : IDisposable
{
    private readonly LocalWhisper local = new();

    /** Plain: the user's own wording (fillers out, no AI), set only when Polish reworded it, so it can be put back. */
    public sealed record Outcome(string Text, string? Warning, long? EntryId, string? Plain = null);

    /** Opens connections to the chosen providers while the user talks. */
    public void WarmUp()
    {
        var s = Settings.Current;
        _ = Task.Run(async () =>
        {
            if (s.CloudSttReady)
                await Net.Warm(s.SttOption.BaseUrl + "/models",
                    new Dictionary<string, string> { ["Authorization"] = "Bearer " + s.Key(s.SttOption.Service) });
            if (s.CleanupReady && !(s.CloudSttReady && s.SttOption.BaseUrl == s.CleanupOption.BaseUrl))
            {
                var c = s.CleanupOption;
                var h = c.Id == "CLAUDE"
                    ? new Dictionary<string, string> { ["x-api-key"] = s.Key(c.Service), ["anthropic-version"] = "2023-06-01" }
                    : new Dictionary<string, string> { ["Authorization"] = "Bearer " + s.Key(c.Service) };
                await Net.Warm(c.BaseUrl + "/models", h);
            }
        });
    }

    /** The dictionary as a hint for the speech model. Its prompt holds ~224 tokens; this stays well under that (newest words win). */
    public static string Hint(Settings s)
    {
        var hint = new StringBuilder();
        foreach (var w in Enumerable.Reverse(s.Words))
        {
            if (hint.Length + w.Length + 2 > 600) break;
            if (hint.Length > 0) hint.Append(", ");
            hint.Append(w);
        }
        return hint.ToString();
    }

    /** For the progress line: 0 while the speech is being recognised, 1 during cleanup, 2 when the text is ready. */
    public static volatile int Stage;
    /** How long the recognised text is, once Stage is 1 (the cleanup's wait grows with it). */
    public static volatile int StageChars;
    /**
     * How long things have been taking since Tokalot started, for the progress line's estimate: speech-to-text
     * in milliseconds per second of audio, cleanup in milliseconds per character. Running averages.
     */
    public static volatile float SttRate = 150f, CleanRate = 6f;

    /** How the last dictation's wait was spent, for the log. */
    public static string LastTiming { get; private set; } = "";

    private static readonly List<string> recent = new();
    /** The last 20 dictations' timings, newest first, for the Speed section in Settings. */
    public static string[] Recent { get { lock (recent) return recent.ToArray(); } }

    /** sparse: barely any sound was heard, so a stock Whisper phrase ("Thank you.") is treated as silence. */
    /** ct: the user cancelled. entryId: re-transcribing a saved recording, so update that history entry instead of adding one. */
    /** live: the pieces already sent off while the user was talking, if any. */
    /** user: cancelled by the user only (ct also carries the time limit). Running out of time during cleanup falls back to basic cleanup. */
    public async Task<Outcome> Process(float[] samples, ActiveApp? app, bool sparse = false, CancellationToken ct = default, long? entryId = null,
        LiveStt? live = null, CancellationToken? user = null)
    {
        var userCt = user ?? ct;
        var s = Settings.Current;
        var warnings = new List<string>();
        var seconds = samples.Length / (double)Recorder.SampleRate;
        var category = AppDetect.Categorize(app, s);
        var hint = Hint(s);
        var clock = System.Diagnostics.Stopwatch.StartNew();
        LastTiming = "";
        Stage = 0;

        // 1. Speech to text
        string? raw = null;
        var usedLocal = false;
        var inPieces = false;
        if (live != null)
        {
            raw = await live.Finish(samples, ct);
            inPieces = raw != null;
            if (inPieces) Usage.RecordStt(s.Stt, seconds);
        }
        foreach (var c in raw != null ? Array.Empty<SttOption>() : SttOrder(s))
        {
            try
            {
                raw = await CloudStt.Transcribe(c.BaseUrl, s.Key(c.Service), s.SttModel(c), samples, hint, !s.AutoLanguage, ct);
                Usage.RecordStt(c.Id, seconds);
                if (c.Id != s.Stt) warnings.Add($"{s.SttOption.Label} unavailable, used {c.Label}");
                break;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e)
            {
                if (warnings.Count == 0) warnings.Add($"{c.Label} failed: {Short(e.Message)}");
            }
        }
        if (raw == null)
        {
            if (!ModelManager.IsReady)
                throw new InvalidOperationException(warnings.Count == 0
                    ? "No speech model: add a Groq key or download the offline model in Settings"
                    : warnings[0] + ", and the offline model isn't downloaded");
            raw = await local.Transcribe(samples, hint, !s.AutoLanguage, ct);
            usedLocal = true;
        }
        var sttMs = clock.ElapsedMilliseconds;
        var baseText = TextTools.StripNoise(raw);
        StageChars = baseText.Length;
        Stage = 1;
        if (baseText.Length == 0 || (sparse && TextTools.IsPhantom(baseText))) return new Outcome("", warnings.FirstOrDefault(), null);

        // 2-4. Snippets + cleanup
        var (protectedText, map) = TextTools.Protect(baseText, s.Snippets);
        var cleaned = false;
        string? final = null;
        string? cleanupErr = null;
        // Quick mode: a short dictation with nothing for the AI to fix skips it and is tidied here, at once.
        var quick = s.QuickSkip && !s.Polish && !inPieces && s.Cleanup != "OFF"
            && TextTools.NothingToFix(baseText, map.Count > 0, s.StyleFor(category).Id, category.Id, s.CustomInstructions);
        foreach (var c in quick ? Array.Empty<CleanupOption>() : CleanupOrder(s))
        {
            try
            {
                var r = await Cleanup.Run(s, c, protectedText, map.Count > 0, category, app?.Label, ct, inPieces);
                Usage.RecordLlm(c.Id, r.InTokens, r.OutTokens);
                cleaned = true;
                final = TextTools.Restore(r.Text, map);
                if (c.Id != s.Cleanup) warnings.Add($"{s.CleanupOption.Label} unavailable, cleaned with {c.Label}");
                break;
            }
            catch (OperationCanceledException) when (userCt.IsCancellationRequested) { throw; }
            catch (Exception e)
            {
                cleanupErr ??= ct.IsCancellationRequested ? "Cleanup took too long; basic cleanup used" : "Cleanup failed: " + Short(e.Message);
                if (ct.IsCancellationRequested) break; // out of time: the text is in hand, don't try a second service
            }
        }
        if (!cleaned && cleanupErr != null) warnings.Add(cleanupErr);
        final ??= TextTools.Restore(TextTools.BasicClean(protectedText), map);

        var inv = System.Globalization.CultureInfo.InvariantCulture;
        var allMs = clock.ElapsedMilliseconds;
        Stage = 2;
        if (!usedLocal && !inPieces) SttRate = SttRate * 0.6f + 0.4f * (float)(sttMs / Math.Max(seconds, 3));
        if (cleaned) CleanRate = CleanRate * 0.6f + 0.4f * ((allMs - sttMs) / (float)Math.Max(baseText.Length, 40));
        LastTiming = string.Format(inv, "{0:0.0} s spoken · speech {1:0.00} s", seconds, sttMs / 1000.0) +
            (inPieces ? $" ({live!.Pieces} pieces)" : usedLocal ? " (on device)" : "") +
            (quick ? " · cleanup skipped, nothing to fix"
                : cleaned ? string.Format(inv, " · cleanup {0:0.00} s", (allMs - sttMs) / 1000.0)
                : s.Cleanup == "OFF" ? " · cleanup off" : string.Format(inv, " · cleanup failed after {0:0.00} s", (allMs - sttMs) / 1000.0)) +
            string.Format(inv, " · total {0:0.00} s", allMs / 1000.0);
        lock (recent)
        {
            recent.Insert(0, DateTime.Now.ToString("HH:mm:ss", inv) + "  " + LastTiming);
            if (recent.Count > 20) recent.RemoveAt(20);
        }
        userCt.ThrowIfCancellationRequested();
        var now = entryId ?? DateTimeOffset.Now.ToUnixTimeMilliseconds();
        // A history file that's briefly locked must not stop the text from being pasted.
        try
        {
            var entry = new Entry
            {
                Id = now, Time = now, Text = final, Raw = baseText, DurationMs = (long)(seconds * 1000),
                Cleaned = cleaned, AppKey = app?.Key ?? "", AppLabel = app?.Label ?? "",
            };
            if (entryId != null) History.Replace(entry); else History.Add(entry);
        }
        catch { }
        Usage.RecordDictation(TextTools.WordCount(final), usedLocal);
        Usage.RecordEdits(AppDetect.FillerCount(baseText), cleaned ? AppDetect.CorrectionCount(baseText) : 0);
        string? plain = null;
        if (cleaned && s.Polish)
        {
            plain = TextTools.Restore(TextTools.BasicClean(protectedText), map);
            if (plain == final) plain = null;
        }
        return new Outcome(final, warnings.FirstOrDefault(), now, plain);
    }

    /** Keeps the audio for playback (after the text is delivered). Failed runs still get an entry. */
    public Task KeepAudio(float[] samples, long? entryId, string? error, ActiveApp? app, bool cancelled = false)
    {
        var s = Settings.Current;
        return Task.Run(() =>
        {
            var id = entryId;
            var pending = false;
            if (id == null && error != null)
            {
                var now = DateTimeOffset.Now.ToUnixTimeMilliseconds();
                History.Add(new Entry
                {
                    Id = now, Time = now, Pending = true,
                    Text = cancelled ? "Cancelled before it was transcribed." : "Transcription failed: " + error,
                    DurationMs = samples.Length * 1000L / Recorder.SampleRate, AppKey = app?.Key ?? "", AppLabel = app?.Label ?? "",
                });
                id = now;
                pending = true;
            }
            // A recording that still needs transcribing is kept even when "Don't save audio" is on, so it can be retried.
            if (id != null && (s.AudioKeepDays > 0 || pending))
            {
                AudioStore.Save(id.Value, samples);
                History.NotifyChanged();
            }
            AudioStore.Prune(s.AudioKeepDays);
        });
    }

    private static string Short(string m) => m.Length > 80 ? m[..80] : m;

    /** Chosen cloud STT first (if keyed), then the other keyed cloud option. Empty = on-device. */
    private static IEnumerable<SttOption> SttOrder(Settings s)
    {
        if (s.Stt == "LOCAL") return Array.Empty<SttOption>();
        return new[] { s.SttOption }.Concat(Catalog.Stt.Where(o => o.Id != s.Stt))
            .Where(o => o.Id != "LOCAL" && s.Key(o.Service).Length > 0);
    }

    /** Chosen cleanup first, then one backup provider with a key. Empty = basic cleanup. */
    private static IEnumerable<CleanupOption> CleanupOrder(Settings s)
    {
        if (s.Cleanup == "OFF") return Array.Empty<CleanupOption>();
        return new[] { s.CleanupOption }.Concat(Catalog.Cleanup.Where(o => o.Id != s.Cleanup))
            .Where(o => o.Id != "OFF" && s.Key(o.Service).Length > 0).Take(2);
    }

    public void Dispose() => local.Dispose();
}
