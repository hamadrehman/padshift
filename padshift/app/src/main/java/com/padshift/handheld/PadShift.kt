package com.padshift.handheld

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import java.util.concurrent.Executors

private const val TAG = "PadShift"

/** Retroid Pocket 5 backend: the verified Settings.System keys, idempotent dock/undock, reconciliation. */
object Rp5 {
    const val NO_CREATE = "no_create_gamepad_button_layout" // 1 = body controller disabled, 0 = enabled
    const val MODE = "temp_abxy_layout_mode"                // 0 Xbox, 1 Retro, 2 Disconnect
    const val FLIP = "flip_button_layout"                   // 1 Xbox, 0 Retro / Disconnect
    var lastError: String? = null
    val exec = Executors.newSingleThreadExecutor() // writes may block on a root prompt: never run them on the main thread

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("padshift", 0)
    fun get(c: Context, k: String, d: Int) = Settings.System.getInt(c.contentResolver, k, d)

    /** Skip if already set; write rootless (legal because targetSdk 22 is exempt from the vendor-key restriction that throws for
     *  newer targets, exactly like the shell uid adb uses), verify by readback, fall back to root (su); never assume success. */
    fun put(c: Context, k: String, v: Int) {
        if (get(c, k, -1) == v) return
        try { if (Settings.System.canWrite(c)) Settings.System.putInt(c.contentResolver, k, v) } catch (e: Exception) { Log.w(TAG, "rootless write $k failed: $e") }
        if (get(c, k, -1) != v) try { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system $k $v")).waitFor() } catch (e: Exception) { Log.w(TAG, "root write $k failed: $e") }
        lastError = if (get(c, k, -1) == v) null else "FAILED to write $k (needs root or Modify System Settings)"
        Log.i(TAG, "write $k=$v readback=${get(c, k, -1)}")
    }

    /** A controller must report the full GAMEPAD or JOYSTICK source (a bare bitmask test also matches every keyboard-class
     *  device via the shared button class bit). */
    fun isGamepad(d: InputDevice?) = d != null && !d.isVirtual &&
        (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK))
    fun gamepads() = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter(::isGamepad)

    /** Retroid re-labels every controller, body or Bluetooth, as USB vendor 0x2022 / product 0x3001 with isExternal=true (a Switch
     *  Pro Controller shows up with the body controller's ids), so identity cannot separate them. What does: the body controller is
     *  exactly one input device while enabled and is removed while disabled. */
    fun externalGamepads(c: Context) = (gamepads().size - if (get(c, NO_CREATE, 0) == 0) 1 else 0).coerceAtLeast(0)

    fun reconcile(c: Context): Int {
        val n = externalGamepads(c)
        Log.i(TAG, "reconcile: externalGamepads=$n mode=${get(c, MODE, -1)} noCreate=${get(c, NO_CREATE, -1)} pads=${gamepads().map { it.name }}")
        if (prefs(c).getBoolean("auto", true)) { if (n > 0) dock(c) else undock(c) }
        return n
    }

    fun dock(c: Context) {
        val cur = get(c, MODE, 1)
        if (cur == 0 || cur == 1) prefs(c).edit().putInt("prev", cur).apply() // never remember Disconnect as "previous"
        put(c, NO_CREATE, 1); put(c, MODE, 2)
    }

    fun undock(c: Context) {
        val prev = prefs(c).getInt("prev", 1)
        put(c, NO_CREATE, 0); put(c, MODE, prev); put(c, FLIP, if (prev == 0) 1 else 0)
    }

    fun status(c: Context, sep: String = "\n") = listOf(
        "External gamepads: ${externalGamepads(c)}",
        "Handheld controls: ${if (get(c, NO_CREATE, 0) == 0) "Enabled" else "Disabled"}",
        "Retroid mode: ${listOf("Xbox", "Retro", "Disconnect").getOrElse(get(c, MODE, 1)) { "?" }}").joinToString(sep)
}

/** Foreground service: reconciles on start, on boot, and on every InputManager device add/remove/change. */
class DockService : Service(), InputManager.InputDeviceListener {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("dock", "PadShift", NotificationManager.IMPORTANCE_LOW))
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
    }
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        show() // foreground immediately; the writes below may block on a root prompt
        val restore = i?.action == "restore"
        Rp5.exec.execute { if (restore) Rp5.undock(this) else Rp5.reconcile(this); show() }
        return START_STICKY
    }
    override fun onInputDeviceAdded(id: Int) = changed(id)
    override fun onInputDeviceRemoved(id: Int) = changed(id)
    override fun onInputDeviceChanged(id: Int) = changed(id)
    private fun changed(id: Int) { Log.i(TAG, "input device event id=$id ${InputDevice.getDevice(id)?.name}"); Rp5.exec.execute { Rp5.reconcile(this); show() } }
    private fun show() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val restore = PendingIntent.getService(this, 1, Intent(this, DockService::class.java).setAction("restore"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "dock").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true)
            .setContentTitle("PadShift active").setContentText(Rp5.status(this, " · ")).setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_revert), "Restore controls", restore).build()).build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { c.startForegroundService(Intent(c, DockService::class.java)) }
}

