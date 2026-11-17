package io.github.odlaver.tepi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

// Daily check for a newer Tepi
object UpdateCheck {
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val CHANNEL = "update"
    private const val ID = 7
    @Volatile private var busy = false

    fun on(p: Prefs) = p.bool("update.notify", true)

    // At most once a day, off the main thread
    fun maybe(c: Context, p: Prefs, found: (Release) -> Unit) {
        if (!on(p) || busy) return
        val now = System.currentTimeMillis()
        if (now - p.sp.getLong("update.last", 0L) < DAY) return
        busy = true
        Thread {
            try {
                val rel = Update.latest() ?: return@Thread
                p.sp.edit().putLong("update.last", now).apply()
                if (!Update.newer(rel.version, BuildConfig.VERSION_NAME)) return@Thread
                // One heads-up per version
                if (p.str("update.seen", "") == rel.version) return@Thread
                p.set("update.seen", rel.version)
                notify(c, rel)
                android.os.Handler(android.os.Looper.getMainLooper()).post { found(rel) }
            } finally { busy = false }
        }.start()
    }

    fun open(c: Context, rel: Release): PendingIntent = PendingIntent.getActivity(c, ID,
        Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // System notification stays until tapped
    private fun notify(c: Context, rel: Release) {
        if (!canNotify(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(c, CHANNEL)
        } else @Suppress("DEPRECATION") Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(c.getString(R.string.update_found, rel.version))
            .setContentText(c.getString(R.string.update_tap))
            .setContentIntent(open(c, rel))
            .setAutoCancel(true)
        try { nm.notify(ID, b.build()) } catch (_: Exception) { }
    }
}
