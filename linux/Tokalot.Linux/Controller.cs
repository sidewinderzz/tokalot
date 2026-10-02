using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Threading;
using Tokalot.Desktop.Core;
using Tokalot.Desktop.Platform;
using Tokalot.Desktop.UI;

namespace Tokalot.Desktop;

/**
 * The hotkey → record → transcribe → paste loop, shared by the Linux and Mac apps (the shortcut
 * is Ctrl+Super on Linux and Ctrl+Cmd on a Mac; Host.Shortcut names it).
 *   Hold the shortcut: records while held, finishes on release.
 *   Tap it (under 350 ms): hands-free; tap again to finish. Esc cancels either way.
 *   The shortcut plus another key quickly (a desktop shortcut): quietly cancels, so shortcuts keep working.
 */
public sealed class Controller : IDisposable
{
    private enum State { Idle, Recording, Processing }

    private const int TapMs = 350;          // shorter press = hands-free
    private const int ShortcutWindowMs = 600; // another key this soon = a desktop shortcut, not dictation
    private const int AutoStopSeconds = 30;
    private const int RevertSeconds = 8;
    private string? plainText;             // the user's own wording for the last (polished) dictation
    private long? plainEntry;
    private ActiveApp? plainApp;
    private DateTime revertUntil;          // the shortcut plus Z puts it back until then

    private readonly Dispatcher ui = Dispatcher.UIThread;
    private readonly HotkeyHook hook;
    private readonly Recorder recorder = new();
    private readonly Dictation dictation = new();
    private readonly Pill pill;               // short messages, shown next to the indicator
    private readonly IndicatorWindow indicator;
    private readonly DispatcherTimer tick;
    private State state = State.Idle;
    private bool handsFree;
    private bool ignoreNextRelease;
    private DateTime pressedAt;
    private ActiveApp? app;
    private Task<ActiveApp?>? appLookup;   // which app has focus, found in the background while recording
    private CancellationTokenSource? work; // the transcription in progress, so Esc can stop it
    private bool userCancelled;

    public Controller()
    {
        pill = new Pill(() => 0);
        indicator = new IndicatorWindow(() => recorder.Level);
        indicator.Clicked += OnClicked;
        TextInjector.Init();
        hook = new HotkeyHook();
        hook.Pressed += () => ui.Post(OnPressed);
        hook.Released += () => ui.Post(OnReleased);
        hook.KeyWhileHeld += vk => ui.Post(() => OnOtherKey(vk));
        hook.Escape += () => ui.Post(() => { if (state == State.Processing) CancelWork(); else Cancel(true); });
        tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        tick.Tick += (_, _) => Tick();
    }

    public bool HotkeyWorks => hook.Installed;

    /** Where the indicator is and whether it shows, for the log (diagnosing placement on new desktops). */
    internal string IndicatorState => $"{(indicator.IsVisible ? "shown" : "hidden")} at {indicator.ScreenBounds} ({indicator.Dock}, {indicator.Mode})";

    internal Window AnyWindow => pill;

    /** Re-reads the indicator's style, edge and idle visibility (after a Settings change). */
    public void RefreshIndicator() => indicator.ApplySettings();

    private void Say(string text, int ms) => pill.FlashNear(text, indicator.ScreenBounds, indicator.Dock, ms);

    /** A reminder of how hands-free works. It has an X; once closed it never shows again (Settings can bring it back). */
    private void Hint(string text, int ms)
    {
        var s = Settings.Current;
        if (s.HideHandsFreeHint) return;
        pill.HintNear(text, indicator.ScreenBounds, indicator.Dock, ms, () => { s.HideHandsFreeHint = true; s.Save(); });
    }

    private void OnPressed()
    {
        if (state == State.Recording && handsFree)
        {
            ignoreNextRelease = true;
            Finish();
            return;
        }
        if (state != State.Idle) return;
        Begin(handsFreeMode: false);
    }

    /** Clicking the indicator: start hands-free, or finish. Focus stays in your app. */
    private void OnClicked()
    {
        if (state == State.Recording) { Finish(); return; }
        if (state == State.Processing) { CancelWork(); return; }
        if (state != State.Idle) return;
        if (Begin(handsFreeMode: true)) Hint($"Listening · click again or press {Host.Shortcut} to finish", 3500);
    }

