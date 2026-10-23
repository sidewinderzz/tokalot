**Beta.** This is the first Linux build of Tokalot. It is a port of the Windows app with the same pages, settings and dictation pipeline.

## New in 0.13.0

- New: **Terminal commands (beta)**, in Settings › Style, off until you turn it on. In a terminal, start with "shell" and say what you want ("shell, show me the ten biggest files in this folder") or speak a command ("shell git commit dash m quote fix the paste bug quote") and a shell command is pasted. Anything that doesn't start with "shell" is dictated as normal text, so prompts for Claude Code and the like are left alone (**Start commands with "shell"** turns that off). It is never run: you read it and press Enter yourself. It knows your distribution, its package manager and which handier tools you have installed (`fd`, `rg`, `bat`…), which are sent to your cleanup AI with the request, so "update the system" gives the right command for your system. It needs AI cleanup and a desktop where Tokalot can tell the window in front is a terminal (X11, KDE, Sway, Hyprland, niri).
- For testing: `Tokalot --transcribe <recording>` runs a whole dictation on an audio file and prints the text (`--terminal` to dictate as if into a terminal).

## New in 0.12.0

- Pasting into a terminal works on Wayland. Terminals paste with Ctrl+Shift+V, and Tokalot used to press Ctrl+V because a Wayland desktop doesn't say which window is in front. It now asks the desktop itself: **KDE Plasma** (through a one-off KWin script), **Sway**, **Hyprland** and **niri**. On GNOME and other desktops nothing changes.
- That also turns on per-app styles (messages, email, AI and code) on those desktops; before, every dictation on Wayland used "Everything else".
- `Tokalot --diagnose` now says which window it sees in front ("Active-app detection") instead of just "off" on Wayland.
- Checked on KDE Plasma 6 on Wayland. The Sway, Hyprland and niri readers follow what their tools print and haven't been run on those desktops, so please say if one doesn't work.

## New in 0.11.0

- Notes get a short title, written by the AI when a note is made or first opened. It shows on the note and in the list. Rename it from the **⋮** menu.
- While a recording plays, the page follows the word being said. Scroll yourself and it stops; **Back to playing** brings it back. The Timeline marks the point being played.
- A one-time tip says you can click any word to play from there.
- The summary length is now a small label on the Summary card. Click it to pick another length.
- Quieter Summary and Timeline buttons, a cleaner position bar with the time played and left under it, and placeholder lines while a summary is being made.
- Deleting a note no longer asks first: a bar shows **Undo** for a few seconds instead.
- Screen readers read a note's text as one block.
- If ffmpeg isn't installed, the player says so: it is needed to jump to a word and skip silence.

## New in 0.10.1

- On a note, the **Summary** and **Timeline** buttons are now at the top, above the text, and open right there.

## New in 0.10.0

- Notes is a clean list now: click a note to open it. Each note opens on its own page with its text first, **Summary** and **Timeline** as small buttons under it that open right below it (a long text folds up while one is open), and the rest (summary length, skip silence, skip ums, copy, delete) in the **⋮** menu. Esc or **Notes** goes back to the list.
- Recordings play inside Tokalot: click any word to hear it from there, with a player along the bottom of the window (play/pause, a position bar, the time, and 1×, 1.25×, 1.5× or 2× speed). The word being said is lit up as it plays.
- Skip silence listens for voice in the recording itself, so steady noise like an engine or wind counts as silence and words are never cut. Playing from any point, speeds and skipping need **ffplay** (part of ffmpeg) or **mpv**; finding the silence needs **ffmpeg**. Without them a recording plays from the start.
- Summary length, playback speed and the skip switches sync with your other devices.
- New: **Settings › AI assistants › Let AI assistants read my notes** makes Tokalot a read-only MCP server, so Claude Desktop, Claude Code or any MCP app on this computer can list, search and read your notes. Off by default.
- Built for ARM64 Linux too (Raspberry Pi 4/5 and other 64-bit ARM computers): download the **linux-arm64** archive.

## New in 0.9.0

- Voice notes (beta) can now turn recordings into notes. Switch on Settings › Recording › Voice notes (beta), open Notes, and click **Add a recording** to pick an audio file (a WhatsApp voice message saved to this computer, say: .opus, .ogg, .m4a, .mp3, .wav and more). It's transcribed with Groq Whisper (or OpenAI) and saved as a note. A recording that fails stays on the page with Try again.
- Each note has **Summarize** (five lengths, from one line to everything) and, for a recording, an **Index** of every point made with when it was said, plus **Play recording**. Uses the cleanup AI you picked in Settings.
- Notes from your phone and other computers show up on the Notes page through sync, as on Windows.
- The Notes beta card can be closed.

## New in 0.8.0

- Choose a bigger offline model in Settings › Offline model: Standard (60 MB, as before), Better (190 MB) or Best (574 MB, the same model as Groq Whisper, compressed). Bigger ones make fewer mistakes, most of all with background noise. Your choice is used once it's downloaded; until then the one you have keeps working.
- New: Prefer offline in weak signal (Settings › Offline model). With no connection, or a speech service that was slow to answer while you talked, the dictation goes straight to the offline model, and the cloud only gets a few seconds before the offline model takes over.
- When nothing was said, the words in your dictionary are no longer typed back. Whisper sometimes returns them on a near-silent clip; that now counts as nothing said.

## New in 0.7.0

- Pick a sound style in Settings › Recording: Soft (the original), Chime, Retro or Bubble. Click one to hear it.

## New in 0.6.0

- Click the stats card on Home to see all your word stats: words for every month since you started (every synced device added together), your best and average month, how long it would have taken to type, speaking pace, longest dictation, streaks, busiest day, top apps, and a breakdown by device.

