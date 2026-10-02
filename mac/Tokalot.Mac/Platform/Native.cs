using System;
using System.Runtime.InteropServices;
using System.Text;

namespace Tokalot.Desktop.Platform;

/**
 * The few pieces of macOS that Tokalot talks to directly: the Objective-C runtime (to send messages
 * to AppKit objects such as NSPasteboard and NSWorkspace) and CoreFoundation strings, numbers and
 * dictionaries. Everything here is plain C calls; no bindings package is needed.
 */
internal static unsafe class Native
{
    public const string ObjC = "/usr/lib/libobjc.A.dylib";
    public const string CoreFoundation = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    public const string CoreGraphics = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";
    public const string ApplicationServices = "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices";
    public const string AudioToolbox = "/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox";
    public const string Security = "/System/Library/Frameworks/Security.framework/Security";
    public const string AppKit = "/System/Library/Frameworks/AppKit.framework/AppKit";
    public const string AVFoundation = "/System/Library/Frameworks/AVFoundation.framework/AVFoundation";

    // ---------- Objective-C runtime ----------

    [DllImport(ObjC)] public static extern IntPtr objc_getClass(string name);
    [DllImport(ObjC)] public static extern IntPtr sel_registerName(string name);
    [DllImport(ObjC)] private static extern IntPtr objc_autoreleasePoolPush();
    [DllImport(ObjC)] private static extern void objc_autoreleasePoolPop(IntPtr pool);

    private static readonly IntPtr MsgSend = NativeLibrary.GetExport(NativeLibrary.Load(ObjC), "objc_msgSend");

    /** Frameworks whose classes are looked up by name must be loaded first. */
    public static void Load(string framework)
    {
        try { NativeLibrary.Load(framework); } catch { }
    }

    public static IntPtr Sel(string name) => sel_registerName(name);
    public static IntPtr Class(string name) => objc_getClass(name);

    // objc_msgSend has to be called through a pointer of exactly the right type (on Apple silicon above all).
    public static IntPtr Send(IntPtr o, string sel) =>
        o == IntPtr.Zero ? IntPtr.Zero : ((delegate* unmanaged<IntPtr, IntPtr, IntPtr>)MsgSend)(o, Sel(sel));
    public static IntPtr Send(IntPtr o, string sel, IntPtr a) =>
        o == IntPtr.Zero ? IntPtr.Zero : ((delegate* unmanaged<IntPtr, IntPtr, IntPtr, IntPtr>)MsgSend)(o, Sel(sel), a);
    public static IntPtr Send(IntPtr o, string sel, IntPtr a, IntPtr b) =>
        o == IntPtr.Zero ? IntPtr.Zero : ((delegate* unmanaged<IntPtr, IntPtr, IntPtr, IntPtr, IntPtr>)MsgSend)(o, Sel(sel), a, b);
    public static IntPtr SendL(IntPtr o, string sel, long a) =>
        o == IntPtr.Zero ? IntPtr.Zero : ((delegate* unmanaged<IntPtr, IntPtr, long, IntPtr>)MsgSend)(o, Sel(sel), a);
    public static void SendB(IntPtr o, string sel, bool a)
    {
        if (o != IntPtr.Zero) ((delegate* unmanaged<IntPtr, IntPtr, byte, void>)MsgSend)(o, Sel(sel), a ? (byte)1 : (byte)0);
    }
    public static bool SendRB(IntPtr o, string sel) =>
        o != IntPtr.Zero && ((delegate* unmanaged<IntPtr, IntPtr, byte>)MsgSend)(o, Sel(sel)) != 0;
    public static bool SendRB(IntPtr o, string sel, IntPtr a) =>
        o != IntPtr.Zero && ((delegate* unmanaged<IntPtr, IntPtr, IntPtr, byte>)MsgSend)(o, Sel(sel), a) != 0;
    public static bool SendRB(IntPtr o, string sel, IntPtr a, IntPtr b) =>
        o != IntPtr.Zero && ((delegate* unmanaged<IntPtr, IntPtr, IntPtr, IntPtr, byte>)MsgSend)(o, Sel(sel), a, b) != 0;
    public static long SendRL(IntPtr o, string sel) =>
        o == IntPtr.Zero ? 0 : ((delegate* unmanaged<IntPtr, IntPtr, long>)MsgSend)(o, Sel(sel));
    public static long SendRL(IntPtr o, string sel, IntPtr a) =>
        o == IntPtr.Zero ? 0 : ((delegate* unmanaged<IntPtr, IntPtr, IntPtr, long>)MsgSend)(o, Sel(sel), a);
    public static int SendRI(IntPtr o, string sel) =>
        o == IntPtr.Zero ? 0 : ((delegate* unmanaged<IntPtr, IntPtr, int>)MsgSend)(o, Sel(sel));