    private bool Begin(bool handsFreeMode)
    {
        // Finding the focused app can mean starting a helper program; it runs beside the recording and is
        // only needed once the recording is over.
        app = null;
        appLookup = Task.Run(AppDetect.Detect);
        recorder.CueSamples = Settings.Current.Sounds ? Recorder.SampleRate * 6 / 10 : 0;
        if (!recorder.Start())
        {
            Sounds.Play(Sounds.Kind.Error);
            Say(Host.MicProblem(), 4000);
            return false;
        }
        state = State.Recording;
        handsFree = handsFreeMode;
        pressedAt = DateTime.UtcNow;
        hook.Listening = true;
        dictation.WarmUp();
        Sounds.Play(Sounds.Kind.Start);
        indicator.SetMode(IndicatorView.Mode.Listening);
        tick.Start();
        return true;
    }

    private void OnReleased()
    {
        if (ignoreNextRelease) { ignoreNextRelease = false; return; }
        if (state != State.Recording || handsFree) return;
        if ((DateTime.UtcNow - pressedAt).TotalMilliseconds < TapMs)
        {
            handsFree = true;
            Hint($"Hands-free · {Host.Shortcut} to finish · Esc to cancel", 3500);
            return;
        }
        Finish();
    }

    private void OnOtherKey(int vk)
    {
        // The shortcut plus Z just after a polished dictation: put the user's own wording back.
        if (vk == HotkeyHook.KeyZ && plainText != null && DateTime.UtcNow < revertUntil)
        {
            if (state == State.Recording && !handsFree) Cancel(false); // the press also started a recording
            if (state == State.Idle) Revert();
            return;
        }
        if (state != State.Recording || handsFree) return;
        // Shortcut+D, Shortcut+Arrow, etc.: the user meant a desktop shortcut.
        if ((DateTime.UtcNow - pressedAt).TotalMilliseconds < ShortcutWindowMs) Cancel(false);
    }

    private void Tick()
    {
        if (state != State.Recording) { tick.Stop(); return; }
        var s = Settings.Current;
        // In hold mode, ask the keyboards what is really held, in case a key-up never arrived.
        if (!handsFree) hook.Poll();
        if (recorder.Full)
        {
            Say("Reached the 10 minute limit. Transcribing…", 3000);
            Finish();
        }
        else if (handsFree && s.AutoStop && (DateTime.UtcNow - recorder.LastVoiceAt).TotalSeconds > AutoStopSeconds)
            Finish(trimSilence: true);
        // Hold mode, but the keys are up and the release never arrived (the keyboard was unplugged mid-press).
        else if (!handsFree && !HotkeyHook.ComboHeld && (DateTime.UtcNow - pressedAt).TotalMilliseconds > ShortcutWindowMs)
            Finish();
    }

    /** Stops recording without transcribing. */
    public void Cancel(bool audible)
    {
        if (state != State.Recording) return;
        var samples = recorder.Stop();
        Reset();
        app = FoundApp();
        indicator.SetMode(IndicatorView.Mode.Idle);
        if (audible)
        {
            Sounds.Play(Sounds.Kind.Cancel);
            // Esc may have been meant for another app: anything longer than a few seconds is kept so it can still be transcribed.
            if (samples.Length >= Recorder.SampleRate * 3)
            {
                dictation.KeepAudio(samples, null, "Cancelled", app, cancelled: true);
                Say("Cancelled · the recording is in history", 2400);
            }
            else Say("Cancelled", 1200);
        }
    }

