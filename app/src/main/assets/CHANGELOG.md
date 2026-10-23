# Tokalot for Android: what's new

<!--
Shown in the app (the update banner's "What's new" and Settings › About › Changelog), and used as
the text of each GitHub release. Add a "## <versionName>" section, newest first, every time
versionName in app/build.gradle.kts goes up. Plain sentences, one "- " line per change.
-->

## 1.46 · 2026-10-23
- New: Tokalot Keyboard, an optional keyboard in the style of Gboard. Turn it on in Settings › Keyboard.
- It autocorrects as you type (teh becomes the, dont becomes don't), and a backspace straight after puts back what you typed. Three suggestions sit above the keys, and between words it guesses the next one.
- Swipe typing: slide from letter to letter, in any text box but a password one. Slide along the space bar to move the cursor (an emoji or accented letter is one step), and left from backspace to select whole words, which go when you let go (slide back to keep them). Slide all the way to the left edge to delete everything in the box, and tap Undo on the bar to bring it back.
- Long-press a letter for its symbol or accents. There's a symbols page, a number pad in number fields, an optional number row, and an emoji page with search and skin tones.
- The mic on the keyboard's bar is Tokalot's own five bars, which bounce with your voice while it records and ripple while it transcribes. It dictates the same as the floating button, which hides while the keyboard is up.
- A clipboard: the button next to ✨ opens what you copied in the last hour, and a long press pins one so it stays. Something you just copied shows on the bar, ready to paste, with a link, email address or phone number in it on its own button. It stays on this phone and skips passwords.
- Themes: light, dark, or matching your phone, in four styles (Classic, Tinted, Borderless, High contrast) and your choice of colour, under Settings › Keyboard.
- The keyboard's own menu: tap the four-squares button at the left of its top bar to move, remove or add the bar's buttons (clipboard, AI fix, emoji, light/dark, number row, settings; the mic always stays), change the keyboard's height, switch its look, and flip the common settings.
- A screenshot you just took, or a picture you copied, shows on the bar as a tiny preview; tap it to paste the picture into apps that take pictures. A sign-in code texted to you shows on the bar too, ready to tap. Both are optional: allow photos and "Codes from texts" once in Settings › Keyboard.
- Suggestions show in every text box but password ones, and the number row is shorter than the letter rows, as on Gboard.
- The ✨ button fixes what you typed with your AI cleanup: spelling, typos, capitals and punctuation. It works on the selected text, or the whole text box, and Undo puts it back.
- Get beta updates, under Settings › About: try new features before they're released. Turn it off to go back at the next release.
- Words the keyboard doesn't know, such as names and farm terms, go into your dictionary once you've kept them twice, so they sync and dictation spells them right too. What else it learns from your typing stays on this phone.
- Light and dark to match the app, in your Tokalot color, with key sound and vibration switches. Its settings sync to your other devices.

## 1.45 · 2026-10-23
- Settings is now a short list of pages, like Android's own Settings. Each row says what it's set to; tap one to open it. No more folding sections that stay open.
- New Privacy page with three levels: Everything on, Balanced and Most private. A level sets the privacy switches all at once, and you can still change any switch. A change made anywhere shows up there, and a mix that matches no level shows as Custom.
- A new install starts with a short welcome: pick a level, then allow the mic, turn on the accessibility switch, and paste a key (or download the on-device model). If you updated, your settings didn't change; a card on Home points to the new page.
- With Learn from my corrections on, a word you spell out while dictating goes straight into your dictionary, marked learned, instead of waiting for you to tap Add.
- The "Without a text box" settings are now called Shortcuts.

## 1.43 · 2026-10-20
- After an update, Tokalot checks that the floating mic button came back. Some phones, such as Samsung, switch it off with every update. If that happens you get a notification that takes you straight to the switch.
- New in Settings › Setup: Stay on after updates. Run one command from a computer once (Tokalot shows it, with a Copy button), and from then on Tokalot turns the mic button back on by itself after an update.

## 1.42 · 2026-10-20
- Notes get a short title, written by the AI when a note is made or first opened. It shows on the note and in the list. Rename it from the ⋮ menu.
- While a recording plays, the page follows the word being said. Scroll yourself and it stops; Back to playing brings it back. The Timeline marks the point being played.
- A one-time tip says you can tap any word to play from there.
- The summary length is now a small label on the Summary card. Tap it to pick another length.
- Quieter Summary and Timeline buttons, more room for the position bar (the time played and left now sit under it), and placeholder lines while a summary is being made.
- Deleting a note no longer asks first: a bar shows Undo for a few seconds instead.
- TalkBack reads a note's text as one block, with a Play from start action.

## 1.41 · 2026-10-20
- On a note, the Summary and Timeline buttons are now at the top, above the text, and open right there. They're for when you want the gist instead of reading, so they come first.

## 1.40 · 2026-10-20
- If Tokalot's accessibility switch is on but Android hasn't started it (some phones, such as Samsung, can do this after an update), the floating mic button can't show. Home now says so, with a Fix it button that opens Tokalot's accessibility page so you can turn it off and on again.

## 1.39 · 2026-10-19
- Tapping Update checks GitHub again first, so it always downloads the newest version, even if another one came out after the banner appeared.

## 1.38 · 2026-10-19
- Notes is a clean list now: tap a note to open it. No more Open buttons.
- A voice note shared from WhatsApp (or any app) is one note, not two. It opens like any other note, with the transcription first and a slim player at the bottom.
- Tap any word in a voice note's transcription to play from there. The player has a seek bar and speeds of 1×, 1.25×, 1.5× and 2×, and you can replay a note any time.
- Summary and Timeline are two small buttons under the transcription. They open right below it, and a long transcription folds up while one is open (tap "Show the whole text" to see it all).
- Summary length, skip silence, skip ums, copy, share and delete are in the ⋮ menu on the note.
- Skip silence listens for voice in the recording itself, so it works with an engine or wind in the background and no longer breaks playback or cuts off words. It's on by default.
- Summary length, playback speed and the skip switches sync with your other devices.

## 1.37 · 2026-10-19
- Home's stats card is simpler: just your three numbers and a gray View stats link. Clean-ups and the all-devices totals are on the word stats page.
- The month in progress shows as partial: Home says how far into the month you are (Day 19 of 31), and the stats page reads "So far" with where you're on track to finish. The average month leaves the unfinished month out.
- Time saved now compares with average phone typing (36 words a minute), and says so.
- Sync carries much more: your settings (speech and cleanup choices, model names, switches, sounds, theme, accent, learned words, app overrides) and each device's clean-up counts. Settings that depend on the phone, like the on-device model, recording retention and the volume-key shortcut, stay on each device.
- New in Settings › Your data › Sync: Include dictation history (off by default) shares the text of your newest 1,000 dictations, so every device has the same history and stats. Recordings never sync.
- If another device syncing the same file has a newer Tokalot, Home shows an "Update Tokalot to sync everything" card with a button to check for updates.

## 1.36 · 2026-10-19
- Choose a bigger on-device model in Settings › Speech & cleanup › On-device model: Standard (60 MB, as before), Better (190 MB) or Best (574 MB, the same model as Groq Whisper, compressed). Bigger ones make fewer mistakes, most of all with background noise, and use more of the phone's cores. Your choice is used once it's downloaded; until then the one you have keeps working.
- New: Prefer on-device in weak signal. With no connection, or a speech service that was slow to answer while you talked, the dictation goes straight to the phone's model, and the cloud only gets a few seconds before the phone takes over (instead of up to half a minute).
- The on-device model runs much faster: phones from about 2018 on get a build that uses their CPU's faster maths instructions, and a short dictation no longer pays for a full 30 seconds of audio.
- While recording, slide the floating button sideways to do that dictation on the phone. It works in tap mode and hold-to-talk; sliding up to the X still cancels.
- When nothing was said, the words in your dictionary are no longer typed back. Whisper sometimes returns them (with a stray "Thank you.") on a near-silent clip; that now counts as nothing said.
- Settings › API keys: the "Get one at" addresses are links now. Tap to open the page, hold to copy the address.

## 1.35 · 2026-10-18
- Accessibility shortcut: if Android's floating accessibility button is on for Tokalot (the default on some phones, such as OnePlus), Settings › Recording & look says so and how to switch to just the volume keys, and its link opens Tokalot's own shortcut page. Removing the floating button by dragging it away can switch Tokalot off on those phones.

## 1.34 · 2026-10-18
- Share a voice message to Tokalot from WhatsApp (or any app) and it's transcribed into a voice note, with the recording kept. Shared text is saved as a note. Sharing turns voice notes on if they were off. Needs a Groq or OpenAI key.
- Open a voice note to play it: the transcript lights up word by word as it plays, and tapping a word jumps there. Skip silence and Skip ums make it quicker to listen to.
- Summarize any note at five levels of detail, from one line to everything, with the cleanup AI you already use. Summaries are saved with the note.
- Make an index of a long voice note: every point in the order it was said, grouped by topic. Tap one to hear that part.
- The "Voice notes are a beta. Worth keeping?" card on Notes has an X to close it, and goes away for good once you close or answer it.

## 1.33 · 2026-10-17
- Sounds: turn them on in Settings › Recording for a short tone when recording starts, stops, finishes or fails, alongside the buzz. Pick a style: Soft, Chime, Retro or Bubble. Off unless you turn it on.

## 1.32 · 2026-10-16
- Tap the stats card on Home to see all your word stats: words for every month since you started (every synced device added together), your best and average month, how long it would have taken to type, speaking pace, longest dictation, streaks, busiest day, top apps, and a breakdown by device.

## 1.31 · 2026-10-14
- The floating record button shows up when you tap a text box, every time. In some apps it could stay hidden until the keyboard was closed and opened again; it now also watches where the cursor lands, and keeps looking a little longer while the keyboard slides up.

## 1.30 · 2026-10-10
- When the on-device speech model hits an error (the phone short of memory, say), the recording is kept in history with a Transcribe button, instead of "Didn't catch anything" and the words lost.
- Cancelling a retry in the app no longer also cancels a dictation running on the floating button at the same time.
- A failed load of the on-device model no longer deletes it. It's only removed if the file itself is damaged; otherwise you're told the phone is short of memory.
- A notes file that can't be read is set aside (kept for recovery) instead of being overwritten with just the newest note.
- History is saved in the background, so Home doesn't stutter with a long history and "My wording" can't crash the floating button when storage is full.
- Stopping a long dictation no longer freezes the floating button for a moment.
- Tapping the mic no longer downloads every provider's model list (tens of KB) just to open the connection.
- Emoji and unusual characters from the on-device model come through intact.

## 1.29 · 2026-10-09
- Cleanup can no longer type a reply of its own. When a dictation was itself a request to an AI ("give me three designs"), the cleanup could answer it, and its answer was typed along with your words. The instructions are firmer, and a result with clearly more words than you said is thrown away in favour of the backup or basic cleanup.

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
