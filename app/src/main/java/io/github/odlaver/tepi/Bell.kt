package io.github.odlaver.tepi

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator

// Chime and buzz, silent under Do Not Disturb
object Bell {
    fun ring(context: Context, buzz: Boolean = true) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return
        try {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
                play()
            }
        } catch (_: Exception) { }
        if (buzz) try {
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        } catch (_: Exception) { }
    }
}