    /**
     * Swaps the polished text that was just pasted for the user's own wording.
     * Tokalot watches the keyboard but doesn't hold keys back, so the app in front also receives the
     * Ctrl+Super+Z (Ctrl+Cmd+Z on a Mac) that asked for this. Tokalot waits for the keys to be let go,
     * sends a plain undo to take the polished text out, then pastes the original wording.
     */
    private async void Revert()
    {
        var text = plainText;
        var id = plainEntry;
        var target = plainApp;
        plainText = null;
        if (text == null) return;
        state = State.Processing; // no new recording while keys are being sent
        try
        {
            var result = await TextInjector.Replace(text, target);
            if (result == TextInjector.Result.Pasted)
            {
                var entry = id == null ? null : History.All().FirstOrDefault(e => e.Id == id);
                if (entry != null)
                    History.Replace(new Entry
                    {
                        Id = entry.Id, Time = entry.Time, Text = text, Raw = entry.Raw, DurationMs = entry.DurationMs,
                        Cleaned = false, AppKey = entry.AppKey, AppLabel = entry.AppLabel,
                    });
                Say("Your own wording is back", 1800);
            }
            // Nothing was swapped: say what did happen instead.
            else if (result == TextInjector.Result.Copied) Say($"Your own wording is copied · press {Host.PasteKeys}", 3500);
            else Say("Couldn't reach the clipboard", 3000);
        }
        catch (Exception e) { App.Log("Revert failed: " + e.Message); }
        finally
        {
            state = State.Idle;
            App.Current.RefreshAfterDictation();
        }
    }

    /** The app found by the background look-up, if it has finished. */
    private ActiveApp? FoundApp() => appLookup is { IsCompletedSuccessfully: true } t ? t.Result : app;

    /** Stops a transcription in progress (Esc, or clicking the indicator). */
    private void CancelWork()
    {
        if (work == null) return;
        userCancelled = true;
        try { work.Cancel(); } catch { }
    }

    /** Gives up after a while even on a dead connection: a minute plus twice the recording's length. */
    private CancellationToken StartWork(int sampleCount)
    {
        work = new CancellationTokenSource();
        userCancelled = false;
        work.CancelAfter(TimeSpan.FromSeconds(60 + 2.0 * sampleCount / Recorder.SampleRate));
        hook.Listening = true; // so Esc is reported while working
        return work.Token;
    }

    private void EndWork()
    {
        hook.Listening = false;
        work?.Dispose();
        work = null;
    }

    /** Transcribes a saved recording again (one that failed or was cancelled) and copies the result. */
    public async void Retry(Entry e)
    {
        if (state != State.Idle) return;
        state = State.Processing;
        indicator.SetMode(IndicatorView.Mode.Working);
        try
        {
            var samples = await Task.Run(() => AudioStore.Load(e.Id));
            if (samples == null || samples.Length == 0) throw new InvalidOperationException("That recording couldn't be read");
            var ct = StartWork(samples.Length);
            var target = e.AppKey.Length > 0 ? new ActiveApp(e.AppKey, e.AppLabel) : null;
            var outcome = await Task.Run(() => dictation.Process(samples, target, ct: ct, entryId: e.Id));
            indicator.SetMode(IndicatorView.Mode.Idle);
            if (outcome.Text.Length > 0)
            {
                await TextInjector.Copy(outcome.Text);
                Sounds.Play(Sounds.Kind.Done);
                Say("Transcribed · copied to the clipboard", 2600);
            }
            else Say("Didn't catch anything", 1800);
        }
        catch (OperationCanceledException) when (userCancelled)
        {
            indicator.SetMode(IndicatorView.Mode.Idle);
            Say("Cancelled", 1200);
        }
        catch (Exception ex)
        {
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say(ex is OperationCanceledException ? "Timed out" : ex.Message, 4500);
        }
        finally
        {
            EndWork();
            state = State.Idle;
            App.Current.RefreshAfterDictation();
        }
    }

    private void Reset()
    {
        state = State.Idle;
        handsFree = false;
        hook.Listening = false;
        tick.Stop();
    }

