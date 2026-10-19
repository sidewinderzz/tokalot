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

/**
 * Recordings added to Notes (a WhatsApp voice message saved to the PC, say) and what a note can carry besides its
 * text: the recording, the time of every word, its summaries and its index. The same layout as the Android app:
 * each such note has a folder, notes-media/<note id>/, holding info.json and, for a recording, audio.<ext>. A
 * recording gets its folder first and becomes a note once it's transcribed, so one that fails stays on the Notes
 * page with Try again. Only the note's text syncs; the folder stays on this computer.
 */
public static class NoteImport
{
    public const string DirName = "notes-media";
    private static readonly object Gate = new();
    private static readonly HashSet<long> Running = new();

    /** Something changed (a recording finished or failed). Raised on a worker thread. */
    public static event Action? Changed;

    public sealed class Info
    {
        public long Id { get; init; }
        public JsonObject O { get; init; } = new();
        public string Status => (string?)O["status"] ?? ""; // pending, failed, done; "" for a text note's summaries only
        public string Error => (string?)O["error"] ?? "";
        public string? Audio => (string?)O["audio"] is { Length: > 0 } a ? a : null;
        public string Name => (string?)O["name"] ?? "";
        public double Duration => (double?)O["duration"] ?? 0;
        public List<NoteMedia.Segment> Segments => (O["segments"] as JsonArray)?.Select(s =>
            new NoteMedia.Segment((double)s![0]!, (double)s[1]!, (string)s[2]!)).ToList() ?? new();
        public string? Summary(int level) => (string?)O["summaries"]?[level.ToString()] is { Length: > 0 } s ? s : null;
        public List<NoteMedia.Word> Words => (O["words"] as JsonArray)?.Select(w =>
            new NoteMedia.Word((string)w![0]!, (double)w[1]!, (double)w[2]!)).ToList() ?? new();
        /** The app a shared recording came from ("WhatsApp"), if known. */
        public string? Source => (string?)O["source"] is { Length: > 0 } src ? src : null;
        /** The stretches with no voice, found once and kept (see NoteMedia.SilenceCuts). */
        public List<NoteMedia.Cut>? Silence => (O["silence"] as JsonArray)?.Select(c => new NoteMedia.Cut((double)c![0]!, (double)c[1]!)).ToList();
        public List<NoteMedia.Point>? Index => (O["index"] as JsonArray)?.Select(p =>
            new NoteMedia.Point((double)p![0]!, (string)p[1]!, (string)p[2]!)).ToList();
    }

    private static string Root => Paths.Dir(DirName);
    private static string Folder(long id) => Path.Combine(Root, id.ToString());
    private static string InfoPath(long id) => Path.Combine(Folder(id), "info.json");

    public static Info? Get(long id)
    {
        lock (Gate)
        {
            try { return File.Exists(InfoPath(id)) ? new Info { Id = id, O = JsonNode.Parse(File.ReadAllText(InfoPath(id)))!.AsObject() } : null; }
            catch { return null; }
        }
    }

    public static string? AudioFile(long id) =>
        Get(id)?.Audio is { } a && File.Exists(Path.Combine(Folder(id), a)) ? Path.Combine(Folder(id), a) : null;

    /** Changes a note's info.json (made if it isn't there yet) through a side file, so a crash can't half-write it. */
    public static void Update(long id, Action<JsonObject> change)
    {
        lock (Gate)
        {
            Directory.CreateDirectory(Folder(id));
            JsonObject o;
            try { o = File.Exists(InfoPath(id)) ? JsonNode.Parse(File.ReadAllText(InfoPath(id)))!.AsObject() : new(); }
            catch { o = new(); }
            change(o);
            var tmp = InfoPath(id) + ".tmp";
            File.WriteAllText(tmp, o.ToJsonString());
            File.Move(tmp, InfoPath(id), true);
        }
    }

    public static void DeleteMedia(long id)
    {
        try { if (Directory.Exists(Folder(id))) Directory.Delete(Folder(id), true); } catch { }
    }

    private static IEnumerable<long> Ids() =>
        Directory.EnumerateDirectories(Root).Select(d => long.TryParse(Path.GetFileName(d), out var v) ? v : 0).Where(v => v > 0);

    /** Recordings that aren't notes yet: being transcribed, or failed. Newest first. */
    public static List<Info> Waiting() =>
        Ids().Select(Get).Where(i => i is { Status: "pending" or "failed" }).Select(i => i!).OrderByDescending(i => i.Id).ToList();

    public static bool IsRunning(long id) { lock (Running) return Running.Contains(id); }

    /** Removes the folders of notes deleted on another device. Ones still waiting to become a note are kept. */
    public static void Prune(ISet<long> noteIds)
    {
        foreach (var id in Ids().ToList())
        {
            if (noteIds.Contains(id) || IsRunning(id)) continue;
            if (Get(id)?.Status is "pending" or "failed") continue;
            DeleteMedia(id);
        }
    }

