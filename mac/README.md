# Knock for Mac

Menu-bar app that watches the notch light sensor, pairs with your phone, and saves what it sends into `~/Knock data`.

## Build and run
Needs the Xcode command line tools (`xcode-select --install`) and macOS 13 or newer.

```
cd mac
swift run KnockVectors ../protocol/reference/vectors.json   # crypto self-check, must print "All vectors pass."
swift build -c release
.build/release/Knock
```

A hand icon appears in the menu bar. Open the menu to see status, the live notch light reading, and the last file received. Keep the Terminal window open while it runs; Ctrl-C quits.

If macOS asks whether Knock (or Terminal) may accept incoming network connections or find devices on your local network, click Allow. The phone can't reach the Mac otherwise.

## Layout
- `Sources/CALS`: C reader for the ambient light sensor (private IOKit HID API, Intel fallback).
- `Sources/KnockCore`: protocol framing, crypto, pairing and session handshake, tap round, file receiving. No UI.
- `Sources/Knock`: the menu-bar app.
- `Sources/KnockVectors`: checks KnockCore against `protocol/reference/vectors.json`.

## Where things are kept
- Identity key and paired phones: `~/Library/Application Support/Knock/` (owner-only permissions).
- Received files: `~/Knock data/`. Names are never overwritten; a clash becomes `name (2).pdf`.
