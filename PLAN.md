# TapDrop: repo layout and build order

The tap is only the trigger. Transfer is phone to Mac directly over local Wi-Fi, paired once with a 6-digit code, end-to-end encrypted.

## Build order (riskiest first)

| # | Piece | Why this order | Kill criterion |
|---|-------|----------------|----------------|
| 1 | **Mac light sensor spike** (`mac-spike/alsprobe.c`, done) | If the notch sensor updates too slowly or is heavily smoothed, the "white then black within half a second" round cannot work. | Fewer than ~10 distinct updates/s, or white-on-notch vs black-on-notch not clearly separable. |
| 2 | **Android screen capture spike** | Android needs MediaProjection consent. On Android 14+ each consent token is single-use, so "capture whatever is on screen at tap time" may mean a system dialog on every tap, or a capture session kept running in the background. | If every tap needs a dialog, the UX is dead; fall back to the Android share sheet. |
| 3 | **Transport + pairing** (shared protocol spec, then both sides) | Well understood, low risk: mDNS/Bonjour discovery, TCP, P-256 ECDH, 6-digit code from the shared secret, HKDF-SHA256, AES-256-GCM with counter nonces. | None, just work. |
| 4 | **Tap round end to end** | Phone white, notch sees it, phone black, notch drops, buzz, send. Fixed threshold first, the "55% of median of last 12" adaptive threshold after. | |
| 5 | **Screenshot transfer** | MVP is "send what's on screen as a PNG". This already solves the PDF problem only partly (you get a picture of page 1, not the PDF). | |
| 6 | **Original-file lookup** | Documents by viewer filename needs an AccessibilityService (Play Store restricts these heavily). Photo/video fingerprinting is a big separate project. | Do last; it is the most work for the least proof. |

## Repo layout

```
tapdrop/
  protocol/        PROTOCOL.md: discovery, pairing, frame format, tap-round messages, test vectors
  mac/             Swift + SwiftUI menu bar app (Xcode project)
    Sensor/        ALS reader (ported from mac-spike)
    Transport/     Network.framework listener, Bonjour, CryptoKit
    TapRound/      state machine + threshold learning
  android/         Kotlin app (Gradle)
    capture/       MediaProjection
    transport/     NSD discovery, java.security / Tink crypto
    tapround/
  spikes/          throwaway experiments (alsprobe lives here)
```

Both apps implement the protocol from `protocol/` and must pass the same test vectors, so crypto bugs show up as a failing vector, not as "it just doesn't connect".

## Open facts needed from the user
- Mac model and chip (Apple Silicon or Intel), macOS version.
- Android phone model and Android version.
