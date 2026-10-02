using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/**
 * Where API keys are kept on a Mac.
 *   "keyring": your login Keychain, as generic passwords under the name "Tokalot" (one per service,
 *              e.g. account "groq"). Visible in the Keychain Access app. Same note name as Linux's
 *              keyring, so settings and backups read the same everywhere.
 *   "file":    keys.json in Tokalot's data folder, readable only by your user (mode 0600). Used only
 *              if the Keychain refuses a key.
 * The Keychain can stop to ask for your password (for instance after Tokalot was updated, because
 * an unsigned app's identity changes with every build), so it is never asked on the window's
 * thread: keys are fetched once in the background at start-up and everything else reads the copy
 * in memory, as on Linux.
 */
public static unsafe class KeyStore
{
    public const string Keyring = "keyring", FileStore = "file";
    private const string ServiceName = "Tokalot";

    private static readonly Dictionary<string, string> Cache = new();
    private static readonly object Gate = new();
    private static readonly object WriteGate = new();
    private static readonly List<Task> Pending = new();
    private static bool? keychainWorks;
    private static Task? preload;

    /** Raised (on a background thread) when keys finished loading or the note under the key fields changed. */
    public static event Action? Changed;

    /** Something the user should know (the Keychain refused a key, or didn't answer). Shown under the key fields. */
    public static string? Notice { get; private set; }

    /** A test copy (TOKALOT_DATA) leaves the real Keychain alone. */
    private static bool UseKeychain => OperatingSystem.IsMacOS() && !Paths.IsTestInstance;

    /** Which store new keys go to. */
    public static string Backend => UseKeychain && keychainWorks != false ? Keyring : FileStore;

    /** A sentence for Settings saying where keys are kept. */
    public static string Where => (Backend == Keyring
        ? "Keys are stored only on this Mac, in your login Keychain (as \"Tokalot\")."
        : "Keys are stored only on this Mac, in " + Pretty(KeysFile) + ", which only your user account can read (not encrypted).")
        + " They never leave it unless you turn on Sync below and choose to include them."
        + (Notice != null ? " " + Notice : "");

    private static string KeysFile => Paths.File("keys.json");

    private static string Pretty(string path)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return home.Length > 0 && path.StartsWith(home) ? "~" + path[home.Length..] : path;
    }

    /** Fetches every noted key in the background. Call once at start-up (and after a restore). */
    public static Task Preload(Settings s)
    {
        var places = s.KeyPlaces();
        return preload = Task.Run(() =>
        {
            foreach (var (service, where) in places)
            {
                lock (Gate) { if (Cache.ContainsKey(service)) continue; }
                var value = Fetch(service, where);
                lock (Gate) Cache.TryAdd(service, value);
            }
            Changed?.Invoke();
        });
    }

    /** Blocking: asks the Keychain (up to 20 s, in case it is asking for your password), then the file. */
    private static string Fetch(string service, string where)
    {
        string? value = null;
        if (where != FileStore && UseKeychain)
        {
            var t = Task.Run(() => Keychain.Find(ServiceName, service));
            if (t.Wait(20000)) value = t.Result.Value;
            else Notice = "The Keychain didn't answer when Tokalot started, so keys kept there aren't loaded. Restart Tokalot and allow it if macOS asks.";
        }
        value ??= ReadFile().GetValueOrDefault(service);
        return value ?? "";
    }

    /** Never waits on the Keychain from the window's thread: the copy in memory, or (for a key kept in the file) a quick read of the file. */
    public static string Get(string service, string where)
    {
        lock (Gate)
        {
            if (Cache.TryGetValue(service, out var hit)) return hit;
        }
        if (where != FileStore && UseKeychain)
        {
            var loading = preload ?? Preload(Settings.Current);
            if (Avalonia.Threading.Dispatcher.UIThread.CheckAccess()) return "";
            try { loading.Wait(25000); } catch { }
            lock (Gate) return Cache.GetValueOrDefault(service, "");
        }
        var value = ReadFile().GetValueOrDefault(service) ?? "";
        lock (Gate) Cache[service] = value;
        return value;
    }

    /** Remembers the key right away and writes it to the store in the background. Returns where it will live. */
    public static string Set(string service, string value)
    {
        lock (Gate) Cache[service] = value;
        var where = Backend;
        Queue(() => Write(service, value));
        return where;
    }

    public static void Remove(string service)
    {
        lock (Gate) Cache[service] = "";
        Queue(() => Write(service, ""));
    }

    private static void Queue(Action write)
    {
        var t = Task.Run(write);
        lock (Pending)
        {
            Pending.RemoveAll(x => x.IsCompleted);
            Pending.Add(t);
        }
    }

    /** Waits for key writes still on their way to the Keychain or the file (called when quitting). */
    public static void Flush(int timeoutMs = 6000)
    {
        Task[] waiting;
        lock (Pending) waiting = Pending.ToArray();
        try { Task.WaitAll(waiting, timeoutMs); } catch { }
    }

    private static void Write(string service, string value)
    {
        lock (WriteGate)
        {
            // Typing a key fires one write per character; only the newest value matters.
            lock (Gate) { if (Cache.GetValueOrDefault(service, "") != value) return; }

            var stored = false;
            if (UseKeychain && keychainWorks != false)
            {
                var status = value.Length == 0 ? Keychain.Delete(ServiceName, service) : Keychain.Store(ServiceName, service, value);
                stored = status == 0 || (value.Length == 0 && status == Keychain.NotFound);
                if (value.Length > 0)
                {
                    keychainWorks = stored;
                    if (!stored)
                    {
                        Notice = "The Keychain didn't take the key (status " + status + "), so it was saved in " + Pretty(KeysFile) + " instead.";
                        App.Log("Keychain store failed (" + status + "); using keys.json");
                    }
                }
            }

            var file = ReadFile();
            if (stored || value.Length == 0)
            {
                if (file.Remove(service)) WriteFile(file);
                return;
            }
            file[service] = value;
            WriteFile(file);
            Settings.Current.NoteKeyPlace(service, FileStore);
            Changed?.Invoke();
        }
    }

    private static Dictionary<string, string> ReadFile()
    {
        try
        {
            if (File.Exists(KeysFile))
                return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(KeysFile)) ?? new();
        }
        catch { }
        return new();
    }

    private static void WriteFile(Dictionary<string, string> keys)
    {
        try
        {
            if (keys.Count == 0) { File.Delete(KeysFile); return; }
            var tmp = KeysFile + ".tmp";
            // Created private before anything is written into it.
            var options = new FileStreamOptions { Mode = FileMode.Create, Access = FileAccess.Write };
            if (!OperatingSystem.IsWindows()) options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
            using (var f = new FileStream(tmp, options))
            using (var w = new StreamWriter(f))
                w.Write(JsonSerializer.Serialize(keys, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, KeysFile, true);
        }
        catch (Exception e) { App.Log("Couldn't write keys.json: " + e.Message); }
    }
}

