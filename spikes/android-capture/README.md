# TapDrop spike #2: screen capture on Android

Question: after saying yes ONCE, can the phone keep grabbing "whatever is on screen" while you use other apps, or does Android ask every time?

## Build (Android Studio)
1. Unzip, then File > Open this folder. Let Gradle sync (it downloads what it needs).
2. Phone: Settings > About phone > tap Build number 7 times, then Developer options > USB debugging ON (or Wireless debugging).
3. Press Run. The app is called "TapDrop Spike".

## Test (write down what the log says)
1. Tap "1. Allow screen capture". If asked, pick **Entire screen**, not a single app.
2. Tap "2. Capture in 5 s", switch to your PDF viewer. Phone buzzes. Come back. Repeat 3 times.
3. Lock the phone for 10 s, unlock, try "Capture in 5 s" again.
4. Open a banking or payment app and capture it (expect a BLACK FRAME line).
5. Leave it 10 minutes, capture again.
6. Screenshot or copy the log and send it, plus your phone model and Android version (shown on the first log line).

Captures land in Gallery under Pictures/TapDropSpike.