/** Accessibility key filter: Home/Guide + button chords from external gamepads -> Android system actions. */
class ShortcutService : AccessibilityService() {
    private val consumed = HashSet<Int>()
    private var modHeld = false
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected() { Rp5.exec.execute { Rp5.reconcile(this) } }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        // Only while docked: the body controller is off then, so any gamepad key event comes from an external controller.
        if (!Rp5.prefs(this).getBoolean("shortcuts", true) || !Rp5.isGamepad(e.device) || Rp5.get(this, Rp5.NO_CREATE, 0) != 1) return false
        val k = e.keyCode; val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount == 0) Log.d(TAG, "key ${KeyEvent.keyCodeToString(k)} from ${e.device.name}")
        if (k == KeyEvent.KEYCODE_BUTTON_MODE) { modHeld = down; return true } // Home/Guide is reserved as the chord modifier
        if (!down) return consumed.remove(k)                                   // swallow the release of a key we swallowed
        if (e.repeatCount > 0) return k in consumed                            // auto-repeat never re-fires
        val action = (if (modHeld) ACTIONS[k] else null) ?: return false      // plain button: pass through to the game
        consumed.add(k)
        if (action < 0) (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (action == VOL_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        else performGlobalAction(action)
        Log.i(TAG, "shortcut ${KeyEvent.keyCodeToString(k)} -> $action")
        return true
    }

    companion object {
        const val VOL_UP = -1; const val VOL_DOWN = -2
        val ACTIONS = mapOf(
            KeyEvent.KEYCODE_BUTTON_B to AccessibilityService.GLOBAL_ACTION_BACK,
            KeyEvent.KEYCODE_BUTTON_A to AccessibilityService.GLOBAL_ACTION_HOME,
            KeyEvent.KEYCODE_BUTTON_X to AccessibilityService.GLOBAL_ACTION_RECENTS,
            KeyEvent.KEYCODE_DPAD_UP to VOL_UP, KeyEvent.KEYCODE_BUTTON_R1 to VOL_UP,     // many pads report the d-pad as a hat axis,
            KeyEvent.KEYCODE_DPAD_DOWN to VOL_DOWN, KeyEvent.KEYCODE_BUTTON_L1 to VOL_DOWN) // which never reaches onKeyEvent, so L1/R1 too
    }
}

class MainActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var status: TextView
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = Rp5.prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        fun toggle(key: String, label: String) = root.addView(Switch(this).apply {
            text = label; isChecked = p.getBoolean(key, true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply(); startService(Intent(this@MainActivity, DockService::class.java)) }
        })
        fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply { text = label; setOnClickListener { onClick(); refresh() } })
        toggle("auto", "Automatic Docking\nDisable handheld controls whenever one or more Bluetooth gamepads are connected.")
        toggle("shortcuts", "Controller Shortcuts\nUse controller chords for Android Back, Home, Recents and volume.")
        status = TextView(this).apply { setPadding(0, 32, 0, 32) }; root.addView(status)
        button("RESTORE HANDHELD CONTROLS NOW") { startService(Intent(this, DockService::class.java).setAction("restore")); later() }
        if (!Settings.System.canWrite(this)) button("Grant Modify System Settings") { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        button("Enable Controller Shortcuts (Accessibility)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.addView(TextView(this).apply { text = "SHORTCUTS (external gamepad; Home/Guide is the modifier)\nHome + B → Back\nHome + A → Home\nHome + X → Recents\nHome + D-pad Up or R1 → Volume Up\nHome + D-pad Down or L1 → Volume Down" })
        setContentView(ScrollView(this).apply { addView(root) })
        startForegroundService(Intent(this, DockService::class.java))
    }
    override fun onResume() { super.onResume(); (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null); refresh() }
    override fun onPause() { super.onPause(); (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this) }
    override fun onInputDeviceAdded(id: Int) = later()
    override fun onInputDeviceRemoved(id: Int) = later()
    override fun onInputDeviceChanged(id: Int) = later()
    private fun later() { status.postDelayed({ refresh() }, 700) } // let DockService reconcile first
    private fun refresh() {
        status.text = "STATUS\n${Rp5.status(this)}\nWrite path: ${if (Settings.System.canWrite(this)) "Modify System Settings (rootless)" else "root (su)"}\n${Rp5.lastError ?: ""}\n\nGamepad input devices (the body controller is one of these while enabled):\n" +
            Rp5.gamepads().joinToString("\n") { "• ${it.name}  vid=${it.vendorId} pid=${it.productId}" }
    }
}
