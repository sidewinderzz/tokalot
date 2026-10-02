# Tokalot for Linux (beta)

Voice typing for any app. Hold **Ctrl+Super** and talk, let go, and the cleaned-up text is pasted
where your cursor is. Tap Ctrl+Super once for hands-free (tap again to finish). **Esc** cancels.
It is the same app as Tokalot for Windows: same screens, same settings file, and backups move
between the two.

Works on X11 and Wayland sessions, 64-bit Intel/AMD. You do not need .NET, and the app itself is
just a folder. It does lean on a few things from the system, nearly all of which a normal desktop
already has (see "What it needs from the system").

## Install

1. Download `Tokalot-linux-x64-<version>.tar.gz` from the project's GitHub Releases page
   (https://github.com/sidewinderzz/tokalot/releases, the release tagged `linux-v<version>`) and
   unpack it somewhere it can stay, for example:

   ```
   mkdir -p ~/.local/opt && tar -xzf Tokalot-linux-x64-*.tar.gz -C ~/.local/opt
   ```

2. Give your user the two permissions below (once), then **log out and back in**.
3. Start it: `~/.local/opt/Tokalot/Tokalot`. On first start it adds itself to your app menu and to
   your login apps (you can turn "Start at login" off in Settings).
4. Open Settings, paste a free Groq API key (or download the 60 MB offline model), click into any
   text box, hold Ctrl+Super and talk.

`Tokalot --diagnose` prints what works on your machine and the exact command for anything that
doesn't. Run it if something isn't working.

## The two permissions

Linux has no one way for an app to see a global shortcut or to press a key that works on both X11
and Wayland, so Tokalot uses the kernel's own input devices. Both need group membership.

**1. Seeing Ctrl+Super** (reading the keyboard devices in `/dev/input`):

```
sudo usermod -aG input $USER
```

**2. Pressing Ctrl+V for you** (a virtual keyboard through `/dev/uinput`):

```
echo 'KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/60-tokalot-uinput.rules && sudo udevadm control --reload-rules && sudo udevadm trigger
```

Then log out and back in (group changes only apply to new logins). Settings › Setup shows a green
"Working" for both when they are in place, and the same commands with a Copy button when not.

What this means, plainly: a member of the `input` group can read every key typed on the machine,
and with the udev rule can also press keys as if typed. So can any program running as you once you
are in the group, not only Tokalot. On X11 programs could already do both; on Wayland this is a
real loosening.

What Tokalot itself does with it: it follows Ctrl, Super, Shift and Alt (down or up) and Esc. While
the shortcut is held, or a recording or transcription is under way, it also notices *that* some
other key was pressed (so Ctrl+Super+arrow stays a desktop shortcut and doesn't start dictation),
not which text you type. Nothing it sees is stored or logged. It does nothing while the screen is
locked or another user is switched in. Its virtual keyboard can only press Ctrl, Shift, V and Insert.

If you would rather not grant the first permission, Tokalot still works by clicking the small
indicator at the edge of the screen. Without the second, text is copied to the clipboard and you
press Ctrl+V yourself; `xdotool` (X11) or `wtype` (Sway, Hyprland and similar; not GNOME or KDE)
are used instead if installed. `ydotool` is not a way round it: it needs `/dev/uinput` too, though
its daemon may already have that access.

## What it needs from the system

| Needed for | What | Usually already there? |
| --- | --- | --- |
| Running at all | glibc 2.34 or newer (Ubuntu 22.04, Debian 12, Fedora 35 and later), X11 or XWayland, fontconfig | Yes |
| The offline speech model | `libgomp.so.1` (package `libgomp1` on Debian/Ubuntu, `libgomp` on Fedora, `gcc-libs` on Arch) | Usually; `Tokalot --diagnose` says if it is missing. Not needed if you only use Groq/OpenAI |
| Microphone | any one of `pw-record` (PipeWire), `parec` (pulseaudio-utils), `arecord` (alsa-utils) | Yes on Ubuntu, Fedora, most desktops |
| Feedback tones, playing recordings | any one of `pw-play`, `paplay`, `aplay` | Yes |

Optional:

| Tool | What it adds |
| --- | --- |
| `secret-tool` (libsecret-tools) | API keys go in your login keyring instead of a private file (`~/.config/Tokalot/keys.json`, mode 0600) |
| `wl-clipboard` (`wl-copy`, `wl-paste`) | **Needed for automatic pasting on KDE and other non-GNOME Wayland desktops.** Without it Tokalot only copies there and asks you to press Ctrl+V, because its own clipboard may not reach Wayland apps |
| `xdotool`, `wtype` | Fallback for pressing Ctrl+V when `/dev/uinput` isn't available (`ydotool` is tried too, but needs `/dev/uinput` itself) |
| `xclip` or `xsel` | Fallback clipboard on X11 |
| `ffmpeg` / `ffplay` or `mpv` | Only to play or re-transcribe recordings restored from a Windows backup (.wma / .m4a) |
| `notify-send` | A notification if the shortcut can't be set up when Tokalot starts in the background |
| An AppIndicator / tray | The tray icon (see GNOME below) |

Optional sync: Settings › Sync can keep your dictionary, snippets, styles and instructions in one
small file (`tokalot-sync.json`) that you put in a folder you already sync (Nextcloud, Dropbox,
Syncthing…). Point each device at the same file, including the Windows and Android apps. There is
no account or server; API keys stay out of the file unless you switch "Include API keys" on.

Data lives in `~/.config/Tokalot` (settings, history, recordings, the offline model, `log.txt`).
The folder is private to your user (mode 0700).

## Known limits

All desktops

- Pasting presses the physical **Ctrl+V** keys. On a keyboard layout where V is somewhere else
  (Dvorak, for instance) set `TOKALOT_PASTE=shift+insert` in the environment.
- "Polish my wording" (Style page) offers Ctrl+Super+Z for a few seconds after a dictation to put
  your own words back. Tokalot does that by pressing Ctrl+Z and pasting the original. Linux lets it
  watch the keyboard but not hold a key back, so the app in front also sees your Ctrl+Super+Z; an
  app that treats that as undo too will have undone one step too many. Redo once in that app
  (usually Ctrl+Shift+Z or Ctrl+Y) to fix it. In apps where Ctrl+Z isn't undo (terminals) the swap
  doesn't work.
- If the clipboard held a picture or files before a dictation, the dictated text is left on the
  clipboard afterwards (only text can be put back).
- Recordings are saved as WAV (about 2 MB a minute); the Windows app compresses them.
- No self-update yet: download the newer tar.gz and unpack it over the old folder.
- "Hidden while an app is full screen" and following the monitor you're working on only know about
  X11 (and XWayland) windows.

Wayland (GNOME, KDE and others)

- Tokalot's own windows run through XWayland. That is fine for the main window; the indicator and
  message pill are positioned by Tokalot and that should be honored, but desktops differ.
- A Wayland desktop doesn't tell apps which window is in front, so per-app styles can't be chosen
  automatically: every dictation uses the "Everything else" style.
- For the same reason Tokalot can't tell it is in a terminal, where paste is Ctrl+Shift+V. In a
  Wayland terminal, paste by hand, or start Tokalot with `TOKALOT_PASTE=ctrl+shift+v` if you mostly
  dictate into terminals.

GNOME

- GNOME has no tray unless the "AppIndicator and KStatusNotifierItem Support" extension is on
  (Ubuntu ships it enabled; Fedora doesn't). Without a tray, start Tokalot again from the app menu
  to bring its window up; Quit is at the bottom of Settings.

KDE Plasma

- On Wayland install `wl-clipboard`; until then dictations are copied but not pasted.

X11

- Per-app styles and terminal detection work. Nothing extra to install.

## Switches

| | |
| --- | --- |
| `--background` | Start in the tray without opening the window (what "Start at login" uses) |
| `--software` | Draw without the graphics card, if the window comes up blank. Automatic on X11 when a monitor uses fractional scaling (125%, 150%…), which can freeze graphics-card drawing |
| `--gpu` | Draw with the graphics card even under fractional scaling |
| `--diagnose` | Print what works on this machine and exit. Add `--download-model` to also fetch and test the offline model |
| `--screenshots <folder>` | Developer tool: draw every page to PNG files with sample data. Needs no display |
| `TOKALOT_DATA=<folder>` | Use another data folder (a test copy that leaves your real data, keyring and autostart alone) |
| `TOKALOT_PASTE=ctrl+v \| ctrl+shift+v \| shift+insert` | Force the paste shortcut |
| `TOKALOT_CLIPBOARD=avalonia \| wl-copy \| xclip \| xsel` | Force how the clipboard is reached |

## Building

Needs the .NET 10 SDK. From the repository root:

```
dotnet build linux/Tokalot.Linux -r linux-x64
dotnet publish linux/Tokalot.Linux -c Release -r linux-x64 --self-contained -o publish/Tokalot
```

The project is `linux/Tokalot.Linux`. It compiles the platform-neutral files in
`windows/Tokalot.Desktop/Core` directly (they are linked, not copied), so a fix to the dictation
pipeline lands in both apps. The interface is Avalonia 11, written in code like the Windows one.
It also builds with `-r win-x64`, which is only useful for running `--screenshots` on Windows.
