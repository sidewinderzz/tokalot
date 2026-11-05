using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Tokalot.Desktop.Core;

/** One shared HttpClient: connections stay open (keep-alive), which is what makes warm-up useful. */
public static class Net
{
    public static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2),
        ConnectTimeout = TimeSpan.FromSeconds(5),
    })
    { Timeout = Timeout.InfiniteTimeSpan };

    static Net()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("Tokalot-Desktop/1.0");
    }

    /** ct cancels on the user's say-so; running past timeoutMs throws TimeoutException instead. */
    public static async Task<JsonNode> PostJson(string url, IDictionary<string, string> headers, JsonObject body, int timeoutMs = 15000,
        CancellationToken ct = default)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeoutMs);
        using var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        foreach (var (k, v) in headers) req.Headers.TryAddWithoutValidation(k, v);
        try
        {
            using var res = await Http.SendAsync(req, cts.Token);
            var text = await res.Content.ReadAsStringAsync(cts.Token);
            if (!res.IsSuccessStatusCode) throw new IOException($"HTTP {(int)res.StatusCode}: {ErrorMessage(text)}");
            return JsonNode.Parse(text) ?? throw new IOException("Empty response");
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new TimeoutException("Timed out"); }
    }

    /**
     * Tiny authenticated request so the TLS connection is already open when the real upload starts.
     * Returns how long it took in milliseconds (connecting included), or -1 if it failed: a rough reading of the link.
     */
    public static async Task<int> Warm(string url, IDictionary<string, string> headers)
    {
        var clock = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            using var cts = new CancellationTokenSource(4000);
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            foreach (var (k, v) in headers) req.Headers.TryAddWithoutValidation(k, v);
            using var res = await Http.SendAsync(req, cts.Token);
            await res.Content.ReadAsByteArrayAsync(cts.Token);
            return (int)clock.ElapsedMilliseconds;
        }
        catch { return -1; }
    }

    /** False when the computer has no network connection at all. */
    public static bool Online()
    {
        try { return System.Net.NetworkInformation.NetworkInterface.GetIsNetworkAvailable(); }
        catch { return true; }
    }

    /** Weak-signal mode: how long the cloud gets for the speech step before the on-device model takes over (5 s, plus a tenth of the audio, at most 20 s). */
    public static int WeakCloudMs(int sampleCount) => Math.Min(20_000, 5_000 + sampleCount / (Recorder.SampleRate / 100));

    /** Weak-signal mode: a warm-up (connecting included) slower than this means the link is too poor to wait on. */
    public const int WeakWarmMs = 2500;

    /** Pulls the human-readable message out of the usual API error shapes. */
    public static string ErrorMessage(string body)
    {
        try
        {
            var o = JsonNode.Parse(body);
            var e = o?["error"];
            if (e is JsonObject eo) return eo["message"]?.ToString() ?? body;
            if (e is JsonValue ev) return ev.ToString();
            return o?["message"]?.ToString() ?? Truncate(body);
        }
        catch { return Truncate(body); }
    }

    private static string Truncate(string s) => s.Length > 200 ? s[..200] : s;

    /** 16 kHz mono float samples -> 16-bit PCM WAV bytes. */
    public static byte[] Wav(float[] samples, int rate = Recorder.SampleRate)
    {
        using var ms = new MemoryStream(44 + samples.Length * 2);
        using var w = new BinaryWriter(ms);
        var dataLen = samples.Length * 2;
        w.Write(Encoding.ASCII.GetBytes("RIFF")); w.Write(36 + dataLen); w.Write(Encoding.ASCII.GetBytes("WAVE"));
        w.Write(Encoding.ASCII.GetBytes("fmt ")); w.Write(16); w.Write((short)1); w.Write((short)1);
        w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16);
        w.Write(Encoding.ASCII.GetBytes("data")); w.Write(dataLen);
        foreach (var s in samples) w.Write((short)(Math.Clamp(s, -1f, 1f) * 32767));
        w.Flush();
        return ms.ToArray();
    }
}

/** Cloud speech-to-text through the OpenAI-compatible endpoint (Groq and OpenAI both speak it). */
public static class CloudStt
{
    public static async Task<string> Transcribe(string baseUrl, string key, string model, float[] samples, string prompt, bool english,
        CancellationToken ct = default)
    {
        // Long recordings are bigger uploads and take longer to transcribe: allow half their length on top.
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(25000 + samples.Length / (Recorder.SampleRate / 500));
        samples = Loudness.ForSpeech(samples); // a quiet microphone's soft words are otherwise missed
        try
        {
            // FLAC is the same audio in about half the bytes. A service that won't take it gets WAV.
            if (flacRefused != baseUrl)
            {
                var (code, body) = await Send(baseUrl, key, model, Flac.Encode(samples), "audio/flac", "audio.flac", prompt, english, cts.Token);
                if (code is >= 200 and < 300) return Text(body);
                if (!FormatRefused(code, body)) throw new IOException($"HTTP {code}: {Net.ErrorMessage(body)}");
                flacRefused = baseUrl;
            }
            var (c, text) = await Send(baseUrl, key, model, Net.Wav(samples), "audio/wav", "audio.wav", prompt, english, cts.Token);
            if (c is < 200 or >= 300)
            {
                // The WAV was refused too, so the format wasn't the problem: try FLAC again next time.
                if (flacRefused == baseUrl && c is 400 or 415) flacRefused = null;
                throw new IOException($"HTTP {c}: {Net.ErrorMessage(text)}");
            }
            return Text(text);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new TimeoutException("Timed out"); }
    }

