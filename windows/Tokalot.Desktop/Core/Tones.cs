using System;
using System.IO;
using System.Linq;

namespace Tokalot.Desktop.Core;

/**
 * The feedback sound sets, synthesized in code (no audio files to ship or license). Each set has one short
 * sound per moment of a dictation. Start sounds stay well under the recorder's cue window, so the mic never
 * mistakes the tone for speech.
 */
public static class Tones
{
    /** Same order as Sounds.Kind on every platform. */
    public enum Kind { Start, Stop, Done, Cancel, Error }

    public record Set(string Id, string Label, string Note);

    public static readonly Set[] Sets =
    {
        new("soft", "Soft", "The original gentle beeps."),
        new("chime", "Chime", "Little bells that ring out."),
        new("retro", "Retro", "8-bit game blips."),
        new("bubble", "Bubble", "Pops that slide up and down."),
    };

    public static string Valid(string? id) => Sets.Any(s => s.Id == id) ? id! : Sets[0].Id;

    public const int Rate = 44100;

    private enum Voice { Sine, Bell, Square, Glide }

    /** One note: start and end pitch in Hz (they differ only for a glide; 0 is a rest) and its length. */
    private record struct Note(double F, double F2, int Ms)
    {
        public Note(double f, int ms) : this(f, f, ms) { }
    }

    /** 16-bit mono PCM at Rate. */
    public static byte[] Pcm(string set, Kind kind)
    {
        var (voice, notes) = Score(Valid(set), kind);
        using var ms = new MemoryStream();
        foreach (var n in notes) Render(ms, voice, n);
        return ms.ToArray();
    }

    /** How long a sound lasts, in ms. */
    public static int Length(string set, Kind kind) => Score(Valid(set), kind).notes.Sum(n => n.Ms);

    private static (Voice voice, Note[] notes) Score(string set, Kind kind) => set switch
    {
        "chime" => (Voice.Bell, kind switch
        {
            Kind.Start => new[] { new Note(1319, 70), new Note(1976, 200) },
            Kind.Stop => new[] { new Note(1568, 160) },
            Kind.Done => new[] { new Note(1319, 70), new Note(1568, 70), new Note(2093, 260) },
            Kind.Cancel => new[] { new Note(1175, 90), new Note(880, 200) },
            _ => new[] { new Note(622, 160), new Note(587, 260) },
        }),
        "retro" => (Voice.Square, kind switch
        {
            Kind.Start => new[] { new Note(988, 45), new Note(1319, 80) },
            Kind.Stop => new[] { new Note(1319, 40), new Note(988, 50) },
            Kind.Done => new[] { new Note(1047, 45), new Note(1319, 45), new Note(1568, 45), new Note(2093, 90) },
            Kind.Cancel => new[] { new Note(659, 60), new Note(494, 80) },
            _ => new[] { new Note(196, 110), new Note(0, 40), new Note(147, 160) },
        }),
        "bubble" => (Voice.Glide, kind switch
        {
            Kind.Start => new[] { new Note(400, 900, 110) },
            Kind.Stop => new[] { new Note(900, 500, 90) },
            Kind.Done => new[] { new Note(500, 1000, 70), new Note(0, 25), new Note(700, 1400, 90) },
            Kind.Cancel => new[] { new Note(700, 300, 140) },
            _ => new[] { new Note(300, 180, 140), new Note(0, 40), new Note(300, 160, 160) },
        }),
        _ => (Voice.Sine, kind switch
        {
            Kind.Start => new[] { new Note(660, 55), new Note(990, 70) },
            Kind.Stop => new[] { new Note(880, 45) },
            Kind.Done => new[] { new Note(784, 50), new Note(1175, 80) },
            Kind.Cancel => new[] { new Note(520, 70) },
            _ => new[] { new Note(330, 90), new Note(0, 40), new Note(330, 90) },
        }),
    };

    private static void Render(Stream ms, Voice voice, Note note)
    {
        int n = Rate * note.Ms / 1000;
        double phase = 0;
        for (int i = 0; i < n; i++)
        {
            double t = (double)i / Rate;
            double f = note.F == note.F2 ? note.F : note.F * Math.Pow(note.F2 / note.F, (double)i / n);
            phase += 2 * Math.PI * f / Rate;
            double v = note.F == 0 ? 0 : voice switch
            {
                // Gentle and quiet: soft attack and release.
                Voice.Sine => Math.Sin(phase) * Ease(i, n, 0.006, 0.03) * 0.12,
                // Struck, then rings down; the inharmonic overtone is what makes it a bell.
                Voice.Bell => (Math.Sin(phase) + 0.35 * Math.Sin(phase * 2.76) * Math.Exp(-t / 0.04)) * Math.Exp(-t / 0.09) * Ease(i, n, 0.002, 0.012) * 0.1,
                // A few odd harmonics: square-ish without the harsh top end. Squares sound louder, so it's quieter.
                Voice.Square => (Math.Sin(phase) + Math.Sin(3 * phase) / 3 + Math.Sin(5 * phase) / 5) * Ease(i, n, 0.003, 0.015) * 0.07,
                // A sine whose pitch slides, rounded off at both ends.
                _ => Math.Sin(phase) * Math.Sin(Math.PI * i / n) * 0.13,
            };
            short s = (short)(v * short.MaxValue);
            ms.WriteByte((byte)s); ms.WriteByte((byte)(s >> 8));
        }
    }

    /** 0..1 ramp in over attack seconds and out over release seconds. */
    private static double Ease(int i, int n, double attack, double release) =>
        Math.Min(1, Math.Min(i / (Rate * attack), (n - i) / (Rate * release)));
}