    /** True if the object answers to this message (used for calls that only some macOS versions have). */
    public static bool Responds(IntPtr o, string sel) => SendRB(o, "respondsToSelector:", Sel(sel));

    /** Objects AppKit hands back "autoreleased" are freed when the surrounding pool ends; background threads need one of their own. */
    public readonly struct Pool : IDisposable
    {
        private readonly IntPtr p;
        public Pool(bool _) => p = objc_autoreleasePoolPush();
        public void Dispose() { if (p != IntPtr.Zero) objc_autoreleasePoolPop(p); }
    }
    public static Pool AutoreleasePool() => new(true);

    // ---------- CoreFoundation ----------

    [DllImport(CoreFoundation)] public static extern void CFRelease(IntPtr cf);
    [DllImport(CoreFoundation)] public static extern IntPtr CFRetain(IntPtr cf);
    [DllImport(CoreFoundation)] private static extern IntPtr CFStringCreateWithCharacters(IntPtr alloc, char* chars, nint length);
    [DllImport(CoreFoundation)] private static extern nint CFStringGetLength(IntPtr s);
    [DllImport(CoreFoundation)] private static extern void CFStringGetCharacters(IntPtr s, CFRange range, char* buffer);
    [DllImport(CoreFoundation)] public static extern nint CFGetTypeID(IntPtr cf);
    [DllImport(CoreFoundation)] public static extern nint CFStringGetTypeID();
    [DllImport(CoreFoundation)] public static extern IntPtr CFDataCreate(IntPtr alloc, byte* bytes, nint length);
    [DllImport(CoreFoundation)] public static extern nint CFDataGetLength(IntPtr data);
    [DllImport(CoreFoundation)] public static extern byte* CFDataGetBytePtr(IntPtr data);
    [DllImport(CoreFoundation)] private static extern IntPtr CFDictionaryCreate(IntPtr alloc, IntPtr* keys, IntPtr* values, nint count, IntPtr keyCallBacks, IntPtr valueCallBacks);
    [DllImport(CoreFoundation)] public static extern IntPtr CFDictionaryGetValue(IntPtr dict, IntPtr key);
    [DllImport(CoreFoundation)] public static extern nint CFArrayGetCount(IntPtr array);
    [DllImport(CoreFoundation)] public static extern IntPtr CFArrayGetValueAtIndex(IntPtr array, nint index);
    [DllImport(CoreFoundation)] [return: MarshalAs(UnmanagedType.U1)] public static extern bool CFNumberGetValue(IntPtr number, nint type, out long value);
    [DllImport(CoreFoundation)] public static extern IntPtr CFRunLoopGetCurrent();
    [DllImport(CoreFoundation)] public static extern void CFRunLoopRun();
    [DllImport(CoreFoundation)] public static extern void CFRunLoopStop(IntPtr rl);
    [DllImport(CoreFoundation)] public static extern void CFRunLoopAddSource(IntPtr rl, IntPtr source, IntPtr mode);
    [DllImport(CoreFoundation)] public static extern IntPtr CFMachPortCreateRunLoopSource(IntPtr alloc, IntPtr port, nint order);

