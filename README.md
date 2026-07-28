# AutoTapper

A floating on-screen tool that taps and/or swipes a point on a loop, on top of
any app, until you press Stop. Built for repetitive queue work.

## What's new in v2.0

- **Tap-only, swipe-only, or both** — swipe is no longer mandatory.
- **Saved profiles** — name and store point/timing presets, switch between them.
- **Persistence** — your last points and settings are restored automatically,
  even after closing the panel or rebooting.
- **Minimize to a bubble** — shrink the panel to a small floating dot while it runs.
- **Cycle limit** — run N cycles then auto-stop (0 = forever).
- **Coordinate fix** — taps now land exactly on the crosshair on all devices.
- **Redesigned UI** — dark Material panel, colored status chip (idle / running /
  error), haptic feedback on start/stop.

## How it works

Android only lets an **Accessibility Service** dispatch gestures system-wide, and
only a **"draw over other apps" overlay** can float controls on top of other
apps. This app uses both, entirely locally. Nothing is read from the screen and
nothing is sent anywhere.

## Setup

1. Install the APK (see below), or open in Android Studio (Giraffe+), minSdk 26.
2. Tap **Open Accessibility Settings** -> enable "AutoTapper".
3. Tap **Open Overlay Settings** -> allow "display over other apps".
4. Tap **Launch Floating Panel**.

## Using it

1. **Set Tap** -> drag the pink point onto the button -> **Confirm**.
2. (Optional) **Set Swipe** -> drag green (start) and yellow (end) -> **Confirm**.
3. Open **Settings (gear)** for timing, tap radius/count, jitter, cycle limit,
   and profiles.
4. **Start**. It repeats swipe -> wait -> tap(s) -> wait until you Stop.
5. **Save** a profile to reuse the setup later.
6. **—** minimizes to a bubble; **✕** closes the overlay.

## Build / distribute

Push to GitHub. The included Actions workflow (`.github/workflows/build.yml`)
builds a debug APK on every push — download it from **Actions -> latest run ->
Artifacts -> AutoTapper-debug-apk**.

## Notes

- Coordinates are raw screen pixels; re-set points after a rotation or on a
  device with a different resolution.
- If the OS kills the accessibility service, re-enable it in Settings — the
  running loop detects the drop and recovers.
