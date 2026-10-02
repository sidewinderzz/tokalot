using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using Tokalot.Desktop.Platform;

namespace Tokalot.Desktop.Core;

/**
 * Records 16 kHz mono 16-bit audio (what Whisper wants) from the default microphone.
 * Mac: Core Audio's AudioQueue does the capturing and converts from the microphone's own rate;
 * it hands over 50 ms chunks on its own thread. The first recording makes macOS ask whether
 * Tokalot may use the microphone (the reason shown comes from the app's Info.plist).
 */
public sealed unsafe class Recorder : IDisposable
{
    public const int SampleRate = 16000;
    private const int MaxSamples = SampleRate * 600; // 10 minute cap
    private const float VoiceRms = 0.015f;            // the most a sound ever has to reach to count as "someone is talking"
    private const float VoiceMin = 0.004f;            // and the least, on a very quiet microphone
    private const float SpeechRms = 0.008f;           // lower bar for "was anything said at all" (quiet mics)
    private const int ChunkBytes = SampleRate / 20 * 2; // 50 ms
    private const int Buffers = 3;

    [StructLayout(LayoutKind.Sequential)]
    private struct AudioStreamBasicDescription
    {
        public double SampleRate;
        public uint FormatID, FormatFlags, BytesPerPacket, FramesPerPacket, BytesPerFrame, ChannelsPerFrame, BitsPerChannel, Reserved;
    }

    [DllImport(Native.AudioToolbox)]
    private static extern int AudioQueueNewInput(ref AudioStreamBasicDescription format,
        delegate* unmanaged<IntPtr, IntPtr, IntPtr, IntPtr, uint, IntPtr, void> callback,
        IntPtr userData, IntPtr runLoop, IntPtr runLoopMode, uint flags, out IntPtr queue);
    [DllImport(Native.AudioToolbox)] private static extern int AudioQueueAllocateBuffer(IntPtr queue, uint size, out IntPtr buffer);
    [DllImport(Native.AudioToolbox)] private static extern int AudioQueueEnqueueBuffer(IntPtr queue, IntPtr buffer, uint descs, IntPtr packetDescs);
    [DllImport(Native.AudioToolbox)] private static extern int AudioQueueStart(IntPtr queue, IntPtr startTime);
    [DllImport(Native.AudioToolbox)] private static extern int AudioQueueStop(IntPtr queue, byte immediate);
    [DllImport(Native.AudioToolbox)] private static extern int AudioQueueDispose(IntPtr queue, byte immediate);

    private IntPtr queue;
    private GCHandle self;
    private volatile int session;
    private int mine;
    private float floor;                              // running estimate of the room's background level
    private readonly List<short> samples = new();
    private readonly object gate = new();

    public bool IsRecording { get; private set; }
    /** The 10 minute cap was reached; later audio is not being kept. */
    public bool Full { get { lock (gate) return samples.Count >= MaxSamples; } }
    /** Loudness of the latest chunk (RMS 0..1), for the animated bars. */
    public float Level { get; private set; }
    /** When speech was last heard, and where it ended in the audio (for auto-stop trimming). */
    public DateTime LastVoiceAt { get; private set; }
    public int LastVoiceSample { get; private set; }
    /** How many 50 ms chunks had sound in them, and how many of those came after the start tone. */
    public int SpeechChunks { get; private set; }
    public int LateSpeechChunks { get; private set; }
    /** Audio this early may just be the start tone coming back through the speakers. */
    public int CueSamples { get; set; }

    /** What does the recording, for Settings and --diagnose. Always present on a Mac. */
    public static string? Tool => "Core Audio";

    /** The last reason a recording couldn't start (an OSStatus code from Core Audio), for the log and --diagnose. */
    public static string? LastError { get; private set; }

    /** Returns false if no microphone could be opened (or Tokalot isn't allowed to use it). */
    public bool Start()
    {
        if (IsRecording) return true;
        if (Permissions.Microphone is Permissions.Mic.Denied or Permissions.Mic.Restricted)
        {
            LastError = "microphone permission denied";
            return false;
        }
        try
        {
            lock (gate) samples.Clear();
            LastVoiceAt = DateTime.UtcNow;
            LastVoiceSample = 0;
            SpeechChunks = 0;
            LateSpeechChunks = 0;
            floor = 0.002f;

            var format = new AudioStreamBasicDescription
            {
                SampleRate = SampleRate, FormatID = 0x6C70636D /* 'lpcm' */, FormatFlags = 4 | 8 /* signed integer, packed */,
                BytesPerPacket = 2, FramesPerPacket = 1, BytesPerFrame = 2, ChannelsPerFrame = 1, BitsPerChannel = 16,
            };
            if (!self.IsAllocated) self = GCHandle.Alloc(this);
            mine = ++session;
            int err = AudioQueueNewInput(ref format, &OnBuffer, GCHandle.ToIntPtr(self), IntPtr.Zero, IntPtr.Zero, 0, out var q);
            if (err != 0) { Fail("AudioQueueNewInput", err); return false; }
            for (int i = 0; i < Buffers; i++)
            {
                if ((err = AudioQueueAllocateBuffer(q, ChunkBytes, out var buf)) != 0) { AudioQueueDispose(q, 1); Fail("AudioQueueAllocateBuffer", err); return false; }
                AudioQueueEnqueueBuffer(q, buf, 0, IntPtr.Zero);
            }
            if ((err = AudioQueueStart(q, IntPtr.Zero)) != 0) { AudioQueueDispose(q, 1); Fail("AudioQueueStart", err); return false; }
            queue = q;
            IsRecording = true;
            LastError = null;
            return true;
        }
        catch (Exception e)
        {
            LastError = e.Message;
            App.Log("Recorder failed: " + e);
            return false;
        }
    }

