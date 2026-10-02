# Tokalot for Mac (experimental)

Voice typing for any app. Hold **Ctrl+Cmd** (⌃⌘) and talk, let go, and the cleaned-up text is pasted
where your cursor is. Tap Ctrl+Cmd once for hands-free (tap again to finish). **Esc** cancels.
It is the same app as Tokalot for Windows and Linux: same screens, same settings, and backups move
between them.

> **Experimental.** This build has been compiled and checked on GitHub's macOS build machines, but
> no person has used it on a real Mac yet. The keyboard shortcut, the microphone, pasting, the
> permission prompts, the menu-bar icon and the on-screen recording indicator have never been tried
> by hand. If you try it, please report what happened (see "Reporting" below).

Needs a Mac with Apple silicon (M1 or newer) and macOS 15 Sequoia or newer (macOS 14 may work).
Intel Macs aren't built yet.

## Install

1. Download `Tokalot-mac-arm64-<version>.zip` from the release tagged `mac-v<version>` on
   https://github.com/sidewinderzz/tokalot/releases and double-click it to unzip.
2. Drag **Tokalot.app** into your **Applications** folder.
3. Open it. Because the app isn't signed by Apple yet, macOS will refuse the first time:
   - **macOS 15 Sequoia and newer:** you'll see "“Tokalot” Not Opened". Click **Done**, then open
     **System Settings › Privacy & Security**, scroll down to the message about Tokalot and click
     **Open Anyway**, then confirm with your password.
   - **macOS 14 Sonoma:** right-click (or Control-click) Tokalot.app, choose **Open**, then **Open**.
   - If macOS says the app **"is damaged"**, the zip lost its signature on the way. In Terminal:
     `xattr -dr com.apple.quarantine /Applications/Tokalot.app` and open it again.
4. Tokalot lives in the **menu bar** (five little bars, top right), with no Dock icon. Its window
   opens on the first start; to get it back later, click the menu-bar icon › Open Tokalot, or open
   Tokalot.app again.

## The three permissions

macOS asks before an app may see the keyboard, press keys for you, or use the microphone. All three
are under **System Settings › Privacy & Security**. Tokalot's Home page and Settings › Setup show
which are missing, with a button that opens the right page.

