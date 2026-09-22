# Dockify for Android Handhelds — Implementation Specification

## 1. Purpose

Build a small Android utility called **Dockify** that makes Android gaming handhelds behave more like a dockable console.

The initial target device is the **Retroid Pocket 5 (RP5)**.

The app has two primary jobs:

1. **Automatic controller takeover**
   - When **one or more Bluetooth gamepads** are connected, disable the RP5's built-in/body controller.
   - When **zero Bluetooth gamepads** are connected, re-enable the RP5's built-in/body controller.
   - Keep Retroid's own Controller Style quick-settings tile visually synchronized with the actual controller state.
   - Preserve and restore the user's previous Retroid button-layout mode (Retro or Xbox).

2. **Controller-to-Android system shortcuts**
   - Allow selected gamepad buttons or button combinations to invoke Android system actions such as:
     - Back
     - Home
     - Recents
     - Volume Up
     - Volume Down
     - Optional Screenshot / Quick Settings later
   - This should be implemented using an Android `AccessibilityService` where possible.
   - Normal gamepad input must pass through untouched unless a configured shortcut is matched.

The app should be deliberately small, focused, and reliable. It is **not** intended to become a full controller remapper, emulator frontend, virtual gamepad driver, or touch-mapping engine.

---

# 2. Product Philosophy

The core principle is:

> Android already understands gamepads. Dockify should only make Android handhelds understand docking.

Do not recreate Steam Input. Do not translate all controller events. Do not create a virtual controller unless absolutely required in a future version.

The RP5-specific controller takeover can be implemented using Retroid system settings that have already been reverse-engineered and tested on a real RP5.

---

# 3. Verified RP5 Reverse-Engineering Results

The following values have been directly measured on a Retroid Pocket 5.

These are **verified facts for the target device** and should not be rediscovered.

## 3.1 Retroid controller state table

Retroid exposes three Controller Style modes:

- Xbox
- Retro
- Disconnect

The relevant values are:

| Retroid mode | `flip_button_layout` | `no_create_gamepad_button_layout` | `temp_abxy_layout_mode` | `persist.sys.gamepad.type` |
|---|---:|---:|---:|---:|
| Xbox | 1 | 0 | 0 | 1 |
| Retro | 0 | 0 | 1 | 0 |
| Disconnect | 0 | 1 | 2 | 2 |

The first three are `Settings.System` values.

The last one is a system property.

---

## 3.2 Verified functional controller switch

This command was tested on the RP5:

```bash
adb shell settings put system no_create_gamepad_button_layout 1
```

Result:

> The RP5's built-in/body controller stops working.

This command was also tested:

```bash
adb shell settings put system no_create_gamepad_button_layout 0
```

Result:

> The RP5's built-in/body controller starts working again.

Therefore:

```text
no_create_gamepad_button_layout = 1
```

means:

```text
Built-in Retroid controller disabled
```

and:

```text
no_create_gamepad_button_layout = 0
```

means:

```text
Built-in Retroid controller enabled
```

This is the **actual functional switch** that Dockify must use on RP5.

---

## 3.3 Verified Retroid tile state switch

This command was tested:

```bash
adb shell settings put system temp_abxy_layout_mode 2
```

Result:

> The Retroid Controller Style quick-settings tile changes to Disconnect.

Therefore:

```text
temp_abxy_layout_mode = 0 -> Xbox
temp_abxy_layout_mode = 1 -> Retro
temp_abxy_layout_mode = 2 -> Disconnect
```

This value controls the Retroid UI/tile representation.

Dockify should update this value so that Retroid's own UI always agrees with the actual body-controller state.

---

## 3.4 `flip_button_layout`

Verified values:

```text
Xbox -> flip_button_layout = 1
Retro -> flip_button_layout = 0
Disconnect -> flip_button_layout = 0
```

This appears to represent ABXY layout orientation.

Dockify only needs to restore this when returning from Disconnect to the user's previous mode.

---

## 3.5 `persist.sys.gamepad.type`

Verified values:

```text
Xbox -> persist.sys.gamepad.type = 1
Retro -> persist.sys.gamepad.type = 0
Disconnect -> persist.sys.gamepad.type = 2
```

However:

- The built-in controller can already be enabled/disabled using `no_create_gamepad_button_layout`.
- The Retroid tile can already be switched using `temp_abxy_layout_mode`.

Therefore **do not require modification of `persist.sys.gamepad.type` for V1**.

