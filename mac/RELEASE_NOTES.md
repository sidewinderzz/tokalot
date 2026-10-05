**Experimental. No person has used this on a real Mac yet.** It is the Linux/Windows app ported to macOS, for Macs with Apple silicon (M1 or newer) on macOS 15 Sequoia or newer (14 may work). Hold **Ctrl+Cmd** (⌃⌘) and talk; let go and the text is pasted where your cursor is. Tap Ctrl+Cmd once for hands-free; Esc cancels.

**Mac testers wanted:** if you try it, please comment on the ["Mac testers wanted" issue](https://github.com/sidewinderzz/tokalot/issues/6), even if everything works.

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

1. Download **Tokalot-mac-arm64-0.1.0.zip** below and double-click it to unzip.
2. Drag **Tokalot.app** into **Applications**.
3. Open it. It isn't signed by Apple yet, so macOS refuses the first time:
   - **macOS 15 Sequoia or newer:** click **Done** on "“Tokalot” Not Opened", then go to **System Settings › Privacy & Security**, scroll down, click **Open Anyway** next to the Tokalot message and confirm.
   - **macOS 14:** right-click Tokalot.app › **Open**, then **Open**.
   - If macOS calls it **"damaged"**: run `xattr -dr com.apple.quarantine /Applications/Tokalot.app` in Terminal and open it again.
4. Tokalot lives in the **menu bar** (five small bars, top right) with no Dock icon. Its Home page lists the permissions still needed, each with a button to the right page of System Settings: **Input Monitoring** (to see Ctrl+Cmd; restart Tokalot after switching it on), **Accessibility** (to press ⌘V for you) and **Microphone** (macOS asks on the first dictation).
5. Add a free Groq API key in Settings, or download the offline model there, click into any text box, hold **Ctrl+Cmd** and talk.

After an update, macOS may still show Tokalot as allowed but stop honouring it: remove it from the Input Monitoring and Accessibility lists and add it again.

The full guide is in [`mac/README.md`](https://github.com/sidewinderzz/tokalot/blob/main/mac/README.md). There's no in-app updater yet: to update, download the newer zip and replace Tokalot.app.
