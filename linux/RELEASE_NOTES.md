**Beta.** This is the first Linux build of Tokalot. It is a port of the Windows app with the same pages, settings and dictation pipeline.

## What has and hasn't been tested

It was built and run under WSL (Ubuntu 24.04): the window, all pages, the recording indicator, the Ctrl+Super shortcut (with a simulated keyboard), recording (with a simulated microphone), offline transcription and the paste keystroke were exercised there.

It has **not yet been run on a real Linux desktop**. Expect rough edges with:

- recording from a real microphone (`pw-record`, `parec` or `arecord`)
- text actually landing in the app you're typing in, and your clipboard being put back afterwards
- the tray icon (GNOME needs the AppIndicator extension)
- the on-screen recording indicator: staying on top, never taking focus, and its position on Wayland
- tiling window managers and multi-monitor or fractionally scaled setups

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
