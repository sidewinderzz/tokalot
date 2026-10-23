**Experimental. No person has used this on a real Mac yet.** It is the Linux/Windows app ported to macOS, for Macs with Apple silicon (M1 or newer) on macOS 15 Sequoia or newer (14 may work). Hold **Ctrl+Cmd** (⌃⌘) and talk; let go and the text is pasted where your cursor is. Tap Ctrl+Cmd once for hands-free; Esc cancels.

**Mac testers wanted:** if you try it, please comment on the ["Mac testers wanted" issue](https://github.com/sidewinderzz/tokalot/issues/6), even if everything works.

## New in 0.12.0

- A tidier Settings › About: the version and Check for updates sit together at the top, and Open-source licenses, Source code and Quit are rows you click, like the rest of Settings.

## New in 0.10.0

- Settings is now split into pages, listed down the left with a line saying what each is set to. Click one to open it. No more long scroll.
- New **Privacy** page with three levels: **Everything on**, **Balanced** and **Most private**. A level sets the privacy switches all at once, and you can still change any switch. A change made anywhere shows up there, and a mix that matches no level shows as Custom.
- A new install starts with a short welcome: pick a level, then set up what it needs (a key, or the offline model). If you updated, your settings didn't change; a card on Home points to the new page.

## New in 0.9.0

- Notes get a short title, written by the AI when a note is made or first opened. It shows on the note and in the list. Rename it from the **⋮** menu.
- While a recording plays, the page follows the word being said. Scroll yourself and it stops; **Back to playing** brings it back. The Timeline marks the point being played.
- A one-time tip says you can click any word to play from there.
- The summary length is now a small label on the Summary card. Click it to pick another length.
- Quieter Summary and Timeline buttons, a cleaner position bar with the time played and left under it, and placeholder lines while a summary is being made.
- Deleting a note no longer asks first: a bar shows **Undo** for a few seconds instead.
- Screen readers read a note's text as one block.
- If ffmpeg isn't installed, the player says so: it is needed to jump to a word and skip silence.

## New in 0.8.1

- On a note, the **Summary** and **Timeline** buttons are now at the top, above the text, and open right there.

## New in 0.8.0

- Notes is a clean list now: click a note to open it. Each note opens on its own page with its text first, **Summary** and **Timeline** as small buttons under it that open right below it (a long text folds up while one is open), and the rest (summary length, skip silence, skip ums, copy, delete) in the **⋮** menu. Esc or **Notes** goes back to the list.
- Recordings play inside Tokalot: click any word to hear it from there, with a player along the bottom of the window (play/pause, a position bar, the time, and 1×, 1.25×, 1.5× or 2× speed). The word being said is lit up as it plays.
- Skip silence listens for voice in the recording itself, so steady noise like an engine or wind counts as silence and words are never cut. Playing from any point, speeds and skipping need **ffplay** (part of ffmpeg) or **mpv**; finding the silence needs **ffmpeg**. Install them with `brew install ffmpeg` (or mpv). Without them a recording plays from the start, through macOS's own player.
- Summary length, playback speed and the skip switches sync with your other devices.
- New: **Settings › AI assistants › Let AI assistants read my notes** makes Tokalot a read-only MCP server, so Claude Desktop, Claude Code or any MCP app on this computer can list, search and read your notes. Off by default.

## New in 0.7.0

- Voice notes (beta) can now turn recordings into notes. Switch on Settings › Recording › Voice notes (beta), open Notes, and click **Add a recording** to pick an audio file (a WhatsApp voice message saved to this computer, say: .opus, .ogg, .m4a, .mp3, .wav and more). It's transcribed with Groq Whisper (or OpenAI) and saved as a note. A recording that fails stays on the page with Try again.
- Each note has **Summarize** (five lengths, from one line to everything) and, for a recording, an **Index** of every point made with when it was said, plus **Play recording**. Uses the cleanup AI you picked in Settings.
- Notes from your phone and other computers show up on the Notes page through sync, as on Windows.
- The Notes beta card can be closed.

## New in 0.6.0

- Choose a bigger offline model in Settings › Offline model: Standard (60 MB, as before), Better (190 MB) or Best (574 MB, the same model as Groq Whisper, compressed). Bigger ones make fewer mistakes, most of all with background noise. Your choice is used once it's downloaded; until then the one you have keeps working.
- New: Prefer offline in weak signal (Settings › Offline model). With no connection, or a speech service that was slow to answer while you talked, the dictation goes straight to the offline model, and the cloud only gets a few seconds before the offline model takes over.

## New in 0.5.0

- Pick a sound style in Settings › Recording: Soft (the original), Chime, Retro or Bubble. Click one to hear it.

## New in 0.4.0

- Click the stats card on Home to see all your word stats: words for every month since you started (every synced device added together), your best and average month, how long it would have taken to type, speaking pace, longest dictation, streaks, busiest day, top apps, and a breakdown by device.

## New in 0.3.3

- Cleanup can no longer type a reply of its own. When a dictation was itself a request to an AI ("give me three designs"), the cleanup model could answer it and its answer was pasted along with your words. The instructions are firmer, and a result with clearly more words than you said is now thrown away in favour of the backup or basic cleanup.

## New in 0.3.1

- Long dictations in noisy places: Tokalot sends a long dictation for transcription in pieces while you talk, cutting at pauses. Where it is never quiet it never saw a pause, so the whole recording went up at the end. It now also cuts at a dip between words after 15 seconds, and at the quietest moment after 25. Settings › Recording › "Transcribe while I talk" turns pieces off.

## New in 0.3.0

- Quick mode (beta), off until you turn it on in Settings › Speed: when a short dictation (20 words or fewer) has nothing for the AI cleanup to fix, the cleanup is skipped and the text is tidied on this computer, which is instant.
- The log now records how long speech-to-text and cleanup took for each dictation, in seconds.

## New in 0.2.2

- If the Keychain didn't answer in time at start-up, Tokalot asks it again at the next dictation instead of showing no keys until a restart.
- An API key you clear is no longer filled back in from the sync file.
- The offline model is only removed when its file is actually damaged.

## New in 0.2.1

- A short hold is no longer mistaken for a tap (hands-free) when the microphone is slow to open.
- A program that isn't responding can no longer hold up the start of transcription.
- Long dictations: if one background piece fails, Tokalot stops sending pieces and transcribes the whole recording once, and silence after your last words is no longer sent on its own.

## New in 0.2.0

- Long dictations come back faster: each time you pause, what you've said so far is sent to the speech service in the background, so when you stop only the last few seconds are left to transcribe. Nothing is pasted until you finish. Needs a cloud speech service and AI cleanup; Settings › Recording › "Transcribe while I talk" turns it off.
- Smaller uploads: audio is sent as FLAC (the same sound in about half the data) instead of WAV.

Like the rest of the Mac app, these have not been tried by a person on a real Mac.

## What has been checked, and how

Every build is run on one of GitHub's macOS 15 machines (Apple silicon, a virtual machine with no person at it) before it is published. This release passed all of these there:

- The real program inside Tokalot.app starts, prints its `--diagnose` report and loads the offline speech engine.
- Every page draws in light and dark (24 pictures; a sample of them checked by eye), and the 11 settings-sync merge checks pass.
- The API-key store: a key was saved to the login Keychain, changed, read back and deleted.
- The clipboard: text written, read back, and the earlier clipboard put back.
- The keyboard listener: started, and synthetic Ctrl+Cmd, Z and Esc presses were each seen correctly.
- The offline model (60 MB) downloaded, and a sentence spoken by macOS's own voice was transcribed correctly through the dictation pipeline.
- The app itself ran with its window and menu-bar icon (seen in a screen capture), and a second start handed over to the first instead of running twice.
- Pasting: text was pasted into TextEdit, the earlier clipboard came back afterwards, and the "own words back" swap (⌘Z, then paste) worked.
- **A whole dictation:** with the app running and TextEdit in front, Ctrl+Cmd was held (synthetically) while the spoken sentence played into a virtual microphone; the app recorded it, transcribed it offline and pasted *"The quick brown fox jumps over the lazy dog. Please call me back tomorrow morning."* into TextEdit.
- Screen captures taken during that dictation show the recording indicator at the bottom of the screen (listening, then working, then the slim idle bar), with TextEdit keeping the keyboard throughout.
- The downloaded zip unpacks with its signature intact.

## What has never been tried

GitHub's build machine already allows the keyboard, key presses and the microphone for programs it starts, and it has no person, no real microphone and no real keyboard. So none of this has been tried:

- **The permission prompts** (Input Monitoring, Accessibility, Microphone) and the steps to grant them, including the Home warnings and Settings › Setup buttons that open System Settings.
- **Your keyboard:** holding the real Ctrl and Cmd keys, the quick tap for hands-free, Esc, and other apps' Ctrl+Cmd shortcuts still working.
- **A real microphone**, including AirPods, USB and Bluetooth microphones, and the "mic heard nothing" warning.
- **Pasting into everyday apps** (Notes, Mail, Messages, Slack, browsers, Terminal, Word), and per-app styles picked from the app and browser tab you're in.
- **The menu-bar icon's menu** and getting the window back from it, start at login, light/dark following macOS, multiple displays, Retina scaling, full-screen apps and Spaces.
- **The on-screen recording indicator and its messages:** where they appear, staying on top, and clicking the indicator without taking focus from your app.
- The Gatekeeper steps below on a real download, and what macOS does with the permissions after an update.
- Cloud speech-to-text and AI cleanup (they need an API key, which the build machine doesn't have); those parts are shared with the Windows and Linux apps.

## Install

1. Download the **Tokalot-mac-arm64** zip below and double-click it to unzip.
2. Drag **Tokalot.app** into **Applications**.
3. Open it. It isn't signed by Apple yet, so macOS refuses the first time:
   - **macOS 15 Sequoia or newer:** click **Done** on "“Tokalot” Not Opened", then go to **System Settings › Privacy & Security**, scroll down, click **Open Anyway** next to the Tokalot message and confirm.
   - **macOS 14:** right-click Tokalot.app › **Open**, then **Open**.
   - If macOS calls it **"damaged"**: run `xattr -dr com.apple.quarantine /Applications/Tokalot.app` in Terminal and open it again.
4. Tokalot lives in the **menu bar** (five small bars, top right) with no Dock icon. Its Home page lists the permissions still needed, each with a button to the right page of System Settings: **Input Monitoring** (to see Ctrl+Cmd; restart Tokalot after switching it on), **Accessibility** (to press ⌘V for you) and **Microphone** (macOS asks on the first dictation).
5. Add a free Groq API key in Settings, or download the offline model there, click into any text box, hold **Ctrl+Cmd** and talk.

After an update, macOS may still show Tokalot as allowed but stop honouring it: remove it from the Input Monitoring and Accessibility lists and add it again.

The full guide is in [`mac/README.md`](https://github.com/sidewinderzz/tokalot/blob/main/mac/README.md). There's no in-app updater yet: to update, download the newer zip and replace Tokalot.app.