    /** File types the transcription services take. WhatsApp's .opus is Ogg inside. */
    public static readonly string[] Extensions = { ".ogg", ".opus", ".oga", ".m4a", ".mp3", ".mp4", ".wav", ".webm", ".flac", ".aac", ".mpeg", ".mpga" };

    private static string Mime(string ext) => ext switch
    {
        "ogg" => "audio/ogg", "m4a" or "mp4" => "audio/mp4", "mp3" or "mpeg" or "mpga" => "audio/mpeg",
        "wav" => "audio/wav", "webm" => "audio/webm", "flac" => "audio/flac", _ => "application/octet-stream",
    };

    /** Copies a recording in and starts transcribing it in the background. False if it couldn't be read. */
    public static bool AddRecording(Settings s, string path)
    {
        long id;
        string folder;
        lock (Gate)
        {
            var taken = Notes.All().Select(n => n.Id).ToHashSet();
            id = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            while (taken.Contains(id) || Directory.Exists(Folder(id))) id++;
            folder = Folder(id);
            Directory.CreateDirectory(folder);
        }
        var ext = Path.GetExtension(path).TrimStart('.').ToLowerInvariant() switch
        {
            "opus" or "oga" => "ogg", "aac" => "m4a", var e when e.Length > 0 => e, _ => "ogg",
        };
        var file = "audio." + ext;
        try
        {
            File.Copy(path, Path.Combine(folder, file));
            if (new FileInfo(Path.Combine(folder, file)).Length == 0) throw new IOException("empty");
        }
        catch { DeleteMedia(id); return false; }
        Update(id, o => { o["status"] = "pending"; o["audio"] = file; o["name"] = Path.GetFileName(path); });
        _ = Transcribe(s, id);
        return true;
    }

    /** Transcribes a waiting recording; on success it becomes a note. Also the Try again button. */
    public static async Task Transcribe(Settings s, long id)
    {
        lock (Running) if (!Running.Add(id)) return;
        Update(id, o => { o["status"] = "pending"; o.Remove("error"); });
        Changed?.Invoke();
        try
        {
            var file = AudioFile(id) ?? throw new IOException("The recording is missing");
            var (segments, timed, duration) = await CloudTranscribe(s, file);
            var words = NoteMedia.Align(segments, timed);
            var text = NoteMedia.Text(words);
            if (text.Trim().Length == 0) throw new IOException("No speech in this recording");
            Update(id, o =>
            {
                o["status"] = "done";
                o.Remove("error");
                o.Remove("silence"); // found again from the new word times
                o["words"] = new JsonArray(words.Select(w => (JsonNode)new JsonArray(w.Text, w.Start, w.End)).ToArray());
                o["segments"] = new JsonArray(segments.Select(g => (JsonNode)new JsonArray(g.Start, g.End, g.Text.Trim())).ToArray());
                o["duration"] = duration > 0 ? duration : words.Count > 0 ? words[^1].End : 0;
            });
            Notes.Add(text, id);
        }
        catch (Exception e)
        {
            Update(id, o => { o["status"] = "failed"; o["error"] = e.Message; });
        }
        finally
        {
            lock (Running) Running.Remove(id);
            Changed?.Invoke();
        }
    }

    /** Whisper with a time for every word: Groq, or else OpenAI's whisper-1 (its newer models don't give word times). */
    private static async Task<(List<NoteMedia.Segment>, List<NoteMedia.Word>, double)> CloudTranscribe(Settings s, string file)
    {
        var groq = Catalog.Stt.First(o => o.Id == "GROQ");
        var openai = Catalog.Stt.First(o => o.Id == "OPENAI");
        var groqModel = s.SttModel(groq).StartsWith("whisper") ? s.SttModel(groq) : groq.Model;
        var order = new[] { (groq, groqModel), (openai, "whisper-1") }.Where(c => s.Key(c.Item1.Service).Length > 0).ToList();
        if (order.Count == 0) throw new IOException("Add a Groq key in Settings to transcribe recordings");
        var bytes = await File.ReadAllBytesAsync(file);
        Exception? last = null;
        foreach (var (c, model) in order)
        {
            try { return Parse(await Send(c.BaseUrl, s.Key(c.Service), model, bytes, Path.GetFileName(file), !s.AutoLanguage)); }
            catch (Exception e) { last = e; }
        }
        throw last ?? new IOException("Couldn't transcribe it");
    }

