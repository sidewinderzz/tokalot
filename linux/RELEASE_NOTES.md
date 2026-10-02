**Beta.** This is the first Linux build of Tokalot. It is a port of the Windows app with the same pages, settings and dictation pipeline.

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

1. Download `Tokalot-linux-x64-<version>.tar.gz` below and unpack it, for example:

   ```
   mkdir -p ~/.local/opt && tar -xzf Tokalot-linux-x64-*.tar.gz -C ~/.local/opt
   ```

2. Run `~/.local/opt/Tokalot/Tokalot`. The Home page and Settings › Setup show the two one-time permission commands (keyboard shortcut and paste) with a Copy button; run them, then log out and back in.
3. Add a free Groq API key in Settings, click into any text box, hold **Ctrl+Super** and talk.

The full guide, including what the permissions mean and the known limits per desktop, is in `README.md` inside the download and in [`linux/README.md`](https://github.com/sidewinderzz/tokalot/blob/main/linux/README.md).

There is no in-app updater yet: to update, download the newer build and unpack it over the old one.
