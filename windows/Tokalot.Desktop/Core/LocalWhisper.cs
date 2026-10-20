using System;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Whisper.net;

namespace Tokalot.Desktop.Core;

/**
 * An on-device speech model to choose from. All are whisper.cpp files from one pinned commit, checked against
 * their published SHA-256, so a file can't change underneath us. Bigger is more accurate (most of all with
 * background noise) and slower. Same list as the Android app.
 */
public sealed record LocalModel(string Id, string File, long Size, string Sha256, string Label, string Note);

/** Owns the offline speech model files: where they live and how they're fetched (once). */
public static class ModelManager
{
    public static readonly LocalModel[] All =
    {
        new("base", "ggml-base.en-q5_1.bin", 59_721_011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
            "Standard (60 MB)", "Fast on any computer. Fine for clear speech in a quiet place."),
        new("small", "ggml-small.en-q5_1.bin", 190_098_681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
            "Better (190 MB)", "Noticeably fewer mistakes, more so with background noise. About 3x slower."),
        new("turbo", "ggml-large-v3-turbo-q5_0.bin", 574_041_195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
            "Best (574 MB)", "The same model as Groq Whisper, compressed. Close to cloud accuracy; a few seconds per dictation on a recent computer."),
    };

    // Pinned to one revision, so the files can't change underneath us.
    private const string UrlBase = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/";

    public static LocalModel Of(string? id) => All.FirstOrDefault(m => m.Id == id) ?? All[0];

    public static string PathOf(LocalModel m) => Path.Combine(Paths.Dir("models"), m.File);
    public static bool IsDownloaded(LocalModel m) => System.IO.File.Exists(PathOf(m)) && new FileInfo(PathOf(m)).Length == m.Size;

    /**
     * The model a dictation uses: the one picked in Settings if it's downloaded, otherwise the best one that is
     * (so picking a bigger model doesn't stop offline working while it downloads). Null if none is.
     */
    public static LocalModel? Active
    {
        get
        {
            var chosen = Of(Settings.Current.LocalModel);
            return IsDownloaded(chosen) ? chosen : All.LastOrDefault(IsDownloaded); // the list runs smallest to biggest
        }
    }

    public static bool IsReady => Active != null;

    public static void Delete(LocalModel m) { try { System.IO.File.Delete(PathOf(m)); } catch { } }

    /** Downloads the model picked in Settings. Returns null on success, or an error message. */
    public static Task<string?> Download(IProgress<int> progress) => Download(Of(Settings.Current.LocalModel), progress);

    /** Returns null on success, or an error message. */
    public static async Task<string?> Download(LocalModel m, IProgress<int> progress)
    {
        var target = PathOf(m);
        var tmp = target + ".part";
        // Give up when nothing arrives for 30 s, so a stalled download can be retried instead of hanging.
        using var stall = new CancellationTokenSource();
        var patience = TimeSpan.FromSeconds(30);
        try
        {
            stall.CancelAfter(patience);
            using var res = await Net.Http.GetAsync(UrlBase + m.File, HttpCompletionOption.ResponseHeadersRead, stall.Token);
            if (!res.IsSuccessStatusCode) return $"Server returned {(int)res.StatusCode}";
            var total = res.Content.Headers.ContentLength ?? m.Size;
            using var hash = System.Security.Cryptography.IncrementalHash.CreateHash(System.Security.Cryptography.HashAlgorithmName.SHA256);
            await using (var input = await res.Content.ReadAsStreamAsync(stall.Token))
            await using (var output = System.IO.File.Create(tmp))
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
                        var pct = (int)Math.Min(100, done * 100 / total);
                        if (pct != last) { last = pct; progress.Report(pct); }
                    }
                }
            }
            if (Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant() != m.Sha256)
            {
                try { System.IO.File.Delete(tmp); } catch { }
                return "The download was damaged. Try again.";
            }
            System.IO.File.Move(tmp, target, true);
            return null;
        }
        catch (OperationCanceledException)
        {
            try { System.IO.File.Delete(tmp); } catch { }
            return "The download stalled. Try again.";
        }
        catch (Exception e)
        {
            try { System.IO.File.Delete(tmp); } catch { }
            return e.Message;
        }
    }
}

/** On-device Whisper. Loaded on first use and freed after a minute idle, so it costs nothing while unused. */
public sealed class LocalWhisper : IDisposable
{
    private WhisperFactory? factory;
    private LocalModel? factoryModel; // which file factory was loaded from
    private readonly SemaphoreSlim gate = new(1, 1);
    private Timer? unloadTimer;

    /** The model the last transcription used, for the timings line. */
    public LocalModel? LastModel => factoryModel;

    public async Task<string> Transcribe(float[] samples, string hint, bool english, CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try
        {
            unloadTimer?.Dispose();
            var model = ModelManager.Active ?? throw new InvalidOperationException("The offline model isn't downloaded");
            // A different model was picked in Settings since the last one: load that one instead.
            if (factoryModel != model) { factory?.Dispose(); factory = null; }
            var path = ModelManager.PathOf(model);
            try { factory ??= WhisperFactory.FromPath(path); factoryModel = model; }
            catch (Exception e)
            {
                // A damaged model file would fail forever; remove it so Settings offers the download again.
                // A good file that won't load (out of memory, the speech engine itself missing) is kept:
                // downloading it again would fail the same way.
                bool damaged;
                try
                {
                    using var f = File.OpenRead(path);
                    damaged = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(f)).ToLowerInvariant() != model.Sha256;
                }
                catch { damaged = false; }
                if (!damaged) throw new InvalidOperationException("The offline model couldn't be loaded: " + e.Message);
                ModelManager.Delete(model);
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
        try { factory?.Dispose(); factory = null; factoryModel = null; }
        finally { gate.Release(); }
    }

    public void Dispose()
    {
        unloadTimer?.Dispose();
        factory?.Dispose();
    }
}
