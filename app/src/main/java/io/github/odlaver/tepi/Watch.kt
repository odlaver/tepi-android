package io.github.odlaver.tepi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

enum class Brief { Charging, Unplugged, Silent, Vibrate, Ring, Audio }

// System broadcasts: power, ring mode, audio devices, camera and mic
class Watch(private val context: Context, private val prefs: Prefs, private val brief: (Brief, String) -> Unit, private val lowBattery: (Int) -> Unit, private val privacy: (Boolean, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private var started = false
    private var lowShown = 100
    private var lastRinger = -1
    private val camsBusy = HashSet<String>()
    private var mic = false
    private var ready = false

    var level = -1
        private set
    var charging = false
        private set

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (lv >= 0 && scale > 0) level = lv * 100 / scale
                    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                    if (charging) lowShown = 100
                    else if (prefs.bool("act.charge", true) && (level == 20 || level == 10) && level < lowShown) {
                        lowShown = level
                        lowBattery(level)
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> if (prefs.bool("act.charge", true)) main.postDelayed({ charging = true; brief(Brief.Charging, "$level%") }, 400)
                Intent.ACTION_POWER_DISCONNECTED -> if (prefs.bool("act.charge", true)) { charging = false; brief(Brief.Unplugged, "$level%") }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val m = audio.ringerMode
                    if (m == lastRinger) return
                    val first = lastRinger < 0
                    lastRinger = m
                    if (first || !prefs.bool("act.ringer", true)) return
                    when (m) {
                        AudioManager.RINGER_MODE_SILENT -> brief(Brief.Silent, "")
                        AudioManager.RINGER_MODE_VIBRATE -> brief(Brief.Vibrate, "")
                        else -> brief(Brief.Ring, "")
                    }
                }
            }
        }
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            // Initial list arrives on register
            if (!ready || !prefs.bool("act.audio", true)) return
            val d = added.firstOrNull { it.isSink && it.type in OUTPUTS } ?: return
            brief(Brief.Audio, d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "")
        }
    }

    private val cams = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(id: String) { camsBusy.add(id); report() }
        override fun onCameraAvailable(id: String) { camsBusy.remove(id); report() }
    }

    private val recs = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            mic = !configs.isNullOrEmpty()
            report()
        }
    }

    private fun report() {
        if (!ready) return
        privacy(prefs.bool("act.privacy", true) && camsBusy.isNotEmpty(), prefs.bool("act.privacy", true) && mic)
    }

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        context.registerReceiver(power, f, Context.RECEIVER_NOT_EXPORTED)
        lastRinger = audio.ringerMode
        audio.registerAudioDeviceCallback(devices, main)
        audio.registerAudioRecordingCallback(recs, main)
        camera.registerAvailabilityCallback(cams, main)
        // Initial device list arrives first; skip it
        main.postDelayed({ ready = true; report() }, 1500)
    }

    fun stop() {
        if (!started) return
        started = false
        ready = false
        try { context.unregisterReceiver(power) } catch (_: Exception) { }
        audio.unregisterAudioDeviceCallback(devices)
        audio.unregisterAudioRecordingCallback(recs)
        camera.unregisterAvailabilityCallback(cams)
    }

    companion object {
        private val OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