It may be useful later if testing shows Retroid needs it for reboot persistence or deeper internal consistency.

Because writing `persist.sys.*` usually requires elevated privileges, V1 should avoid depending on it unless clearly necessary.

---

# 4. Required Docking Behavior

The docking rule is intentionally simple:

```text
If number of connected Bluetooth GAMEPADS >= 1:
    disable RP5 body controller
    show Retroid tile as Disconnect

If number of connected Bluetooth GAMEPADS == 0:
    enable RP5 body controller
    restore previous Retro/Xbox state
```

Do **not** trigger on every Bluetooth device.

Bluetooth earbuds, keyboards, mice, watches, speakers, etc. must not cause docking.

The trigger is:

> Any connected Bluetooth input device that Android identifies as a gamepad / joystick.

There is no preferred-controller concept in V1.

---

# 5. Important Multi-Controller Rule

The app must support multiple Bluetooth gamepads correctly.

Example:

```text
Controller A connects
-> body controller OFF

Controller B connects
-> body controller remains OFF

Controller A disconnects
-> body controller remains OFF because B is still connected

Controller B disconnects
-> no external Bluetooth gamepads remain
-> body controller ON
```

Do not simply undock on every `ACTION_ACL_DISCONNECTED`.

Always re-evaluate the total number of currently connected Bluetooth gamepads.

---

# 6. Preserve the User's Previous Retroid Mode

Before entering Disconnect, Dockify must remember whether the user was using:

```text
Xbox
```

or:

```text
Retro
```

Read:

```text
Settings.System["temp_abxy_layout_mode"]
```

Interpret:

```text
0 = Xbox
1 = Retro
2 = Disconnect
```

If current mode is 0 or 1, save it as `previousRetroidMode`.

Do not overwrite `previousRetroidMode` when the current mode is already 2.

This prevents this failure:

```text
Retro
-> dock
-> Disconnect
-> service restarts
-> reads 2
-> saves 2 as previous state
-> undock
-> remains Disconnect
```

Use persistent storage, e.g. `SharedPreferences` or DataStore.

Recommended:

```text
previous_retroid_mode = 0 or 1
```

Default fallback:

```text
1 = Retro
```

---

# 7. Exact RP5 Dock / Undock Operations

## 7.1 Dock

For RP5:

```text
no_create_gamepad_button_layout = 1
temp_abxy_layout_mode = 2
```

Pseudo-code:

```kotlin
fun dockRp5() {
    val currentMode = getSystemInt("temp_abxy_layout_mode", 1)

    if (currentMode == 0 || currentMode == 1) {
        savePreviousMode(currentMode)
    }

    putSystemInt("no_create_gamepad_button_layout", 1)
    putSystemInt("temp_abxy_layout_mode", 2)
}
```

Expected result:

```text
Body controller: OFF
Retroid tile: Disconnect
External Bluetooth gamepad(s): untouched
```

---

## 7.2 Undock

Restore the internal controller first:

```text
no_create_gamepad_button_layout = 0
```

Then restore prior Retroid mode.

### Restore Retro

```text
temp_abxy_layout_mode = 1
flip_button_layout = 0
```

### Restore Xbox

```text
temp_abxy_layout_mode = 0
flip_button_layout = 1
```

Pseudo-code:

```kotlin
fun undockRp5() {
    val previousMode = loadPreviousMode(default = 1)

    putSystemInt("no_create_gamepad_button_layout", 0)

    when (previousMode) {
        0 -> {
            putSystemInt("temp_abxy_layout_mode", 0)
            putSystemInt("flip_button_layout", 1)
        }

        else -> {
            putSystemInt("temp_abxy_layout_mode", 1)
            putSystemInt("flip_button_layout", 0)
        }
    }
}
```

---

# 8. Rootless vs Root Backend

The app should be written so that controller-state mutation is abstracted behind an interface.

Example:

```kotlin
interface HandheldControllerBackend {
    val isSupported: Boolean

    fun readMode(): HandheldMode
    fun dock()
    fun undock(previousMode: HandheldMode)
}
```

Initial implementations:

```text
RetroidRp5SettingsBackend
RootShellBackend (future/general fallback)
```

---

## 8.1 Preferred RP5 backend

Try to write the discovered `Settings.System` keys directly.

Manifest:

