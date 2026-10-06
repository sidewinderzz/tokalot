using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Tokalot.Desktop.Core;

/**
 * Transcribes a long dictation while it is still being spoken. Each time the speaker pauses, the part
 * said so far is sent to the speech service in the background; when they stop, only the last few seconds
 * are left to do, so the wait no longer grows with the length of the dictation. Nothing is shown or pasted
 * early: the pieces are joined and cleaned up once, at the end, exactly like a whole recording.
 * If any piece fails, Finish() returns null and the whole recording is transcribed the usual way.
 */
public sealed class LiveStt
{
    /** A piece is at least this long. Groq bills every request as 10 s or more, so shorter pieces would cost extra. */
    public const int MinPiece = Recorder.SampleRate * 10;
    /** How long the speaker must have been quiet before the audio is cut there. */
    public const int Pause = Recorder.SampleRate / 2;
    public const string CleanupNote =
        "Note: the transcript was recognized in pieces, split where the speaker paused. A full stop followed by a capital " +
        "letter may therefore fall in the middle of a sentence. Where the sentence clearly carries on, join the pieces and " +
        "fix the punctuation and capitalization.";

    private readonly Settings s;
    private readonly string hint;
    private readonly List<Task<string>> pieces = new();
    private readonly CancellationTokenSource cts = new();
    private int sent; // samples already handed to a piece

    public int Pieces => pieces.Count;

    /**
     * Where speech was last heard in the finished recording (a sample index), when the recorder knows.
     * If that is before the last cut, nothing was said after it and the tail isn't sent at all.
     */
    public int LastVoice { get; set; } = -1;

    /** Needs a cloud speech service, and AI cleanup to smooth the joins between pieces. */
    public static bool Usable(Settings s) => s.LiveStt && s.CloudSttReady && s.CleanupReady;

    private readonly Func<float[], CancellationToken, Task<string>> transcribe;

    /** transcribe: stands in for the speech service in the app's self-checks. */
    public LiveStt(Settings s, Func<float[], CancellationToken, Task<string>>? transcribe = null)
    {
        this.s = s;
        hint = Dictation.Hint(s);
        var c = s.SttOption;
        this.transcribe = transcribe ?? ((piece, ct) =>
            CloudStt.Transcribe(c.BaseUrl, s.Key(c.Service), s.SttModel(c), piece, hint, !s.AutoLanguage, ct));
    }

    /** Where to cut next (a sample index in the middle of the current pause), or -1 for "not yet". */
    public static int NextCut(int sent, int count, int lastVoice)
    {
        if (count - lastVoice < Pause) return -1;       // still talking
        if (lastVoice - sent < MinPiece) return -1;     // not enough said since the last cut
        return lastVoice + Pause / 2;
    }

    // In a noisy place the microphone never reads as "quiet", so the pause NextCut waits for never comes and
    // a long dictation went up in one go at the end. After this much unsent audio, a dip between words is
    // enough; after ForceHard, the quietest moment is used whatever it is.
    public const int ForceAfter = Recorder.SampleRate * 15;
    public const int ForceHard = Recorder.SampleRate * 25;
    /** How far back from "now" the quietest moment is looked for. */
    public const int Search = Recorder.SampleRate * 6;

    /**
     * The quietest quarter-second in a: its centre (an index into a), and whether it is a real dip
     * (under half the typical loudness of a), such as the gap between two words or a breath.
     */
    public static (int At, bool Dip) Quietest(float[] a)
    {
        const int win = Recorder.SampleRate / 20; // 50 ms
        int n = a.Length / win;
        if (n < 6) return (a.Length / 2, false);
        var rms = new float[n];
        for (int w = 0; w < n; w++)
        {
            double sum = 0;
            for (int i = w * win; i < (w + 1) * win; i++) sum += a[i] * a[i];
            rms[w] = (float)Math.Sqrt(sum / win);
        }
        int best = 0;
        float bestLevel = float.MaxValue;
        for (int w = 0; w <= n - 5; w++)
        {
            float level = (rms[w] + rms[w + 1] + rms[w + 2] + rms[w + 3] + rms[w + 4]) / 5f;
            if (level < bestLevel) { bestLevel = level; best = w; }
        }
        var sorted = (float[])rms.Clone();
        Array.Sort(sorted);
        return (best * win + win * 5 / 2, bestLevel < sorted[n / 2] * 0.5f);
    }