    private static volatile string? flacRefused; // the service that last turned FLAC down

    /** Whisper's own cut-off for "this was silence" is 0.6; a stock phrase on top of it needs less. */
    public const double NoSpeech = 0.5;

    /**
     * The transcript, or "" when it's one of the phrases Whisper invents for noise ("Thank you.") and Whisper
     * itself rated every part of the audio as probably not speech. A "thank you" that was said comes through.
     */
    public static string Text(string body)
    {
        var o = JsonNode.Parse(body);
        var text = o?["text"]?.ToString() ?? "";
        if (o?["segments"] is not JsonArray segs || segs.Count == 0 || !TextTools.IsPhantom(text)) return text;
        try { return segs.All(seg => ((double?)seg?["no_speech_prob"] ?? 0) >= NoSpeech) ? "" : text; }
        catch (Exception) { return text; } // an odd reply shape: keep what was heard
    }

    /** A 400 can be about anything (a retired model, a blocked account); only one about the audio file means "send WAV". */
    private static bool FormatRefused(int code, string body)
    {
        if (code == 415) return true;
        if (code != 400) return false;
        var m = body.ToLowerInvariant();
        return m.Contains("file") || m.Contains("format") || m.Contains("audio") || m.Contains("decod") || m.Contains("media");
    }

    private static async Task<(int Code, string Body)> Send(string baseUrl, string key, string model, byte[] audio, string mime, string name,
        string prompt, bool english, CancellationToken ct)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(model), "model");
        if (english) form.Add(new StringContent("en"), "language"); // omitted = the model detects it
        // Whisper models also say how likely each stretch was to be silence, which tells a stray "Thank you." from a real one.
        form.Add(new StringContent(model.StartsWith("whisper") ? "verbose_json" : "json"), "response_format");
        if (!string.IsNullOrWhiteSpace(prompt)) form.Add(new StringContent(prompt), "prompt");
        var file = new ByteArrayContent(audio);
        file.Headers.ContentType = new MediaTypeHeaderValue(mime);
        form.Add(file, "file", name);

        using var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/audio/transcriptions") { Content = form };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        using var res = await Net.Http.SendAsync(req, ct);
        return ((int)res.StatusCode, await res.Content.ReadAsStringAsync(ct));
    }
}

/**
 * Brings a quiet recording up to a normal speaking level before speech recognition. Some laptop microphones
 * (seen on a Windows on ARM laptop) record speech 15-18 dB below normal, peaking around a twentieth of full
 * scale. Whisper then misses soft words or whole soft endings. The saved recording is left as it was.
 */
public static class Loudness
{
    /** The level speech is brought up to: the loud parts of it (RMS of the top 5% of 50 ms stretches). */
    public const float Target = 0.1f;
    /** Never more than this (18 dB), so a recording of nothing but room noise isn't made into a roar. */
    public const float MaxGain = 8f;
    /** The loudest 50 ms stretch is never raised past this RMS. */
    public const float Loudest = 0.25f;

    /** How much ForSpeech would raise a by (1 = left alone). */
    public static float Gain(float[] a)
    {
        const int win = 800; // 50 ms
        int n = a.Length / win;
        if (n < 4) return 1f;
        var rms = new float[n];
        for (int w = 0; w < n; w++)
        {
            double sum = 0;
            for (int i = w * win; i < (w + 1) * win; i++) sum += a[i] * a[i];
            rms[w] = (float)Math.Sqrt(sum / win);
        }
        Array.Sort(rms);
        var loud = rms[Math.Min(n - 1, n * 95 / 100)];
        if (loud <= 0.0005f) return 1f; // silence: nothing to bring up
        // A short command in a long recording: the top 5% can be room noise. Never push the loudest stretch past
        // Loudest, so speech that is already at a normal level is never raised into clipping.
        var g = Math.Min(Math.Min(MaxGain, Target / loud), Loudest / rms[n - 1]);
        return g < 1.25f ? 1f : g; // already at a normal level
    }

    /** a, raised to a normal speaking level when it is quiet (a new array), or a itself. */
    public static float[] ForSpeech(float[] a)
    {
        var g = Gain(a);
        if (g == 1f) return a;
        var o = new float[a.Length];
        for (int i = 0; i < a.Length; i++) o[i] = Math.Clamp(a[i] * g, -0.99f, 0.99f);
        return o;
    }
}
