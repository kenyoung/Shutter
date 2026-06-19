# Shutter Remote

Turn a spare Google Pixel into a wireless Bluetooth shutter remote for another
Pixel's **astrophotography mode** — so you can trigger long exposures (e.g. of a
meteor shower) without touching the camera phone and shaking it.

## How it works

There is no public API to drive Google Camera's astro mode from another app, so
this app does what a cheap Bluetooth selfie remote does: the **trigger phone**
advertises itself as a Bluetooth HID device (Android's `BluetoothHidDevice`
profile, API 28+, **no root**) and sends a **Volume-Up** key. Pixel's Google
Camera treats a volume key as the shutter.

The **camera phone needs no app at all** — you just pair the trigger phone and
open Google Camera.

## Setup

1. Build & install this app on the **trigger phone** (the spare). Grant the
   Bluetooth and notification permissions it asks for.
2. On the **camera phone**: Settings → Bluetooth → pair the device shown as
   **"Pixel Shutter Remote"**.
3. In Google Camera on the camera phone, make sure the volume key is set to fire
   the shutter (Settings → Gestures → "Volume key shortcut" → **Shutter**;
   this is the Pixel default).
4. In the app on the trigger phone: tap **Refresh list**, pick the camera phone,
   tap **Connect**. The status line shows when it is connected.

## Use

- **Take Photo** — fires one shot.
- **Auto-trigger** — enter an interval and tap **Start auto-trigger** to fire
  repeatedly. A foreground notification + wakelock keep it running with the
  screen off through a long session.
- **Max exposures** — optional limit for auto-trigger: it stops automatically
  after this many shots (leave blank for unlimited). The notification shows
  `N / max` progress. A manual **Stop** still works at any time. When the full
  set finishes on its own, the voice announces "Set of {x} exposures complete."
  (this is always spoken, even in Beep Mode).
- **Trigger key** — defaults to Volume Up; switch to **Enter** if a particular
  camera responds to that instead.
- **Audio feedback** — each successful trigger is confirmed aloud, so you can
  track progress without looking at the phone:
  - **Count Mode** (default) — speaks the running exposure count ("1", "2", …).
  - **Beep Mode** — plays a short tone instead.

  The feedback plays on the trigger phone (the one in your hand), uses the media
  stream so it stays audible with the ringer silenced, and only sounds when a
  shot is actually sent — not as a confirmation that the camera captured.

### Reliability for unattended nights

- **Settings persist** — interval, unit, max exposures, feedback mode, trigger
  key, and the last camera phone are remembered between launches, so you can set
  up the same way each night. The last device is pre-selected in the list.
- **Auto-reconnect** — if the Bluetooth link drops mid-session, the app keeps
  retrying (every few seconds) to restore it, so a brief glitch doesn't end the
  night. Dropped ticks are *not* counted toward the max-exposure limit.
- **Connection alerts** — during auto-trigger, the voice warns "Camera
  disconnected. Reconnecting." on a drop and "Camera reconnected." on recovery
  (spoken in either feedback mode), so you notice instead of finding gaps in the
  morning.

### Astro tips

- Mount the camera phone on a tripod, pick **Night Sight**, and let
  astrophotography engage (it only does so when the phone is perfectly still in a
  dark scene).
- Keep the auto-trigger interval **longer than the exposure** (≥5 min). An astro
  exposure can run up to ~4 minutes, and a second press *during* an exposure
  cancels it. Use the **sec** unit only for quick testing.

## Build

Open in Android Studio and let it sync (it generates the Gradle wrapper jar),
then Build → Build APK. Or from the command line once the wrapper exists:

```sh
ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/Shutter-debug.apk`; a copy is
also placed in `dist/Shutter.apk`.

- `minSdk 28`, `targetSdk`/`compileSdk 36`, Kotlin, classic Views.