    private static void Fail(string call, int status)
    {
        LastError = $"{call} returned {status} ({FourCC(status)})";
        App.Log("Recorder: " + LastError);
    }

    /** Core Audio codes are often four letters ('!dev' = no device). */
    private static string FourCC(int status)
    {
        var b = BitConverter.GetBytes(status);
        Array.Reverse(b);
        foreach (var c in b) if (c < 32 || c > 126) return status.ToString();
        return "'" + System.Text.Encoding.ASCII.GetString(b) + "'";
    }

    [UnmanagedCallersOnly]
    private static void OnBuffer(IntPtr user, IntPtr q, IntPtr buffer, IntPtr startTime, uint packets, IntPtr descs)
    {
        try
        {
            if (GCHandle.FromIntPtr(user).Target is not Recorder r) return;
            // struct AudioQueueBuffer { UInt32 capacity; void* data; UInt32 byteSize; ... } on a 64-bit Mac.
            var data = Marshal.ReadIntPtr(buffer, 8);
            var size = Marshal.ReadInt32(buffer, 16);
            if (r.session == r.mine && size > 0) r.OnData((short*)data, size / 2);
            if (r.session == r.mine) AudioQueueEnqueueBuffer(q, buffer, 0, IntPtr.Zero);
        }
        catch { }
    }

    private void OnData(short* data, int n)
    {
        if (n == 0) return;
        double sum = 0;
        lock (gate)
        {
            for (int i = 0; i < n; i++)
            {
                short s = data[i];
                double v = s / 32768.0;
                sum += v * v;
                if (samples.Count < MaxSamples) samples.Add(s);
            }
            Level = (float)Math.Sqrt(sum / n);
            if (Level > SpeechRms)
            {
                SpeechChunks++;
                if (samples.Count - n >= CueSamples) LateSpeechChunks++;
            }
            // The bar for "talking" follows the room: three times the background level, which drops at once in a
            // quiet moment and creeps up slowly. A fixed bar read soft speech on a quiet mic as silence, and
            // auto-stop then ended the recording 30 s in, mid-sentence.
            floor = Level < floor ? Level : Math.Min(Level, floor * 1.003f + 0.00001f);
            if (Level > Math.Clamp(floor * 3, VoiceMin, VoiceRms))
            {
                LastVoiceAt = DateTime.UtcNow;
                LastVoiceSample = samples.Count;
            }
        }
    }

    /**
     * Stops the mic and returns the audio as floats in [-1, 1]. The chunk being filled is let finish
     * first (up to 60 ms), so the last syllable isn't cut off: call it off the UI thread where that matters.
     */
    public float[] Stop()
    {
        var q = queue;
        queue = IntPtr.Zero;
        if (q != IntPtr.Zero)
        {
            Thread.Sleep(60);
            session++;
            try
            {
                AudioQueueStop(q, 1);
                AudioQueueDispose(q, 1);
            }
            catch { }
        }
        IsRecording = false;
        Level = 0;
        lock (gate)
        {
            var outp = new float[samples.Count];
            for (int i = 0; i < outp.Length; i++) outp[i] = samples[i] / 32768f;
            samples.Clear();
            return outp;
        }
    }

    public void Dispose()
    {
        if (IsRecording) Stop();
        if (self.IsAllocated) self.Free();
    }

    // ---------- the input device ----------

    private const string CoreAudio = "/System/Library/Frameworks/CoreAudio.framework/CoreAudio";

    [StructLayout(LayoutKind.Sequential)] private struct Address { public uint Selector, Scope, Element; }

    [DllImport(CoreAudio)]
    private static extern int AudioObjectGetPropertyData(uint objectId, ref Address address, uint qualifierSize, IntPtr qualifier, ref uint dataSize, void* data);

    /** The name of the default input device ("MacBook Air Microphone"), or null when there is none. */
    public static string? InputDevice()
    {
        if (!OperatingSystem.IsMacOS()) return null;
        try
        {
            var a = new Address { Selector = 0x64496E20 /* 'dIn ' default input device */, Scope = 0x676C6F62 /* 'glob' */, Element = 0 };
            uint id = 0, size = 4;
            if (AudioObjectGetPropertyData(1 /* the system */, ref a, 0, IntPtr.Zero, ref size, &id) != 0 || id == 0) return null;
            var n = new Address { Selector = 0x6C6E616D /* 'lnam' name */, Scope = 0x676C6F62, Element = 0 };
            IntPtr name = IntPtr.Zero;
            size = (uint)IntPtr.Size;
            if (AudioObjectGetPropertyData(id, ref n, 0, IntPtr.Zero, ref size, &name) != 0 || name == IntPtr.Zero) return "device " + id;
            var s = Native.Text(name);
            Native.CFRelease(name);
            return s;
        }
        catch { return null; }
    }
}
