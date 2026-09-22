# PadShift (formerly PadShift) — status as of 2026-09-21

V1 of the spec ([PadShift_Android_Handheld_Spec.md](PadShift_Android_Handheld_Spec.md)) is built, installed on the RP5 and
verified for automatic dock and undock with a Nintendo Switch Pro Controller over Bluetooth. Writes are rootless.

## 2026-09-21 update

- Renamed Dockify → **PadShift** (package `com.padshift.handheld`, log tag `PadShift`, project dir `padshift/`).
  The spec file keeps its original Dockify working name.
- The renamed build is compiled ([PadShift-debug.apk](PadShift-debug.apk)) but **not yet installed**: the RP5 was off.
  Because the package id changed, next steps on the device are `adb uninstall com.dockify.handheld`, install the new APK,
  open it once (tap OK on the older-Android notice), then re-add `com.padshift.handheld/.ShortcutService` to
  `enabled_accessibility_services` by appending, never overwriting. Uninstalling the old package also removes it from that list.
- Wi-Fi adb address/port will have changed after the reboot; use `adb connect <ip>:<port>` from Wireless debugging.

## State on the RP5 as of 2026-09-20 (last time it was on)

- Installed: the old `com.dockify.handheld` v0.1 (debug build, targetSdk 22); PadShift APK at [PadShift-debug.apk](PadShift-debug.apk) awaiting install.
- Foreground service running with the "PadShift active" notification (Restore controls action).
- Accessibility service enabled alongside Key Mapper and Retroid Game Assistant.
- Device: Retroid Pocket 5, Android 13 (SDK 33), Magisk 30.7 root (not needed by PadShift), adb over Wi-Fi at 192.168.4.78.
- RP5 left in Retro mode with body controls enabled; Bluetooth on, Pro Controller disconnected (press a button to reconnect).

## Verified

| Test | Result |
|---|---|
| Connect Pro Controller | externalGamepads=1, `no_create_gamepad_button_layout=1`, `temp_abxy_layout_mode=2`, body pad input device removed, tile = Disconnect. Writes read back OK in ~3 ms, no root. |
| Controller drops (Bluetooth off) | externalGamepads=0, `no_create=0`, `temp_abxy=1`, `flip=0`, body pad re-registered within ~40 ms. |
| No controller, app start / restart | externalGamepads=0, no writes (idempotent). |
| Headphones / keyboard | Not tested with hardware, but keyboard-class devices (power key, headset button, fingerprint key) are present and correctly ignored. |
| ANR / crash | None after moving writes to a background thread. |

Not yet tested: the accessibility chords (Home+B Back, Home+A Home, Home+X Recents, Home+R1/L1 or D-pad volume) while
docked; Xbox-mode save/restore (test C); two controllers at once (test D); reboot (test H / BootReceiver).

## Key findings (do not rediscover)

1. **Retroid re-labels every controller.** Body pad and Bluetooth pads all appear as USB vendor 0x2022 / product 0x3001
   with `isExternal=true`. A Pro Controller shows up as "Nintendo Switch Pro Controller" with the body pad's ids.
   Vendor, product, isExternal and name cannot separate them. In Xbox mode the body pad is named "Xbox Wireless
   Controller", product 0x3002.
2. **The body pad is one InputDevice while enabled and is removed while disabled.** PadShift therefore counts gamepad
   devices and subtracts one when `no_create_gamepad_button_layout=0`. This is the detection rule that works.
3. **Use `InputDevice.supportsSource()`**, not a bare bitmask: `sources and (GAMEPAD or JOYSTICK) != 0` also matches every
   keyboard-class device via the shared button-class bit (this bug docked with no controller on the first build).
4. **Rootless writes need targetSdk ≤ 22.** For targetSdk ≥ 23 SettingsProvider throws
   `You cannot keep your settings in the secure settings` on vendor keys, even with Modify System Settings or
   WRITE_SECURE_SETTINGS. adb works because the shell uid is exempt; apps targeting 22 are exempt too and get
   WRITE_SETTINGS at install. Costs: a one-time "built for an older version of Android" dialog; Android 14+ refuses to
   install targetSdk < 23 apps; changing targetSdk 33 → 22 requires `adb uninstall` first.
5. **Writing `temp_abxy_layout_mode` + `flip_button_layout` fully switches Retro/Xbox** (`persist.sys.gamepad.type`
   follows), so no property write is needed.
6. **Uninstalling drops the app from `enabled_accessibility_services`.** Re-add by appending, never overwrite; in zsh use
   `"${VAR}:com.padshift.handheld/.ShortcutService"` (bare `$VAR:c` is a zsh modifier and corrupts the string).
7. Bluetooth ACL broadcasts were dropped: InputManager add/remove events are the single trigger and were sufficient.

## Code layout ([padshift/](padshift/))

- [PadShift.kt](padshift/app/src/main/java/com/padshift/handheld/PadShift.kt) — everything, ~175 lines:
  - `Rp5` object: settings keys, `put()` (skip-if-set → rootless → readback → `su` fallback), gamepad counting,
    `reconcile()`, `dock()`, `undock()`, `status()`, single-thread executor for writes.
  - `DockService`: foreground service, `InputManager.InputDeviceListener`, notification with Restore action
    (intent action `restore` undocks without re-reconciling).
  - `BootReceiver`: starts the service on boot.
  - `ShortcutService`: AccessibilityService key filter; active only while docked (`no_create=1`), Home/Guide
    (`KEYCODE_BUTTON_MODE`) is the swallowed modifier; chords fire once, auto-repeat swallowed, releases of consumed keys swallowed.
  - `MainActivity`: two toggles (auto, shortcuts), live status + gamepad device list, RESTORE HANDHELD CONTROLS NOW,
    buttons to the Modify System Settings and Accessibility pages.
- [AndroidManifest.xml](padshift/app/src/main/AndroidManifest.xml), [accessibility.xml](padshift/app/src/main/res/xml/accessibility.xml),
  [strings.xml](padshift/app/src/main/res/values/strings.xml), Gradle files. No AndroidX or other dependencies.
- Prefs (`SharedPreferences "padshift"`): `auto` (default true), `shortcuts` (default true), `prev` (0 Xbox / 1 Retro, default 1).

## Build and install

```bash
cd padshift
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew --no-daemon -q assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s PadShift                            # reconcile / write / shortcut lines
```

Toolchain installed via Homebrew: `openjdk@17`, `android-commandlinetools` (SDK at
/opt/homebrew/share/android-commandlinetools, platform 34, build-tools 34.0.0), Gradle 8.9 via wrapper, AGP 8.7.3, Kotlin 2.0.21.

## Recovery if body controls are ever stuck off

```bash
adb shell am force-stop com.padshift.handheld
adb shell settings put system no_create_gamepad_button_layout 0
adb shell settings put system temp_abxy_layout_mode 1     # 0 for Xbox
adb shell settings put system flip_button_layout 0        # 1 for Xbox
```

Or tap "Restore controls" on the notification / the red button in the app.

## Suggested next steps

1. Test chords while docked; check `adb logcat -s PadShift` for `key BUTTON_...` lines to see what the Pro Controller's
   Home button reports (expected `KEYCODE_BUTTON_MODE`). Its D-pad is likely a hat axis, hence the L1/R1 volume alternates.
2. Test Xbox mode save/restore and two controllers.
3. Reboot test for `BootReceiver`.
4. Optional polish: configurable chords, release signing (release builds need `lint { checkReleaseBuilds = false }` or
   the `ExpiredTargetSdkVersion` lint error will fail them).
