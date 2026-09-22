# PadShift

PadShift makes the **Retroid Pocket 5** behave like a dockable console.

- Connect one or more Bluetooth gamepads → the RP5's built-in controller is disabled and Retroid's *Controller Style*
  tile switches to **Disconnect**.
- Disconnect the last gamepad → the built-in controller comes back and your previous **Retro** or **Xbox** mode is restored.
- Optional: while docked, **Home/Guide + button** chords on the external pad trigger Android Back, Home, Recents and volume.

Normal gamepad input is never touched. No root needed on the RP5's Android 13. About 175 lines of Kotlin, no dependencies.

## How it works

Retroid exposes its controller state as three `Settings.System` keys (verified on an RP5):

| Retroid mode | `no_create_gamepad_button_layout` | `temp_abxy_layout_mode` | `flip_button_layout` |
|---|---:|---:|---:|
| Xbox | 0 | 0 | 1 |
| Retro | 0 | 1 | 0 |
| Disconnect (body controller off) | 1 | 2 | 0 |

PadShift runs a small foreground service that listens to `InputManager` device add/remove events and reconciles:
count the gamepad input devices, subtract one for the built-in controller while it is enabled, and dock if anything is
left. That rule is needed because Retroid's firmware re-labels every controller, built-in or Bluetooth, with the same
USB vendor/product ids, so identity cannot tell them apart. Every write is verified by reading the value back; a `su`
fallback exists but is not used on stock Android 13.

The previous Retro/Xbox mode is remembered before docking and never overwritten with Disconnect, so a service restart
while docked cannot lose it.

## Install

1. Download `PadShift-debug.apk` from the Releases page and `adb install PadShift-debug.apk` (or sideload it).
2. Open PadShift once. Android shows a one-time *"built for an older version of Android"* notice, tap OK. This is the
   price of the rootless write path: only apps targeting API 22 or lower may write vendor keys in `Settings.System`.
3. Optional: tap **Enable Controller Shortcuts** and turn on PadShift under Accessibility.

The status notification shows the current state and has a **Restore controls** action. The app has a large
**RESTORE HANDHELD CONTROLS NOW** button. If you ever end up with no working controls, from a computer:

```bash
adb shell settings put system no_create_gamepad_button_layout 0
adb shell settings put system temp_abxy_layout_mode 1   # 0 for Xbox
adb shell settings put system flip_button_layout 0      # 1 for Xbox
```

## Shortcuts (external pad, only while docked)

| Chord | Action |
|---|---|
| Home + B | Back |
| Home + A | Home |
| Home + X | Recents |
| Home + D-pad Up or R1 | Volume up |
| Home + D-pad Down or L1 | Volume down |

Home/Guide (`KEYCODE_BUTTON_MODE`) is reserved as the modifier while shortcuts are on. Many pads report the D-pad as a hat
axis, which never reaches the key filter, hence the L1/R1 alternatives.

## Build

```bash
cd padshift
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb logcat -s PadShift         # reconcile / write / shortcut log lines
```

Needs JDK 17 and an Android SDK with platform 34 and build-tools 34.0.0 (`local.properties` → `sdk.dir`).

## Caveats

- Targets API 22 on purpose (see Install). Android 14+ refuses to install apps targeting below 23, so this build only
  works while the RP5 stays on Android 13. A rooted variant would just raise `targetSdk`.
- Tested with a Nintendo Switch Pro Controller over Bluetooth. Xbox-mode restore, two simultaneous pads and the
  accessibility chords are implemented per spec but not yet exercised on hardware.
- Only the RP5 is supported. Other Retroid models may use the same keys; other vendors will not.

## Repository

- `padshift/` — the Android project (single source file: `app/src/main/java/com/padshift/handheld/PadShift.kt`).
- `Dockify_Android_Handheld_Spec.md` — the original specification (working name Dockify).
- `2026-09-21-padshift-status.md` — implementation findings and test log.
