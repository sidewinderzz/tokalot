# Tokalot for Android: what's new

<!--
Shown in the app (the update banner's "What's new" and Settings › About › Changelog), and used as
the text of each GitHub release. Add a "## <versionName>" section, newest first, every time
versionName in app/build.gradle.kts goes up. Plain sentences, one "- " line per change.
-->

## 1.28 · 2026-10-09
- Settings › About can install a new version right there: "Check for updates" now brings up an Update button, even if you closed the banner on Home.
- Voice notes are part of sync: with a sync file set up, notes made on one device show up on the others, and deleting one deletes it everywhere. Update Tokalot on every device to share them.
- Dictating with no keyboard showing types into the selected text box again (from the Dictate tile, for example), except in apps on the new Clipboard only list (Settings › Recording & look › Without a text box), which get the clipboard. Launchers are on it to start with, so Niagara's hidden search box stays out of it.
- A quick tap with nothing said no longer types "Thank you." Groq Whisper now says when the audio was most likely silence, and that stock phrase is dropped then. A "thank you" you actually say still comes through.

## 1.27 · 2026-10-08
- A short What's new tour opens once after this update (and from Settings › About).
- Spell a word out while dictating ("Kowalski, K-O-W-A-L-S-K-I") and it's written once, without the letters. Home then offers to add it to your dictionary.
- A Dictate tile in Quick Settings: talk with no text box open, and the text goes into the text box if there is one, or onto the clipboard if not.
- Dictated text is only typed when a keyboard is showing; otherwise it goes on the clipboard. Launchers like Niagara keep a hidden search box ready on the home screen, and text no longer lands in it.
- Voice notes (beta, Settings › Recording & look › Without a text box): a Voice note tile and a Notes page, one note per dictation, each also copied to the clipboard. The Notes page asks whether it's worth keeping.
- With voice notes on, start any dictation with "Note this" or "Make a note" (the phrases can be changed) and it's saved as a note instead of typed, with a button to type it after all.
- On Android 11 and newer, holding both volume keys (Android's accessibility shortcut) can start a dictation or a voice note instead of switching Tokalot off and on (Settings › Recording & look).
- Settings is grouped into five categories you tap to open and close; the app remembers which are open.
- Dictating into the middle of a sentence fits in: no capital at the start, and no period when the sentence carries on. Style › Fit into the sentence turns it off.
- The update banner on Home has a small "What's new" arrow that shows what changed since your version.
- Settings › About has a Changelog with the latest versions' changes.
- The floating button keeps out from under a taller keyboard (numbers, emoji, a toolbar), and when you drop it onto the keyboard it stays at that spot on the keys.
- The floating button no longer stays hidden when the keyboard opens a moment after the text box is selected, and tapping a text box that already had the cursor brings it back.

## 1.26 · 2026-10-07
- Long dictations in noisy places are sent in pieces while you talk too: after 15 seconds a dip between words is enough to cut at, and after 25 seconds the quietest moment is used.
- Settings › Speed timings split the speech step into prepare, send and wait.
- An upload that fails within a couple of seconds is tried once more before falling back to the phone's own model.
- AI cleanup is still used when the speech service answered a moment before.

## 1.24 · 2026-10-06
- Quick mode (off by default, Settings › Speed): a short dictation with nothing for the AI to fix skips the cleanup and is tidied on the phone, which is instant.
- Settings › Speed lists how long speech-to-text and cleanup took for your last dictations.
- A progress line runs round the floating button while transcribing, paced by how fast your connection is right now.

## 1.22 · 2026-10-06
- An API key you clear on this phone is no longer filled back in from the sync file.

## 1.21 · 2026-10-06
- Audio is sent as AAC on mobile data or a slow connection, so uploads are smaller.
- With no connection at all, the phone's own model and basic cleanup are used straight away.
- Cleanup replies cut off by the length limit are noticed, and the limit is higher.
- The button's first spot waits for the keyboard to settle.
- A microphone that stops mid-recording says so, and a full phone no longer loses the text.

## 1.20 · 2026-10-05
- No more pop-ups about updates. A new version waits quietly on the Home page.

## 1.19 · 2026-10-05
- Learn names and terms from your corrections (off by default, on the Dictionary page). When you respell a name in a dictation, that spelling is added to your dictionary, with a note to undo it.

## 1.18 · 2026-10-04
- Long dictations come back faster: each pause sends what you've said so far in the background, so only the last few seconds are left when you stop.
- Audio is sent as FLAC, the same sound in about half the data.

## 1.17 · 2026-10-03
- The floating button stays where you put it on the screen, with one spot for upright and one for sideways.

## 1.16 · 2026-10-03
- New versions are found in the background, without opening the app.

## 1.15 · 2026-10-02
- A Paste button for API keys. Pasted keys are tidied and checked.

## 1.14 · 2026-10-01
- "Polish my wording" (off by default) lets the AI tighten what you said, with a "My wording" button to put your own words back.
- Auto-stop no longer cuts off quiet speech.

## 1.13 · 2026-10-01
- A light rim makes the button easier to see on dark apps.
- Settings › Recording › Reset position puts the button back in its first spot.

## 1.12 · 2026-10-01
- Optional sync of your dictionary, snippets, styles and instructions through one file in a folder you already sync.

## 1.11 · 2026-10-01
- The cancel X only appears after 7 seconds of transcribing.
- The button is no longer missed when an app reports the text box a moment late.

## 1.10 · 2026-10-01
- Home shows your speaking pace instead of an estimated cost.

## 1.9 · 2026-10-01
- On Android 13 and newer, text is typed at the cursor, so formatting in rich text boxes is kept.
- Tap the button while transcribing to cancel. Failed and cancelled recordings keep their audio and can be transcribed again from history.
- API keys are encrypted on the phone.
- Better screen-reader labels and bigger touch targets.

## 1.8 · 2026-09-30
- Many small fixes: soft speakers' recordings are no longer trimmed, undo only shows when there's something to undo, and a space is added before a following word.