## New in 0.5.3

- Cleanup can no longer type a reply of its own. When a dictation was itself a request to an AI ("give me three designs"), the cleanup model could answer it and its answer was pasted along with your words. The instructions are firmer, and a result with clearly more words than you said is now thrown away in favour of the backup or basic cleanup.

## New in 0.5.1

- Long dictations in noisy places: Tokalot sends a long dictation for transcription in pieces while you talk, cutting at pauses. Where it is never quiet it never saw a pause, so the whole recording went up at the end. It now also cuts at a dip between words after 15 seconds, and at the quietest moment after 25. Settings › Recording › "Transcribe while I talk" turns pieces off.

## New in 0.5.0

- Quick mode (beta), off until you turn it on in Settings › Speed: when a short dictation (20 words or fewer) has nothing for the AI cleanup to fix, the cleanup is skipped and the text is tidied on this computer, which is instant.
- The log now records how long speech-to-text and cleanup took for each dictation, in seconds.

## New in 0.4.2

- A hands-free recording stops within a few seconds of the screen being locked; what you said is kept in history.
- A microphone that is slow to start, such as a Bluetooth headset, gets a second, longer chance instead of "no microphone".
- If the login keyring didn't answer in time at start-up, Tokalot asks it again at the next dictation instead of showing no keys until a restart.
- An API key you clear is no longer filled back in from the sync file.
- The offline model is only removed when its file is actually damaged.

## New in 0.4.1

- Recording on PipeWire systems (most current distros): `pw-record` sends its audio in a different byte order from the other recorders, and Tokalot was reading it as noise. It now reads it correctly. This was found by reading the code, not on a real PipeWire machine, so please report whether recording works for you.
- A short hold is no longer mistaken for a tap (hands-free) when the microphone is slow to open.
- A program that isn't responding can no longer hold up the start of transcription.
- Long dictations: if one background piece fails, Tokalot stops sending pieces and transcribes the whole recording once, and silence after your last words is no longer sent on its own.

## New in 0.4.0

- Long dictations come back faster: each time you pause, what you've said so far is sent to the speech service in the background, so when you stop only the last few seconds are left to transcribe. Nothing is pasted until you finish. Needs a cloud speech service and AI cleanup; Settings › Recording › "Transcribe while I talk" turns it off.
- Smaller uploads: audio is sent as FLAC (the same sound in about half the data) instead of WAV.
- The log (`Tokalot --diagnose` says where it is) now records how long speech-to-text and cleanup took for each dictation.

## New in 0.3.1

- No more freezes under fractional scaling: on X11, when a monitor is set to 125%, 150% and so on, the window now draws with the processor instead of the graphics card, which could stall it for many seconds. Start with `--gpu` to keep the graphics card anyway.

## New in 0.3.0

- Your own words are kept by default: cleanup removes fillers and fixes punctuation and formatting without rewording you.
- "Polish my wording" (Style page) is opt-in: the AI may also tighten and clarify what you said. For a few seconds after a polished dictation, **Ctrl+Super+Z** puts your own wording back.
- Recordings are no longer cut off on quiet microphones: what counts as "talking" now follows the room's background level, so hands-free auto-stop doesn't end a recording mid-sentence when you speak softly.
- The hands-free reminder above the indicator has an X that turns it off for good (Settings › Recording brings it back).

## New in 0.2.0

- Optional settings sync (Settings › Sync): keep your dictionary, snippets, styles and instructions in one file inside a folder you already sync, and point your other devices, including the Windows and Android apps, at the same file. No account, no server; API keys are left out unless you choose to include them.

## What has and hasn't been tested

It was built and run under WSL (Ubuntu 24.04): the window, all pages, the recording indicator, the Ctrl+Super shortcut (with a simulated keyboard), recording (with a simulated microphone), offline transcription and the paste keystroke were exercised there. Sync was checked there with two copies sharing one file.

It has **not yet been run on a real Linux desktop**. Expect rough edges with:

- recording from a real microphone (`pw-record`, `parec` or `arecord`)
- text actually landing in the app you're typing in, and your clipboard being put back afterwards
- the tray icon (GNOME needs the AppIndicator extension)
- the on-screen recording indicator: staying on top, never taking focus, and its position on Wayland
- tiling window managers and multi-monitor or fractionally scaled setups
- the Ctrl+Super+Z revert after a polished dictation: it has not been exercised at all yet, and in an app that itself treats Ctrl+Super+Z as undo it may undo one step too many (redo once in that app)
- sync through a real sync service (it was only tested with a local file), and the file choosers that pick the sync file

If you try it, please open an issue saying which distro and desktop you use and what happened. `Tokalot --diagnose` prints a summary worth pasting in.

## Install

1. Download `Tokalot-linux-x64-<version>.tar.gz` below (or `Tokalot-linux-arm64-<version>.tar.gz` on an ARM64 machine such as a Raspberry Pi 4/5) and unpack it, for example:

   ```
   mkdir -p ~/.local/opt && tar -xzf Tokalot-linux-*.tar.gz -C ~/.local/opt
   ```

2. Run `~/.local/opt/Tokalot/Tokalot`. The Home page and Settings › Setup show the two one-time permission commands (keyboard shortcut and paste) with a Copy button; run them, then log out and back in.
3. Add a free Groq API key in Settings, click into any text box, hold **Ctrl+Super** and talk.

The full guide, including what the permissions mean and the known limits per desktop, is in `README.md` inside the download and in [`linux/README.md`](https://github.com/sidewinderzz/tokalot/blob/main/linux/README.md).

There is no in-app updater yet: to update, download the newer build and unpack it over the old one.
