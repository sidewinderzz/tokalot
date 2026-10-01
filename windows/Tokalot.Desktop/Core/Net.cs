using System;
using System.Collections.Generic;
using System.IO;
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

    public static async Task<JsonNode> PostJson(string url, IDictionary<string, string> headers, JsonObject body, int timeoutMs = 15000)
    {
        using var cts = new CancellationTokenSource(timeoutMs);
        using var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        foreach (var (k, v) in headers) req.Headers.TryAddWithoutValidation(k, v);
        using var res = await Http.SendAsync(req, cts.Token);
        var text = await res.Content.ReadAsStringAsync(cts.Token);
        if (!res.IsSuccessStatusCode) throw new IOException($"HTTP {(int)res.StatusCode}: {ErrorMessage(text)}");
        return JsonNode.Parse(text) ?? throw new IOException("Empty response");
    }

    /** Tiny authenticated request so the TLS connection is already open when the real upload starts. */
    public static async Task Warm(string url, IDictionary<string, string> headers)
    {
        try
        {
            using var cts = new CancellationTokenSource(4000);
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            foreach (var (k, v) in headers) req.Headers.TryAddWithoutValidation(k, v);
            using var res = await Http.SendAsync(req, cts.Token);
            await res.Content.ReadAsByteArrayAsync(cts.Token);
        }
        catch { }
    }

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
    public static async Task<string> Transcribe(string baseUrl, string key, string model, float[] samples, string prompt, bool english)
    {
        using var cts = new CancellationTokenSource(25000);
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(model), "model");
        if (english) form.Add(new StringContent("en"), "language"); // omitted = the model detects it
        form.Add(new StringContent("json"), "response_format");
        if (!string.IsNullOrWhiteSpace(prompt)) form.Add(new StringContent(prompt), "prompt");
        var file = new ByteArrayContent(Net.Wav(samples));
        file.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
        form.Add(file, "file", "audio.wav");

        using var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/audio/transcriptions") { Content = form };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        using var res = await Net.Http.SendAsync(req, cts.Token);
        var text = await res.Content.ReadAsStringAsync(cts.Token);
        if (!res.IsSuccessStatusCode) throw new IOException($"HTTP {(int)res.StatusCode}: {Net.ErrorMessage(text)}");
        return JsonNode.Parse(text)?["text"]?.ToString() ?? "";
    }
}