```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

Use:

```kotlin
Settings.System.canWrite(context)
```

If not granted, open:

```kotlin
Intent(
    Settings.ACTION_MANAGE_WRITE_SETTINGS,
    Uri.parse("package:${context.packageName}")
)
```

Then attempt:

```kotlin
Settings.System.putInt(
    contentResolver,
    "no_create_gamepad_button_layout",
    value
)
```

and similarly for:

```text
temp_abxy_layout_mode
flip_button_layout
```

Important:

Although these values are in `Settings.System`, Android OEM restrictions may still affect whether a normal app with Modify System Settings can mutate undocumented vendor keys.

Therefore:

- Implement this rootless path first.
- Verify actual write success by reading the value back.
- If writing fails, use a root fallback.

Do not assume a write succeeded simply because `Settings.System.putInt()` returned normally.

---

## 8.2 Root fallback

The user is comfortable granting root if necessary.

If root is available, Dockify may write these settings through `su`.

Example:

```bash
settings put system no_create_gamepad_button_layout 1
settings put system temp_abxy_layout_mode 2
```

and:

```bash
settings put system no_create_gamepad_button_layout 0
settings put system temp_abxy_layout_mode 1
settings put system flip_button_layout 0
```

Root fallback can be implemented with a small root-shell utility.

Example interface:

```kotlin
interface PrivilegedCommandRunner {
    suspend fun run(command: String): Result<String>
}
```

Avoid spawning a root shell for every event if a persistent root shell can be maintained safely.

However, because docking events are very infrequent, simplicity is more important than micro-optimization.

---

# 9. Bluetooth Gamepad Detection

## 9.1 Goal

Detect when any Bluetooth-connected device is actually a controller.

Do not dock because of:

- headphones
- earbuds
- speakers
- keyboard
- mouse
- smartwatch
- car Bluetooth
- generic BLE accessory

---

## 9.2 Bluetooth connection events

Listen for:

```text
BluetoothDevice.ACTION_ACL_CONNECTED
BluetoothDevice.ACTION_ACL_DISCONNECTED
```

On Android 12+ request:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Use runtime permission handling.

---

## 9.3 Do not trust Bluetooth class alone

A Bluetooth device class is useful but should not be the only criterion.

The best determination is whether Android currently exposes an `InputDevice` associated with the Bluetooth device whose sources include:

```text
InputDevice.SOURCE_GAMEPAD
```

and/or:

```text
InputDevice.SOURCE_JOYSTICK
```

Useful test:

```kotlin
fun isGameController(device: InputDevice): Boolean {
    val sources = device.sources

    val gamepad =
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

    val joystick =
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    return gamepad || joystick
}
```

---

## 9.4 Recommended approach: maintain controller set using InputManager

Use:

```text
InputManager.InputDeviceListener
```

to react to Android input-device add/remove/change events.

This may actually be more reliable than depending only on Bluetooth ACL events because Android may establish Bluetooth first and register the gamepad input device milliseconds later.

Recommended architecture:

```text
Bluetooth event
    -> schedule immediate reconciliation

InputManager onInputDeviceAdded
    -> reconciliation

InputManager onInputDeviceRemoved
    -> reconciliation

Service start
    -> reconciliation