| Permission | What it's for | If it's missing |
| --- | --- | --- |
| **Input Monitoring** | Seeing Ctrl+Cmd (and Esc) while you're in other apps | The shortcut does nothing. Click the indicator at the bottom of the screen to dictate instead |
| **Accessibility** | Pressing ⌘V for you (and reading the browser tab's title, so Gmail gets the email style) | Text is copied, and you press ⌘V yourself |
| **Microphone** | Recording | macOS asks the first time you dictate |

After switching Input Monitoring on, quit Tokalot (menu-bar icon › Quit) and open it again.

**After every update** (and with this unsigned app, every new download counts) macOS keeps showing
Tokalot as switched on but no longer honours it. Select Tokalot in the list, remove it with the
**–** button (or switch it off and on), and add it again.

What Tokalot does with them: it watches Ctrl, Cmd, Option and Shift, and Esc. While the shortcut
is held, or a recording or transcription is under way, it also notices *that* another key was
pressed (so Ctrl+Cmd+Q still locks your screen instead of starting dictation), and which one only
for Z (the "put my own words back" shortcut below). Nothing it sees is stored or logged. Its only
key presses are ⌘V (paste) and ⌘Z (undo, for the revert).

## Why Ctrl+Cmd

Holding Control and Command together does nothing by itself anywhere in macOS, and the pair sits
where Ctrl+Win does on a PC keyboard. The 🌐/Fn key is taken by the emoji picker and Apple's own
dictation and isn't on most external keyboards; Ctrl+Option is VoiceOver's key pair. The few
system shortcuts that start with Ctrl+Cmd (Q to lock the screen, F for full screen, Space for the
emoji picker, D to look up a word) keep working: another key pressed straight after the pair
cancels the dictation quietly.

## Using it

- **Hold Ctrl+Cmd**, talk, let go: the text appears where your cursor is.
- **Tap Ctrl+Cmd** for hands-free; tap again to finish. Auto-stop after 30 s of silence (Settings).
- **Esc** cancels a recording, or stops a transcription in progress. A cancelled or failed
  recording longer than a few seconds is kept in History with a **Transcribe** button.
- **Polish my wording** (Style page): when on, the AI may tighten what you said, and for a few
  seconds after each dictation **Ctrl+Cmd+Z** puts your own words back (Tokalot sends ⌘Z and pastes
  the original).
- **Sync** (Settings › Sync): keep your dictionary, snippets, styles and instructions in one file in
  a folder you already sync (iCloud Drive, Dropbox, Google Drive…) and point your other devices at
  it. No account, no server; API keys stay out unless you switch that on.

Data lives in `~/Library/Application Support/Tokalot` (settings, history, recordings, the offline
model, `log.txt`), private to your user. API keys are in your login Keychain under "Tokalot".

## Known limits

- Unsigned and not notarized: the Gatekeeper steps above, and the permissions need re-granting
  after each update.
- No self-update yet: download the newer zip and replace Tokalot.app.
- Pasting presses the **V key position with ⌘**. On a keyboard layout where V is elsewhere (Dvorak),
  choose the "Dvorak – QWERTY ⌘" layout, which keeps ⌘ shortcuts on QWERTY positions.
- In password fields and apps with "Secure Keyboard Entry" on (Terminal, iTerm2 and some password
  managers offer it), macOS hides the keyboard from every other app, including Tokalot's shortcut.
- If the clipboard held a picture or files before a dictation, the dictated text stays on the
  clipboard afterwards (only text is put back).
- Recordings are saved as WAV (about 2 MB a minute).
- The window uses macOS's own buttons (top left), with Tokalot's pages drawn up under them.

## Reporting

Please open an issue at https://github.com/sidewinderzz/tokalot/issues with your Mac model, macOS
version, what you tried and what happened, plus the output of:

```
/Applications/Tokalot.app/Contents/MacOS/Tokalot --diagnose
/Applications/Tokalot.app/Contents/MacOS/Tokalot --selftest --record 3
```

(the second records 3 seconds from your microphone and prints only numbers about it), and the end of
`~/Library/Application Support/Tokalot/log.txt`.

## Switches

| | |
| --- | --- |
| `--background` | Start in the menu bar without opening the window (what "Start at login" uses) |
| `--software` | Draw without the graphics card, if the window comes up blank |
| `--diagnose` | Print what works on this Mac and exit. Add `--download-model` to also fetch and test the offline model |
| `--selftest` | Check the Keychain, clipboard, front-app detection, keyboard tap and more; `--record <seconds>` also tests the microphone |
| `--transcribe <file.wav>` | Run a recording through the dictation pipeline and print the text |
| `--screenshots <folder>` | Developer tool: draw every page to PNG files with sample data |
| `TOKALOT_DATA=<folder>` | Use another data folder (a test copy that leaves your real data, Keychain and login items alone) |

## Building

Needs the .NET 10 SDK. From the repository root, on a Mac:

```
dotnet publish mac/Tokalot.Mac -c Release -r osx-arm64 --self-contained -o publish/Tokalot
```

then assemble Tokalot.app as `.github/workflows/mac.yml` does (Info.plist from
`mac/Tokalot.Mac/Bundle`, icon, ad-hoc signature).

The project is `mac/Tokalot.Mac`. It links, rather than copies, the platform-neutral files in
`windows/Tokalot.Desktop/Core` and the Linux app's interface and controller
(`linux/Tokalot.Linux`, built on Avalonia, which also runs on macOS). Only the parts that differ
are Mac-specific: the keyboard tap, paste and clipboard, the microphone (Core Audio), the Keychain,
start at login (a LaunchAgent), front-app detection, the always-on-top window behaviour, the
permission rows in Settings and the menu-bar icon.