    private int lookedAt; // where the recording had got to when a noisy cut was last considered

    /** See ForceAfter. Looked into once a second at most: it reads six seconds of audio. */
    private int NoisyCut(int count, Func<int, int, float[]> snapshot)
    {
        if (count - sent < ForceAfter || count - lookedAt < Recorder.SampleRate) return -1;
        lookedAt = count;
        int from = count - Search;
        var (at, dip) = Quietest(snapshot(from, count - Recorder.SampleRate / 4));
        return dip || count - sent >= ForceHard ? from + at : -1;
    }

    /** Call a few times a second while recording. */
    public void Feed(Recorder r) => Feed(r.Count, r.LastVoiceSample, r.Snapshot);

    public void Feed(int count, int lastVoice, Func<int, int, float[]> snapshot)
    {
        // A piece already failed: the whole recording will be transcribed at the end, so more pieces are wasted uploads.
        if (pieces.Exists(p => p.IsFaulted || p.IsCanceled)) return;
        int cut = NextCut(sent, count, lastVoice);
        if (cut < 0) cut = NoisyCut(count, snapshot);
        if (cut < 0) return;
        var piece = snapshot(sent, cut);
        if (piece.Length == 0) return;
        sent += piece.Length;
        pieces.Add(Task.Run(() => One(piece)));
    }

    /**
     * tail: what was recorded after the last cut. That can be nothing but room noise and the key click,
     * which the speech model turns into "Thank you.", so it needs half a second of sound to be sent and a
     * stock phrase from it is dropped. Earlier pieces were cut because speech was heard in them.
     */
    private async Task<string> One(float[] piece, bool tail = false)
    {
        if (tail && Voiced(piece) < 10) return "";
        string raw;
        try { raw = await transcribe(piece, cts.Token); }
        // One more try for a passing error (a dropped connection, a busy server). A timeout has cost enough already.
        catch (Exception e) when (e is not OperationCanceledException and not TimeoutException && !cts.IsCancellationRequested)
        {
            raw = await transcribe(piece, cts.Token);
        }
        var text = TextTools.StripNoise(raw);
        return tail && TextTools.IsPhantom(text) ? "" : text;
    }

    /**
     * all: the finished recording. Transcribes what's left after the last cut and returns the whole text,
     * or null when nothing was sent early or a piece failed (the caller then transcribes the recording whole).
     */
    public async Task<string?> Finish(float[] all, CancellationToken ct)
    {
        if (pieces.Count == 0) return null;
        using var reg = ct.Register(cts.Cancel);
        try
        {
            // A piece has already failed: don't make the user wait for the tail before starting over.
            if (pieces.Exists(p => p.IsFaulted || p.IsCanceled)) { Cancel(); return null; }
            if (sent < all.Length && (LastVoice < 0 || LastVoice > sent))
            {
                var tail = all[sent..];
                pieces.Add(One(tail, tail: true));
            }
            var texts = await Task.WhenAll(pieces);
            return string.Join(" ", texts.Where(t => t.Length > 0));
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch
        {
            Cancel(); // stop the pieces still on their way
            return null;
        }
    }

    /** The recording was thrown away: stop the uploads. */
    public void Cancel() { try { cts.Cancel(); } catch { } }

    /** How many 50 ms stretches have sound in them. */
    public static int Voiced(float[] a)
    {
        const int win = Recorder.SampleRate / 20;
        int n = 0;
        for (int at = 0; at + win <= a.Length; at += win)
        {
            double sum = 0;
            for (int i = at; i < at + win; i++) sum += a[i] * a[i];
            if (Math.Sqrt(sum / win) > 0.008) n++;
        }
        return n;
    }
}
