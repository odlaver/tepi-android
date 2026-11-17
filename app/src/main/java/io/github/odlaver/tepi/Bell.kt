package io.github.odlaver.tepi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    // Silent entry; ring() already made the sound
    fun note(c: Context, title: String, body: String) {
        if (!UpdateCheck.canNotify(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.remind_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mark).setContentTitle(title)
            .setContentText(body.ifEmpty { null }).setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(ID, n) } catch (_: Exception) { }
    }

    private const val CHANNEL = "remind"
    private const val ID = 8
}