```

The single source of truth should be:

> How many currently attached Android input devices are both game controllers and Bluetooth/external.

---

# 10. Determining Whether a Gamepad Is External / Bluetooth

Built-in Retroid controls must never count as an external controller.

Prefer checking:

```kotlin
inputDevice.isExternal
```

where available/reliable.

Also inspect:

```text
InputDevice.name
InputDevice.descriptor
InputDevice.vendorId
InputDevice.productId
InputDevice.sources
```

The RP5 built-in controller can be excluded through a combination of:

- `isExternal == false`, if Retroid reports it correctly
- known built-in descriptor/device name if required
- transport / descriptor heuristics

For V1 on RP5, log all input devices during development so the built-in controller can be confidently excluded.

Do not hardcode `/dev/input/eventX`.

---

# 11. Reconciliation Instead of Event-Only Logic

Do not write logic like:

```text
on CONNECT -> dock
on DISCONNECT -> undock
```

Instead implement:

```kotlin
fun reconcileDockState() {
    val externalGamepads = getConnectedExternalGamepads()

    if (externalGamepads.isNotEmpty()) {
        ensureDocked()
    } else {
        ensureUndocked()
    }
}
```

Call reconciliation on:

- app/service startup
- accessibility service startup
- Bluetooth connected
- Bluetooth disconnected
- InputDevice added
- InputDevice removed
- InputDevice changed
- boot completed, if background startup is used
- user taps manual refresh
- optional screen unlock

This makes the app self-healing.

---

# 12. Idempotency

`ensureDocked()` and `ensureUndocked()` must be idempotent.

Repeated execution must be harmless.

Example:

```kotlin
fun ensureDocked() {
    if (currentRetroidStateIsAlreadyDocked()) return
    dock()
}
```

Similarly:

```kotlin
fun ensureUndocked() {
    if (bodyControllerAlreadyEnabledAndModeRestored()) return
    undock()
}
```

Do not repeatedly overwrite the user's saved previous mode.

---

# 13. Safety Rule

The app must never leave the built-in controller disabled when no external gamepad is available.

Always prefer:

```text
false positive undock
```

over:

```text
user stranded with no controls
```

If uncertain whether an external controller still exists, enable the internal controller.

Provide a UI button:

```text
RESTORE HANDHELD CONTROLS NOW
```

that forces:

```text
no_create_gamepad_button_layout = 0
```

and restores the saved Retro/Xbox mode.

This recovery control should be highly visible in the app.

---

# 14. Accessibility Controller Shortcuts

This is the second major feature.

Use an Android `AccessibilityService`.

The service should request hardware key filtering.

Accessibility service XML/config should include:

```text
FLAG_REQUEST_FILTER_KEY_EVENTS
```

The service should override:

```kotlin
override fun onKeyEvent(event: KeyEvent): Boolean
```

---

## 14.1 Core behavior

For each incoming gamepad key event:

1. Determine the originating `InputDevice`.
2. Ignore events from the built-in handheld controller if desired.
3. Ignore keyboards unless explicitly supported.
4. Track currently pressed buttons.
5. Match configured shortcut combinations.
6. If a shortcut matches:
   - trigger Android system action
   - consume the event as required
7. Otherwise:
   - return `false`
   - allow game/emulator to receive the event normally

---

# 15. Initial Shortcut Actions

V1 should support:

```text
Android Back
Android Home
Android Recents
Volume Up
Volume Down
```

Recommended Accessibility actions:

```kotlin
performGlobalAction(GLOBAL_ACTION_BACK)
performGlobalAction(GLOBAL_ACTION_HOME)
performGlobalAction(GLOBAL_ACTION_RECENTS)
```

Volume can use `AudioManager`.

Optional later:

```text
Screenshot
Quick Settings
Notifications
Power dialog
Lock screen
```

---

# 16. Avoid Sacrificing Normal Game Buttons

Do not default a normal face button directly to Back/Home.

Use a modifier/chord model.

Recommended default concept:

```text
Controller Home + B -> Android Back
Controller Home + A -> Android Home
Controller Home + X -> Recents
Controller Home + D-pad Up -> Volume Up
Controller Home + D-pad Down -> Volume Down
```

The exact defaults may be adjusted based on how the connected controller reports its guide/home button.

The app should eventually allow the user to configure these.

---

# 17. Shortcut Chord Handling

Need to avoid leaking partial chord events into games where possible.

Example:

```text
Home pressed
B pressed
```

If the chord `Home+B` maps to Back:

- trigger Back once
- consume the relevant key events
- prevent repeated triggering from key auto-repeat
- reset chord state on key-up

Suggested internal state:

```kotlin
val pressedKeys = mutableSetOf<Int>()
val firedShortcuts = mutableSetOf<ShortcutId>()
```

On `ACTION_DOWN`:

```text
add key
check shortcuts
if newly matched -> fire once
```

On `ACTION_UP`:

```text
remove key
clear shortcut fired state when chord is no longer held
```

---

# 18. Controller Event Scope

Shortcuts should only trigger from external game controllers by default.

Do not intercept:

- RP5 body buttons
- attached keyboard
- touchscreen
- volume buttons
- unrelated input devices

unless explicitly configured later.

---

# 19. Persistent Notification / Foreground Service

The user is comfortable with a persistent notification if useful.

A small foreground service is acceptable.

Recommended notification:

```text
Dockify active
Handheld controls: Enabled
External gamepads: 0
```

When docked:

```text
Dockify active
Handheld controls: Disabled
External gamepads: 1
```

Actions:

```text
Restore controls
Open Dockify
```

Keep notification low priority / silent.

---

# 20. Service Architecture

Suggested components:

```text
MainActivity
DockService
DockStateManager
BluetoothGamepadMonitor
RetroidRp5Backend
AccessibilityShortcutService
ShortcutManager
SettingsRepository
RootShellRunner
BootReceiver
```

Suggested package layout:

```text
app/
  MainActivity.kt

