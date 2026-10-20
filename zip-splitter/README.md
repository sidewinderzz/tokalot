# ZIP Splitter for Android
Choose a ZIP, choose an output folder, tap Split. Each output is strictly below 29,000,000 bytes and independently extractable. Creates a fresh child folder and never changes the original. Files and paths are preserved; archive metadata/permissions/comments are not. No Internet or broad storage permission.

Uses Android's file picker (Android 8+). Select local phone storage to keep processing entirely local. Keep the app in the foreground; cancellation cleans up partial results when possible. If Android kills the process, delete any incomplete output folder before retrying.

Rejects encrypted/unsupported ZIPs, duplicate or unsafe paths, more than 60,000 entries, corrupt contents, and single files that cannot fit compressed. Does not divide individual files. May produce more than two archives. ZIP data is streamed with bounded buffers; temporary disk space is needed for the input and outputs.

Build: JDK 17, Android SDK 34, Gradle 8.7. From this folder run `gradle :app:assembleDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signed for direct installation.
