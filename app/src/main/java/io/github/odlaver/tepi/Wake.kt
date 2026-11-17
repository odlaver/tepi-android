package io.github.odlaver.tepi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// Handler delays stop while the phone sleeps; alarms do not
class Wake : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Listener.instance?.woke()
    }

    companion object {
        const val TIMER = 1
        const val ONCE = 2
        const val PRAY = 3

        private fun pi(c: Context, slot: Int, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
            c, slot, Intent(c, Wake::class.java),
            PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
        )

        // Zero or past clears the slot
        fun set(c: Context, slot: Int, at: Long) {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            if (at <= System.currentTimeMillis()) { pi(c, slot, false)?.let { am.cancel(it); it.cancel() }; return }
            val p = pi(c, slot, true) ?: return
            try {
                // Exact needs a grant on Android 12; inexact may run minutes late
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            }
        }
    }
}
