package io.github.odlaver.tepi

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager

// Low-power mode: auto, forced on, forced off
object Power {
    const val AUTO = "auto"
    const val ON = "on"
    const val OFF = "off"

    @Volatile private var lowRam = false
    @Volatile private var saver = false
    @Volatile var screenOn = true
        private set
    @Volatile var offAt = 0L
        private set

    // Off long enough that nobody is looking
    fun idle(ms: Long) = !screenOn && android.os.SystemClock.elapsedRealtime() - offAt > ms
    private var listening = false

    // Small or Go phones
    fun weakDevice() = lowRam

    fun low(p: Prefs): Boolean = when (p.lowPower) {
        ON -> true
        OFF -> false
        else -> lowRam || saver
    }

    // Saver and screen changes reach here
    private val watch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; offAt = android.os.SystemClock.elapsedRealtime() }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> saver = c.getSystemService(PowerManager::class.java).isPowerSaveMode
            }
            changed?.invoke()
        }
    }
    private var changed: (() -> Unit)? = null

    fun start(c: Context, onChange: () -> Unit) {
        val app = c.applicationContext
        val am = app.getSystemService(ActivityManager::class.java)
        // Go edition, or 3 GB and under
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        lowRam = am.isLowRamDevice || mem.totalMem < 3_200_000_000L
        val pm = app.getSystemService(PowerManager::class.java)
        saver = pm.isPowerSaveMode
        screenOn = pm.isInteractive
        changed = onChange
        if (listening) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(watch, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(watch, f)
            listening = true
        } catch (_: Exception) { }
    }

    fun stop(c: Context) {
        changed = null
        if (!listening) return
        try { c.applicationContext.unregisterReceiver(watch) } catch (_: Exception) { }
        listening = false
    }
}
