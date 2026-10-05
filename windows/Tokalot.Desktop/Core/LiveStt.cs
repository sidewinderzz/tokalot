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

    /** Call a few times a second while recording. */
    public void Feed(Recorder r) => Feed(r.Count, r.LastVoiceSample, r.Snapshot);

    public void Feed(int count, int lastVoice, Func<int, int, float[]> snapshot)
    {
        int cut = NextCut(sent, count, lastVoice);
        if (cut < 0) return;
        var piece = snapshot(sent, cut);
        if (piece.Length == 0) return;
        sent += piece.Length;
        pieces.Add(Task.Run(() => One(piece)));
    }

    private async Task<string> One(float[] piece)
    {
        int voiced = Voiced(piece);
        if (voiced < 3) return ""; // silence makes the speech model invent words
        var raw = await transcribe(piece, cts.Token);
        var text = TextTools.StripNoise(raw);
        return voiced < 6 && TextTools.IsPhantom(text) ? "" : text;
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
            if (sent < all.Length)
            {
                var tail = all[sent..];
                pieces.Add(One(tail));
            }
            var texts = await Task.WhenAll(pieces);
            return string.Join(" ", texts.Where(t => t.Length > 0));
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch { return null; }
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