dock/
  DockService.kt
  DockStateManager.kt
  DockState.kt

input/
  BluetoothGamepadMonitor.kt
  InputDeviceUtils.kt

backend/
  HandheldControllerBackend.kt
  RetroidRp5Backend.kt
  RootShellRunner.kt

accessibility/
  DockifyAccessibilityService.kt
  ShortcutManager.kt
  Shortcut.kt

data/
  SettingsRepository.kt

receiver/
  BootReceiver.kt
```

---

# 21. Suggested Dock State Model

```kotlin
sealed interface DockState {
    data object Undocked : DockState

    data class Docked(
        val externalGamepadCount: Int
    ) : DockState
}
```

Retroid mode:

```kotlin
enum class RetroidMode(val raw: Int) {
    XBOX(0),
    RETRO(1),
    DISCONNECT(2)
}
```

Note:

`temp_abxy_layout_mode` uses:

```text
0 Xbox
1 Retro
2 Disconnect
```

`persist.sys.gamepad.type` uses a different mapping:

```text
1 Xbox
0 Retro
2 Disconnect
```

Do not confuse these.

---

# 22. Recommended DataStore / Preferences

Store:

```text
auto_docking_enabled: Boolean
controller_shortcuts_enabled: Boolean
previous_retroid_mode: Int
notification_enabled: Boolean
root_backend_enabled: Boolean
```

Future:

```text
shortcut mappings
excluded controllers
included controllers
device backend override
```

For V1 there is no need to store a preferred Bluetooth controller.

---

# 23. First-Run Flow

Recommended first launch:

## Screen 1 — Welcome

```text
Dockify makes your handheld automatically behave like a docked console when a Bluetooth gamepad connects.
```

## Screen 2 — Modify System Settings

Explain:

```text
Dockify needs permission to enable and disable the handheld's built-in controller.
```

Button:

```text
Grant Modify System Settings
```

Verify ability to write/read the Retroid keys.

If rootless write fails:

```text
Root access is required on this device.
```

Then request root.

## Screen 3 — Accessibility

Explain:

```text
Accessibility is used only for optional controller shortcuts such as Back, Home, and Recents.
```

Button:

```text
Enable Controller Shortcuts
```

This should be optional if the user only wants automatic docking.

## Screen 4 — Done

Show current state:

```text
External gamepads: 0
Handheld controls: Enabled
Retroid mode: Retro
```

---

# 24. Main UI

Keep it extremely simple.

Suggested screen:

```text
DOCKIFY

Automatic Docking                         [ ON ]
Disable handheld controls whenever one or
more Bluetooth gamepads are connected.

Controller Shortcuts                     [ ON ]
Use controller combinations for Android
Back, Home, Recents and volume.

STATUS

External gamepads: 1
Handheld controls: Disabled
Retroid mode: Disconnect

[ Restore Handheld Controls ]

SHORTCUTS