    private async void Finish(bool trimSilence = false)
    {
        if (state != State.Recording) return;
        // Stopping lets the recorder hand over its last fraction of a second, which can take a moment:
        // it is done off the window's thread. The state changes first, so nothing else starts meanwhile.
        state = State.Processing;
        hook.Listening = false;
        tick.Stop();
        handsFree = false;
        var samples = await Task.Run(() => recorder.Stop());
        var lastVoice = recorder.LastVoiceSample;
        int speech = recorder.SpeechChunks, lateSpeech = recorder.LateSpeechChunks;
        try { if (appLookup != null) app = await appLookup; } catch { }

        // Auto-stop: drop the long silent tail (keep half a second after the last speech).
        if (trimSilence && lastVoice > 0)
        {
            var keep = Math.Min(samples.Length, lastVoice + Recorder.SampleRate / 2);
            samples = samples[..keep];
        }

        if (samples.Length < Recorder.SampleRate * 3 / 10)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Say($"Too short. Hold {Host.Shortcut} while you talk.", 2200);
            return;
        }
        float peak = 0;
        foreach (var v in samples) { var a = Math.Abs(v); if (a > peak) peak = a; }
        if (peak < 0.0005f)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say("The mic heard nothing. Check the input device in your sound settings.", 4500);
            return;
        }

        // A quick press with nothing said: only the start tone or room noise got in, and Whisper
        // would turn that into "Thank you." Six chunks is 300 ms of sound.
        var heard = lateSpeech >= 2 || speech >= 6;
        if (!heard && samples.Length < Recorder.SampleRate * 5 / 2)
        {
            state = State.Idle;
            indicator.SetMode(IndicatorView.Mode.Idle);
            Say("Didn't catch anything", 1800);
            return;
        }

        state = State.Processing;
        Sounds.Play(Sounds.Kind.Stop);
        indicator.SetMode(IndicatorView.Mode.Working);
        var target = app;
        var ct = StartWork(samples.Length);
        try
        {
            var outcome = await Task.Run(() => dictation.Process(samples, target, sparse: speech < 6, ct: ct));
            hook.Listening = false;
            var pasted = TextInjector.Result.Pasted;
            if (outcome.Text.Length > 0)
            {
                pasted = await TextInjector.Paste(outcome.Text, target);
                // The "done" tone means the text is in your app; when it could only be copied, the message says so instead.
                if (pasted == TextInjector.Result.Pasted) Sounds.Play(Sounds.Kind.Done);
                else if (pasted == TextInjector.Result.Failed) Sounds.Play(Sounds.Kind.Error);
                if (pasted != TextInjector.Result.Pasted) App.Log("Paste: " + pasted + " (keys: " + TextInjector.Method + ", clipboard: " + TextInjector.ClipboardMethod + ")");
            }
            indicator.SetMode(IndicatorView.Mode.Idle);
            // The revert only makes sense when the polished text really went into the app.
            var canRevert = outcome.Plain != null && outcome.Text.Length > 0 && pasted == TextInjector.Result.Pasted;
            plainText = canRevert ? outcome.Plain : null;
            plainEntry = outcome.EntryId;
            plainApp = target;
            revertUntil = DateTime.UtcNow.AddSeconds(RevertSeconds);
            if (outcome.Warning != null) Say(outcome.Warning, 3500);
            else if (outcome.Text.Length == 0) Say("Didn't catch anything", 1800);
            else if (canRevert) Say($"Polished · {Host.Shortcut}+Z for your own wording", RevertSeconds * 1000);
            // Tokalot couldn't press the paste shortcut itself (see Settings › Setup).
            else if (pasted == TextInjector.Result.Copied) Say(TextInjector.ClipboardNeedsWlCopy ? $"Copied · press {Host.PasteKeys} (install wl-clipboard to paste automatically)" : $"Copied · press {Host.PasteKeys}", 3500);
            else if (pasted == TextInjector.Result.Failed) Say("Couldn't reach the clipboard. The text is in Tokalot's history.", 4500);
            dictation.KeepAudio(samples, outcome.EntryId, null, target);
        }
        catch (OperationCanceledException) when (userCancelled)
        {
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Cancel);
            Say("Cancelled · the recording is in history", 2400);
            dictation.KeepAudio(samples, null, "Cancelled", target, cancelled: true);
        }
        catch (Exception e)
        {
            var message = e is OperationCanceledException ? "Timed out" : e.Message;
            App.Log("Dictation failed: " + e);
            indicator.SetMode(IndicatorView.Mode.Idle);
            Sounds.Play(Sounds.Kind.Error);
            Say(message + " · the recording is in history", 4500);
            dictation.KeepAudio(samples, null, message, target);
        }
        finally
        {
            EndWork();
            state = State.Idle;
            App.Current.RefreshAfterDictation();
        }
    }

    public void Dispose()
    {
        hook.Dispose();
        recorder.Dispose();
        dictation.Dispose();
        Player.Stop(false);
        pill.Close();
        indicator.Close();
    }
}
