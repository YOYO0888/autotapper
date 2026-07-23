# AutoTapper

A floating on-screen tool that scrolls and taps a button on a loop, on top of
any app, until you press Stop.

## How it works

Android won't let a normal app inject touches into other apps — only an
**Accessibility Service** can dispatch gestures system-wide, and only a
**"draw over other apps" overlay** can show floating controls on top of
whatever you're using. This app uses both, entirely locally on your device.
Nothing is sent anywhere.

## Setup

1. Open the project in Android Studio (Giraffe or newer), let Gradle sync,
   and run it on a device or emulator (minSdk 26 / Android 8+).
2. In the app, tap **Enable Accessibility Service** → find "AutoTapper" in
   the list → turn it on.
3. Tap **Enable Overlay Permission** → allow "display over other apps".
4. Tap **Launch Floating Controls**. A small draggable panel appears.

## Using it

1. Open whatever app/screen you want to automate (e.g. swipe to it, or just
   leave the panel floating over your home screen — it stays on top of
   everything).
2. In the panel, tap **Set Scroll Points**. Two circles appear — drag the
   green one to where the swipe should start and the yellow one to where it
   should end (e.g. green near the bottom of the screen, yellow near the
   top, for a scroll-up gesture). Tap **Confirm Position**.
3. Tap **Set Tap Point**, drag the pink circle onto the button you want
   pressed, tap **Confirm Position**.
4. Set the **interval** (milliseconds to wait between each loop).
5. Tap **Start**. It will repeat: swipe → wait → tap → wait, forever.
6. Tap **Stop** any time to end the loop. The panel stays up so you can
   re-run it or reposition the points.
7. Tap the ✕ on the panel to fully close the overlay and stop the
   foreground service.

## Notes

- You can drag the whole panel around by its "⠿ AutoTapper" header.
- If the accessibility service gets killed by the OS (some phones are
  aggressive about this), re-enable it from Android's Accessibility
  settings — the app will pick it back up automatically.
- Coordinates are recorded once, in raw screen pixels, so they'll be off if
  you rotate the screen or switch to a device with a different resolution.
