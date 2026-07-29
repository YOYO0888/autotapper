# AutoTapper

A floating on-screen tool that runs a saved *sequence* of taps, swipes, and
waits on a loop, on top of any app, until you press Stop. Built for repetitive
queue work.

## v3.0 — what's new

- **Multi-step sequences**: chain any number of Tap, Swipe, and Wait steps.
  The loop runs them top to bottom, then repeats.
- **Record mode**: press Record, do the workflow on the screen with your
  finger, press Stop — the tool captures every tap, swipe, and the waits
  between them.
- **Reorder / delete per step** (▲ ▼ ✕ buttons on each row).
- **Profile export/import via clipboard**: copy a profile to share with the
  team, paste-import one someone sent you.
- **Live cycle count** shown on the minimized bubble; tap the bubble to pause.
- Everything from v2 kept: named profiles, persistence, tap radius/jitter,
  cycle limit, dark Material UI, coordinate fix, haptics.

## Setup

Same as before:

1. Install the APK from GitHub Actions (Actions → latest run → Artifacts).
2. Open the app → **Open Accessibility Settings** → enable AutoTapper.
3. **Open Overlay Settings** → allow "display over other apps".
4. **Launch Floating Panel**.

## Using it

**Manual build**
- **+ Tap** → drag pink point onto target → **Confirm**.
- **+ Swipe** → drag green (start) and yellow (end) → **Confirm**.
- **+ Wait** → adds a wait step using the default duration in Settings.
- Reorder with ▲▼, delete with ✕.

**Record**
- Press **● Record**, do the actions with your finger (a faint pink overlay
  captures gestures instead of the underlying app receiving them).
- Press **■ Stop Recording**. The captured sequence is now in your steps.

**Run**
- **▶ Start**. Set cycle limit in Settings (0 = forever), or **■ Stop** any time.

**Share a profile**
- Save → select in the carousel → **Copy (share)**. Paste the text in Slack /
  email.
- Recipient: copy the text on their phone → **Paste import** in the panel.

## Notes

- Coordinates are raw screen pixels; a profile recorded on one device will
  usually not fit a different resolution exactly. Re-record if needed.
- If Android kills the accessibility service, re-enable it — the running loop
  detects the drop and reconnects.