/** Generic passwords in the login Keychain, through the Security framework's SecItem calls. */
internal static unsafe class Keychain
{
    public const int NotFound = -25300, Duplicate = -25299;

    [DllImport(Native.Security)] private static extern int SecItemAdd(IntPtr attributes, IntPtr result);
    [DllImport(Native.Security)] private static extern int SecItemUpdate(IntPtr query, IntPtr attributes);
    [DllImport(Native.Security)] private static extern int SecItemCopyMatching(IntPtr query, out IntPtr result);
    [DllImport(Native.Security)] private static extern int SecItemDelete(IntPtr query);

    private static IntPtr K(string name) => Native.Constant(Native.Security, name);

    /** Runs f with a query naming one item (service + account); everything made here is released after. */
    private static T With<T>(string service, string account, Func<IntPtr, IntPtr, IntPtr, T> f)
    {
        IntPtr s = Native.Str(service), a = Native.Str(account);
        try { return f(K("kSecClass"), s, a); }
        finally { Native.CFRelease(s); Native.CFRelease(a); }
    }

    public static (int Status, string? Value) Find(string service, string account) => With(service, account, (cls, s, a) =>
    {
        var q = Native.Dict((cls, K("kSecClassGenericPassword")), (K("kSecAttrService"), s), (K("kSecAttrAccount"), a),
            (K("kSecReturnData"), Native.CFTrue), (K("kSecMatchLimit"), K("kSecMatchLimitOne")));
        try
        {
            var status = SecItemCopyMatching(q, out var data);
            if (status != 0 || data == IntPtr.Zero) return (status, (string?)null);
            var value = Encoding.UTF8.GetString(Native.Bytes(data));
            Native.CFRelease(data);
            return (0, value);
        }
        finally { Native.CFRelease(q); }
    });

    public static int Store(string service, string account, string value) => With(service, account, (cls, s, a) =>
    {
        var data = Native.Data(Encoding.UTF8.GetBytes(value));
        var label = Native.Str("Tokalot " + account + " API key");
        var q = Native.Dict((cls, K("kSecClassGenericPassword")), (K("kSecAttrService"), s), (K("kSecAttrAccount"), a));
        var add = Native.Dict((cls, K("kSecClassGenericPassword")), (K("kSecAttrService"), s), (K("kSecAttrAccount"), a),
            (K("kSecAttrLabel"), label), (K("kSecValueData"), data));
        var change = Native.Dict((K("kSecValueData"), data));
        try
        {
            var status = SecItemAdd(add, IntPtr.Zero);
            if (status == Duplicate) status = SecItemUpdate(q, change);
            return status;
        }
        finally
        {
            foreach (var p in new[] { change, add, q, label, data }) Native.CFRelease(p);
        }
    });

    public static int Delete(string service, string account) => With(service, account, (cls, s, a) =>
    {
        var q = Native.Dict((cls, K("kSecClassGenericPassword")), (K("kSecAttrService"), s), (K("kSecAttrAccount"), a));
        try { return SecItemDelete(q); }
        finally { Native.CFRelease(q); }
    });
}