    private static async Task<JsonNode> Send(string baseUrl, string key, string model, byte[] audio, string name, bool english)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(model), "model");
        if (english) form.Add(new StringContent("en"), "language");
        form.Add(new StringContent("verbose_json"), "response_format");
        form.Add(new StringContent("word"), "timestamp_granularities[]");
        form.Add(new StringContent("segment"), "timestamp_granularities[]");
        var content = new ByteArrayContent(audio);
        content.Headers.ContentType = new MediaTypeHeaderValue(Mime(Path.GetExtension(name).TrimStart('.')));
        form.Add(content, "file", name);
        using var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/audio/transcriptions") { Content = form };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        using var res = await Net.Http.SendAsync(req, cts.Token);
        var body = await res.Content.ReadAsStringAsync(cts.Token);
        if (!res.IsSuccessStatusCode) throw new IOException($"HTTP {(int)res.StatusCode}: {Net.ErrorMessage(body)}");
        return JsonNode.Parse(body) ?? throw new IOException("Empty response");
    }

    /** The segments, timed words and length of a verbose_json reply. With no segments, the whole text is one. */
    public static (List<NoteMedia.Segment>, List<NoteMedia.Word>, double) Parse(JsonNode reply)
    {
        var duration = (double?)reply["duration"] ?? 0;
        var segments = (reply["segments"] as JsonArray)?.Select(x => new NoteMedia.Segment(
            (double?)x?["start"] ?? 0, (double?)x?["end"] ?? 0, (string?)x?["text"] ?? "")).ToList() ?? new();
        if (segments.Count == 0) segments.Add(new NoteMedia.Segment(0, duration, (string?)reply["text"] ?? ""));
        var words = (reply["words"] as JsonArray)?.Select(x => new NoteMedia.Word(
            ((string?)x?["word"] ?? "").Trim(), (double?)x?["start"] ?? 0, (double?)x?["end"] ?? 0)).ToList() ?? new();
        return (segments.Where(g => g.Text.Trim().Length > 0).ToList(), words, duration);
    }

    /** Asks the cleanup AI for a summary or index: the provider picked for cleanup first, then one other with a key. */
    public static async Task<string> Ask(Settings s, string system, string user, int maxTokens)
    {
        var order = new[] { s.CleanupOption }.Concat(Catalog.Cleanup.Where(o => o.Id != s.Cleanup))
            .Where(o => o.Id != "OFF" && s.Key(o.Service).Length > 0).Take(2).ToList();
        if (order.Count == 0) throw new IOException("Add a Groq key in Settings to summarize notes");
        Exception? last = null;
        foreach (var c in order)
        {
            try
            {
                var text = (await Chat(s, c, system, user, maxTokens)).Trim();
                if (text.Length > 0) return text;
                last = new IOException("The AI sent back nothing");
            }
            catch (Exception e) { last = e; }
        }
        throw last ?? new IOException("Couldn't reach the AI");
    }

    private static async Task<string> Chat(Settings s, CleanupOption c, string system, string user, int maxTokens)
    {
        var key = s.Key(c.Service);
        var model = s.CleanupModel(c);
        if (c.Id == "CLAUDE")
        {
            var body = new JsonObject
            {
                ["model"] = model, ["max_tokens"] = maxTokens, ["system"] = system,
                ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = user }),
            };
            var res = await Net.PostJson(c.BaseUrl + "/messages",
                new Dictionary<string, string> { ["x-api-key"] = key, ["anthropic-version"] = "2023-06-01" }, body, 60_000);
            var sb = new StringBuilder();
            foreach (var part in res["content"]!.AsArray())
                if (part?["type"]?.ToString() == "text") sb.Append(part["text"]?.ToString());
            return sb.ToString();
        }
        var chat = new JsonObject
        {
            ["model"] = model,
            ["messages"] = new JsonArray(
                new JsonObject { ["role"] = "system", ["content"] = system },
                new JsonObject { ["role"] = "user", ["content"] = user }),
        };
        // Gemini's OpenAI-style endpoint only knows the older max_tokens. GPT-OSS thinks first, and that counts against the cap.
        var cap = c.Id == "GEMINI" ? "max_tokens" : "max_completion_tokens";
        if (model.Contains("gpt-oss")) { chat["reasoning_effort"] = "low"; chat[cap] = maxTokens + 2000; }
        else chat[cap] = maxTokens;
        var r = await Net.PostJson(c.BaseUrl + "/chat/completions", new Dictionary<string, string> { ["Authorization"] = "Bearer " + key }, chat, 60_000);
        return r["choices"]?[0]?["message"]?["content"]?.ToString() ?? "";
    }

    /** Asks for and saves a summary at [level]. */
    public static async Task<string> Summarize(Settings s, long id, string text, int level, bool voice)
    {
        var summary = await Ask(s, NoteMedia.SummarySystem(level, voice), NoteMedia.SummaryUser(text), NoteMedia.SummaryMaxTokens(level));
        Update(id, o =>
        {
            if (o["summaries"] is not JsonObject sums) o["summaries"] = sums = new JsonObject();
            sums[level.ToString()] = summary;
        });
        return summary;
    }

    /** Asks for and saves the index of a recording. */
    public static async Task<List<NoteMedia.Point>> MakeIndex(Settings s, long id, List<NoteMedia.Segment> segments)
    {
        var points = NoteMedia.ParseIndex(await Ask(s, NoteMedia.IndexSystem, NoteMedia.IndexUser(segments), 2400), segments);
        Update(id, o => o["index"] = new JsonArray(points.Select(p => (JsonNode)new JsonArray(p.Time, p.Topic, p.Text)).ToArray()));
        return points;
    }
}
