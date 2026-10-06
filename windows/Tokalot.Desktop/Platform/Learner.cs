using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Automation;
using System.Windows.Threading;
using Tokalot.Desktop.Core;

namespace Tokalot.Desktop.Platform;

/**
 * Looks at the text box a dictation was pasted into, a few times over the next two minutes, to see
 * whether the user respelled a name or a term in it (see Learn). Only runs when "Learn from my
 * corrections" is on. Windows lets one app read another's text box through UI Automation, the same
 * door screen readers use; apps that don't offer their text that way simply teach nothing.
 */
public sealed class Learner
{
    private const int WatchSeconds = 120;
    private readonly DispatcherTimer timer;
    private string? text;    // the dictation being watched
    private DateTime until;
    private string? seen;    // the corrected word found at the last look
    private string? stable;  // the same word found three looks running (unchanged for 3 s, so not caught mid-typing)
    private int same;        // looks in a row that found `seen`
    private int misses;      // looks in a row that didn't find the dictation
    private bool busy;

    /** A word was added to the dictionary. */
    public event Action<string>? Learned;

    public Learner(Dispatcher ui)
    {
        timer = new DispatcherTimer(TimeSpan.FromMilliseconds(1500), DispatcherPriority.Background, (_, _) => Look(), ui);
        timer.Stop();
    }

    public void Watch(string pasted)
    {
        Stop(learn: false);
        if (!Settings.Current.LearnWords) return;
        text = pasted;
        until = DateTime.UtcNow.AddSeconds(WatchSeconds);
        timer.Start();
    }

    private async void Look()
    {
        var watching = text;
        if (busy || watching == null) return;
        busy = true;
        string? field;
        try
        {
            // Some apps answer slowly or not at all: never wait on them for long.
            var read = Task.Run(ReadFocused);
            field = await Task.WhenAny(read, Task.Delay(1200)) == read ? read.Result : null;
        }
        catch { field = null; }
        finally { busy = false; }
        if (text != watching) return; // stopped, or a newer dictation, while reading

        var now = field == null ? null : Learn.Look(watching, field, Settings.Current.Words, !Settings.Current.AutoLanguage);
        // The dictation is gone (sent, cleared, or the text box was left): what was last seen stands.
        // One look that finds nothing can be a slow app or a glance at another window; two in a row is gone.
        if (now == null || !now.Found)
        {
            if (++misses >= 2) Stop(learn: true);
            return;
        }
        misses = 0;
        same = now.Word != null && now.Word == seen ? same + 1 : 1;
        stable = now.Word != null && same >= 3 ? now.Word : null;
        seen = now.Word;
        if (DateTime.UtcNow > until) Stop(learn: true);
    }

    /** learn: add the correction that was seen, if there was one. False when the dictation itself was undone. */
    public void Stop(bool learn)
    {
        timer.Stop();
        var word = stable;
        text = null; seen = null; stable = null; same = 0; misses = 0;
        if (!learn || word == null) return;
        var s = Settings.Current;
        if (s.Words.Any(w => string.Equals(w, word, StringComparison.OrdinalIgnoreCase))) return;
        s.Words.Add(word);
        s.LearnedWords.Add(word);
        s.Save();
        Learned?.Invoke(word);
    }

    /** Takes a learned word back out. */
    public static void Forget(string word)
    {
        var s = Settings.Current;
        s.Words.Remove(word);
        s.LearnedWords.Remove(word);
        s.Save();
    }

    /** The text of the focused text box in another app, or null when it can't be read (or is a password). */
    public static string? ReadFocused()
    {
        try
        {
            var el = AutomationElement.FocusedElement;
            if (el == null || el.Current.ProcessId == Environment.ProcessId || el.Current.IsPassword) return null;
            if (el.TryGetCurrentPattern(TextPattern.Pattern, out var tp))
                return ((TextPattern)tp).DocumentRange.GetText(60000);
            if (el.TryGetCurrentPattern(ValuePattern.Pattern, out var vp))
                return ((ValuePattern)vp).Current.Value;
        }
        catch { }
        return null;
    }
}
