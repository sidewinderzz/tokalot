using System;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Whisper.net;

namespace Tokalot.Desktop.Core;

/** The offline backup speech model: where it lives and how it's fetched (once). */
public static class ModelManager
{
    // Quantized base.en: ~60 MB, same model as the Android app.
    public const string Name = "ggml-base.en-q5_1.bin";
    // Pinned to one revision and checked against its published SHA-256, so the file can't change underneath us.
    private const string Url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/" + Name;
    private const string Sha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f";

    public static string FilePath => Path.Combine(Paths.Dir("models"), Name);
    public static bool IsReady => File.Exists(FilePath) && new FileInfo(FilePath).Length > 10_000_000;

    /** Returns null on success, or an error message. */
    public static async Task<string?> Download(IProgress<int> progress)
    {
        var tmp = FilePath + ".part";
        // Give up when nothing arrives for 30 s, so a stalled download can be retried instead of hanging.
        using var stall = new CancellationTokenSource();
        var patience = TimeSpan.FromSeconds(30);
        try
        {
            stall.CancelAfter(patience);
            using var res = await Net.Http.GetAsync(Url, HttpCompletionOption.ResponseHeadersRead, stall.Token);
            if (!res.IsSuccessStatusCode) return $"Server returned {(int)res.StatusCode}";
            var total = res.Content.Headers.ContentLength ?? 0;
            using var hash = System.Security.Cryptography.IncrementalHash.CreateHash(System.Security.Cryptography.HashAlgorithmName.SHA256);
            await using (var input = await res.Content.ReadAsStreamAsync(stall.Token))
            await using (var output = File.Create(tmp))
            {
                var buf = new byte[1 << 16];
                long done = 0;
                int last = -1, n;
                while ((n = await input.ReadAsync(buf, stall.Token)) > 0)
                {
                    stall.CancelAfter(patience);
                    hash.AppendData(buf, 0, n);
                    await output.WriteAsync(buf.AsMemory(0, n));
                    done += n;
                    if (total > 0)
                    {
                        var pct = (int)(done * 100 / total);
                        if (pct != last) { last = pct; progress.Report(pct); }
                    }
                }
            }
            if (Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant() != Sha256)
            {
                try { File.Delete(tmp); } catch { }
                return "The download was damaged. Try again.";
            }
            File.Move(tmp, FilePath, true);
            return null;
        }
        catch (OperationCanceledException)
        {
            try { File.Delete(tmp); } catch { }
            return "The download stalled. Try again.";
        }
        catch (Exception e)
        {
            try { File.Delete(tmp); } catch { }
            return e.Message;
        }
    }
}

/** On-device Whisper. Loaded on first use and freed after a minute idle, so it costs nothing while unused. */
public sealed class LocalWhisper : IDisposable
{
    private WhisperFactory? factory;
    private readonly SemaphoreSlim gate = new(1, 1);
    private Timer? unloadTimer;

    public async Task<string> Transcribe(float[] samples, string hint, bool english, CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try
        {
            unloadTimer?.Dispose();
            try { factory ??= WhisperFactory.FromPath(ModelManager.FilePath); }
            catch
            {
                // A damaged model file would fail forever; remove it so Settings offers the download again.
                try { File.Delete(ModelManager.FilePath); } catch { }
                throw new InvalidOperationException("The offline model was damaged and has been removed. Download it again in Settings.");
            }
            var builder = factory.CreateBuilder()
                .WithThreads(Math.Clamp(Environment.ProcessorCount / 2, 2, 8));
            builder = english ? builder.WithLanguage("en") : builder.WithLanguageDetection();
            if (!string.IsNullOrWhiteSpace(hint)) builder = builder.WithPrompt(hint);
            await using var processor = builder.Build();
            var sb = new StringBuilder();
            await foreach (var seg in processor.ProcessAsync(samples, ct)) sb.Append(seg.Text);
            return sb.ToString();
        }
        finally
        {
            unloadTimer = new Timer(_ => Unload(), null, TimeSpan.FromMinutes(1), Timeout.InfiniteTimeSpan);
            gate.Release();
        }
    }

    private void Unload()
    {
        if (!gate.Wait(0)) return;
        try { factory?.Dispose(); factory = null; }
        finally { gate.Release(); }
    }

    public void Dispose()
    {
        unloadTimer?.Dispose();
        factory?.Dispose();
    }
}
