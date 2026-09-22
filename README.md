<h1 align="center">PadShift</h1>
<p align="center"><b>Your Retroid Pocket 5 hands its controls to a Bluetooth gamepad, and takes them back.</b></p>
<p align="center">
  <a href="https://github.com/hamadrehman/padshift/actions/workflows/build.yml"><img alt="Build" src="https://github.com/hamadrehman/padshift/actions/workflows/build.yml/badge.svg"></a>
  <a href="https://github.com/hamadrehman/padshift/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/hamadrehman/padshift?display_name=tag"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
</p>

Dock your RP5 to a TV, pick up a controller, and the handheld's own buttons get out of the way, automatically.

- **Gamepad connects** → the RP5's built-in controller is disabled and Retroid's *Controller Style* tile flips to **Disconnect**.
- **Last gamepad disconnects** → the built-in controller returns, in the **Retro** or **Xbox** layout you had before.
- **Optional shortcuts** → while docked, **Home + button** on the external pad triggers Android Back, Home, Recents and volume.

Normal gamepad input is never intercepted. No root required on the RP5's Android 13. One Kotlin file, no dependencies.

## Download

| | |
|---|---|
| **Stable** | [Latest release → `PadShift.apk`](https://github.com/hamadrehman/padshift/releases/latest/download/PadShift.apk) |
| **Bleeding edge** | [Automatic build of `main`](https://github.com/hamadrehman/padshift/releases/download/latest/PadShift.apk) |

Every APK is built by [GitHub Actions](https://github.com/hamadrehman/padshift/actions) from the commit it is attached to and signed with the project key, so updates install over each other.

## Install

1. `adb install PadShift.apk`, or sideload the file on the device.
2. Open PadShift once. Android shows a one-time *"This app was built for an older version of Android"* notice, tap **OK**.
   (See [Why targetSdk 22](#why-targetsdk-22).) Automatic Docking is on by default.
3. Optional: tap **Enable Controller Shortcuts** and switch PadShift on under *Accessibility*.

The status notification shows the current state and carries a **Restore controls** action. The app has a large
**RESTORE HANDHELD CONTROLS NOW** button. If you ever end up with no working controls at all, from a computer:

```bash
adb shell settings put system no_create_gamepad_button_layout 0
adb shell settings put system temp_abxy_layout_mode 1   # 0 for Xbox
adb shell settings put system flip_button_layout 0      # 1 for Xbox
```

## Shortcuts

Active only while docked (the built-in pad is off then, so every gamepad event is from the external controller).
**Home/Guide** (`KEYCODE_BUTTON_MODE`) is the modifier and is swallowed while shortcuts are on.

| Chord | Action |
|---|---|
| Home + B | Back |
| Home + A | Home |
| Home + X | Recents |
| Home + D-pad Up **or** R1 | Volume up |
| Home + D-pad Down **or** L1 | Volume down |

Many pads report the D-pad as a hat axis, which never reaches Android's key filter, hence the L1/R1 alternatives.

## How it works

Retroid exposes its controller state as three `Settings.System` keys (verified on an RP5):

| Retroid mode | `no_create_gamepad_button_layout` | `temp_abxy_layout_mode` | `flip_button_layout` |
|---|---:|---:|---:|
| Xbox | 0 | 0 | 1 |
| Retro | 0 | 1 | 0 |
| Disconnect (body controller off) | 1 | 2 | 0 |

A small foreground service listens to `InputManager` device add/remove events and **reconciles** on every change:
count the gamepad input devices, subtract one for the built-in controller while it is enabled, dock if anything is left,
undock otherwise. Counting is necessary because Retroid's firmware re-labels every controller, built-in or Bluetooth,
with the same USB vendor/product ids, so identity cannot tell them apart. The built-in pad, however, is exactly one input
device while enabled and disappears while disabled.

Every write is verified by reading the value back. The previous Retro/Xbox mode is saved before docking and never
overwritten with Disconnect, so a restart while docked cannot lose it. Reconciliation also runs on service start, on
boot and when the accessibility service connects, so a crash cannot leave you stranded.

### Why targetSdk 22

Android only lets the shell user (what `adb` uses), system apps, and **apps targeting API 22 or lower** write vendor keys
in `Settings.System`. Anything newer throws *"You cannot keep your settings in the secure settings"* even with *Modify
System Settings* granted. Targeting 22 is what makes PadShift work without root. The costs: the one-time notice above,
and Android 14+ refuses to install apps targeting below 23, so this build only works while the RP5 is on Android 13.
A `su` fallback exists in the code for rooted devices and is never used on stock firmware.

## Build

```bash
cd padshift
export JAVA_HOME=/path/to/jdk17          # Android SDK: platform 34 + build-tools 34.0.0, or ANDROID_HOME set
./gradlew assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
adb logcat -s PadShift                    # reconcile / write / shortcut log lines
```

Release builds are signed when `padshift/keystore.properties` exists (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`); CI writes it from repository secrets. Pushing a `v*` tag publishes a release; every push to `main`
refreshes the rolling `latest` pre-release.

## Status and caveats

- Tested on a Retroid Pocket 5, Android 13, with a Nintendo Switch Pro Controller over Bluetooth: dock and undock work.
- Implemented per the spec but not yet exercised on hardware: Xbox-mode restore, two simultaneous pads, the chords.
- RP5 only. Other Retroid models may share the keys; other vendors will not. Details and the full test log are in
  [2026-09-21-padshift-status.md](2026-09-21-padshift-status.md); the original specification is
  [Dockify_Android_Handheld_Spec.md](Dockify_Android_Handheld_Spec.md) (PadShift's working name was Dockify).

## License

[MIT](LICENSE)
