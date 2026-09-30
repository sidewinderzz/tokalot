# OfflineFlow

Offline voice typing for Android. Works with whatever keyboard you already use.

Tap any text field and a small mic button floats above the keyboard. Tap it to talk, tap again to stop,
and the transcript is typed into the field. Speech recognition is Whisper (whisper.cpp) running on the phone.

## Build the APK (no Android Studio needed)

1. Create a new GitHub repo and push this folder to it.
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push (first build takes several
   minutes because it compiles whisper.cpp).
3. Open the finished run and download the **OfflineFlow-debug-apk** artifact. Unzip it, send
   `app-debug.apk` to your phone, and install it (allow "install unknown apps" for whatever app you opened it from).

Or open the folder in Android Studio and press Run.

## First-time setup on the phone

1. Open OfflineFlow, tap **Allow microphone**.
2. Tap **Download speech model** (~60 MB, the only time it uses the internet).
3. Tap **Turn on OfflineFlow in Accessibility** and enable it. Android will show a scary warning; that's
   normal for any accessibility service. If the switch is greyed out: phone Settings > Apps > OfflineFlow >
   three-dot menu > **Allow restricted settings**, then try again.
4. Also set battery usage for OfflineFlow to **Unrestricted** so Android doesn't kill the service.

## How it keeps battery use low

- The service only listens for focus/window changes. No polling, no mic, no model loaded while idle.
- The mic is open only between your two taps.
- The model loads on your first dictation and is freed after 60 seconds idle.
- Native code is always built optimized (Release), even in the debug APK.

## Known limits

- English only (base.en model). For better accuracy, change `NAME` in `ModelManager.kt` to
  `ggml-small.en-q5_1.bin` (about 3x bigger and slower).
- Tap to start/stop, not hold-to-talk.
- Password fields are skipped on purpose.
- Some apps block programmatic text entry; then the text is pasted via the clipboard instead.
- Filler words (um, uh) and sound tags like [BLANK_AUDIO] are stripped with simple rules. There's no
  AI rewrite/cleanup step like Whisper Flow has.
- Untested on a real device so far. The first thing to check is that recording works when you tap the mic
  inside another app (Android restricts background mic use, and this relies on the accessibility overlay
  counting as foreground).