Home + B      Back
Home + A      Home
Home + X      Recents
Home + ↑      Volume Up
Home + ↓      Volume Down
```

No controller picker is required.

---

# 25. Boot Behavior

If the user enables automatic docking:

1. Start/reconcile on boot.
2. Enumerate current input devices.
3. If one or more Bluetooth gamepads are already connected:
   - Dock.
4. Otherwise:
   - Ensure body controller is enabled.
   - Restore saved prior mode if needed.

Do not assume Bluetooth connection broadcasts will always be replayed after reboot.

Reconciliation is mandatory.

---

# 26. App Crash / Kill Behavior

If Android kills the process while docked, the Retroid setting may remain:

```text
no_create_gamepad_button_layout = 1
```

Therefore on next service/app start, immediately reconcile.

Also provide a persistent notification action:

```text
Restore controls
```

If feasible, consider a watchdog only if real-world testing shows process death is common.

Avoid excessive wakeups or polling.

---

# 27. Battery Requirements

This app should have negligible battery impact.

Design constraints:

- No periodic polling loop.
- No high-frequency timers.
- No GPS.
- No sensor polling.
- No Logcat monitoring.
- No wake lock unless strictly necessary.
- Prefer event-driven:
  - Bluetooth events
  - InputManager callbacks
  - Accessibility callbacks
  - boot event
- Foreground service may remain idle between events.

---

# 28. Manual Test Commands for RP5

These are useful during development.

## Disable body controller

```bash
adb shell settings put system no_create_gamepad_button_layout 1
```

## Re-enable body controller

```bash
adb shell settings put system no_create_gamepad_button_layout 0
```

## Set Retroid tile to Disconnect

```bash
adb shell settings put system temp_abxy_layout_mode 2
```

## Set tile to Retro

```bash
adb shell settings put system temp_abxy_layout_mode 1
```

## Set tile to Xbox

```bash
adb shell settings put system temp_abxy_layout_mode 0
```

## Restore Retro ABXY orientation

```bash
adb shell settings put system flip_button_layout 0
```

## Restore Xbox ABXY orientation

```bash
adb shell settings put system flip_button_layout 1
```

---

# 29. State Inspection Commands

```bash
adb shell settings get system flip_button_layout
adb shell settings get system no_create_gamepad_button_layout
adb shell settings get system temp_abxy_layout_mode
adb shell getprop persist.sys.gamepad.type
```

Expected:

## Xbox

```text
flip_button_layout=1
no_create_gamepad_button_layout=0
temp_abxy_layout_mode=0
persist.sys.gamepad.type=1
```

## Retro

```text
flip_button_layout=0
no_create_gamepad_button_layout=0
temp_abxy_layout_mode=1
persist.sys.gamepad.type=0
```

## Disconnect

```text
flip_button_layout=0
no_create_gamepad_button_layout=1
temp_abxy_layout_mode=2
persist.sys.gamepad.type=2
```

---

# 30. RP5 Acceptance Tests

## Test A — Basic automatic dock

Starting state:

```text
RP5 body controls active
Retroid mode = Retro
0 Bluetooth gamepads
```

Action:

```text
Connect an 8BitDo Bluetooth controller
```

Expected:

```text
body controls stop responding
Retroid tile changes to Disconnect
8BitDo continues functioning
```

---

## Test B — Automatic undock

Starting state:

```text
1 Bluetooth gamepad
body controls disabled
Retroid tile = Disconnect
```

Action:

```text
Turn off Bluetooth controller
```

Expected:

```text
body controls resume
Retroid tile returns to Retro
flip_button_layout = 0
```

---

## Test C — Xbox restoration

Starting state:

```text
Retroid mode = Xbox
```

Connect gamepad.

Expected:

```text
Disconnect
```

Disconnect final external gamepad.

Expected:

```text
Xbox restored
flip_button_layout = 1
```

---

## Test D — Two gamepads

Connect A.

Expected:

```text
body controls OFF
```

Connect B.

Expected:

```text
body controls OFF
```

Disconnect A.

Expected:

```text
body controls still OFF
```

Disconnect B.

Expected:

```text
body controls ON
previous mode restored
```

---

## Test E — Headphones

Connect Bluetooth earbuds.

Expected:

```text
no state change
body controller remains enabled
```

---

## Test F — Bluetooth keyboard

Connect Bluetooth keyboard.

Expected:

```text
no state change
```

---

## Test G — Service restart while docked

Connect gamepad.

Kill/restart Dockify service.

Expected:

```text
service enumerates input devices
detects existing external controller
remains docked
previous Retro/Xbox state is not overwritten with Disconnect
```

---

## Test H — Restart while undocked

No Bluetooth gamepads connected.

Restart service.

Expected:

```text
body controls enabled
previous mode restored
```

---

## Test I — Manual recovery

Force:

```text
no_create_gamepad_button_layout=1
```

with no external controller.

Press:

```text
Restore Handheld Controls
```

Expected:

```text
body controller enabled immediately
previous mode restored
```

---

# 31. Accessibility Acceptance Tests

## Back shortcut

Configured:

```text
Home + B -> Back
```

Expected:

- Android Back executes once.
- Game does not receive an unintended B action if the chord is successfully consumed.
- Releasing/repressing can trigger again.
- Holding does not spam Back.

## Home shortcut

```text
Home + A -> Home
```

Expected:

```text
Android launcher opens
```

## Recents shortcut

```text
Home + X -> Recents
```

Expected:

```text
Android Recents opens
```

## Pass-through

Press B without Home.

Expected:

```text
game receives normal B
Dockify does nothing
```

---

# 32. Logging

During development, add structured logs for:

```text
Input device added
Input device removed
Input device name
descriptor
vendor ID
product ID
sources
isExternal
current external gamepad count
current Retroid mode
docking decision
settings write result
settings readback result
root fallback result
shortcut fired
```

Do not leave verbose continuous logging enabled in production by default.

Provide an optional debug screen later if useful.

---

# 33. Device Detection

Initial target is RP5.

Do not attempt broad automatic vendor detection in the first build.

Implement a Retroid backend that activates when the required keys exist and behave as expected.

Example capability probe:

```text
Can read:
temp_abxy_layout_mode