    [StructLayout(LayoutKind.Sequential)] private struct CFRange { public nint Location, Length; }

    private static readonly IntPtr CF = NativeLibrary.Load(CoreFoundation);

    /** The value of an exported constant such as kCFBooleanTrue or kSecClass (a pointer stored in the library). */
    public static IntPtr Constant(string library, string name) =>
        Marshal.ReadIntPtr(NativeLibrary.GetExport(NativeLibrary.Load(library), name));

    /** The address of an exported structure such as kCFTypeDictionaryKeyCallBacks. */
    public static IntPtr Address(string library, string name) => NativeLibrary.GetExport(NativeLibrary.Load(library), name);

    public static IntPtr CFTrue => Constant(CoreFoundation, "kCFBooleanTrue");

    /** A new CFString (also usable as an NSString). Release it with CFRelease. */
    public static IntPtr Str(string s)
    {
        fixed (char* p = s) return CFStringCreateWithCharacters(IntPtr.Zero, p, s.Length);
    }

    /** The text of a CFString / NSString, or null. */
    public static string? Text(IntPtr cfString)
    {
        if (cfString == IntPtr.Zero || CFGetTypeID(cfString) != CFStringGetTypeID()) return null;
        var n = CFStringGetLength(cfString);
        if (n == 0) return "";
        var buf = new char[n];
        fixed (char* p = buf) CFStringGetCharacters(cfString, new CFRange { Location = 0, Length = n }, p);
        return new string(buf);
    }

    public static IntPtr Data(byte[] bytes)
    {
        fixed (byte* p = bytes) return CFDataCreate(IntPtr.Zero, p, bytes.Length);
    }

    public static byte[] Bytes(IntPtr cfData)
    {
        var n = (int)CFDataGetLength(cfData);
        var outp = new byte[n];
        if (n > 0) Marshal.Copy((IntPtr)CFDataGetBytePtr(cfData), outp, 0, n);
        return outp;
    }

    /** A CFDictionary of CF objects (retained by the dictionary). Release it with CFRelease. */
    public static IntPtr Dict(params (IntPtr Key, IntPtr Value)[] pairs)
    {
        var keys = new IntPtr[pairs.Length];
        var values = new IntPtr[pairs.Length];
        for (int i = 0; i < pairs.Length; i++) { keys[i] = pairs[i].Key; values[i] = pairs[i].Value; }
        fixed (IntPtr* k = keys)
        fixed (IntPtr* v = values)
            return CFDictionaryCreate(IntPtr.Zero, k, v, pairs.Length,
                Address(CoreFoundation, "kCFTypeDictionaryKeyCallBacks"), Address(CoreFoundation, "kCFTypeDictionaryValueCallBacks"));
    }

    /** An NSString for a call that keeps it only for the call's duration. Released by the pool. */
    public static IntPtr NSString(string s) => Send(Str(s), "autorelease");

    /** The text of an NSString-returning message. */
    public static string? SendText(IntPtr o, string sel) => Text(Send(o, sel));

    // ---------- the operating system ----------

    [DllImport("/usr/lib/libSystem.dylib")] private static extern int sysctlbyname(string name, byte* oldp, ref nint oldlenp, IntPtr newp, nint newlen);

    /** "macOS 15.1 (24B83)" from the kernel's own record, without starting a program. */
    public static string MacVersion()
    {
        string Get(string key)
        {
            nint len = 256;
            var buf = new byte[256];
            fixed (byte* p = buf) if (sysctlbyname(key, p, ref len, IntPtr.Zero, 0) != 0) return "";
            return Encoding.UTF8.GetString(buf, 0, (int)Math.Max(0, len - 1));
        }
        var v = Get("kern.osproductversion");
        var b = Get("kern.osversion");
        return "macOS " + (v.Length > 0 ? v : Environment.OSVersion.Version.ToString()) + (b.Length > 0 ? " (" + b + ")" : "");
    }
}
