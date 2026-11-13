# Knock

Tap your Android phone on your MacBook's notch and the file you're looking at lands on your Mac. No cable, no account, no cloud.

The MacBook's ambient light sensor sits by the notch. When the phone touches it, the phone flashes its screen dark, white, dark. Only a screen pressed right on the sensor can make it read ~0 then bright then ~0, so that pattern is the "go" signal. The phone then sends the original file (or a screenshot if no file matches) straight to the Mac over your Wi-Fi, end-to-end encrypted after a one-time 6-digit pairing. Received files go to `~/Knock data`.

## Status
Feasibility spikes done, on a MacBook Pro and a Samsung Galaxy S23 (Android 17):

| Risk | Result |
|---|---|
| Can the Mac read the notch light sensor fast enough? | Yes, 5 readings/s (one per 200 ms) |
| Does a phone flash stand out? | Room ~61, white screen pressed ~605-661, black screen pressed 0. 12/12 flashes at 600 ms and 17/17 at 200 ms detected |
| Can the phone grab its screen without a dialog every time? | MediaProjection: no, the session dies on its own. Accessibility service: yes, 5/5 with no dialogs |
| Can it find the original document? | Yes. The viewer's on-screen file name matched the PDF in Download/ from both Google Drive's viewer and Samsung Notes |

Planned tap timing: dark >= 400 ms, white 400 ms, dark 400 ms, about 1.2 s.

## Layout
- `spikes/mac-light-sensor/` `alsprobe.c` reads the sensor (private IOKit HID API); `flasher.html` is a phone page that flashes test patterns.
- `spikes/android-capture/` Android Studio project testing MediaProjection vs Accessibility capture and file lookup.
- `mac/`, `android/`, `protocol/` v1 apps (in progress).

## Known limits
- Private macOS API, so no Mac App Store. Accessibility + all-files access, so no Google Play. Both are fine for sideloading.
- Untested in direct sunlight.
