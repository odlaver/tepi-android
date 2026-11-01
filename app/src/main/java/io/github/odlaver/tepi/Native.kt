package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.TimerInfo

// HyperOS Super Island through focus notifications
object Native {
    private const val CHANNEL = "island"
    const val TIMER_ID = 7001
    const val ALERT_ID = 7002

    // Phone has the system island at all
    @SuppressLint("PrivateApi")
    fun hasIsland(): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", true)) return false
        return try {
            Class.forName("android.os.SystemProperties").getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)
                .invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (_: Exception) { false }
    }

    fun protocol(c: Context) = try { Settings.System.getInt(c.contentResolver, "notification_focus_protocol", 0) } catch (_: Exception) { 0 }

    fun canPost(c: Context) = c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    // "Featured notifications" switch for Tepi
    fun featured(c: Context) = HyperIslandNotification.isSupported(c)

    fun ready(c: Context) = hasIsland() && protocol(c) >= 3 && canPost(c) && featured(c)

    fun on(c: Context, prefs: Prefs) = prefs.bool("engine.native", true) && ready(c)

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_island), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    // White glyph on transparent, for island pictures
    private fun glyph(icon: Icon, color: Int = Color.WHITE): Bitmap {
        val s = 96
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Icons.draw(Canvas(b), icon, s / 2f, s / 2f, s * 0.8f, color)
        return b
    }

    private fun open(c: Context): PendingIntent =
        PendingIntent.getActivity(c, 0, Intent(c, Open::class.java).putExtra(Open.EXTRA, "timer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun post(c: Context, id: Int, island: HyperIslandNotification, title: String, text: String, ongoing: Boolean) {
        channel(c)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(c))
            .setCategory(if (ongoing) Notification.CATEGORY_STOPWATCH else Notification.CATEGORY_REMINDER)
            .addExtras(island.buildResourceBundle())
            .build()
        n.extras.putString("miui.focus.param", island.buildJsonParam())
        try { c.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)

    // Running timer: countdown or count-up in the big island
    fun timer(c: Context, t: Timers) {
        val k = t.kind ?: return cancel(c, TIMER_ID)
        val label = c.getString(when (k) {
            Timers.Kind.Focus -> R.string.t_focus
            Timers.Kind.Break -> R.string.t_break
            Timers.Kind.Countdown -> R.string.t_timer
            Timers.Kind.Stopwatch -> R.string.t_stopwatch
        })
        val now = System.currentTimeMillis()
        val b = HyperIslandNotification.Builder(c, "tepi_timer", label)
            .addPicture(HyperPicture("timer", glyph(Icon.Timer)))
            .setIslandConfig(priority = 2, dismissible = false)
            .setSmallIsland("timer")
        if (t.paused) {
            val shown = c.getString(R.string.t_paused) + " · " + Ui(c).clock(t.shownMs())
            b.setBaseInfo(title = label, content = shown, pictureKey = "timer")
            post(c, TIMER_ID, b, label, shown, true)
            return
        }
        if (k == Timers.Kind.Stopwatch) {
            val start = now - t.elapsed()
            b.setHighlightInfo(title = label, picKey = "timer", timer = TimerInfo(1, start, start, now))
            b.setBigIslandCountUp(start, "timer")
        } else {
            val end = now + t.remaining()
            b.setHighlightInfo(title = label, picKey = "timer", timer = TimerInfo(-1, end, t.totalMs, now))
            b.setBigIslandCountdown(end, "timer")
        }
        post(c, TIMER_ID, b, label, "", true)
    }

    // Short card: prayer, class, nudge, timer end
    fun alert(c: Context, title: String, body: String, icon: Icon, ms: Long) {
        val b = HyperIslandNotification.Builder(c, "tepi_alert", title)
            .addPicture(HyperPicture("alert", glyph(icon)))
            .setBaseInfo(title = title, content = body.ifEmpty { " " }, pictureKey = "alert")
            .setIslandConfig(priority = 2, timeout = ms.toInt(), dismissible = true, expandedTimeMs = ms.toInt())
            .setSmallIsland("alert")
            .setEnableFloat(true)
            .setTimeout(ms)
        post(c, ALERT_ID, b, title, body, false)
    }
}
