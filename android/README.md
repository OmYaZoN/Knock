# Knock for Android

Sideloaded app (Android 11+). The Play Store would not allow the Accessibility and all-files use this needs.

## Install

Download `knock-debug-apk` from the latest green CI run, unzip it, and install `app-debug.apk` on the phone.

## Set up once

1. **Turn on Knock in Accessibility.** Lets Knock take a screenshot with no dialog, read on-screen text at the moment of a knock, and draw the black/white flash.
2. **Allow all-files access.** Lets Knock find the original file you have open. Without it, Knock sends a screenshot.
3. **Pair with your Mac.** Start Knock on the Mac first. Both screens show a 6-digit code; confirm on both.

## Knock

Open a file, press the top of the phone's screen on the MacBook notch. The proximity sensor starts the round, the screen goes black, flashes white, goes black, and the phone buzzes when the Mac has seen it. The file lands in `~/Knock data` on the Mac.

**Test: knock in 5 s** in the app does the same without the proximity sensor.

## Code

- `dev.knock.proto` is the protocol (`protocol/PROTOCOL.md`), pure JVM. `VectorsTest` checks it byte for byte against `protocol/reference/vectors.json`.
- `KnockService` is the round: trigger, capture, overlay, session, file.
- `FileFinder` looks up on-screen text as a file name in MediaStore. The text is never stored or sent.

Build: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 34).