Can read:
no_create_gamepad_button_layout
```

Optionally inspect:

```text
Build.MANUFACTURER
Build.MODEL
```

but do not depend entirely on model strings.

---

# 34. Generic Android Handheld Future Backend

Future generic rooted fallback may suppress built-in controls via Linux evdev `EVIOCGRAB`.

Concept:

```text
open built-in /dev/input/event* nodes
issue EVIOCGRAB
Android stops receiving those events
release grab when undocking
```

Advantages:

- reversible
- no kernel modification
- grab releases if process/FD closes

However, this is **not needed for RP5 V1** because the Retroid-specific settings already work.

Do not spend time on evdev for the first implementation unless requested.

---

# 35. Non-Goals for V1

Do not implement:

- per-emulator profiles
- touchscreen mapping
- controller calibration
- virtual controller creation
- Steam Input clone
- input latency tuning
- controller firmware management
- game launching
- frontend features
- HDMI detection
- automatic TV resolution switching
- controller-specific mappings
- per-game mappings
- Linux support
- controller driver replacement

Keep V1 small.

---

# 36. Suggested Minimum SDK / Modern Android

Use current Android tooling and Kotlin.

Recommended stack:

```text
Kotlin
AndroidX
Material 3 or simple Compose UI
DataStore Preferences
Foreground Service
InputManager
Bluetooth APIs
AccessibilityService
```

Compose is optional.

A classic XML UI is perfectly acceptable if it reduces complexity.

---

# 37. Critical Implementation Detail: Verify Writes

When rootless:

```kotlin
Settings.System.putInt(...)
```

must always be followed by readback:

```kotlin
val written = Settings.System.getInt(...)
```

If requested value != readback value:

```text
rootless backend failed
```

Then:

- if root is available, fallback to root
- otherwise show a clear setup error

Do not silently pretend docking succeeded.

---

# 38. Recommended State Mutation API

Example:

```kotlin
data class RetroidControllerState(
    val noCreateGamepadButtonLayout: Int,
    val tempAbxyLayoutMode: Int,
    val flipButtonLayout: Int
)

interface RetroidSettingsWriter {
    suspend fun read(): RetroidControllerState
    suspend fun write(state: RetroidControllerState): Boolean
}
```

Dock:

```kotlin
RetroidControllerState(
    noCreateGamepadButtonLayout = 1,
    tempAbxyLayoutMode = 2,
    flipButtonLayout = 0
)
```

Retro:

```kotlin
RetroidControllerState(
    noCreateGamepadButtonLayout = 0,
    tempAbxyLayoutMode = 1,
    flipButtonLayout = 0
)
```

Xbox:

```kotlin
RetroidControllerState(
    noCreateGamepadButtonLayout = 0,
    tempAbxyLayoutMode = 0,
    flipButtonLayout = 1
)
```

---

# 39. Recommended Reconciliation Algorithm

Pseudo-code:

```kotlin
suspend fun reconcile() {
    if (!settings.autoDockingEnabled) {
        return
    }

    val externalGamepads = gamepadMonitor.connectedExternalGamepads()

    if (externalGamepads.isNotEmpty()) {
        enterDockedState()
    } else {
        enterUndockedState()
    }
}
```

Enter docked:

```kotlin
suspend fun enterDockedState() {
    val currentMode = backend.readMode()

    if (currentMode == XBOX || currentMode == RETRO) {
        settings.savePreviousMode(currentMode)
    }

    backend.setDocked()

    stateFlow.value = Docked(
        externalGamepadCount = gamepadMonitor.count
    )
}
```

Enter undocked:

```kotlin
suspend fun enterUndockedState() {
    val previousMode = settings.previousMode ?: RETRO

    backend.setUndocked(previousMode)

    stateFlow.value = Undocked
}
```

---

# 40. Recommended Event Flow

```text
Bluetooth controller powers on
        |
        v
Android creates Bluetooth connection
        |
        v
InputManager sees new InputDevice
        |
        v
Dockify reconcile()
        |
        v
external gamepad count > 0
        |
        v
remember prior Retroid mode
        |
        v
no_create_gamepad_button_layout = 1
        |
        v
temp_abxy_layout_mode = 2
        |
        v
body controls OFF
Retroid UI says Disconnect
```

Disconnect:

```text
Last Bluetooth gamepad powers off
        |
        v
InputManager removes device
        |
        v
Dockify reconcile()
        |
        v
external gamepad count = 0
        |
        v
no_create_gamepad_button_layout = 0
        |
        v
restore temp_abxy_layout_mode
        |
        v
restore flip_button_layout
        |
        v
body controls ON
Retroid UI restored
```

---

# 41. Implementation Priority

Build in this order.

## Phase 1

Create a simple Android app with buttons:

```text
Dock
Undock Retro
Undock Xbox
```

Verify rootless Settings.System writes.

If rootless fails, implement root fallback.

Do not add Bluetooth yet.

---

## Phase 2

Implement gamepad enumeration.

Show:

```text
Connected external gamepads: N
```

Make sure:

```text
8BitDo = counted
RP5 built-in controls = not counted
headphones = not counted
```

---

## Phase 3

Connect gamepad count to `reconcile()`.

At this point automatic docking should be fully functional.

---

## Phase 4

Add foreground service + notification.

---

## Phase 5

Add boot reconciliation.

---

## Phase 6

Add Accessibility controller shortcuts.

---

## Phase 7

Polish UI and error recovery.

---

# 42. Definition of Done for V1

Dockify V1 is complete when the following works reliably on RP5:

1. User launches Dockify.
2. Required permission is granted.
3. Automatic Docking is enabled.
4. RP5 is in Retro or Xbox mode.
5. User connects any Bluetooth gamepad.
6. Within approximately one second:
   - body controller stops responding
   - external gamepad still works
   - Retroid tile says Disconnect
7. User connects a second gamepad.
8. Body controller remains disabled.
9. One gamepad disconnects.
10. Body controller remains disabled.
11. Last gamepad disconnects.
12. RP5 body controls immediately return.
13. Previous Retro/Xbox mode is restored.
14. Retroid tile matches restored state.
15. Bluetooth headphones alone never trigger docking.
16. Reboot/restart does not strand the user without controls.
17. Optional Accessibility shortcuts allow Android Back/Home/Recents from the external controller.
18. Normal gamepad buttons continue to behave normally in games unless a configured shortcut chord is intentionally matched.

---

# 43. Very Important Notes for the Coding Agent

Do not waste time rediscovering the RP5 settings.

They have already been verified.

The key facts are:

```text
FUNCTIONAL BODY CONTROLLER SWITCH:
system:no_create_gamepad_button_layout
1 = disabled
0 = enabled
```

```text
RETROID TILE STATE:
system:temp_abxy_layout_mode
0 = Xbox
1 = Retro
2 = Disconnect
```

```text
ABXY LAYOUT:
system:flip_button_layout
1 = Xbox
0 = Retro / Disconnect
```

The `persist.sys.gamepad.type` property exists but is not required for the already-tested runtime behavior.

Start by building the smallest possible proof of concept using those values.

Do not start by reverse-engineering Retroid firmware.

Do not start by writing an evdev driver.

Do not start by implementing generic handheld support.

Get the RP5 backend working first.

---

# 44. Suggested Project Name

Working name:

```text
Dockify
```

Possible package:

```text
com.dockify.handheld
```

or:

```text
app.dockify.android
```

---

# 45. Final Product Summary

Dockify should make the Retroid Pocket 5 behave like this:

```text
HANDHELD MODE

No Bluetooth gamepads
        |
        v
RP5 controls ON
Retroid mode restored
```

```text
DOCKED MODE

Any Bluetooth gamepad connects
        |
        v
RP5 body controller OFF
Retroid tile -> Disconnect
External controller remains active
```

```text
UNDOCK

Last Bluetooth gamepad disconnects
        |
        v
RP5 body controller ON
Previous Retro/Xbox mode restored
```

Plus:

```text
External controller shortcuts
        |
        +-> Android Back
        +-> Android Home
        +-> Android Recents
        +-> Volume
```

That is the complete intended V1 behavior.
